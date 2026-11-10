package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Independent recovery witnesses; shared-UID guest isolation is not provided by these files.
internal class ProjectCopyMembership(
    private val root: File,
    private val rootKey: String,
    private val durability: ProjectCopyJournal.Durability,
    private val authorityHome: File? = null,
) {
    @Serializable private data class Binding(val version: Int = 1, val rootPath: String,
        val rootKey: String, val metadataKey: String, val journalKey: String, val indexKey: String,
        val authorityKey: String)
    @Serializable data class Entry(val id: String, val recordKey: String, val intentHash: String)

    private val metadata = File(root, ".droide")
    private val journal = File(metadata, "copy-recovery")
    private val authority = authorityHome?.let { home ->
        require(home.absoluteFile == home.canonicalFile && !home.toPath().startsWith(root.toPath())) {
            "Copy recovery authority must be outside the workspace"
        }
        File(home, ProjectCopyEvidence.sha256(root.path.toByteArray(Charsets.UTF_8)))
    } ?: metadata
    private val index = File(authority, "copy-recovery-membership")
    private val bindingFile = File(authority, "copy-recovery-membership.json")
    private val archive = File(metadata, "copy-recovery-archive")
    private val completed = File(authority, "copy-recovery-membership-completed")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }

    fun verifyStorage() {
        try { binding()?.let(::requireBinding) }
        catch (failure: Exception) {
            if (failure is java.util.concurrent.CancellationException) throw failure
            throw ProjectCopyJournal.Blocked(null,
                "Copy journal metadata membership needs recovery. Existing work was preserved. " + failure.message.orEmpty().take(160)
            ).also { it.initCause(failure) }
        }
    }

    fun admit(entry: Entry) {
        validate(entry)
        val bound = binding() ?: initialize()
        requireBinding(bound)
        AnchoredWorkspaceFiles(authority).use { parent ->
            parent.openRead(bindingFile.name).use { input ->
                val identity = input.observe().attributes.fileKey().toString()
                input.forceContents()
                parent.forceDirectory()
                check(ProjectCopyEvidence.identity(bindingFile).key == identity) { "Copy enrollment changed while becoming durable" }
            }
        }
        requireBinding(bound)
        write(index, "${entry.id}.json", json.encodeToString(entry))
        requireBinding(bound)
        check(readEntry(index, "${entry.id}.json") == entry) { "Copy membership changed during admission" }
    }

    fun verify(entries: Map<String, Entry>, required: Set<String>,
               retired: (Entry) -> String): List<Pair<Entry, String>> {
        val bound = binding()
        if (bound == null) {
            check(required.isEmpty()) { "Admitted copy membership disappeared; existing work was preserved" }
            return emptyList()
        }
        requireBinding(bound)
        val seen = mutableSetOf<String>()
        val completed = mutableListOf<Pair<Entry, String>>()
        AnchoredWorkspaceFiles(index).use { files ->
            files.forEachChild { name ->
                check(seen.size < 256 && name.endsWith(".json")) { "Copy membership is full or contains unknown entries" }
                val entry = readEntry(index, name)
                check(name == "${entry.id}.json" && seen.add(entry.id)) { "Invalid copy membership identity" }
                val active = entries[entry.id]
                if (active == null) {
                    completed += entry to retired(entry)
                } else check(active == entry && entry.id in required) {
                    "Admitted copy record or membership sidecar was replaced or changed (${entry.id})"
                }
            }
        }
        check(seen.containsAll(required)) { "Admitted copy membership is missing; inspect recovery before a new copy" }
        requireBinding(bound)
        return completed
    }

    fun retire(entry: Entry, archiveKey: String) {
        val bound = checkNotNull(binding()) { "Copy membership enrollment disappeared" }
        requireBinding(bound)
        val name = "${entry.id}.json"
        check(ProjectCopyEvidence.identity(archive).key == archiveKey) { "Copy membership archive changed" }
        AnchoredWorkspaceFiles(authority).use { parent ->
            when (parent.kind(completed.name)) {
                AnchoredWorkspaceFiles.Kind.ABSENT -> check(parent.createDirectory(completed.name).durable) {
                    "Completed copy membership storage creation was not confirmed"
                }
                AnchoredWorkspaceFiles.Kind.DIRECTORY -> Unit
                else -> error("Completed copy membership storage is unsafe")
            }
        }
        AnchoredWorkspaceFiles(index).use { active ->
            AnchoredWorkspaceFiles(completed).use { retained ->
                if (active.kind(name) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                    check(readEntry(completed, name) == entry) { "Retired copy membership disappeared or changed" }
                } else {
                    check(readEntry(index, name) == entry) { "Copy membership changed before retirement" }
                    active.moveNoReplace(name, retained, name)
                }
                durability.forceDirectory(index)
                durability.forceDirectory(completed)
                check(active.kind(name) == AnchoredWorkspaceFiles.Kind.ABSENT && readEntry(completed, name) == entry) {
                    "Copy membership retirement needs recovery; both locations were preserved"
                }
                requireBinding(bound)
                check(ProjectCopyEvidence.identity(archive).key == archiveKey) { "Copy membership archive changed" }
            }
        }
    }

    private fun initialize(): Binding {
        check(ProjectCopyEvidence.root(root) == (root to rootKey)) { "Copy membership root changed" }
        ensureAuthority()
        AnchoredWorkspaceFiles(authority).use { parent ->
            check(parent.kind(bindingFile.name) == AnchoredWorkspaceFiles.Kind.ABSENT &&
                parent.kind(index.name) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                "Copy membership initialization is incomplete; existing metadata was preserved"
            }
            check(parent.createDirectory(index.name).durable) { "Copy membership directory creation was not confirmed" }
            val bound = Binding(rootPath = root.path, rootKey = rootKey,
                metadataKey = ProjectCopyEvidence.identity(metadata).key, journalKey = ProjectCopyEvidence.identity(journal).key,
                indexKey = parent.directoryKey(index.name), authorityKey = parent.directoryKey())
            write(authority, bindingFile.name, json.encodeToString(bound))
            durability.forceDirectory(root)
            requireBinding(bound)
            check(binding() == bound) { "Copy membership enrollment changed" }
            return bound
        }
    }

    private fun binding(): Binding? {
        if (ProjectCopyEvidence.identityOrAbsent(authority) == null) return null
        AnchoredWorkspaceFiles(authority).use { parent ->
            if (parent.kind(bindingFile.name) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                check(parent.kind(index.name) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                    "Copy membership enrollment is missing or incomplete; recovery is required"
                }
                return null
            }
        }
        return decode<Binding>(read(authority, bindingFile.name)).also {
            check(it.version == 1 && it.rootPath == root.path && it.rootKey == rootKey) {
                "Copy membership belongs to a different project"
            }
        }
    }

    private fun requireBinding(bound: Binding) {
        check(ProjectCopyEvidence.root(root) == (root to rootKey)) { "Copy membership root identity changed" }
        AnchoredWorkspaceFiles(metadata).use { parent ->
            check(parent.directoryKey() == bound.metadataKey && parent.directoryKey(journal.name) == bound.journalKey) {
                "Admitted copy recovery storage disappeared or was replaced; existing work was preserved"
            }
        }
        AnchoredWorkspaceFiles(authority).use { parent ->
            check(parent.directoryKey() == bound.authorityKey && parent.directoryKey(index.name) == bound.indexKey) {
                "Copy journal membership authority disappeared or was replaced"
            }
        }
        check(decode<Binding>(read(authority, bindingFile.name)) == bound) { "Copy membership enrollment changed" }
    }

    private fun ensureAuthority() {
        val home = authorityHome ?: return
        fun create(parent: File, name: String) = AnchoredWorkspaceFiles(parent).use { files ->
            val created = files.createDirectory(name)
            check(created.durable || !created.published && created.error == 17 &&
                files.kind(name) == AnchoredWorkspaceFiles.Kind.DIRECTORY) {
                "Copy recovery authority creation was not confirmed or is not an ordinary directory"
            }
            files.forceDirectory()
            files.directoryKey(name)
        }
        create(requireNotNull(home.parentFile), home.name)
        create(home, authority.name)
    }

    private fun readEntry(parent: File, name: String): Entry = decode<Entry>(read(parent, name)).also(::validate)

    private fun validate(entry: Entry) {
        require(ProjectCopyEvidence.UUID_PATTERN.matches(entry.id) &&
            ProjectCopyEvidence.SHA256_PATTERN.matches(entry.intentHash) && entry.recordKey.length in 1..512)
    }

    private inline fun <reified T> decode(text: String): T = json.decodeFromString<T>(text).also {
        check(json.encodeToString(it) == text) { "Copy membership metadata is corrupt or unsupported" }
    }

    private fun write(parent: File, name: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        check(bytes.size in 1..MAX_BYTES)
        AnchoredWorkspaceFiles(parent).use { files ->
            files.createNew(name).use { output ->
                val identity = output.observe().attributes.fileKey().toString()
                output.write(bytes)
                output.openWriteChannel().use { durability.forceFile(it) }
                durability.forceDirectory(parent)
                check(read(parent, name) == text) { "Copy membership changed while becoming durable" }
                check(ProjectCopyEvidence.identity(File(parent, name)).key == identity &&
                    output.observe().attributes.fileKey().toString() == identity) { "Copy membership identity changed" }
            }
        }
    }

    private fun read(parent: File, name: String): String = AnchoredWorkspaceFiles(parent).use { files ->
        files.openRead(name).use { input ->
            val before = input.observe()
            check(before.attributes.size() in 1..MAX_BYTES.toLong()) { "Copy membership metadata is empty or oversized" }
            val bytes = ByteArray(before.attributes.size().toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                check(count > 0) { "Copy membership was truncated while reading" }
                offset += count
            }
            check(input.read() == -1) { "Copy membership grew while reading" }
            val after = input.observe()
            check(before.attributes.fileKey() == after.attributes.fileKey() &&
                before.attributes.size() == after.attributes.size() &&
                before.attributes.lastModifiedTime() == after.attributes.lastModifiedTime() && before.changed == after.changed &&
                ProjectCopyEvidence.identity(File(parent, name)).key == before.attributes.fileKey().toString()) {
                "Copy membership changed while reading"
            }
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }
    }

    private companion object { const val MAX_BYTES = 4096 }
}
