package com.baystudio.droide.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SafManifestFile(
    val version: Int = 2,

    val entries: Map<String, String> = emptyMap(),
)

@Serializable
private data class PendingSafActivation(
    val version: Int = 1,
    val roots: List<String>,
    val stage: String,
    val backup: String,
)

// Use same-filesystem replacement for atomic activation.

class SafMirror(private val context: Context, treeUri: String, private val localRoot: File) {
    // Test-only byte observation; never a trust cache or a callback after the final proof.
    private var unchangedReadObserver: ((Boolean, Int) -> Unit)? = null
    internal constructor(context: Context, treeUri: String, localRoot: File,
        unchangedReadObserver: (external: Boolean, bytes: Int) -> Unit) : this(context, treeUri, localRoot) {
        this.unchangedReadObserver = unchangedReadObserver
    }
    private val uri = Uri.parse(treeUri)
    private val activationMarker = File(localRoot, ".droide/saf_activation_pending.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val query = SafDocumentsTreeQuery(context, uri)
    private val ownedStorage = SafOperationStorage()

    private data class OwnedAdmission(var journal: SafOperationJournal?, var legacy: SafLegacyAdmission,
        val baseline: Map<String, String>, val directory: File, val initiallyPending: Boolean,
        val mutatedDuringAdmission: Boolean, val visible: SafStoredFile?, val revision: Long?, val digest: String?,
        val cancellationJob: Job?)

    /** Strict reads precede even private staging/bootstrap. Legacy evidence is never recovered or cleared. */
    private fun admitOwned(paths: List<String>, checkCancelled: () -> Unit, cancellationJob: Job?): OwnedAdmission {
        checkPendingActivation(paths.takeUnless { it == listOf("") })
        val parent = File(context.applicationContext.filesDir, "saf-owned-operations")
        val key = (uri.toString() + "\u0000" + localRoot.absoluteFile.toPath().normalize()).toByteArray(Charsets.UTF_8).safDigest(checkCancelled)
        val directory = File(parent, key)
        ownedStorage.requireRealAncestors(directory)
        val reader = SafLegacyEvidence(context.applicationContext.filesDir, localRoot, uri.toString(), ownedStorage)
        val journal: SafOperationJournal?
        val legacy: SafLegacyAdmission
        var initiallyPending = false
        var mutatedDuringAdmission = false
        var admittedVisible: SafStoredFile? = null
        var admittedRevision: Long? = null
        var admittedDigest: String? = null
        if (ownedStorage.attributes(directory) == null) {
            legacy = reader.inspect(checkCancelled)
            legacy.revalidateAndRequireDisjoint(localRoot, paths, checkCancelled)
            journal = null // Bootstrap only once complete preflight proves a mutation is needed.
        } else {
            journal = SafOperationJournal.open(localRoot, uri.toString(), directory, ownedStorage, cancellationJob = cancellationJob)
            val publisher = SafDurableManifestCommit(journal)
            val admittedOperations = journal.recoverAll(checkCancelled)
            val visible = publisher.admitBeforeEffects(admittedOperations, checkCancelled)
            val trusted = publisher.trusted(checkCancelled)
            admittedVisible = visible
            admittedRevision = trusted.revision
            admittedDigest = trusted.digest
            initiallyPending = admittedOperations.any { pending -> !journal.isTerminal(pending) &&
                pending.scopes.any { scope -> paths.any { SafOperationJournal.overlaps(scope, it) } } }
            legacy = if (visible == null) reader.inspect(checkCancelled)
                else reader.inspectOwned(SafLegacyOwnedManifest(visible, trusted.entries), checkCancelled)
            legacy.revalidateAndRequireDisjoint(localRoot, paths, checkCancelled)
            check(legacy.baselineEntries == trusted.entries)
            check(admittedOperations.none { operation ->
                operation.effects.values.any { record -> record.plan.local?.let { local ->
                    local.effect == SafLocalEffect.DELETE_STAGE && local.deleteCustody == null && record.phase >= SafEffectPhase.ATTEMPTING &&
                        paths.any { SafOperationJournal.overlaps(it, local.destination.path) }
                } == true }
            }) { "SAF operation is blocked by retained legacy sibling Delete custody; no automatic migration or cleanup was attempted" }
            // An explicit new invocation may release only completely admitted no-attempt authority. Retain
            // all evidence; interrupted/attempted effects never replay or enter this branch.
            admittedOperations.filter { pending ->
                !journal.isTerminal(pending) && "manifest-attempt.json" !in pending.files &&
                    pending.effects.values.all { it.phase == SafEffectPhase.PREPARED } &&
                    pending.scopes.any { scope -> paths.any { SafOperationJournal.overlaps(scope, it) } }
            }.forEach { pending ->
                checkCancelled()
                publisher.admitBeforeEffects(journal.recoverAll(checkCancelled), checkCancelled)
                legacy.revalidateAndRequireDisjoint(localRoot, paths, checkCancelled)
                mutatedDuringAdmission = true
                journal.abandonPrepared(journal.recover(pending.id, checkCancelled), checkCancelled)
            }
        }
        return OwnedAdmission(journal, legacy, legacy.baselineEntries, directory, initiallyPending,
            mutatedDuringAdmission, admittedVisible, admittedRevision, admittedDigest, cancellationJob)
    }

    /** Export observes local saved bytes only. No provider query, recovery, journal bootstrap,
     * abandonment, durability promotion, or cleanup is allowed in this read-only boundary. */
    internal fun requireExportReadable(relativeScope: String) {
        val paths = listOf(relativeScope)
        checkPendingActivation(paths.takeUnless { relativeScope.isEmpty() })
        val parent = File(context.applicationContext.filesDir, "saf-owned-operations")
        val key = (uri.toString() + "\u0000" + localRoot.absoluteFile.toPath().normalize()).toByteArray(Charsets.UTF_8).safDigest()
        val directory = File(parent, key)
        ownedStorage.requireRealAncestors(directory)
        val reader = SafLegacyEvidence(context.applicationContext.filesDir, localRoot, uri.toString(), ownedStorage)
        val legacy = if (ownedStorage.attributes(directory) == null) reader.inspect() else {
            val journal = SafOperationJournal.open(localRoot, uri.toString(), directory, ownedStorage)
            val admission = journal.inspectExportReadOnly()
            val publisher = SafDurableManifestCommit(journal)
            val visible = publisher.admitBeforeEffects(admission) {}
            val trusted = publisher.trusted(admission) {}
            if (visible == null) reader.inspect()
            else reader.inspectOwned(SafLegacyOwnedManifest(visible, trusted.entries))
        }
        legacy.revalidateAndRequireDisjoint(localRoot, paths) {}
    }

    private fun initializeJournal(admission: OwnedAdmission, paths: List<String>, poll: () -> Unit): SafOperationJournal {
        admission.journal?.let { return it }
        admission.legacy.revalidateAndRequireDisjoint(localRoot, paths, poll)
        poll()
        val parent = requireNotNull(admission.directory.parentFile)
        if (ownedStorage.attributes(parent) == null) ownedStorage.createDirectory(parent)
        return SafOperationJournal.create(localRoot, uri.toString(), admission.directory, ownedStorage,
            cancellationJob = admission.cancellationJob).also { admission.journal = it }
    }

    private fun mutationRequest(mutation: FileRepository.Mutation): SafOperationRequest = when (mutation) {
        is FileRepository.Mutation.Write -> SafOperationRequest(SafOperationKind.WRITE, listOf(mutation.path))
        is FileRepository.Mutation.CreateDirectory -> SafOperationRequest(SafOperationKind.CREATE_DIRECTORY, listOf(mutation.path))
        is FileRepository.Mutation.Delete -> SafOperationRequest(SafOperationKind.DELETE, listOf(mutation.path))
        is FileRepository.Mutation.Move -> SafOperationRequest(SafOperationKind.MOVE, listOf(mutation.from, mutation.to))
        is FileRepository.Mutation.Sync -> SafOperationRequest(SafOperationKind.SYNC, listOf(mutation.path))
    }

    private fun authorityScopes(paths: List<String>, checkCancelled: () -> Unit): List<String> {
        val scopes = paths.map { path ->
            SafOperationJournal.validPath(path, false)
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                checkCancelled()
                val attrs = ownedStorage.attributes(File(localRoot, parent))
                require(attrs == null || attrs.isDirectory && !attrs.isSymbolicLink)
                if (attrs != null && findNode(parent, checkCancelled)?.isDirectory == true) break
                parent = parent.substringBeforeLast('/', "")
            }
            parent
        }.toSet()
        return if ("" in scopes) listOf("") else collapsePaths(scopes).sorted()
    }

    // A future repository stream has no known length yet. Its retained evidence is admitted against
    // this filesystem's capacity, not a hard-coded artifact size.
    private fun reservation(payload: Long = localRoot.totalSpace.also {
        check(it > 0L) { "Workspace storage capacity is unavailable; existing files were retained" }
    }) =
        SafJournalReservation(200_000, 128L * 1024 * 1024, payload, indexBytes = 32 * 1024, evidenceFiles = 200_000)

