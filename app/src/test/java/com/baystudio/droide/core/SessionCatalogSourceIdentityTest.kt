package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/** Actual managed-save/cache-repair fixtures. Retain all fixture evidence for review. */
class SessionCatalogSourceIdentityTest {
    private fun <T> workspace(block: suspend (File) -> T): T = runBlocking {
        val root = Files.createTempDirectory("session-source-identity-").toFile()
        println("SOURCE_IDENTITY_FIXTURE=${root.path}")
        block(root)
    }

    private fun primary(root: File, id: String) = root.resolve(".droide/sessions/$id.json")
    private fun meta(root: File, id: String) = root.resolve(".droide/sessions/$id.meta")
    private fun stamp(file: File) = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    private fun preserveFailedIndex(root: File, id: String, name: String) {
        val temporary = root.resolve(".droide/sessions").listFiles()!!
            .single { it.name.startsWith(".$id.meta.tmp-") }
        Files.move(temporary.toPath(), root.resolve(name).toPath())
    }

    private fun makeLegacy(meta: File) {
        val original = Json.parseToJsonElement(meta.readText()).jsonObject
        meta.writeText(JsonObject(original.filterKeys { it != "sourceKey" && it != "sourceModifiedTime" } + mapOf(
            "title" to JsonPrimitive("Obsolete cache"), "createdAt" to JsonPrimitive(999),
            "parentId" to JsonPrimitive("s-obsolete"), "messageCount" to JsonPrimitive(999),
            "mode" to JsonPrimitive("REVIEW"),
        )).toString())
    }

    private fun assertSummary(session: DroideSession, summary: SessionSummary?) {
        assertEquals(SessionSummary(session.id, session.title, session.createdAt, session.parentId,
            session.messages.size, session.mode), summary)
    }

    @Test fun repeatedManagedSavesWithIdenticalPreciseTimeUsePrimaryUntilRepairCanPublish() = workspace { root ->
        val manager = SessionManager(root)
        val original = DroideSession("s-repeated", "Before", createdAt = 111, parentId = "s-old-parent",
            messages = listOf(AgentMessage("user", "prior")))
        manager.save(original)
        val file = primary(root, original.id)
        val cache = meta(root, original.id)
        val oldStamp = stamp(file)
        val oldCache = cache.readBytes()
        val failedWrites = AtomicInteger()
        manager.textEditIoProbe = { event, _ ->
            if (event == TextEditIo.SESSION_WRITE) {
                val temporary = file.parentFile!!.listFiles()!!.single { it.name.startsWith(".${original.id}.json.tmp-") }
                Files.setLastModifiedTime(temporary.toPath(), oldStamp.lastModifiedTime())
            }
            if (event == TextEditIo.INDEX_WRITE)
                preserveFailedIndex(root, original.id, "retained-failed-save-${failedWrites.incrementAndGet()}")
        }
        var latest = original
        listOf("After!", "Latest").forEachIndexed { index, title ->
            // Keeping old fixture inodes alive makes replacement-key assertions deterministic.
            Files.createLink(root.resolve("retained-primary-$index").toPath(), file.toPath())
            latest = original.copy(title = title, createdAt = 222L + index * 111,
                parentId = "s-new-parent", messages = listOf(AgentMessage("user", "later")))
            manager.save(latest)
            val committed = stamp(file)
            assertEquals(oldStamp.size(), committed.size())
            assertEquals(oldStamp.lastModifiedTime(), committed.lastModifiedTime())
            assertNotEquals(oldStamp.fileKey(), committed.fileKey())
            assertArrayEquals(oldCache, cache.readBytes())
        }
        assertEquals(2, failedWrites.get())
        manager.textEditIoProbe = null
        assertEquals(latest, SessionManager(root).get(latest.id))
        val committedBytes = file.readBytes()
        val reads = AtomicInteger()
        val repairWrites = AtomicInteger()
        val reader = SessionManager(root).apply {
            textEditIoProbe = { event, _ ->
                if (event == TextEditIo.SESSION_READ) reads.incrementAndGet()
                if (event == TextEditIo.INDEX_WRITE)
                    preserveFailedIndex(root, original.id, "retained-failed-repair-${repairWrites.incrementAndGet()}")
            }
        }
        assertSummary(latest, reader.getSummary(latest.id))
        assertSummary(latest, reader.listSummaries().single())
        assertEquals(2, reads.get())
        assertEquals(2, repairWrites.get())
        assertArrayEquals(oldCache, cache.readBytes())
        assertArrayEquals(committedBytes, file.readBytes())
        reader.textEditIoProbe = { event, _ ->
            if (event == TextEditIo.SESSION_READ) reads.incrementAndGet()
            if (event == TextEditIo.INDEX_WRITE) repairWrites.incrementAndGet()
        }
        assertSummary(latest, reader.getSummary(latest.id))
        assertEquals(3, reads.get())
        assertEquals(3, repairWrites.get())
        assertSummary(latest, reader.listSummaries().single())
        val cold = SessionManager(root).apply { textEditIoProbe = { event, _ ->
            if (event == TextEditIo.SESSION_READ) reads.incrementAndGet()
        } }
        assertSummary(latest, cold.getSummary(latest.id))
        assertEquals(3, reads.get())
        assertArrayEquals(committedBytes, file.readBytes())
        assertEquals(4, root.listFiles()!!.count { it.name.startsWith("retained-failed-") })
        println("REPEATED_MANAGED_SAVE exact_time_collision=true fallback_reads=2 repaired_once=true reopened_reads=0")
    }

