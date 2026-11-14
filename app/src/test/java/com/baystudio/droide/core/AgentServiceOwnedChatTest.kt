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
@Config(sdk = [35], manifest = Config.NONE)
class AgentServiceOwnedChatTest {
    @org.junit.Before fun openApplicationSession() {
        ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }
    private val docs = object: WorkspaceDocumentAuthority {
        override suspend fun snapshot(path:String):WorkspaceDocumentSnapshot? = null
        override suspend fun snapshots():List<WorkspaceDocumentSnapshot> = emptyList()
        override suspend fun observeRecovery(path:String) = WorkspaceRecoveryEditorObservation(emptyList(), emptySet(), {})
        override suspend fun commitDiskOnly(path:String, action:()->Unit) = action()
    }
    private fun response(fixture:ProviderStreamFixture, text:String, mixed:Boolean=false):String {
        val arguments=buildJsonObject { put("path","large.txt");put("content",text) }.toString()
        return buildString {
            arguments.chunked(16000).forEachIndexed { index,fragment ->
                val delta=buildJsonObject { put("tool_calls",buildJsonArray { add(buildJsonObject {
                    put("index",0);if(index==0){put("id","large-call");put("type","function")}
                    put("function",buildJsonObject { if(index==0)put("name","write_file");put("arguments",fragment) })
                }) }) }
                append(fixture.event(buildJsonObject { put("choices",buildJsonArray { add(buildJsonObject { put("delta",delta) }) }) }.toString()))
            }
            if(mixed)append(fixture.event("""{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"other","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"large.txt\"}"}}]}}]}"""))
            append(fixture.event("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""));append("data: [DONE]\n\n")
        }
    }
    @Test(timeout=90000) fun largeWritePersistsCanonicalReplaysAndRestoresWithoutLegacyProjection()=runBlocking {
        val root=Files.createTempDirectory("service-owned-").toFile()
        try { withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
            val text="exact field content\n".repeat(120000)
            var secondRequest=""
            fixture.response={ index,body,_ ->
                if(index==1)response(fixture,text) else {
                    secondRequest=body.toString()
                    fixture.event("""{"choices":[{"delta":{"content":"Done exactly"},"finish_reason":"stop"}]}""")+"data: [DONE]\n\n"
                }
            }
            val agent=AgentService(FileRepository(root),AgentWorkflowTerminal(),GitManager(root),
                perms=PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW))),documentAuthority=docs,maxSubagentDepth=0)
            try {
                runBlocking { withTimeout(60000) { agent.chat("Write the complete file",fixture.config,maxTurns=2) } }
                assertTrue(agent.messages.value.toString(),root.resolve("large.txt").exists())
                assertEquals(text,root.resolve("large.txt").readText())
                val saved=runBlocking { checkNotNull(agent.sessions.get(checkNotNull(agent.currentSessionId))) }
                assertNotNull(saved.typedChat);assertTrue(saved.apiHistory.isEmpty())
                assertTrue(saved.typedChat!!.exact.any { it is TypedChatEntry.OwnedAssistant && it.callIds==listOf("large-call") })
                val messages=Json.parseToJsonElement(secondRequest).jsonObject.getValue("messages").jsonArray
                val call=messages.map { it.jsonObject }.first { it["tool_calls"] != null }.getValue("tool_calls").jsonArray.single().jsonObject
                assertEquals(text,Json.parseToJsonElement(call.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content).jsonObject.getValue("content").jsonPrimitive.content)
                assertTrue(messages.any { it.jsonObject["role"]==JsonPrimitive("tool") })
                runBlocking { agent.loadSession(saved);withTimeout(30000) { agent.chat("Confirm the result",fixture.config,maxTurns=1) } }
                assertTrue(secondRequest.contains("large-call"))
            } finally { agent.shutdown() }
        } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test(timeout=60000) fun unsupportedMixedLargeBatchExecutesNothing()=runBlocking {
        val root=Files.createTempDirectory("service-owned-mixed-").toFile()
        try { withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
            fixture.response={ _,_,_->response(fixture,"x".repeat(150000),true).replace("\"name\":\"read_file\"", "\"name\":\"not_advertised\"") }
            val agent=AgentService(FileRepository(root),AgentWorkflowTerminal(),GitManager(root),
                perms=PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW))),documentAuthority=docs,maxSubagentDepth=0)
            try {
                runBlocking { withTimeout(30000) { agent.chat("Write file",fixture.config,maxTurns=2) } }
                assertFalse(root.resolve("large.txt").exists())
                assertTrue(agent.messages.value.toString(),agent.messages.value.any { it.content.contains("not advertised") })
            } finally { agent.shutdown() }
        } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test(timeout=90000) fun stopWhileAskingAcknowledgesNoExecutionAndContinuationNeverExecutesSavedCall()=runBlocking {
        val root=Files.createTempDirectory("service-owned-ask-").toFile()
        try { withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
            val text="review all of this\n".repeat(10000)
            fixture.response={ index,_,_-> if(index==1) response(fixture,text) else
                fixture.event("""{"choices":[{"delta":{"content":"Acknowledged"},"finish_reason":"stop"}]}""")+"data: [DONE]\n\n" }
            val agent=AgentService(FileRepository(root),AgentWorkflowTerminal(),GitManager(root),
                perms=PermissionEngine(listOf(PermRule("*","*",PermEffect.ASK))),documentAuthority=docs,maxSubagentDepth=0)
            try { runBlocking {
                val task=async { agent.chat("Write file after review",fixture.config,maxTurns=2) }
                val request=withTimeout(30000) { agent.approvals.pending.filterNotNull().first() }
                val proposal=checkNotNull(request.proposedContent)
                val full=java.io.ByteArrayOutputStream();proposal.export(full)
                assertEquals(text,full.toString("UTF-8"));assertFalse(root.resolve("large.txt").exists())
                val saved=checkNotNull(agent.sessions.get(checkNotNull(agent.currentSessionId)))
                assertNotNull(saved.typedChat)
                withTimeout(15000){task.cancelAndJoin()}
                agent.loadSession(saved)
                withTimeout(15000){agent.chat("Continue",fixture.config,maxTurns=1)}
                assertFalse(root.resolve("large.txt").exists())
                // Runtime Stop can prove no execution began; replay receives cancellation, never an execution permit.
                assertFalse(agent.messages.value.toString(),agent.messages.value.any { it.content.startsWith("Error:") })
                assertEquals(2,fixture.requests.size)
                assertTrue(fixture.requests.last().toString().contains("Cancelled before execution"))
            } } finally { agent.shutdown() }
        } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test(timeout=90000) fun stopAfterRenameRetainsReceiptAndContinuationDoesNotRepeatWrite()=runBlocking {
        val root=Files.createTempDirectory("service-owned-stop-").toFile()
        try { withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
            val text="committed once\n".repeat(12000)
            fixture.response={ index,_,_-> if(index==1) response(fixture,text) else
                fixture.event("""{"choices":[{"delta":{"content":"Already committed"},"finish_reason":"stop"}]}""")+"data: [DONE]\n\n" }
            lateinit var running:Job;var mutations=0
            val files=FileRepository(root,onMutation={ mutation -> if(mutation is FileRepository.Mutation.Write) {
                mutations++;running.cancel(CancellationException("User Stop after native publication"))
            } })
            val agent=AgentService(files,AgentWorkflowTerminal(),GitManager(root),
                perms=PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW))),documentAuthority=docs,maxSubagentDepth=0)
            try { runBlocking {
                running=launch(start=CoroutineStart.LAZY) { agent.chat("Write file",fixture.config,maxTurns=2) }
                running.start();withTimeout(30000){running.join()}
                assertTrue(agent.messages.value.toString(), root.resolve("large.txt").exists())
                assertEquals(text,root.resolve("large.txt").readText());assertEquals(1,mutations)
                val saved=checkNotNull(agent.sessions.get(checkNotNull(agent.currentSessionId)))
                val checkpoint=checkNotNull(saved.typedChat)
                val turn=checkpoint.exact.filterIsInstance<TypedChatEntry.OwnedAssistant>().first()
                val resources=agent.sessions.typedChatResources()
                val locator=checkNotNull(resources.journal.locate(
                    OwnedResponseContentStore.Owner(saved.id,turn.runId,"large-call"),"write_file"))
                assertEquals(ToolCallHistoryJournal.State.SETTLED,resources.journal.state(locator))
                agent.loadSession(saved)
                withTimeout(30000){agent.chat("Continue without repeating completed work",fixture.config,maxTurns=1)}
                assertEquals(1,mutations);assertEquals(text,root.resolve("large.txt").readText())
                assertEquals(2,fixture.requests.size)
                val last=fixture.requests.last().getValue("messages").jsonArray
                assertTrue(last.any { it.jsonObject["role"]==JsonPrimitive("tool") })
            } } finally { agent.shutdown() }
        } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test(timeout=60000) fun manualTypedCompactionCommitsRevisionAndKeepsExactHistory()=runBlocking {
        val root=Files.createTempDirectory("service-owned-compact-").toFile()
        try { withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
            ModelContextRegistry.remember(fixture.provider.id,fixture.model,64000)
            fixture.response={ _,_,_-> fixture.event("""{"choices":[{"delta":{"content":"Earlier code was reviewed; no authorization to publish was given."},"finish_reason":"stop"}]}""")+"data: [DONE]\n\n" }
            val agent=AgentService(FileRepository(root),AgentWorkflowTerminal(),GitManager(root),maxSubagentDepth=0)
            try { runBlocking {
                fun entry(role:String,content:String)=TypedChatEntry.Legacy(buildJsonObject { put("role",role);put("content",content) })
                val entries=listOf(entry("system","Never publish without approval."),entry("user","Investigate earlier work"),
                    entry("assistant","historical details ".repeat(1000)),entry("user","Review only now."))
                val session=agent.sessions.create()
                val target=checkNotNull(LlmClient().ownedChatTarget(fixture.provider.id,fixture.provider.baseUrl,fixture.model))
                val checkpoint=TypedChatCheckpoint(target=TypedChatTarget.of(target),live=entries,exact=entries)
                val saved=agent.sessions.attachTypedChat(session.id,checkpoint)
                agent.loadSession(saved)
                assertEquals("Session compacted.",withTimeout(30000){agent.compactCurrent(fixture.config)})
                val compacted=checkNotNull(agent.sessions.get(session.id)).typedChat!!
                assertEquals(entries,compacted.exact);assertEquals(entries.first(),compacted.live.first())
                assertEquals(entries.last(),compacted.live.last());assertTrue(compacted.live.size<entries.size)
                assertEquals(1,fixture.requests.size)
                assertFalse(checkNotNull(agent.compactionActivity.value).running)
                assertNull(agent.compactionActivity.value!!.afterTokens)
            } } finally { agent.shutdown() }
        } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test(timeout=90000) fun mixedOwnedThenSmallToolForkKeepsCompleteProtocolWithoutLaterAnswer()=runBlocking {
        val root=Files.createTempDirectory("service-owned-fork-").toFile()
        root.resolve("evidence.txt").writeText("local evidence")
        try { withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
            val text="complete write\n".repeat(12000)
            fixture.response={ index,_,_-> when(index) {
                1->response(fixture,text)
                2->fixture.event("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"small-read","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"evidence.txt\"}"}}]},"finish_reason":"tool_calls"}]}""")+"data: [DONE]\n\n"
                3->fixture.event("""{"choices":[{"delta":{"content":"Original completion"},"finish_reason":"stop"}]}""")+"data: [DONE]\n\n"
                else->fixture.event("""{"choices":[{"delta":{"content":"Fork continuation"},"finish_reason":"stop"}]}""")+"data: [DONE]\n\n"
            } }
            var writes=0
            val agent=AgentService(FileRepository(root,onMutation={ if(it is FileRepository.Mutation.Write)writes++ }),AgentWorkflowTerminal(),GitManager(root),
                perms=PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW))),documentAuthority=docs,maxSubagentDepth=0)
            try { runBlocking {
                withTimeout(30000){agent.chat("Write then read evidence",fixture.config,maxTurns=3)}
                val saved=checkNotNull(agent.sessions.get(checkNotNull(agent.currentSessionId)))
                val smallIndex=saved.messages.indexOfFirst { it.streamPart?.toolCallId=="small-read" }
                assertTrue(saved.messages.toString(),smallIndex>=0)
                assertNotNull(saved.messages[smallIndex].forkHistoryEnd)
                assertTrue(saved.messages.any { it.streamPart?.toolCallId=="large-call" })
                val fork=checkNotNull(agent.sessions.fork(saved.id,smallIndex))
                assertNotEquals(saved.id,fork.id)
                assertEquals(1,fork.typedChat!!.exact.filterIsInstance<TypedChatEntry.OwnedAssistant>().count { "large-call" in it.callIds })
                agent.loadSession(fork)
                withTimeout(30000){agent.chat("Continue this branch",fixture.config,maxTurns=1)}
                val sent=fixture.requests.last().toString()
                assertTrue(sent.contains("small-read"));assertTrue(sent.contains("large-call"))
                assertFalse(sent.contains("Original completion"));assertEquals(1,writes)
            } } finally { agent.shutdown() }
        } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

}
