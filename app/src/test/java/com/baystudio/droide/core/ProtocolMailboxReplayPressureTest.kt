package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

/** Tiny accounting weights only; no large frames or heap-pressure execution. */
class ProtocolMailboxReplayPressureTest {
    @Test(timeout = 15_000) fun consumedReplayYieldsToPressureAndReconnectSeesOnlyRemainingReplay() = runBlocking {
        val box = mailbox(8)
        try {
            box.tryOffer("a", 4); box.tryOffer("b", 4)
            assertEquals(listOf("a", "b"), withTimeout(1_000) { box.events.take(2).toList() })

            assertEquals(ProtocolEventMailbox.Admission.ACCEPTED, box.tryOffer("c", 4))
            assertEquals(8L, box.retainedBytes)
            assertEquals(listOf("b", "c"), withTimeout(1_000) { box.events.take(2).toList() })
        } finally { box.finish() }
    }

    @Test(timeout = 15_000) fun successfullyDeliveredEntryIsProtectedWhileInFlightAgain() = runBlocking {
        val box = mailbox(8)
        box.tryOffer("a", 4); box.tryOffer("b", 1)
        withTimeout(1_000) { box.events.take(2).toList() }
        val entered = CompletableDeferred<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            box.events.collect { entered.complete(Unit); withTimeout(5_000) { awaitCancellation() } }
        }
        try {
            withTimeout(1_000) { entered.await() }
            assertEquals(ProtocolEventMailbox.Admission.OVERFLOW, box.tryOffer("c", 4))
            assertEquals(5L, box.retainedBytes)
        } finally { try { stop(collector) } finally { box.finish() } }
    }

    @Test(timeout = 15_000) fun previouslyDeliveredButPendingReplayCannotBePrunedOrDuplicated() = runBlocking {
        val box = mailbox(8)
        box.tryOffer("a", 1); box.tryOffer("b", 3); box.tryOffer("c", 1)
        withTimeout(1_000) { box.events.take(3).toList() }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val drained = CompletableDeferred<Unit>()
        val seen = mutableListOf<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            box.events.collect {
                seen += it
                if (it == "a") { entered.complete(Unit); withTimeout(5_000) { release.await() } }
                if (it == "c") drained.complete(Unit)
            }
        }
        try {
            withTimeout(1_000) { entered.await() }

            assertEquals(ProtocolEventMailbox.Admission.OVERFLOW, box.tryOffer("rejected", 4))
            assertEquals(5L, box.retainedBytes)
            release.complete(Unit)
            withTimeout(1_000) { drained.await() }
            box.finish()
            withTimeout(1_000) { collector.join() }
            assertEquals(listOf("a", "b", "c"), seen)
        } finally { release.complete(Unit); try { stop(collector) } finally { box.finish() } }
    }

    @Test(timeout = 15_000) fun insufficientEligibleReplayLeavesEveryOldEntryAndChargeIntact() = runBlocking {
        val box = mailbox(8)
        try {
            box.tryOffer("a", 2); box.tryOffer("b", 6)
            withTimeout(1_000) { box.events.take(2).toList() }
            // Only a is eligible. Evicting its 2 bytes would still not admit 3.
            assertEquals(ProtocolEventMailbox.Admission.OVERFLOW, box.tryOffer("c", 3))
            assertEquals(8L, box.retainedBytes)
            assertEquals(listOf("a", "b"), withTimeout(1_000) { box.events.take(2).toList() })
        } finally { box.finish() }
    }

    @Test(timeout = 15_000) fun startupReplayHasNoDeliveryProofAndCannotBePressureEvicted() = runBlocking {
        val box = mailbox(8)
        try {
            box.tryOffer("a", 4); box.tryOffer("b", 4)
            assertEquals(ProtocolEventMailbox.Admission.OVERFLOW, box.tryOffer("c", 4))
            assertEquals(8L, box.retainedBytes)
            assertEquals(listOf("a", "b"), withTimeout(1_000) { box.events.take(2).toList() })
        } finally { box.finish() }
    }

    @Test(timeout = 15_000) fun firstAbortDoesNotClaimThatDownstreamReturnedNormally() = runBlocking {
        val box = mailbox(8)
        try {
            box.tryOffer("a", 8)
            assertEquals("a", withTimeout(1_000) { box.events.first() })
            assertEquals(ProtocolEventMailbox.Admission.OVERFLOW, box.tryOffer("b", 1))
            assertEquals(8L, box.retainedBytes)
        } finally { box.finish() }
    }

    @Test(timeout = 15_000) fun fullProductionBudgetIsPermittedAsOneWeightWithoutAllocatingThatPayload() = runBlocking {
        val limit = 8L * 1024 * 1024
        val box = mailbox(limit)
        try {
            assertEquals(ProtocolEventMailbox.Admission.ACCEPTED, box.tryOffer("tiny representative value", limit))
            assertEquals(limit, box.retainedBytes)
            assertEquals("tiny representative value", withTimeout(1_000) { box.events.first() })
        } finally { box.finish() }
        assertEquals(0L, box.retainedBytes)
    }

    private fun mailbox(bytes: Long) = ProtocolEventMailbox<String>(16, 8, bytes, 4)
    private suspend fun stop(job: Job) {
        job.cancel()
        withContext(NonCancellable) { withTimeout(1_000) { job.join() } }
    }
}
