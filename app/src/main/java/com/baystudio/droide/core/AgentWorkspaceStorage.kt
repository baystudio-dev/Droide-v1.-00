package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class AgentWorkspaceStorage @JvmOverloads constructor(
    private val project: File,
    private val legacyDirectory: File,
    val directory: File,
    private val checkpoint: (String) -> Unit = {},
) {
    private data class Entry(val suffix: String, val key: String?, val digest: String?)
    @Volatile private var prepared = false
    private val suffixes = listOf("workspace", "attachment-conflicts.txt", "sync-baseline.tsv", "sync-authority")
    private val journalName = ".${directory.name}-storage-migration"

    suspend fun prepare() {
        if (prepared) return
        withContext(Dispatchers.IO) {
            WorkspaceFileMutationLocks.withRoot(project) { guard ->
                if (prepared) return@withRoot
                require(!PathSecurity.contains(project, directory) && !PathSecurity.contains(directory, project) &&
                    !PathSecurity.contains(project, legacyDirectory) && !PathSecurity.contains(legacyDirectory, project)) {
                    "CLI workspace storage must be separate from the project"
                }
                PathSecurity.safeLeafName(directory.name)
                PathSecurity.safeLeafName(legacyDirectory.name)
                checkNotNull(openHostParent(checkNotNull(directory.parentFile), create = true)).use { target ->
                    if (legacyDirectory.absoluteFile != directory.absoluteFile) {
                        when (target.kind(journalName)) {
                            AnchoredWorkspaceFiles.Kind.ABSENT -> migrateIfPresent(target, guard.rootIdentity)
                            AnchoredWorkspaceFiles.Kind.DIRECTORY -> verifyCompleted(target, guard.rootIdentity)
                            else -> error("CLI migration journal is unsafe; all paths were preserved")
                        }
                    }
                    require(target.kind(directory.name) in setOf(AnchoredWorkspaceFiles.Kind.ABSENT, AnchoredWorkspaceFiles.Kind.DIRECTORY)) {
                        "Persistent CLI workspace is unavailable; existing files were preserved"
                    }
                }
                guard.requireRoot(project)
                prepared = true
            }
        }
    }

    private suspend fun migrateIfPresent(target: AnchoredWorkspaceFiles, projectKey: String) {
        val parent = checkNotNull(legacyDirectory.parentFile)
        (openHostParent(parent, create = false) ?: return).use { old ->
            val rootKind = old.kind(legacyDirectory.name)
            if (rootKind == AnchoredWorkspaceFiles.Kind.ABSENT) {
                check(suffixes.drop(1).all { old.kind(oldName(it)) == AnchoredWorkspaceFiles.Kind.ABSENT }) {
                    "Legacy CLI metadata remains without a migration intent; all versions were preserved for explicit recovery"
                }
                return
            }
            require(rootKind == AnchoredWorkspaceFiles.Kind.DIRECTORY) { "Previous CLI workspace is not a directory; it was preserved" }
            val entries = suffixes.map { capture(old, oldName(it), it) }
            check(target.kind(directory.name) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                "Both previous and persistent CLI workspaces exist; both were preserved"
            }
            for (suffix in suffixes.drop(1)) require(target.kind(newName(suffix)) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                "Persistent CLI destination or metadata already exists; both versions were preserved"
            }
            WorkspaceProcessPathGuard.requireUnused(legacyDirectory)
            val intent = encode(entries, old.directoryKey(), target.directoryKey(), projectKey)
            val stage = ".cli-storage-${UUID.randomUUID()}"
            durable(target.createDirectory(stage), "creating migration intent staging")
            AnchoredWorkspaceFiles(File(directory.parentFile, stage)).use { pending ->
                pending.createNew("intent").use { it.write(intent); it.force(420) }
                pending.forceDirectory()
            }
            val journalKey = target.directoryKey(stage)
            checkpoint("intent-staged")
            durable(target.moveNoReplaceDurable(stage, target, journalName), "publishing migration intent")
            check(target.directoryKey(journalName) == journalKey) { "CLI migration intent directory changed during publication; data was preserved" }
            checkpoint("intent-published")
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                check(capture(old, oldName(entry.suffix), entry.suffix) == entry) { "CLI source changed after intent admission; data was preserved" }
                if (entry.key == null) {
                    check(target.kind(newName(entry.suffix)) == AnchoredWorkspaceFiles.Kind.ABSENT) { "CLI metadata appeared concurrently; it was preserved" }
                    continue
                }
                fun moveEntry() {
                    checkpoint("before-entry:${entry.suffix}")
                    durable(old.moveNoReplaceDurable(oldName(entry.suffix), target, newName(entry.suffix)), "moving ${entry.suffix}")
                }
                if (entry.suffix == "workspace") {
                    WorkspaceFileMutationLocks.withRoot(legacyDirectory) { workingGuard ->
                        currentCoroutineContext().ensureActive()
                        check(workingGuard.rootIdentity == entry.key) {
                            "CLI working copy changed before migration; all locations were preserved"
                        }
                        workingGuard.requireRoot(legacyDirectory)
                        WorkspaceProcessPathGuard.requireUnused(legacyDirectory)
                        moveEntry()
                    }
                } else moveEntry()
                checkpoint("entry-moved:${entry.suffix}")
                check(capture(target, newName(entry.suffix), entry.suffix) == entry) {
                    "CLI entry changed during capture; all current and retained locations were preserved for recovery"
                }
            }
            for (entry in entries) check(capture(old, oldName(entry.suffix), entry.suffix).key == null &&
                capture(target, newName(entry.suffix), entry.suffix) == entry) { "CLI migration changed before completion; all versions were preserved" }
            AnchoredWorkspaceFiles(File(directory.parentFile, journalName)).use { journal ->
                check(journal.directoryKey() == journalKey && read(journal, "intent", 65_536).contentEquals(intent)) {
                    "CLI migration intent changed before completion; all locations were preserved"
                }
                val name = "complete-${UUID.randomUUID()}"
                journal.createNew(name).use { it.write(receipt(intent)); it.force(420) }
                checkpoint("receipt-staged")
                durable(journal.moveNoReplaceDurable(name, journal, "complete"), "publishing migration completion")
            }
            checkpoint("completed")
        }
    }

    private suspend fun verifyCompleted(target: AnchoredWorkspaceFiles, projectKey: String) {
        AnchoredWorkspaceFiles(File(directory.parentFile, journalName)).use { journal ->
            check(journal.kind("complete") != AnchoredWorkspaceFiles.Kind.ABSENT) {
                "CLI storage migration was interrupted. Inspect ${legacyDirectory.absolutePath}, ${directory.absolutePath}, and ${File(directory.parentFile, journalName).absolutePath}; no automatic replay or deletion was performed."
            }
            val bytes = read(journal, "intent", 65_536)
            val rows = decode(bytes).split('\n')
            check(rows.size == 13 && rows[0] == "DROIDE-CLI-STORAGE-MOVE-1" && rows.last().isEmpty()) { "CLI migration intent is corrupt; data was preserved" }
            val body = rows.take(11).joinToString("\n", postfix = "\n")
            check(rows[11] == "SHA256\t${hash(body.toByteArray(Charsets.UTF_8))}") { "CLI migration checksum changed; data was preserved" }
            val expected = listOf(project.canonicalPath, projectKey, legacyDirectory.absolutePath, directory.absolutePath)
            check(rows.subList(1, 5) == expected.map(::encoded) && rows[6] == encoded(target.directoryKey())) {
                "CLI migration belongs to another project or storage identity; data was preserved"
            }
            val root = rows[7].split('\t')
            check(root.size == 3 && root[0] == "workspace" && root[1].isNotEmpty() && root[2].isEmpty()) { "CLI migration working-copy identity is invalid" }
            check(read(journal, "complete", 4_096).contentEquals(receipt(bytes))) { "CLI migration completion is corrupt; existing data was preserved" }
            for ((index, suffix) in suffixes.withIndex()) {
                val fields = rows[7 + index].split('\t')
                check(fields.size == 3 && fields[0] == suffix &&
                    (fields[1].isEmpty() || Base64.getUrlDecoder().decode(fields[1]).isNotEmpty()) &&
                    (if (index == 0 || index == 3) fields[2].isEmpty() else
                        (fields[1].isEmpty() && fields[2].isEmpty()) || (fields[1].isNotEmpty() && Regex("[0-9a-f]{64}").matches(fields[2])))) {
                    "CLI migration entry is invalid; data was preserved"
                }
            }
            check(target.kind(directory.name) == AnchoredWorkspaceFiles.Kind.DIRECTORY && encoded(target.directoryKey(directory.name)) == root[1]) {
                "Established CLI working copy disappeared or changed; nothing was recreated"
            }
            val oldParent = checkNotNull(legacyDirectory.parentFile)
            openHostParent(oldParent, create = false)?.use { old ->
                check(suffixes.all { old.kind(oldName(it)) == AnchoredWorkspaceFiles.Kind.ABSENT }) {
                    "Legacy CLI data reappeared after completed migration; both locations were preserved"
                }
            }
            journal.forceDirectory()
            target.forceDirectory()
        }
    }

    private suspend fun capture(parent: AnchoredWorkspaceFiles, name: String, suffix: String): Entry = when (parent.kind(name)) {
        AnchoredWorkspaceFiles.Kind.ABSENT -> Entry(suffix, null, null)
        AnchoredWorkspaceFiles.Kind.DIRECTORY -> {
            require(suffix == "workspace" || suffix == "sync-authority") { "CLI metadata is not an ordinary file; it was preserved" }
            Entry(suffix, parent.directoryKey(name), null)
        }
        AnchoredWorkspaceFiles.Kind.FILE -> {
            require(suffix != "workspace" && suffix != "sync-authority") { "CLI working storage is not a directory; it was preserved" }
            parent.openRead(name).use { input ->
                val before = input.observe()
                require(before.attributes.size() <= 16L * 1024 * 1024) { "CLI metadata exceeds its limit; it was preserved" }
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val size = input.read(buffer)
                    if (size < 0) break
                    count += size
                    check(count <= before.attributes.size()) { "CLI metadata grew during inspection; it was preserved" }
                    digest.update(buffer, 0, size)
                }
                check(count == before.attributes.size() && unchanged(before, input.observe()) &&
                    unchanged(before, parent.openRead(name).use { it.observe() })) { "CLI metadata changed during inspection; all versions were preserved" }
                Entry(suffix, before.attributes.fileKey().toString(), hex(digest.digest()))
            }
        }
        else -> throw IllegalArgumentException("CLI storage or metadata contains an unsafe entry; it was preserved")
    }

    private suspend fun read(parent: AnchoredWorkspaceFiles, name: String, limit: Int): ByteArray = parent.openRead(name).use { input ->
        val before = input.observe()
        check(before.attributes.size() <= limit) { "CLI migration metadata exceeds its limit" }
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            check(count <= limit - bytes.size()) { "CLI migration metadata grew during inspection" }
            bytes.write(buffer, 0, count)
        }
        check(unchanged(before, input.observe()) && unchanged(before, parent.openRead(name).use { it.observe() })) { "CLI migration metadata changed during inspection" }
        bytes.toByteArray()
    }

    private fun encode(entries: List<Entry>, oldParent: String, newParent: String, projectKey: String): ByteArray {
        val body = buildString {
            append("DROIDE-CLI-STORAGE-MOVE-1\n")
            listOf(project.canonicalPath, projectKey, legacyDirectory.absolutePath, directory.absolutePath, oldParent, newParent)
                .forEach { append(encoded(it)); append('\n') }
            for (entry in entries) append("${entry.suffix}\t${entry.key?.let(::encoded).orEmpty()}\t${entry.digest.orEmpty()}\n")
        }
        return (body + "SHA256\t${hash(body.toByteArray(Charsets.UTF_8))}\n").toByteArray(Charsets.UTF_8)
    }

    private fun openHostParent(parent: File, create: Boolean): AnchoredWorkspaceFiles? {
        val peers = listOf(project, checkNotNull(legacyDirectory.parentFile), checkNotNull(directory.parentFile))
            .map { it.canonicalFile.toPath() }
        var shared = peers.first()
        while (peers.any { !it.startsWith(shared) }) shared = checkNotNull(shared.parent)
        var ancestor = parent.absoluteFile
        while (ancestor.canonicalFile.toPath() != shared) ancestor = checkNotNull(ancestor.parentFile) {
            "CLI storage has no verified common parent"
        }
        val parts = ancestor.toPath().relativize(parent.absoluteFile.toPath()).map { PathSecurity.safeLeafName(it.toString()) }
        AnchoredWorkspaceFiles(ancestor).use { anchor ->
            var relative = ""
            for (name in parts) {
                relative = if (relative.isEmpty()) name else "$relative/$name"
                when (anchor.kind(relative)) {
                    AnchoredWorkspaceFiles.Kind.ABSENT -> {
                        if (!create) return null
                        val result = anchor.createDirectory(relative)
                        if (!result.published && result.error == 17) require(anchor.kind(relative) == AnchoredWorkspaceFiles.Kind.DIRECTORY) {
                            "CLI storage parent changed; existing data was preserved"
                        } else durable(result, "creating CLI storage parents")
                    }
                    AnchoredWorkspaceFiles.Kind.DIRECTORY -> Unit
                    else -> throw IllegalArgumentException("CLI storage parent contains an unsafe entry; all existing data was preserved")
                }
            }
            return anchor.openDirectory(relative)
        }
    }

    private fun oldName(suffix: String) = if (suffix == "workspace") legacyDirectory.name else ".${legacyDirectory.name}-$suffix"
    private fun newName(suffix: String) = if (suffix == "workspace") directory.name else ".${directory.name}-$suffix"
    private fun durable(receipt: AnchoredWorkspaceFiles.MoveReceipt, operation: String) {
        check(receipt.durable) { "CLI migration failed while $operation (published=${receipt.published}, errno=${receipt.error}); all paths were preserved for recovery" }
    }
    private fun unchanged(a: AnchoredWorkspaceFiles.Observation, b: AnchoredWorkspaceFiles.Observation) =
        a.attributes.fileKey() == b.attributes.fileKey() && a.attributes.size() == b.attributes.size() &&
            a.attributes.lastModifiedTime() == b.attributes.lastModifiedTime() && a.changed == b.changed
    private fun encoded(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun receipt(intent: ByteArray) = "DROIDE-CLI-STORAGE-MOVE-DONE-1\t${hash(intent)}\n".toByteArray(Charsets.UTF_8)
    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun decode(bytes: ByteArray) = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}
