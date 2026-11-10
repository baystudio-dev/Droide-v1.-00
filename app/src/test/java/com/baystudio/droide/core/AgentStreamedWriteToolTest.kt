package com.baystudio.droide.core

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentStreamedWriteToolTest {
    private class Env(val root:File,val parent:File,val effect:PermEffect) {
        var current=true;var live=false;var wrongHookScope=false;var hooksAfterWrite=false;var stopAfterWrite=false;var mutations=0
        lateinit var id:String
        val run=java.util.UUID.randomUUID().toString()
        val sessions=SessionManager(root)
        val terminal=NoTerminal()
        val docs=object:WorkspaceDocumentAuthority {
            override suspend fun snapshot(path:String)=if(live) WorkspaceDocumentSnapshot(path,"dirty",true,WorkspaceDocumentKind.TEXT,true,true,1,1,0,0,0,"doc") else null
            override suspend fun snapshots()=listOfNotNull(snapshot("a.txt"))
            override suspend fun commitDiskOnly(path:String,action:()->Unit) { check(!live) { "Live editor appeared" };action() }
        }
        val files=FileRepository(root,onMutation={ if(it is FileRepository.Mutation.Write) { mutations++;if(stopAfterWrite)current=false
            if(hooksAfterWrite) File(root,".droide/hooks.json").writeText("""{"enabled":true,"events":{"PostToolUse":[{"command":"forbidden {event_file}"}]}}""") } })
        val history=EditHistory(files,streamCommitAuthority=docs)
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
        lateinit var pending:ToolCallHistoryJournal.Pending
        lateinit var call:AgentStreamedTextEditCall
        var observation:StreamedTextEditObservation?=null
        var execution:ToolCallHistoryJournal.Execution?=null
        val journal=ToolCallHistoryJournal(File(parent,"journal"),store) { checkCurrent() }
        fun checkCurrent() { if(!current)throw CancellationException("revoked") }
        suspend fun prepare(text:String,path:String="a.txt") {
            id=sessions.create().id
            sourceSession=store.authorize(OwnedResponseContentStore.Owner(id,run,"call"))
            val original=buildJsonObject { put("path",path);put("content",text) }.toString()
            val source=sourceSession.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use { it.append(original);it.sealCompleted { StreamedJsonValidator.requireObject(it) }.content }
            prepared=StreamedFileArgumentExtractor.extract(sourceSession,source.handle,StreamedFileArgumentExtractor.Tool.WRITE_FILE,"call")
            pending=journal.recordCompleted(OwnedResponseContentStore.Owner(id,run,"call"),"write_file",prepared.originalArguments)
            call=AgentStreamedTextEditCall(sessions,SessionTextEditEvidence.Owner(id,run,"call","tool:1","write_file"),{current},{_,_->})
        }
        fun context() = AgentTools.Ctx(files,terminal,GitManager(root),approvals,QuestionManager(),history,perms,AgentMode.BUILD,LlmClient(),
                AgentConfig(baseUrl="",apiKey="",model="unreachable"),root,TodoManager(),id,lsp,documentAuthority=docs,webResearch=web)
        suspend fun execute(beforeApproved:()->Unit={}):AgentStreamedWriteTool.Result {
            val context=context()
            val hookOwner=HookInvocationAuthority(AgentMode.BUILD,AgentRequestProvenance.primary(),if(wrongHookScope)"wrong-scope" else context.permissionScope,{current})
            val hooks=AgentHookLifecycleBridge(HookManager(root,terminal,perms,approvals),{id},{AgentMode.BUILD})
            return AgentStreamedWriteTool.execute(prepared,sourceSession,context,hooks,hookOwner,call,::checkCurrent) { actual ->
                beforeApproved();observation=actual
                journal.approveStreamed(pending,"a".repeat(64),actual,::checkCurrent)
                execution=journal.beginExecution(pending,::checkCurrent)
            }
        }
    }
    private fun fixture(effect:PermEffect=PermEffect.ALLOW,body:suspend Env.()->Unit)=runBlocking {
        val parent=Files.createTempDirectory("streamed-agent-tool-").toFile();val root=File(parent,"workspace").apply{mkdir()};val env=Env(root,parent,effect)
        try { withTimeout(30000) { env.body() } } finally { env.approvals.resolve(ApprovalResolution.DENY);env.web.close();parent.deleteRecursively() }
    }
    @Test fun allowLargeExactWriteAndUndo()=fixture {
        File(root,"a.txt").writeText("before")
        val text="🌏 exact\n".repeat(230000);prepare(text)
        val result=execute();assertTrue(result is AgentStreamedWriteTool.Result.Committed)
        assertEquals(text,File(root,"a.txt").readText());assertEquals(1,mutations)
        assertTrue(history.undo().startsWith("Undone:"));assertEquals("before",File(root,"a.txt").readText())
    }
    @Test fun askExposesFullProposalAndDenialDoesNotMutate()=fixture(PermEffect.ASK) { coroutineScope {
        val text="readable full proposal\n".repeat(9000);prepare(text)
        val work=async { execute() }
        val request=withTimeout(5000) { approvals.pending.filterNotNull().first() }
        val proposal=requireNotNull(request.proposedContent)
        assertTrue(proposal.readPage(0).hasNext)
        val full=java.io.ByteArrayOutputStream();proposal.export(full)
        assertEquals(text,full.toString("UTF-8"));assertTrue(request.detail.contains("partial preview"))
        approvals.resolve(ApprovalResolution.DENY)
        assertTrue(work.await() is AgentStreamedWriteTool.Result.Rejected);assertFalse(File(root,"a.txt").exists());assertEquals(0,mutations)
    } }
    @Test fun denyNeverMutates()=fixture(PermEffect.DENY) { prepare("x".repeat(150000));assertTrue(execute() is AgentStreamedWriteTool.Result.Rejected);assertEquals(0,mutations) }
    @Test fun revocationAfterAskPreventsPublication()=fixture(PermEffect.ASK) { coroutineScope {
        prepare("x".repeat(150000));val work=async { runCatching { execute() } }
        approvals.pending.filterNotNull().first();current=false;approvals.resolve(ApprovalResolution.ALLOW_ONCE)
        assertTrue(work.await().isFailure);assertEquals(0,mutations)
    } }
    @Test fun targetCasAndLateLiveEditorBlockPublication()=fixture {
        File(root,"a.txt").writeText("before");prepare("x".repeat(150000))
        assertTrue(runCatching { execute { File(root,"a.txt").writeText("external") } }.isFailure)
        assertEquals("external",File(root,"a.txt").readText());assertEquals(0,mutations)
    }
    @Test fun lateLiveBarrierNeverWritesBehindEditor()=fixture {
        prepare("x".repeat(150000));assertTrue(runCatching { execute { live=true } }.isFailure)
        assertFalse(File(root,"a.txt").exists());assertEquals(0,mutations)
    }
    @Test fun configuredHookFailsClosed()=fixture {
        prepare("x".repeat(150000));File(root,".droide/hooks.json").writeText("""{"enabled":true,"events":{"PreToolUse":[{"command":"forbidden {event_file}"}]}}""")
        assertTrue(execute() is AgentStreamedWriteTool.Result.Rejected);assertEquals(0,mutations);assertEquals(0,terminal.calls)
    }
    @Test fun stopAfterPublicationSettlesJournalWithAttributableProof()=fixture {
        prepare("x".repeat(150000));stopAfterWrite=true
        assertTrue(runCatching { execute() }.isFailure)
        val proof=call.publicationProof(requireNotNull(observation))
        journal.checkpointCommitted(requireNotNull(execution),proof)
        val recovered=ToolCallHistoryJournal(File(parent,"journal"),store)
        assertEquals(ToolCallHistoryJournal.State.SETTLED,recovered.state(pending.locator));assertEquals(1,mutations)
        assertEquals("x".repeat(150000),File(root,"a.txt").readText())
    }
    @Test fun hookConfigurationAfterCommitKeepsReceiptAndNeverRepeatsWrite()=fixture {
        prepare("x".repeat(150000));hooksAfterWrite=true
        val result=execute()
        assertTrue(result is AgentStreamedWriteTool.Result.Committed)
        assertTrue(result.output.contains("do not repeat"))
        assertEquals(1,mutations);assertEquals(0,terminal.calls)
        assertEquals("x".repeat(150000),File(root,"a.txt").readText())
    }
    @Test fun absoluteAndAliasedPathsNeverReinterpretTargets() {
        listOf("/a.txt","C:\\a.txt","\\\\server\\a.txt","./a.txt","x/../a.txt","a.txt/").forEach { path -> fixture {
            prepare("x".repeat(150000),path)
            assertTrue(runCatching { execute() }.isFailure)
            assertFalse(File(root,"a.txt").exists());assertEquals(0,mutations)
        } }
    }
    @Test fun hookAuthorityMismatchFailsBeforeApprovalAndPublication()=fixture(PermEffect.ASK) {
        prepare("x".repeat(150000));wrongHookScope=true
        assertTrue(runCatching { execute() }.isFailure);assertNull(approvals.pending.value);assertEquals(0,mutations)
    }
    @Test fun doomLoopAllowKeepsNormalPolicyWithoutMutation()=fixture {
        prepare("x".repeat(150000));val c=context();val owner=HookInvocationAuthority(c.mode,c.provenance,c.permissionScope,{current})
        assertFalse(AgentStreamedApprovalSupport.doomLoopDenied(prepared,sourceSession,c,owner,::checkCurrent));assertEquals(0,mutations)
    }
    @Test fun doomLoopDenyRejectsWithoutMutation()=fixture(PermEffect.DENY) {
        prepare("x".repeat(150000));val c=context();val owner=HookInvocationAuthority(c.mode,c.provenance,c.permissionScope,{current})
        assertTrue(AgentStreamedApprovalSupport.doomLoopDenied(prepared,sourceSession,c,owner,::checkCurrent));assertEquals(0,mutations)
    }
    @Test fun doomLoopAskShowsExactProposalAndRevocationWins()=fixture(PermEffect.ASK) { coroutineScope {
        val text="x".repeat(150000);prepare(text);val c=context();val owner=HookInvocationAuthority(c.mode,c.provenance,c.permissionScope,{current})
        val work=async { runCatching { AgentStreamedApprovalSupport.doomLoopDenied(prepared,sourceSession,c,owner,::checkCurrent) } }
        val request=approvals.pending.filterNotNull().first();assertEquals("doom_loop",request.action)
        val full=java.io.ByteArrayOutputStream();requireNotNull(request.proposedContent).export(full);assertEquals(text,full.toString("UTF-8"))
        current=false;approvals.resolve(ApprovalResolution.ALLOW_ONCE)
        assertTrue(work.await().isFailure);assertEquals(0,mutations)
    } }
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
