package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** Small local histories only; no Agent, terminal, provider, native or device fixture. */
class AgentHistoryCheckpointOwnershipTest {
    @Test(timeout = 15_000)
    fun staleCheckpointAndAvailabilityTicketsCannotTouchReplacementHistoryOrBytes() {
        val root = Files.createTempDirectory("droide-checkpoint-owner-").toFile()
        try { runBlocking { withTimeout(8_000) {
            val history = AgentConversationHistory(SessionHistoryLedger(root))
            history.append(entry("old conversation"))
            val oldOwner = history.ownershipVersion
            val replacement = entry("replacement conversation")
            history.load(DroideSession("replacement", "Replacement", apiHistory = listOf(replacement),
                forkHistory = listOf(replacement), forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION))
            assertEquals(listOf(replacement), history.completeExactOrNull(history.ownershipVersion)!!.toList())
            val before = bytes(root)
            assertFalse("Current checkpoint should have one small persisted page", before.isEmpty())

            val staleCheckpoint = runCatching { history.completeExactOrNull(oldOwner) }.exceptionOrNull()
            val staleAvailability = runCatching { history.ensureStorageAvailable(oldOwner) }.exceptionOrNull()

            assertTrue(staleCheckpoint is CancellationException)
            assertTrue(staleAvailability is CancellationException)
            assertEquals(listOf(replacement), history.active)
            assertEquals(before, bytes(root))
            history.ensureStorageAvailable(history.ownershipVersion)
            assertEquals(listOf(replacement), history.completeExactOrNull()!!.toList())
            assertEquals(before, bytes(root))
        } } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test(timeout = 15_000)
    fun checkpointAlreadyWaitingOnAppendMutexCannotPublishAfterLoad() = runBlocking {
        withTimeout(8_000) {
            val history = AgentConversationHistory()
            history.append(entry("old conversation"))
            val oldOwner = history.ownershipVersion
            val mutex = AgentConversationHistory::class.java.getDeclaredField("appendMutex")
                .apply { isAccessible = true }.get(history) as Mutex
            mutex.lock()
            var held = true
            val checkpoint = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { history.completeExactOrNull(oldOwner) }
            }
            try {
                assertFalse(checkpoint.isCompleted)
                val replacement = entry("replacement conversation")
                history.load(DroideSession("replacement", "Replacement", apiHistory = listOf(replacement),
                    forkHistory = listOf(replacement), forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION))
                mutex.unlock()
                held = false
                assertTrue(checkpoint.await().exceptionOrNull() is CancellationException)
                assertEquals(listOf(replacement), history.active)
                assertEquals(listOf(replacement), history.completeExactOrNull())
            } finally {
                if (held) mutex.unlock()
                withContext(NonCancellable) { withTimeout(2_000) { checkpoint.cancelAndJoin() } }
            }
        }
    }

    private fun entry(text: String) = buildJsonObject { put("role", "user"); put("content", text) }
    private fun bytes(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
}
