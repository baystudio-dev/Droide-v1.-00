package com.baystudio.droide.core

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeAssistantRegistryTest {
    @Test fun privateSmallToolsPersistReplayAndForkWithoutExecutionAuthority() {
        val root=Files.createTempDirectory("native-small-").toFile()
        try {
            val store=OwnedResponseContentStore(File(root,"owned").toPath(),OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0))
            val owner=OwnedResponseContentStore.Owner("session","run","response")
            val target=ProviderReplayTarget("provider","model",ProviderReplayTarget.Protocol.OPENAI_CHAT,OwnedChatEndpointIdentity.fromValidatedUrl("https://configured/chat/completions"))
            val acc=OwnedChatStreamAccumulator(store.authorize(owner),{store.authorize(owner.copy(callId=it))},target)
            acc.accept("""{"choices":[{"delta":{"reasoning_content":"private-secret-reasoning","content":"Checking files","tool_calls":[{"index":0,"id":"a","function":{"name":"read_file","arguments":"{\"path\":\"one\"}"}},{"index":1,"id":"b","function":{"name":"list_files","arguments":"{}"}}]}}]}""")
            acc.accept("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""");acc.accept("[DONE]")
            val received=acc.finishAtTransportEnd(OwnedChatStreamAccumulator.TransportEnd.DONE_MARKER)
            val journal=ToolCallHistoryJournal(File(root,"calls"),store)
            val registry=CanonicalChatTurnRegistry(File(root,"turns"),store,journal)
            val native=registry.nativeAssistantRegistry()
            val id=native.recordAccepted(received,owner)
            val entry=TypedChatEntry.OwnedAssistant(id,owner.runId,owner.callId,listOf("a","b"))
            fun result(id:String)=TypedChatEntry.Legacy(buildJsonObject { put("role","tool");put("tool_call_id",id);put("content","ok") })
            val user=TypedChatEntry.Legacy(buildJsonObject { put("role","user");put("content","inspect") })
            val entries=listOf(user,entry,result("b"),result("a"))
            val checkpoint=TypedChatCheckpoint(target=TypedChatTarget.of(target),live=entries,exact=entries)
            val serialized=Json.encodeToString(TypedChatCheckpoint.serializer(),checkpoint)
            assertFalse(serialized.contains("private-secret"));assertFalse(serialized.contains(received.nativeAssistantMessage.handle.serialized))
            assertFalse(File(root,"calls").exists())
            val history=TypedChatHistory(registry)
            fun wire(c:TypedChatCheckpoint,session:String):JsonArray {
                val out=ByteArrayOutputStream()
                SourceAwareChatHistoryWriter.writeMessages(history.validateCompleteRequest(c,session,target),target,out)
                return Json.parseToJsonElement(out.toString("UTF-8")).jsonArray
            }
            val replay=wire(checkpoint,owner.sessionId)
            assertEquals("private-secret-reasoning",replay[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
            assertEquals("read_file",replay[1].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals(checkpoint,Json.decodeFromString(TypedChatCheckpoint.serializer(),serialized))
            val child=history.fork(checkpoint,owner.sessionId,"child",registry)
            assertNotEquals(id,(child.exact[1] as TypedChatEntry.OwnedAssistant).registryId)
            assertEquals(replay,wire(child,"child"))
            assertThrows(IllegalArgumentException::class.java) { wire(checkpoint,"foreign") }
            assertThrows(IllegalArgumentException::class.java) { native.reopen(id,owner,target.copy(modelId="other")) }
            assertThrows(IllegalArgumentException::class.java) { history.verify(checkpoint.copy(live=listOf(entry.copy(callIds=listOf("forged"))),exact=listOf(entry.copy(callIds=listOf("forged")))),owner.sessionId) }
            val invalid=listOf(listOf(user,entry),listOf(user,entry,result("a")),listOf(user,entry,result("a"),result("a")),listOf(user,entry,result("a"),result("b"),result("b")))
            invalid.forEach { items ->
                val bad=checkpoint.copy(live=items,exact=items)
                assertThrows(IllegalArgumentException::class.java) { wire(bad,owner.sessionId) }
                assertThrows(IllegalArgumentException::class.java) { history.fork(bad,owner.sessionId,"bad-child",registry) }
            }
            assertEquals(listOf(user),history.fork(checkpoint,owner.sessionId,"prefix",registry,1).exact)
            assertThrows(IllegalArgumentException::class.java) { history.fork(checkpoint,owner.sessionId,"partial",registry,2) }
        } finally { root.deleteRecursively() }
    }
}