    internal fun repositoryParticipant(): WorkspaceMutationParticipant = WorkspaceMutationParticipant { guard, mutation ->
        val job = currentCoroutineContext()[Job]
        val poll: () -> Unit = { job?.ensureActive(); guard.requireRoot(localRoot) }
        guard.requireRoot(localRoot)
        val request = mutationRequest(mutation)
        val admission = admitOwned(request.paths, poll, job)
        requireDirectTypesUnchanged(mutation, poll)
        val journal = initializeJournal(admission, request.paths, poll)
        val operation = journal.begin(request, authorityScopes(request.paths, poll), reservation(), poll)
        object : WorkspaceMutationSession(guard, mutation) {
            private val local = SafLocalEffects(guard, journal, operation, poll)
            private var latest: SafLocalEffects.Effect? = null
            private var lastReceipt: WorkspaceLocalReceipt? = null
            private var attempted = false
            private var completed = false
            private var deleteCustody: SafDeleteCustody? = null
            override val retainLocalOnSyncFailure: Boolean get() = attempted
            override suspend fun prepareDeleteStage(): String {
                check(mutation is FileRepository.Mutation.Delete && deleteCustody == null)
                poll()
                journal.validate(operation, poll)
                admission.legacy.revalidateAndRequireDisjoint(localRoot, request.paths, poll)
                val metadata = File(localRoot, ".droide")
                val metadataWasAbsent = ownedStorage.attributes(metadata) == null
                val parent = File(metadata, "saf-delete-custody")
                for (directory in listOf(metadata, parent)) {
                    poll(); ownedStorage.requireRealAncestors(directory)
                    if (ownedStorage.attributes(directory) == null) ownedStorage.createDirectory(directory)
                    ownedStorage.key(directory, true)
                }
                val relative = ".droide/saf-delete-custody/${operation.id}"
                val directory = File(localRoot, relative)
                poll(); ownedStorage.requireRealAncestors(directory)
                ownedStorage.createDirectory(directory)
                val binding = SafDeleteCustody(relative, ownedStorage.key(directory, true))
                check(Files.getFileStore(directory.toPath()) == Files.getFileStore(File(localRoot, mutation.path).toPath())) {
                    "SAF Delete custody must be on the source filesystem"
                }
                poll(); check(ownedStorage.attributes(File(directory, "original")) == null)
                if (metadataWasAbsent) {
                    // Our exclusive metadata creation changes one observed absent slot. Consume a fresh independent
                    // strict admission, never a stale or permissive baseline.
                    val publisher = SafDurableManifestCommit(journal)
                    val visible = publisher.admitBeforeEffects(journal.recoverAll(poll), poll)
                    val trusted = publisher.trusted(poll)
                    check(trusted.entries == admission.baseline)
                    val reader = SafLegacyEvidence(context.applicationContext.filesDir, localRoot, uri.toString(), ownedStorage)
                    val fresh = if (visible == null) reader.inspect(poll)
                        else reader.inspectOwned(SafLegacyOwnedManifest(visible, trusted.entries), poll)
                    check(fresh.baselineEntries == admission.baseline)
                    fresh.revalidateAndRequireDisjoint(localRoot, request.paths, poll)
                    admission.legacy = fresh
                }
                deleteCustody = binding
                return "$relative/original"
            }
            override suspend fun prepareLocal(preparation: WorkspaceLocalPreparation) {
                if (preparation.effect == WorkspaceLocalEffect.WRITE) require(requireNotNull(preparation.bytes) in
                    0L..operation.envelope.reservation.payloadBytes) { "SAF local payload exceeds available filesystem capacity" }
                admission.legacy.revalidateAndRequireDisjoint(localRoot, request.paths, poll)
                latest = local.prepare(preparation, deleteCustody.takeIf { preparation.effect == WorkspaceLocalEffect.DELETE_STAGE })
            }
            override fun beforeLocalAttempt(preparation: WorkspaceLocalPreparation) {
                // The local admission itself is durable: any subsequent uncertainty forbids rollback.
                attempted = true
                local.beforeAttempt(requireNotNull(latest), preparation)
            }
            override fun localReturned(receipt: WorkspaceLocalReceipt) {
                lastReceipt = receipt
                local.returned(requireNotNull(latest), receipt)
            }
            override suspend fun synchronize() {
                latest?.let { local.verify(it) }
                synchronizeOwned(guard, mutation, admission, operation, poll) { markExternalEffectAttempted() }
                completed = true
            }
            override fun retainsDeleteStage(receipt: WorkspaceLocalReceipt): Boolean {
                guard.requireRoot(localRoot)
                check(completed && receipt === lastReceipt && mutation is FileRepository.Mutation.Delete)
                val effect = requireNotNull(latest)
                check(receipt.preparation === effect.preparation && receipt.preparation.effect == WorkspaceLocalEffect.DELETE_STAGE)
                val record = operation.effects.getValue(local.actionId(effect))
                check(record.phase == SafEffectPhase.VERIFIED && record.localVerified?.forcesCompleted == true &&
                    record.localVerified?.destination?.key == receipt.destinationIdentity && record.plan.local?.beforeInventory != null)
                record.plan.local?.deleteCustody?.let { custody ->
                    check(custody == deleteCustody)
                    ownedStorage.requireRealAncestors(File(localRoot, "${custody.directory}/original"))
                    check(ownedStorage.key(File(localRoot, custody.directory), true) == custody.directoryKey)
                    check(SafLocalFiles.version(ownedStorage, File(localRoot, "${custody.directory}/original")) == record.localVerified?.destination)
                }
                return true // Exact original inventory remains charged and owned; no recursive stage cleanup.
            }
            override fun finish(outcome: WorkspaceMutationOutcome) { /* Receipts already persisted; no replay or scans here. */ }
        }
    }

    suspend fun refreshFromTree(
        maxFiles: Int = MAX_MIRROR_FILES,
        maxBytes: Long = Long.MAX_VALUE,
        maxDepth: Int = MAX_MIRROR_DEPTH,
        onProgress: ((SafMirrorProgress) -> Unit)? = null,
    ): String = WorkspaceFileMutationLocks.withRoot(localRoot) { guard ->
        withContext(Dispatchers.IO) {
            checkPendingActivation(null)
            val job = currentCoroutineContext()[Job]
            val checkCancelled: () -> Unit = { job?.ensureActive(); guard.requireRoot(localRoot) }
            val admission = admitOwned(listOf(""), checkCancelled, job)
            val admittedLegacy = admission.legacy
            require(maxFiles > 0 && maxBytes > 0 && maxDepth > 0) { "Invalid SAF mirror limits" }
            var lastReport = 0L
            val report: (SafMirrorProgress) -> Unit = { progress ->
                val now = System.currentTimeMillis()
                if (now - lastReport > 64L || progress.phase != SafMirrorPhase.COPYING_EXTERNAL && progress.phase != SafMirrorPhase.SCANNING_LOCAL) {
                    lastReport = now
                // Progress presentation must never become a filesystem correctness dependency.
                runCatching { onProgress?.invoke(progress) }
                }
            }
            report(SafMirrorProgress(SafMirrorPhase.PREPARING))
            if (unchangedRefresh(admission, maxFiles, maxBytes, maxDepth, checkCancelled, report)) {
                return@withContext "SAF synchronized: ${admission.baseline.values.count { it.startsWith("f:") }} files"
            }
            val stage = File(localRoot.parentFile, ".${localRoot.name}.saf-stage-${UUID.randomUUID()}")
            val backup = File(localRoot.parentFile, ".${localRoot.name}.saf-backup-${UUID.randomUUID()}")
            ownedStorage.requireRealAncestors(stage)
            Files.createDirectory(stage.toPath()) // Private preflight copy, exclusive and never adopted on collision.
            val stageKey = ownedStorage.key(stage, true)

            var retainActivationEvidence = false
            var ownedBackupKey: String? = null
            var refreshFailure: Throwable? = null
            try {
                val external = copyExternalSnapshot(stage, maxFiles, maxBytes, maxDepth, checkCancelled, report)
                report(SafMirrorProgress(SafMirrorPhase.SCANNING_LOCAL))
                val local = scanLocal(localRoot, maxFiles, maxBytes, maxDepth, checkCancelled, report)
                report(SafMirrorProgress(SafMirrorPhase.RESOLVING, external.files, external.bytes))
                val previous = SafManifestFile(entries = admittedLegacy.baselineEntries)
                val resolution = resolve(previous, local.entries, external.entries, checkCancelled)

                if (resolution.conflicts.isNotEmpty()) {
                    val preview = resolution.conflicts.sorted().take(12).joinToString(", ")
                    throw IllegalStateException(
                        "SAF sync conflict: both local mirror and external folder changed ${resolution.conflicts.size} path(s): $preview" +
                            if (resolution.conflicts.size > 12) " …" else ""
                    )
                }

                val desired = resolvedEntries(external.entries, local.entries, resolution.localWinners).toMutableMap()
                applyLocalWinnersToStage(stage, resolution.localWinners, local.entries, checkCancelled, SafReadBudget(maxBytes))
                preserveLocalExecutableIntent(stage, local.entries, checkCancelled)
                val stagedSnapshot = scanLocal(stage, maxFiles, maxBytes, maxDepth, checkCancelled)
                check(stagedSnapshot.entries == desired) {
                    "SAF staging changed or is incomplete; no new provider mutation was started"
                }
                check(scanLocal(localRoot, maxFiles, maxBytes, maxDepth, checkCancelled).entries == local.entries) {
                    "Local SAF mirror changed while preparing synchronization"
                }
                currentCoroutineContext().ensureActive()
                report(SafMirrorProgress(SafMirrorPhase.APPLYING, external.files, external.bytes))
                currentCoroutineContext().ensureActive()
                // Progress callbacks and other writers can change the live source. Never publish those bytes.
                check(scanLocal(localRoot, maxFiles, maxBytes, maxDepth, checkCancelled).entries == local.entries) {
                    "Local SAF mirror changed before provider publication"
                }
                check(scanLocal(stage, maxFiles, maxBytes, maxDepth, checkCancelled).entries == desired) {
                    "SAF staging changed before provider publication"
                }
                // Validate every affected provider subtree before the first mutation, not just immediately
                // before each member of a multi-path update.
                val verificationBudget = SafReadBudget(maxBytes)
                check(treeNode(checkCancelled).identity == external.identities[""]) { "SAF root identity changed before publication" }
                for (path in collapsePaths(resolution.localWinners)) {
                    assertExternalUnchanged(path, external.entries, includeTree = true, checkCancelled,
                        verificationBudget, maxFiles, maxDepth, external.identities)
                }
                check(scanLocal(localRoot, maxFiles, maxBytes, maxDepth, checkCancelled).entries == local.entries) {
                    "Local SAF mirror changed during provider preflight"
                }
                checkCancelled()
                admittedLegacy.revalidateAndRequireDisjoint(localRoot, listOf(""), checkCancelled)
                val journal = initializeJournal(admission, listOf(""), checkCancelled)
                // Refresh has complete size observations before begin. Do not pin an unknown-stream maximum for
                // a tiny fixed activation and thereby prevent a disjoint small save.
                val retainedBudget = retainedPayloadBytes(local.bytes, stagedSnapshot.bytes, external.bytes, stagedSnapshot.bytes)
                val operation = journal.begin(SafOperationRequest(SafOperationKind.REFRESH, emptyList()), listOf(""), reservation(retainedBudget), checkCancelled)
                retainActivationEvidence = true
                val provider = ProviderPlan(guard, journal, operation, stage, external.entries, desired, checkCancelled, allowTypeReplacement = true)
                for (path in collapsePaths(resolution.localWinners)) {
                    if (local.entries[path] == null) provider.delete(path) else provider.synchronize(path)
                }
                ownedStorage.createDirectory(backup)
                ownedBackupKey = ownedStorage.key(backup, true)
                val artifacts = listOf(
                    SafLocalArtifact(SafLocalArtifactKind.LOCAL_ROOT, localRoot.absolutePath, journal.anchor.workspaceKey),
                    SafLocalArtifact(SafLocalArtifactKind.STAGE, stage.absolutePath, stageKey),
                    SafLocalArtifact(SafLocalArtifactKind.BACKUP, backup.absolutePath, requireNotNull(ownedBackupKey)))
                val original = SafActivationFiles.capture(journal, operation, artifacts[0], checkCancelled)
                val staged = SafActivationFiles.capture(journal, operation, artifacts[1], checkCancelled)
                val originalRef = journal.preserveActivationInventory(operation, original, checkCancelled)
                val stageRef = journal.preserveActivationInventory(operation, staged, checkCancelled)

                retainActivationEvidence = true
                admittedLegacy.revalidateAndRequireDisjoint(localRoot, listOf(""), checkCancelled)
                provider.execute()
                check(scanLocal(localRoot, maxFiles, maxBytes, maxDepth, checkCancelled).entries == local.entries) {
                    "Local SAF mirror changed during provider publication; local activation was not started"
                }
                report(SafMirrorProgress(SafMirrorPhase.FINALIZING, external.files, external.bytes))
                val action = journal.prepare(operation, SafEffectPlan(SafEffectKind.LOCAL_ACTIVATION, "", null, null,
                    null, null, null, localArtifacts = artifacts), checkCancelled)
                val certificate = journal.containActivation(action, originalRef, stageRef, checkCancelled)
                val roots = recordPendingActivation(stage, backup)
                check(roots == certificate.roots.toSet())
                journal.attempting(action, checkCancelled)
                // Deep cancellable checks stay outside the bounded move/return finalizer.
                check(SafActivationFiles.capture(journal, operation, artifacts[0], checkCancelled).entries == original.entries)
                check(SafActivationFiles.capture(journal, operation, artifacts[1], checkCancelled).entries == staged.entries)
                try {
                    swapStageIntoLocal(stage, backup, roots, original, staged, artifacts[2], checkCancelled)
                } catch (failure: Throwable) {
                    try { journal.returned(action, SafEffectReturn.THREW, SafProviderReturn(SafProviderReturnShape.THREW,
                        diagnosticClass = failure.javaClass.name.take(64), diagnosticMessage = failure.message?.take(1000))) }
                    catch (recordFailure: Throwable) { if (recordFailure !== failure) failure.addSuppressed(recordFailure) }
                    throw failure
                }
                journal.returned(action, SafEffectReturn.COMPLETED, SafProviderReturn(SafProviderReturnShape.VOID))
                SafActivationFiles.forceAndVerify(journal, operation, staged, artifacts[0], checkCancelled)
                SafActivationFiles.forceAndVerify(journal, operation, original, artifacts[2], checkCancelled)
                check(SafActivationFiles.capture(journal, operation, artifacts[1], checkCancelled).entries.isEmpty())
                journal.verified(action, checkActive = checkCancelled)
                publishOwned(journal, operation, admission.baseline, desired, checkCancelled)
                check(activationMarker.delete()) {
                    "SAF activation completed but its recovery marker could not be cleared; staging and original backup were retained."
                }
                ownedStorage.syncDirectory(requireNotNull(activationMarker.parentFile))
                // Never recursively clean a replacement or a forest with new descendant bytes.
                check(SafActivationFiles.capture(journal, operation, artifacts[2], checkCancelled).entries == original.entries)
                check(ownedStorage.key(stage, true) == stageKey && stage.list()!!.isEmpty())
                retainActivationEvidence = false
                "SAF synchronized: ${desired.values.count { it.startsWith("f:") }} files"
            } catch (failure: Throwable) {
                refreshFailure = failure
                throw failure
            } finally {
                if (!retainActivationEvidence) {
                    val cleanupFailure = runCatching {
                        val stageRemoved = cleanupOwnedArtifact(stage, stageKey)
                        val backupRemoved = cleanupOwnedArtifact(backup, ownedBackupKey)
                        check(stageRemoved && backupRemoved) { "SAF temporary cleanup incomplete; remaining files were retained." }
                    }.exceptionOrNull()
                    if (cleanupFailure != null) {
                        val originalFailure = refreshFailure
                        if (originalFailure != null) originalFailure.addSuppressed(cleanupFailure)
                        else throw cleanupFailure
                    }
                }
            }
        }
    }

