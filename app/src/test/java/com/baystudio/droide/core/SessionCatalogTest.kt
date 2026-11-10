package com.baystudio.droide.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** Exercises SessionDrawer's actual API without changing its presentation or restoring cold history. */
class SessionCatalogTest {
    private fun <T> workspace(block: suspend (File) -> T): T = runBlocking {
        val root = Files.createTempDirectory("droide-complete-catalog-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun primary(root: File, id: String) = root.resolve(".droide/sessions/$id.json")
    private fun meta(root: File, id: String) = root.resolve(".droide/sessions/$id.meta")
    private suspend fun save(manager: SessionManager, index: Int, createdAt: Long = index.toLong()): DroideSession =
        DroideSession("s-${index.toString().padStart(5, '0')}", "Conversation $index", createdAt = createdAt,
            forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION).also { manager.save(it) }

    @Test fun all101RowsKeepOldestReopenAndForkReachableThroughExistingApis() = workspace { root ->
        val manager = SessionManager(root)
        val history = listOf(buildJsonObject { put("role", "user"); put("content", "Keep this exact old request") })
        val oldest = manager.create("Important old conversation").copy(createdAt = 1,
            messages = listOf(AgentMessage("user", "Keep this exact old request")),
            apiHistory = history, forkHistory = history)
        manager.save(oldest)
        assertTrue(primary(root, oldest.id).setLastModified(1_000))
        repeat(100) { save(manager, it, it + 2L) }
        val reopened = SessionManager(root)
        val summaries = reopened.listSummaries()
        println("CATALOG101 durable=101 summaries=${summaries.size} oldest=${summaries.any { it.id == oldest.id }}")
        assertEquals(101, summaries.size)
        val selected = summaries.last()
        assertEquals(oldest.id, selected.id)
        assertEquals(history, reopened.get(selected.id)!!.forkHistory)
        val child = reopened.fork(selected.id)!!
        assertEquals(oldest.id, child.parentId)
        assertEquals(history, reopened.get(child.id)!!.forkHistory)
        assertEquals(102, reopened.count())
    }

    @Test fun thousandRowsAreCompleteDeterministicAndCachedWhileFullListStaysAt20() = workspace { root ->
        val manager = SessionManager(root)
        repeat(1_000) { save(manager, it, (it / 10).toLong()) }
        val sourceBytes = primary(root, "s-00000").readBytes()
        val reads = AtomicInteger()
        val reopened = SessionManager(root).apply {
            textEditIoProbe = { event, _ -> if (event == TextEditIo.SESSION_READ) reads.incrementAndGet() }
        }
        val summaries = reopened.listSummaries()
        assertEquals(1_000, summaries.size)
        assertEquals(1_000, summaries.map { it.id }.toSet().size)
        assertEquals(summaries.sortedWith(compareByDescending<SessionSummary> { it.createdAt }.thenBy { it.id }), summaries)
        assertEquals(summaries, reopened.listSummaries())
        assertEquals(summaries, SessionManager(root).listSummaries())
        assertEquals(1_000, reopened.count())
        assertEquals("Healthy sidecars must not decode primary manifests", 0, reads.get())
        assertEquals("s-00990", summaries.first().id)
        assertEquals("s-00009", summaries.last().id)
        assertEquals(20, reopened.list().size)
        assertEquals("Full-payload compatibility path stays bounded", 20, reads.get())
        assertArrayEquals(sourceBytes, primary(root, "s-00000").readBytes())
        println("CATALOG1000 rows=${summaries.size} cached_primary_reads=0 full_payload_reads=${reads.get()}")
    }

    @Test fun missingAndStaleSidecarsRepairOneManifestAtATimeWithoutColdRestore() = workspace { root ->
        val manager = SessionManager(root)
        val old = save(manager, 0)
        manager.save(old.copy(messages = listOf(AgentMessage("user", "cold history")),
            forkHistory = listOf(buildJsonObject { put("role", "user"); put("content", "x".repeat(600_000)) })))
        val page = root.resolve(".droide/sessions/.history-pages").listFiles()!!.single { it.extension == "json" }
        page.writeText("corrupted cold history fixture; preserve this evidence")
        repeat(100) { save(manager, it + 1) }
        assertTrue(meta(root, old.id).delete())
        for (i in 1..99) assertTrue(meta(root, "s-${i.toString().padStart(5, '0')}").delete())
        val stale = primary(root, "s-00100")
        stale.writeText(stale.readText().replace("Conversation 100", "Changed outside manager"))
        val sourceBefore = root.resolve(".droide/sessions").listFiles()!!.filter { it.extension == "json" }
            .associate { it.name to it.readBytes() }
        val reads = AtomicInteger()
        val repairedWrites = AtomicInteger()
        var maxUnrepaired = 0
        manager.textEditIoProbe = { event, _ ->
            if (event == TextEditIo.SESSION_READ) {
                reads.incrementAndGet()
                maxUnrepaired = maxOf(maxUnrepaired, reads.get() - repairedWrites.get())
            }
            if (event == TextEditIo.INDEX_WRITE) repairedWrites.incrementAndGet()
        }
        val summaries = manager.listSummaries()
        assertEquals(101, summaries.size)
        assertEquals("Changed outside manager", summaries.first { it.id == "s-00100" }.title)
        assertEquals(101, reads.get())
        assertEquals(101, repairedWrites.get())
        assertEquals("Repair must not retain a batch of decoded manifests", 1, maxUnrepaired)
        assertEquals(summaries, manager.listSummaries())
        assertEquals(101, reads.get())
        sourceBefore.forEach { (name, bytes) -> assertArrayEquals(bytes, root.resolve(".droide/sessions/$name").readBytes()) }
        assertEquals("corrupted cold history fixture; preserve this evidence", page.readText())
        assertThrows(SessionHistoryCorruptionException::class.java) { runBlocking { manager.get(old.id) } }
        Unit
    }

    @Test fun invalidFilesAliasesAndSymlinksNeverCreateRowsOrOverwriteOtherEvidence() = workspace { root ->
        val manager = SessionManager(root)
        val valid = save(manager, 0)
        val dir = primary(root, valid.id).parentFile
        val realMeta = meta(root, valid.id).readBytes()
        File(dir, "s-invalid.json").writeText("not json")
        File(dir, "s-empty.json").writeText("")
        File(dir, "s-directory.json").mkdir()
        File(dir, "s-alias.json").writeBytes(primary(root, valid.id).readBytes())
        File(dir, "s-control\n.json").writeBytes(primary(root, valid.id).readBytes())
        RandomAccessFile(File(dir, "s-oversize.json"), "rw").use { it.setLength(5L * 1024 * 1024 + 1) }
        Files.createSymbolicLink(File(dir, "s-link.json").toPath(), primary(root, valid.id).toPath())
        val symlinkMetaOwner = save(manager, 1)
        assertTrue(meta(root, symlinkMetaOwner.id).delete())
        val sentinel = root.resolve("sidecar-target").apply { writeText("do not follow or replace") }
        Files.createSymbolicLink(meta(root, symlinkMetaOwner.id).toPath(), sentinel.toPath())
        val blocked = save(manager, 2)
        assertTrue(meta(root, blocked.id).delete()); assertTrue(meta(root, blocked.id).mkdir())
        val obstruction = File(meta(root, blocked.id), "user-evidence").apply { writeText("preserve") }
        val brokenMeta = save(manager, 3)
        meta(root, brokenMeta.id).writeText("x".repeat(65_537))
        val summaries = manager.listSummaries()
        assertEquals(listOf("s-00003", "s-00002", "s-00001", "s-00000"), summaries.map { it.id })
        assertEquals(4, manager.count())
        assertNull(manager.getSummary("s-alias"))
        assertNull(manager.getSummary("s-link"))
        assertFalse(File(dir, "s-alias.meta").exists())
        assertArrayEquals(realMeta, meta(root, valid.id).readBytes())
        assertTrue(Files.isSymbolicLink(meta(root, symlinkMetaOwner.id).toPath()))
        assertEquals("do not follow or replace", sentinel.readText())
        assertEquals("preserve", obstruction.readText())
        assertTrue(File(dir, "s-invalid.json").exists())
        assertTrue(File(dir, "s-oversize.json").exists())
        assertTrue(Files.isSymbolicLink(File(dir, "s-link.json").toPath()))
    }

    @Test fun cancellationDuringRepairStopsBeforeWritingCacheOrReadingNextManifest() = workspace { root ->
        val manager = SessionManager(root)
        repeat(101) { val s = save(manager, it); assertTrue(meta(root, s.id).delete()) }
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        val owner = SupervisorJob()
        manager.textEditIoProbe = { event, _ ->
            if (event == TextEditIo.SESSION_READ) { reads.incrementAndGet(); owner.cancel() }
            if (event == TextEditIo.INDEX_WRITE) writes.incrementAndGet()
        }
        val call = kotlinx.coroutines.CoroutineScope(owner + Dispatchers.IO).async { manager.listSummaries() }
        try {
            assertThrows(CancellationException::class.java) { runBlocking { withTimeout(5_000) { call.await() } } }
            assertEquals(1, reads.get())
            assertEquals(0, writes.get())
            assertEquals(101, primary(root, "s-00000").parentFile.listFiles()!!.count { it.extension == "json" })
            assertEquals(0, primary(root, "s-00000").parentFile.listFiles()!!.count { it.extension == "meta" })
        } finally { owner.cancel() }
    }

    @Test fun repairHoldsWriterMutexUntilItsCachePublicationAndCannotStampOldBodyAsNew() = workspace { root ->
        val repairing = SessionManager(root)
        val saved = save(repairing, 0)
        assertTrue(meta(root, saved.id).delete())
        val readFinished = CountDownLatch(1)
        val release = CountDownLatch(1)
        repairing.textEditIoProbe = { event, _ -> if (event == TextEditIo.SESSION_READ) {
            readFinished.countDown(); check(release.await(5, TimeUnit.SECONDS))
        } }
        val owner = SupervisorJob()
        val scope = kotlinx.coroutines.CoroutineScope(owner + Dispatchers.IO)
        val listing = scope.async { repairing.listSummaries() }
        try {
            assertTrue(readFinished.await(5, TimeUnit.SECONDS))
            assertTrue("Repair must serialize with save and delete", SessionWriteTransactions.forWorkspace(root.canonicalPath).entry(saved.id).mutex.isLocked)
            val saver = SessionManager(root)
            val attempted = CompletableDeferred<Unit>()
            val updating = scope.async { attempted.complete(Unit); saver.save(saved.copy(title = "New authoritative title")) }
            attempted.await()
            release.countDown()
            listing.await(); updating.await()
            assertEquals("New authoritative title", SessionManager(root).getSummary(saved.id)!!.title)
            assertEquals("New authoritative title", SessionManager(root).listSummaries().single().title)
        } finally { release.countDown(); listing.join(); owner.cancel() }
    }

    @Test fun oversizedFallbackMetadataStaysSmallAndPreservesFullAuthoritativeValues() = workspace { root ->
        val manager = SessionManager(root)
        val session = DroideSession("s-large-metadata", "t".repeat(600_000), createdAt = 42,
            parentId = "p".repeat(600_000))
        manager.save(session)
        val bytes = primary(root, session.id).readBytes()
        val summary = manager.listSummaries().single()
        assertTrue("Catalog cannot retain transcript-sized title strings", summary.title.length <= 1_024)
        assertNull("Invalid parent ID cannot address a valid saved session", summary.parentId)
        assertArrayEquals(bytes, primary(root, session.id).readBytes())
        assertEquals(session.title, manager.get(session.id)!!.title)
        assertEquals(session.parentId, manager.get(session.id)!!.parentId)
        assertTrue(meta(root, session.id).length() <= 65_536)
    }

    @Test fun cachedLegacyMetadataIsBoundedAndTruncationDoesNotSplitSurrogatePairs() = workspace { root ->
        val manager = SessionManager(root)
        val saved = save(manager, 0)
        val oldCache = Json.parseToJsonElement(meta(root, saved.id).readText()).jsonObject
        val legacyCache = buildJsonObject {
            oldCache.forEach { (key, value) -> if (key != "title" && key != "parentId") put(key, value) }
            put("title", "a".repeat(1_023) + "\uD83D\uDE00" + "b".repeat(5_000))
            put("parentId", "p".repeat(5_000))
        }.toString()
        meta(root, saved.id).writeText(legacyCache)
        val source = primary(root, saved.id).readBytes()
        val reads = AtomicInteger()
        manager.textEditIoProbe = { event, _ -> if (event == TextEditIo.SESSION_READ) reads.incrementAndGet() }
        val row = manager.listSummaries().single()
        assertEquals("a".repeat(1_023), row.title)
        assertNull(row.parentId)
        assertEquals(0, reads.get())
        assertArrayEquals(source, primary(root, saved.id).readBytes())
        assertEquals(legacyCache, meta(root, saved.id).readText())
        assertEquals(saved.title, manager.get(saved.id)!!.title)
    }

    @Test fun cancellationWhileWaitingForWriterDoesNotStealItsLockOrRepairMetadata() = workspace { root ->
        val manager = SessionManager(root)
        val saved = save(manager, 0)
        assertTrue(meta(root, saved.id).delete())
        val entry = SessionWriteTransactions.forWorkspace(root.canonicalPath).entry(saved.id)
        entry.mutex.lock()
        val owner = SupervisorJob()
        val waiting = kotlinx.coroutines.CoroutineScope(owner + Dispatchers.IO).async { manager.getSummary(saved.id) }
        try {
            owner.cancel()
            assertThrows(CancellationException::class.java) { runBlocking { withTimeout(5_000) { waiting.await() } } }
            assertTrue(entry.mutex.isLocked)
            assertFalse(meta(root, saved.id).exists())
        } finally { entry.mutex.unlock(); owner.cancel() }
        assertEquals(saved.id, manager.getSummary(saved.id)!!.id)
    }

    @Test fun absentSessionDirectoryIsAnEmptyCatalogWithoutCreatingStorage() = workspace { root ->
        val manager = SessionManager(root)
        assertTrue(manager.listSummaries().isEmpty())
        assertEquals(0, manager.count())
        assertTrue(manager.list().isEmpty())
        assertFalse(root.resolve(".droide/sessions").exists())
    }
}
