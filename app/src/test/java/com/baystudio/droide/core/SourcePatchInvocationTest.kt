package com.baystudio.droide.core

import java.nio.file.Files
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
class SourcePatchInvocationTest {
    private suspend fun fixture(block:suspend (AgentTools.Ctx,ToolCallHistoryJournal,ToolCallHistoryJournal.Pending,StreamedPatchArgumentExtractor.Prepared)->Unit,
        progress:(AgentToolProgress)->Unit={} ) {
        ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication());ApplicationWorkAdmission.reopenFromUser()
        val parent=Files.createTempDirectory("patch-return-").toFile();val workspace=parent.resolve("project").apply{mkdir()}
        val files=FileRepository(workspace);val terminal=AgentWorkflowTerminal()
        val lsp=object:LspManager {
            override val diagnostics=MutableStateFlow<Map<String,List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath:String):String=""
            override suspend fun lsp(op:String,path:String,line:Int,col:Int):String=""
        }
        val ctx=AgentTools.Ctx(files,terminal,GitManager(workspace),ApprovalManager(),QuestionManager(),EditHistory(files),
            PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW))),AgentMode.BUILD,LlmClient(),
            AgentConfig(baseUrl="",apiKey="",model="unused"),workspace,TodoManager(),"session",lsp,
            activeToolNames=setOf("apply_patch"),onProgress=progress)
        try {
            val owner=OwnedResponseContentStore.Owner("session","run","call")
            val store=OwnedResponseContentStore(parent.resolve("owned").toPath(),object:OwnedResponseContentStore.DiskAdmission {
                override fun reserve(bytes:Long):AutoCloseable=AutoCloseable {}
            })
            val session=store.authorize(owner)
            val raw=session.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use {
                it.append(buildJsonObject {put("patchText","*** Begin Patch\n*** Add File: new.txt\n+"+"x".repeat(170000)+"\n*** End Patch")}.toString())
                it.sealCompleted {input->StreamedJsonValidator.requireObject(input)}.content
            }
            val prepared=StreamedPatchArgumentExtractor.extract(session,raw.handle,"call")
            val journal=ToolCallHistoryJournal(parent.resolve("journal"),store)
            block(ctx,journal,journal.recordCompletedPatch(owner,prepared.originalArguments),prepared)
        } finally {ctx.webResearch.close();PathSecurity.deleteTreeNoFollow(parent)}
    }
    @Test(timeout=60000) fun returnCapturedBeforeDispatcherCancellationSettlesExactlyAndCannotRepeat()=runBlocking {
        fixture({ctx,journal,pending,prepared->
            val started=journal.beginPatchInvocation(pending,{})
            try {journal.invokePatch(ToolCallHistoryJournal.Execution(journal,pending.locator),prepared,ctx,{},{ });fail("Fabricated execution admitted")}
            catch(expected:IllegalArgumentException){}
            val original=prepared.originalArguments
            val wrong=StreamedFileArgumentExtractor.Source(original.handle,original.bytes,original.utf16Units,"0".repeat(64),original.kind)
            try {journal.invokePatch(started,StreamedPatchArgumentExtractor.Prepared(prepared.callId,wrong),ctx,{},{ });fail("Wrong argument hash admitted")}
            catch(expected:IllegalArgumentException){}
            try {journal.invokePatch(started,StreamedPatchArgumentExtractor.Prepared("other-call",original),ctx,{},{ });fail("Wrong call owner admitted")}
            catch(expected:IllegalArgumentException){}
            var captured:ToolCallHistoryJournal.PatchCompletion?=null
            lateinit var job:Job
            job=launch(start=CoroutineStart.LAZY) {
                withContext(Dispatchers.IO) {
                    journal.invokePatch(started,prepared,ctx,{}) { captured=it;job.cancel(CancellationException("return boundary")) }
                }
            }
            job.start();job.join()
            val receipt=checkNotNull(captured)
            assertTrue(ctx.workDir.resolve("new.txt").exists())
            val fake=buildJsonObject {put("role","tool");put("tool_call_id","call");put("content","forged")}
            try {journal.settle(started,fake);fail("Generic settlement admitted executing patch")} catch(expected:IllegalArgumentException){}
            val result=journal.checkpointPatchCompletion(started,receipt)
            assertEquals(receipt.output,result.getValue("content").jsonPrimitive.content)
            assertEquals(ToolCallHistoryJournal.State.SETTLED,journal.state(pending.locator))
            try {journal.invokePatch(started,prepared,ctx,{},{ });fail("Repeated invocation admitted")} catch(expected:IllegalArgumentException){}
            try {journal.settle(started,result);fail("Generic settlement admitted patch")} catch(expected:IllegalArgumentException){}
        })
    }
    @Test(timeout=60000) fun thrownCancellationAfterMutationRemainsUnknownAndCannotRepeat()=runBlocking {
        fixture({ctx,journal,pending,prepared->
            val started=journal.beginPatchInvocation(pending,{})
            try {journal.invokePatch(ToolCallHistoryJournal.Execution(journal,pending.locator),prepared,ctx,{},{ });fail("Fabricated execution admitted")}
            catch(expected:IllegalArgumentException){}
            var captured:ToolCallHistoryJournal.PatchCompletion?=null
            try {journal.invokePatch(started,prepared,ctx,{}) {captured=it};fail("Cancellation expected")}
            catch(expected:CancellationException){}
            assertNull(captured)
            assertTrue("Known workspace change must not be reported as no execution",ctx.workDir.resolve("new.txt").exists())
            assertEquals(ToolCallHistoryJournal.State.EXECUTING,journal.state(pending.locator))
            val reopenedStore=OwnedResponseContentStore(ctx.workDir.parentFile.resolve("owned").toPath(),object:OwnedResponseContentStore.DiskAdmission {
                override fun reserve(bytes:Long):AutoCloseable=AutoCloseable {}
            })
            val reopened=ToolCallHistoryJournal(ctx.workDir.parentFile.resolve("journal"),reopenedStore)
            assertEquals(ToolCallHistoryJournal.State.EXECUTING,reopened.state(pending.locator))
            try {reopened.beginPatchInvocation(ToolCallHistoryJournal.Pending(reopened,pending.locator),{});fail("Restart minted invocation")}
            catch(expected:IllegalArgumentException){}
            try {reopened.invokePatch(started,prepared,ctx,{},{ });fail("Foreign journal accepted execution")}
            catch(expected:IllegalArgumentException){}
            try {journal.checkpointNoExecution(pending);fail("Unknown invocation settled no-effect")} catch(expected:IllegalArgumentException){}
            try {journal.invokePatch(started,prepared,ctx,{},{ });fail("Unknown invocation repeated")}
            catch(expected:IllegalStateException){}
        },progress={throw CancellationException("after publication progress")})
    }
    @Test(timeout=60000) fun returnedSuccessAfterMutationSurvivesThrowingCompletionCallback()=runBlocking {
        fixture({ctx,journal,pending,prepared->
            val started=journal.beginPatchInvocation(pending,{})
            var captured:ToolCallHistoryJournal.PatchCompletion?=null
            try {
                journal.invokePatch(started,prepared,ctx,{}) {captured=it;throw IllegalStateException("callback after capture")}
                fail("Callback failure expected")
            } catch(expected:IllegalStateException) {assertEquals("callback after capture",expected.message)}
            val receipt=checkNotNull(captured)
            assertFalse(receipt.output,receipt.output.startsWith("ERROR"))
            assertTrue(ctx.workDir.resolve("new.txt").exists())
            val result=journal.checkpointPatchCompletion(started,receipt)
            assertEquals(receipt.output,result.getValue("content").jsonPrimitive.content)
            assertEquals(ToolCallHistoryJournal.State.SETTLED,journal.state(pending.locator))
        },progress={throw IllegalArgumentException("progress failure after publication")})
    }
    @Test(timeout=60000) fun actualReturnedErrorIsRecordedVerbatimWithoutInventingNoEffect()=runBlocking {
        fixture({ctx,journal,pending,prepared->
            ctx.workDir.resolve("new.txt").writeText("existing user data")
            val started=journal.beginPatchInvocation(pending,{})
            var captured:ToolCallHistoryJournal.PatchCompletion?=null
            val receipt=journal.invokePatch(started,prepared,ctx,{}) {captured=it}
            assertSame(receipt,captured)
            assertTrue(receipt.output,receipt.output.startsWith("ERROR"))
            val result=journal.checkpointPatchCompletion(started,receipt)
            assertEquals(receipt.output,result.getValue("content").jsonPrimitive.content)
            assertEquals("existing user data",ctx.workDir.resolve("new.txt").readText())
            assertEquals(ToolCallHistoryJournal.State.SETTLED,journal.state(pending.locator))
        })
    }
}
