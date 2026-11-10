package com.baystudio.droide.core

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Private durable history, not restored approval. Construct only with app-private storage. No
 * method executes a tool. Runtime permits cannot be reconstructed from persisted records. */
internal class ToolCallHistoryJournal(
    private val root: File,
    private val owned: OwnedResponseContentStore,
    private val checkCancelled: () -> Unit = {},
) {
    enum class State { RECORDED, APPROVED, EXECUTING, SETTLED, INCOMPLETE }
    @Serializable data class Locator(val id: String, val sessionId: String, val runId: String, val callId: String, val name: String)
    class Pending internal constructor(internal val journal: ToolCallHistoryJournal, val locator: Locator)
    class Execution internal constructor(internal val journal: ToolCallHistoryJournal, val locator: Locator, internal val observation: StreamedTextEditObservation? = null, internal val liveObservation: StreamedLiveEditObservation? = null, internal val artifactObservation: SourceArtifactPublisher.Observation? = null, internal val artifactPublisher: SourceArtifactPublisher? = null)
    @Serializable private data class Record(val session: String, val run: String, val call: String,
        val name: String, val handle: String, val bytes: Long, val chars: Long, val hash: String,
        val complete: Boolean, val state: String, val approvalEvidence: String? = null,
        val resultBytes: Long? = null, val resultHash: String? = null,
        val reviewedRoot: String? = null,val reviewedPath: String? = null,val reviewedBytes: Long? = null,val reviewedHash: String? = null,
        val effect: String? = null, val liveDocumentId: String? = null, val liveRevision: Int? = null,
        val liveChangeVersion: Int? = null, val liveBeforeBytes: Long? = null, val liveBeforeHash: String? = null,
        val liveBackup: String? = null, val liveBackupIdentity: String? = null, val liveRootIdentity: String? = null,
        val artifactOperation: String? = null, val artifactType: String? = null, val artifactRootIdentity: String? = null,
        val artifactDurabilityConfirmed: Boolean? = null,
        val commandJob:String?=null,val commandHash:String?=null,val commandTimeout:Long?=null,
        val commandKeepInput:Boolean?=null,val commandStopWithAgent:Boolean?=null,val commandTty:Boolean?=null,
        val commandReturnHash:String?=null,val commandActivation:String?=null,val commandDisposition:String?=null,val commandExit:Int?=null,val commandOutputIncomplete:Boolean?=null,
        val stdinSession: String? = null, val stdinObservation: Boolean? = null,
        val stdinOfferAttempted: Boolean? = null, val stdinOfferAccepted: Boolean? = null,
        val stdinOfferKnownRejected: Boolean? = null, val stdinOfferRejection: String? = null,
        val stdinEofAttempted: Boolean? = null, val stdinEofAccepted: Boolean? = null,
        val stdinInterruptAttempted: Boolean? = null, val stdinInterruptAccepted: Boolean? = null)
    private val json = Json { encodeDefaults = true }
    private val active = mutableSetOf<String>()
    private val pendingCapabilities = mutableMapOf<String, Pair<Pending, StreamedFileArgumentExtractor.Source>>()
    private val stdinPreparing = mutableSetOf<String>()
    private class StdinInvocation(val execution: Execution, var arguments: JsonObject?,
        val manager: BackgroundCommandManager, val binding: BackgroundCommandManager.SourceStdinBinding)
    private val stdinInvocations = mutableMapOf<String, StdinInvocation>()
    class StdinCompletion private constructor(internal val execution: Execution, internal val argumentsHash: String,
        val output: String) {
        companion object {
            internal suspend fun invoke(journal: ToolCallHistoryJournal, pending: Pending,
                prepared: StreamedStdinArgumentExtractor.Prepared, context: AgentTools.Ctx,
                current: () -> Unit, runtimeCurrent: () -> Unit,
                started: (Execution)->Unit, completed: (StdinCompletion)->Unit): StdinCompletion {
                require(context.sessionId==pending.locator.sessionId)
                val invocation=journal.admitStdinInvocation(pending,prepared,context,current,runtimeCurrent)
                val args=checkNotNull(invocation.arguments).also { invocation.arguments=null }
                started(invocation.execution)
                check(context.sourceStdin==null)
                context.sourceStdin=invocation.binding
                try {
                    val output=AgentTools.execute("write_stdin",args,context)
                    val receipt=StdinCompletion(invocation.execution,prepared.originalArguments.sha256,output)
                    completed(receipt)
                    return receipt
                } finally {context.sourceStdin=null}
            }
        }
    }
    private val patchExecutions = mutableMapOf<String, Execution>()
    private val patchInvoked = mutableSetOf<String>()
    class PatchCompletion private constructor(internal val execution: Execution, internal val argumentsHash: String,
        val output: String) {
        companion object {
            internal suspend fun invoke(execution: Execution, prepared: StreamedPatchArgumentExtractor.Prepared,
                context: AgentTools.Ctx, current: () -> Unit, completed: (PatchCompletion)->Unit): PatchCompletion {
                require(context.sessionId==execution.locator.sessionId) {"Patch invocation conversation mismatch"}
                val journal=execution.journal
                val session=journal.claimPatchInvocation(execution,prepared,current)
                val patch=StreamedPatchArgumentExtractor.materialize(session,prepared,current)
                current()
                val result = AgentTools.execute("apply_patch", buildJsonObject { put("patchText", patch) }, context)
                val receipt=PatchCompletion(execution, prepared.originalArguments.sha256, result)
                completed(receipt) // capture inside the gateway before return cancellation/post-hooks
                return receipt
            }
        }
    }
    private val observations = mutableMapOf<String,StreamedTextEditObservation>()
    private val artifactPublishers = mutableMapOf<String, SourceArtifactPublisher>()
    private val artifactObservations = mutableMapOf<String, SourceArtifactPublisher.Observation>()
    private val liveObservations = mutableMapOf<String,StreamedLiveEditObservation>()
    private val lock: Any = locks.computeIfAbsent(root.canonicalPath) { Any() }
    private fun owner(l: Locator) = OwnedResponseContentStore.Owner(l.sessionId, l.runId, l.callId)
    private fun session(l: Locator) = owned.authorize(owner(l))
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun id(owner: OwnedResponseContentStore.Owner) = hash(listOf(owner.sessionId, owner.runId, owner.callId).joinToString("") { "${it.length}:$it" })
    private fun path(id: String): File {
        require(id.matches(Regex("[a-f0-9]{64}")))
        ChatMediaStoragePaths.requireNoLinks(root); Files.createDirectories(root.toPath())
        return File(root, "$id.json").also(ChatMediaStoragePaths::requireNoLinks)
    }
    private fun read(l: Locator, checkActive: () -> Unit = checkCancelled): Record {
        checkActive()
        val r = Files.newInputStream(path(l.id).toPath(), NOFOLLOW_LINKS).use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while(out.size() <= 65536) { val n=input.read(buffer,0,minOf(buffer.size,65537-out.size()));if(n<0)break;check(n>0);out.write(buffer,0,n) }
            require(out.size() <= 65536) { "Tool history metadata exceeds supported schema" }
            json.decodeFromString(Record.serializer(),out.toString("UTF-8"))
        }
        require(r.session == l.sessionId && r.run == l.runId && r.call == l.callId && r.name == l.name && l.id == id(owner(l))) { "Tool history ownership mismatch" }
        return r
    }
    private fun save(l: Locator, r: Record, checkActive: () -> Unit = checkCancelled) {
        checkActive()
        val target = path(l.id); val tmp = Files.createTempFile(root.toPath(), ".tool-history-", ".tmp").toFile()
        try {
            java.io.FileOutputStream(tmp).use { out -> out.write(json.encodeToString(Record.serializer(), r).toByteArray()); out.fd.sync() }
            checkActive(); Files.move(tmp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            java.nio.channels.FileChannel.open(root.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        } finally { tmp.delete() }
    }
    private fun source(l: Locator, r: Record): StreamedFileArgumentExtractor.Source {
        require(r.complete) { "Incomplete tool arguments cannot be replayed or executed" }
        return StreamedFileArgumentExtractor.Source(OwnedResponseContentStore.Handle.parse(r.handle), r.bytes, r.chars, r.hash,
            OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).also { it.verify(session(l)) }
    }
    /** The caller must first validate the actual provider terminal and batch identities. */
    fun recordCompleted(owner: OwnedResponseContentStore.Owner, name: String, arguments: StreamedFileArgumentExtractor.Source): Pending = synchronized(lock) {
        require(name in setOf("write_file", "edit_file", "write", "edit")) { "Only reviewed source-backed file mutation tools are admitted" }
        recordCompletedSource(owner,name,arguments)
    }
    fun recordCompletedArtifact(owner: OwnedResponseContentStore.Owner, arguments: StreamedFileArgumentExtractor.Source): Pending = synchronized(lock) {
        recordCompletedSource(owner,"artifact",arguments)
    }
    fun recordCompletedPatch(owner: OwnedResponseContentStore.Owner, arguments: StreamedFileArgumentExtractor.Source): Pending = synchronized(lock) {
        recordCompletedSource(owner,"apply_patch",arguments)
    }
    /** Admission to the existing executor, NOT a permission grant or proof that a patch applied.
     * Actual endpoint review and permission remain inside AgentTools.apply_patch. */
    fun beginPatchInvocation(pending: Pending, current: () -> Unit): Execution = synchronized(lock) {
        require(pending.journal === this && pending.locator.id in active && pendingCapabilities[pending.locator.id]?.first === pending)
        val r=read(pending.locator)
        require(r.name=="apply_patch" && r.state==State.RECORDED.name)
        source(pending.locator,r); current()
        save(pending.locator,r.copy(state=State.EXECUTING.name,effect="PATCH_INVOCATION"))
        active.remove(pending.locator.id);pendingCapabilities.remove(pending.locator.id);stdinPreparing.remove(pending.locator.id)
        Execution(this,pending.locator).also {patchExecutions[pending.locator.id]=it}
    }
    private fun claimPatchInvocation(execution: Execution, prepared: StreamedPatchArgumentExtractor.Prepared,
        current: () -> Unit): OwnedResponseContentStore.Session = synchronized(lock) {
        require(execution.journal === this && patchExecutions[execution.locator.id] === execution)
        val r=read(execution.locator)
        require(r.name=="apply_patch" && r.state==State.EXECUTING.name && r.effect=="PATCH_INVOCATION")
        val original=prepared.originalArguments
        source(execution.locator,r)
        require(prepared.callId==r.call && original.handle.serialized==r.handle && original.bytes==r.bytes &&
            original.utf16Units==r.chars && original.sha256==r.hash && original.kind==OwnedResponseContentStore.Kind.TOOL_ARGUMENTS)
        current(); check(patchInvoked.add(execution.locator.id)) {"Patch invocation cannot be repeated"}
        session(execution.locator)
    }
    suspend fun invokePatch(execution: Execution, prepared: StreamedPatchArgumentExtractor.Prepared,
        context: AgentTools.Ctx, current: () -> Unit, completed: (PatchCompletion)->Unit): PatchCompletion {
        require(execution.journal === this)
        return PatchCompletion.invoke(execution,prepared,context,current,completed)
    }
    /** A returned error is a completed invocation, NOT evidence of no effects or rollback. */
    fun checkpointPatchCompletion(execution: Execution, receipt: PatchCompletion): JsonObject = synchronized(lock) {
        require(execution.journal === this && patchExecutions[execution.locator.id] === execution)
        require(receipt.execution === execution && execution.locator.id in patchInvoked)
        val r=read(execution.locator,{})
        require(r.name=="apply_patch" && r.state==State.EXECUTING.name && r.effect=="PATCH_INVOCATION" && r.hash==receipt.argumentsHash)
        val result=buildJsonObject {put("role","tool");put("tool_call_id",r.call);put("content",receipt.output)}
        val saved=saveResult(execution.locator,result)
        save(execution.locator,r.copy(state=State.SETTLED.name,resultBytes=saved.first,resultHash=saved.second),{})
        patchExecutions.remove(execution.locator.id);patchInvoked.remove(execution.locator.id)
        result
    }
    fun recordCompletedStdin(owner: OwnedResponseContentStore.Owner, arguments: StreamedFileArgumentExtractor.Source): Pending = synchronized(lock) {
        recordCompletedSource(owner,"write_stdin",arguments)
    }
    private fun admitStdinInvocation(pending: Pending, prepared: StreamedStdinArgumentExtractor.Prepared,
        context: AgentTools.Ctx, current: () -> Unit, runtimeCurrent: () -> Unit): StdinInvocation {
        synchronized(lock) {
            require(pending.journal === this && pendingCapabilities[pending.locator.id]?.first === pending && pending.locator.id in active)
            require(pending.locator.name=="write_stdin" && prepared.callId==pending.locator.callId && context.sourceStdin==null)
            verifyPendingArguments(pending,prepared.originalArguments)
            check(stdinPreparing.add(pending.locator.id)) {"Stdin call preparation already in progress"}
        }
        try {
            current()
            val args=StreamedStdinArgumentExtractor.materialize(session(pending.locator),prepared,current)
            val manager=requireNotNull(context.backgroundCommands) {"Background command manager unavailable"}
            val id=args.getValue("session_id").let { (it as kotlinx.serialization.json.JsonPrimitive).content }
            val text=(args["chars"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val close=(args["close_stdin"] as? kotlinx.serialization.json.JsonPrimitive)?.content=="true"
            val interrupt=(args["interrupt"] as? kotlinx.serialization.json.JsonPrimitive)?.content=="true"
            // Binding is read-only and exact-entry scoped. No permission/effect occurs here.
            val binding=manager.bindSourceStdin(id,owner(pending.locator),prepared.originalArguments.sha256,text,close,interrupt,runtimeCurrent)
            return synchronized(lock) {
                require(pendingCapabilities[pending.locator.id]?.first === pending && pending.locator.id in active)
                val r=read(pending.locator);require(r.name=="write_stdin" && r.state==State.RECORDED.name)
                current();check(context.sourceStdin==null)
                save(pending.locator,r.copy(state=State.EXECUTING.name,effect="STDIN_INVOCATION",stdinSession=id))
                active.remove(pending.locator.id);pendingCapabilities.remove(pending.locator.id);stdinPreparing.remove(pending.locator.id)
                StdinInvocation(Execution(this,pending.locator),args,manager,binding).also {stdinInvocations[pending.locator.id]=it}
            }
        } finally {
            // A preparation failure consumes this gateway attempt too. Pending-only cleanup may settle no
            // execution, but another caller cannot retry binding this Pending capability.
        }
    }
    suspend fun invokeStdin(pending: Pending, prepared: StreamedStdinArgumentExtractor.Prepared,
        context: AgentTools.Ctx, current: () -> Unit, runtimeCurrent: () -> Unit,
        started: (Execution)->Unit, completed: (StdinCompletion)->Unit): StdinCompletion {
        require(pending.journal === this)
        return StdinCompletion.invoke(this,pending,prepared,context,current,runtimeCurrent,started,completed)
    }
    // Historical runtime admission metadata only. These flags NEVER authorize manager operations.
    private fun stdinEvidence(execution: Execution, r: Record): Record {
        require(execution.journal === this)
        val invocation=requireNotNull(stdinInvocations[execution.locator.id])
        require(invocation.execution === execution && r.name=="write_stdin" && r.effect=="STDIN_INVOCATION")
        val proof=invocation.manager.inspectSourceStdin(invocation.binding)
        require(proof.sourceOwner==owner(execution.locator) && proof.argumentsSha256==r.hash && proof.sessionId==r.stdinSession)
        require(proof.offerKnownRejected == (proof.offerRejection != null))
        require(!proof.offerKnownRejected || (proof.offerAttempted && !proof.offerAccepted))
        return r.copy(stdinOfferKnownRejected=proof.offerKnownRejected, stdinOfferRejection=proof.offerRejection?.name,
            stdinObservation=proof.observationStarted,stdinOfferAttempted=proof.offerAttempted,
            stdinOfferAccepted=proof.offerAccepted,stdinEofAttempted=proof.eofAttempted,stdinEofAccepted=proof.eofAccepted,
            stdinInterruptAttempted=proof.interruptAttempted,stdinInterruptAccepted=proof.interruptAccepted)
    }
    class StdinOutcomeUncertainException : IllegalStateException("Command input outcome requires reconciliation. Do not resend this input automatically.")
    fun checkpointStdinCompletion(execution: Execution, receipt: StdinCompletion): JsonObject = synchronized(lock) {
        require(receipt.execution === execution)
        val r=read(execution.locator,{})
        require(r.state==State.EXECUTING.name && r.hash==receipt.argumentsHash)
        val withEvidence=stdinEvidence(execution,r)
        val result=buildJsonObject {put("role","tool");put("tool_call_id",r.call);put("content",receipt.output)}
        val saved=saveResult(execution.locator,result)
        // A typed internal rejection proves this offer never entered the pipe queue. PTY false/throw has
        // no such proof; independent EOF/interrupt attempts remain conservative.
        val potentiallyEffectiveOffer=withEvidence.stdinOfferAttempted==true && withEvidence.stdinOfferKnownRejected!=true
        val attempted=potentiallyEffectiveOffer || withEvidence.stdinEofAttempted==true || withEvidence.stdinInterruptAttempted==true
        val unknown=(potentiallyEffectiveOffer && withEvidence.stdinOfferAccepted!=true) ||
            (withEvidence.stdinEofAttempted==true && withEvidence.stdinEofAccepted!=true) ||
            (withEvidence.stdinInterruptAttempted==true && withEvidence.stdinInterruptAccepted!=true)
        val failedAfterAttempt=attempted && (receipt.output.startsWith("ERROR") || receipt.output.startsWith("DENIED"))
        // Keep the exact return as private historical evidence, not a provider-ready tool result.
        // Otherwise the model could interpret a failed command as permission to resend under a new ID.
        if(unknown || failedAfterAttempt) {
            save(execution.locator,withEvidence.copy(resultBytes=saved.first,resultHash=saved.second),{})
            throw StdinOutcomeUncertainException()
        }
        save(execution.locator,withEvidence.copy(state=State.SETTLED.name,resultBytes=saved.first,resultHash=saved.second),{})
        stdinInvocations.remove(execution.locator.id)
        result
    }
    /** An interrupted attempt remains unresolved. Preserve admission facts, never synthesize a tool result. */
    fun checkpointStdinUnresolved(execution: Execution) = synchronized(lock) {
        val r=read(execution.locator,{})
        require(r.state==State.EXECUTING.name)
        save(execution.locator,stdinEvidence(execution,r),{})
    }
    private fun recordCompletedSource(owner: OwnedResponseContentStore.Owner, name: String, arguments: StreamedFileArgumentExtractor.Source): Pending {
        require(arguments.kind == OwnedResponseContentStore.Kind.TOOL_ARGUMENTS)
        val l = Locator(id(owner), owner.sessionId, owner.runId, owner.callId, name)
        require(!path(l.id).exists()) { "Tool call already recorded; automatic replay execution is forbidden" }
        arguments.verify(session(l))
        session(l).openBytes(arguments.handle).use { StreamedJsonValidator.requireObject(it) }
        save(l, Record(owner.sessionId, owner.runId, owner.callId, name, arguments.handle.serialized,
            arguments.bytes, arguments.utf16Units, arguments.sha256, true, State.RECORDED.name))
        active.add(l.id); return Pending(this, l).also { pendingCapabilities[l.id] = it to arguments }
    }
    fun recordIncomplete(owner: OwnedResponseContentStore.Owner, name: String, handle: OwnedResponseContentStore.Handle): Locator = synchronized(lock) {
        require(name in setOf("write_file", "edit_file", "write", "edit"))
        val l = Locator(id(owner), owner.sessionId, owner.runId, owner.callId, name)
        require(!path(l.id).exists())
        val content = session(l).open(handle)
        require(content.kind == OwnedResponseContentStore.Kind.TOOL_ARGUMENTS && content.state == OwnedResponseContentStore.State.INCOMPLETE)
        save(l, Record(owner.sessionId, owner.runId, owner.callId, name, handle.serialized,
            content.byteLength, content.utf16Length, content.sha256, false, State.INCOMPLETE.name))
        l
    }
    /** Fork preserves exact bytes and lifecycle evidence but NEVER mints execution permission. */
    fun copyForFork(locator: Locator, target: ToolCallHistoryJournal, targetOwner: OwnedResponseContentStore.Owner): Locator {
        require(targetOwner.callId == locator.callId) { "Fork must preserve protocol call identity" }
        val r = synchronized(lock) { read(locator) }
        val handle = OwnedResponseContentStore.Handle.parse(r.handle)
        val sourceSession = session(locator)
        val actual = sourceSession.open(handle)
        require(actual.kind == OwnedResponseContentStore.Kind.TOOL_ARGUMENTS && actual.byteLength == r.bytes && actual.sha256 == r.hash &&
            (actual.state == OwnedResponseContentStore.State.COMPLETE) == r.complete)
        val targetLocator = Locator(target.id(targetOwner), targetOwner.sessionId, targetOwner.runId, targetOwner.callId, r.name)
        val destination = target.session(targetLocator)
        val copied = destination.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use { writer ->
            sourceSession.openBytes(handle).use { input ->
                val bytes = ByteArray(8192)
                while(true) { checkCancelled(); val count = input.read(bytes); if(count < 0) break; check(count > 0); writer.appendUtf8(bytes, 0, count) }
            }
            if(r.complete) writer.sealCompleted { StreamedJsonValidator.requireObject(it) }.content else writer.sealIncomplete()
        }
        require(copied.byteLength == r.bytes && copied.sha256 == r.hash)
        val resultChannel=if(r.state == State.SETTLED.name) verifiedResultChannel(locator,r) else null
        try {
            synchronized(target.lock) {
                require(!target.path(targetLocator.id).exists())
                if(resultChannel!=null) java.io.FileOutputStream(target.resultPath(targetLocator)).use { output ->
                    copyVerifiedResult(resultChannel,output);output.fd.sync()
                }
                target.save(targetLocator, r.copy(session = targetOwner.sessionId, run = targetOwner.runId,
                    handle = copied.handle.serialized, chars = copied.utf16Length))
            }
        } finally { resultChannel?.close() }
        return targetLocator
    }
    fun approve(pending: Pending, evidenceDigest: String, currentAuthorization: () -> Unit) = synchronized(lock) {
        require(pending.journal === this && pending.locator.id in active)
        require(evidenceDigest.matches(Regex("[a-f0-9]{64}")))
        val r = read(pending.locator); require(r.name in setOf("write_file", "edit_file", "write", "edit") && r.state == State.RECORDED.name)
        source(pending.locator, r); currentAuthorization(); checkCancelled()
        save(pending.locator, r.copy(state = State.APPROVED.name, approvalEvidence = evidenceDigest))
    }
    fun approveStreamed(pending: Pending, evidenceDigest: String, observation: StreamedTextEditObservation,
        currentAuthorization: () -> Unit) = synchronized(lock) {
        require(pending.journal === this && pending.locator.id in active)
        val r=read(pending.locator);require(r.name in setOf("write_file", "edit_file", "write", "edit") && r.state==State.RECORDED.name && observation.argumentsSha256==r.hash)
        require(evidenceDigest.matches(Regex("[a-f0-9]{64}")))
        source(pending.locator,r);currentAuthorization();checkCancelled()
        save(pending.locator,r.copy(state=State.APPROVED.name,approvalEvidence=evidenceDigest,
            reviewedRoot=observation.repository.root.canonicalPath,reviewedPath=observation.path,
            reviewedBytes=observation.after.bytes,reviewedHash=observation.after.sha256,effect="DISK"))
        observations[pending.locator.id]=observation
    }
    fun approveLive(pending: Pending, evidenceDigest: String, observation: StreamedLiveEditObservation,
        currentAuthorization: () -> Unit) = synchronized(lock) {
        require(pending.journal === this && pending.locator.id in active)
        val r = read(pending.locator)
        require(r.name in setOf("write_file", "edit_file", "write", "edit") && r.state == State.RECORDED.name && observation.argumentsSha256 == r.hash)
        require(evidenceDigest.matches(Regex("[a-f0-9]{64}")))
        source(pending.locator, r); currentAuthorization(); checkCancelled(); observation.verifyUndo()
        require(observation.replacement.publication == null)
        save(pending.locator, r.copy(state = State.APPROVED.name, approvalEvidence = evidenceDigest,
            reviewedRoot = observation.canonicalRoot, reviewedPath = observation.path,
            reviewedBytes = observation.replacement.utf8Bytes, reviewedHash = observation.replacement.sha256,
            effect = "LIVE_BUFFER", liveDocumentId = observation.before.documentId,
            liveRevision = observation.before.revision, liveChangeVersion = observation.before.changeVersion,
            liveBeforeBytes = observation.beforeContent.bytes, liveBeforeHash = observation.beforeContent.sha256,
            liveBackup = observation.backupRelative, liveBackupIdentity = observation.backupIdentity, liveRootIdentity = observation.rootIdentity))
        liveObservations[pending.locator.id] = observation
    }

    /** Private reservation storage only. Does not publish an artifact or convey permission. */
    fun artifactStorage(): java.nio.file.Path = synchronized(lock) {
        checkCancelled(); ChatMediaStoragePaths.requireNoLinks(root)
        Files.createDirectories(root.toPath())
        val path = root.toPath().resolve("artifact-publications")
        ChatMediaStoragePaths.requireNoLinks(path.toFile()); Files.createDirectories(path)
        path
    }
    fun reserveArtifactOperation(pending: Pending, operationId: String, originalType: String) = synchronized(lock) {
        require(pending.journal === this && pending.locator.id in active && pendingCapabilities[pending.locator.id]?.first === pending)
        require(UUID.fromString(operationId).toString() == operationId && originalType.length <= 4096)
        val r=read(pending.locator); require(r.name == "artifact" && r.state == State.RECORDED.name && r.artifactOperation == null)
        save(pending.locator,r.copy(artifactOperation=operationId,artifactType=originalType,effect="ARTIFACT_CREATE"))
    }
    fun approveArtifact(pending: Pending, observation: SourceArtifactPublisher.Observation, publisher: SourceArtifactPublisher,
        currentAuthorization: () -> Unit) = synchronized(lock) {
        require(pending.journal === this && pending.locator.id in active && pendingCapabilities[pending.locator.id]?.first === pending)
        val r=read(pending.locator)
        require(r.name == "artifact" && r.state == State.RECORDED.name && r.effect == "ARTIFACT_CREATE")
        require(observation.owner == owner(pending.locator) && observation.argumentsSha256 == r.hash &&
            observation.operationId == r.artifactOperation && observation.originalType == r.artifactType)
        source(pending.locator,r); currentAuthorization(); checkCancelled()
        save(pending.locator,r.copy(state=State.APPROVED.name,approvalEvidence=r.hash,
            reviewedPath=observation.path,reviewedBytes=observation.bytes,reviewedHash=observation.sha256,
            artifactRootIdentity=observation.rootIdentity))
        artifactObservations[pending.locator.id]=observation
        artifactPublishers[pending.locator.id]=publisher
    }
    /** Normal artifact settlement requires its native publication proof, not a success String. */
    fun settleArtifact(execution: Execution, receipt: SourceArtifactPublisher.Receipt, result: JsonObject): JsonObject = synchronized(lock) {
        require(execution.journal === this)
        val original=requireNotNull(execution.artifactObservation)
        val verified=requireNotNull(execution.artifactPublisher).verify(receipt)
        val actual=verified.observation
        val r=read(execution.locator)
        require(r.name == "artifact" && r.state == State.EXECUTING.name && r.effect == "ARTIFACT_CREATE")
        require(actual.owner == owner(execution.locator) && actual.argumentsSha256 == r.hash &&
            actual.operationId == r.artifactOperation && actual.originalType == r.artifactType &&
            actual.rootIdentity == r.artifactRootIdentity && actual.path == r.reviewedPath &&
            actual.bytes == r.reviewedBytes && actual.sha256 == r.reviewedHash &&
            actual.contentSha256 == original.contentSha256 && actual.id == original.id)
        require(result["role"]?.toString() == "\"tool\"" && result["tool_call_id"]?.toString() == json.encodeToString(kotlinx.serialization.serializer<String>(),r.call))
        val originalText=(result["content"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("Artifact result requires text content")
        val normalized=buildJsonObject {
            put("role","tool");put("tool_call_id",r.call)
            put("content",originalText +
                (if (verified.currentContentVerified) "" else " Artifact was published; current content may have changed or been removed. Do not recreate automatically.") +
                (if (verified.durabilityConfirmed) "" else " Published; storage durability unconfirmed. Do not repeat this operation."))
        }
        val saved=saveResult(execution.locator,normalized)
        save(execution.locator,r.copy(state=State.SETTLED.name,resultBytes=saved.first,resultHash=saved.second,
            artifactDurabilityConfirmed=verified.durabilityConfirmed))
        normalized
    }
    fun checkpointArtifactCommitted(execution: Execution, receipt: SourceArtifactPublisher.Receipt): JsonObject = synchronized(lock) {
        require(execution.journal === this)
        val original=requireNotNull(execution.artifactObservation)
        val verified=requireNotNull(execution.artifactPublisher).verify(receipt)
        val actual=verified.observation
        val r=read(execution.locator,{})
        require(r.name == "artifact" && r.state == State.EXECUTING.name && r.effect == "ARTIFACT_CREATE")
        require(actual.owner == owner(execution.locator) && actual.argumentsSha256 == r.hash &&
            actual.operationId == r.artifactOperation && actual.originalType == r.artifactType &&
            actual.rootIdentity == r.artifactRootIdentity && actual.path == r.reviewedPath &&
            actual.bytes == r.reviewedBytes && actual.sha256 == r.reviewedHash)
        require(actual.operationId == original.operationId && actual.id == original.id && actual.contentSha256 == original.contentSha256)
        val result=buildJsonObject {
            put("role","tool");put("tool_call_id",r.call)
            put("content","Artifact publication verified at ${actual.path} (${actual.bytes} bytes). " +
                (if (verified.currentContentVerified) "" else "It was published; current content may have changed or been removed. ") +
                (if (verified.durabilityConfirmed) "" else "Published; storage durability unconfirmed. ") +
                "Do not repeat this operation automatically.")
        }
        val saved=saveResult(execution.locator,result)
        save(execution.locator,r.copy(state=State.SETTLED.name,resultBytes=saved.first,resultHash=saved.second,
            artifactDurabilityConfirmed=verified.durabilityConfirmed),{})
        result
    }
    fun beginExecution(pending: Pending, currentAuthorization: () -> Unit): Execution = synchronized(lock) {
        require(pending.journal === this && pending.locator.id in active)
        val r = read(pending.locator); require(r.state == State.APPROVED.name)
        source(pending.locator, r); currentAuthorization(); checkCancelled()
        save(pending.locator, r.copy(state = State.EXECUTING.name))
        active.remove(pending.locator.id)
        pendingCapabilities.remove(pending.locator.id);stdinPreparing.remove(pending.locator.id)
        Execution(this, pending.locator, observations.remove(pending.locator.id), liveObservations.remove(pending.locator.id), artifactObservations.remove(pending.locator.id), artifactPublishers.remove(pending.locator.id))
    }
    /** Result publication is not proof of disk mutation: caller supplies an attributable receipt/result. */
    fun settle(execution: Execution, result: JsonObject) = synchronized(lock) {
        require(execution.journal === this)
        val r = read(execution.locator); require(r.state == State.EXECUTING.name)
        require(r.name !in setOf("artifact","apply_patch","write_stdin","run_command","bash","exec_command") && r.effect !in setOf("ARTIFACT_CREATE","PATCH_INVOCATION","STDIN_INVOCATION","COMMAND_INVOCATION")) { "Artifact settlement requires publication proof" }
        require(result["role"]?.toString() == "\"tool\"" && result["tool_call_id"]?.toString() == json.encodeToString(kotlinx.serialization.serializer<String>(), r.call))
        val receipt = saveResult(execution.locator,result)
        save(execution.locator, r.copy(state = State.SETTLED.name, resultBytes = receipt.first, resultHash = receipt.second))
    }
    /** Finite journal cleanup only. */

    fun checkpointCommitted(execution: Execution, proof: AgentStreamedTextEditCall.PublicationProof): JsonObject = synchronized(lock) {
        require(execution.journal === this)
        val observation=requireNotNull(execution.observation)
        val r=read(execution.locator, {})
        require(r.name in setOf("write_file", "edit_file", "write", "edit") && r.state==State.EXECUTING.name && r.effect != "LIVE_BUFFER")
        val intent=proof.record.intent;val receipt=proof.receipt
        require(intent.sessionId==r.session && intent.runId==r.run && intent.callId==r.call && intent.tool==r.name)
        require(intent.argumentsSha256==r.hash && intent.path==r.reviewedPath && intent.canonicalRoot==r.reviewedRoot)
        require(intent.after.bytes==r.reviewedBytes && intent.after.sha256==r.reviewedHash)
        require(receipt.preparation === observation.preparation && receipt.preparation==intent.streamPreparation)
        require(receipt.preparation.relativePath==r.reviewedPath && receipt.preparation.canonicalRoot==r.reviewedRoot &&
            receipt.preparation.bytes==r.reviewedBytes && receipt.preparation.sha256==r.reviewedHash && receipt.destinationIdentity==receipt.preparation.stageIdentity)
        val result=buildJsonObject {
            put("role","tool");put("tool_call_id",r.call)
            put("content","Local write verified at ${r.reviewedPath} (${r.reviewedBytes} bytes). Synchronization is not asserted by this recovery checkpoint. Do not repeat this operation automatically.")
        }
        require(result.toString().toByteArray().size<=16384) { "Settlement receipt exceeds bounded cleanup schema" }
        val saved=saveResult(execution.locator,result)
        save(execution.locator,r.copy(state=State.SETTLED.name,resultBytes=saved.first,resultHash=saved.second),{})
        result
    }
    /** Metadata-only Stop settlement for a proven native editor publication, never a disk receipt. */
    fun checkpointLiveCommitted(execution: Execution, proof: StreamedLiveEditObservation.PublicationProof): JsonObject = synchronized(lock) {
        require(execution.journal === this && execution.liveObservation === proof.observation)
        val observation = requireNotNull(execution.liveObservation)
        val r = read(execution.locator, {})
        require(r.state == State.EXECUTING.name && r.effect == "LIVE_BUFFER")
        val receipt = proof.publication
        require(receipt.replacement === observation.replacement && receipt.effect == "LIVE_BUFFER")
        require(r.hash == observation.argumentsSha256 && r.reviewedRoot == observation.canonicalRoot &&
            r.liveRootIdentity == observation.rootIdentity && r.reviewedPath == receipt.path && r.reviewedBytes == receipt.utf8Bytes && r.reviewedHash == receipt.sha256)
        require(r.liveDocumentId == receipt.before.documentId && r.liveRevision == receipt.before.revision &&
            r.liveChangeVersion == receipt.before.changeVersion && receipt.after.documentId == r.liveDocumentId)
        require(r.liveBeforeBytes == observation.beforeContent.bytes && r.liveBeforeHash == observation.beforeContent.sha256 &&
            r.liveBackup == observation.backupRelative && r.liveBackupIdentity == observation.backupIdentity)
        val result = buildJsonObject {
            put("role", "tool"); put("tool_call_id", r.call)
            put("content", "Live editor update verified at ${r.reviewedPath}; document_id=${receipt.after.documentId} " +
                "revision=${receipt.after.revision} change_version=${receipt.after.changeVersion}. " +
                "This receipt does not assert a disk save or that later user edits are absent. " +
                (if (proof.undoAcknowledged) "Undo was registered. " else "Undo registration is uncertain; the reviewed backup was retained. ") +
                "Do not repeat this operation automatically.")
        }
        require(result.toString().toByteArray(Charsets.UTF_8).size <= 16384)
        val saved = saveResult(execution.locator, result)
        save(execution.locator, r.copy(state = State.SETTLED.name, resultBytes = saved.first, resultHash = saved.second), {})
        result
    }

    /** Denial/cancellation before publication settles the protocol without claiming any execution. */
    fun settleWithoutExecution(pending: Pending, result: JsonObject) = synchronized(lock) {
        require(pending.journal === this && pending.locator.id in active)
        val r = read(pending.locator); require(r.state == State.RECORDED.name || r.state == State.APPROVED.name)
        require(result["role"]?.toString() == "\"tool\"" && result["tool_call_id"]?.toString() == json.encodeToString(kotlinx.serialization.serializer<String>(), r.call))
        val receipt = saveResult(pending.locator,result)
        save(pending.locator, r.copy(state = State.SETTLED.name, resultBytes = receipt.first, resultHash = receipt.second)); active.remove(pending.locator.id); pendingCapabilities.remove(pending.locator.id);stdinPreparing.remove(pending.locator.id);commandPreparing.remove(pending.locator.id); observations.remove(pending.locator.id); liveObservations.remove(pending.locator.id); artifactObservations.remove(pending.locator.id); artifactPublishers.remove(pending.locator.id)
    }
    /** Finite Stop cleanup for a runtime call that has never entered execution. No cancelled store
     * reads, restored permits, arbitrary caller results, or workspace operations. */
    fun checkpointNoExecution(pending: Pending): JsonObject = synchronized(lock) {
        require(pending.journal === this && pending.locator.id in active)
        val admitted = requireNotNull(pendingCapabilities[pending.locator.id])
        require(admitted.first === pending) { "Only the original runtime pending capability may settle Stop" }
        val r = read(pending.locator, {})
        require(r.state == State.RECORDED.name || r.state == State.APPROVED.name)
        val source = admitted.second
        require(r.complete && r.handle == source.handle.serialized && r.bytes == source.bytes &&
            r.chars == source.utf16Units && r.hash == source.sha256) { "Pending argument identity changed" }
        val result = buildJsonObject {
            put("role", "tool"); put("tool_call_id", r.call)
            put("content", if(r.name in setOf("run_command","bash","exec_command")) "Cancelled before command invocation. No command activation was attempted by this tool call." else if (r.name == "write_stdin") "Cancelled before execution. No command input, close, or interrupt was attempted by this tool call." else if (r.name == "artifact") "Cancelled before execution. No artifact was published by this tool call; any staged bytes remain recovery data." else "Cancelled before execution. No write was performed by this tool call.")
        }
        require(result.toString().toByteArray(Charsets.UTF_8).size <= 16384)
        val receipt = saveResult(pending.locator, result)
        save(pending.locator, r.copy(state = State.SETTLED.name, resultBytes = receipt.first, resultHash = receipt.second), {})
        active.remove(pending.locator.id); pendingCapabilities.remove(pending.locator.id);stdinPreparing.remove(pending.locator.id);commandPreparing.remove(pending.locator.id); observations.remove(pending.locator.id); liveObservations.remove(pending.locator.id); artifactObservations.remove(pending.locator.id); artifactPublishers.remove(pending.locator.id)
        result
    }
    /** Trusted history admission only; this does not create execution or restored approval. */
    fun recordedArguments(locator: Locator): Pair<OwnedResponseContentStore.Session, StreamedFileArgumentExtractor.Source> = synchronized(lock) {
        val r = read(locator); session(locator) to source(locator, r)
    }
    /** Admits only this runtime's exact pending capability and its original complete source. */
    fun verifyPendingArguments(pending: Pending, expected: StreamedFileArgumentExtractor.Source) = synchronized(lock) {
        require(pending.journal === this && pending.locator.id in active)
        val capability = requireNotNull(pendingCapabilities[pending.locator.id])
        require(capability.first === pending)
        val record = read(pending.locator)
        require(record.state == State.RECORDED.name && record.complete)
        val original = capability.second
        require(expected.kind == OwnedResponseContentStore.Kind.TOOL_ARGUMENTS && original.kind == expected.kind)
        require(original.handle == expected.handle && original.bytes == expected.bytes &&
            original.utf16Units == expected.utf16Units && original.sha256 == expected.sha256)
        require(record.handle == expected.handle.serialized && record.bytes == expected.bytes &&
            record.chars == expected.utf16Units && record.hash == expected.sha256)
        expected.verify(session(pending.locator))
    }
    /** Read-only discovery. Never creates a Pending/Execution or restores execution authority. */
    fun locate(owner: OwnedResponseContentStore.Owner, name: String): Locator? = synchronized(lock) {
        checkCancelled()
        require(name in setOf("write_file", "edit_file", "write", "edit", "artifact", "apply_patch", "write_stdin", "run_command", "bash", "exec_command"))
        ChatMediaStoragePaths.requireNoLinks(root)
        if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) return@synchronized null
        val locator = Locator(id(owner), owner.sessionId, owner.runId, owner.callId, name)
        val file = File(root, "${locator.id}.json")
        ChatMediaStoragePaths.requireNoLinks(file)
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return@synchronized null
        read(locator) // validates exact owner, logical tool and deterministic record identity
        locator
    }
    fun state(locator: Locator): State = synchronized(lock) { State.valueOf(read(locator).state) }
    private fun resultPath(l: Locator) = File(path(l.id).parentFile, "${l.id}.result").also(ChatMediaStoragePaths::requireNoLinks)
    private fun saveResult(l: Locator, result: JsonObject): Pair<Long,String> {
        val encoded=result.toString().toByteArray(Charsets.UTF_8)
        val target=resultPath(l);val tmp=Files.createTempFile(root.toPath(),".result-",".tmp").toFile()
        try { java.io.FileOutputStream(tmp).use { it.write(encoded);it.fd.sync() }
            Files.move(tmp.toPath(),target.toPath(),ATOMIC_MOVE,REPLACE_EXISTING)
            java.nio.channels.FileChannel.open(root.toPath(),java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        } finally { tmp.delete() }
        return encoded.size.toLong() to MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
    }
    private fun verifiedResultChannel(locator: Locator, r: Record): java.nio.channels.FileChannel {
        require(r.state==State.SETTLED.name)
        val channel=java.nio.channels.FileChannel.open(resultPath(locator).toPath(),java.nio.file.StandardOpenOption.READ,NOFOLLOW_LINKS)
        try {
            val digest=MessageDigest.getInstance("SHA-256");var count=0L;val b=java.nio.ByteBuffer.allocate(8192)
            while(true) { checkCancelled();b.clear();val n=channel.read(b);if(n<0)break;check(n>0);digest.update(b.array(),0,n);count+=n }
            require(count==r.resultBytes && digest.digest().joinToString("") { "%02x".format(it) }==r.resultHash) { "Tool result evidence changed" }
            channel.position(0);return channel
        } catch(failure: Throwable) { channel.close();throw failure }
    }
    private fun copyVerifiedResult(channel: java.nio.channels.FileChannel, output: OutputStream) {
        val b=java.nio.ByteBuffer.allocate(8192)
        while(true) { checkCancelled();b.clear();val n=channel.read(b);if(n<0)break;check(n>0);output.write(b.array(),0,n) }
    }
    fun verifyResult(locator: Locator) = synchronized(lock) {
        verifiedResultChannel(locator,read(locator)).use { }
    }
    fun writeResult(locator: Locator, output: OutputStream) = synchronized(lock) {
        verifiedResultChannel(locator,read(locator)).use { copyVerifiedResult(it,output) }
    }
    fun completedArguments(locator: Locator): Pair<OwnedResponseContentStore.Session, StreamedFileArgumentExtractor.Source> = synchronized(lock) {
        val r = read(locator); require(r.state == State.SETTLED.name) { "Unresolved tool call requires recovery; never automatically repeat it" }
        session(locator) to source(locator, r)
    }
    internal fun recoveryJournal() = OwnedChatRecoveryJournal(File(root.parentFile,"recovery"))
    companion object { private val locks = ConcurrentHashMap<String, Any>() }
    private class CommandInvocation(val execution:Execution,var arguments:JsonObject?,
        val manager:BackgroundCommandManager,val options:CommandOptions,val argumentsHash:String) {
        var binding:CommandBinding?=null
    }
    private data class CommandOptions(val command:String,val timeout:Long?,val keep:Boolean,val stop:Boolean,val tty:Boolean)
    private val commandInvocations=mutableMapOf<String,CommandInvocation>()
    private val commandPreparing=mutableSetOf<String>()
    private val commandUnresolved=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Execution,Boolean>())
    class CommandBinding internal constructor(private val journal:ToolCallHistoryJournal,internal val execution:Execution,
        private val current:()->Unit,private val runtimeCurrent:()->Unit) {
        internal var startRequested=false;private set
        // Set only by the closed manager's actual pre-activation persistence callback. startSource
        // cannot invoke Job.start before this callback; no error text is evidence.
        internal var persistenceCallbackObserved=false;private set
        internal var receipt:BackgroundCommandManager.SourceLaunchReceipt?=null;private set
        private var normalReturnHash:String?=null
        internal fun recordNormalReturn(output:String) {
            val invocation=journal.requireCommandBinding(this)
            val observed=invocation.manager.inspectSourceLaunch(checkNotNull(receipt))
            check(observed.activation==BackgroundCommandManager.SourceActivation.ACTIVATED)
            check(normalReturnHash==null)
            normalReturnHash=outputHash(output)
        }
        internal fun matchesNormalReturn(output:String?)=output!=null && normalReturnHash==outputHash(output)
        internal fun recordedReturnHash():String?=normalReturnHash
        private fun outputHash(output:String)=MessageDigest.getInstance("SHA-256").digest(output.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        fun start(manager:BackgroundCommandManager,command:String,timeout:Long?,disk:AgentDiskExecutionState,
            keep:Boolean,stop:Boolean,tty:Boolean):BackgroundCommandManager.Snapshot {
            val invocation=journal.requireCommandBinding(this)
            require(manager===invocation.manager)
            require(invocation.options==CommandOptions(command,timeout,keep,stop,tty)) {"Command invocation options changed"}
            synchronized(this){check(!startRequested);startRequested=true}
            current()
            return run {
                fun capture(value:BackgroundCommandManager.SourceLaunchReceipt){
                    manager.verifySourceLaunch(value);receipt=value
                }
                val value=try {
                    manager.startSource(command,journal.owner(execution.locator),invocation.argumentsHash,
                        timeout,disk,keep,stop,tty,requireCurrent=runtimeCurrent,
                        persistIntent={intent ->
                            persistenceCallbackObserved=true
                            current()
                            journal.associateCommand(this,intent)
                        })
                }catch(failure:BackgroundCommandManager.SourceLaunchFailure){capture(failure.receipt);throw failure}
                catch(failure:BackgroundCommandManager.SourceLaunchCancelled){capture(failure.receipt);throw failure}
                capture(value)
                val observation=manager.inspectSourceLaunch(value)
                check(value.activation==BackgroundCommandManager.SourceActivation.ACTIVATED){"Command session was not activated"}
                observation.snapshot
            }
        }
    }
    class CommandCompletion private constructor(internal val execution:Execution,internal val argumentsHash:String,val output:String) {
        companion object {
            internal suspend fun invoke(journal:ToolCallHistoryJournal,pending:Pending,prepared:StreamedCommandArgumentExtractor.Prepared,
                context:AgentTools.Ctx,current:()->Unit,runtimeCurrent:()->Unit,started:(Execution)->Unit,
                completed:(CommandCompletion)->Unit):CommandCompletion {
                val invocation=journal.admitCommandInvocation(pending,prepared,context,current)
                val binding=CommandBinding(journal,invocation.execution,current,runtimeCurrent)
                synchronized(journal.lock){check(invocation.binding==null);invocation.binding=binding}
                val args=checkNotNull(invocation.arguments).also {invocation.arguments=null}
                started(invocation.execution)
                check(context.sourceCommand==null);context.sourceCommand=binding
                try {
                    val output=AgentTools.execute(prepared.toolName,args,context)
                    return CommandCompletion(invocation.execution,prepared.originalArguments.sha256,output).also(completed)
                }finally{context.sourceCommand=null}
            }
        }
    }
    fun recordCompletedCommand(owner:OwnedResponseContentStore.Owner,name:String,arguments:StreamedFileArgumentExtractor.Source):Pending=synchronized(lock){
        require(name in setOf("run_command","bash","exec_command"));recordCompletedSource(owner,name,arguments)
    }
    private fun admitCommandInvocation(pending:Pending,prepared:StreamedCommandArgumentExtractor.Prepared,
        context:AgentTools.Ctx,current:()->Unit):CommandInvocation {
        synchronized(lock){
            require(pending.journal===this && pendingCapabilities[pending.locator.id]?.first===pending && pending.locator.id in active)
            require(prepared.callId==pending.locator.callId && prepared.toolName==pending.locator.name && context.sourceCommand==null)
            verifyPendingArguments(pending,prepared.originalArguments);check(commandPreparing.add(pending.locator.id))
        }
        current();require(context.sessionId==pending.locator.sessionId)
        val manager=requireNotNull(context.backgroundCommands){"Background command manager unavailable; no alternate backend was used"}
        val args=StreamedCommandArgumentExtractor.materialize(session(pending.locator),prepared,current)
        val exec=prepared.toolName=="exec_command"
        fun primitive(key:String)=args[key] as? kotlinx.serialization.json.JsonPrimitive
        val options=CommandOptions(requireNotNull(primitive(if(exec)"cmd" else "command")).content,
            primitive("timeout_ms")?.content?.toLongOrNull(),exec && primitive("keep_stdin_open")?.content=="true",
            exec || primitive("background")?.content!="true",primitive("requires_tty")?.content=="true")
        return synchronized(lock){
            require(pendingCapabilities[pending.locator.id]?.first===pending && pending.locator.id in active)
            val r=read(pending.locator);require(r.state==State.RECORDED.name && r.name==prepared.toolName)
            current();save(pending.locator,r.copy(state=State.EXECUTING.name,effect="COMMAND_INVOCATION"))
            active.remove(pending.locator.id);pendingCapabilities.remove(pending.locator.id);commandPreparing.remove(pending.locator.id)
            CommandInvocation(Execution(this,pending.locator),args,manager,options,r.hash).also {commandInvocations[pending.locator.id]=it}
        }
    }
    private fun requireCommandBinding(binding:CommandBinding):CommandInvocation=synchronized(lock){
        require(binding.execution.journal===this)
        val invocation=checkNotNull(commandInvocations[binding.execution.locator.id])
        require(invocation.execution===binding.execution && invocation.binding===binding)
        invocation
    }
    private fun associateCommand(binding:CommandBinding,intent:BackgroundCommandManager.SourceLaunchIntent)=synchronized(lock){
        val invocation=requireCommandBinding(binding);val l=binding.execution.locator;val r=read(l)
        require(r.state==State.EXECUTING.name && r.effect=="COMMAND_INVOCATION" && intent.sourceOwner==owner(l) && intent.argumentsSha256==r.hash)
        require(r.commandJob==null && r.hash==invocation.argumentsHash)
        save(l,r.copy(commandJob=intent.jobId,commandHash=intent.commandSha256,commandTimeout=intent.timeoutMs,
            commandKeepInput=intent.keepStdinOpen,commandStopWithAgent=intent.stopWithAgent,commandTty=intent.requiresTty,
            commandActivation="INTENT_RECORDED"))
    }
    suspend fun invokeCommand(pending:Pending,prepared:StreamedCommandArgumentExtractor.Prepared,context:AgentTools.Ctx,
        current:()->Unit,runtimeCurrent:()->Unit,started:(Execution)->Unit,completed:(CommandCompletion)->Unit):CommandCompletion {
        require(pending.journal===this)
        return CommandCompletion.invoke(this,pending,prepared,context,current,runtimeCurrent,started,completed)
    }
    private fun commandEvidence(execution:Execution,r:Record,output:String?=null):Pair<Record,Boolean> {
        require(execution.journal===this)
        val invocation=checkNotNull(commandInvocations[execution.locator.id]);require(invocation.execution===execution)
        val binding=checkNotNull(invocation.binding);require(r.hash==invocation.argumentsHash)
        if(!binding.startRequested)return r.copy(commandActivation="NOT_REQUESTED") to false
        val receipt=binding.receipt ?: return if(!binding.persistenceCallbackObserved)
            r.copy(commandActivation="NO_COMMAND_ACTIVATION",commandDisposition="PRE_ADMISSION_REFUSED") to false
        else r.copy(commandActivation="UNKNOWN") to true
        val observed=invocation.manager.inspectSourceLaunch(receipt)
        require(observed.intent.sourceOwner==owner(execution.locator) && observed.intent.argumentsSha256==r.hash)
        if(observed.activation!=BackgroundCommandManager.SourceActivation.NO_COMMAND_ACTIVATION)require(observed.intent.jobId==r.commandJob)
        val unknown=when(observed.activation){
            BackgroundCommandManager.SourceActivation.NO_COMMAND_ACTIVATION -> false
            BackgroundCommandManager.SourceActivation.ACTIVATION_UNCERTAIN -> true
            BackgroundCommandManager.SourceActivation.ACTIVATED -> if(observed.snapshot.state==BackgroundCommandManager.State.RUNNING)
                !binding.matchesNormalReturn(output) else !SourceCommandDisposition.knownTerminal(observed.snapshot)
        }
        return r.copy(commandReturnHash=binding.recordedReturnHash(),commandActivation=observed.activation.name,commandDisposition=observed.snapshot.state.name,
            commandExit=observed.snapshot.exitCode,commandOutputIncomplete=observed.snapshot.outputIncomplete) to unknown
    }
    class CommandOutcomeUncertainException:IllegalStateException("Command activation outcome is unresolved. Inspect the recorded command session; do not launch it again automatically.")
    fun checkpointCommandCompletion(execution:Execution,receipt:CommandCompletion):JsonObject=synchronized(lock){
        require(receipt.execution===execution);val r=read(execution.locator,{})
        require(r.state==State.EXECUTING.name && r.effect=="COMMAND_INVOCATION" && r.hash==receipt.argumentsHash)
        if(execution in commandUnresolved)throw CommandOutcomeUncertainException()
        val (evidence,unknown)=commandEvidence(execution,r,receipt.output)
        val result=buildJsonObject {put("role","tool");put("tool_call_id",r.call);put("content",receipt.output)}
        val saved=saveResult(execution.locator,result)
        save(execution.locator,evidence.copy(state=if(unknown)State.EXECUTING.name else State.SETTLED.name,resultBytes=saved.first,resultHash=saved.second),{})
        if(unknown){commandInvocations.remove(execution.locator.id);commandUnresolved.add(execution);throw CommandOutcomeUncertainException()}
        commandInvocations.remove(execution.locator.id);result
    }
    fun checkpointCommandUnresolved(execution:Execution)=synchronized(lock){
        val r=read(execution.locator,{});require(r.state==State.EXECUTING.name)
        if(execution in commandUnresolved)return@synchronized
        save(execution.locator,commandEvidence(execution,r).first,{})
        commandInvocations.remove(execution.locator.id);commandUnresolved.add(execution)
    }

}
