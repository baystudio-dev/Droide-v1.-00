package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class SessionHistoryLedgerTest {
    private fun entry(index: Int, characters: Int = 100_000): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", "$index:" + "x".repeat(characters))
    }
    private fun <T> withRoot(block: suspend (File) -> T): T = runBlocking {
        val root = Files.createTempDirectory("droide-exact-ledger-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun longHistorySurvivesSpillCheckpointRestartAndLatestForkWithoutGrowingTheMainJson() = withRoot { root ->
        val manager = SessionManager(root)
        val original = manager.create("Long professional task")
        val history = AgentConversationHistory(manager.historyLedger)
        val expected = (0 until 60).map { entry(it) }
        val checkpoint = buildJsonObject { put("role", "assistant"); put("content", "[Historical conversation checkpoint; all prior decisions retained]") }
        repeat(20) { turn ->
            history.append(expected.subList(turn * 3, turn * 3 + 3))
            history.active.clear(); history.active += checkpoint
            val exact = history.completeExactOrNull()!!
            manager.save(original.copy(messages = listOf(AgentMessage("assistant", "Task checkpoint retained")), apiHistory = listOf(checkpoint), forkHistory = exact,
                forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION))
            assertEquals(0L, history.hotHistoryBytes)
        }
        val jsonFile = File(root, ".droide/sessions/${original.id}.json")
        assertTrue("Cold history must not remain in the main session JSON", jsonFile.length() < 32_000)
        val restored = SessionManager(root).get(original.id)!!
        assertEquals(expected, restored.forkHistory)
        assertEquals(listOf(checkpoint), restored.apiHistory)
        val lazy = restored.forkHistory as SessionHistoryLedger.Snapshot
        assertTrue(lazy.pages.size > 2)
        assertTrue(lazy.cachedPageCount <= 2)
        val forked = manager.fork(original.id)!!
        assertEquals(expected, forked.forkHistory)
        assertEquals("Latest fork keeps the parent's actual compacted model context", listOf(checkpoint), forked.apiHistory)
        assertEquals(expected, SessionManager(root).get(forked.id)!!.forkHistory)
    }

    @Test fun twentyAppendSaveCyclesSealAndAdoptPagesWithoutPartialTailOrphans() = withRoot { root ->
        val manager = SessionManager(root)
        val session = manager.create()
        val history = AgentConversationHistory(manager.historyLedger)
        repeat(20) { index ->
            history.append(entry(index, 1_024))
            val exact = history.completeExactOrNull()!!
            manager.save(session.copy(forkHistory = exact, forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION))
            manager.save(session.copy(forkHistory = history.completeExactOrNull()!!, forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION))
        }
        val restored = manager.get(session.id)!!.forkHistory as SessionHistoryLedger.Snapshot
        val pagesOnDisk = File(root, ".droide/sessions/.history-pages").listFiles()!!.filter { it.extension == "json" }
        assertEquals(20, restored.pages.size)
        assertEquals(restored.pages.map { it.sha256 + ".json" }.toSet(), pagesOnDisk.map { it.name }.toSet())
        assertEquals(restored.pages.sumOf { it.bytes }, pagesOnDisk.sumOf { it.length() })
    }

    @Test fun rawDescriptorMetadataAndListRoundTripsRetainTheColdPrefix() = withRoot { root ->
        val manager = SessionManager(root)
        val session = manager.create()
        val exact = listOf(entry(0, 600_000), entry(1, 600_000))
        manager.save(session.copy(forkHistory = exact, forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION))
        val raw = manager.getForUpdate(session.id)!!
        assertTrue(raw.forkHistory.isEmpty()); assertTrue(raw.forkHistoryPages.isNotEmpty())
        manager.save(raw.copy(title = "Metadata update"))
        assertEquals(exact, manager.get(session.id)!!.forkHistory)
        val listed = manager.list().single()
        assertEquals(exact, listed.forkHistory)
        manager.save(listed.copy(mode = AgentMode.PLAN))
        assertEquals(exact, manager.get(session.id)!!.forkHistory)
        manager.savePendingInputs(session.id, emptyList())
        assertEquals(exact, manager.get(session.id)!!.forkHistory)
    }

    @Test fun smallLegacyJsonSessionsStillDecodeWithoutPagesOrFormatMigration() = withRoot { root ->
        val manager = SessionManager(root)
        val session = manager.create()
        val file = File(root, ".droide/sessions/${session.id}.json")
        file.writeText("""{"id":"${session.id}","title":"Legacy","apiHistory":[{"role":"user","content":"original"}],"forkHistory":[{"role":"user","content":"original"}],"forkHistoryVersion":1}""")
        val restored = manager.get(session.id)!!
        assertTrue(restored.forkHistoryPages.isEmpty())
        val history = AgentConversationHistory(manager.historyLedger)
        history.load(restored)
        history.append(entry(1, 16))
        assertEquals(2, history.completeExactOrNull()!!.size)
        assertEquals(JsonPrimitive("original"), history.active.first()["content"])
    }

    @Test fun historicalForkKeepsExactChosenBoundaryAfterPageSpill() = withRoot { root ->
        val manager = SessionManager(root)
        val session = manager.create()
        val exact = (0..6).map { entry(it) }
        val messages = exact.map { AgentMessage("user", (it["content"] as JsonPrimitive).content) }
        manager.save(session.copy(messages = messages, apiHistory = listOf(exact.last()), forkHistory = exact,
            forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION))
        val historical = manager.fork(session.id, 2)!!
        assertEquals(exact.take(3), historical.apiHistory)
        assertEquals(exact.take(3), historical.forkHistory)
        assertEquals(3, historical.messages.size)
        assertEquals(exact, manager.get(session.id)!!.forkHistory)
    }

    @Test fun corruptOrMissingColdPageFailsExplicitlyWithoutDeletingSessionOrHistory() = withRoot { root ->
        val manager = SessionManager(root)
        val session = manager.create()
        manager.save(session.copy(forkHistory = listOf(entry(0, 600_000)), forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION))
        val page = File(root, ".droide/sessions/.history-pages").listFiles()!!.single()
        val original = page.readBytes()
        val damaged = original.clone().apply { this[size / 2] = 'z'.code.toByte() }
        page.writeBytes(damaged)
        val failure = runCatching { manager.get(session.id) }.exceptionOrNull()
        assertTrue(failure is SessionHistoryCorruptionException)
        assertArrayEquals(damaged, page.readBytes())
        assertTrue(File(root, ".droide/sessions/${session.id}.json").isFile)
        page.delete()
        assertTrue(runCatching { manager.get(session.id) }.exceptionOrNull() is SessionHistoryCorruptionException)
    }

    @Test fun failedMainSessionSerializationLeavesThePreviousCheckpointUnchanged() = withRoot { root ->
        val manager = SessionManager(root)
        val session = manager.create("Preserve before overflow")
        val file = File(root, ".droide/sessions/${session.id}.json")
        val before = file.readBytes()
        val failure = runCatching { manager.save(session.copy(apiHistory = listOf(entry(1, 6 * 1024 * 1024)))) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertArrayEquals(before, file.readBytes())
        assertEquals("Preserve before overflow", manager.get(session.id)!!.title)
        assertFalse(file.parentFile!!.listFiles()!!.any { it.extension == "tmp" || ".tmp-" in it.name })
    }

    @Test fun streamingAdmissionRejectsTheOverflowByteBeforeWritingAndBoundsGrowingInput() {
        val bytes = ByteArrayOutputStream()
        val output = BoundedSessionOutput(bytes, 4)
        output.write(byteArrayOf(1, 2, 3, 4))
        assertTrue(runCatching { output.write(5) }.exceptionOrNull() is IOException)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes.toByteArray())
        val input = BoundedSessionInput(ByteArrayInputStream(ByteArray(64)), 4)
        assertTrue(runCatching { input.read(ByteArray(64)) }.exceptionOrNull() is IOException)
        assertEquals(5L, input.bytesRead)
    }

    @Test fun pageCapacityRepackingPreservesExistingImmutablePages() = withRoot { root ->
        val ledger = SessionHistoryLedger(root)
        val page = ledger.writePage(listOf(entry(0, 10)))
        val full = ledger.snapshot(List(SessionHistoryLedger.MAX_HISTORY_PAGES) { page }, listOf(entry(1, 10)), true)
        val dir = File(root, ".droide/sessions/.history-pages")
        val before = dir.listFiles()!!.map { it.name }.toSet()
        val repacked = ledger.persist(full)
        assertTrue(repacked.size < full.pages.size)
        assertEquals(full, ledger.snapshot(repacked, emptyList()))
        assertTrue(dir.listFiles()!!.map { it.name }.toSet().containsAll(before))
    }

    @Test fun checkpointStorageFailurePausesAdmissionThenRecoversTheSamePendingHistory() = withRoot { root ->
        val manager = SessionManager(root)
        val blocker = File(root, ".droide/sessions/.history-pages").apply { parentFile!!.mkdirs(); writeText("blocked") }
        val history = AgentConversationHistory(manager.historyLedger)
        history.append(entry(0, 16))
        assertTrue(runCatching { history.completeExactOrNull() }.isFailure)
        history.append(entry(1, 16))
        assertTrue(runCatching { history.ensureStorageAvailable() }.exceptionOrNull() is IOException)
        assertEquals(2, history.active.size)
        blocker.delete(); blocker.mkdirs()
        history.ensureStorageAvailable()
        assertEquals(listOf(entry(0, 16), entry(1, 16)), history.completeExactOrNull())
        assertEquals(0L, history.hotHistoryBytes)
    }

    @Test fun concurrentAppendOwnershipPreservesEveryEntryExactlyOnce() = withRoot { root ->
        val manager = SessionManager(root)
        val history = AgentConversationHistory(manager.historyLedger)
        coroutineScope { (0 until 100).map { index -> async(kotlinx.coroutines.Dispatchers.Default) { history.append(entry(index, 100)) } }.forEach { it.await() } }
        val exact = history.completeExactOrNull()!!
        assertEquals(100, exact.size)
        assertEquals((0 until 100).map { entry(it, 100) }.toSet(), exact.toSet())
        assertEquals(100, history.active.size)
    }

    @Test fun staleRestorePublicationCannotOverwriteTheCurrentContext() = withRoot { root ->
        val manager = SessionManager(root)
        val history = AgentConversationHistory(manager.historyLedger)
        val current = entry(0, 16)
        history.append(current)
        val other = DroideSession("s-other", "Other", apiHistory = listOf(entry(1, 16)),
            forkHistory = listOf(entry(1, 16)), forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION)
        assertTrue(runCatching { history.load(other, shouldPublish = { false }) }.exceptionOrNull() is CancellationException)
        assertEquals(listOf(current), history.active)
        assertEquals(listOf(current), history.completeExactOrNull())
    }

    @Test fun resetInsideRestoreGuardInvalidatesPublicationAtomically() = withRoot { root ->
        val manager = SessionManager(root)
        val history = AgentConversationHistory(manager.historyLedger)
        history.append(entry(0, 16))
        val other = DroideSession("s-other", "Other", apiHistory = listOf(entry(1, 16)),
            forkHistory = listOf(entry(1, 16)), forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION)
        val failure = runCatching { history.load(other, shouldPublish = { history.resetNew(); true }) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(history.active.isEmpty())
        assertTrue(history.completeExactOrNull()!!.isEmpty())
    }

    @Test fun snapshotRetainsListEqualityAndCannotChangeWhenOwnerAppendsMoreEntries() = withRoot { root ->
        val manager = SessionManager(root)
        val history = AgentConversationHistory(manager.historyLedger)
        history.append(entry(0, 16))
        val snapshot = history.completeExactOrNull()!!
        history.append(entry(1, 16))
        assertEquals(listOf(entry(0, 16)), snapshot)
        assertEquals(snapshot, listOf(entry(0, 16)))
        assertEquals(1, snapshot.size)
        assertEquals(2, history.completeExactOrNull()!!.size)
    }

    @Test fun deeplyNestedNewHistoryIsRejectedDuringAdmissionWithoutStackOverflowOrFiles() = withRoot { root ->
        var nested: kotlinx.serialization.json.JsonElement = JsonPrimitive("leaf")
        repeat(1_000) { nested = buildJsonObject { put("nested", nested) } }
        val entry = buildJsonObject { put("role", "user"); put("content", nested) }
        val ledger = SessionHistoryLedger(root)
        assertTrue(SessionJsonSize.estimatedBytes(entry) > SessionHistoryLedger.MAX_PAGE_BYTES)
        assertTrue(runCatching { ledger.persist(listOf(entry)) }.exceptionOrNull() is IllegalArgumentException)
        assertFalse(File(root, ".droide/sessions/.history-pages").exists())
    }

    @Test fun corruptDeepRestoreJsonCannotReachAnUnboundedRecursiveParser() = withRoot { root ->
        val manager = SessionManager(root)
        val session = manager.create()
        val file = File(root, ".droide/sessions/${session.id}.json")
        val payload = "{\"id\":\"${session.id}\",\"title\":\"Deep\",\"forkHistory\":[" +
            "{\"nested\":".repeat(1_000) + "0" + "}".repeat(1_000) + "]}"
        file.writeText(payload)
        assertNull(manager.get(session.id))
        assertEquals(payload, file.readText())
        val quoted = "{\"content\":\"" + "[".repeat(1_000) + "\\\"{}\\\"\"}"
        val bounded = BoundedSessionInput(ByteArrayInputStream(quoted.toByteArray()), quoted.toByteArray().size.toLong())
        assertEquals(quoted, bounded.readBytes().toString(Charsets.UTF_8))
    }

    @Test fun supersededRestoreTicketCannotInvalidateTheNewerAdmittedRestore() = withRoot { root ->
        val history = AgentConversationHistory(SessionHistoryLedger(root))
        val older = history.beginRestore()
        val newer = history.beginRestore()
        val incoming = DroideSession("s-current", "Current", apiHistory = listOf(entry(0, 16)), forkHistory = listOf(entry(0, 16)),
            forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION)
        assertTrue(runCatching { history.load(incoming, restoreTicket = older) }.exceptionOrNull() is CancellationException)
        history.load(incoming, restoreTicket = newer)
        assertEquals(incoming.apiHistory, history.active)
    }

    @Test fun preparedRestoreCannotReplaceContextWhenNewerTransactionFailsBeforeCommit() = withRoot { root ->
        val history = AgentConversationHistory(SessionHistoryLedger(root))
        history.append(entry(0, 16))
        val newerFailure = DroideSession("s-failing", "Fails", forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION,
            forkHistory = object : AbstractList<JsonObject>() { override val size = 1; override fun get(index: Int): JsonObject = throw IOException("Synthetic failure") })
        val incoming = DroideSession("s-obsolete", "Prepared", apiHistory = listOf(entry(1, 16)), forkHistory = listOf(entry(1, 16)),
            forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION)
        val obsolete = runCatching {
            history.load(incoming, publishTransaction = { commit ->
                assertTrue(runBlocking { runCatching { history.load(newerFailure) } }.exceptionOrNull() is IOException)
                commit()
            })
        }.exceptionOrNull()
        assertTrue(obsolete is CancellationException)
        assertEquals(listOf(entry(0, 16)), history.active)
        assertEquals(listOf(entry(0, 16)), history.completeExactOrNull())
    }

    @Test fun metadataAuthorityCannotOverwritePresentDamagedSessionAsIfItWereMissing() = withRoot { root ->
        val manager = SessionManager(root)
        val session = manager.create()
        val file = File(root, ".droide/sessions/${session.id}.json")
        val original = "{damaged-session-metadata"
        file.writeText(original)
        assertTrue(runCatching { manager.getForUpdate(session.id) }.exceptionOrNull() is SessionHistoryCorruptionException)
        assertTrue(runCatching { manager.savePendingInputs(session.id, emptyList()) }.exceptionOrNull() is SessionHistoryCorruptionException)
        assertEquals(original, file.readText())
        assertNull(manager.getForUpdate("s-absent"))
    }

    @Test fun retiredCompactionReferenceCannotModifyRestoredModelHistory() = withRoot { root ->
        val history = AgentConversationHistory(SessionHistoryLedger(root))
        history.append(entry(0, 16))
        val retired = history.active
        val replacement = DroideSession("s-current", "Current", apiHistory = listOf(entry(1, 16)), forkHistory = listOf(entry(1, 16)),
            forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION)
        history.load(replacement)
        retired.clear(); retired += entry(2, 16)
        assertEquals(replacement.apiHistory, history.active)
        assertEquals(replacement.forkHistory, history.completeExactOrNull())
    }
}