    private class NotUnchanged : Exception()
    private class UnchangedMetadata {
        var bytes = 0L
            private set
        fun add(vararg strings: String) {
            val charge = 128L + strings.sumOf { it.length.toLong() * 2L }
            if (charge > 16L * 1024 * 1024 - bytes) throw NotUnchanged()
            bytes += charge
        }
    }
    private data class UnchangedLocal(val key: String, val parentKey: String?, val directory: Boolean,
        val fingerprint: String, val children: Int, val version: SafLocalVersion)
    private data class UnchangedExternal(val identity: SafProviderIdentity, val parentId: String?,
        val fingerprint: String, val children: Int)

    private fun unchangedRefresh(admission: OwnedAdmission, maxFiles: Int, maxBytes: Long, maxDepth: Int,
        poll: () -> Unit, report: (SafMirrorProgress) -> Unit): Boolean {
        if (admission.initiallyPending || admission.mutatedDuringAdmission ||
            admission.legacy.manifestState !in setOf(SafLegacyManifestState.JSON_V2, SafLegacyManifestState.OWNED)) return false
        val metadata = UnchangedMetadata()
        val external: Map<String, UnchangedExternal>
        val local: Map<String, UnchangedLocal>
        try {
            external = unchangedExternal(admission.baseline, maxFiles, maxBytes, maxDepth, metadata, poll)
            local = unchangedLocal(admission.baseline, maxFiles, maxBytes, maxDepth, metadata, poll)
            // Reserve the other half for the equally bounded second pass, including traversal state.
            if (metadata.bytes > 8L * 1024 * 1024) return false
        } catch (_: NotUnchanged) { return false }
        // This is the last user progress callback. All payload/identity/admission proof follows it.
        report(SafMirrorProgress(SafMirrorPhase.RESOLVING, admission.baseline.values.count { it.startsWith("f:") }))
        try {
            unchangedExternal(admission.baseline, maxFiles, maxBytes, maxDepth, metadata, poll, external)
            // Finish all provider callbacks before the final local content proof. A provider can mutate
            // local bytes from a query callback even without changing their filesystem key.
            revalidateUnchangedExternal(external, maxFiles, poll)
            unchangedLocal(admission.baseline, maxFiles, maxBytes, maxDepth, metadata, poll, local)
        } catch (_: NotUnchanged) {
            error("SAF tree changed during unchanged Refresh verification; no synchronization was started")
        }
        revalidateUnchangedLocal(local, maxFiles, poll)
        checkPendingActivation(null)
        admission.legacy.revalidateAndRequireDisjoint(localRoot, listOf(""), poll)
        val journal = admission.journal
        if (journal == null) {
            ownedStorage.requireRealAncestors(admission.directory)
            check(ownedStorage.attributes(admission.directory) == null) { "SAF owned history appeared during unchanged Refresh" }
        } else {
            val operations = journal.recoverAll(poll)
            check(operations.all(journal::isTerminal)) { "SAF pending evidence appeared during unchanged Refresh" }
            val publisher = SafDurableManifestCommit(journal)
            check(publisher.admitBeforeEffects(operations, poll) == admission.visible)
            val trusted = publisher.trusted(operations, poll)
            check(trusted.revision == admission.revision && trusted.digest == admission.digest && trusted.entries == admission.baseline)
            publisher.requireAdmittedVisible(admission.visible, poll)
        }

        admission.legacy.revalidateAndRequireDisjoint(localRoot, listOf(""), poll)
        poll()
        return true
    }

    private fun unchangedDigest(input: java.io.InputStream, budget: SafReadBudget, external: Boolean,
        poll: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { source ->
            val buffer = ByteArray(SAF_COPY_BUFFER_BYTES)
            while (true) {
                poll()
                val count = readSafChunk(source, buffer, poll)
                poll()
                if (count < 0) break
                check(count in 1..buffer.size)
                budget.consume(count)
                digest.update(buffer, 0, count)
                unchangedReadObserver?.invoke(external, count)
                poll()
            }
        }
        return "f:${digest.digest().toHex()}"
    }

    private fun unchangedExternal(baseline: Map<String, String>, maxFiles: Int, maxBytes: Long, maxDepth: Int,
        metadata: UnchangedMetadata, poll: () -> Unit,
        expected: Map<String, UnchangedExternal>? = null): Map<String, UnchangedExternal> {
        data class Work(val node: SafDocumentNode, val path: String, val parent: String?, val depth: Int)
        val out = linkedMapOf<String, UnchangedExternal>()
        val pending = java.util.ArrayDeque<Work>()
        val visited = hashSetOf<String>()
        val budget = SafReadBudget(maxBytes)
        var files = 0
        var entries = 0
        fun enqueue(node: SafDocumentNode, path: String, parent: String?, depth: Int) {
            poll()
            require(depth <= maxDepth) { "SAF project nesting exceeds $maxDepth levels" }
            check(visited.add(node.documentId)) { "SAF tree repeats a document identity" }
            check(++entries <= entryLimit(maxFiles) + 1) { "SAF project exceeds its total entry limit" }
            metadata.add(path, node.documentId, node.uri.toString(), node.name, node.mimeType, parent.orEmpty(), "f:" + "0".repeat(64))
            pending.addLast(Work(node, path, parent, depth))
        }
        val root = treeNode(poll)
        enqueue(root, "", null, 0)
        while (pending.isNotEmpty()) {
            poll()
            val work = pending.removeLast()
            val node = work.node
            var children = 0
            val fingerprint = if (node.isDirectory) {
                val allowance = if (work.path.isEmpty()) 2 else 0
                for (child in query.listChildren(node.documentId, entryLimit(maxFiles) - entries + 1 + allowance, poll)) {
                    val path = if (work.path.isEmpty()) child.name else "${work.path}/${child.name}"
                    if (isRuntimePath(path)) continue
                    children++
                    enqueue(child, path, node.documentId, work.depth + 1)
                }
                DIR
            } else {
                check(++files <= maxFiles) { "SAF project exceeds $maxFiles files" }
                unchangedDigest(requireNotNull(context.contentResolver.openInputStream(node.uri)), budget, true, poll)
            }
            check(query.readDocument(node.documentId, poll)?.identity == node.identity) { "SAF identity changed during unchanged Refresh" }
            val value = UnchangedExternal(node.identity, work.parent, fingerprint, children)
            if (work.path.isNotEmpty() && baseline[work.path] != fingerprint) throw NotUnchanged()
            if (expected == null) out[work.path] = value
            else check(expected[work.path] == value) { "SAF provider identity or membership changed during unchanged Refresh" }
        }
        if (entries != baseline.size + 1) throw NotUnchanged()
        return expected ?: out
    }

