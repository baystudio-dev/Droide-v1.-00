package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentConversationHistoryCancellationTest {
    private fun entry(text: String) = buildJsonObject {
        put("role", "user")
        put("content", text)
    }

    @Test fun alreadyCancelledAppendCannotUseTheUncontendedLockFastPath() = runBlocking {
        val history = AgentConversationHistory()
        var failure: Throwable? = null
        var admitted = false
        launch(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            failure = runCatching { history.append(entry("cancelled"), beforeAppend = { admitted = true }) }.exceptionOrNull()
        }.join()
        assertTrue(failure is CancellationException)
        assertFalse(admitted)
        assertTrue(history.active.isEmpty())
        assertTrue(requireNotNull(history.completeExactOrNull()).isEmpty())
    }

    @Test fun cancelledContendedWaiterCannotPublishAfterTheMutexIsReleased() = runBlocking {
        val history = AgentConversationHistory()
        val mutex = AgentConversationHistory::class.java.getDeclaredField("appendMutex").apply { isAccessible = true }.get(history) as Mutex
        mutex.lock()
        var entered = false
        var admitted = false
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            entered = true
            history.append(entry("waiting"), beforeAppend = { admitted = true })
        }
        assertTrue(entered)
        assertFalse(pending.isCompleted)
        pending.cancel()
        mutex.unlock()
        pending.join()
        assertFalse(admitted)
        assertTrue(history.active.isEmpty())
        assertTrue(requireNotNull(history.completeExactOrNull()).isEmpty())
    }

    @Test fun cancellationAfterAcknowledgedAppendPreservesCompletedHistory() = runBlocking {
        val history = AgentConversationHistory()
        val completed = entry("completed")
        var rejected: Throwable? = null
        launch(start = CoroutineStart.UNDISPATCHED) {
            history.append(completed)
            cancel()
            rejected = runCatching { history.append(entry("late")) }.exceptionOrNull()
        }.join()
        assertTrue(rejected is CancellationException)
        assertEquals(listOf(completed), history.active)
        assertEquals(listOf(completed), history.completeExactOrNull())
    }

    @Test fun cancellationDuringAdmissionCannotMutateEitherHistory() = runBlocking {
        val history = AgentConversationHistory()
        var failure: Throwable? = null
        launch(start = CoroutineStart.UNDISPATCHED) {
            val job = currentCoroutineContext().job
            failure = runCatching { history.append(entry("revoked"), beforeAppend = { job.cancel() }) }.exceptionOrNull()
        }.join()
        assertTrue(failure is CancellationException)
        assertTrue(history.active.isEmpty())
        assertTrue(requireNotNull(history.completeExactOrNull()).isEmpty())
    }
}