    @Test fun genuinelyLegacyCacheRepairsAllMetadataOnceAndReopensWithoutPrimaryReads() = workspace { root ->
        val saved = DroideSession("s-legacy", "Current metadata", createdAt = 12, parentId = "s-parent",
            messages = listOf(AgentMessage("user", "Keep this request")))
        SessionManager(root).save(saved)
        makeLegacy(meta(root, saved.id))
        val bytes = primary(root, saved.id).readBytes()
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        val reader = SessionManager(root).apply { textEditIoProbe = { event, _ ->
            if (event == TextEditIo.SESSION_READ) reads.incrementAndGet()
            if (event == TextEditIo.INDEX_WRITE) writes.incrementAndGet()
        } }
        assertSummary(saved, reader.getSummary(saved.id))
        assertSummary(saved, reader.listSummaries().single())
        assertEquals(1, reader.count())
        val cold = SessionManager(root).apply { textEditIoProbe = reader.textEditIoProbe }
        assertSummary(saved, cold.getSummary(saved.id))
        assertEquals(1, reads.get())
        assertEquals(1, writes.get())
        val repaired = Json.parseToJsonElement(meta(root, saved.id).readText()).jsonObject
        assertEquals(stamp(primary(root, saved.id)).fileKey().toString(), (repaired["sourceKey"] as JsonPrimitive).content)
        assertEquals(stamp(primary(root, saved.id)).lastModifiedTime().toString(), (repaired["sourceModifiedTime"] as JsonPrimitive).content)
        assertArrayEquals(bytes, primary(root, saved.id).readBytes())
        println("LEGACY_MIGRATION primary_reads=1 cache_writes=1 reopened_reads=0")
    }