    private fun unchangedLocal(baseline: Map<String, String>, maxFiles: Int, maxBytes: Long, maxDepth: Int,
        metadata: UnchangedMetadata, poll: () -> Unit,
        expected: Map<String, UnchangedLocal>? = null): Map<String, UnchangedLocal> {
        data class Work(val file: File, val path: String, val parent: String?, val depth: Int,
            val key: String, val directory: Boolean, val version: SafLocalVersion)
        val out = linkedMapOf<String, UnchangedLocal>()
        val pending = java.util.ArrayDeque<Work>()
        val visited = hashSetOf<String>()
        val budget = SafReadBudget(maxBytes)
        var files = 0
        var entries = 0
        fun enqueue(file: File, path: String, parent: String?, depth: Int) {
            poll()
            require(depth <= maxDepth) { "Local SAF mirror nesting exceeds $maxDepth levels" }
            val attributes = requireNotNull(ownedStorage.attributes(file))
            require(!attributes.isSymbolicLink && (attributes.isRegularFile || attributes.isDirectory)) { "SAF refresh cannot represent symbolic links or special paths" }
            val key = requireNotNull(attributes.fileKey()).toString()
            val version = SafLocalVersion(if (attributes.isDirectory) SafLocalKind.DIRECTORY else SafLocalKind.FILE,
                key, attributes.size(), attributes.lastModifiedTime().toString())
            check(visited.add(key)) { "SAF local tree repeats a filesystem identity" }
            check(++entries <= entryLimit(maxFiles) + 1) { "Local SAF mirror exceeds its total entry limit" }
            metadata.add(path, file.absolutePath, key, parent.orEmpty(), version.modified, "f:" + "0".repeat(64))
            pending.addLast(Work(file, path, parent, depth, key, attributes.isDirectory, version))
        }
        ownedStorage.requireRealAncestors(localRoot)
        check(ownedStorage.attributes(localRoot)?.isDirectory == true)
        enqueue(localRoot, "", null, 0)
        while (pending.isNotEmpty()) {
            poll()
            val work = pending.removeLast()
            ownedStorage.requireRealAncestors(work.file)
            check(SafLocalFiles.version(ownedStorage, work.file) == work.version)
            var children = 0
            val fingerprint = if (work.directory) {
                Files.newDirectoryStream(work.file.toPath()).use { stream ->
                    for (child in stream) {
                        poll()
                        val name = PathSecurity.safeLeafName(child.fileName.toString())
                        val path = if (work.path.isEmpty()) name else "${work.path}/$name"
                        if (isRuntimePath(path)) continue
                        children++
                        enqueue(child.toFile(), path, work.key, work.depth + 1)
                    }
                }
                DIR
            } else {
                check(++files <= maxFiles) { "Local SAF mirror exceeds $maxFiles files" }
                unchangedDigest(Files.newInputStream(work.file.toPath(), LinkOption.NOFOLLOW_LINKS), budget, false, poll)
            }
            ownedStorage.requireRealAncestors(work.file)
            check(SafLocalFiles.version(ownedStorage, work.file) == work.version)
            val value = UnchangedLocal(work.key, work.parent, work.directory, fingerprint, children, work.version)
            if (work.path.isNotEmpty() && baseline[work.path] != fingerprint) throw NotUnchanged()
            if (expected == null) out[work.path] = value
            else check(expected[work.path] == value) { "SAF local identity or membership changed during unchanged Refresh" }
        }
        if (entries != baseline.size + 1) throw NotUnchanged()
        return expected ?: out
    }

    private fun revalidateUnchangedExternal(proof: Map<String, UnchangedExternal>, maxFiles: Int, poll: () -> Unit) {
        for ((path, node) in proof) {
            poll()
            check(query.readDocument(node.identity.documentId, poll)?.identity == node.identity)
            if (node.fingerprint != DIR) continue
            var children = 0
            for (child in query.listChildren(node.identity.documentId, entryLimit(maxFiles) + if (path.isEmpty()) 2 else 0, poll)) {
                val relative = if (path.isEmpty()) child.name else "$path/${child.name}"
                if (isRuntimePath(relative)) continue
                val expected = proof[relative]
                check(expected != null && expected.identity == child.identity && expected.parentId == node.identity.documentId) {
                    "SAF provider membership changed during unchanged Refresh"
                }
                children++
            }
            check(children == node.children)
        }
    }

    private fun revalidateUnchangedLocal(proof: Map<String, UnchangedLocal>, maxFiles: Int, poll: () -> Unit) {
        for ((path, node) in proof) {
            poll()
            val file = if (path.isEmpty()) localRoot else File(localRoot, path)
            ownedStorage.requireRealAncestors(file)
            check(SafLocalFiles.version(ownedStorage, file) == node.version)
            if (!node.directory) continue
            var children = 0
            Files.newDirectoryStream(file.toPath()).use { stream ->
                for (child in stream) {
                    poll()
                    val name = PathSecurity.safeLeafName(child.fileName.toString())
                    val relative = if (path.isEmpty()) name else "$path/$name"
                    if (isRuntimePath(relative)) continue
                    check(++children <= entryLimit(maxFiles))
                    val expected = proof[relative]
                    check(expected != null && expected.parentKey == node.key &&
                        SafLocalFiles.version(ownedStorage, child.toFile()) == expected.version) {
                        "SAF local membership changed during unchanged Refresh"
                    }
                }
            }
            check(children == node.children)
        }
    }

    private fun cleanupOwnedArtifact(file: File, ownedKey: String?): Boolean {
        if (ownedKey == null) return true // A chosen pathname or absence never establishes cleanup ownership.
        ownedStorage.requireRealAncestors(file)
        check(ownedStorage.key(file, true) == ownedKey) { "Unowned SAF artifact path was retained" }
        return PathSecurity.deleteTreeNoFollow(file)
    }

