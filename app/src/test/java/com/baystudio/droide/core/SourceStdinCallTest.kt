package com.baystudio.droide.core

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class SourceStdinCallTest {
    private class Env(val root:java.io.File,val ctx:AgentTools.Ctx,val manager:BackgroundCommandManager,val command:String,
        val journal:ToolCallHistoryJournal,val pending:ToolCallHistoryJournal.Pending,val prepared:StreamedStdinArgumentExtractor.Prepared,
        val call:SourceStdinCall,val owner:OwnedResponseContentStore.Owner,val offers:AtomicInteger) {
        suspend fun execute():SourceCallLifecycle.Outcome {
            val authority=HookInvocationAuthority(AgentMode.BUILD,ctx.provenance,ctx.permissionScope,{true})
            val hooks=AgentHookLifecycleBridge(HookManager(ctx.workDir,ctx.terminal,ctx.perms,ctx.approvals),{"session"},{AgentMode.BUILD})
            return call.execute(ctx,hooks,authority,owner,1,{})
        }
    }
    private suspend fun fixture(sinkAccept:Boolean=true,effect:PermEffect=PermEffect.ALLOW,wait:Boolean=false,
        progress:(AgentToolProgress)->Unit={},requestedId:String?=null,rejection:String?=null,body:suspend(Env)->Unit) {
        ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication());ApplicationWorkAdmission.reopenFromUser()
        val root=Files.createTempDirectory("stdin-call-").toFile();val workspace=root.resolve("project").apply{mkdir()}
        val offers=AtomicInteger();val ready=CompletableDeferred<Unit>()
        val terminal=object:ITerminalSession by AgentWorkflowTerminal(),InteractivePtyCommandSession {
            override val supportsInteractivePty=true
            override suspend fun execInteractivePty(command:String,timeoutMs:Long,input:ProcessInputBuffer?,onOutput:(String)->Unit):ExecResult {
                val buffer=requireNotNull(input)
                when(rejection) {
                    "CLOSED" -> buffer.close()
                    "PIPE_QUEUE_FULL" -> repeat(8){buffer.offer("queued-$it")}
                    else -> buffer.bindPty({offers.incrementAndGet();sinkAccept},{true})
                }
                ready.complete(Unit);awaitCancellation()
            }
        }
        val manager=BackgroundCommandManager(terminal)
        val command=manager.start("fixture",keepStdinOpen=true,requiresTty=true).id
        withTimeout(5000){ready.await()}
        val files=FileRepository(workspace)
        val lsp=object:LspManager {
            override val diagnostics=MutableStateFlow<Map<String,List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath:String):String=""
            override suspend fun lsp(op:String,path:String,line:Int,col:Int):String=""
        }
        val perms=PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW),PermRule("shell_escape","*",effect)))
        val ctx=AgentTools.Ctx(files,terminal,GitManager(workspace),ApprovalManager(),QuestionManager(),EditHistory(files),perms,
            AgentMode.BUILD,LlmClient(),AgentConfig(baseUrl="",apiKey="",model="unused"),workspace,TodoManager(),"session",lsp,
            backgroundCommands=manager,activeToolNames=setOf("write_stdin"),onProgress=progress)
        try {
            val owner=OwnedResponseContentStore.Owner("session","run","call")
            val store=OwnedResponseContentStore(root.resolve("owned").toPath(),OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0))
            val session=store.authorize(owner)
            val raw="{\"session_id\":\"${requestedId ?: command}\",\"chars\":\""+"\\u0078".repeat(30000)+"\",\"yield_time_ms\":0,\"wait_for_completion\":$wait,\"close_stdin\":${rejection!=null}}"
            val content=session.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use {it.append(raw);it.sealCompleted {input->StreamedJsonValidator.requireObject(input)}.content}
            val prepared=StreamedStdinArgumentExtractor.extract(session,content.handle,"call")
            val journal=ToolCallHistoryJournal(root.resolve("journal"),store)
            val pending=journal.recordCompletedStdin(owner,prepared.originalArguments)
            val call=SourceStdinCall(PreparedToolInput.SourceStdin(prepared,prepared.originalArguments,session),journal,pending)
            body(Env(root,ctx,manager,command,journal,pending,prepared,call,owner,offers))
        } finally {ctx.webResearch.close();manager.closeAndJoin();PathSecurity.deleteTreeNoFollow(root)}
    }
    @Test(timeout=60000) fun escapedLargeInputUsesExistingConsumerAndPreservesExactStructuredResult()=runBlocking {
        fixture {e->
            val outcome=e.execute();assertFalse(outcome.output,outcome.rejected);assertEquals(1,e.offers.get())
            assertEquals(outcome.output,checkNotNull(e.call.settledResult).getValue("content").jsonPrimitive.content)
            assertEquals(ToolCallHistoryJournal.State.SETTLED,e.journal.state(e.pending.locator))
            val metadata=e.root.resolve("journal/${e.pending.locator.id}.json").readText()
            assertTrue(metadata,metadata.contains("\"stdinOfferAccepted\":true"))
            assertFalse(outcome.output.contains("Source arguments"))
            assertEquals(BackgroundCommandManager.State.RUNNING,e.manager.snapshot(e.command)?.state)
        }
    }
    @Test(timeout=60000) fun missingCommandBeforeBindingIsACompletedNoInputError()=runBlocking {
        fixture(requestedId="missing-command"){e->
            val result=e.execute();assertTrue(result.output,result.rejected);assertEquals(0,e.offers.get())
            assertTrue(result.output.contains("No input offer was attempted"))
            assertEquals(ToolCallHistoryJournal.State.SETTLED,e.journal.state(e.pending.locator))
        }
    }
    @Test(timeout=60000) fun shellEscapeDenialNeverOffersInput()=runBlocking {
        fixture(effect=PermEffect.DENY){e->
            val outcome=e.execute();assertTrue(outcome.output,outcome.rejected);assertEquals(0,e.offers.get())
            assertEquals(ToolCallHistoryJournal.State.SETTLED,e.journal.state(e.pending.locator))
        }
    }
    @Test(timeout=60000) fun askDenialKeepsOriginalInputPermissionAndDoesNotOffer()=runBlocking {
        fixture(effect=PermEffect.ASK){e->coroutineScope {
            val task=async {e.execute()};val request=withTimeout(5000){e.ctx.approvals.pending.filterNotNull().first()}
            assertEquals("shell_escape",request.action)
            e.ctx.approvals.resolve(ApprovalResolution.DENY)
            assertTrue(task.await().rejected);assertEquals(0,e.offers.get())
        }}
    }
    @Test(timeout=60000) fun returnedErrorAfterUncertainOfferRemainsUnresolvedNotProviderReady()=runBlocking {
        fixture(sinkAccept=false){e->
            try {e.execute();fail("Uncertain input cannot become a normal tool result")}
            catch(expected:ToolCallHistoryJournal.StdinOutcomeUncertainException){}
            assertEquals(1,e.offers.get());assertNull(e.call.checkpointInterrupted());assertNull(e.call.settledResult)
            assertEquals(ToolCallHistoryJournal.State.EXECUTING,e.journal.state(e.pending.locator))
            assertTrue(e.root.resolve("journal/${e.pending.locator.id}.result").readText().contains("ERROR"))
            try {e.journal.invokeStdin(e.pending,e.prepared,e.ctx,{},{},{},{ });fail("Input replayed")}
            catch(expected:IllegalArgumentException){}
            assertEquals(1,e.offers.get())
        }
    }
    @Test(timeout=60000) fun stopAfterQueueAcceptanceDoesNotKillCloseOrResendProcess()=runBlocking {
        fixture(wait=true,progress={throw CancellationException("stop waiting")}){e->
            try {e.execute();fail("Stop expected")}catch(expected:CancellationException){}
            assertEquals(1,e.offers.get());assertNull(e.call.checkpointInterrupted())
            assertEquals(ToolCallHistoryJournal.State.EXECUTING,e.journal.state(e.pending.locator))
            assertEquals(BackgroundCommandManager.State.RUNNING,e.manager.snapshot(e.command)?.state)
            val metadata=e.root.resolve("journal/${e.pending.locator.id}.json").readText()
            assertTrue(metadata.contains("\"stdinOfferAccepted\":true"));assertTrue(metadata.contains("\"stdinEofAttempted\":false"))
        }
    }
    @Test(timeout=60000) fun successfulReturnCapturedBeforeDispatcherCancellationIsReplayableWithoutResend()=runBlocking {
        fixture {e->
            var execution:ToolCallHistoryJournal.Execution?=null
            var completion:ToolCallHistoryJournal.StdinCompletion?=null
            lateinit var job:Job
            job=launch(start=CoroutineStart.LAZY) {
                withContext(Dispatchers.IO) {
                    e.journal.invokeStdin(e.pending,e.prepared,e.ctx,{},{},started={execution=it},completed={
                        completion=it;job.cancel(CancellationException("after actual return"))
                    })
                }
            }
            job.start();job.join()
            val exact=checkNotNull(completion)
            val result=e.journal.checkpointStdinCompletion(checkNotNull(execution),exact)
            assertEquals(exact.output,result.getValue("content").jsonPrimitive.content)
            assertEquals(ToolCallHistoryJournal.State.SETTLED,e.journal.state(e.pending.locator))
            assertEquals(1,e.offers.get())
            try {e.journal.invokeStdin(e.pending,e.prepared,e.ctx,{},{},{},{ });fail("Completed input resent")}
            catch(expected:IllegalArgumentException){}
            assertEquals(1,e.offers.get())
        }
    }
    private suspend fun verifyKnownRejection(reason:String) {
        fixture(rejection=reason){e->
            val result=e.execute()
            assertTrue(result.output,result.output.startsWith("ERROR"))
            assertEquals(result.output,e.call.settledResult!!.getValue("content").jsonPrimitive.content)
            assertEquals(ToolCallHistoryJournal.State.SETTLED,e.journal.state(e.pending.locator))
            assertEquals(0,e.offers.get())
            val metadata=e.root.resolve("journal/${e.pending.locator.id}.json").readText()
            assertTrue(metadata,metadata.contains("\"stdinOfferKnownRejected\":true"))
            assertTrue(metadata,metadata.contains("\"stdinOfferRejection\":\"$reason\""))
            assertTrue(metadata,metadata.contains("\"stdinOfferAccepted\":false"))
            assertTrue(metadata,metadata.contains("\"stdinEofAttempted\":false"))
            assertEquals(result.output,e.call.checkpointInterrupted()!!.getValue("content").jsonPrimitive.content)
        }
    }
    @Test(timeout=60000) fun closedBufferIsKnownNoAdmissionAndSettlesExactError()=runBlocking {verifyKnownRejection("CLOSED")}
    @Test(timeout=60000) fun fullPipeQueueIsKnownNoAdmissionAndSettlesExactError()=runBlocking {verifyKnownRejection("PIPE_QUEUE_FULL")}

}
