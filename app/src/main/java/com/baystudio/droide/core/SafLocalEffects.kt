package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Unwired ordinary-local adapter for C1. Never invokes a mutation, provider, rollback or cleanup. */
internal class SafLocalEffects(
    private val guard: WorkspaceMutationGuard,
    private val journal: SafOperationJournal,
    private val operation: SafOperationJournal.Operation,
    private val checkActive: () -> Unit,
) {
    internal class Effect internal constructor(internal val owner: Any, internal val preparation: WorkspaceLocalPreparation,
        internal val action: SafOperationJournal.Action, internal val admission: SafOperationJournal.LocalAttemptAdmission)
    private val inUse = AtomicBoolean(false)
    private var failed = false
    private var latest: Effect? = null

    init { guard.requireRoot(journal.workspace); check(operation.activeInvocation); journal.validate(operation, checkActive) }

    fun prepare(preparation: WorkspaceLocalPreparation, deleteCustody: SafDeleteCustody? = null): Effect = owned {
        var precedingParents: Effect? = null
        latest?.let {
            val phase = operation.effects.getValue(it.action.id).phase
            if (phase == SafEffectPhase.RETURNED && it.preparation.effect == WorkspaceLocalEffect.CREATE_PARENTS && preparation.effect == WorkspaceLocalEffect.WRITE)
                precedingParents = it // C1 has already placed its exact owned stage in the new parent.
            else if (phase == SafEffectPhase.RETURNED) verifyInternal(it)
            else check(phase == SafEffectPhase.VERIFIED) { "An earlier local effect is unresolved" }
        }
        check(operation.effects.values.none { it.plan.kind != SafEffectKind.LOCAL_MUTATION && it.phase >= SafEffectPhase.ATTEMPTING }) {
            "Local intent cannot be introduced after provider work"
        }
        val effect = SafLocalEffect.valueOf(preparation.effect.name)
        val target = SafLocalPath(SafLocalRole.WORKSPACE, preparation.relativePath)
        val source = preparation.sourcePath?.let { SafLocalPath(SafLocalRole.WORKSPACE, it) }
        val endpoints = listOfNotNull(source, target)
        endpoints.forEach { endpoint ->
            SafLocalFiles.resolve(journal, endpoint, deleteCustody)
            if (deleteCustody == null || endpoint !== target)
                require(operation.scopes.any { SafOperationJournal.contains(it, endpoint.path) }) { "Local endpoint was not reserved at begin" }
        }
        require(deleteCustody == null || effect == SafLocalEffect.DELETE_STAGE &&
            deleteCustody.directory == ".droide/saf-delete-custody/${operation.id}" && target.path == "${deleteCustody.directory}/original")
        val parents = SafLocalFiles.parents(journal, endpoints, deleteCustody, checkActive)
        deleteCustody?.let { check(parents.single { parent -> parent.path == it.directory }.key == it.directoryKey) }
        val targetFile = SafLocalFiles.resolve(journal, target, deleteCustody)
        val targetVersion = SafLocalFiles.version(journal.storage, targetFile)
        val sourceFile = source?.let { SafLocalFiles.resolve(journal, it) }
        val sourceVersion = sourceFile?.let { SafLocalFiles.version(journal.storage, it) }
        var intended: SafEvidenceFile? = null
        var previous: SafEvidenceFile? = null
        var inventory: SafEvidenceFile? = null
        val missing = arrayListOf<String>()
        when (effect) {
            SafLocalEffect.WRITE -> {
                require(sourceFile != null && sourceVersion?.kind == SafLocalKind.FILE && preparation.sourceIdentity == sourceVersion.key)
                require(preparation.bytes == sourceVersion.bytes)
                require(targetVersion == null || targetVersion.kind == SafLocalKind.FILE)
                intended = journal.preserve(operation, sourceFile, checkActive)
                preparation.sha256?.let { require(it == requireNotNull(intended).identity.sha256) }
                if (targetVersion != null) previous = journal.preserve(operation, targetFile, checkActive)
                checkActive()
                // Text stages are not forced by C1. This force is outside its publication finalizer.
                journal.storage.syncFile(sourceFile)
            }
            SafLocalEffect.MOVE, SafLocalEffect.DELETE_STAGE -> {
                require(source != null && sourceVersion?.kind in setOf(SafLocalKind.FILE, SafLocalKind.DIRECTORY) && targetVersion == null)
                require(preparation.sourceIdentity == sourceVersion!!.key)
                val image = SafLocalFiles.capture(journal, operation, source, checkActive)
                require(image.rootVersion == sourceVersion)
                SafLocalFiles.verify(journal, operation, image, source, checkActive)
                inventory = journal.preserveLocalIndex(operation, image, checkActive)
            }
            SafLocalEffect.CREATE_PARENTS, SafLocalEffect.CREATE_DIRECTORY -> {
                require(source == null && targetVersion == null)
                var relative = ""
                var absent = false
                target.path.split('/').forEach { name ->
                    checkActive()
                    relative = if (relative.isEmpty()) name else "$relative/$name"
                    val current = SafLocalFiles.version(journal.storage, File(journal.workspace, relative))
                    if (current == null) absent = true else require(!absent && current.kind == SafLocalKind.DIRECTORY)
                    if (absent) {
                        require(operation.scopes.any { SafOperationJournal.contains(it, relative) }) { "Missing parent was not reserved" }
                        missing += relative
                    }
                }
            }
            else -> error("Activation is outside this local adapter")
        }
        val plan = SafLocalPlan(effect, source, target, sourceVersion, targetVersion, parents,
            Collections.unmodifiableList(missing), inventory, intended = intended, previous = previous, deleteCustody = deleteCustody)
        SafLocalFiles.requireVersions(journal, plan, checkActive)
        val action = journal.prepare(operation, SafEffectPlan(SafEffectKind.LOCAL_MUTATION, if (deleteCustody == null) target.path else requireNotNull(source).path,
            null, null, null, null, null, local = plan), checkActive)
        precedingParents?.let { verifyInternal(it, action) }
        verifyBefore(action)
        val admission = journal.admitLocalAttempt(action, guard, checkActive)
        Effect(this, preparation, action, admission).also { latest = it }
    }

    fun beforeAttempt(effect: Effect, preparation: WorkspaceLocalPreparation = effect.preparation) = owned(poll = false) {
        require(effect.owner === this && latest === effect && preparation === effect.preparation)
        journal.attemptLocal(effect.admission)
    }

    /** No caller cancellation/guard/source checks here: preserve the representable raw return first. */
    fun returned(effect: Effect, receipt: WorkspaceLocalReceipt) = owned(poll = false, requireGuard = false) {
        require(effect.owner === this && latest === effect && receipt.preparation === effect.preparation)
        val failure = receipt.failure
        val className = failure?.javaClass?.name?.replace('\u0000', '?')?.take(64)
        // Diagnostics never authorize work. Keep UTF-8 below the record bound without serializing Throwable.
        val message = failure?.message?.replace('\u0000', '?')?.take(1000)
        journal.returnedLocal(effect.action, SafLocalReturn(receipt.returned, receipt.successful && failure == null,
            receipt.destinationIdentity, receipt.durable, className, message,
            failure != null && ((failure.javaClass.name.length > 64) || (failure.message?.length ?: 0) > 1000)))
    }

    /** Preserve the original throwable if a mandatory return append also fails. */
    fun threw(effect: Effect, failure: Throwable, invocationReturned: Boolean = false): Nothing {
        try { returned(effect, WorkspaceLocalReceipt(effect.preparation, invocationReturned, null, failure = failure)) }
        catch (recordFailure: Throwable) { if (recordFailure !== failure) failure.addSuppressed(recordFailure) }
        throw failure
    }

    fun verify(effect: Effect) = owned { require(effect.owner === this && latest === effect); verifyInternal(effect) }
    fun actionId(effect: Effect): String { require(effect.owner === this); return effect.action.id }

    private fun verifyBefore(action: SafOperationJournal.Action) {
        val plan = requireNotNull(action.admittedPlan.local)
        SafLocalFiles.requireVersions(journal, plan, checkActive)
        if (plan.effect == SafLocalEffect.WRITE) {
            checkContent(SafLocalFiles.resolve(journal, requireNotNull(plan.source)), requireNotNull(plan.sourceVersion), requireNotNull(plan.intended))
            plan.previous?.let { checkContent(SafLocalFiles.resolve(journal, plan.destination), requireNotNull(plan.expectedDestination), it) }
        } else if (plan.beforeInventory != null) {
            val image = journal.localInventory(operation, plan, checkActive)
            SafLocalFiles.verify(journal, operation, image, requireNotNull(plan.source), checkActive)
        }
        SafLocalFiles.requireVersions(journal, plan, checkActive)
    }

    private fun verifyInternal(effect: Effect, nextWrite: SafOperationJournal.Action? = null) {
        val action = effect.action
        val record = operation.effects.getValue(action.id)
        check(record.phase == SafEffectPhase.RETURNED && record.result == SafEffectReturn.COMPLETED)
        val raw = requireNotNull(record.localReturn)
        val plan = requireNotNull(action.admittedPlan.local)
        val allowedStage = nextWrite?.let {
            check(it.operation === operation && operation.effects.getValue(it.id).phase == SafEffectPhase.PREPARED)
            check(journal.read(operation, SafOperationJournal.actionName(it.id, SafEffectPhase.PREPARED), SafEffectRecord.serializer(), checkActive) == operation.effects.getValue(it.id))
            val next = requireNotNull(it.admittedPlan.local)
            val source = requireNotNull(next.source)
            check(plan.effect == SafLocalEffect.CREATE_PARENTS && next.effect == SafLocalEffect.WRITE &&
                source.path.substringBeforeLast('/', "") == plan.destination.path)
            checkContent(SafLocalFiles.resolve(journal, source), requireNotNull(next.sourceVersion), requireNotNull(next.intended))
            source
        }
        val target = SafLocalFiles.resolve(journal, plan.destination, plan.deleteCustody)
        SafLocalFiles.requireParents(journal, plan.parents, checkActive)
        val observed = requireNotNull(SafLocalFiles.version(journal.storage, target))
        check(observed.key == raw.destinationKey)
        val created = arrayListOf<SafLocalParent>()
        when (plan.effect) {
            SafLocalEffect.WRITE -> {
                checkContent(target, requireNotNull(plan.sourceVersion), requireNotNull(plan.intended))
                journal.checkEvidence(journal.file(operation, plan.intended.name), plan.intended, checkActive)
                plan.previous?.let { journal.checkEvidence(journal.file(operation, it.name), it, checkActive) }
                checkActive(); journal.storage.syncFile(target)
            }
            SafLocalEffect.MOVE, SafLocalEffect.DELETE_STAGE -> {
                check(SafLocalFiles.version(journal.storage, SafLocalFiles.resolve(journal, requireNotNull(plan.source))) == null) { "Move source was recreated; both sides retained" }
                val image = journal.localInventory(operation, plan, checkActive)
                SafLocalFiles.verify(journal, operation, image, plan.destination, plan.deleteCustody, checkActive)
                SafLocalFiles.forceInventory(journal, image, plan.destination, plan.deleteCustody, checkActive)
            }
            SafLocalEffect.CREATE_PARENTS, SafLocalEffect.CREATE_DIRECTORY -> {
                created += checkCreatedDirectories(plan, allowedStage)
                created.asReversed().forEach { checkActive(); journal.storage.syncDirectory(File(journal.workspace, it.path)) }
            }
            else -> error("Activation requires its later binding")
        }
        // Existing ancestors include both source and target links and the workspace itself.
        plan.parents.sortedByDescending { it.path.count { c -> c == '/' } * 2 + if (it.path.isEmpty()) 0 else 1 }.forEach {
            checkActive(); SafLocalFiles.requireParents(journal, listOf(it)); journal.storage.syncDirectory(File(journal.workspace, it.path))
        }
        SafLocalFiles.requireParents(journal, plan.parents, checkActive)
        check(SafLocalFiles.version(journal.storage, target) == observed)
        when (plan.effect) {
            SafLocalEffect.WRITE -> checkContent(target, requireNotNull(plan.sourceVersion), requireNotNull(plan.intended))
            SafLocalEffect.MOVE, SafLocalEffect.DELETE_STAGE -> {
                val image = journal.localInventory(operation, plan, checkActive)
                SafLocalFiles.verify(journal, operation, image, plan.destination, plan.deleteCustody, checkActive)
                check(SafLocalFiles.version(journal.storage, SafLocalFiles.resolve(journal, requireNotNull(plan.source))) == null)
            }
            else -> check(checkCreatedDirectories(plan, allowedStage) == created)
        }
        nextWrite?.admittedPlan?.local?.let { next ->
            checkContent(SafLocalFiles.resolve(journal, requireNotNull(next.source)), requireNotNull(next.sourceVersion), requireNotNull(next.intended))
        }
        checkActive()
        guard.requireRoot(journal.workspace)
        journal.verifiedLocal(action, SafLocalVerified(observed,
            if (plan.effect == SafLocalEffect.WRITE) plan.intended!!.identity.sha256 else null,
            plan.beforeInventory?.identity?.sha256, Collections.unmodifiableList(created), true), checkActive)
    }

    private fun checkCreatedDirectories(plan: SafLocalPlan, allowedStage: SafLocalPath? = null): List<SafLocalParent> = plan.missingDirectories.mapIndexed { index, path ->
        checkActive()
        val file = File(journal.workspace, path)
        val version = requireNotNull(SafLocalFiles.version(journal.storage, file))
        check(version.kind == SafLocalKind.DIRECTORY)
        val children = if (index == plan.missingDirectories.lastIndex) emptySet()
            else setOf(plan.missingDirectories[index + 1].substringAfterLast('/'))
        val expected = children + if (allowedStage?.path?.substringBeforeLast('/', "") == path) setOf(allowedStage.path.substringAfterLast('/')) else emptySet()
        val names = linkedSetOf<String>()
        Files.newDirectoryStream(file.toPath()).use { stream -> stream.forEach { child ->
            checkActive(); check(names.size < expected.size + 1); names += child.fileName.toString()
        } }
        check(names == expected) { "New local directory gained unowned contents" }
        check(SafLocalFiles.version(journal.storage, file) == version)
        SafLocalParent(path, version.key)
    }

    private fun checkContent(file: File, expected: SafLocalVersion, evidence: SafEvidenceFile) {
        checkActive()
        check(SafLocalFiles.version(journal.storage, file) == expected)
        val actual = journal.storage.describe(file, evidence.identity.bytes, checkActive)
        check(actual.key == expected.key && actual.bytes == evidence.identity.bytes && actual.sha256 == evidence.identity.sha256)
        check(SafLocalFiles.version(journal.storage, file) == expected)
    }

    private inline fun <T> owned(poll: Boolean = true, requireGuard: Boolean = true, block: () -> T): T {
        check(inUse.compareAndSet(false, true)) { "Local SAF helper is already in use" }
        try {
            check(!failed) { "Uncertain local helper cannot be reused" }
            if (requireGuard) guard.requireRoot(journal.workspace)
            if (poll) checkActive()
            return block()
        } catch (failure: Throwable) { failed = true; throw failure }
        finally { inUse.set(false) }
    }
}
