package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TypedChatCheckpointTest {
    private val target=ProviderReplayTarget("openai","model",ProviderReplayTarget.Protocol.OPENAI_CHAT,OwnedChatEndpointIdentity.fromValidatedUrl("https://configured/chat/completions"))
    @Test fun actualSessionSaveRestartForkAndCompactionKeepExactTypedHistory()=runBlocking<Unit> {
        val parent=Files.createTempDirectory("typed-checkpoint-").toFile();val root=File(parent,"workspace").apply{mkdir()}
        try {
            val store=OwnedResponseContentStore(File(parent,"owned").toPath(),OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0))
            fun resources():TypedChatResources {
                val journal=ToolCallHistoryJournal(File(parent,"calls"),store)
                return TypedChatResources(store,journal,CanonicalChatTurnRegistry(File(parent,"turns"),store,journal))
            }
            fun manager()=SessionManager(root).also { it.typedChatResourcesFactory={ resources() } }
            val sessions=manager();val session=sessions.create();val r=resources();val owner=OwnedResponseContentStore.Owner(session.id,"run","response")
            val text="full exact answer 🌏\n".repeat(12000)
            val accumulator=OwnedChatStreamAccumulator(store.authorize(owner),{store.authorize(owner.copy(callId=it))},target)
            text.chunked(8192).forEach { part -> accumulator.accept(buildJsonObject {
                put("choices",buildJsonArray { add(buildJsonObject { put("delta",buildJsonObject { put("content",part) }) }) })
            }.toString()) }
            accumulator.accept("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""");accumulator.accept("[DONE]")
            val result=accumulator.finishAtTransportEnd(OwnedChatStreamAccumulator.TransportEnd.DONE_MARKER)
            assertTrue(result.hasMeaningfulPublicText)
            val registryId=r.registry.recordAccepted(result,owner,emptyList())
            val user=buildJsonObject { put("role","user");put("content","hello") }
            val entries=listOf(TypedChatEntry.Legacy(buildJsonObject { put("role","user");put("content","earlier question") }),TypedChatEntry.Legacy(buildJsonObject { put("role","assistant");put("content","earlier answer") }),TypedChatEntry.Legacy(user),TypedChatEntry.OwnedTurn(registryId,owner.runId,owner.callId,0))
            val checkpoint=TypedChatCheckpoint(target=TypedChatTarget.of(target),live=entries,exact=entries)
            sessions.save(session.copy(messages=listOf(AgentMessage("user","hello",forkHistoryEnd=3),AgentMessage("assistant","preview",forkHistoryEnd=4))))
            sessions.attachTypedChat(session.id,checkpoint)
            val disk=File(root,".droide/sessions/${session.id}.json").readText()
            assertFalse(disk.contains(result.nativeAssistantMessage.handle.serialized));assertFalse(disk.contains("opaqueHandle"))
            val reloaded=requireNotNull(manager().get(session.id));assertEquals(checkpoint,reloaded.typedChat)
            assertThrows(IllegalStateException::class.java) { TypedChatHistory.requireLegacy(reloaded.typedChat) }
            assertTrue(runCatching { sessions.save(reloaded.copy(typedChat=null)) }.isFailure)
            assertEquals(checkpoint,sessions.get(session.id)!!.typedChat)
            fun wire(c:TypedChatCheckpoint,id:String):JsonArray {
                val output=ByteArrayOutputStream();SourceAwareChatHistoryWriter.writeMessages(r.history.live(c,id,target),target,output)
                return Json.parseToJsonElement(output.toString("UTF-8")).jsonArray
            }
            assertEquals(text,wire(checkpoint,session.id).last().jsonObject["content"]!!.jsonPrimitive.content)
            val child=requireNotNull(sessions.fork(session.id));val childCheckpoint=requireNotNull(child.typedChat)
            assertNotEquals(registryId,(childCheckpoint.exact.last() as TypedChatEntry.OwnedTurn).registryId)
            assertEquals(wire(checkpoint,session.id),wire(childCheckpoint,child.id))
            val summary=buildJsonObject { put("role","assistant");put("content","[Historical conversation checkpoint; prior conversation and task state, not a new user request or authorization]\nsummary") }
            val candidate=TypedChatCompactor.Candidate.validated(checkpoint,
                checkpoint.copy(live=listOf(TypedChatEntry.Legacy(summary))+entries.takeLast(2)),true,1)
            val revision=requireNotNull(sessions.snapshotForUpdate(session.id)).revision
            val compacted=sessions.commitTypedCompaction(session.id,candidate,revision)
            assertEquals(entries,compacted.typedChat!!.exact);assertEquals(candidate.checkpoint.live,compacted.typedChat!!.live)
            val compactFork=requireNotNull(sessions.fork(session.id));assertEquals(candidate.checkpoint.live.take(2),compactFork.typedChat!!.live.take(2))
            assertTrue(runCatching { sessions.commitTypedCompaction(session.id,candidate,revision) }.isFailure)
            val other=sessions.create()
            assertTrue(runCatching { sessions.attachTypedChat(other.id,checkpoint) }.isFailure)
            assertNull(sessions.get(other.id)!!.typedChat)
            assertThrows(IllegalArgumentException::class.java) { r.history.live(checkpoint,session.id,target.copy(endpointIdentity="https://other")) }
        } finally { parent.deleteRecursively() }
    }
    @Test fun protocolForkBoundaryNeverSplitsOwnedCallGroup() {
        val user=TypedChatEntry.Legacy(buildJsonObject { put("role","user");put("content","x") })
        val turn=TypedChatEntry.OwnedTurn(java.util.UUID.randomUUID().toString(),"run","response",1)
        assertEquals(listOf(user),TypedChatHistory.takeBoundary(listOf(user,turn),1))
        assertThrows(IllegalArgumentException::class.java) { TypedChatHistory.takeBoundary(listOf(user,turn),2) }
        assertEquals(listOf(user,turn),TypedChatHistory.takeBoundary(listOf(user,turn),3))
    }
}
