package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SessionHistoryDepthTest {
    private fun entry(arrays: Int, emptyLeaf: Boolean = false, emptyObject: Boolean = false): JsonObject {
        var value: JsonElement = if (emptyObject) JsonObject(emptyMap()) else if (emptyLeaf) JsonArray(emptyList()) else JsonPrimitive("leaf { [ \\\" ] } 😀")
        repeat(arrays) { value = JsonArray(listOf(value)) }
        return buildJsonObject { put("role", "user"); put("content", value) }
    }
    private fun withRoot(block: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("history-depth-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    @Test fun supportedEntryDepthRoundTripsCheckpointAndColdSession() = withRoot { root ->
        val manager = SessionManager(root)
        val session = manager.create()
        val exact = AgentConversationHistory(manager.historyLedger)
        val entries = listOf(entry(127), entry(127, emptyLeaf = true), entry(127, emptyObject = true))
        entries.forEach { exact.append(it) }
        val checkpoint = checkNotNull(exact.completeExactOrNull())
        manager.save(session.copy(forkHistory = checkpoint, forkHistoryVersion = 1))
        assertEquals(entries, SessionManager(root).get(session.id)!!.forkHistory)
        assertEquals(entries, SessionHistoryLedger(root).snapshot((checkpoint as SessionHistoryLedger.Snapshot).pages, emptyList()))
    }
    @Test fun supportedInlineApiHistoryRoundTrips() = withRoot { root ->
        val manager = SessionManager(root); val session = manager.create()
        val entries = listOf(entry(127), entry(127, emptyLeaf = true), entry(127, emptyObject = true))
        manager.save(session.copy(apiHistory = entries))
        assertEquals(entries, SessionManager(root).get(session.id)!!.apiHistory)
    }
    @Test fun supportedInlineForkHistoryRoundTrips() = withRoot { root ->
        val manager = SessionManager(root); val session = manager.create()
        val entries = listOf(entry(127), entry(127, emptyLeaf = true), entry(127, emptyObject = true))
        manager.save(session.copy(forkHistory = entries, forkHistoryVersion = 1))
        assertEquals(entries, SessionManager(root).get(session.id)!!.forkHistory)
    }
    @Test fun unsupportedInlineDepthDoesNotReplacePriorManifest() = withRoot { root ->
        val manager = SessionManager(root); val session = manager.create("keep this checkpoint")
        val file = File(root, ".droide/sessions/${session.id}.json"); val before = file.readBytes()
        assertTrue(runCatching { manager.save(session.copy(apiHistory = listOf(entry(128)))) }.isFailure)
        assertArrayEquals(before, file.readBytes())
        assertEquals(session.title, SessionManager(root).get(session.id)!!.title)
    }
    @Test fun unsupportedDirectPageDepthCreatesNoPublishedPage() = withRoot { root ->
        val ledger = SessionHistoryLedger(root)
        val old = ledger.writePage(listOf(entry(1)))
        val directory = File(root, ".droide/sessions/.history-pages")
        val before = directory.listFiles()!!.associate { it.name to it.readBytes().toList() }
        assertTrue(runCatching { ledger.writePage(listOf(entry(128))) }.isFailure)
        assertEquals(before, directory.listFiles()!!.associate { it.name to it.readBytes().toList() })
        assertEquals(listOf(entry(1)), SessionHistoryLedger(root).snapshot(listOf(old), emptyList()))
    }
    @Test fun byteOnlyOutputAndBoundedInputHandleChunkedEscapes() {
        val payload = "[".repeat(128) + JsonPrimitive("\\\"{[😀]}\\").toString() + "]".repeat(128)
        val bytes = payload.toByteArray()
        val output = ByteArrayOutputStream(); val bounded = BoundedSessionOutput(output, 10000)
        bytes.forEach { bounded.write(it.toInt() and 255) }
        assertArrayEquals(bytes, output.toByteArray())
        val input = BoundedSessionInput(ByteArrayInputStream(bytes), 10000)
        assertArrayEquals(bytes, input.readBytes())
        val tooDeep = ("[".repeat(129) + "0" + "]".repeat(129)).toByteArray()
        assertTrue(runCatching { BoundedSessionInput(ByteArrayInputStream(tooDeep), 10000).readBytes() }.isFailure)
    }
    @Test fun thousandLevelInlineHistoriesAndLegacySnapshotFailBeforeRecursiveEncoding() = withRoot { root ->
        val manager = SessionManager(root); val session = manager.create("prior checkpoint")
        val file = File(root, ".droide/sessions/${session.id}.json"); val before = file.readBytes()
        val deep = entry(1_000)
        val variants = listOf(
            session.copy(apiHistory = listOf(deep)),
            session.copy(forkHistory = listOf(deep)),
            session.copy(forkHistory = manager.historyLedger.snapshot(emptyList(), listOf(deep))),
        )
        for (candidate in variants) {
            val failure = runCatching { manager.save(candidate) }.exceptionOrNull()
            assertTrue("Must reject before stack overflow: $failure", failure is IllegalArgumentException)
            assertArrayEquals(before, file.readBytes())
            assertEquals(session.title, SessionManager(root).get(session.id)!!.title)
        }
        assertTrue(runCatching { manager.historyLedger.writePage(listOf(deep)) }.exceptionOrNull() is IllegalArgumentException)
        assertFalse(File(root, ".droide/sessions/.history-pages").exists())
    }

    @Test fun explicitJsonOutputGuardSupportsEveryChunkBoundaryAndKeepsFiniteLimits() {
        for (limit in listOf(128, SessionJsonSize.PAGE_NESTING_DEPTH, SessionJsonSize.SESSION_NESTING_DEPTH)) {
            val payload = "[".repeat(limit) + JsonPrimitive("quote \" slash \\ brackets [ { } ] 😀").toString() + "]".repeat(limit)
            val bytes = payload.toByteArray()
            for (chunk in 1..9) {
                val target = ByteArrayOutputStream()
                val output = BoundedSessionOutput(target, 10000, limit)
                var offset = 0
                while (offset < bytes.size) {
                    val count = minOf(chunk, bytes.size - offset)
                    if (count == 1) output.write(bytes[offset].toInt() and 255)
                    else output.write(bytes, offset, count)
                    offset += count
                }
                assertArrayEquals(bytes, target.toByteArray())
                val input = BoundedSessionInput(ByteArrayInputStream(bytes), 10000, limit)
                val restored = ByteArrayOutputStream(); val buffer = ByteArray(chunk)
                while (true) { val count = input.read(buffer); if (count < 0) break; restored.write(buffer, 0, count) }
                assertArrayEquals(bytes, restored.toByteArray())
            }
            val tooDeep = ("[".repeat(limit + 1) + "0" + "]".repeat(limit + 1)).toByteArray()
            assertTrue(runCatching { BoundedSessionOutput(ByteArrayOutputStream(), 10000, limit).write(tooDeep) }.exceptionOrNull() is java.io.IOException)
            assertTrue(runCatching { BoundedSessionInput(ByteArrayInputStream(tooDeep), 10000, limit).readBytes() }.exceptionOrNull() is java.io.IOException)
        }
        val arbitraryBytes = "[".repeat(1000).toByteArray()
        val ordinary = ByteArrayOutputStream()
        BoundedSessionOutput(ordinary, 10000).write(arbitraryBytes)
        assertArrayEquals(arbitraryBytes, ordinary.toByteArray())
    }

    @Test fun productionHistoryClassesAreFreshlyLoadedTogether() {
        val expected = javaClass.protectionDomain.codeSource.location
        for (type in listOf(SessionManager::class.java, SessionHistoryLedger::class.java,
            BoundedSessionInput::class.java, BoundedSessionOutput::class.java, SessionJsonSize::class.java)) {
            println("F141 LOADED ${type.name} ${type.protectionDomain.codeSource.location}")
            assertEquals("History production class must be freshly compiled with this test", expected, type.protectionDomain.codeSource.location)
        }
    }

}
