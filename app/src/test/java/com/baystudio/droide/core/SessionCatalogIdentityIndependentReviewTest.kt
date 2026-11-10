package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SessionCatalogIdentityIndependentReviewTest {
    private fun <T> workspace(block: suspend (File) -> T): T = runBlocking {
        val root = Files.createTempDirectory("f149-independent-identity-").toFile()
        println("INDEPENDENT_FIXTURE_ROOT=${root.path}")
        block(root)
    }

    private fun primary(root: File, id: String) = root.resolve(".droide/sessions/$id.json")
    private fun meta(root: File, id: String) = root.resolve(".droide/sessions/$id.meta")
    private fun attributes(file: File) = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    private fun alterCache(file: File, changes: Map<String, JsonElement?>) {
        val values = Json.parseToJsonElement(file.readText()).jsonObject.toMutableMap()
        changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        values["title"] = JsonPrimitive("Poisoned obsolete display")
        values["createdAt"] = JsonPrimitive(-100L)
        values["parentId"] = JsonPrimitive("s-obsolete-parent")
        file.writeText(JsonObject(values).toString())
    }

    private fun assertCurrent(session: DroideSession, row: SessionSummary?) {
        assertNotNull("Invalid cache identity must not hide a valid primary", row)
        assertEquals(session.id, row!!.id)
        assertEquals(session.title, row.title)
        assertEquals(session.createdAt, row.createdAt)
        assertEquals(session.parentId, row.parentId)
        assertEquals(session.messages.size, row.messageCount)
        assertEquals(session.mode, row.mode)
    }

    @Test fun unavailableIdentityCannotAuthorizeNullOrUnboundedMatches() {
        val time = FileTime.from(Instant.parse("2026-11-04T12:34:56.123456789Z"))
        val invalidKeys: List<Any?> = listOf(null, "", "  ", "key\ncontrol", "key\u0000control", "x".repeat(1_025))
        invalidKeys.forEach { assertNull("Unusable key must be a cache miss: ${it?.toString()?.take(30)}", sessionCacheIdentity(it, time)) }
        val failedProviderKey = object { override fun toString(): String = throw IllegalStateException("Unavailable provider key") }
        assertNull(sessionCacheIdentity(failedProviderKey, time))
        val cancelledProviderKey = object { override fun toString(): String = throw CancellationException("Cancelled provider observation") }
        assertThrows(CancellationException::class.java) { sessionCacheIdentity(cancelledProviderKey, time) }
        val identity = checkNotNull(sessionCacheIdentity("k".repeat(1_024), time))
        assertTrue(identity.matches("k".repeat(1_024), time.toString()))
        assertFalse(identity.matches(null, null))
        assertFalse(identity.matches(identity.sourceKey, null))
        assertFalse(identity.matches(null, identity.sourceModifiedTime))
        assertFalse(identity.matches(identity.sourceKey, "not-a-time"))
        assertFalse(identity.matches(identity.sourceKey + "x", identity.sourceModifiedTime))
        assertFalse(identity.matches(identity.sourceKey, FileTime.fromMillis(time.toMillis()).toString()))
        println("PREDICATE_SCOPE nullable/invalid provider-key representation; not a null-key filesystem integration")
    }

    @Test fun genuineLegacyCacheRepairsOnceThenReopenedManagerUsesNoPrimaryReads() = workspace { root ->
        val session = DroideSession("s-legacy-identity", "Authoritative legacy", createdAt = 47,
            parentId = "s-real-parent", messages = listOf(AgentMessage("user", "retained")))
        SessionManager(root).save(session)
        val source = primary(root, session.id).readBytes()
        alterCache(meta(root, session.id), mapOf("sourceKey" to null, "sourceModifiedTime" to null))
        val reads = AtomicInteger(); val writes = AtomicInteger()
        val reader = SessionManager(root).apply { textEditIoProbe = { event, _ ->
            if (event == TextEditIo.SESSION_READ) reads.incrementAndGet()
            if (event == TextEditIo.INDEX_WRITE) writes.incrementAndGet()
        } }
        assertCurrent(session, reader.getSummary(session.id))
        assertEquals(1, reads.get()); assertEquals(1, writes.get())
        val repaired = meta(root, session.id).readBytes()
        assertCurrent(session, reader.listSummaries().single())
        assertEquals(1, reader.count())
        val reopened = SessionManager(root).apply { textEditIoProbe = reader.textEditIoProbe }
        assertCurrent(session, reopened.getSummary(session.id))
        assertEquals(1, reads.get()); assertEquals(1, writes.get())
        assertArrayEquals(repaired, meta(root, session.id).readBytes())
        assertArrayEquals(source, primary(root, session.id).readBytes())
    }

    @Test fun unavailableKeyPublisherReturnsCurrentMetadataWithoutUselessCacheWrites() = workspace { root ->
        val session = DroideSession("s-null-key-publication", "Readable without key", createdAt = 18)
        val manager = SessionManager(root)
        manager.save(session)
        val file = primary(root, session.id)
        val source = file.readBytes()
        val oldCache = meta(root, session.id).readBytes()
        val stamp = attributes(file)
        // The host provider supplies a real key. Inject only the publisher's source observation, not an
        // artificial production I/O abstraction or a claim of provider integration.
        val stampClass = Class.forName("com.baystudio.droide.core.SessionFileStamp")
        val constructor = stampClass.declaredConstructors.single { it.parameterCount == 4 }.apply { isAccessible = true }
        val unavailableSource = constructor.newInstance(stamp.size(), stamp.lastModifiedTime().toMillis(), null, stamp.lastModifiedTime())
        val writeIndex = SessionManager::class.java.getDeclaredMethod("writeIndex", File::class.java, DroideSession::class.java, stampClass)
            .apply { isAccessible = true }
        val writes = AtomicInteger()
        manager.textEditIoProbe = { event, _ -> if (event == TextEditIo.INDEX_WRITE) writes.incrementAndGet() }
        repeat(2) {
            val index = writeIndex.invoke(manager, file, session, unavailableSource)
            val row = index.javaClass.getDeclaredMethod("summary").apply { isAccessible = true }.invoke(index) as SessionSummary
            assertCurrent(session, row)
        }
        assertEquals(0, writes.get())
        assertArrayEquals(oldCache, meta(root, session.id).readBytes())
        assertArrayEquals(source, file.readBytes())
        println("PUBLISHER_SCOPE null-key observation returns current metadata, preserves existing cache, performs zero writes")
    }

    @Test fun partialMalformedAndOversizedIdentityAlwaysFallsBackAndRepairs() = workspace { root ->
        val cases = listOf(
            mapOf("sourceKey" to null),
            mapOf("sourceModifiedTime" to null),
            mapOf("sourceKey" to JsonNull),
            mapOf("sourceModifiedTime" to JsonNull),
            mapOf("sourceKey" to JsonPrimitive("")),
            mapOf("sourceKey" to JsonPrimitive(" ")),
            mapOf("sourceKey" to JsonPrimitive("wrong-key")),
            mapOf("sourceKey" to JsonPrimitive("x".repeat(1_025))),
            mapOf("sourceKey" to JsonPrimitive("bad\u0000key")),
            mapOf("sourceModifiedTime" to JsonPrimitive("")),
            mapOf("sourceModifiedTime" to JsonPrimitive("not-a-time")),
            mapOf("sourceModifiedTime" to JsonPrimitive("t".repeat(2_000))),
            mapOf("sourceKey" to JsonObject(emptyMap())),
            mapOf("sourceModifiedTime" to JsonObject(emptyMap()))
        )
        for ((index, mutation) in cases.withIndex()) {
            val session = DroideSession("s-invalid-identity-$index", "Authoritative $index", createdAt = 100L + index,
                parentId = "s-current-parent", messages = listOf(AgentMessage("user", "still reachable")))
            SessionManager(root).save(session)
            val source = primary(root, session.id).readBytes()
            alterCache(meta(root, session.id), mutation)
            val reads = AtomicInteger()
            val reader = SessionManager(root).apply { textEditIoProbe = { event, _ ->
                if (event == TextEditIo.SESSION_READ) reads.incrementAndGet()
            } }
            assertCurrent(session, reader.getSummary(session.id))
            assertEquals("Repair needs exactly one body decode for case $index", 1, reads.get())
            assertCurrent(session, reader.getSummary(session.id))
            assertEquals("Repaired cache must be reusable for case $index", 1, reads.get())
            assertArrayEquals(source, primary(root, session.id).readBytes())
        }
        assertEquals(cases.size, SessionManager(root).count())
    }

    @Test fun sameInodeSameSizeSubmillisecondModificationInvalidatesPriorMetadata() = workspace { root ->
        val session = DroideSession("s-precise-time", "Before", createdAt = 321)
        val manager = SessionManager(root)
        manager.save(session)
        val file = primary(root, session.id)
        val firstTime = FileTime.from(Instant.parse("2026-11-04T12:34:56.123456700Z"))
        val secondTime = FileTime.from(Instant.parse("2026-11-04T12:34:56.123456789Z"))
        Files.setLastModifiedTime(file.toPath(), firstTime)
        assertCurrent(session, manager.getSummary(session.id))
        val before = attributes(file)
        file.writeText(file.readText().replace("Before", "After!"))
        Files.setLastModifiedTime(file.toPath(), secondTime)
        val after = attributes(file)
        assertEquals("Host must retain first precise timestamp", firstTime, before.lastModifiedTime())
        assertEquals("Host must retain second precise timestamp", secondTime, after.lastModifiedTime())
        assertEquals(before.fileKey(), after.fileKey())
        assertEquals(before.size(), after.size())
        assertEquals(before.lastModifiedTime().toMillis(), after.lastModifiedTime().toMillis())
        assertNotEquals(before.lastModifiedTime(), after.lastModifiedTime())
        val reads = AtomicInteger()
        val reader = SessionManager(root).apply { textEditIoProbe = { event, _ ->
            if (event == TextEditIo.SESSION_READ) reads.incrementAndGet()
        } }
        val source = file.readBytes()
        assertCurrent(session.copy(title = "After!"), reader.getSummary(session.id))
        assertCurrent(session.copy(title = "After!"), reader.listSummaries().single())
        assertEquals(1, reads.get())
        assertArrayEquals(source, file.readBytes())
        println("PRECISE_TIME_CONTROL key=${after.fileKey()} before=${before.lastModifiedTime()} after=${after.lastModifiedTime()}")
    }

    @Test fun failedLegacyRepairRemainsReadableAndRetryPublishesOnceWithoutRemovingEvidence() = workspace { root ->
        val session = DroideSession("s-failed-repair", "Current despite failure", createdAt = 823)
        SessionManager(root).save(session)
        val source = primary(root, session.id).readBytes()
        val cache = meta(root, session.id)
        alterCache(cache, mapOf("sourceKey" to null, "sourceModifiedTime" to null))
        val oldCache = cache.readBytes()
        val reads = AtomicInteger(); val attempts = AtomicInteger()
        val reader = SessionManager(root).apply { textEditIoProbe = { event, _ ->
            if (event == TextEditIo.SESSION_READ) reads.incrementAndGet()
            if (event == TextEditIo.INDEX_WRITE) {
                val attempt = attempts.incrementAndGet()
                val temporary = cache.parentFile.listFiles()!!.single { it.name.startsWith(".${session.id}.meta.tmp-") }
                Files.move(temporary.toPath(), root.resolve("retained-failed-repair-$attempt").toPath())
            }
        } }
        assertCurrent(session, reader.getSummary(session.id))
        assertCurrent(session, reader.listSummaries().single())
        assertEquals(2, reads.get()); assertEquals(2, attempts.get())
        assertArrayEquals(oldCache, cache.readBytes())
        assertArrayEquals(source, primary(root, session.id).readBytes())
        val evidence = (1..2).associate { it to root.resolve("retained-failed-repair-$it").readBytes() }
        reader.textEditIoProbe = { event, _ ->
            if (event == TextEditIo.SESSION_READ) reads.incrementAndGet()
            if (event == TextEditIo.INDEX_WRITE) attempts.incrementAndGet()
        }
        assertCurrent(session, reader.getSummary(session.id))
        assertEquals(3, reads.get()); assertEquals(3, attempts.get())
        assertCurrent(session, reader.listSummaries().single())
        assertEquals(3, reads.get()); assertEquals(3, attempts.get())
        evidence.forEach { (index, bytes) -> assertArrayEquals(bytes, root.resolve("retained-failed-repair-$index").readBytes()) }
        assertArrayEquals(source, primary(root, session.id).readBytes())
    }

    @Test fun preciseTimeChangeDuringDecodeCannotPublishOldBodyAgainstNewPrimary() = workspace { root ->
        val session = DroideSession("s-changed-during-decode", "Before", createdAt = 77)
        val manager = SessionManager(root)
        manager.save(session)
        val file = primary(root, session.id)
        val firstTime = FileTime.from(Instant.parse("2026-11-04T12:34:56.123456700Z"))
        val secondTime = FileTime.from(Instant.parse("2026-11-04T12:34:56.123456789Z"))
        Files.setLastModifiedTime(file.toPath(), firstTime)
        assertCurrent(session, manager.getSummary(session.id))
        alterCache(meta(root, session.id), mapOf("sourceKey" to null))
        val oldCache = meta(root, session.id).readBytes()
        val oldStamp = attributes(file)
        val writes = AtomicInteger()
        manager.textEditIoProbe = { event, _ ->
            if (event == TextEditIo.SESSION_READ) {
                file.writeText(file.readText().replace("Before", "After!"))
                Files.setLastModifiedTime(file.toPath(), secondTime)
            }
            if (event == TextEditIo.INDEX_WRITE) writes.incrementAndGet()
        }
        assertNull("A changed source must be retried, not stamped with the decoded old body", manager.getSummary(session.id))
        assertEquals(0, writes.get())
        assertArrayEquals(oldCache, meta(root, session.id).readBytes())
        val newStamp = attributes(file)
        assertEquals(oldStamp.size(), newStamp.size())
        assertEquals(oldStamp.fileKey(), newStamp.fileKey())
        assertEquals(oldStamp.lastModifiedTime().toMillis(), newStamp.lastModifiedTime().toMillis())
        assertNotEquals(oldStamp.lastModifiedTime(), newStamp.lastModifiedTime())
        manager.textEditIoProbe = null
        assertCurrent(session.copy(title = "After!"), manager.getSummary(session.id))
    }
}
