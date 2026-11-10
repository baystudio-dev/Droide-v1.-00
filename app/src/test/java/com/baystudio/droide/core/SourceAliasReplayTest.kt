package com.baystudio.droide.core

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SourceAliasReplayTest {
    private class Env : AutoCloseable {
        val root=Files.createTempDirectory("native-source-replay-").toFile()
        val store=OwnedResponseContentStore(File(root,"owned").toPath(),OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0))
        val owner=OwnedResponseContentStore.Owner("session","run","response")
        val target=ProviderReplayTarget("provider","model",ProviderReplayTarget.Protocol.OPENAI_CHAT,OwnedChatEndpointIdentity.fromValidatedUrl("https://configured/chat/completions"))
        val journal=ToolCallHistoryJournal(File(root,"calls"),store)
        val registry=CanonicalChatTurnRegistry(File(root,"turns"),store,journal)
        val native=registry.nativeAssistantRegistry()
        val history=TypedChatHistory(registry)
        val received:OwnedChatResult
        val pending:ToolCallHistoryJournal.Pending
        init {
            val args=buildJsonObject {put("path","a.txt");put("content","x".repeat(150000))}.toString()
            val acc=OwnedChatStreamAccumulator(store.authorize(owner),{store.authorize(owner.copy(callId=it))},target)
            val event=buildJsonObject {put("choices",buildJsonArray {add(buildJsonObject {put("delta",buildJsonObject {
                put("tool_calls",buildJsonArray {
                    add(buildJsonObject {put("index",0);put("id","large");put("function",buildJsonObject {put("name","write");put("arguments",args)})})
                    add(buildJsonObject {put("index",1);put("id","small");put("function",buildJsonObject {put("name","list_files");put("arguments","{}")})})
                })
            })})})}
            acc.accept(event.toString());acc.accept("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""");acc.accept("[DONE]")
            received=acc.finishAtTransportEnd(OwnedChatStreamAccumulator.TransportEnd.DONE_MARKER)
            pending=journal.recordCompleted(owner.copy(callId="large"),"write",received.calls.first().arguments)
        }
        fun result(id:String,text:String)=buildJsonObject {put("role","tool");put("tool_call_id",id);put("content",text)}
        fun checkpoint():TypedChatCheckpoint {
            val id=native.recordAccepted(received,owner,listOf(pending))
            val entry=TypedChatEntry.OwnedAssistant(id,owner.runId,owner.callId,listOf("large","small"))
            val items=listOf(entry,TypedChatEntry.Legacy(result("small","listed")),TypedChatEntry.Legacy(result("large","settled")))
            return TypedChatCheckpoint(target=TypedChatTarget.of(target),live=items,exact=items)
        }
        fun wire(c:TypedChatCheckpoint,session:String=owner.sessionId):String {
            val out=ByteArrayOutputStream()
            SourceAwareChatHistoryWriter.writeMessages(history.validateCompleteRequest(c,session,target),target,out)
            return out.toString("UTF-8")
        }
        override fun close(){root.deleteRecursively()}
    }
    @Test fun canonicalAdvertisementDoesNotAuthorizeAbsentWireAlias() {Env().use {e->
        val failure=runCatching {MixedOwnedToolPlanner.prepare(e.received,{e.store.authorize(e.owner.copy(callId=it))},
            Json,1,128000,setOf("write_file","list_files"),"",0,{})}
        assertTrue(failure.isFailure)
        assertEquals(ToolCallHistoryJournal.State.RECORDED,e.journal.state(e.pending.locator))
        val calls=MixedOwnedToolPlanner.prepare(e.received,{e.store.authorize(e.owner.copy(callId=it))},
            Json,1,128000,setOf("write","list_files"),"",0,{})
        val source=calls.first().input as PreparedToolInput.SourceFile
        assertEquals("write",source.prepared.wireName)
        assertEquals(StreamedFileArgumentExtractor.Tool.WRITE_FILE,source.prepared.tool)
        assertTrue(runCatching {StreamedFileArgumentExtractor.Prepared(StreamedFileArgumentExtractor.Tool.EDIT_FILE,
            "large","a.txt",null,source.original,source.prepared.content,null,null,"write")}.isFailure)
        assertTrue(runCatching {StreamedFileArgumentExtractor.extractWire(source.session,source.original.handle,"custom_write","large")}.isFailure)
    }}
    @Test fun unknownExecutingCannotReplayOrForkFakeWorkspaceResult() {Env().use { e ->
        val c=e.checkpoint();e.history.verify(c,e.owner.sessionId) // pending is persistable, not replayable
        e.journal.approve(e.pending,"a".repeat(64),{});e.journal.beginExecution(e.pending,{})
        assertTrue(runCatching {e.wire(c)}.isFailure)
        assertTrue(runCatching {e.history.fork(c,e.owner.sessionId,"child",e.registry)}.isFailure)
        assertEquals(ToolCallHistoryJournal.State.EXECUTING,e.journal.state(e.pending.locator))
    }}
    @Test fun settledExactReceiptReplaysForksAndNeverMintsExecution() {Env().use { e ->
        val c=e.checkpoint();e.journal.settleWithoutExecution(e.pending,e.result("large","settled"))
        val expected=e.wire(c);val child=e.history.fork(c,e.owner.sessionId,"child",e.registry)
        assertEquals(expected,e.wire(child,"child"))
        val locator=requireNotNull(e.journal.locate(e.owner.copy(sessionId="child",callId="large"),"write"))
        assertEquals(ToolCallHistoryJournal.State.SETTLED,e.journal.state(locator))
        assertTrue(runCatching {e.journal.beginExecution(ToolCallHistoryJournal.Pending(e.journal,locator),{})}.isFailure)
    }}
    @Test fun settledMissingSourceResultIsRecoveredButOrdinaryResultsAreNeverInvented() {Env().use { e ->
        val c=e.checkpoint()
        val saved=c.copy(live=c.live.take(2),exact=c.exact.take(2))
        assertEquals(saved,e.history.reconcileSettledSources(saved,e.owner.sessionId))
        e.journal.settleWithoutExecution(e.pending,e.result("large","settled"))
        val recovered=e.history.reconcileSettledSources(saved,e.owner.sessionId)
        assertEquals(e.wire(c),e.wire(recovered))
        val noOrdinary=c.copy(live=c.live.take(1),exact=c.exact.take(1))
        val partial=e.history.reconcileSettledSources(noOrdinary,e.owner.sessionId)
        assertEquals(2,partial.live.size)
        assertTrue(runCatching {e.wire(partial)}.isFailure)
    }}
    @Test fun oversizedRecoveryReceiptFailsWithoutTruncatingPrivateEvidence() {Env().use { e ->
        val c=e.checkpoint();val saved=c.copy(live=c.live.take(2),exact=c.exact.take(2))
        e.journal.settleWithoutExecution(e.pending,e.result("large","x".repeat(300000)))
        assertTrue(runCatching {e.history.reconcileSettledSources(saved,e.owner.sessionId)}.isFailure)
        val bytes=ByteArrayOutputStream();e.journal.writeResult(e.pending.locator,bytes)
        assertEquals(300000,Json.parseToJsonElement(bytes.toString("UTF-8")).jsonObject["content"]!!.jsonPrimitive.content.length)
    }}
    @Test fun alteredWorkspaceResultRejectedBeforeAnyWireBytes() {Env().use { e ->
        val c=e.checkpoint();e.journal.settleWithoutExecution(e.pending,e.result("large","real private result"))
        val out=ByteArrayOutputStream()
        assertTrue(runCatching {SourceAwareChatHistoryWriter.writeMessages(e.history.live(c,e.owner.sessionId,e.target),e.target,out)}.isFailure)
        assertEquals(0,out.size())
    }}
    @Test fun duplicateForgedAndForeignPendingCannotCreateAssociation() {Env().use { e ->
        assertTrue(runCatching {e.native.recordAccepted(e.received,e.owner,listOf(e.pending,e.pending))}.isFailure)
        assertTrue(runCatching {e.native.recordAccepted(e.received,e.owner,listOf(ToolCallHistoryJournal.Pending(e.journal,e.pending.locator)))}.isFailure)
        val other=ToolCallHistoryJournal(File(e.root,"other"),e.store)
        val foreign=other.recordCompleted(e.owner.copy(callId="large"),"write",e.received.calls.first().arguments)
        assertTrue(runCatching {e.native.recordAccepted(e.received,e.owner,listOf(foreign))}.isFailure)
    }}
    @Test fun wrongArgumentsAndWrongToolNameCannotBind() {Env().use { e ->
        val other=ToolCallHistoryJournal(File(e.root,"other"),e.store)
        val native=NativeAssistantRegistry(File(e.root,"native-other"),e.store,other)
        val bad=other.recordCompleted(e.owner.copy(callId="large"),"edit_file",e.received.calls.first().arguments)
        assertTrue(runCatching {native.recordAccepted(e.received,e.owner,listOf(bad))}.isFailure)
        val source=e.received.calls.first().arguments
        val altered=e.received.calls.first().copy(arguments=StreamedFileArgumentExtractor.Source(source.handle,source.bytes,source.utf16Units,"0".repeat(64),source.kind))
        val result=OwnedChatResult(e.received.target,e.received.publicText,e.received.nativeAssistantMessage,listOf(altered,e.received.calls.last()),"tool_calls",null,null,null,null)
        assertTrue(runCatching {e.native.recordAccepted(result,e.owner,listOf(e.pending))}.isFailure)
    }}
}
