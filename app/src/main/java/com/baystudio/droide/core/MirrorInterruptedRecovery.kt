package com.baystudio.droide.core

import java.io.File
import com.baystudio.droide.core.MirrorRecoverySnapshot.Observation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Explicit completion of an interrupted journal entry; never replays a filesystem mutation. */
internal class MirrorInterruptedRecovery(private val source: File, private val working: File) {
    class Review internal constructor(
        internal val owner: MirrorInterruptedRecovery,
        internal val revision: Long,
        val operation: MirrorMetadataAuthority.Intent,
        internal val observed: List<Observation>,
        val canComplete: Boolean,
    )

    private val directory = File(working.parentFile, ".${working.name}-sync-authority")
    private val authority = MirrorMetadataAuthority(directory, source, working)
    private val bootstrap = LocalMirrorAuthorityBootstrap(source, working)

    /** Evidence-only hold: original database/journal are never opened by SQLite or modified. */
    suspend fun captureSqliteRollbackEvidence(requireCurrent: () -> Unit): MirrorSqliteRollbackRecovery.Review =
        withContext(Dispatchers.IO) {
            val caller = currentCoroutineContext()
            MirrorRecoveryWriterFence.mutate(source, working) { sourceGuard, workingGuard ->
                authority.captureSqliteRollbackEvidence {
                    caller.ensureActive()
                    requireCurrent()
                    sourceGuard.requireRoot(source); workingGuard.requireRoot(working)
                }
            }
        }

    /** Builds only a separate speculative byte candidate, never live recovery or SQL. */
    suspend fun buildSqliteForensicCandidate(requireCurrent: () -> Unit): MirrorSqliteForensicCandidate.Result =
        withContext(Dispatchers.IO) {
            val caller = currentCoroutineContext()
            MirrorRecoveryWriterFence.mutate(source, working) { sourceGuard, workingGuard ->
                authority.buildSqliteForensicCandidate {
                    caller.ensureActive(); requireCurrent()
                    sourceGuard.requireRoot(source); workingGuard.requireRoot(working)
                }
            }
        }

    suspend fun inspect(operation: MirrorMetadataAuthority.Intent): Review = withContext(Dispatchers.IO) {
        WorkspaceFileMutationLocks.withCaptureRoot(source) {
            WorkspaceFileMutationLocks.withCaptureRoot(working) { guard ->
                val state = bootstrap.readLinkedState(guard, authority)
                requirePending(operation)
                val observations = observe(operation)
                check(bootstrap.readLinkedState(guard, authority).revision == state.revision)
                Review(this@MirrorInterruptedRecovery, state.revision, operation, observations, isComplete(operation, observations))
            }
        }
    }

    /** Review-only fallback protects the path as a conflict, retaining all candidate versions. */
    suspend fun finish(review: Review, complete: Boolean, requireCurrent: () -> Unit) = withContext(Dispatchers.IO) {
        require(review.owner === this@MirrorInterruptedRecovery) { "Recovery review belongs to another workspace" }
        requireCurrent()
        // Repository stream publication also takes source before working. Preserve that lock order.
        MirrorRecoveryWriterFence.mutate(source, working) { sourceGuard, guard ->
            authority.requireNoPendingProcessOwners()
            val state = bootstrap.readLinkedState(guard, authority)
            check(state.revision == review.revision) { "Recovery state changed; inspect the interrupted change again" }
            requirePending(review.operation)
            val current = observe(review.operation)
            check(current == review.observed) { "A reviewed file changed; all versions were kept. Inspect again." }
            check(!complete || review.canComplete && isComplete(review.operation, current)) {
                "Publication is not verified; keep this path protected for manual review"
            }
            currentCoroutineContext().ensureActive()
            requireCurrent()
            sourceGuard.requireRoot(source); guard.requireRoot(working)
            val operation = review.operation
            val transitions = buildList {
                if (complete && operation.phase == MirrorMetadataAuthority.Phase.PREPARED)
                    add(MirrorMetadataAuthority.Transition(operation.id, operation.phase, MirrorMetadataAuthority.Phase.RETAINED))
                add(MirrorMetadataAuthority.Transition(operation.id,
                    if (complete) MirrorMetadataAuthority.Phase.RETAINED else operation.phase,
                    if (complete) MirrorMetadataAuthority.Phase.APPLIED else MirrorMetadataAuthority.Phase.CONFLICT))
            }
            val baseline = state.baseline.toMutableMap()
            val directories = state.directoryHistory.toMutableSet()
            val conflicts = state.conflicts.toMutableSet()
            if (complete) {
                baseline.keys.removeAll { it == operation.path || it.startsWith("${operation.path}/") }
                directories.removeAll { it == operation.path || it.startsWith("${operation.path}/") }
                when (operation.proposed.type) {
                    MirrorMetadataAuthority.NodeType.FILE -> baseline[operation.path] =
                        WorkspaceSyncManifest.Entry(operation.proposed.sha256, operation.proposed.mode)
                    MirrorMetadataAuthority.NodeType.DIRECTORY -> directories.add(operation.path)
                    MirrorMetadataAuthority.NodeType.ABSENT -> Unit
                }

                if (current[2].node != operation.before) conflicts.add(operation.path)
            } else conflicts.add(operation.path)
            authority.finishBatchWithHistory(state.revision, transitions, baseline, conflicts, directories)

        }
    }

    private fun requirePending(operation: MirrorMetadataAuthority.Intent) {
        check(operation.phase in setOf(MirrorMetadataAuthority.Phase.PREPARED, MirrorMetadataAuthority.Phase.RETAINED) &&
            authority.operation(operation.id) == operation) { "Interrupted change was already handled or changed; refresh recovery" }
    }

    private fun isComplete(operation: MirrorMetadataAuthority.Intent, values: List<Observation>): Boolean {
        val (canonical, copy, original, stable) = values
        val sourceMatches = canonical.node == operation.proposed || operation.proposed.type == MirrorMetadataAuthority.NodeType.DIRECTORY &&
            canonical.node.type == MirrorMetadataAuthority.NodeType.DIRECTORY
        return sourceMatches && copy.node == operation.proposed &&
            (operation.before.type == MirrorMetadataAuthority.NodeType.ABSENT ||
                original.node.type != MirrorMetadataAuthority.NodeType.ABSENT && stable.node == operation.before)
    }

    private suspend fun observe(operation: MirrorMetadataAuthority.Intent): List<Observation> = listOf(
        observe(source, operation.path), observe(working, operation.path),
        observe(File(directory, "retained"), operation.retainedName),
        observe(File(directory, "retained"), operation.stableName),
        observe(File(directory, "prepared"), operation.id),
    )

    private suspend fun observe(root: File, path: String) =
        MirrorRecoverySnapshot.observe(root, path, currentCoroutineContext())

}
