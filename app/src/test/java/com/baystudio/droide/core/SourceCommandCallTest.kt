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
class SourceCommandCallTest {
    private class Env(val root:java.io.File,val ctx:AgentTools.Ctx,val manager:BackgroundCommandManager,
        val store:OwnedResponseContentStore,val journal:ToolCallHistoryJournal,val starts:AtomicInteger) {
        var next=0
        data class Call(val call:SourceCommandCall,val pending:ToolCallHistoryJournal.Pending,
            val prepared:StreamedCommandArgumentExtractor.Prepared,val owner:OwnedResponseContentStore.Owner)
        fun call(name:String="run_command",yield:Int=1000,extra:String=""):Call {
            val owner=OwnedResponseContentStore.Owner("session","run","call-${++next}");val session=store.authorize(owner)
            val key=if(name=="exec_command")"cmd" else "command"
            val raw="{\"$key\":\": #"+"\\u0078".repeat(23000)+"\\nprintf marker\",\"yield_time_ms\":$yield$extra}"
            val content=session.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use {it.append(raw);it.sealCompleted {stream->StreamedJsonValidator.requireObject(stream)}.content}
            val prepared=StreamedCommandArgumentExtractor.extract(session,content.handle,owner.callId,name)
            val pending=journal.recordCompletedCommand(owner,name,prepared.originalArguments)
            return Call(SourceCommandCall(PreparedToolInput.SourceCommand(prepared,prepared.originalArguments,session),journal,pending),pending,prepared,owner)
        }
        suspend fun execute(call:Call):SourceCallLifecycle.Outcome {
            val authority=HookInvocationAuthority(AgentMode.BUILD,ctx.provenance,ctx.permissionScope,{true})
            val hooks=AgentHookLifecycleBridge(HookManager(ctx.workDir,ctx.terminal,ctx.perms,ctx.approvals),{"session"},{AgentMode.BUILD})
            return call.call.execute(ctx,hooks,authority,call.owner,1,{})
        }
    }
    private suspend fun fixture(outcome:String="exit",effect:PermEffect=PermEffect.ALLOW,progressFailure:Boolean=false,reconcileFailure:Boolean=false,managerAvailable:Boolean=true,body:suspend(Env)->Unit) {
        ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication());ApplicationWorkAdmission.reopenFromUser()
        val root=Files.createTempDirectory("source-command-call-").toFile();val workspace=root.resolve("project").apply{mkdir()}
        val starts=AtomicInteger()
        val terminal=object:ITerminalSession by AgentWorkflowTerminal() {
            override val supportsCommandInput=false
            override suspend fun execStreaming(cmd:String,timeoutMs:Long,onOutput:(String)->Unit):ExecResult {
                starts.incrementAndGet();onOutput("ran once")
                return when(outcome){"running"->awaitCancellation();"failure"->error("native outcome unknown");"negative"->ExecResult(-1,"backend unavailable",false);"exit1"->ExecResult(1,"ordinary test failed",false);"timeoutKnown"->ExecResult(124,"completed timeout",true);else->ExecResult(0,"ran once",false)}
            }
        }
        val manager=BackgroundCommandManager(terminal);val files=FileRepository(workspace)
        val lsp=object:LspManager {
            override val diagnostics=MutableStateFlow<Map<String,List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath:String)=""
            override suspend fun lsp(op:String,path:String,line:Int,col:Int)=""
        }
        val ctx=AgentTools.Ctx(files,terminal,GitManager(workspace),ApprovalManager(),QuestionManager(),EditHistory(files),
            PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW),PermRule("shell","*",effect))),AgentMode.BUILD,LlmClient(),
            AgentConfig(baseUrl="",apiKey="",model="unused"),workspace,TodoManager(),"session",lsp,backgroundCommands=if(managerAvailable)manager else null,
            documentAuthority=if(reconcileFailure)object:WorkspaceDocumentAuthority {
                override suspend fun snapshot(path:String):WorkspaceDocumentSnapshot?=null
                override suspend fun snapshots()=emptyList<WorkspaceDocumentSnapshot>()
                override suspend fun reconcilePersistedWorkspace():WorkspacePersistedReconciliation=error("Reconciliation failed after returned process outcome")
            }else null,
            activeToolNames=setOf("run_command","bash","exec_command"),onProgress={if(progressFailure && it.outputDelta.isNotEmpty())error("Progress transport failed after activation")})
        val store=OwnedResponseContentStore(root.resolve("owned").toPath(),OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0))
        try {body(Env(root,ctx,manager,store,ToolCallHistoryJournal(root.resolve("journal"),store),starts))}
        finally {ctx.webResearch.close();manager.closeAndJoin();PathSecurity.deleteTreeNoFollow(root)}
    }
    @Test(timeout=60000) fun allWireNamesUseExistingCommandConsumerAndExactReceipt()=runBlocking {
        fixture {e->for(name in listOf("run_command","bash","exec_command")){
            val call=e.call(name);val result=e.execute(call)
            assertFalse(result.output,result.rejected)
            assertEquals(result.output,call.call.settledResult!!.getValue("content").jsonPrimitive.content)
            assertEquals(ToolCallHistoryJournal.State.SETTLED,e.journal.state(call.pending.locator))
            val record=e.root.resolve("journal/${call.pending.locator.id}.json").readText()
            assertTrue(record,record.contains("\"commandActivation\":\"ACTIVATED\""));assertTrue(record.contains("\"commandJob\":\"bg-"))
        };assertEquals(3,e.starts.get())}
    }
    @Test(timeout=60000) fun deniedPermissionHasNoActivationAndSettlesWithoutJob()=runBlocking {fixture(effect=PermEffect.DENY){e->
        val call=e.call();assertTrue(e.execute(call).rejected);assertEquals(0,e.starts.get())
        assertEquals(ToolCallHistoryJournal.State.SETTLED,e.journal.state(call.pending.locator))
    }}
    @Test(timeout=60000) fun askDenialPreservesReviewAndDoesNotActivate()=runBlocking {fixture(effect=PermEffect.ASK){e->coroutineScope {
        val call=e.call();val task=async {e.execute(call)}
        val request=withTimeout(5000){e.ctx.approvals.pending.filterNotNull().first()}
        assertEquals("shell",request.action);e.ctx.approvals.resolve(ApprovalResolution.DENY)
        assertTrue(task.await().rejected);assertEquals(0,e.starts.get())
    }}}
    @Test(timeout=60000) fun runningAdmissionSettlesButSameInvocationCannotLaunchAgain()=runBlocking {fixture("running"){e->
        val call=e.call(yield=10);assertFalse(e.execute(call).rejected)
        withTimeout(5000){while(e.starts.get()!=1)delay(5)}
        assertEquals(ToolCallHistoryJournal.State.SETTLED,e.journal.state(call.pending.locator))
        assertTrue(runCatching {e.journal.invokeCommand(call.pending,call.prepared,e.ctx,{},{},started={},completed={})}.isFailure)
        assertEquals(1,e.starts.get())
    }}
    @Test(timeout=60000) fun failedOrSentinelExitNeverBecomesNoEffectProof()=runBlocking {
        for(kind in listOf("failure","negative"))fixture(kind){e->
            val call=e.call()
            try{e.execute(call);fail("Unknown command outcome cannot settle normally")}catch(_:ToolCallHistoryJournal.CommandOutcomeUncertainException){}
            assertNull(call.call.checkpointInterrupted());assertEquals(1,e.starts.get())
            assertEquals(ToolCallHistoryJournal.State.EXECUTING,e.journal.state(call.pending.locator))
            assertTrue(e.root.resolve("journal/${call.pending.locator.id}.result").exists())
        }
    }
    @Test(timeout=60000) fun returnedCompletionSurvivesDispatcherCancellationWithoutRelaunch()=runBlocking {fixture {e->
        val call=e.call();var execution:ToolCallHistoryJournal.Execution?=null;var completion:ToolCallHistoryJournal.CommandCompletion?=null
        lateinit var job:Job
        job=launch(start=CoroutineStart.LAZY){withContext(Dispatchers.IO){
            e.journal.invokeCommand(call.pending,call.prepared,e.ctx,{},{},started={execution=it},completed={completion=it;job.cancel()})
        }}
        job.start();job.join();val receipt=checkNotNull(completion)
        assertEquals(receipt.output,e.journal.checkpointCommandCompletion(checkNotNull(execution),receipt).getValue("content").jsonPrimitive.content)
        assertEquals(1,e.starts.get());assertTrue(runCatching {e.journal.beginExecution(call.pending,{})}.isFailure)
    }}
    @Test(timeout=60000) fun ordinaryNonzeroExitAndKnownTimeoutSettleWithoutChangingLegacyRetryPolicy()=runBlocking {for(kind in listOf("exit1","timeoutKnown"))fixture(kind){e->
        repeat(2){
            val call=e.call();val result=e.execute(call)
            assertTrue(result.output,result.rejected)
            assertEquals(ToolCallHistoryJournal.State.SETTLED,e.journal.state(call.pending.locator))
            assertEquals(result.output,call.call.settledResult!!.getValue("content").jsonPrimitive.content)
        }
        assertEquals(2,e.starts.get())
    }}
    @Test(timeout=60000) fun forgedPreparationAndForeignJournalNeverActivate()=runBlocking {fixture {e->
        val original=e.call()
        val forged=StreamedCommandArgumentExtractor.Prepared(original.prepared.callId,original.prepared.toolName,
            original.prepared.originalArguments,"0".repeat(64))
        val failed=runCatching {e.journal.invokeCommand(original.pending,forged,e.ctx,{},{},started={fail("No execution")},completed={fail("No completion")})}
        assertTrue(failed.isFailure);assertEquals(0,e.starts.get())
        assertEquals(ToolCallHistoryJournal.State.RECORDED,e.journal.state(original.pending.locator))
        val other=ToolCallHistoryJournal(e.root.resolve("other-journal"),e.store)
        assertTrue(runCatching {other.invokeCommand(original.pending,original.prepared,e.ctx,{},{},started={},completed={})}.isFailure)
        assertEquals(0,e.starts.get())
    }}
    @Test(timeout=60000) fun closedUnsupportedAndSaturatedManagersAreProvenPreActivationRefusals()=runBlocking {
        for(kind in listOf("closed","stdin","tty","capacity"))fixture(if(kind=="capacity")"running" else "exit"){e->
            when(kind){
                "closed"->e.manager.closeAndJoin()
                "capacity"->{repeat(BackgroundCommandManager.MAX_RUNNING_JOBS){e.manager.start("capacity-$it")};withTimeout(5000){while(e.starts.get()!=BackgroundCommandManager.MAX_RUNNING_JOBS)delay(1)}}
            }
            val before=e.starts.get()
            val call=when(kind){
                "stdin"->e.call("exec_command",extra=",\"keep_stdin_open\":true")
                "tty"->e.call(extra=",\"requires_tty\":true")
                else->e.call()
            }
            val result=e.execute(call)
            assertTrue("$kind: ${result.output}",result.rejected)
            assertEquals(before,e.starts.get())
            assertEquals(ToolCallHistoryJournal.State.SETTLED,e.journal.state(call.pending.locator))
            assertEquals(result.output,call.call.settledResult!!.getValue("content").jsonPrimitive.content)
            val record=e.root.resolve("journal/${call.pending.locator.id}.json").readText()
            assertTrue("$kind: $record",Json.parseToJsonElement(record).jsonObject["commandActivation"]?.jsonPrimitive?.content in setOf("NO_COMMAND_ACTIVATION","NOT_REQUESTED"))
            assertFalse(record.contains("\"commandJob\":\"bg-"))
        }
    }
    @Test(timeout=60000) fun progressFailureAfterActivationCannotMasqueradeAsNormalRunningAdmission()=runBlocking {
        fixture("running",progressFailure=true){e->
            val call=e.call(yield=1000)
            try {e.execute(call);fail("Activated command without normal admission return stays unresolved")}
            catch(_:ToolCallHistoryJournal.CommandOutcomeUncertainException){}
            assertEquals(1,e.starts.get())
            assertEquals(ToolCallHistoryJournal.State.EXECUTING,e.journal.state(call.pending.locator))
            assertNull(call.call.checkpointInterrupted())
            val record=e.root.resolve("journal/${call.pending.locator.id}.json").readText()
            assertTrue(record,record.contains("\"commandReturnHash\":null"))
            assertTrue(record,record.contains("\"commandDisposition\":\"RUNNING\""))
        }
    }
    @Test(timeout=60000) fun knownTerminalOutcomeAndPostRunErrorRemainDistinctFacts()=runBlocking {
        fixture("exit1",reconcileFailure=true){e->
            val call=e.call();val result=e.execute(call)
            assertTrue(result.output,result.rejected)
            assertTrue(result.output,result.output.contains("Reconciliation failed"))
            assertEquals(ToolCallHistoryJournal.State.SETTLED,e.journal.state(call.pending.locator))
            val record=Json.parseToJsonElement(e.root.resolve("journal/${call.pending.locator.id}.json").readText()).jsonObject
            assertEquals("FAILED",record.getValue("commandDisposition").jsonPrimitive.content)
            assertEquals(1,record.getValue("commandExit").jsonPrimitive.int)
            assertEquals(JsonNull,record["commandReturnHash"])
            assertEquals(result.output,call.call.settledResult!!.getValue("content").jsonPrimitive.content)
        }
    }
    @Test(timeout=60000) fun missingManagerNeverFallsBackToAnotherExecutor()=runBlocking {
        fixture(managerAvailable=false){e->
            val call=e.call();val result=e.execute(call)
            assertTrue(result.rejected);assertEquals(0,e.starts.get())
            assertEquals(ToolCallHistoryJournal.State.SETTLED,e.journal.state(call.pending.locator))
            assertTrue(result.output,result.output.contains("no alternate backend"))
        }
    }

}
