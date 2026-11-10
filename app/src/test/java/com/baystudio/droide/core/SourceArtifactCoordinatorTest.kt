package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class SourceArtifactCoordinatorTest {
    @Test(timeout=60000) fun jobCancellationStopsPrivatePreparationEvenWhenOwnerCallbackStaysTrue()=runBlocking {
        ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication());ApplicationWorkAdmission.reopenFromUser()
        val parent=Files.createTempDirectory("artifact-job-cancel-").toFile()
        val workspace=parent.resolve("project").apply{mkdir()}
        var armed=false;lateinit var invocation:Job
        val admission=object:OwnedResponseContentStore.DiskAdmission {
            override fun reserve(bytes:Long):AutoCloseable {
                if(armed) {armed=false;invocation.cancel(CancellationException("Stop during private preparation"))}
                return AutoCloseable {}
            }
        }
        val files=FileRepository(workspace);val terminal=AgentWorkflowTerminal()
        val lsp=object:LspManager {
            override val diagnostics=MutableStateFlow<Map<String,List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath:String):String=error("not used")
            override suspend fun lsp(op:String,path:String,line:Int,col:Int):String=error("not used")
        }
        val approvals=ApprovalManager();val perms=PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW)))
        val ctx=AgentTools.Ctx(files,terminal,GitManager(workspace),approvals,QuestionManager(),EditHistory(files),perms,AgentMode.BUILD,
            LlmClient(),AgentConfig(baseUrl="",apiKey="",model="unused"),workspace,TodoManager(),"session",lsp)
        try {
            val owner=OwnedResponseContentStore.Owner("session","run","call")
            val store=OwnedResponseContentStore(parent.resolve("owned").toPath(),admission)
            val session=store.authorize(owner)
            val raw=session.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use {
                it.append("{\"type\":\"report\",\"content\":\""+"x".repeat(170000)+"\"}")
                it.sealCompleted {input->StreamedJsonValidator.requireObject(input)}.content
            }
            val prepared=StreamedArtifactArgumentExtractor.extract(session,raw.handle,"call")
            val journal=ToolCallHistoryJournal(parent.resolve("journal"),store)
            val pending=journal.recordCompletedArtifact(owner,prepared.originalArguments)
            val call=SourceArtifactCall(PreparedToolInput.SourceArtifact(prepared,prepared.originalArguments,session),journal,pending)
            val hookOwner=HookInvocationAuthority(AgentMode.BUILD,AgentRequestProvenance.primary(),ctx.permissionScope,{true})
            val hooks=AgentHookLifecycleBridge(HookManager(workspace,terminal,perms,approvals),{"session"},{AgentMode.BUILD})
            var cancelled=false
            invocation=launch(start=CoroutineStart.LAZY) {
                try {call.execute(ctx,hooks,hookOwner,owner,1,{})}
                catch(expected:CancellationException) {cancelled=true;throw expected}
            }
            armed=true;invocation.start();withTimeout(30000){invocation.join()}
            assertTrue(cancelled);assertFalse(workspace.resolve(".droide/artifacts").exists())
            val result=checkNotNull(call.checkpointInterrupted())
            assertTrue(result.toString().contains("No artifact was published"))
            assertEquals(ToolCallHistoryJournal.State.SETTLED,journal.state(pending.locator))
        } finally {ctx.webResearch.close();PathSecurity.deleteTreeNoFollow(parent)}
    }
}