    @Test fun replacementDuringPrimaryDecodeCannotPublishMetadataFromOldBody() = workspace { root ->
        val saved = DroideSession("s-racing", "Before", createdAt = 111)
        val writer = SessionManager(root)
        writer.save(saved)
        val file = primary(root, saved.id)
        val prior = stamp(file)
        makeLegacy(meta(root, saved.id))
        val oldCache = meta(root, saved.id).readBytes()
        val replacement = root.resolve("prepared-replacement.json")
        replacement.writeText(file.readText().replace("Before", "After!"))
        Files.setLastModifiedTime(replacement.toPath(), prior.lastModifiedTime())
        val writes = AtomicInteger()
        var replaced = false
        val reader = SessionManager(root).apply { textEditIoProbe = { event, _ ->
            if (event == TextEditIo.SESSION_READ && !replaced) {
                Files.move(replacement.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                replaced = true
            }
            if (event == TextEditIo.INDEX_WRITE) writes.incrementAndGet()
        } }
        assertNull(reader.getSummary(saved.id))
        assertTrue(replaced)
        assertNotEquals(prior.fileKey(), stamp(file).fileKey())
        assertEquals(prior.lastModifiedTime(), stamp(file).lastModifiedTime())
        assertEquals(0, writes.get())
        assertArrayEquals(oldCache, meta(root, saved.id).readBytes())
        assertSummary(saved.copy(title = "After!"), reader.getSummary(saved.id))
        assertEquals(1, writes.get())
    }

    @Test fun sameInodePreciseTimeChangeDuringDecodeCannotPublishOldMetadata() = workspace { root ->
        val saved = DroideSession("s-precise-race", "Before", createdAt = 111)
        SessionManager(root).save(saved)
        val file = primary(root, saved.id)
        val firstTime = FileTime.from(1_700_000_000_123_100_000L, TimeUnit.NANOSECONDS)
        val secondTime = FileTime.from(1_700_000_000_123_200_000L, TimeUnit.NANOSECONDS)
        Files.setLastModifiedTime(file.toPath(), firstTime)
        assertEquals(firstTime, stamp(file).lastModifiedTime())
        val prior = stamp(file)
        makeLegacy(meta(root, saved.id))
        val oldCache = meta(root, saved.id).readBytes()
        val writes = AtomicInteger()
        var changed = false
        val reader = SessionManager(root).apply { textEditIoProbe = { event, _ ->
            if (event == TextEditIo.SESSION_READ && !changed) {
                file.writeText(file.readText().replace("Before", "After!"))
                Files.setLastModifiedTime(file.toPath(), secondTime)
                changed = true
            }
            if (event == TextEditIo.INDEX_WRITE) writes.incrementAndGet()
        } }
        assertNull(reader.getSummary(saved.id))
        assertTrue(changed)
        assertEquals(prior.fileKey(), stamp(file).fileKey())
        assertEquals(prior.size(), stamp(file).size())
        assertEquals(prior.lastModifiedTime().toMillis(), stamp(file).lastModifiedTime().toMillis())
        assertEquals(secondTime, stamp(file).lastModifiedTime())
        assertEquals(0, writes.get())
        assertArrayEquals(oldCache, meta(root, saved.id).readBytes())
        assertSummary(saved.copy(title = "After!"), reader.getSummary(saved.id))
        assertEquals(1, writes.get())
    }

    @Test fun cancelledLegacyRepairPreservesCacheAndAllowsLaterRetry() = workspace { root ->
        val saved = DroideSession("s-cancelled-legacy", "Still available", createdAt = 13)
        SessionManager(root).save(saved)
        makeLegacy(meta(root, saved.id))
        val oldCache = meta(root, saved.id).readBytes()
        val oldPrimary = primary(root, saved.id).readBytes()
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        val owner = SupervisorJob()
        val reader = SessionManager(root).apply { textEditIoProbe = { event, _ ->
            if (event == TextEditIo.SESSION_READ) { reads.incrementAndGet(); owner.cancel() }
            if (event == TextEditIo.INDEX_WRITE) writes.incrementAndGet()
        } }
        val repair = CoroutineScope(owner + Dispatchers.IO).async { reader.getSummary(saved.id) }
        try {
            assertThrows(CancellationException::class.java) { runBlocking { withTimeout(5_000) { repair.await() } } }
            assertEquals(1, reads.get())
            assertEquals(0, writes.get())
            assertArrayEquals(oldCache, meta(root, saved.id).readBytes())
            assertArrayEquals(oldPrimary, primary(root, saved.id).readBytes())
        } finally { owner.cancel() }
        reader.textEditIoProbe = { event, _ ->
            if (event == TextEditIo.SESSION_READ) reads.incrementAndGet()
            if (event == TextEditIo.INDEX_WRITE) writes.incrementAndGet()
        }
        assertSummary(saved, reader.getSummary(saved.id))
        assertSummary(saved, reader.listSummaries().single())
        assertEquals(2, reads.get())
        assertEquals(1, writes.get())
        assertArrayEquals(oldPrimary, primary(root, saved.id).readBytes())
    }
}
