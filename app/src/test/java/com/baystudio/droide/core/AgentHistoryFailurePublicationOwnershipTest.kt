package com.baystudio.droide.core

import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Bounded direct-history failures; no Agent, terminal, provider, process, native or device fixture. */
class AgentHistoryFailurePublicationOwnershipTest {
    @Test(timeout = 15_000)
    fun staleCheckpointFailureCannotPoisonTheRetainedCurrentState() = checkpointFailure(revoke = true)

    @Test(timeout = 15_000)
    fun currentCheckpointFailureStillRecordsItsOwnStorageFailure() = checkpointFailure(revoke = false)

    @Test(timeout = 15_000)
    fun staleSpillFailureCannotPoisonTheRetainedCurrentState() {
        val root = Files.createTempDirectory("droide-spill-error-owner-").toFile()
        try { runBlocking { withTimeout(8_000) {
            val history = AgentConversationHistory(SessionHistoryLedger(root))
            val original = message("original")
            history.append(original)
            val state = state(history)
            val oldVersion = history.ownershipVersion
            val failure = IOException("deliberate stale spill failure")
            var checks = 0
            val outcome = runCatching {
                // Just over the existing hot-tail threshold, below the single-entry cap. The second beforeAppend
                // callback fails before any ledger persist call.
                history.append(listOf(message("h".repeat(SessionHistoryLedger.HOT_HISTORY_BYTES.toInt()))),
                    expectedOwnerVersion = oldVersion, beforeAppend = {
                        checks++
                        if (checks == 2) { history.beginRestore(); throw failure }
                    })
            }
            assertEquals(2, checks)
            assertTrue(outcome.exceptionOrNull() is CancellationException)
            assertSame(state, state(history))
            assertTrue(history.ownershipVersion > oldVersion)
            assertEquals(listOf(original), history.active)
            assertFalse(root.walkTopDown().any { it.isFile })
            assertNull("A stale spill failure must not poison the retained current state", storageFailure(history))
            history.ensureStorageAvailable(history.ownershipVersion)
        } } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun checkpointFailure(revoke: Boolean) {
        val root = Files.createTempDirectory("droide-checkpoint-error-owner-").toFile()
        try { runBlocking { withTimeout(8_000) {
            val history = AgentConversationHistory(SessionHistoryLedger(root))
            val values = FailingEntriesMap(mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive("original")))
            val entry = JsonObject(values)
            history.append(entry)
            val state = state(history)
            val oldVersion = history.ownershipVersion
            val failure = FixtureIOException("deliberate checkpoint admission read failure")
            val failures = AtomicInteger()
            values.beforeEntries = {
                // The proposal snapshot has already been captured. Its bounded ledger admission walk reads this
                // entry before creating any immutable page.
                failures.incrementAndGet()
                if (revoke) history.beginRestore()
                throw failure
            }
            val outcome = try { runCatching { history.completeExactOrNull(oldVersion) } }
                finally { values.beforeEntries = null }
            assertSame(failure, outcome.exceptionOrNull())
            assertEquals(1, failures.get())
            assertSame(state, state(history))
            assertEquals(listOf(entry), history.active)
            assertFalse(root.walkTopDown().any { it.isFile })
            if (revoke) {
                assertTrue(history.ownershipVersion > oldVersion)
                assertNull("A stale checkpoint failure must not poison the retained current state", storageFailure(history))
            } else {
                assertEquals(oldVersion, history.ownershipVersion)
                assertSame(failure, storageFailure(history))
                history.ensureStorageAvailable(oldVersion)
                assertNull(storageFailure(history))
                assertEquals(listOf(entry), history.completeExactOrNull(oldVersion)!!.toList())
            }
        } } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    // Preserve exact injected identity across coroutine debug stack-trace recovery.
    @OptIn(ExperimentalCoroutinesApi::class)
    private class FixtureIOException(message: String) : IOException(message), CopyableThrowable<FixtureIOException> {
        override fun createCopy(): FixtureIOException? = null
    }

    private class FailingEntriesMap(private val backing: Map<String, JsonElement>) : Map<String, JsonElement> by backing {
        override fun equals(other: Any?): Boolean = backing == other
        override fun hashCode(): Int = backing.hashCode()
        @Volatile var beforeEntries: (() -> Unit)? = null
        override val entries: Set<Map.Entry<String, JsonElement>> get() {
            beforeEntries?.invoke()
            return backing.entries
        }
    }
    private fun state(history: AgentConversationHistory): Any = AgentConversationHistory::class.java
        .getDeclaredField("state").apply { isAccessible = true }.get(history)
    private fun storageFailure(history: AgentConversationHistory): Any? = state(history).let { owner ->
        owner.javaClass.getDeclaredField("storageFailure").apply { isAccessible = true }.get(owner)
    }
    private fun message(text: String) = buildJsonObject { put("role", "user"); put("content", text) }
}
