package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class AgentConversationHistoryOwnershipTest {
    private fun entry(text: String) = buildJsonObject {
        put("role", "user")
        put("content", text)
    }

    @Test fun delayedOldSessionAppendCannotAdoptTheNewHistoryOwner() = runBlocking {
        val history = AgentConversationHistory()
        history.append(entry("old conversation"))
        val oldOwner = history.ownershipVersion
        history.resetNew()
        val current = entry("new conversation")
        history.append(current)

        val failure = runCatching {
            history.append(listOf(entry("obsolete cleanup")), expectedOwnerVersion = oldOwner)
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(listOf(current), history.active)
        assertEquals(listOf(current), history.completeExactOrNull())
        history.append(listOf(entry("current continuation")), expectedOwnerVersion = history.ownershipVersion)
        assertEquals(2, history.completeExactOrNull()!!.size)
    }

    @Test fun beginningRestoreInvalidatesOldAppendBeforeReplacementIsPublished() = runBlocking {
        val history = AgentConversationHistory()
        val original = entry("retained until restore succeeds")
        history.append(original)
        val oldOwner = history.ownershipVersion
        history.beginRestore()

        val failure = runCatching {
            history.append(listOf(entry("stale old-session result")), expectedOwnerVersion = oldOwner)
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(listOf(original), history.active)
        assertEquals(listOf(original), history.completeExactOrNull())
    }

    @Test fun suspendedAppendCannotPublishAfterAnotherConversationTakesOwnership() = runBlocking {
        val history = AgentConversationHistory()
        history.append(entry("old conversation"))
        val oldOwner = history.ownershipVersion
        // Force the real append to suspend without sleeping or changing its data/owner counters.
        val appendMutex = AgentConversationHistory::class.java.getDeclaredField("appendMutex")
            .apply { isAccessible = true }.get(history) as Mutex
        appendMutex.lock()
        val obsolete = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { history.append(listOf(entry("old pending result")), expectedOwnerVersion = oldOwner) }
        }
        try {
            assertFalse(obsolete.isCompleted)
            history.resetNew()
        } finally {
            appendMutex.unlock()
        }

        assertTrue(obsolete.await().exceptionOrNull() is CancellationException)
        val current = entry("new conversation")
        history.append(current)
        assertEquals(listOf(current), history.active)
        assertEquals(listOf(current), history.completeExactOrNull())
    }
}
