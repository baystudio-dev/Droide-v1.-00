package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real HTTP/provider/service/manager integration, recording terminal only: not Android native proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class AgentServiceSourceCommandTest {
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
    private class RecordingTerminal(val blocking:Boolean=true,val exit:Int=0):ITerminalSession by AgentWorkflowTerminal() {
        val commands=java.util.Collections.synchronizedList(mutableListOf<String>())
        override suspend fun execStreaming(cmd:String,timeoutMs:Long,onOutput:(String)->Unit):ExecResult {
            commands.add(cmd)
            if(blocking)awaitCancellation()
            return ExecResult(exit,"recorded",false)
        }
        suspend fun awaitCount(count:Int)=withTimeout(10000){while(commands.size<count)delay(5)}
    }
    private data class Call(val id:String,val name:String,val args:String)
    private val command=": #"+"x".repeat(23000)+"\nprintf marker"
    private fun escaped(name:String,extra:String=",\"yield_time_ms\":0"):String {
        val key=if(name=="exec_command")"cmd" else "command"
        return "{\"$key\":\": #"+"\\u0078".repeat(23000)+"\\nprintf marker\"$extra}"
    }
    private fun literal(name:String,cmd:String=command,yield:Long=0)=buildJsonObject {
        put(if(name=="exec_command")"cmd" else "command",cmd);put("yield_time_ms",yield)
    }.toString()
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
    private fun done(f:ProviderStreamFixture)=f.event("""{"choices":[{"delta":{"content":"Observed without restarting"},"finish_reason":"stop"}]}""")+"data: [DONE]\n\n"
    private fun agent(root:File,terminal:RecordingTerminal,effect:PermEffect=PermEffect.ALLOW)=
        AgentService(FileRepository(root),terminal,GitManager(root),perms=PermissionEngine(listOf(
            PermRule("*","*",PermEffect.ALLOW),PermRule("shell","*",effect))),documentAuthority=docs,maxSubagentDepth=0)
    private fun rawCall(request:JsonObject,id:String)=request.getValue("messages").jsonArray.map{it.jsonObject}
        .flatMap{(it["tool_calls"] as? JsonArray)?.map{v->v.jsonObject}?:emptyList()}.first{it["id"]==JsonPrimitive(id)}
        .getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content
    private fun result(request:JsonObject,id:String)=request.getValue("messages").jsonArray.map{it.jsonObject}
        .last{it["role"]==JsonPrimitive("tool") && it["tool_call_id"]==JsonPrimitive(id)}.getValue("content").jsonPrimitive.content

    @Test(timeout=180000) fun allNamesHugeEscapedWireLaunchOnceReplayExactAndForkWithoutLaunch()=runBlocking {
        for(name in listOf("run_command","bash","exec_command")) {
            val root=Files.createTempDirectory("source-command-http-").toFile();val terminal=RecordingTerminal()
            root.resolve("note.txt").writeText("ordinary read still works")
            try {withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS){f->runBlocking {
                ModelContextRegistry.remember(f.provider.id,f.model,1_000_000)
                val original=escaped(name);assertTrue(original.length>138000)
                f.response={index,_,_->when(index) {
                    1->response(f,listOf(Call("launch",name,original)))
                    2->{runBlocking{terminal.awaitCount(1)};response(f,listOf(Call("later","read_file","""{"path":"note.txt"}""")))}
                    else->done(f)
                }}
                val a=agent(root,terminal)
                try {
                    withTimeout(60000){a.chat("Execute the requested command once then read note.txt",f.config,maxTurns=4)}
                    assertEquals(a.messages.value.toString(),listOf(command),terminal.commands.toList())
                    assertEquals(original,rawCall(f.requests.last(),"launch"))
                    assertTrue(result(f.requests.last(),"later").contains("ordinary read still works"))
                    val saved=checkNotNull(a.sessions.get(checkNotNull(a.currentSessionId)))
                    val native=checkNotNull(saved.typedChat).exact.filterIsInstance<TypedChatEntry.OwnedAssistant>().first{ "launch" in it.callIds }
                    val journal=a.sessions.typedChatResources().journal
                    val locator=checkNotNull(journal.locate(OwnedResponseContentStore.Owner(saved.id,native.runId,"launch"),name))
                    assertEquals(ToolCallHistoryJournal.State.SETTLED,journal.state(locator))
                    a.loadSession(saved);withTimeout(30000){a.chat("Only describe the recorded result, do not rerun",f.config,maxTurns=1)}
                    assertEquals(original,rawCall(f.requests.last(),"launch"));assertEquals(1,terminal.commands.size)
                    val refreshed=checkNotNull(a.sessions.get(saved.id))
                    val index=refreshed.messages.indexOfFirst{it.streamPart?.toolCallId=="later"};assertTrue(index>=0)
                    val fork=checkNotNull(a.sessions.fork(saved.id,index))
                    a.loadSession(fork);withTimeout(30000){a.chat("Observe this branch without running commands",f.config,maxTurns=1)}
                    assertEquals(original,rawCall(f.requests.last(),"launch"));assertEquals(1,terminal.commands.size)
                } finally {a.shutdown()}
            }}} finally {PathSecurity.deleteTreeNoFollow(root)}
        }
    }

    @Test(timeout=90000) fun deniedOrCancelledApprovalNeverActivatesSourceCommand()=runBlocking {
        for(cancel in listOf(false,true)) {
            val root=Files.createTempDirectory("command-approval-http-").toFile();val terminal=RecordingTerminal()
            try {withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS){f->runBlocking {
                ModelContextRegistry.remember(f.provider.id,f.model,1_000_000)
                f.response={index,_,_->if(index==1)response(f,listOf(Call("launch","exec_command",escaped("exec_command"))))else done(f)}
                val a=agent(root,terminal,PermEffect.ASK)
                try {
                    val chat=async{a.chat("Run only after permission",f.config,maxTurns=3)}
                    val pending=withTimeout(30000){a.approvals.pending.filterNotNull().first()}
                    val review=checkNotNull(pending.inputReview)
                    val full=buildString{var p=0;while(true){val page=review.readPage(p++);append(page.text);if(!page.hasNext)break}}
                    assertEquals(command,full)
                    if(cancel){chat.cancel();withTimeout(30000){chat.join()}} else {
                        a.approvals.resolve(pending.id,ApprovalResolution.DENY);withTimeout(30000){chat.await()}
                    }
                    assertTrue(terminal.commands.isEmpty())
                    assertTrue(runCatching{review.readPage(0)}.isFailure)
                } finally {a.shutdown()}
            }}} finally {PathSecurity.deleteTreeNoFollow(root)}
        }
    }

    @Test(timeout=90000) fun ordinaryNonzeroCompletionAllowsNewCallAttempt()=runBlocking {
        val root=Files.createTempDirectory("command-nonzero-http-").toFile();val terminal=RecordingTerminal(blocking=false,exit=1)
        try {withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS){f->runBlocking {
            ModelContextRegistry.remember(f.provider.id,f.model,1_000_000)
            f.response={index,_,_->when(index){
                1->response(f,listOf(Call("first","run_command",escaped("run_command",",\"yield_time_ms\":1000"))))
                2->response(f,listOf(Call("again","bash",literal("bash",yield=1000))))
                else->done(f)
            }}
            val a=agent(root,terminal)
            try {withTimeout(60000){a.chat("Run once and retry the ordinary failed command",f.config,maxTurns=4)}
                assertEquals(listOf(command,command),terminal.commands.toList())
                assertFalse(result(f.requests.last(),"again").contains("already active"))
            } finally {a.shutdown()}
        }}} finally {PathSecurity.deleteTreeNoFollow(root)}
    }

    @Test(timeout=90000) fun policyDenialRejectsUnadvertisedSourceBeforeCommandActivation()=runBlocking {
        val root=Files.createTempDirectory("command-policy-deny-http-").toFile();val terminal=RecordingTerminal()
        try {withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS){f->runBlocking {
            ModelContextRegistry.remember(f.provider.id,f.model,1_000_000)
            f.response={index,_,_->if(index==1)response(f,listOf(Call("denied","run_command",escaped("run_command"))))else done(f)}
            val a=agent(root,terminal,PermEffect.DENY)
            try {
                withTimeout(60000){a.chat("Attempt the requested command under existing policy",f.config,maxTurns=3)}
                assertTrue(terminal.commands.isEmpty());assertNull(a.approvals.pending.value)
                // Policy filters the advertised catalog BEFORE source-call admission. No private
                // executable journal record should be invented merely to satisfy a denial fixture.
                val advertised=(f.requests.first()["tools"] as? JsonArray).orEmpty().mapNotNull {
                    it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                }
                assertFalse(advertised.toString(),"run_command" in advertised)
                val saved=checkNotNull(a.sessions.get(checkNotNull(a.currentSessionId)))
                assertNull(saved.typedChat)
            } finally {a.shutdown()}
        }}} finally {PathSecurity.deleteTreeNoFollow(root)}
    }

    @Test(timeout=90000) fun immediateUnknownLaunchStopsLaterBatchAndReloadNeverAutoExecutes()=runBlocking {
        val root=Files.createTempDirectory("command-unknown-http-").toFile()
        val terminal=RecordingTerminal(blocking=false,exit=-1)
        try {withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS){f->runBlocking {
            ModelContextRegistry.remember(f.provider.id,f.model,1_000_000)
            val original=escaped("run_command",",\"yield_time_ms\":1000")
            val later=buildJsonObject{put("path","must-not-exist.txt");put("content","later effect")}.toString()
            f.response={index,_,_->if(index==1)response(f,listOf(Call("uncertain","run_command",original),Call("later","write_file",later)))else done(f)}
            val a=agent(root,terminal)
            try {
                withTimeout(60000){a.chat("Run requested command then write the file only after its result",f.config,maxTurns=3)}
                assertEquals(listOf(command),terminal.commands.toList())
                assertFalse("No later effect after unknown current-call outcome",root.resolve("must-not-exist.txt").exists())
                assertEquals("No provider retry after uncertainty",1,f.requests.size)
                val saved=checkNotNull(a.sessions.get(checkNotNull(a.currentSessionId)))
                val checkpoint=checkNotNull(saved.typedChat)
                val native=checkpoint.exact.filterIsInstance<TypedChatEntry.OwnedAssistant>().first{"uncertain" in it.callIds}
                val resources=a.sessions.typedChatResources()
                val locator=checkNotNull(resources.journal.locate(OwnedResponseContentStore.Owner(saved.id,native.runId,"uncertain"),"run_command"))
                assertEquals(ToolCallHistoryJournal.State.EXECUTING,resources.journal.state(locator))
                // Recovery evidence can be inspected, but unresolved arguments must not gain
                // the settled replay capability used by normal continuation/fork.
                assertThrows(IllegalArgumentException::class.java){resources.journal.completedArguments(locator)}
                val exactArguments=resources.journal.recordedArguments(locator)
                assertEquals(original,exactArguments.first.openBytes(exactArguments.second.handle).bufferedReader().use{it.readText()})
                assertNull(TypedChatForkBoundary.completedEnd(checkpoint.exact))
                a.loadSession(saved)
                withTimeout(30000){a.chat("Inspect the pending outcome without automatically rerunning",f.config,maxTurns=1)}
                assertEquals(1,f.requests.size);assertEquals(listOf(command),terminal.commands.toList())
                assertFalse(root.resolve("must-not-exist.txt").exists())
                val restored=checkNotNull(a.sessions.get(saved.id))
                assertEquals(checkpoint.exact,checkNotNull(restored.typedChat).exact)
                assertEquals(ToolCallHistoryJournal.State.EXECUTING,resources.journal.state(locator))
            } finally {a.shutdown()}
        }}} finally {PathSecurity.deleteTreeNoFollow(root)}
    }

}
