package com.baystudio.droide.core

import com.baystudio.droide.ui.*
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentStreamedLiveWriteToolTest {
    private class Env(val root:File,val parent:File,val effect:PermEffect) {
        var current=true;var live=false;var wrongHookScope=false;var hooksAfterWrite=false;var stopAfterWrite=false;var mutations=0
        lateinit var id:String
        val run=java.util.UUID.randomUUID().toString()
        val sessions=SessionManager(root)
        val terminal=NoTerminal()
        val state=EditorWorkspaceState().apply { completeRecovery() }
        val document=state.document("a.txt")
        var nativeText="dirty"
        var nativePrepare:()->Unit={}
        var failBeforePublish=false
        var hookFailureBeforePublish=false
        val files=FileRepository(root,onMutation={ if(it is FileRepository.Mutation.Write) { mutations++;if(stopAfterWrite)current=false
            if(hooksAfterWrite) File(root,".droide/hooks.json").writeText("""{"enabled":true,"events":{"PostToolUse":[{"command":"forbidden {event_file}"}]}}""") } })
        val docs=EditorWorkspaceDocumentAuthority(state,Dispatchers.Unconfined, files = files)
        val history=EditHistory(files,docs)
        val approvals=ApprovalManager()
        val perms=PermissionEngine(listOf(PermRule("*","*",effect)))
        val web=AgentWebResearch(root,AgentWebTransport { _,_->error("network forbidden") },object:AgentWebPdfBackend {
            override fun pageCount(file:File):Int=error("pdf forbidden")
            override fun text(file:File,page:Int):String?=error("pdf forbidden")
            override fun screenshot(file:File,page:Int,output:OutputStream):AgentWebImageSize=error("pdf forbidden")
        })
        val lsp=object:LspManager {
            override val diagnostics=MutableStateFlow<Map<String,List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath:String):String=error("lsp forbidden")
            override suspend fun lsp(op:String,path:String,line:Int,col:Int):String=error("lsp forbidden")
        }
        val store=OwnedResponseContentStore(File(parent,"owned").toPath(),OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0))
        lateinit var sourceSession:OwnedResponseContentStore.Session
        lateinit var prepared:StreamedFileArgumentExtractor.Prepared
        var observation:StreamedLiveEditObservation?=null
        val journal=ToolCallHistoryJournal(File(parent,"journal"),store) { checkCurrent() }
        lateinit var pending:ToolCallHistoryJournal.Pending
        var execution:ToolCallHistoryJournal.Execution?=null
        fun checkCurrent() { if(!current)throw CancellationException("revoked") }
        suspend fun prepare(text:String,path:String="a.txt", search:String?=null, dirty:String="dirty") {
            File(root,"a.txt").writeText("saved")
            document.ensureLoaded(files);document.capture(dirty);nativeText=dirty
            document.nativeMutationBinding=EditorNativeMutationBinding(Any(),{true},{nativePrepare()},{old,new,publish->
                check(old==nativeText);nativeText=new;mutations++;if(hookFailureBeforePublish)throw SourceBackedHookTransportUnavailableException();if(failBeforePublish)error("native failed after effect");publish();if(stopAfterWrite)throw CancellationException("Stop after effect")
            })
            id=sessions.create().id
            sourceSession=store.authorize(OwnedResponseContentStore.Owner(id,run,"call"))
            val tool=if(search==null) StreamedFileArgumentExtractor.Tool.WRITE_FILE else StreamedFileArgumentExtractor.Tool.EDIT_FILE
            val original=buildJsonObject { put("path",path);if(search==null)put("content",text) else {put("search",search);put("replace",text)};put("expected_document_id",document.documentId);put("expected_revision",document.revision);put("expected_change_version",document.changeVersion) }.toString()
            val source=sourceSession.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use { it.append(original);it.sealCompleted { StreamedJsonValidator.requireObject(it) }.content }
            prepared=StreamedFileArgumentExtractor.extract(sourceSession,source.handle,tool,"call")
            pending=journal.recordCompleted(OwnedResponseContentStore.Owner(id,run,"call"),tool.wireName,prepared.originalArguments)

        }
        fun context() = AgentTools.Ctx(files,terminal,GitManager(root),approvals,QuestionManager(),history,perms,AgentMode.BUILD,LlmClient(),
                AgentConfig(baseUrl="",apiKey="",model="unreachable"),root,TodoManager(),id,lsp,documentAuthority=docs,webResearch=web)
        suspend fun execute(beforeApproved:()->Unit={}):AgentStreamedLiveWriteTool.LiveResult {
            val context=context()
            val owner=HookInvocationAuthority(AgentMode.BUILD,AgentRequestProvenance.primary(),context.permissionScope,{current})
            val hooks=AgentHookLifecycleBridge(HookManager(root,terminal,perms,approvals),{id},{AgentMode.BUILD})
            return AgentStreamedLiveWriteTool.execute(prepared,sourceSession,context,hooks,owner,::checkCurrent) { actual ->
                observation=actual
                journal.approveLive(pending,"a".repeat(64),actual,::checkCurrent)
                execution=journal.beginExecution(pending,::checkCurrent)
                beforeApproved()
            }
        }
    }

    private fun fixture(effect:PermEffect=PermEffect.ALLOW,body:suspend Env.()->Unit)=runBlocking {
        val parent=Files.createTempDirectory("streamed-agent-tool-").toFile();val root=File(parent,"workspace").apply{mkdir()};val env=Env(root,parent,effect)
        try { withTimeout(30000) { env.body() } } finally { env.approvals.resolve(ApprovalResolution.DENY);env.web.close();parent.deleteRecursively() }
    }
    @Test fun completeLiveWriteFromCleanBufferSavesBeforeReturning()=fixture {
        prepare("complete generated file", dirty="saved")
        val result=execute() as AgentStreamedLiveWriteTool.LiveResult.Committed
        assertTrue(result.output, result.output.contains("saved=1 attention=0"))
        assertEquals("complete generated file", File(root,"a.txt").readText())
        assertFalse(document.dirty); assertTrue(result.proof.undoAcknowledged)
        assertTrue(history.undo().startsWith("Undone:"))
        assertEquals("saved", File(root,"a.txt").readText()); assertFalse(document.dirty)
    }

    @Test fun completeLiveWritePreservesDiskAndUndo()=fixture {
        val text="x".repeat(2_100_000);prepare(text)
        val result=execute() as AgentStreamedLiveWriteTool.LiveResult.Committed
        assertTrue(result.proof.undoAcknowledged);assertEquals(text,document.content)
        assertEquals("saved",File(root,"a.txt").readText());assertEquals(1,mutations)
        assertTrue(history.undo().startsWith("Undone:"));assertEquals("dirty",document.content)
    }
    @Test fun journalRejectsEquivalentButDifferentObservationProof()=fixture {
        prepare("replacement");execute()
        val original=requireNotNull(observation)
        val other=StreamedLiveEditObservation(root,original.path,original.before,original.replacement,original.argumentsSha256,original.undo)
        assertTrue(runCatching { journal.checkpointLiveCommitted(requireNotNull(execution),other.publicationProof()) }.isFailure)
        assertEquals(ToolCallHistoryJournal.State.EXECUTING,journal.state(pending.locator))
        journal.checkpointLiveCommitted(requireNotNull(execution),original.publicationProof())
        assertEquals(ToolCallHistoryJournal.State.SETTLED,journal.state(pending.locator))
    }
    @Test fun editUsesDirtySnapshotAndPreservesUndo()=fixture {
        val replacement="🌏".repeat(70000)
        prepare(replacement, search="unique", dirty="before unique after")
        val result=execute() as AgentStreamedLiveWriteTool.LiveResult.Committed
        assertEquals("before ${replacement} after",document.content)
        assertEquals("saved",File(root,"a.txt").readText());assertTrue(result.proof.undoAcknowledged)
        assertTrue(history.undo().startsWith("Undone:"));assertEquals("before unique after",document.content)
    }
    @Test fun editAskShowsCompleteCandidateAndActualToolName()=fixture(PermEffect.ASK) { coroutineScope {
        prepare("x".repeat(150000),search="unique",dirty="left unique right")
        val work=async {execute()};val request=approvals.pending.filterNotNull().first()
        val bytes=java.io.ByteArrayOutputStream();requireNotNull(request.proposedContent).export(bytes)
        assertEquals("left ${"x".repeat(150000)} right",bytes.toString("UTF-8"))
        assertTrue(request.summary.startsWith("Edit "));assertEquals("edit_file",request.kind)
        approvals.resolve(ApprovalResolution.DENY);assertTrue(work.await() is AgentStreamedLiveWriteTool.LiveResult.Rejected)
        assertEquals(0,mutations)
    } }
    @Test fun ambiguousLiveEditNeverPublishesOrCreatesUndo()=fixture {
        prepare("replacement",search="same",dirty="same same")
        assertTrue(runCatching {execute()}.isFailure);assertNull(observation)
        assertEquals(0,mutations);assertEquals(0,history.depth());assertEquals("same same",document.content)
    }
    @Test fun liveEditNativeCasConflictPreservesUserTyping()=fixture {
        prepare("replacement",search="unique",dirty="before unique after")
        nativePrepare={nativeText="later user edit";document.captureNativeText(nativeText)}
        assertTrue(execute() is AgentStreamedLiveWriteTool.LiveResult.Rejected)
        assertEquals(0,mutations);assertEquals("later user edit",document.content)
    }
    @Test fun nativeFailureBeforeReceiptRetainsUndoAndUnresolvedJournal()=fixture {
        prepare("replacement");failBeforePublish=true
        assertTrue(runCatching {execute()}.isFailure)
        val obs=requireNotNull(observation)
        assertEquals("replacement",nativeText);assertTrue(obs.replacement.publicationAttempted)
        assertNull(obs.replacement.publication);assertTrue(obs.undo.backup.exists())
        assertEquals("dirty",obs.undo.backup.readText());assertEquals(ToolCallHistoryJournal.State.EXECUTING,journal.state(pending.locator))
    }
    @Test fun hookTransportExceptionAfterPartialNativeEffectRemainsUnknown()=fixture {
        prepare("replacement");hookFailureBeforePublish=true
        assertTrue(runCatching {execute()}.exceptionOrNull() is SourceBackedHookTransportUnavailableException)
        assertTrue(requireNotNull(observation).replacement.publicationAttempted)
        assertNull(requireNotNull(observation).replacement.publication)
        assertTrue(requireNotNull(observation).undo.backup.exists())
        assertEquals(ToolCallHistoryJournal.State.EXECUTING,journal.state(pending.locator))
    }
    @Test fun undoTamperAfterVerificationBeforeNativeApplyFailsClosed()=fixture {
        prepare("replacement")
        nativePrepare={
            val backup=requireNotNull(observation).undo.backup
            val old=java.nio.file.Files.getLastModifiedTime(backup.toPath())
            backup.writeText("xxxxx")
            java.nio.file.Files.setLastModifiedTime(backup.toPath(),old)
        }
        assertTrue(runCatching {execute()}.isFailure)
        assertEquals(0,mutations);assertFalse(requireNotNull(observation).replacement.publicationAttempted)
        assertEquals("dirty",nativeText)
    }
    @Test fun hardDenyHasNoUndoOrMutation()=fixture(PermEffect.DENY) {
        prepare("x".repeat(150000));assertTrue(execute() is AgentStreamedLiveWriteTool.LiveResult.Rejected)
        assertNull(observation);assertEquals(0,history.depth());assertEquals(0,mutations)
    }
    @Test fun askFullProposalDenialDoesNotMutate()=fixture(PermEffect.ASK) { coroutineScope {
        val text="exact\n".repeat(30000);prepare(text)
        val work=async { execute() };val request=approvals.pending.filterNotNull().first()
        val bytes=java.io.ByteArrayOutputStream();requireNotNull(request.proposedContent).export(bytes)
        assertEquals(text,bytes.toString("UTF-8"));approvals.resolve(ApprovalResolution.DENY)
        assertTrue(work.await() is AgentStreamedLiveWriteTool.LiveResult.Rejected)
        assertEquals(0,mutations);assertNull(observation)
    } }
    @Test fun casAfterApprovalRejectsAndDiscardsUnappliedUndo()=fixture {
        prepare("replacement");val result=execute { document.capture("user typed");nativeText="user typed" }
        assertTrue(result is AgentStreamedLiveWriteTool.LiveResult.Rejected)
        assertEquals(0,mutations);assertEquals("user typed",document.content)
        assertFalse(requireNotNull(observation).undo.backup.exists())
    }
    @Test fun stopAfterNativePublicationRetainsProofAndUndo()=fixture {
        prepare("replacement");stopAfterWrite=true
        assertTrue(runCatching { execute() }.isFailure)
        assertEquals(1,mutations);assertTrue(requireNotNull(observation).publicationProof().undoAcknowledged)
        journal.checkpointLiveCommitted(requireNotNull(execution),requireNotNull(observation).publicationProof())
        val recovered=ToolCallHistoryJournal(File(parent,"journal"),store)
        assertEquals(ToolCallHistoryJournal.State.SETTLED,recovered.state(pending.locator))
        stopAfterWrite=false;assertTrue(history.undo().startsWith("Undone:"));assertEquals("dirty",document.content)
    }
    @Test fun permissionRevokedBeforeNativeApplyPreservesBuffer()=fixture {
        prepare("replacement");nativePrepare={current=false}
        assertTrue(runCatching { execute() }.isFailure)
        assertEquals(0,mutations);assertEquals("dirty",document.content)
        assertFalse(requireNotNull(observation).undo.backup.exists())
    }
    private class NoTerminal:ITerminalSession {
        var calls=0;override val output=MutableStateFlow("")
        private fun fail():Nothing { calls++;error("process forbidden") }
        override fun start()=fail();override fun send(cmd:String)=fail()
        override suspend fun execOnce(cmd:String,timeoutMs:Long):ExecResult=fail()
        override suspend fun execArgv(argv:List<String>,timeoutMs:Long):ExecResult=fail()
        override suspend fun execStreaming(cmd:String,timeoutMs:Long,onOutput:(String)->Unit):ExecResult=fail()
        override suspend fun execStreamingWithInput(cmd:String,timeoutMs:Long,input:ProcessInputBuffer,onOutput:(String)->Unit):ExecResult=fail()
        override fun interrupt(){};override fun clear(){};override fun destroy(){}
    }
}
