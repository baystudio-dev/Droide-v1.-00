package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class LocalMirrorAuthorityBootstrap(
    private val source: File,
    private val working: File,
    private val checkpoint: (String) -> Unit = {},
) {
    val directory = File(working.parentFile, ".${working.name}-sync-authority")
    private val parent = checkNotNull(working.parentFile)
    private val indexName = ".${working.name}-sync-baseline.tsv"
    private val conflictName = ".${working.name}-attachment-conflicts.txt"
    private val sourceIdentity = WorkspaceDirectoryIdentity(source)
    private data class Metadata(val bytes: ByteArray, val observation: AnchoredWorkspaceFiles.Observation)
    private data class HostBaseline(
        val files: Map<String, WorkspaceSyncManifest.Entry>,
        val directories: Set<String>,
        val typed: Boolean,
    ) {
        fun matches(state: MirrorMetadataAuthority.State) = files == state.baseline && directories == state.directoryHistory
    }

    suspend fun readLinkedState(
        guard: WorkspaceMutationGuard,
        authority: MirrorMetadataAuthority,
    ): MirrorMetadataAuthority.State = readLinkedState(guard, authority, executionReady = false)

    suspend fun readLinkedExecutionState(
        guard: WorkspaceMutationGuard,
        authority: MirrorMetadataAuthority,
    ): MirrorMetadataAuthority.State = readLinkedState(guard, authority, executionReady = true)

    private suspend fun readLinkedState(
        guard: WorkspaceMutationGuard,
        authority: MirrorMetadataAuthority,
        executionReady: Boolean,
    ): MirrorMetadataAuthority.State {
        guard.requireRoot(working)
        sourceIdentity.requireCurrent()
        require(authority.directory.absoluteFile == directory.absoluteFile) { "Synchronization authority belongs to another workspace" }
        val identity = WorkspaceDirectoryIdentity(directory)
        val state = if (executionReady) authority.loadExecutionReadyState() else authority.load()
        check(state.bootstrapPhase == MirrorMetadataAuthority.BootstrapPhase.LINKED) {
            "Synchronization bootstrap is incomplete; existing files were preserved"
        }
        val expected = linkBytes()
        AnchoredWorkspaceFiles(parent).use { host ->
            check(readOptional(host, indexName, MAX_METADATA_BYTES)?.bytes?.contentEquals(expected) == true) {
                "Established synchronization link is missing or changed; no workspace data was reset"
            }
        }
        identity.requireCurrent()
        sourceIdentity.requireCurrent()
        guard.requireRoot(working)
        return state
    }

    suspend fun prepare(): MirrorMetadataAuthority = withContext(Dispatchers.IO) {
        WorkspaceFileMutationLocks.forRoot(working).withLock {
            sourceIdentity.requireCurrent()
            require(!PathSecurity.contains(source, working) && !PathSecurity.contains(working, source) &&
                !PathSecurity.contains(source, directory) && !PathSecurity.contains(working, directory)) {
                "CLI working copy and synchronization authority must be separate from the canonical source"
            }
            AnchoredWorkspaceFiles(parent).use { host ->
                val existingWorking = host.kind(working.name)
                val existingAuthority = host.kind(directory.name)
                val store = when {
                    existingWorking == AnchoredWorkspaceFiles.Kind.ABSENT -> {
                        check(existingAuthority == AnchoredWorkspaceFiles.Kind.ABSENT &&
                            host.kind(indexName) == AnchoredWorkspaceFiles.Kind.ABSENT &&
                            host.kind(conflictName) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                            "CLI working copy disappeared but its metadata remains; restore it before retrying. Nothing was recreated."
                        }
                        MirrorMetadataAuthority.createForNewWorkingCopy(directory, source, working, requireExternalLink = true)
                    }
                    existingWorking != AnchoredWorkspaceFiles.Kind.DIRECTORY -> error("CLI working copy is not a directory; all existing data was preserved")
                    existingAuthority == AnchoredWorkspaceFiles.Kind.DIRECTORY -> MirrorMetadataAuthority(directory, source, working)
                    existingAuthority != AnchoredWorkspaceFiles.Kind.ABSENT -> error("Synchronization authority path is unsafe; all existing data was preserved")
                    else -> {
                        val previous = readOptional(host, indexName, MAX_METADATA_BYTES)
                            ?: error("Existing CLI working copy has no authoritative baseline; its data was preserved")
                        val baseline = hostBaseline(previous.bytes)
                        val conflicts = readOptional(host, conflictName, 65_536)?.let { legacyConflicts(it.bytes) }.orEmpty()
                        val identity = java.nio.file.Files.readAttributes(working.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey().toString()
                        if (baseline.typed) {
                            MirrorMetadataAuthority.importHostBaseline(directory, source, working, identity, baseline.files, conflicts, baseline.directories)
                        } else {
                            MirrorMetadataAuthority.importLegacyHostBaseline(directory, source, working, identity, baseline.files, conflicts)
                        }
                    }
                }
                checkpoint("authority-prepared")
                var state = store.load()
                val expected = linkBytes()
                var current = readOptional(host, indexName, MAX_METADATA_BYTES)
                if (state.bootstrapPhase == MirrorMetadataAuthority.BootstrapPhase.LINKED) {
                    check(current != null && current.bytes.contentEquals(expected)) {
                        "Established synchronization link is missing or changed; no legacy fallback or workspace reset was performed"
                    }
                    return@withLock store
                }
                check(store.operations(limit = 1).isEmpty()) { "Filesystem intents exist during bootstrap; recovery is required" }
                AnchoredWorkspaceFiles(directory).use { authority ->
                    if (current != null && !current.bytes.contentEquals(expected)) {
                        check(authority.kind(LEGACY_BASELINE) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                            "Both current and retained legacy metadata exist; both were preserved for explicit recovery"
                        }
                        check(hostBaseline(current.bytes).matches(state)) { "Host baseline changed after import; both versions were preserved" }
                        checkpoint("before-legacy-baseline-retire")
                        capture(host, indexName, current, authority, LEGACY_BASELINE)
                        checkpoint("legacy-baseline-retired")
                        current = null
                    }
                    if (current == null) {
                        val previous = readOptional(authority, LEGACY_BASELINE, MAX_METADATA_BYTES)
                        if (previous != null) check(hostBaseline(previous.bytes).matches(state)) {
                            "Retained legacy baseline does not match the prepared authority; recovery is required"
                        } else check(state.baseline.isEmpty() && state.directoryHistory.isEmpty()) {
                            "Imported host baseline is missing; bootstrap stopped without changing CLI files"
                        }
                        val oldConflicts = readOptional(host, conflictName, 65_536)
                        val savedConflicts = readOptional(authority, LEGACY_CONFLICTS, 65_536)
                        check(oldConflicts == null || savedConflicts == null) { "Both conflict metadata versions exist; both were preserved" }
                        val conflicts = oldConflicts ?: savedConflicts
                        check((conflicts?.let { legacyConflicts(it.bytes) }.orEmpty()) == state.conflicts) {
                            "Attachment conflict metadata is missing or changed; all existing versions were preserved"
                        }
                        if (oldConflicts != null) {
                            capture(host, conflictName, oldConflicts, authority, LEGACY_CONFLICTS)
                            checkpoint("legacy-conflicts-retired")
                        }
                        val preparedRoot = File(directory, "prepared")
                        AnchoredWorkspaceFiles(preparedRoot).use { staged ->
                            val name = "bootstrap-${UUID.randomUUID()}"
                            staged.createNew(name).use { output -> output.write(expected); output.force(420) }
                            checkpoint("link-staged")
                            requireDurable(staged.moveNoReplaceDurable(name, host, indexName), "publishing the host authority link")
                        }
                        checkpoint("link-published")
                    }
                }
                check(readOptional(host, indexName, MAX_METADATA_BYTES)?.bytes?.contentEquals(expected) == true) {
                    "Synchronization link changed before bootstrap completion; data was preserved"
                }
                state = store.completeExternalLink(state.revision)
                check(state.bootstrapPhase == MirrorMetadataAuthority.BootstrapPhase.LINKED &&
                    readOptional(host, indexName, MAX_METADATA_BYTES)?.bytes?.contentEquals(expected) == true) {
                    "Synchronization link changed during completion; existing data was preserved"
                }
                checkpoint("linked")
                sourceIdentity.requireCurrent()
                store
            }
        }
    }

    private suspend fun capture(host: AnchoredWorkspaceFiles, name: String, expected: Metadata,
                                authority: AnchoredWorkspaceFiles, retained: String) {
        requireDurable(host.moveNoReplaceDurable(name, authority, retained), "retaining legacy host metadata")
        val captured = try { readOptional(authority, retained, MAX_METADATA_BYTES) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        val before = expected.observation.attributes
        val after = captured?.observation?.attributes
        if (captured != null && after != null && captured.bytes.contentEquals(expected.bytes) &&
            before.fileKey() == after.fileKey() && before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime()) return
        val restore = authority.moveNoReplaceDurable(retained, host, name)
        if (restore.published) requireDurable(restore, "restoring changed legacy metadata")
        else check(restore.error == 17) { "Cannot restore changed legacy metadata (errno=${restore.error}); inspect $directory" }
        error("Legacy metadata changed during retirement; current and retained versions were preserved at $parent and $directory")
    }

    private suspend fun readOptional(files: AnchoredWorkspaceFiles, name: String, limit: Int): Metadata? {
        if (files.kind(name) == AnchoredWorkspaceFiles.Kind.ABSENT) return null
        return files.openRead(name).use { input ->
            val before = input.observe()
            require(before.attributes.size() <= limit.toLong()) { "Host synchronization metadata exceeds its limit; data was preserved" }
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                check(count <= limit - bytes.size()) { "Host synchronization metadata grew during inspection; data was preserved" }
                bytes.write(buffer, 0, count)
            }
            val after = input.observe()
            check(before.attributes.fileKey() == after.attributes.fileKey() && before.attributes.size() == after.attributes.size() &&
                before.attributes.lastModifiedTime() == after.attributes.lastModifiedTime() && before.changed == after.changed) {
                "Host synchronization metadata changed during inspection; data was preserved"
            }
            Metadata(bytes.toByteArray(), after)
        }
    }

    private fun hostBaseline(bytes: ByteArray): HostBaseline {
        val stored = decode(bytes)
        val version = stored.substringBefore('\n').substringBefore('\t')
        val typed = version == TYPED_HEADER
        val body = if (version == LEGACY_HEADER || typed) {
            val header = stored.substringBefore('\n').split('\t')
            val content = stored.substringAfter('\n', "")
            require('\n' in stored && header.size == 3 && header[1] == binding() && header[2] == hash(content.toByteArray(Charsets.UTF_8))) {
                "Host baseline checksum or identity is invalid; all CLI files were preserved"
            }
            content
        } else {
            require(stored.isNotBlank() && !stored.startsWith("DROIDE-")) { "Missing, empty or newer host baseline cannot be imported; recovery is required" }
            stored
        }
        val entries = linkedMapOf<String, WorkspaceSyncManifest.Entry>()
        val directories = linkedSetOf<String>()
        for (line in body.lineSequence().filter { it.isNotBlank() }) {
            if (typed && line.startsWith("D\t")) {
                val path = line.removePrefix("D\t")
                require(directories.size < 40_000 && AgentWorkspacePathPolicy.visible(path) && directories.add(path)) {
                    "Invalid or duplicate host directory history; all existing data was preserved"
                }
            } else {
                val row = WorkspaceSyncManifest.parseTrustedOrEmpty(line, 1).entries.singleOrNull()
                require(row != null && entries.size < 20_000 && row.value.mode != null &&
                    AgentWorkspacePathPolicy.visible(row.key) && entries.put(row.key, row.value) == null) {
                    "Invalid or duplicate host file history; all existing data was preserved"
                }
            }
        }
        for (path in entries.keys + directories) {
            require(path !in entries || path !in directories) { "Host baseline contains a file/directory identity collision" }
            var slash = path.indexOf('/')
            while (slash >= 0) {
                require(path.substring(0, slash) !in entries) { "Legacy baseline contains a file/directory identity collision" }
                slash = path.indexOf('/', slash + 1)
            }
        }
        return HostBaseline(entries, directories, typed)
    }

    private fun legacyConflicts(bytes: ByteArray): Set<String> {
        val paths = decode(bytes).lineSequence().filter { it.isNotEmpty() }.toList()
        require(paths.size <= 128 && paths.size == paths.toSet().size && paths.all(AgentWorkspacePathPolicy::visible)) {
            "Invalid legacy attachment conflict metadata; all existing data was preserved"
        }
        return paths.toSet()
    }

    private suspend fun linkBytes(): ByteArray {
        val marker = AnchoredWorkspaceFiles(directory).use { authority ->
            checkNotNull(readOptional(authority, "identity", 4_096)).bytes
        }
        return "$LINK_HEADER\t${binding()}\t${hash(marker)}\n".toByteArray(Charsets.UTF_8)
    }

    private fun binding(): String {
        fun key(file: File) = java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey().toString()
        return hash("${source.canonicalPath}\n${key(source)}\n${key(working)}".toByteArray(Charsets.UTF_8))
    }

    private fun decode(bytes: ByteArray) = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun requireDurable(receipt: AnchoredWorkspaceFiles.MoveReceipt, operation: String) {
        check(receipt.durable) { "Failed while $operation (published=${receipt.published}, errno=${receipt.error}); data was preserved at $parent and $directory" }
    }

    private companion object {
        const val MAX_METADATA_BYTES = 16 * 1024 * 1024
        const val LEGACY_HEADER = "DROIDE-LOCAL-BASELINE-1"
        const val TYPED_HEADER = "DROIDE-LOCAL-BASELINE-2"
        const val LINK_HEADER = "DROIDE-MIRROR-LINK-2"
        const val LEGACY_BASELINE = "legacy-baseline.tsv"
        const val LEGACY_CONFLICTS = "legacy-conflicts.txt"
    }
}