    suspend fun apply(mutation: FileRepository.Mutation) = WorkspaceFileMutationLocks.withRoot(localRoot) { guard ->
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()[Job]
            val poll: () -> Unit = { job?.ensureActive(); guard.requireRoot(localRoot) }
            val request = mutationRequest(mutation)
            val admission = admitOwned(request.paths, poll, job)
            requireDirectTypesUnchanged(mutation, poll)
            if (mutation is FileRepository.Mutation.CreateDirectory && admission.baseline[mutation.path] == DIR) {
                assertExternalUnchanged(mutation.path, admission.baseline, includeTree = false, poll)
                admission.legacy.revalidateAndRequireDisjoint(localRoot, request.paths, poll)
                admission.journal?.let { journal ->
                    val scopes = authorityScopes(request.paths, poll)
                    journal.recoverAll(poll).filterNot(journal::isTerminal).forEach {
                        journal.requireDisjointFromPending(request, scopes, emptyList(), it, poll)
                    }
                }
                return@withContext
            }
            val journal = initializeJournal(admission, request.paths, poll)
            val operation = journal.begin(request, authorityScopes(request.paths, poll), directReservation(request.paths, poll), poll)
            synchronizeOwned(guard, mutation, admission, operation, poll)
        }
    }

    private fun synchronizeOwned(guard: WorkspaceMutationGuard, mutation: FileRepository.Mutation, admission: OwnedAdmission,
        operation: SafOperationJournal.Operation, poll: () -> Unit, beforeEffects: () -> Unit = {}) {
        val paths = mutationRequest(mutation).paths
        val journal = requireNotNull(admission.journal)
        val known = admission.baseline
        paths.forEach { assertExternalUnchanged(it, known, includeTree = mutation !is FileRepository.Mutation.Write, poll) }
        requireDirectTypesUnchanged(mutation, poll)
        val desired = known.toMutableMap()
        val replacements = linkedSetOf<String>()
        val deletions = linkedSetOf<String>()
        when (mutation) {
            is FileRepository.Mutation.Delete -> { removeKnownTree(desired, mutation.path); deletions += mutation.path }
            is FileRepository.Mutation.Move -> {
                removeKnownTree(desired, mutation.from); removeKnownTree(desired, mutation.to)
                desired.putAll(localPathEntries(mutation.to, poll)); replacements += mutation.to; deletions += mutation.from
            }
            is FileRepository.Mutation.CreateDirectory -> { desired[mutation.path] = DIR; replacements += mutation.path }
            is FileRepository.Mutation.Write -> {
                desired.putAll(localPathEntries(mutation.path, poll)); replacements += mutation.path
            }
            is FileRepository.Mutation.Sync -> {
                removeKnownTree(desired, mutation.path)
                val entries = localPathEntries(mutation.path, poll)
                desired.putAll(entries)
                if (entries.isEmpty()) deletions += mutation.path else replacements += mutation.path
            }
        }
        val provider = ProviderPlan(guard, journal, operation, localRoot, known, desired, poll)
        deletions.forEach(provider::delete)
        if (mutation is FileRepository.Mutation.CreateDirectory) provider.directoryOnly(mutation.path)
        else replacements.forEach(provider::synchronize)
        SafDurableManifestCommit.validateEntries(desired, poll)
        admission.legacy.revalidateAndRequireDisjoint(localRoot, paths, poll)
        poll()
        beforeEffects()
        provider.execute()
        // Local receipts and immutable outbound copies prevent a later canonical edit being
        // acknowledged.
        replacements.filterNot { mutation is FileRepository.Mutation.CreateDirectory }.forEach { path ->
            check(localPathEntries(path, poll) == desired.filterKeys { it == path || it.startsWith("$path/") }) {
                "Local SAF source changed during synchronization; owned evidence retained"
            }
        }
        publishOwned(journal, operation, known, desired, poll)
    }

    private fun retainedPayloadBytes(vararg sizes: Long): Long =
        sizes.fold(64L * 1024 * 1024) { total, bytes ->
            SafOperationJournal.boundedAdd(total, bytes, Long.MAX_VALUE)
        }

    /** After-only calls reserve observed bytes; unknown provider streams use physical free space. */
    private fun directReservation(paths: List<String>, poll: () -> Unit): SafJournalReservation {
        var localBytes = 0L
        var providerBytes = 0L
        var unknownProviderSize = false
        var entries = 0
        var files = 0
        var localEntries = 0
        var localFiles = 0
        val identities = hashSetOf<String>()
        fun provider(node: SafDocumentNode, depth: Int) {
            poll(); require(depth <= MAX_MIRROR_DEPTH)
            check(++entries <= entryLimit(MAX_MIRROR_FILES) && identities.add(node.documentId))
            if (node.isDirectory) query.listChildren(node.documentId, entryLimit(MAX_MIRROR_FILES) - entries, poll).forEach { provider(it, depth + 1) }
            else {
                check(++files <= MAX_MIRROR_FILES)
                if (node.size < 0) unknownProviderSize = true
                else providerBytes = SafOperationJournal.boundedAdd(providerBytes, node.size, Long.MAX_VALUE)
            }
        }
        for (path in collapsePaths(paths.toSet())) {
            val file = File(localRoot, path)
            ownedStorage.requireRealAncestors(file)
            if (ownedStorage.attributes(file) != null) {
                val depth = MAX_MIRROR_DEPTH - path.count { it == '/' }
                Files.walkFileTree(file.toPath(), emptySet(), depth, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        poll(); check(++localEntries <= entryLimit(MAX_MIRROR_FILES)); return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(node: Path, attrs: BasicFileAttributes): FileVisitResult {
                        poll(); require(attrs.isRegularFile && !attrs.isSymbolicLink)
                        check(++localEntries <= entryLimit(MAX_MIRROR_FILES) && ++localFiles <= MAX_MIRROR_FILES)
                        localBytes = SafOperationJournal.boundedAdd(localBytes, attrs.size(), Long.MAX_VALUE)
                        return FileVisitResult.CONTINUE
                    }
                })
            }
            findNode(path, poll)?.let { provider(it, path.count { c -> c == '/' } + 1) }
        }
        return reservation(retainedPayloadBytes(localBytes,
            if (unknownProviderSize) maxOf(providerBytes, localRoot.usableSpace) else providerBytes))
    }

    private fun localPathEntries(path: String, poll: () -> Unit): Map<String, String> {
        SafOperationJournal.validPath(path, false)
        val file = File(localRoot, path)
        ownedStorage.requireRealAncestors(file)
        val attrs = ownedStorage.attributes(file) ?: return emptyMap()
        require(!attrs.isSymbolicLink && (attrs.isDirectory || attrs.isRegularFile)) { "SAF cannot synchronize symbolic links or special paths: $path" }
        if (attrs.isRegularFile) return mapOf(path to fingerprintFile(file, poll))
        val children = scanLocal(file, MAX_MIRROR_FILES, Long.MAX_VALUE, MAX_MIRROR_DEPTH - path.count { it == '/' } - 1, poll, pathPrefix = path)
        return linkedMapOf(path to DIR).apply { children.entries.forEach { (child, value) -> put("$path/$child", value) } }
    }

    private fun publishOwned(journal: SafOperationJournal, operation: SafOperationJournal.Operation,
        baseline: Map<String, String>, desired: Map<String, String>, poll: () -> Unit) {
        val delta = sortedMapOf<String, String?>()
        (baseline.keys + desired.keys).forEach { path -> poll(); if (baseline[path] != desired[path]) delta[path] = desired[path] }
        val publisher = SafDurableManifestCommit(journal)
        publisher.plan(operation, delta, poll)
        val result = publisher.publish(operation, poll)
        if (!result.acknowledged) throw IllegalStateException("SAF manifest publication is awaiting its exact acknowledgement; evidence retained",
            result.retainedAcknowledgementFailure)
    }

    private inner class ProviderPlan(guard: WorkspaceMutationGuard, private val journal: SafOperationJournal,
        private val operation: SafOperationJournal.Operation, private val sourceRoot: File,
        private val before: Map<String, String>, private val desired: MutableMap<String, String>, private val poll: () -> Unit,
        private val allowTypeReplacement: Boolean = false) {
        private val effects = SafProviderEffects(guard, journal, operation, SafProviderEffects.android(context, uri), poll)
        private val steps = arrayListOf<Pair<SafEffectKind, SafProviderEffects.Effect>>()
        private val directories = hashMapOf<String, Pair<SafOwnedDocument?, String?>>()
        private val planned = hashSetOf<String>()
        private val deleted = hashSetOf<String>()
        private val replacements = hashMapOf<String, String>()

        private fun document(path: String): SafOwnedDocument? {
            var node = treeNode(poll)
            var parentId = node.documentId
            for (part in segments(path)) {
                poll(); check(node.isDirectory)
                parentId = node.documentId
                node = query.findChild(parentId, part, poll) ?: return null
            }
            return SafOwnedDocument(node.uri.toString(), node.documentId, node.name, node.mimeType, parentId)
        }
        private fun directory(path: String): Pair<SafOwnedDocument?, String?> = directories.getOrPut(path) {
            if (path.isNotEmpty()) desired[path] = DIR
            val parent = if (path.isEmpty()) null else directory(path.substringBeforeLast('/', ""))
            val replacement = replacements[path]
            val existing = if (replacement != null || parent?.second != null) null else document(path)
            if (existing != null) {
                check(existing.mimeType == SafOperationJournal.DIRECTORY_MIME) { "SAF type replacement requires snapshot-backed recovery: $path" }
                existing to null
            } else {
                require(path.isNotEmpty())
                val admittedParent = requireNotNull(parent)
                val action = effects.prepare(SafEffectPlan(SafEffectKind.CREATE_DIRECTORY, path, null, admittedParent.first, null, null, null,
                    requestedMimeType = SafOperationJournal.DIRECTORY_MIME, parentCreationActionId = admittedParent.second,
                    replacementDeleteActionId = replacement))
                steps += SafEffectKind.CREATE_DIRECTORY to action
                null to effects.actionId(action)
            }
        }
        fun directoryOnly(path: String) { directory(path) }
        fun delete(path: String) { prepareDelete(path) }
        private fun prepareDelete(path: String): SafProviderEffects.Effect? {
            require(deleted.add(path))
            val original = document(path) ?: return null
            val parent = requireNotNull(document(path.substringBeforeLast('/', "")))
            val before = effects.captureBeforeTree(path, original)
            val captured = before.image.entries.associate { entry ->
                val relative = path + if (entry.relativePath.isEmpty()) "" else "/${entry.relativePath}"
                relative to if (entry.document.mimeType == SafOperationJournal.DIRECTORY_MIME) DIR else "f:${requireNotNull(entry.payload).identity.sha256}"
            }
            check(captured == this.before.filterKeys { it == path || it.startsWith("$path/") }) { "SAF delete beforeimage changed after admission: $path" }
            return effects.prepare(SafEffectPlan(SafEffectKind.DELETE, path, original, parent,
                null, null, null, beforeTree = before.reference)).also { steps += SafEffectKind.DELETE to it }
        }
        fun synchronize(path: String) {
            val entries = desired.filterKeys { it == path || it.startsWith("$path/") }
            require(entries.isNotEmpty()) { "Local SAF source missing: $path" }
            if (allowTypeReplacement) entries.keys.forEach { relative ->
                poll()
                var parent = relative.substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    check(desired[parent] == DIR) { "Invalid SAF desired hierarchy: $relative" }
                    parent = parent.substringBeforeLast('/', "")
                }
            }
            val replaced = collapsePaths(entries.keys.filter { relative ->
                poll(); before[relative]?.let { (it == DIR) != (entries.getValue(relative) == DIR) } == true
            }.toSet())
            check(replaced.isEmpty() || allowTypeReplacement && operation.envelope.request.kind == SafOperationKind.REFRESH) {
                "SAF type replacement requires snapshot-backed full Refresh"
            }
            replaced.forEach { root -> replacements[root] = effects.actionId(requireNotNull(prepareDelete(root))) }
            val removed = before.keys.filter { relative ->
                poll()
                (relative == path || relative.startsWith("$path/")) && relative !in desired &&
                    replaced.none { relative == it || relative.startsWith("$it/") }
            }.toSet()
            collapsePaths(removed).forEach(::delete)
            entries.keys.sortedWith(compareBy<String> { it.count { c -> c == '/' } }.thenBy { it }).forEach { relative ->
                poll()
                require(planned.add(relative))
                if (entries.getValue(relative) == DIR) { directory(relative); return@forEach }
                val parent = directory(relative.substringBeforeLast('/', ""))
                val replacement = replacements[relative]
                val original = if (parent.second == null && replacement == null) document(relative) else null
                check(original?.mimeType != SafOperationJournal.DIRECTORY_MIME) { "SAF type replacement requires snapshot-backed recovery: $relative" }
                val localWrite = operation.effects.values.singleOrNull { it.plan.kind == SafEffectKind.LOCAL_MUTATION &&
                    it.plan.local?.effect == SafLocalEffect.WRITE && it.plan.path == relative }
                val intended = if (localWrite != null) {
                    check(localWrite.phase == SafEffectPhase.VERIFIED)
                    requireNotNull(localWrite.plan.local?.intended)
                } else journal.preserve(operation, File(sourceRoot, relative), poll)
                check("f:${intended.identity.sha256}" == entries.getValue(relative)) { "Verified outbound SAF bytes changed" }
                val previous = if (original != null) journal.preserveInput(operation,
                    requireNotNull(context.contentResolver.openInputStream(Uri.parse(original.uri))), Long.MAX_VALUE, poll) else null
                check(if (previous == null) before[relative] == null || replacement != null else before[relative] == "f:${previous.identity.sha256}") {
                    "SAF write beforeimage changed after admission: $relative"
                }
                var creation: String? = null
                if (original == null) {
                    val create = effects.prepare(SafEffectPlan(SafEffectKind.CREATE_FILE, relative, null, parent.first, null, null, null,
                        requestedMimeType = "application/octet-stream", parentCreationActionId = parent.second,
                        replacementDeleteActionId = replacement))
                    steps += SafEffectKind.CREATE_FILE to create
                    creation = effects.actionId(create)
                }
                val write = effects.prepare(SafEffectPlan(SafEffectKind.WRITE_FILE, relative, original, parent.first, creation, previous, intended,
                    parentCreationActionId = parent.second))
                steps += SafEffectKind.WRITE_FILE to write
            }
        }
        fun execute() {
            // Cancellation and complete reads remain enabled; each helper records the raw return without
            // polling.
            steps.forEach { (kind, action) ->
                poll()
                when (kind) {
                    SafEffectKind.CREATE_DIRECTORY -> effects.createDirectory(action)
                    SafEffectKind.CREATE_FILE -> effects.createFile(action)
                    SafEffectKind.WRITE_FILE -> effects.writeFile(action)
                    SafEffectKind.DELETE -> effects.deleteTree(action)
                    else -> error("Unsupported provider step")
                }
            }
        }
    }

    // Recheck authoritative state at commit boundaries to avoid stale writes.

    private fun assertExternalUnchanged(path: String, known: Map<String, String>, includeTree: Boolean, checkCancelled: () -> Unit = {},
        budget: SafReadBudget = SafReadBudget(Long.MAX_VALUE), maxFiles: Int = MAX_MIRROR_FILES, maxDepth: Int = MAX_MIRROR_DEPTH,
        identities: Map<String, SafProviderIdentity>? = null) {
        val clean = path.trim('/').replace('\\', '/')
        require(clean.isNotBlank()) { "Empty SAF path" }

        val parts = segments(clean)
        for (i in 1 until parts.size) {
            checkCancelled()
            val ancestor = parts.take(i).joinToString("/")
            val expected = known[ancestor]
            val actualNode = findNode(ancestor, checkCancelled)
            if (identities != null && actualNode?.identity != identities[ancestor]) throw safConcurrentConflict(ancestor)
            val actual = when {
                actualNode == null -> null
                actualNode.isDirectory -> DIR
                actualNode.isFile -> fingerprintExternalNode(actualNode, ancestor, checkCancelled, budget)
                else -> null
            }
            if (expected != actual) throw safConcurrentConflict(ancestor)
        }

        val expected = if (includeTree) {
            known.filterKeys { it == clean || it.startsWith("$clean/") }
        } else {
            known[clean]?.let { mapOf(clean to it) } ?: emptyMap()
        }
        val actual = snapshotExternalPath(clean, includeTree, checkCancelled, budget, maxFiles, maxDepth, identities)
        if (expected != actual) throw safConcurrentConflict(clean)
    }

    private fun safConcurrentConflict(path: String) = IllegalStateException(
        "SAF sync conflict at '$path': the external folder changed after the last sync. Refresh before retrying; no external data was overwritten."
    )

    private fun snapshotExternalPath(path: String, includeTree: Boolean, checkCancelled: () -> Unit = {},
        budget: SafReadBudget = SafReadBudget(Long.MAX_VALUE), maxFiles: Int = MAX_MIRROR_FILES, maxDepth: Int = MAX_MIRROR_DEPTH,
        identities: Map<String, SafProviderIdentity>? = null): Map<String, String> {
        val clean = path.trim('/').replace('\\', '/')
        val node = findNode(clean, checkCancelled) ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        var files = 0
        val visited = hashSetOf<String>()

        fun visit(cur: SafDocumentNode, rel: String, depth: Int) {
            checkCancelled()
            require(depth <= maxDepth) { "SAF path nesting exceeds $maxDepth levels" }
            check(visited.add(cur.documentId)) { "SAF tree repeats a document identity" }
            if (identities != null && cur.identity != identities[rel]) throw safConcurrentConflict(rel)
            check(out.size < entryLimit(maxFiles)) { "SAF path exceeds its total entry limit" }
            when {
                cur.isDirectory -> {
                    out[rel] = DIR
                    if (!includeTree && rel == clean) return
                    for (child in query.listChildren(cur.documentId, entryLimit(maxFiles) - out.size, checkCancelled)) {
                        val childRel = "$rel/${child.name}"
                        if (!isRuntimePath(childRel)) visit(child, childRel, depth + 1)
                    }
                }
                cur.isFile -> {
                    if (++files > maxFiles) throw IllegalStateException("SAF path exceeds $maxFiles files")
                    out[rel] = fingerprintExternalNode(cur, rel, checkCancelled, budget)
                }
            }
        }
        visit(node, clean, 0)
        return out
    }

    private fun fingerprintExternalNode(file: SafDocumentNode, rel: String, checkCancelled: () -> Unit = {},
        budget: SafReadBudget = SafReadBudget(Long.MAX_VALUE)): String {
        check(file.isFile) { "SAF path is not a file: $rel" }
        checkCancelled()
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(file.uri)
            ?: throw IllegalStateException("Cannot read SAF file while checking conflict: $rel")
        input.use { source ->
            val buffer = ByteArray(SAF_COPY_BUFFER_BYTES)
            while (true) {
                checkCancelled()
                val n = readSafChunk(source, buffer, checkCancelled)
                checkCancelled()
                if (n < 0) break
                if (n == 0) continue
                budget.consume(n)
                digest.update(buffer, 0, n)
            }
        }
        check(query.readDocument(file.documentId, checkCancelled)?.identity == file.identity) {
            "SAF file identity changed while reading: $rel"
        }
        return "f:${digest.digest().toHex()}"
    }

    private data class Snapshot(val entries: Map<String, String>, val files: Int, val bytes: Long,
        val identities: Map<String, SafProviderIdentity> = emptyMap())
    private data class Resolution(val localWinners: Set<String>, val conflicts: Set<String>)

    private fun copyExternalSnapshot(
        stage: File,
        maxFiles: Int,
        maxBytes: Long,
        maxDepth: Int,
        checkCancelled: () -> Unit = {},
        onProgress: (SafMirrorProgress) -> Unit = {},
    ): Snapshot {
        return copyExternalSnapshotDirect(stage, maxFiles, maxBytes, maxDepth, checkCancelled, onProgress)
    }

    private fun copyExternalSnapshotDirect(
        stage: File,
        maxFiles: Int,
        maxBytes: Long,
        maxDepth: Int,
        checkCancelled: () -> Unit = {},
        onProgress: (SafMirrorProgress) -> Unit = {},
    ): Snapshot {
        val query = SafDocumentsTreeQuery(context, uri)
        val rootId = query.rootDocumentId()
        val rootIdentity = query.readDocument(rootId, checkCancelled)?.takeIf { it.isDirectory }?.identity
            ?: error("SAF tree permission or directory identity unavailable")
        val entries = linkedMapOf<String, String>()
        val identities = linkedMapOf("" to rootIdentity)
        val visitedIds = hashSetOf(rootId)
        val maxEntries = entryLimit(maxFiles)
        var files = 0
        var bytes = 0L
        var lastProgressBytes = -SAF_PROGRESS_INTERVAL_BYTES

        onProgress(SafMirrorProgress(SafMirrorPhase.COPYING_EXTERNAL))

        fun copyFile(src: SafDocumentNode, dst: File, rel: String) {
            checkCancelled()
            if (++files > maxFiles) throw IllegalStateException("SAF project exceeds $maxFiles files")
            if (src.size >= 0L && src.size > maxBytes - bytes) {
                throw IllegalStateException("SAF project exceeds ${maxBytes / (1024 * 1024)} MiB mirror limit")
            }
            ensureStagingFreeSpace(stage, src.size.coerceAtLeast(0L))
            dst.parentFile?.mkdirs()
            val tmp = File(dst.parentFile, ".${dst.name}.saf-tmp-${System.nanoTime()}")
            val digest = MessageDigest.getInstance("SHA-256")
            var lastSpaceCheck = bytes
            try {
                val input = context.contentResolver.openInputStream(src.uri)
                    ?: throw IllegalStateException("Cannot read SAF file: $rel")
                input.use { srcIn ->
                    tmp.outputStream().buffered(SAF_COPY_BUFFER_BYTES).use { out ->
                        val buf = ByteArray(SAF_COPY_BUFFER_BYTES)
                        while (true) {
                            checkCancelled()
                            val n = readSafChunk(srcIn, buf, checkCancelled)
                            checkCancelled()
                            if (n < 0) break
                            if (n == 0) continue
                            if (n.toLong() > maxBytes - bytes) {
                                throw IllegalStateException("SAF project exceeds ${maxBytes / (1024 * 1024)} MiB mirror limit")
                            }
                            bytes += n
                            if (bytes - lastSpaceCheck >= SAF_SPACE_CHECK_INTERVAL_BYTES) {
                                ensureStagingFreeSpace(stage, 0L)
                                lastSpaceCheck = bytes
                            }
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                            if (lastProgressBytes < 0L || bytes - lastProgressBytes >= SAF_PROGRESS_INTERVAL_BYTES) {
                                onProgress(SafMirrorProgress(SafMirrorPhase.COPYING_EXTERNAL, files, bytes, rel))
                                lastProgressBytes = bytes
                            }
                        }
                    }
                }
                check(query.readDocument(src.documentId, checkCancelled)?.identity == src.identity) {
                    "SAF identity changed while staging: $rel"
                }
                replace(tmp, dst)
                if (src.modified > 0L) dst.setLastModified(src.modified)
                entries[rel] = "f:${digest.digest().toHex()}"
                onProgress(SafMirrorProgress(SafMirrorPhase.COPYING_EXTERNAL, files, bytes, rel))
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }

        fun copyDir(parentDocumentId: String, dst: File, relDir: String, depth: Int) {
            checkCancelled()
            require(depth <= maxDepth) { "SAF project nesting exceeds $maxDepth levels" }
            check(dst.isDirectory || dst.mkdirs()) { "Cannot create staging directory: ${dst.path}" }
            if (relDir.isNotBlank()) entries[relDir] = DIR
            // Two protected top-level names may be present but are not part of the mirrored payload.
            val allowance = if (relDir.isEmpty()) 2 else 0
            for (child in query.listChildren(parentDocumentId, maxEntries - entries.size + allowance, checkCancelled)) {
                checkCancelled()
                val rel = if (relDir.isBlank()) child.name else "$relDir/${child.name}"
                if (isRuntimePath(rel)) continue
                check(entries.size < maxEntries) { "SAF project exceeds its total entry limit" }
                check(visitedIds.add(child.documentId)) { "SAF tree repeats a document identity" }
                identities[rel] = child.identity
                val out = PathSecurity.resolveWithin(stage, rel)
                if (child.isDirectory) copyDir(child.documentId, out, rel, depth + 1)
                else if (child.isFile) copyFile(child, out, rel)
            }
        }

        copyDir(rootId, stage, "", 0)
        check(query.readDocument(rootId, checkCancelled)?.identity == rootIdentity) { "SAF root identity changed while staging" }
        return Snapshot(entries, files, bytes, identities)
    }

    private fun ensureStagingFreeSpace(stage: File, nextFileBytes: Long) {
        val spaceRoot = stage.parentFile ?: stage
        val usable = spaceRoot.usableSpace
        require(WorkspaceTransferSpace.canStage(usable, nextFileBytes, MIN_SAFE_FREE_BYTES)) {
            "Not enough free storage for SAF import and safety headroom; existing files were retained"
        }
    }

    private fun scanLocal(
        root: File,
        maxFiles: Int,
        maxBytes: Long,
        maxDepth: Int,
        checkCancelled: () -> Unit = {},
        onProgress: (SafMirrorProgress) -> Unit = {},
        pathPrefix: String = "",
    ): Snapshot {
        val entries = linkedMapOf<String, String>()
        var files = 0
        val budget = SafReadBudget(maxBytes)
        var lastProgressBytes = -SAF_PROGRESS_INTERVAL_BYTES
        if (!root.exists()) return Snapshot(entries, 0, 0)
        val base = root.toPath()
        Files.walkFileTree(base, emptySet(), maxDepth.coerceAtMost(Int.MAX_VALUE - 1) + 1, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                if (dir == base) return FileVisitResult.CONTINUE
                val rel = base.relativize(dir).toString().replace(File.separatorChar, '/')
                if (isRuntimePath(if (pathPrefix.isEmpty()) rel else "$pathPrefix/$rel")) return FileVisitResult.SKIP_SUBTREE
                require(!Files.isSymbolicLink(dir)) { "SAF refresh cannot represent symbolic link '$rel'; local data was retained." }
                check(entries.size < entryLimit(maxFiles)) { "Local SAF mirror exceeds its total entry limit" }
                entries[rel] = DIR
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                val rel = base.relativize(file).toString().replace(File.separatorChar, '/')
                if (isRuntimePath(if (pathPrefix.isEmpty()) rel else "$pathPrefix/$rel")) return FileVisitResult.CONTINUE
                require(!Files.isSymbolicLink(file)) { "SAF refresh cannot represent symbolic link '$rel'; local data was retained." }
                require(attrs.isRegularFile) { "SAF refresh cannot represent special or over-depth path '$rel'; local data was retained." }
                check(entries.size < entryLimit(maxFiles)) { "Local SAF mirror exceeds its total entry limit" }
                if (++files > maxFiles) throw IllegalStateException("Local SAF mirror exceeds $maxFiles files")
                entries[rel] = fingerprintFile(file.toFile(), checkCancelled, budget)
                if (lastProgressBytes < 0L || budget.bytes - lastProgressBytes >= SAF_PROGRESS_INTERVAL_BYTES || files % SAF_PROGRESS_FILE_INTERVAL == 0) {
                    onProgress(SafMirrorProgress(SafMirrorPhase.SCANNING_LOCAL, files, budget.bytes, rel))
                    lastProgressBytes = budget.bytes
                }
                return FileVisitResult.CONTINUE
            }
        })
        onProgress(SafMirrorProgress(SafMirrorPhase.SCANNING_LOCAL, files, budget.bytes))
        return Snapshot(entries, files, budget.bytes)
    }

    private fun resolve(previous: SafManifestFile, local: Map<String, String>, external: Map<String, String>, checkCancelled: () -> Unit = {}): Resolution {
        val localWinners = linkedSetOf<String>()
        val conflicts = linkedSetOf<String>()
        val all = linkedSetOf<String>().apply {
            addAll(previous.entries.keys)
            addAll(local.keys)
            addAll(external.keys)
        }

        for (path in all.sortedBy { it.count { ch -> ch == '/' } }) {
            checkCancelled()
            val prev = previous.entries[path]
            val localNow = local[path]
            val externalNow = external[path]

            if (prev == LEGACY_UNKNOWN) {
                // Never guess if the two sides differ.
                when {
                    localNow == externalNow -> Unit
                    localNow == null && externalNow != null -> Unit
                    localNow != null && externalNow == null -> conflicts += path
                    else -> conflicts += path
                }
                continue
            }

            if (prev == null) {
                when {
                    localNow == externalNow -> Unit
                    localNow == null -> Unit
                    externalNow == null -> localWinners += path
                    else -> conflicts += path
                }
                continue
            }

            val localChanged = localNow != prev
            val externalChanged = externalNow != prev
            when {
                localChanged && externalChanged && localNow != externalNow -> conflicts += path
                localChanged -> localWinners += path
                else -> Unit
            }
        }
        // A winning ancestor replaces its whole subtree, including external-only additions.
        for (path in all) {
            checkCancelled()
            if (external[path] == previous.entries[path] || external[path] == local[path]) continue
            var ancestor = path
            while (true) {
                if (ancestor in localWinners) { conflicts += path; break }
                val slash = ancestor.lastIndexOf('/')
                if (slash < 0) break
                ancestor = ancestor.substring(0, slash)
            }
        }
        return Resolution(localWinners, conflicts)
    }

    private fun resolvedEntries(
        external: Map<String, String>,
        local: Map<String, String>,
        localWinners: Set<String>,
    ): Map<String, String> {
        val desired = external.toMutableMap()
        for (root in collapsePaths(localWinners)) {
            val prefix = "$root/"
            desired.keys.removeAll { it == root || it.startsWith(prefix) }
            local.forEach { (path, fingerprint) ->
                if (path == root || path.startsWith(prefix)) desired[path] = fingerprint
            }
        }
        return desired.toSortedMap()
    }

    private fun collapsePaths(paths: Set<String>): Set<String> {
        val ordered = paths.sortedWith(compareBy<String> { it.count { ch -> ch == '/' } }.thenBy { it })
        val roots = linkedSetOf<String>()
        for (path in ordered) {
            var cursor = path
            var covered = false
            while (true) {
                if (cursor in roots) { covered = true; break }
                val slash = cursor.lastIndexOf('/')
                if (slash < 0) break
                cursor = cursor.substring(0, slash)
            }
            if (!covered) roots += path
        }
        return roots
    }

    private fun preserveLocalExecutableIntent(
        stage: File,
        localEntries: Map<String, String>,
        checkCancelled: () -> Unit = {},
    ) {
        localEntries.forEach { (rel, fingerprint) ->
            checkCancelled()
            if (!fingerprint.startsWith("f:")) return@forEach
            val source = PathSecurity.resolveWithin(localRoot, rel)
            if (!source.isFile || PathSecurity.isSymbolicLink(source) || !source.canExecute()) return@forEach
            val staged = PathSecurity.resolveWithin(stage, rel)
            if (!staged.isFile || PathSecurity.isSymbolicLink(staged)) return@forEach
            WorkspaceExecutablePolicy.preserveExplicitExecutableBit(localRoot, source, stage, staged)
        }
    }

    private fun applyLocalWinnersToStage(stage: File, paths: Set<String>, local: Map<String, String>, checkCancelled: () -> Unit = {},
        budget: SafReadBudget = SafReadBudget(Long.MAX_VALUE)) {
        val roots = collapsePaths(paths)
        // A collapsed winning directory represents its complete local subtree, not just its root.
        for (root in roots) {
            checkCancelled()
            check(PathSecurity.deleteTreeNoFollow(PathSecurity.resolveWithin(stage, root))) {
                "Cannot replace staged SAF subtree: $root"
            }
        }
        val ordered = local.keys.filter { path ->
            var ancestor = path
            while (ancestor !in roots && '/' in ancestor) ancestor = ancestor.substringBeforeLast('/')
            ancestor in roots
        }
            .sortedWith(compareBy<String> { it.count { ch -> ch == '/' } }.thenBy { it })
        for (path in ordered) {
            checkCancelled()
            val src = PathSecurity.resolveWithin(localRoot, path)
            val dst = PathSecurity.resolveWithin(stage, path)
            when (local[path]) {
                null -> PathSecurity.deleteTreeNoFollow(dst)
                DIR -> {
                    if (dst.exists() && !dst.isDirectory) PathSecurity.deleteTreeNoFollow(dst)
                    check(dst.isDirectory || dst.mkdirs()) { "Cannot stage local directory: $path" }
                }
                else -> {
                    if (dst.isDirectory) PathSecurity.deleteTreeNoFollow(dst)
                    dst.parentFile?.mkdirs()
                    copyFileAtomic(src, dst, checkCancelled, budget)
                }
            }
        }
    }

    private fun swapStageIntoLocal(stage: File, backup: File, activationRoots: Set<String>,
        original: SafActivationInventory, staged: SafActivationInventory, backupArtifact: SafLocalArtifact, poll: () -> Unit) {
        fun requireArtifacts() {
            for (artifact in listOf(original.artifact, staged.artifact, backupArtifact)) {
                val root = File(artifact.path)
                ownedStorage.requireRealAncestors(root)
                check(ownedStorage.key(root, true) == artifact.directoryKey) { "SAF activation artifact identity changed" }
            }
        }
        requireArtifacts()
        check(backup.list()!!.isEmpty()) { "SAF backup is not empty" }
        try {
            val currentEntries = requireNotNull(localRoot.listFiles()) { "Cannot list local SAF mirror" }
                .filterNot { isRuntimePath(it.name) }
            val stagedEntries = requireNotNull(stage.listFiles()) { "Cannot list SAF staging directory" }
            check((currentEntries + stagedEntries).all { it.name in activationRoots && !isRuntimePath(it.name) }) {
                "SAF activation roots changed after recording recovery evidence"
            }
            currentEntries.forEach { current ->
                poll()
                requireArtifacts()
                val dst = File(backup, current.name)
                val expected = original.entries.single { it.path == current.name }.version
                check(SafLocalFiles.version(ownedStorage, current) == expected && ownedStorage.attributes(dst) == null)
                move(current, dst)
                requireArtifacts()
                check(SafLocalFiles.version(ownedStorage, dst) == expected && ownedStorage.attributes(current) == null)
            }
            stagedEntries.forEach { source ->
                poll()
                requireArtifacts()
                val dst = File(localRoot, source.name)
                val expected = staged.entries.single { it.path == source.name }.version
                check(SafLocalFiles.version(ownedStorage, source) == expected && ownedStorage.attributes(dst) == null)
                move(source, dst)
                requireArtifacts()
                check(SafLocalFiles.version(ownedStorage, dst) == expected && ownedStorage.attributes(source) == null)
            }
        } catch (t: Throwable) {
            // A destination may contain newer data. Never delete it or guess that rollback is safe.
            if (t is CancellationException) throw t
            throw IllegalStateException(
                "SAF local activation failed; original backup and staging data were retained. Overlapping sync is blocked pending recovery: ${t.message}", t
            )
        }
    }

    private fun recordPendingActivation(stage: File, backup: File): Set<String> {
        checkPendingActivation(null)
        check(localRoot.isDirectory || localRoot.mkdirs()) { "Cannot create local SAF mirror" }
        val roots = (requireNotNull(localRoot.listFiles()) { "Cannot list local SAF mirror" }.asList() +
            requireNotNull(stage.listFiles()) { "Cannot list SAF staging directory" }.asList())
            .map { it.name }.filterNot(::isRuntimePath).distinct().sorted()
        val record = PendingSafActivation(roots = roots, stage = stage.name, backup = backup.name)
        val directory = requireNotNull(activationMarker.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create SAF activation metadata directory" }
        val tmp = File(directory, ".saf-activation-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tmp).use { output ->
                output.write(json.encodeToString(PendingSafActivation.serializer(), record).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            // No replacement: a different unfinished activation must not lose its evidence.
            WorkspacePathMove.platform.move(tmp, activationMarker)
            // Include new metadata/root directory entries, not just the marker file's contents.
            listOf(directory, localRoot, localRoot.parentFile ?: localRoot).distinct().forEach { parent ->
                val descriptor = Os.open(parent.absolutePath, OsConstants.O_RDONLY, 0)
                try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
            }
        } finally {
            tmp.delete()
        }
        return roots.toSet()
    }

    /** Evidence is a blocker only; paths in this record never authorize cleanup or rollback. */
    private fun checkPendingActivation(paths: List<String>?): Boolean {
        val record = try {
            require(!Files.isSymbolicLink(localRoot.toPath()) &&
                !Files.isSymbolicLink(requireNotNull(activationMarker.parentFile).toPath())) {
                "SAF recovery metadata must not use symbolic links"
            }
            val attributes = try {
                Files.readAttributes(activationMarker.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return false
            }
            require(attributes.isRegularFile)
            require(activationMarker.length() in 1..MAX_MANIFEST_BYTES.toLong())
            json.decodeFromString(PendingSafActivation.serializer(), activationMarker.readText()).also { pending ->
                require(pending.version == 1)
                pending.roots.forEach { require(PathSecurity.safeLeafName(it) == it && !isRuntimePath(it)) }
                require(PathSecurity.safeLeafName(pending.stage) == pending.stage)
                require(PathSecurity.safeLeafName(pending.backup) == pending.backup)
            }
        } catch (failure: Exception) {
            throw IllegalStateException("SAF activation recovery metadata is unreadable; retained data must be reviewed before syncing.", failure)
        }
        val overlaps = paths == null || paths.any { raw ->
            val parts = segments(raw)
            var node = localRoot
            for (part in parts) {
                node = File(node, part)
                require(!Files.isSymbolicLink(node.toPath())) {
                    "SAF activation recovery pending; symbolic-link aliases cannot bypass retained path protection."
                }
            }
            parts.isEmpty() || parts.first() in record.roots
        }
        check(!overlaps) { "SAF local activation recovery pending; overlapping sync is blocked and retained data was not changed." }
        return true
    }

    /** Direct mutations lack complete snapshot-backed type-replacement recovery. */
    private fun requireDirectTypesUnchanged(mutation: FileRepository.Mutation, checkCancelled: () -> Unit) {
        if (mutation is FileRepository.Mutation.Delete) return
        val path = when (mutation) {
            is FileRepository.Mutation.Write -> mutation.path
            is FileRepository.Mutation.CreateDirectory -> mutation.path
            is FileRepository.Mutation.Move -> mutation.to
            is FileRepository.Mutation.Sync -> mutation.path
            is FileRepository.Mutation.Delete -> return
        }
        val clean = segments(path).joinToString("/")
        require(clean.isNotBlank()) { "Root SAF reconciliation must use refreshFromTree()" }
        require(!Files.isSymbolicLink(localRoot.toPath())) { "SAF cannot traverse a symbolic-link project root" }
        var ancestor = ""
        for (part in segments(clean).dropLast(1)) {
            ancestor = if (ancestor.isEmpty()) part else "$ancestor/$part"
            checkCancelled()
            require(!Files.isSymbolicLink(File(localRoot, ancestor).toPath())) { "SAF cannot traverse symbolic-link parent: $ancestor" }
            check(findNode(ancestor, checkCancelled)?.isDirectory != false) {
                "SAF type replacement requires full refresh: $ancestor"
            }
        }
        if (mutation is FileRepository.Mutation.CreateDirectory) {
            require(!Files.isSymbolicLink(File(localRoot, clean).toPath())) { "SAF cannot synchronize a symbolic-link directory: $clean" }
            check(findNode(clean, checkCancelled)?.isDirectory != false) { "SAF type replacement requires full refresh: $clean" }
            return
        }
        val root = File(localRoot, clean)
        try {
            Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return
        }
        var entries = 0
        Files.walkFileTree(root.toPath(), emptySet(), MAX_MIRROR_DEPTH, object : SimpleFileVisitor<Path>() {
            fun admit(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                val rel = localRoot.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                if (isRuntimePath(rel)) return FileVisitResult.SKIP_SUBTREE
                check(++entries <= entryLimit(MAX_MIRROR_FILES)) { "SAF direct preflight exceeds its entry limit" }
                require(!attributes.isSymbolicLink && (attributes.isDirectory || attributes.isRegularFile)) {
                    "SAF cannot synchronize symbolic links or special paths: $rel"
                }
                val existing = findNode(rel, checkCancelled)
                check(existing == null || existing.isDirectory == attributes.isDirectory) {
                    "SAF type replacement requires full refresh: $rel"
                }
                return FileVisitResult.CONTINUE
            }
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) = admit(dir, attrs)
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!attrs.isDirectory) { "SAF direct preflight exceeds its depth limit" }
                return admit(file, attrs)
            }
        })
    }

    private fun treeNode(checkCancelled: () -> Unit = {}): SafDocumentNode =
        query.readDocument(query.rootDocumentId(), checkCancelled)?.takeIf { it.isDirectory }
            ?: throw IllegalStateException("SAF tree permission unavailable")

    private fun findNode(path: String, checkCancelled: () -> Unit = {}): SafDocumentNode? {
        var current = treeNode(checkCancelled)
        for (segment in segments(path)) {
            checkCancelled()
            check(current.isDirectory) { "SAF path component is not a directory: $path" }
            current = query.findChild(current.documentId, segment, checkCancelled) ?: return null
        }
        return current
    }

    private fun removeKnownTree(known: MutableMap<String, String>, path: String) {
        val base = path.trimEnd('/')
        known.keys.removeAll { it == base || it.startsWith("$base/") }
    }

    private fun segments(path: String): List<String> = path.replace('\\', '/').split('/')
        .filter { it.isNotEmpty() }
        .map(PathSecurity::safeLeafName)

    private fun fingerprintFile(file: File, checkCancelled: () -> Unit = {}, budget: SafReadBudget = SafReadBudget(Long.MAX_VALUE)): String {
        require(file.isFile && !PathSecurity.isSymbolicLink(file)) { "Not a regular file: ${file.path}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(SAF_COPY_BUFFER_BYTES).use { input ->
            val buf = ByteArray(SAF_COPY_BUFFER_BYTES)
            while (true) {
                checkCancelled()
                val n = readSafChunk(input, buf, checkCancelled)
                checkCancelled()
                if (n < 0) break
                if (n == 0) continue
                budget.consume(n)
                digest.update(buf, 0, n)
            }
        }
        return "f:${digest.digest().toHex()}"
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun entryLimit(maxFiles: Int): Int = (maxFiles.toLong() * 2L)
        .coerceAtMost((SafDocumentsTreeQuery.MAX_QUERY_ENTRIES - 2).toLong()).toInt()

    private fun copyFileAtomic(src: File, dst: File, checkCancelled: () -> Unit = {}, budget: SafReadBudget = SafReadBudget(Long.MAX_VALUE)) {
        require(src.isFile && !PathSecurity.isSymbolicLink(src)) { "Not a regular file: ${src.path}" }
        dst.parentFile?.mkdirs()
        ensureStagingFreeSpace(dst, src.length())
        val tmp = File(dst.parentFile, ".${dst.name}.tmp-${System.nanoTime()}")
        try {
            src.inputStream().buffered(SAF_COPY_BUFFER_BYTES).use { input ->
                tmp.outputStream().buffered(SAF_COPY_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(SAF_COPY_BUFFER_BYTES)
                    var bytesSinceSpaceCheck = 0L
                    while (true) {
                        checkCancelled()
                        val n = readSafChunk(input, buffer, checkCancelled)
                        checkCancelled()
                        if (n < 0) break
                        if (n == 0) continue
                        budget.consume(n)
                        bytesSinceSpaceCheck += n
                        if (bytesSinceSpaceCheck >= SAF_SPACE_CHECK_INTERVAL_BYTES) {
                            ensureStagingFreeSpace(dst, n.toLong())
                            bytesSinceSpaceCheck = 0L
                        }
                        output.write(buffer, 0, n)
                    }
                }
            }
            replace(tmp, dst)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun isRuntimePath(path: String): Boolean {
        val p = path.trimStart('/').replace('\\', '/')
        return p == ".droide" || p.startsWith(".droide/") || p == ".git" || p.startsWith(".git/")
    }

    private fun replace(tmp: File, target: File) {
        target.parentFile?.mkdirs()
        runCatching {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun move(source: File, target: File) {
        target.parentFile?.mkdirs()
        // ATOMIC_MOVE may replace an existing destination even without REPLACE_EXISTING.
        check(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) { "SAF activation destination already exists: ${target.name}" }
        WorkspacePathMove.platform.move(source, target)
    }

    companion object {
        private const val DIR = "d"
        private const val LEGACY_UNKNOWN = "?"
        private const val MAX_MIRROR_FILES = 50_000
        private const val MAX_MIRROR_DEPTH = 64
        private const val SAF_COPY_BUFFER_BYTES = 256 * 1024
        private const val SAF_PROGRESS_INTERVAL_BYTES = 4L * 1024 * 1024
        private const val SAF_PROGRESS_FILE_INTERVAL = 64
        private const val SAF_SPACE_CHECK_INTERVAL_BYTES = 16L * 1024 * 1024
        private const val MIN_SAFE_FREE_BYTES = 192L * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
    }
}
