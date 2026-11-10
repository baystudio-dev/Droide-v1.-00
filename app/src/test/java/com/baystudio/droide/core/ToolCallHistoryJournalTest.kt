package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolCallHistoryJournalTest {
    private fun fixture(test: (File, OwnedResponseContentStore) -> Unit) {
        val dir = Files.createTempDirectory("tool-history-").toFile()
        try { test(dir, OwnedResponseContentStore(File(dir,"owned").toPath(), OwnedResponseContentStore.HeadroomAdmission({ Long.MAX_VALUE },0))) }
        finally { dir.deleteRecursively() }
    }
    private val owner = OwnedResponseContentStore.Owner("session","run","call")
    private fun arguments(store: OwnedResponseContentStore, text: String): StreamedFileArgumentExtractor.Source {
        val c = store.authorize(owner).begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use {
            it.append(text); it.sealCompleted { input -> StreamedJsonValidator.requireObject(input) }.content
        }
        return StreamedFileArgumentExtractor.Source(c.handle,c.byteLength,c.utf16Length,c.sha256,c.kind)
    }
    private fun readResult(journal: ToolCallHistoryJournal, locator: ToolCallHistoryJournal.Locator): JsonObject = Json.parseToJsonElement(java.io.ByteArrayOutputStream().also { journal.writeResult(locator,it) }.toString("UTF-8")).jsonObject
    private fun result() = buildJsonObject { put("role","tool");put("tool_call_id","call");put("content","saved") }
    @Test fun largeArgumentsPersistBeforeExecutionAndRestartNeverRestoresPermission() = fixture { dir, store ->
        val original = "{\"path\":\"output.txt\",\"content\":\"" + "z".repeat(2_100_000) + "\"}"
        val source = arguments(store,original)
        val root = File(dir,"journal");val journal = ToolCallHistoryJournal(root,store)
        val pending = journal.recordCompleted(owner,"write_file",source)
        assertEquals(ToolCallHistoryJournal.State.RECORDED,journal.state(pending.locator))
        assertThrows(IllegalArgumentException::class.java) { journal.beginExecution(pending) {} }
        journal.approve(pending,"a".repeat(64)) {}
        val restarted = ToolCallHistoryJournal(root,store)
        assertThrows(IllegalArgumentException::class.java) { restarted.beginExecution(pending) {} }
        assertThrows(IllegalArgumentException::class.java) { restarted.recordCompleted(owner,"write_file",source) }
        val execution = journal.beginExecution(pending) {}
        assertEquals(ToolCallHistoryJournal.State.EXECUTING,restarted.state(pending.locator))
        assertThrows(IllegalArgumentException::class.java) { journal.beginExecution(pending) {} }
        assertThrows(IllegalArgumentException::class.java) { restarted.completedArguments(pending.locator) }
        journal.settle(execution,result())
        val (session,replayed) = restarted.completedArguments(pending.locator)
        assertEquals(original,session.openBytes(replayed.handle).bufferedReader().use { it.readText() })
        assertEquals(result(),readResult(restarted,pending.locator))
        assertFalse(pending.locator.toString().contains(source.handle.serialized))
    }
    @Test fun forkRebindsSettledArgumentsAndNeverGrantsPendingPermission() = fixture { dir, store ->
        val journal = ToolCallHistoryJournal(File(dir,"journal"),store)
        val pending = journal.recordCompleted(owner,"write_file",arguments(store,"{\"path\":\"a\",\"content\":\"x\"}"))
        journal.approve(pending,"b".repeat(64)) {};journal.settle(journal.beginExecution(pending) {},result())
        val targetOwner = owner.copy(sessionId="fork")
        val fork = journal.copyForFork(pending.locator,journal,targetOwner)
        val source = journal.completedArguments(pending.locator).second
        val child = journal.completedArguments(fork)
        assertNotEquals(source.handle.serialized,child.second.handle.serialized)
        assertThrows(IllegalStateException::class.java) { source.verify(child.first) }
        assertEquals(result(),readResult(journal,fork))
        assertThrows(IllegalArgumentException::class.java) { journal.state(fork.copy(sessionId="attacker")) }
    }
    @Test fun incompleteDeniedAndRevokedAuthorizationNeverExecutes() = fixture { dir,store ->
        val journal=ToolCallHistoryJournal(File(dir,"journal"),store)
        val c=store.authorize(owner).begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use { it.append("{\"path\":");it.sealIncomplete() }
        val incomplete=journal.recordIncomplete(owner,"write_file",c.handle)
        assertEquals(ToolCallHistoryJournal.State.INCOMPLETE,journal.state(incomplete))
        assertThrows(IllegalArgumentException::class.java) { journal.completedArguments(incomplete) }
    }
    @Test fun revocationDenialAndResultTamperFailClosed() = fixture { dir,store ->
        val journal=ToolCallHistoryJournal(File(dir,"journal"),store)
        val pending=journal.recordCompleted(owner,"write_file",arguments(store,"{\"path\":\"a\",\"content\":\"x\"}"))
        assertThrows(IllegalStateException::class.java) { journal.approve(pending,"a".repeat(64)) { error("revoked") } }
        assertEquals(ToolCallHistoryJournal.State.RECORDED,journal.state(pending.locator))
        journal.settleWithoutExecution(pending,buildJsonObject { put("role","tool");put("tool_call_id","call");put("content","denied") })
        assertThrows(IllegalArgumentException::class.java) { journal.beginExecution(pending) {} }
        File(dir,"journal/${pending.locator.id}.result").appendText("tampered")
        assertThrows(IllegalArgumentException::class.java) { journal.verifyResult(pending.locator) }
    }
    @Test fun malformedMetadataAndCancelledSettlementNeverRestoreExecution() = fixture { dir,store ->
        var cancelled=false
        val root=File(dir,"journal");val journal=ToolCallHistoryJournal(root,store) { if(cancelled) throw java.util.concurrent.CancellationException() }
        val pending=journal.recordCompleted(owner,"write_file",arguments(store,"{\"path\":\"a\",\"content\":\"x\"}"))
        journal.approve(pending,"a".repeat(64)) {};val execution=journal.beginExecution(pending) {}
        cancelled=true
        assertThrows(java.util.concurrent.CancellationException::class.java) { journal.settle(execution,result()) }
        val restarted=ToolCallHistoryJournal(root,store)
        assertEquals(ToolCallHistoryJournal.State.EXECUTING,restarted.state(pending.locator))
        assertThrows(IllegalArgumentException::class.java) { restarted.beginExecution(pending) {} }
        File(root,"${pending.locator.id}.json").writeText("x".repeat(65537))
        assertThrows(IllegalArgumentException::class.java) { restarted.state(pending.locator) }
    }
    @Test fun stopBeforeExecutionUsesOnlyOriginalRuntimeCapability() = fixture { dir,store ->
        for (approved in listOf(false,true)) {
            var cancelled=false
            val root=File(dir,"stop-$approved")
            val journal=ToolCallHistoryJournal(root,store) { if(cancelled) throw java.util.concurrent.CancellationException() }
            val pending=journal.recordCompleted(owner,"write_file",arguments(store,"{}"))
            if(approved) journal.approve(pending,"a".repeat(64)) {}
            cancelled=true
            val restored=ToolCallHistoryJournal(root,store)
            assertThrows(IllegalArgumentException::class.java) { restored.checkpointNoExecution(pending) }
            assertThrows(IllegalArgumentException::class.java) { journal.checkpointNoExecution(ToolCallHistoryJournal.Pending(journal,pending.locator)) }
            assertThrows(IllegalArgumentException::class.java) { journal.checkpointNoExecution(ToolCallHistoryJournal.Pending(journal,pending.locator.copy(sessionId="other"))) }
            val result=journal.checkpointNoExecution(pending)
            assertEquals(result,readResult(restored,pending.locator))
            assertEquals(ToolCallHistoryJournal.State.SETTLED,restored.state(pending.locator))
            assertThrows(IllegalArgumentException::class.java) { journal.checkpointNoExecution(pending) }
            assertThrows(IllegalArgumentException::class.java) { journal.beginExecution(pending) {} }
        }
    }
    @Test fun executionAndStopRaceHaveOnlyOneWinner() = fixture { dir,store ->
        repeat(12) { index ->
            val journal=ToolCallHistoryJournal(File(dir,"race-$index"),store)
            val pending=journal.recordCompleted(owner,"write_file",arguments(store,"{}"))
            journal.approve(pending,"a".repeat(64)) {}
            val start=java.util.concurrent.CountDownLatch(1)
            val execution=java.util.concurrent.atomic.AtomicBoolean(false)
            val stopped=java.util.concurrent.atomic.AtomicBoolean(false)
            val a=Thread { start.await();runCatching { journal.beginExecution(pending) {} }.onSuccess { execution.set(true) } }
            val b=Thread { start.await();runCatching { journal.checkpointNoExecution(pending) }.onSuccess { stopped.set(true) } }
            a.start();b.start();start.countDown();a.join();b.join()
            assertTrue(execution.get() xor stopped.get())
            assertEquals(if(execution.get()) ToolCallHistoryJournal.State.EXECUTING else ToolCallHistoryJournal.State.SETTLED,journal.state(pending.locator))
            assertThrows(IllegalArgumentException::class.java) { journal.checkpointNoExecution(pending) }
        }
    }

}
