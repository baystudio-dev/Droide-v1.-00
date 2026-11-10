package com.baystudio.droide.core

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CanonicalChatTurnRegistryTest {
    @Test fun actualTypedIngressDurableReplayIsExactAndBoundToJournalAndTarget() {
        val root=Files.createTempDirectory("canonical-chat-").toFile()
        try {
            val store=OwnedResponseContentStore(File(root,"owned").toPath(),OwnedResponseContentStore.HeadroomAdmission({ Long.MAX_VALUE },0))
            val owner=OwnedResponseContentStore.Owner("session","run","response")
            val target=ProviderReplayTarget("openai","model",ProviderReplayTarget.Protocol.OPENAI_CHAT,OwnedChatEndpointIdentity.fromValidatedUrl("https://configured/chat/completions"))
            val original=buildJsonObject { put("path","out.txt");put("content","unicode 🌏\\\n".repeat(180000)) }.toString()
            val acc=OwnedChatStreamAccumulator(store.authorize(owner),{ id -> store.authorize(owner.copy(callId=id)) },target)
            fun chunk(delta: JsonObject,reason: String?=null): String {
                val choice=buildJsonObject { put("delta",delta);reason?.let { put("finish_reason",it) } }
                return buildJsonObject { put("choices",buildJsonArray { add(choice) }) }.toString()
            }

            original.chunked(8192).forEachIndexed { i,fragment ->
                val function=buildJsonObject { if(i==0)put("name","write_file");put("arguments",fragment) }
                val call=buildJsonObject { put("index",0);if(i==0)put("id","call");put("function",function) }
                val delta=buildJsonObject { put("tool_calls",buildJsonArray { add(call) }) }
                acc.accept(chunk(delta))
            }
            acc.accept(chunk(buildJsonObject {},"tool_calls"));acc.accept("[DONE]")
            val result=acc.finishAtTransportEnd(OwnedChatStreamAccumulator.TransportEnd.DONE_MARKER)
            val journal=ToolCallHistoryJournal(File(root,"journal"),store)
            val pending=journal.recordCompleted(owner.copy(callId="call"),"write_file",result.calls.single().arguments)
            val registry=CanonicalChatTurnRegistry(File(root,"turns"),store,journal)
            assertThrows(IllegalArgumentException::class.java) { registry.recordAccepted(result,owner,listOf(pending.locator.copy(callId="other"))) }
            val id=registry.recordAccepted(result,owner,listOf(pending.locator))
            val bound=registry.reopen(id,owner,target)
            val empty=ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java) { SourceAwareChatHistoryWriter.writeMessages(listOf(SourceAwareChatHistoryWriter.Item.OwnedAssistantGroup(bound)),target,empty) }
            assertEquals(0,empty.size())
            val prefix=TypedChatEntry.Legacy(buildJsonObject { put("role","user");put("content","create") })
            val typedEntries=listOf(prefix,TypedChatEntry.OwnedTurn(id,owner.runId,owner.callId,1))
            val typed=TypedChatCheckpoint(target=TypedChatTarget.of(target),live=typedEntries,exact=typedEntries)
            val history=TypedChatHistory(registry)
            assertThrows(IllegalArgumentException::class.java) { history.fork(typed,owner.sessionId,"blocked-fork",registry) }
            val before=history.fork(typed,owner.sessionId,"prefix-fork",registry,1)
            assertEquals(listOf(prefix),before.exact)
            journal.approve(pending,"a".repeat(64)) {};journal.settle(journal.beginExecution(pending) {},buildJsonObject { put("role","tool");put("tool_call_id","call");put("content","saved") })
            val reopened=CanonicalChatTurnRegistry(File(root,"turns"),store,ToolCallHistoryJournal(File(root,"journal"),store)).reopen(id,owner,target)
            val out=ByteArrayOutputStream()
            val user=buildJsonObject { put("role","user");put("content","create") }
            SourceAwareChatHistoryWriter.writeMessages(listOf(SourceAwareChatHistoryWriter.Item.Legacy(user),SourceAwareChatHistoryWriter.Item.OwnedAssistantGroup(reopened)),target,out)
            val messages=Json.parseToJsonElement(out.toString("UTF-8")).jsonArray
            assertEquals(user,messages[0]);assertEquals(original,messages[1].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
            assertEquals("call",messages[2].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
            assertFalse(out.toString("UTF-8").contains(result.calls.single().arguments.handle.serialized))
            val forkOwner=owner.copy(sessionId="fork")
            val forkId=registry.copyForFork(id,owner,target,registry,forkOwner)
            val forkOutput=ByteArrayOutputStream()
            SourceAwareChatHistoryWriter.writeMessages(listOf(SourceAwareChatHistoryWriter.Item.OwnedAssistantGroup(registry.reopen(forkId,forkOwner,target))),target,forkOutput)
            assertEquals(messages.drop(1),Json.parseToJsonElement(forkOutput.toString("UTF-8")).jsonArray.toList())
            assertThrows(IllegalArgumentException::class.java) { registry.reopen(id,owner.copy(sessionId="other"),target) }
            assertThrows(IllegalArgumentException::class.java) { registry.reopen(id,owner,target.copy(endpointIdentity="https://changed")) }
        } finally { root.deleteRecursively() }
    }
}
