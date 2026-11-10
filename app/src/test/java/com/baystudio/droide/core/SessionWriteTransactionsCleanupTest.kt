package com.baystudio.droide.core

import java.lang.ref.WeakReference
import org.junit.Assert.*
import org.junit.Test

/** Explicit reference clearing/enqueuing makes cleanup deterministic without relying on GC. */
class SessionWriteTransactionsCleanupTest {
    @Test fun lookupDoesNotSweepClearedReferencesThatHaveNotBeenQueued() {
        val transactions = SessionWriteTransactions()
        val cleared = transactions.entry("cleared")
        val entries = entries(transactions)
        val reference = entries.getValue("cleared")
        assertSame(cleared, reference.get())
        reference.clear()

        val live = transactions.entry("live")
        assertSame(reference, entries.getValue("cleared"))
        assertSame(live, entries.getValue("live").get())

        assertTrue(reference.enqueue())
        assertSame(live, transactions.entry("live"))
        assertEquals(setOf("live"), entries.keys)
    }

    @Test fun lateQueueNotificationCannotRemoveReplacementForTheSameId() {
        val transactions = SessionWriteTransactions()
        val original = transactions.entry("reused")
        val entries = entries(transactions)
        val oldReference = entries.getValue("reused")
        assertSame(original, oldReference.get())

        oldReference.clear()
        val replacement = transactions.entry("reused")
        replacement.conversationRevision = 41L
        val replacementReference = entries.getValue("reused")
        assertNotSame(original, replacement)
        assertNotSame(oldReference, replacementReference)

        assertTrue(oldReference.enqueue())
        assertSame(replacement, transactions.entry("reused"))
        assertSame(replacementReference, entries.getValue("reused"))
        assertEquals(41L, replacement.conversationRevision)
    }

    @Test fun nextLookupDrainsAllQueuedReferencesAndPreservesLiveEntries() {
        val transactions = SessionWriteTransactions()
        val live = transactions.entry("live")
        val count = 4_096
        val expired = (0 until count).map { transactions.entry("expired-$it") }
        val entries = entries(transactions)
        val references = (0 until count).map { entries.getValue("expired-$it") }
        references.forEachIndexed { index, reference ->
            assertSame(expired[index], reference.get())
            reference.clear()
            assertTrue(reference.enqueue())
        }
        assertEquals(count + 1, entries.size)

        val next = transactions.entry("next")
        assertEquals(setOf("live", "next"), entries.keys)
        assertSame(live, entries.getValue("live").get())
        assertSame(next, entries.getValue("next").get())
        assertSame(live, transactions.entry("live"))
    }

    @Test fun pinnedRevisionRetainsItsExactEntryLockAndRevisionDuringCleanup() {
        val transactions = SessionWriteTransactions()
        val revision = transactions.entry("pinned").let { entry ->
            entry.conversationRevision = 17L
            SessionWriteRevision(entry, entry.conversationRevision)
        }
        val entries = entries(transactions)
        val pinnedReference = entries.getValue("pinned")
        val expired = transactions.entry("expired")
        val expiredReference = entries.getValue("expired")
        assertSame(expired, expiredReference.get())
        expiredReference.clear()
        assertTrue(expiredReference.enqueue())

        val pinnedMutex = revision.entry.mutex
        assertTrue(pinnedMutex.tryLock())
        try {
            val current = transactions.entry("pinned")
            assertSame(revision.entry, current)
            assertSame(pinnedReference, entries.getValue("pinned"))
            assertSame(pinnedMutex, current.mutex)
            assertFalse(current.mutex.tryLock())
            assertEquals(setOf("pinned"), entries.keys)
            assertEquals(17L, current.conversationRevision)
            current.conversationRevision++
            assertEquals(18L, revision.entry.conversationRevision)
            assertEquals(17L, revision.value)
        } finally {
            pinnedMutex.unlock()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun entries(transactions: SessionWriteTransactions): Map<String, WeakReference<SessionWriteTransactions.Entry>> =
        SessionWriteTransactions::class.java.getDeclaredField("entries").let { field ->
            field.isAccessible = true
            field.get(transactions) as Map<String, WeakReference<SessionWriteTransactions.Entry>>
        }
}
