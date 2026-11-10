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
class AgentServiceSourceStdinTest {
    @org.junit.Before fun initialize() {
        ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }
    private val docs=object:WorkspaceDocumentAuthority {
        override suspend fun snapshot(path:String):WorkspaceDocumentSnapshot?=null
        override suspend fun snapshots():List<WorkspaceDocumentSnapshot> = emptyList()
        override suspend fun observeRecovery(path:String)=WorkspaceRecoveryEditorObservation(emptyList(),emptySet(),{})
        override suspend fun commitDiskOnly(path:String,action:()->Unit)=action()
    }
    private data class Call(val id:String,val name:String,val args:String)
    private fun write(id:String,path:String,text:String)=Call(id,"write_file",buildJsonObject { put("path",path);put("content",text) }.toString())
    private fun read(id:String,path:String,padding:String?=null)=Call(id,"read_file",buildJsonObject { put("path",path);padding?.let { put("padding",it) } }.toString())
    private fun response(f:ProviderStreamFixture,calls:List<Call>)=buildString {
        calls.forEachIndexed { i,call -> call.args.chunked(12000).forEachIndexed { fragment,part ->
            append(f.event(buildJsonObject { put("choices",buildJsonArray { add(buildJsonObject {
                put("delta",buildJsonObject { put("tool_calls",buildJsonArray { add(buildJsonObject {
                    put("index",i);if(fragment==0){put("id",call.id);put("type","function")}
                    put("function",buildJsonObject { if(fragment==0)put("name",call.name);put("arguments",part) })
                }) }) })
            }) }) }.toString()))
        } }
        append(f.event("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""));append("data: [DONE]\n\n")
    }
    private fun done(f:ProviderStreamFixture)=f.event("""{"choices":[{"delta":{"content":"Completed once"},"finish_reason":"stop"}]}""")+"data: [DONE]\n\n"
    private fun agent(root:java.io.File,files:FileRepository=FileRepository(root),effect:PermEffect=PermEffect.ALLOW)=
        AgentService(files,AgentWorkflowTerminal(),GitManager(root),perms=PermissionEngine(listOf(PermRule("*","*",effect))),
            documentAuthority=docs,maxSubagentDepth=0)

    private suspend fun scenario(accepted:Boolean,rejection:String?=null) {
        val root=Files.createTempDirectory("source-stdin-http-").toFile()
        val offers=java.util.concurrent.atomic.AtomicInteger()
        val ready=CompletableDeferred<Unit>()
        val terminal=object:ITerminalSession by AgentWorkflowTerminal(),InteractivePtyCommandSession {
            override val supportsInteractivePty=true
            override suspend fun execInteractivePty(command:String,timeoutMs:Long,input:ProcessInputBuffer?,onOutput:(String)->Unit):ExecResult {
                val buffer=requireNotNull(input)
                when(rejection) {
                    "CLOSED" -> buffer.close()
                    "PIPE_QUEUE_FULL" -> repeat(8){buffer.offer("queued-$it")}
                    else -> buffer.bindPty({offers.incrementAndGet();accepted},{true})
                }
                ready.complete(Unit);awaitCancellation()
            }
        }
        try {withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) {f->runBlocking {
            ModelContextRegistry.remember(f.provider.id,f.model,1_000_000)
            var original=""
            f.response={index,_,_->when(index) {
                1->response(f,listOf(Call("start","exec_command","""{"cmd":"fixture","requires_tty":true,"keep_stdin_open":true,"yield_time_ms":10}""")))
                2->{
                    runBlocking {withTimeout(5000){ready.await()}}
                    val tool=f.requests.last().getValue("messages").jsonArray.map {it.jsonObject}.last {it["role"]==JsonPrimitive("tool")}
                    val output=tool.getValue("content").jsonPrimitive.content
                    val id=checkNotNull(Regex("COMMAND_SESSION id=([^ ]+)").find(output)).groupValues[1]
                    original="{\"session_id\":\"$id\",\"chars\":\""+"\\u0078".repeat(30000)+"\",\"yield_time_ms\":0,\"close_stdin\":${rejection!=null}}"
                    response(f,listOf(Call("stdin","write_stdin",original),write("later","later.txt","later".repeat(30000))))
                }
                else->done(f)
            }}
            val a=AgentService(FileRepository(root),terminal,GitManager(root),perms=PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW))),documentAuthority=docs,maxSubagentDepth=0)
            try {
                withTimeout(60000){a.chat("Start and send one input",f.config,maxTurns=3)}
                assertEquals(a.messages.value.toString(),if(rejection==null)1 else 0,offers.get())
                val saved=checkNotNull(a.sessions.get(checkNotNull(a.currentSessionId)))
                val native=checkNotNull(saved.typedChat).exact.filterIsInstance<TypedChatEntry.OwnedAssistant>().last()
                val journal=a.sessions.typedChatResources().journal
                val locator=checkNotNull(journal.locate(OwnedResponseContentStore.Owner(saved.id,native.runId,"stdin"),"write_stdin"))
                if(accepted || rejection!=null) {
                    assertTrue(a.messages.value.toString(),root.resolve("later.txt").exists())
                    assertEquals(ToolCallHistoryJournal.State.SETTLED,journal.state(locator))
                    if(rejection!=null) {
                        val sourceResult=java.io.ByteArrayOutputStream().also { journal.writeResult(locator,it) }.toString("UTF-8").let { Json.parseToJsonElement(it).jsonObject.getValue("content").jsonPrimitive.content }
                        assertTrue(sourceResult,sourceResult.startsWith("ERROR"))
                    }
                    val all=f.requests.last().getValue("messages").jsonArray.map {it.jsonObject}
                    val call=all.flatMap { (it["tool_calls"] as? JsonArray)?.map {v->v.jsonObject} ?: emptyList() }.first {it["id"]==JsonPrimitive("stdin")}
                    assertEquals(original,call.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content)
                    a.loadSession(saved);withTimeout(30000){a.chat("Observe only",f.config,maxTurns=1)}
                    assertEquals(if(rejection==null)1 else 0,offers.get())
                    val refreshed=checkNotNull(a.sessions.get(saved.id))
                    val forkIndex=refreshed.messages.indexOfFirst {it.streamPart?.toolCallId=="later"}
                    assertTrue(forkIndex>=0)
                    val fork=checkNotNull(a.sessions.fork(saved.id,forkIndex))
                    val forkNative=checkNotNull(fork.typedChat).exact.filterIsInstance<TypedChatEntry.OwnedAssistant>().last()
                    assertEquals(ToolCallHistoryJournal.State.SETTLED,journal.state(checkNotNull(journal.locate(
                        OwnedResponseContentStore.Owner(fork.id,forkNative.runId,"stdin"),"write_stdin"))))
                    a.loadSession(fork);withTimeout(30000){a.chat("No resending in branch",f.config,maxTurns=1)}
                    assertEquals(if(rejection==null)1 else 0,offers.get())
                } else {
                    assertFalse("Later mutation must not run after uncertain stdin",root.resolve("later.txt").exists())
                    assertEquals(ToolCallHistoryJournal.State.EXECUTING,journal.state(locator))
                    assertEquals("No provider retry after uncertain input",2,f.requests.size)
                    assertNull(TypedChatForkBoundary.completedEnd(checkNotNull(saved.typedChat).exact))
                    a.loadSession(saved);withTimeout(30000){a.chat("Continue safely",f.config,maxTurns=1)}
                    assertEquals("Unresolved source may not resume provider",2,f.requests.size)
                    val blocked=checkNotNull(a.sessions.get(saved.id))
                    assertEquals(checkNotNull(saved.typedChat).exact,checkNotNull(blocked.typedChat).exact)
                    assertTrue(blocked.messages.any {it.role=="user" && it.content=="Continue safely" && it.forkHistoryEnd==null})
                    val prior=blocked.messages.indexOfFirst {it.streamPart?.toolCallId=="start"}
                    assertTrue(prior>=0)
                    val safeFork=checkNotNull(a.sessions.fork(blocked.id,prior))
                    assertTrue(checkNotNull(safeFork.typedChat).exact.filterIsInstance<TypedChatEntry.OwnedAssistant>().all {"stdin" !in it.callIds})
                    assertEquals(if(rejection==null)1 else 0,offers.get())
                }
            } finally {a.shutdown()}
        }}} finally {PathSecurity.deleteTreeNoFollow(root)}
    }
    @Test(timeout=90000) fun largeEscapedInputKeepsNativeReplayAndDoesNotResendOnRestore()=runBlocking {scenario(true)}
    @Test(timeout=90000) fun uncertainInputStopsLaterMixedWriteAndProviderContinuation()=runBlocking {scenario(false)}
    @Test(timeout=90000) fun knownClosedInputAllowsLaterMixedWriteAndHistoryContinuation()=runBlocking {scenario(false,"CLOSED")}
    @Test(timeout=90000) fun knownFullPipeQueueAllowsLaterMixedWriteAndHistoryContinuation()=runBlocking {scenario(false,"PIPE_QUEUE_FULL")}

}
