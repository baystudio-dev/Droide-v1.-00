package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Review receipts acknowledge exact retained bytes; resolving a path never disposes its history. */
internal class MirrorConflictRecovery(private val source: File, private val working: File,
    private val checkpoint: (String) -> Unit = {},
) {
    /** Call-local outcome only. DB reinspection is authoritative after restart;
     * this is never a durable receipt or permission to release retained bytes. */
    class ResolutionAttempt internal constructor() {
        enum class Outcome { NOT_ATTEMPTED, UNKNOWN, CONFIRMED }
        @Volatile var outcome: Outcome = Outcome.NOT_ATTEMPTED
            private set
        @Volatile var committedRevision: Long? = null
            private set
        internal fun attempted() { outcome = Outcome.UNKNOWN }
        internal fun confirmed(revision: Long) { committedRevision = revision; outcome = Outcome.CONFIRMED }
    }
    internal data class History(val digest: String, val versions: Int, val unreviewed: Int)
    class Review internal constructor(internal val owner: MirrorConflictRecovery, internal val revision: Long,
        val path: String, internal val canonical: MirrorRecoverySnapshot.Observation,
        internal val copy: MirrorRecoverySnapshot.Observation, internal val history: History) {
        @Volatile var lastResolutionAttempt: ResolutionAttempt? = null
            internal set
        val unreviewedVersions get() = history.unreviewed
        val matchingFiles get() = canonical.node == copy.node && canonical.files == copy.files && canonical.directories == copy.directories
        val canResolve get() = matchingFiles && unreviewedVersions == 0
    }
    class VersionReview internal constructor(internal val owner: MirrorConflictRecovery, internal val revision: Long,
        val item: MirrorRetainedRecovery.Item, internal val observed: MirrorRecoverySnapshot.Observation) {
        val digest get() = observed.node.sha256
        val bytes get() = observed.bytes
        val files get() = observed.files.size
    }
    private val directory = File(working.parentFile, ".${working.name}-sync-authority")
    private val authority = MirrorMetadataAuthority(directory, source, working)
    private val bootstrap = LocalMirrorAuthorityBootstrap(source, working)

    suspend fun inspectVersion(item: MirrorRetainedRecovery.Item): VersionReview = locked(false) { guard ->
        val state = bootstrap.readLinkedExecutionState(guard, authority)
        requireOriginal(item)
        val observed = observe(File(directory, "retained"), item.operation.retainedName)
        check(observed.node.type != MirrorMetadataAuthority.NodeType.ABSENT) { "Retained version disappeared; acknowledgement was refused" }
        VersionReview(this, state.revision, item, observed)
    }

    suspend fun acknowledge(review: VersionReview, requireCurrent: () -> Unit) = locked(true) { guard ->
        require(review.owner === this) { "Retained review belongs to another workspace" }
        requireCurrent()
        val state = bootstrap.readLinkedExecutionState(guard, authority)
        check(state.revision == review.revision) { "History changed; review this version again" }
        requireOriginal(review.item)
        val observed = observe(File(directory, "retained"), review.item.operation.retainedName)
        check(observed == review.observed) { "Retained bytes changed after review; no acknowledgement was saved" }
        currentCoroutineContext().ensureActive(); requireCurrent()
        authority.acknowledgeOriginal(state.revision, review.item.operation, observed.node.signature())
    }

    suspend fun inspect(path: String): Review = locked(false) { guard -> inspect(guard, path) }

    /** Resolves agreement of SAVED files only. Editor buffers may keep changing;
     * neither their content nor dirty/saved state is read, written or acknowledged.
     * Existing writer fences serialize participating saves and CLI publication. */
    suspend fun resolve(review: Review, requireCurrent: () -> Unit) {
        require(review.owner === this) { "Conflict review belongs to another workspace" }
        val attempt = ResolutionAttempt().also { review.lastResolutionAttempt = it }
        locked(true) { guard ->
            requireCurrent()
            val current = inspect(guard, review.path)
            check(current.revision == review.revision && current.canonical == review.canonical && current.copy == review.copy &&
                current.history == review.history) { "Reviewed files or history changed; review the conflict again" }
            check(current.matchingFiles && current.unreviewedVersions == 0) { "Match the saved project and CLI files and acknowledge changed retained versions first" }
            val state = bootstrap.readLinkedExecutionState(guard, authority)
            val baseline = state.baseline.filterKeys { !under(it, review.path) }.toMutableMap()
            val directories = state.directoryHistory.filterNot { under(it, review.path) }.toMutableSet()
            when (current.canonical.node.type) {
                MirrorMetadataAuthority.NodeType.ABSENT -> Unit
                MirrorMetadataAuthority.NodeType.FILE -> baseline[review.path] = WorkspaceSyncManifest.Entry(current.canonical.node.sha256, current.canonical.node.mode)
                MirrorMetadataAuthority.NodeType.DIRECTORY -> {
                    directories.add(review.path)
                    current.canonical.directories.map { "${review.path}/$it" }.filter(AgentWorkspacePathPolicy::visible).forEach(directories::add)
                    current.canonical.files.forEach { (name, version) ->
                        val path = "${review.path}/$name"
                        if (AgentWorkspacePathPolicy.visible(path)) baseline[path] = version
                    }
                }
            }
            checkpoint("saved-resolution-before-commit")
            currentCoroutineContext().ensureActive(); requireCurrent()
            // A throw after this boundary can include an already committed transaction.
            attempt.attempted()
            val committed = authority.commitWithHistory(state.revision, baseline, state.conflicts - review.path, directories)
            attempt.confirmed(committed.revision)
            checkpoint("saved-resolution-after-commit")
        }
    }

    private suspend fun inspect(guard: WorkspaceMutationGuard, path: String): Review {
        val state = bootstrap.readLinkedExecutionState(guard, authority)
        check(path in state.conflicts) { "Conflict changed or was already resolved; refresh recovery" }
        val canonical = observe(source, path)
        val copy = observe(working, path)
        val history = history(path)
        check(bootstrap.readLinkedExecutionState(guard, authority).revision == state.revision) { "History changed during review" }
        return Review(this, state.revision, path, canonical, copy, history)
    }

    private suspend fun history(path: String): History {
        val context = currentCoroutineContext()
        val hash = MessageDigest.getInstance("SHA-256")
        fun part(value: String) { val bytes = value.toByteArray(); hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes) }
        var versions = 0; var unreviewed = 0
        authority.visitOperations { page -> for (record in page) {
            if (!under(record.path, path)) continue
            context.ensureActive(); versions++
            val original = MirrorRecoverySnapshot.observe(File(directory, "retained"), record.retainedName, context)
            val stable = MirrorRecoverySnapshot.observe(File(directory, "retained"), record.stableName, context)
            val prepared = MirrorRecoverySnapshot.observe(File(directory, "prepared"), record.id, context)
            if (record.phase == MirrorMetadataAuthority.Phase.APPLIED && record.before.type != MirrorMetadataAuthority.NodeType.ABSENT) {
                check(stable.node == record.before && original.node.type != MirrorMetadataAuthority.NodeType.ABSENT) {
                    "Retained history is missing or damaged; existing versions were kept"
                }
            }
            if (original.node.type != MirrorMetadataAuthority.NodeType.ABSENT &&
                (if (record.acknowledgedOriginal != null) record.acknowledgedOriginal != original.node.signature() else original.node != record.before)) unreviewed++
            part(record.id); part(record.phase.name); part(record.acknowledgedOriginal.orEmpty())
            for (observation in listOf(original, stable, prepared)) {
                part(observation.node.signature()); part(observation.identity); part(observation.parentIdentity)
            }
        } }
        return History(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, versions, unreviewed)
    }

    private fun requireOriginal(item: MirrorRetainedRecovery.Item) {
        require(item.role == MirrorRetainedRecovery.Role.ORIGINAL && item.kind in setOf(AnchoredWorkspaceFiles.Kind.FILE, AnchoredWorkspaceFiles.Kind.DIRECTORY))
        check(authority.operation(item.operation.id) == item.operation && item.operation.phase in setOf(
            MirrorMetadataAuthority.Phase.APPLIED, MirrorMetadataAuthority.Phase.CONFLICT)) { "Retained operation changed; inspect again" }
    }
    private suspend fun observe(root: File, path: String) = MirrorRecoverySnapshot.observe(root, path, currentCoroutineContext())
    private fun under(path: String, parent: String) = path == parent || path.startsWith("$parent/")

    private suspend fun <T> locked(mutation: Boolean, action: suspend (WorkspaceMutationGuard) -> T): T = withContext(Dispatchers.IO) {
        if (mutation) MirrorRecoveryWriterFence.mutate(source, working) { _, guard ->
            authority.requireNoPendingProcessOwners()
            action(guard)
        }
        else WorkspaceFileMutationLocks.withCaptureRoot(source) {
            WorkspaceFileMutationLocks.withCaptureRoot(working, action)
        }
    }
}
