package com.baystudio.droide.core

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SourceAliasDiskToolTest {
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
        var profile:PermissionEngine?=null
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
        var revokeAfterMetaCount: Long? = null
        fun ownedMetaCount() = File(parent,"owned").listFiles().orEmpty().count { it.name.endsWith(".meta") }.toLong()
        fun checkCurrent() {
            if(revokeAfterMetaCount?.let { ownedMetaCount()>it } == true)current=false
            if(!current)throw CancellationException("revoked")
        }
        suspend fun prepare(search:String,replacement:String,path:String="a.txt") {
            id=sessions.create().id
            sourceSession=store.authorize(OwnedResponseContentStore.Owner(id,run,"call"))
            val original=buildJsonObject { put("path",path);put("search",search);put("replace",replacement) }.toString()
            val source=sourceSession.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use { it.append(original);it.sealCompleted { StreamedJsonValidator.requireObject(it) }.content }
            prepared=StreamedFileArgumentExtractor.extractWire(sourceSession,source.handle,"edit","call")
            pending=journal.recordCompleted(OwnedResponseContentStore.Owner(id,run,"call"),"edit",prepared.originalArguments)
            call=AgentStreamedTextEditCall(sessions,SessionTextEditEvidence.Owner(id,run,"call","tool:1","edit"),{current},{_,_->})
        }
        fun context() = AgentTools.Ctx(files,terminal,GitManager(root),approvals,QuestionManager(),history,profile ?: perms,AgentMode.BUILD,LlmClient(),
                AgentConfig(baseUrl="",apiKey="",model="unreachable"),root,TodoManager(),id,lsp,documentAuthority=docs,webResearch=web)
        suspend fun execute(beforeApproved:()->Unit={}):AgentStreamedWriteTool.Result {
            val context=context()
            val hookOwner=HookInvocationAuthority(AgentMode.BUILD,AgentRequestProvenance.primary(),if(wrongHookScope)"wrong-scope" else context.permissionScope,{current})
            val hooks=AgentHookLifecycleBridge(HookManager(root,terminal,perms,approvals),{id},{AgentMode.BUILD})
            return AgentStreamedFileTool.execute(prepared,sourceSession,context,hooks,hookOwner,call,::checkCurrent) { actual ->
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
    @Test fun hooksMatchOriginalWireAliasNotCanonicalOperation() {
        fixture {
            File(root,"a.txt").writeText("old");prepare("old","new")
            File(root,".droide").mkdirs()
            File(root,".droide/hooks.json").writeText("""{"enabled":true,"events":{"PreToolUse":[{"matcher":"edit","command":"forbidden {event_file}"}]}}""")
            val result=execute()
            assertTrue(result is AgentStreamedWriteTool.Result.Rejected)
            assertEquals("old",File(root,"a.txt").readText());assertEquals(0,mutations)
        }
        fixture {
            File(root,"a.txt").writeText("old");prepare("old","new")
            File(root,".droide").mkdirs()
            File(root,".droide/hooks.json").writeText("""{"enabled":true,"events":{"PreToolUse":[{"matcher":"edit_file","command":"forbidden {event_file}"}]}}""")
            assertTrue(execute() is AgentStreamedWriteTool.Result.Committed)
            assertEquals("new",File(root,"a.txt").readText())
        }
    }
    @Test fun canonicalProfileRestrictionStillControlsAliasOperation() {
        fixture {
            File(root,"a.txt").writeText("old");prepare("old","new")
            profile=perms.fork(emptyList(),setOf("read_file"))
            assertTrue(execute() is AgentStreamedWriteTool.Result.Rejected);assertEquals(0,mutations)
        }
        fixture {
            File(root,"a.txt").writeText("old");prepare("old","new")
            profile=perms.fork(emptyList(),setOf("edit_file"))
            assertTrue(execute() is AgentStreamedWriteTool.Result.Committed)
        }
    }
    @Test fun largeExactDiskEditAndUndoPreserveOriginalVersion()=fixture {
        val search="needle🌍".repeat(20000);val replacement="replacement\r\n".repeat(18000)
        val original="\uFEFFprefix\r\n"+search+"\r\ntail";File(root,"a.txt").writeText(original)
        prepare(search,replacement);val result=execute()
        assertTrue(result is AgentStreamedWriteTool.Result.Committed);assertEquals(1,mutations)
        assertEquals("\uFEFFprefix\r\n"+replacement+"\r\ntail",File(root,"a.txt").readText())
        assertTrue(history.undo().startsWith("Undone:"));assertEquals(original,File(root,"a.txt").readText())
    }
    @Test fun denyHappensBeforePrivateSnapshotCopy()=fixture(PermEffect.DENY) {
        File(root,"a.txt").writeText("old".repeat(100000));prepare("old","new")
        val before=ownedMetaCount();val allBefore=File(parent,"owned").listFiles().orEmpty().map{it.name}.toSet()
        assertTrue(execute() is AgentStreamedWriteTool.Result.Rejected)
        assertEquals(before,ownedMetaCount());assertEquals(allBefore,File(parent,"owned").listFiles().orEmpty().map{it.name}.toSet())
        assertEquals(0,mutations);assertNull(observation)
    }
    @Test fun askDenialShowsExactEditProposalAndNeverPublishes()=fixture(PermEffect.ASK) { coroutineScope {
        File(root,"a.txt").writeText("prefix old suffix");val replacement="new".repeat(50000);prepare("old",replacement)
        val work=async { execute() };val request=withTimeout(5000){approvals.pending.filterNotNull().first()}
        assertEquals("edit",request.kind)
        val out=java.io.ByteArrayOutputStream();requireNotNull(request.proposedContent).export(out)
        assertEquals("prefix "+replacement+" suffix",out.toString("UTF-8"))
        approvals.resolve(ApprovalResolution.DENY);assertTrue(work.await() is AgentStreamedWriteTool.Result.Rejected)
        assertEquals("prefix old suffix",File(root,"a.txt").readText());assertEquals(0,mutations);assertNull(observation)
    } }
    @Test fun zeroAndTwoMatchesFailWithoutApprovalOrMutation() {
        for(text in listOf("none","old old"))fixture {
            File(root,"a.txt").writeText(text);prepare("old","new")
            val failure=runCatching{execute()}.exceptionOrNull();assertNotNull(failure)
            assertTrue(generateSequence(failure){it.cause}.any{it is StreamedExactEdit.MatchCount})
            assertEquals(text,File(root,"a.txt").readText());assertEquals(0,mutations);assertNull(approvals.pending.value);assertNull(observation)
        }
    }
    @Test fun casChangeAfterApprovalDoesNotOverwriteNewVersion()=fixture {
        File(root,"a.txt").writeText("old");prepare("old","new".repeat(50000))
        assertTrue(runCatching{execute{File(root,"a.txt").writeText("external newer version")}}.isFailure)
        assertEquals("external newer version",File(root,"a.txt").readText());assertEquals(0,mutations)
    }
    @Test fun cancellationAfterCandidateSealRetainsBothRecoveryHandles()=fixture {
        File(root,"a.txt").writeText("old");prepare("old","new".repeat(50000))
        revokeAfterMetaCount=ownedMetaCount()+1 // snapshot may seal; revoke as candidate seals.
        val failure=runCatching{execute()}.exceptionOrNull()
        val cancelled=generateSequence(failure){it.cause}.filterIsInstance<StreamedDiskEditPreparation.Cancelled>().firstOrNull()
        assertNotNull("$failure",cancelled)
        assertNotNull(cancelled!!.recovery.snapshot);assertNotNull(cancelled.recovery.candidate)
        assertEquals(OwnedResponseContentStore.State.COMPLETE,sourceSession.openCompleted(cancelled.recovery.candidate!!).content.state)
        assertEquals("old",File(root,"a.txt").readText());assertEquals(0,mutations);assertNull(observation)
    }
    @Test fun invalidUtf8TargetIsNotSilentlyReencodedOrMutated()=fixture {
        val original=byteArrayOf(0x61,0xC3.toByte(),0x28);File(root,"a.txt").writeBytes(original);prepare("a","b")
        val failure=runCatching{execute()}.exceptionOrNull();assertNotNull(failure)
        assertTrue(generateSequence(failure){it.cause}.any{it is StreamedDiskEditPreparation.Failure})
        assertArrayEquals(original,File(root,"a.txt").readBytes());assertEquals(0,mutations);assertNull(observation)
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
