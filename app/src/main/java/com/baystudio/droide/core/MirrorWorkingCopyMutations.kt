package com.baystudio.droide.core

import java.io.File
import java.nio.file.FileSystemException
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class MirrorWorkingCopyMutations(
    private val source: File,
    private val working: File,
    private val authority: MirrorMetadataAuthority,
    private val checkpoint: (String, MirrorMetadataAuthority.Intent) -> Unit = { _, _ -> },
) {
    data class Result(val path: String, val applied: Boolean, val conflict: String?, val retained: File?, val stable: File? = null)
    private val retainedRoot = File(authority.directory, "retained")
    private val preparedRoot = File(authority.directory, "prepared")

    suspend fun apply(guard: WorkspaceMutationGuard, expectedRevision: Long,
                      proposals: List<MirrorMetadataAuthority.Proposal>): List<Result> {
        guard.requireRoot(working)
        val context = currentCoroutineContext()
        context.ensureActive()
        var state = authority.load()
        check(state.revision == expectedRevision) { "Synchronization revision changed; no working-copy file was mutated" }
        for (proposal in proposals) if (proposal.before.type == MirrorMetadataAuthority.NodeType.FILE) {
            check(state.baseline[proposal.path] == WorkspaceSyncManifest.Entry(proposal.before.sha256, proposal.before.mode)) {
                "A working-copy file is not the published baseline: ${proposal.path}; it was preserved"
            }
        } else if (proposal.before.type == MirrorMetadataAuthority.NodeType.ABSENT) {
            check(state.baseline.keys.none { it == proposal.path || it.startsWith("${proposal.path}/") } &&
                state.directoryHistory.none { it == proposal.path || it.startsWith("${proposal.path}/") }) {
                "A missing working-copy entry is an unpublished CLI deletion: ${proposal.path}; it was preserved"
            }
        } else if (proposal.before.type == MirrorMetadataAuthority.NodeType.DIRECTORY) {
            WorkspaceProcessPathGuard.requireUnused(File(working, proposal.path))
        }
        val intents = authority.prepareBatch(expectedRevision, proposals)
        val nextBaseline = state.baseline.toMutableMap()
        val nextDirectories = state.directoryHistory.toMutableSet()
        val nextConflicts = state.conflicts.toMutableSet()
        val result = mutableListOf<Result>()
        val receipts = mutableListOf<MirrorMetadataAuthority.Transition>()
        fun flush() {
            state = authority.finishBatchWithHistory(state.revision, receipts, nextBaseline, nextConflicts, nextDirectories)
            receipts.clear()
        }
        AnchoredWorkspaceFiles(source).use { canonical -> AnchoredWorkspaceFiles(working).use { copy ->
            AnchoredWorkspaceFiles(retainedRoot).use { retained -> AnchoredWorkspaceFiles(preparedRoot).use { prepared ->
                for (intent in intents) {
                    context.ensureActive()
                    guard.requireRoot(working)
                    checkpoint("prepared", intent)
                    if (intent.proposed.type == MirrorMetadataAuthority.NodeType.FILE) {
                        stage(canonical, prepared, intent, context)
                    }
                    checkpoint("staged", intent)
                    if (intent.proposed.type == MirrorMetadataAuthority.NodeType.FILE) {
                        check(node(prepared, preparedRoot, intent.id, context) == intent.proposed) {
                            "Prepared synchronization file changed; CLI data was preserved and recovery is required at $preparedRoot"
                        }
                    }
                    val hadEntry = intent.before.type != MirrorMetadataAuthority.NodeType.ABSENT
                    if (hadEntry) {
                        if (intent.before.type == MirrorMetadataAuthority.NodeType.DIRECTORY) {
                            WorkspaceProcessPathGuard.requireUnused(File(working, intent.path))
                        }
                        val moved = copy.moveNoReplaceDurable(intent.path, retained, intent.retainedName)
                        requireDurable(moved, intent)
                        checkpoint("retired", intent)
                        if (node(retained, retainedRoot, intent.retainedName, context) != intent.before ||
                            (intent.before.type == MirrorMetadataAuthority.NodeType.DIRECTORY &&
                                !knownDirectory(File(retainedRoot, intent.retainedName), intent.path, state, context))) {
                            val restored = retained.moveNoReplaceDurable(intent.retainedName, copy, intent.path)
                            if (restored.published) requireDurable(restored, intent)
                            else check(restored.error == 17) { "Cannot restore retained CLI work (errno=${restored.error}); inspect $retainedRoot" }
                            receipts += MirrorMetadataAuthority.Transition(intent.id, MirrorMetadataAuthority.Phase.PREPARED, MirrorMetadataAuthority.Phase.CONFLICT)
                            result += Result(intent.path, false, "CLI entry changed before capture; both current and retained data were preserved",
                                File(retainedRoot, intent.retainedName).takeUnless { restored.published })
                            nextConflicts += intent.path
                            if (receipts.size >= 64) flush()
                            continue
                        }
                        stableCopy(retained, intent, context)
                        checkpoint("snapshot", intent)
                        check(node(retained, retainedRoot, intent.stableName, context) == intent.before) {
                            "Stable retained copy changed; publication stopped and all versions remain at $retainedRoot"
                        }
                        if (node(retained, retainedRoot, intent.retainedName, context) != intent.before) {
                            val restored = retained.moveNoReplaceDurable(intent.retainedName, copy, intent.path)
                            if (restored.published) requireDurable(restored, intent)
                            else check(restored.error == 17) { "Cannot restore CLI work; inspect $retainedRoot" }
                            nextConflicts += intent.path
                            receipts += MirrorMetadataAuthority.Transition(intent.id, MirrorMetadataAuthority.Phase.PREPARED, MirrorMetadataAuthority.Phase.CONFLICT)
                            result += Result(intent.path, false, "CLI continued writing during stable capture; live and stable versions were preserved",
                                File(retainedRoot, intent.retainedName).takeUnless { restored.published }, File(retainedRoot, intent.stableName))
                            if (receipts.size >= 64) flush()
                            continue
                        }
                    }
                    receipts += MirrorMetadataAuthority.Transition(intent.id, MirrorMetadataAuthority.Phase.PREPARED, MirrorMetadataAuthority.Phase.RETAINED)
                    val publication = when (intent.proposed.type) {
                        MirrorMetadataAuthority.NodeType.FILE -> prepared.moveNoReplaceDurable(intent.id, copy, intent.path)
                        MirrorMetadataAuthority.NodeType.DIRECTORY -> copy.createDirectory(intent.path)
                        MirrorMetadataAuthority.NodeType.ABSENT -> AnchoredWorkspaceFiles.MoveReceipt(true, 0)
                    }
                    if (!publication.published && publication.error == 17) {
                        nextConflicts += intent.path
                        receipts += MirrorMetadataAuthority.Transition(intent.id, MirrorMetadataAuthority.Phase.RETAINED, MirrorMetadataAuthority.Phase.CONFLICT)
                        result += Result(intent.path, false, "CLI created a concurrent destination; no version was overwritten",
                            File(retainedRoot, intent.retainedName).takeIf { hadEntry }, File(retainedRoot, intent.stableName).takeIf { hadEntry })
                    } else {
                        requireDurable(publication, intent)
                        checkpoint("published", intent)
                        val matches = node(copy, working, intent.path, context) == intent.proposed
                        val retainedChanged = hadEntry && node(retained, retainedRoot, intent.retainedName, context) != intent.before
                        if (!matches || retainedChanged) nextConflicts += intent.path
                        receipts += MirrorMetadataAuthority.Transition(intent.id, MirrorMetadataAuthority.Phase.RETAINED,
                            if (matches) MirrorMetadataAuthority.Phase.APPLIED else MirrorMetadataAuthority.Phase.CONFLICT)
                        result += Result(intent.path, matches,
                            when {
                                !matches -> "CLI changed the published path concurrently; both live and retained versions were preserved"
                                retainedChanged -> "An open CLI writer changed its retained inode after publication; inspect both retained and stable versions"
                                else -> null
                            },
                            File(retainedRoot, intent.retainedName).takeIf { hadEntry }, File(retainedRoot, intent.stableName).takeIf { hadEntry })
                        if (matches) {
                            nextBaseline.keys.removeAll { it == intent.path || it.startsWith("${intent.path}/") }
                            nextDirectories.removeAll { it == intent.path || it.startsWith("${intent.path}/") }
                            if (intent.proposed.type == MirrorMetadataAuthority.NodeType.FILE) {
                                nextBaseline[intent.path] = WorkspaceSyncManifest.Entry(intent.proposed.sha256, intent.proposed.mode)
                            } else if (intent.proposed.type == MirrorMetadataAuthority.NodeType.DIRECTORY) {
                                nextDirectories += intent.path
                            }
                        }
                    }
                    if (receipts.size >= 64) flush()
                }
                if (receipts.isNotEmpty()) flush()
            } }
        } }
        intents.lastOrNull()?.let { checkpoint("receipts", it) }
        return result
    }

    fun requireNoInterruptedWork() {
        authority.loadExecutionReadyState()
    }

    fun requireUbuntuLocalConsistency(guard: WorkspaceMutationGuard) {
        guard.requireRoot(working)
        requireNoInterruptedWork()
        guard.requireRoot(working)
    }

    suspend fun inspectRetainedVersions(guard: WorkspaceMutationGuard): Set<String> {
        guard.requireRoot(working)
        requireNoInterruptedWork()
        val context = currentCoroutineContext()
        val detected = linkedSetOf<String>()
        AnchoredWorkspaceFiles(retainedRoot).use { retained ->
            val state = authority.visitOperations { records -> for (record in records) {
                context.ensureActive()
                guard.requireRoot(working)
                if (record.before.type == MirrorMetadataAuthority.NodeType.ABSENT) continue
                val kind = retained.kind(record.retainedName)
                if (record.phase == MirrorMetadataAuthority.Phase.APPLIED) {
                    check(node(retained, retainedRoot, record.stableName, context) == record.before) {
                        "Stable retained version is missing or changed for ${record.path}; recovery stopped at $retainedRoot"
                    }
                    check(kind != AnchoredWorkspaceFiles.Kind.ABSENT) {
                        "Retained CLI inode is missing for ${record.path}; inspect $retainedRoot"
                    }
                }
                if (kind != AnchoredWorkspaceFiles.Kind.ABSENT) {
                    val current = node(retained, retainedRoot, record.retainedName, context)
                    val reviewedSignature = if (record.acknowledgedOriginal != null && kind == AnchoredWorkspaceFiles.Kind.DIRECTORY)
                        MirrorRecoverySnapshot.observe(retainedRoot, record.retainedName, context).node.signature() else current?.signature()
                    val changed = if (record.acknowledgedOriginal != null) record.acknowledgedOriginal != reviewedSignature else current != record.before
                    if (changed) detected += record.path
                }
            } }
            if (detected.isNotEmpty()) authority.commit(state.revision, state.baseline, state.conflicts + detected)
        }
        return detected
    }

    private fun stage(canonical: AnchoredWorkspaceFiles, prepared: AnchoredWorkspaceFiles,
                      intent: MirrorMetadataAuthority.Intent, context: CoroutineContext) =
        copyVerified(canonical, intent.path, prepared, intent.id, preparedRoot, intent.proposed, context)

    private fun copyVerified(original: AnchoredWorkspaceFiles, originalPath: String, destination: AnchoredWorkspaceFiles,
                             destinationPath: String, destinationRoot: File, expected: MirrorMetadataAuthority.Node,
                             context: CoroutineContext) {
        original.openRead(originalPath).use { input ->
            val before = input.observe()
            WorkspaceTransferSpace.requireAvailable(destinationRoot, before.attributes.size(), 192L * 1024 * 1024)
            destination.createNew(destinationPath).use { output ->
                val hash = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                var sinceSpaceCheck = 0L
                while (true) {
                    context.ensureActive()
                    val size = input.read(buffer)
                    if (size < 0) break
                    check(size.toLong() <= before.attributes.size() - count) { "Source grew while preparing synchronization; CLI data was preserved" }
                    output.write(buffer, 0, size)
                    hash.update(buffer, 0, size)
                    count += size
                    sinceSpaceCheck += size
                    if (sinceSpaceCheck >= 16L * 1024 * 1024) {
                        WorkspaceTransferSpace.requireAvailable(destinationRoot, before.attributes.size() - count, 192L * 1024 * 1024)
                        sinceSpaceCheck = 0
                    }
                }
                val after = input.observe()
                check(count == before.attributes.size() && unchanged(before, after) &&
                    hash.digest().hex() == expected.sha256 &&
                    (if (before.executable) 493 else 420) == expected.mode) {
                    "Source changed while preparing synchronization; working and retained data remain at $working and $retainedRoot"
                }
                val named = original.openRead(originalPath).use { it.observe() }
                check(unchanged(before, named)) { "Source identity changed while preparing synchronization; CLI data was preserved" }
                output.force(expected.mode)
            }
        }
    }

    private fun stableCopy(retained: AnchoredWorkspaceFiles, intent: MirrorMetadataAuthority.Intent,
                           context: CoroutineContext) {
        if (intent.before.type == MirrorMetadataAuthority.NodeType.FILE) {
            copyVerified(retained, intent.retainedName, retained, intent.stableName, retainedRoot, intent.before, context)
            return
        }
        check(intent.before.type == MirrorMetadataAuthority.NodeType.DIRECTORY)
        val tree = AnchoredWorkspaceTree.snapshot(File(retainedRoot, intent.retainedName), WorkspaceFileFingerprintCache(maxEntries = 0), context)
        check(tree.opaque.isEmpty()) { "Retained directory changed before stable capture; all bytes were preserved" }
        requireDurable(retained.createDirectory(intent.stableName), intent)
        for (directory in tree.directories.sortedBy { it.count { character -> character == '/' } }) {
            context.ensureActive()
            requireDurable(retained.createDirectory("${intent.stableName}/$directory"), intent)
        }
        for ((path, value) in tree.files) {
            context.ensureActive()
            copyVerified(retained, "${intent.retainedName}/$path", retained, "${intent.stableName}/$path", retainedRoot,
                MirrorMetadataAuthority.Node.file(WorkspaceSyncManifest.Entry(value.sha256, if (value.executable) 493 else 420)), context)
        }
    }

    private fun node(files: AnchoredWorkspaceFiles, root: File, relative: String,
                     context: CoroutineContext): MirrorMetadataAuthority.Node? = when (files.kind(relative)) {
        AnchoredWorkspaceFiles.Kind.ABSENT -> MirrorMetadataAuthority.Node.absent
        AnchoredWorkspaceFiles.Kind.FILE -> {
            val value = WorkspaceFileFingerprintCache(maxEntries = 0).read(files, relative, File(root, relative).toPath(), context, false)
            MirrorMetadataAuthority.Node.file(WorkspaceSyncManifest.Entry(value.sha256, if (value.executable) 493 else 420))
        }
        AnchoredWorkspaceFiles.Kind.DIRECTORY -> directoryNode(File(root, relative), context)
        else -> null
    }

    private fun requireDurable(receipt: AnchoredWorkspaceFiles.MoveReceipt, intent: MirrorMetadataAuthority.Intent) {
        if (!receipt.durable) throw FileSystemException(intent.path, authority.directory.path,
            "Synchronization ${if (receipt.published) "published but durability is unconfirmed" else "did not publish"} (errno=${receipt.error}); all retained versions were preserved for recovery")
    }

    private fun unchanged(a: AnchoredWorkspaceFiles.Observation, b: AnchoredWorkspaceFiles.Observation) =
        a.attributes.fileKey() == b.attributes.fileKey() && a.attributes.size() == b.attributes.size() &&
            a.attributes.lastModifiedTime() == b.attributes.lastModifiedTime() && a.changed == b.changed && a.executable == b.executable

    private fun knownDirectory(
        root: File,
        relative: String,
        state: MirrorMetadataAuthority.State,
        context: CoroutineContext,
    ): Boolean {
        val tree = AnchoredWorkspaceTree.snapshot(root, WorkspaceFileFingerprintCache(maxEntries = 0), context)
        if (tree.opaque.isNotEmpty()) return false
        val prefix = "$relative/"
        val expected = state.baseline.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
        if (tree.files.mapValues { (_, file) -> WorkspaceSyncManifest.Entry(file.sha256, if (file.executable) 493 else 420) } != expected) return false
        val expectedDirectories = state.directoryHistory.filter { it.startsWith(prefix) }.mapTo(linkedSetOf()) { it.removePrefix(prefix) }
        val knownRoot = relative in state.directoryHistory || expected.isNotEmpty() || expectedDirectories.isNotEmpty()
        for (path in expected.keys + expectedDirectories.toList()) {
            var slash = path.indexOf('/')
            while (slash >= 0) {
                expectedDirectories += path.substring(0, slash)
                slash = path.indexOf('/', slash + 1)
            }
        }
        return knownRoot && tree.directories == expectedDirectories
    }

    companion object {
        fun directoryNode(root: File, context: CoroutineContext): MirrorMetadataAuthority.Node {
            val tree = AnchoredWorkspaceTree.snapshot(root, WorkspaceFileFingerprintCache(maxEntries = 0), context)
            val rows = tree.directories.map { "D\t$it" } + tree.opaque.map { "O\t$it" } +
                tree.files.map { (path, value) -> "F\t$path\t${value.sha256}\t${if (value.executable) 493 else 420}" }
            val hash = MessageDigest.getInstance("SHA-256")
            rows.sorted().forEach { hash.update(it.toByteArray(Charsets.UTF_8)); hash.update('\n'.code.toByte()) }
            return MirrorMetadataAuthority.Node.directory(hash.digest().hex())
        }
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
