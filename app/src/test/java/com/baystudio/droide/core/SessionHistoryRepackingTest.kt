package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class SessionHistoryRepackingTest {
    private fun entry(value: String) = buildJsonObject { put("role", "user"); put("content", value) }
    private fun <T> withRoot(block: suspend (File) -> T): T = runBlocking {
        val root = Files.createTempDirectory("droide-history-repack-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun pageFiles(root: File) = File(root, ".droide/sessions/.history-pages").listFiles()!!.filter { it.extension == "json" }
    private fun files(root: File) = pageFiles(root).associate { it.name to it.readBytes().toList() }

    @Test fun exhaustedSmallCheckpointsContinueThroughSaveRestartAndFork() = withRoot { root ->
        val manager = SessionManager(root)
        val session = manager.create("Fragmented history")
        val originalEntries = List(4096) { entry("turn $it") }
        val descriptors = originalEntries.map { manager.historyLedger.writePage(listOf(it)) }
        val old = manager.historyLedger.snapshot(descriptors, emptyList())
        manager.save(session.copy(messages = listOf(AgentMessage("user", "turn 4095")),
            forkHistory = old, forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION))
        val oldFork = manager.fork(session.id)!!
        val before = files(root)
        val reopened = SessionManager(root)
        val loaded = reopened.get(session.id)!!
        val history = AgentConversationHistory(reopened.historyLedger)
        history.load(loaded)
        history.append(entry("next turn"))
        val next = history.completeExactOrNull()!! as SessionHistoryLedger.Snapshot
        assertTrue("Small checkpoint fragmentation must not consume the page limit", next.pages.size < 10)
        assertEquals(4097, next.size)
        assertEquals(originalEntries.last(), next[4095]); assertEquals(entry("next turn"), next.last())
        reopened.save(loaded.copy(forkHistory = next, forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION))
        val cold = SessionManager(root)
        assertEquals(next, cold.get(session.id)!!.forkHistory)
        val fork = cold.fork(session.id)!!
        assertEquals(next, SessionManager(root).get(fork.id)!!.forkHistory)
        assertEquals("Previously captured snapshots remain valid", originalEntries, old)
        assertEquals(originalEntries, cold.get(oldFork.id)!!.forkHistory)
        val after = files(root)
        assertEquals(next, history.completeExactOrNull())
        assertEquals(after, files(root))
        before.forEach { (name, bytes) -> assertEquals(bytes, File(root, ".droide/sessions/.history-pages/$name").readBytes().toList()) }
        assertTrue(next.cachedPageCount <= 2)
    }

    @Test fun fragmentedColdSnapshotRepackingPreservesOrderingAndOldDescriptors() = withRoot { root ->
        val ledger = SessionHistoryLedger(root)
        val first = ledger.writePage(listOf(entry("first")))
        val second = ledger.writePage(listOf(entry("second")))
        val descriptors = List(4096) { if (it % 2 == 0) first else second }
        val old = ledger.snapshot(descriptors, listOf(entry("tail")))
        val expected = old.toList()
        val before = files(root)
        val result = ledger.persist(old)
        assertTrue(result.size < descriptors.size)
        assertEquals(expected, ledger.snapshot(result, emptyList()))
        assertEquals(descriptors, old.pages)
        assertEquals(expected, old)
        before.forEach { (name, bytes) -> assertEquals(bytes, File(root, ".droide/sessions/.history-pages/$name").readBytes().toList()) }
    }
    @Test fun cachedCorruptSourceIsReverifiedBeforeAnyReplacementPageIsWritten() = withRoot { root ->
        val ledger = SessionHistoryLedger(root)
        val page = ledger.writePage(listOf(entry("old")))
        val other = ledger.writePage(listOf(entry("other")))
        val old = ledger.snapshot(listOf(page) + List(4095) { other }, listOf(entry("tail")))
        old[0] // A stale cached first page would otherwise evade the entire pass.
        val file = File(root, ".droide/sessions/.history-pages/${page.sha256}.json")
        val damaged = file.readBytes().also { it[it.lastIndex - 3] = 'z'.code.toByte() }
        file.writeBytes(damaged)
        val before = files(root)
        val failure = runCatching { ledger.persist(old) }.exceptionOrNull()
        assertTrue(failure is SessionHistoryCorruptionException)
        assertEquals(before, files(root))
        assertEquals(4096, old.pages.size)
    }

    @Test fun invalidOversizedTailIsRejectedWithoutAnyRepackingWrites() = withRoot { root ->
        val ledger = SessionHistoryLedger(root)
        val page = ledger.writePage(listOf(entry("old")))
        val before = files(root)
        val old = ledger.snapshot(List(4096) { page }, listOf(entry("x".repeat(SessionHistoryLedger.MAX_PAGE_BYTES.toInt()))))
        assertNotNull(runCatching { ledger.persist(old) }.exceptionOrNull())
        assertEquals(before, files(root))
    }

    @Test fun smallAppendsKeepExistingPageIdentityAndDoNotRepackEarly() = withRoot { root ->
        val ledger = SessionHistoryLedger(root)
        var pages = emptyList<SessionHistoryPage>()
        repeat(20) { index ->
            val prior = pages
            pages = ledger.persist(ledger.snapshot(pages, listOf(entry("turn $index"))))
            assertEquals(prior, pages.take(prior.size))
            assertEquals(index + 1, pages.size)
        }
        assertEquals(20, pageFiles(root).size)
    }

    @Test fun damagedReplacementPagePreservesSessionAndOriginalHistory() = withRoot { root ->
        val manager = SessionManager(root)
        val session = manager.create("original")
        val item = entry("old")
        val page = manager.historyLedger.writePage(listOf(item))
        val original = manager.historyLedger.snapshot(List(4096) { page }, emptyList())
        manager.save(session.copy(forkHistory = original, forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION))
        val manifest = File(root, ".droide/sessions/${session.id}.json")
        val manifestBefore = manifest.readBytes().toList()
        // Another valid immutable page supplies the exact expected packed name.
        // Damage it to exercise writePage's refusal to replace existing evidence.
        val packed = manager.historyLedger.writePage(List(4096) { item })
        val target = File(root, ".droide/sessions/.history-pages/${packed.sha256}.json")
        target.writeText("damaged existing evidence")
        val before = files(root)
        val history = AgentConversationHistory(manager.historyLedger)
        history.load(manager.get(session.id)!!)
        history.append(entry("tail"))
        assertTrue(runCatching { history.completeExactOrNull() }.exceptionOrNull() is SessionHistoryCorruptionException)
        assertEquals(manifestBefore, manifest.readBytes().toList())
        assertEquals(before, files(root))
        assertEquals(4096, manager.get(session.id)!!.forkHistory.size)
        assertEquals(original, manager.get(session.id)!!.forkHistory)
        assertTrue("Failed checkpoint retains the live tail", history.hotHistoryBytes > 0)
    }

    @Test fun actualEntryAndDeclaredByteLimitsRemainFailClosedWithoutNewPages() = withRoot { root ->
        val ledger = SessionHistoryLedger(root)
        val page = ledger.writePage(listOf(entry("old")))
        val before = files(root)
        // Declared aggregate capacity is checked without trusting or decoding
        // oversized metadata. No fake page is treated as verified content.
        val byteFull = ledger.snapshot(List(4096) { page.copy(bytes = SessionHistoryLedger.MAX_HISTORY_BYTES / 4096) }, listOf(entry("tail")))
        assertTrue(runCatching { ledger.persist(byteFull) }.exceptionOrNull() is IllegalArgumentException)
        val oversizedEntries = object : AbstractList<JsonObject>() {
            override val size = SessionHistoryLedger.MAX_HISTORY_ENTRIES.toInt() + 1
            override fun get(index: Int): JsonObject = error("Oversized list must be rejected before reading")
        }
        assertTrue(runCatching { ledger.persist(oversizedEntries) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(before, files(root))
    }

    @Test fun singletonArrayFramingIsAdmittedBeforeEarlierBatchesCanWrite() = withRoot { root ->
        val ledger = SessionHistoryLedger(root)
        val page = ledger.writePage(listOf(entry("old")))
        val before = files(root)
        val fixed = SessionJsonSize.estimatedBytes(entry(""))
        val tooLarge = entry("x".repeat((SessionHistoryLedger.MAX_PAGE_BYTES - fixed).toInt()))
        assertEquals(SessionHistoryLedger.MAX_PAGE_BYTES, SessionJsonSize.estimatedBytes(tooLarge))
        val proposal = ledger.snapshot(List(4096) { page }, listOf(entry("would be written"), tooLarge))
        assertTrue(runCatching { ledger.persist(proposal) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(before, files(root))
    }

    @Test fun rejectedManifestPublicationLeavesOldSessionAndSupportsExactRetry() = withRoot { root ->
        val manager = SessionManager(root)
        val session = manager.create("old session")
        val item = entry("old")
        val page = manager.historyLedger.writePage(listOf(item))
        val old = manager.historyLedger.snapshot(List(4096) { page }, emptyList())
        manager.save(session.copy(forkHistory = old, forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION))
        val manifest = File(root, ".droide/sessions/${session.id}.json")
        val originalBytes = manifest.readBytes().toList()
        val before = files(root)
        val proposed = manager.historyLedger.snapshot(old.pages, listOf(entry("tail")))
        val failures = listOf(java.io.IOException("publication unavailable"), kotlinx.coroutines.CancellationException("publication cancelled"))
        for (failure in failures) {
            var reached = false
            val caught = runCatching { manager.update(session.id, commitAdmission = {
                reached = true
                assertTrue("Repacked pages precede manifest publication", pageFiles(root).size > before.size)
                throw failure
            }) { it!!.copy(forkHistory = proposed, forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION) } }.exceptionOrNull()
            assertTrue(reached)
            assertEquals(failure.message, caught?.message)
            assertEquals(originalBytes, manifest.readBytes().toList())
            assertEquals(old, SessionManager(root).get(session.id)!!.forkHistory)
            before.forEach { (name, bytes) -> assertEquals(bytes, File(root, ".droide/sessions/.history-pages/$name").readBytes().toList()) }
        }
        val staged = files(root)
        manager.update(session.id) { it!!.copy(forkHistory = proposed, forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION) }
        assertEquals(proposed, SessionManager(root).get(session.id)!!.forkHistory)
        assertEquals("Retry reuses exactly the staged immutable pages", staged, files(root))
    }

}
