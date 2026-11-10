package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

/** PROPOSED API CONTRACT ONLY. has no ProtocolEventMailbox and cannot compile this file. */

class ProtocolEventMailboxContractTest {
    @Test(timeout = 15_000) fun rpcDiscardsWhenUnsubscribedWithoutChargingOrReplaying() = runBlocking {
        val box = mailbox(replay = 0, bytes = 12)
        assertEquals(ProtocolEventMailbox.Admission.DISCARDED, box.tryOffer("old", 8))
        assertEquals(0L, box.retainedBytes)
        val first = async(start = CoroutineStart.UNDISPATCHED) { box.events.first() }
        try {
            assertEquals(ProtocolEventMailbox.Admission.ACCEPTED, box.tryOffer("new", 8))
            assertEquals("new", withTimeout(1_000) { first.await() })
            assertEquals(0L, box.retainedBytes)
        } finally { first.cancel(); box.finish() }
    }

    @Test(timeout = 15_000) fun replayRetainsLatestEightAcrossSequentialSubscribers() = runBlocking {
        val box = mailbox(replay = 8, bytes = 16)
        try {
            repeat(10) { assertEquals(ProtocolEventMailbox.Admission.ACCEPTED, box.tryOffer("$it", 1)) }
            assertEquals(8L, box.retainedBytes)
            assertEquals((2..9).map { it.toString() }, withTimeout(1_000) { box.events.take(8).toList() })
            assertEquals(8L, box.retainedBytes)
            repeat(3) { box.tryOffer("${it + 10}", 1) }
            assertEquals((5..12).map { it.toString() }, withTimeout(1_000) { box.events.take(8).toList() })
        } finally { box.finish() }
        assertEquals(0L, box.retainedBytes)
    }

    @Test(timeout = 15_000) fun byteOverflowIsAtomicAndDoesNotEvictAnOldReplayEntry() = runBlocking {
        val box = ProtocolEventMailbox<String>(capacity = 4, replay = 2, maxRetainedBytes = 8, terminalReserveBytes = 4)
        try {
            box.tryOffer("old-a", 4)
            box.tryOffer("old-b", 4)
            assertEquals(ProtocolEventMailbox.Admission.OVERFLOW, box.tryOffer("too-large", 5))
            assertEquals(8L, box.retainedBytes)
            assertEquals(listOf("old-a", "old-b"), withTimeout(1_000) { box.events.take(2).toList() })

            assertEquals(ProtocolEventMailbox.Admission.ACCEPTED, box.tryOffer("new", 4))
            assertEquals(listOf("old-b", "new"), withTimeout(1_000) { box.events.take(2).toList() })
        } finally { box.finish() }
    }

    @Test(timeout = 15_000) fun inFlightPayloadStaysChargedUntilTheCallbackReturns() = runBlocking {
        val box = mailbox(replay = 0, bytes = 12)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val deliveredSecond = CompletableDeferred<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            box.events.collect {
                if (it == "first") { entered.complete(Unit); withTimeout(5_000) { release.await() } }
                if (it == "second") deliveredSecond.complete(Unit)
            }
        }
        try {
            box.tryOffer("first", 8)
            withTimeout(1_000) { entered.await() }
            assertEquals(8L, box.retainedBytes)
            assertEquals(ProtocolEventMailbox.Admission.OVERFLOW, box.tryOffer("rejected", 5))
            assertEquals(ProtocolEventMailbox.Admission.ACCEPTED, box.tryOffer("second", 4))
            assertEquals(12L, box.retainedBytes)
            release.complete(Unit)
            withTimeout(1_000) { deliveredSecond.await() }
        } finally { release.complete(Unit); try { stop(collector) } finally { box.finish() } }
        assertEquals(0L, box.retainedBytes)
    }

    @Test(timeout = 15_000) fun replayAndInFlightOwnershipChargeTheSameEntryOnlyOnce() = runBlocking {
        val box = mailbox(replay = 8, bytes = 8)
        box.tryOffer("first", 8)
        val entered = CompletableDeferred<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            box.events.collect { entered.complete(Unit); withTimeout(5_000) { awaitCancellation() } }
        }
        try {
            withTimeout(1_000) { entered.await() }
            assertEquals(8L, box.retainedBytes)
            assertEquals(ProtocolEventMailbox.Admission.OVERFLOW, box.tryOffer("second", 1))
            stop(collector)
            assertEquals(8L, box.retainedBytes) // Replay still owns the entry.
            assertEquals("first", withTimeout(1_000) { box.events.first() })
        } finally { try { stop(collector) } finally { box.finish() } }
        assertEquals(0L, box.retainedBytes)
    }

    @Test(timeout = 15_000) fun rpcCancellationReleasesInFlightAndBacklogBeforeResubscribe() = runBlocking {
        val box = mailbox(replay = 0, bytes = 12)
        val entered = CompletableDeferred<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            box.events.collect { entered.complete(Unit); withTimeout(5_000) { awaitCancellation() } }
        }
        var next: Deferred<String>? = null
        try {
            box.tryOffer("in-flight", 8)
            withTimeout(1_000) { entered.await() }
            box.tryOffer("backlog", 4)
            stop(collector)
            assertEquals(0L, box.retainedBytes)
            assertEquals(ProtocolEventMailbox.Admission.DISCARDED, box.tryOffer("detached", 8))
            val nextOwner = async(start = CoroutineStart.UNDISPATCHED) { box.events.first() }
            next = nextOwner
            box.tryOffer("fresh", 8)
            assertEquals("fresh", withTimeout(1_000) { nextOwner.await() })
        } finally { next?.cancel(); try { stop(collector) } finally { box.finish() } }
    }

    @Test(timeout = 15_000) fun rejectingSecondCollectorDoesNotDetachTheFirst() = runBlocking {
        val box = mailbox(replay = 0, bytes = 12)
        val first = async(start = CoroutineStart.UNDISPATCHED) { box.events.first() }
        try {
            val failure = withTimeout(1_000) { runCatching { box.events.first() }.exceptionOrNull() }
            assertTrue(failure is IllegalStateException)
            assertEquals(ProtocolEventMailbox.Admission.ACCEPTED, box.tryOffer("owned", 8))
            assertEquals("owned", withTimeout(1_000) { first.await() })
        } finally { first.cancel(); box.finish() }
    }

    @Test(timeout = 15_000) fun finishClearsBacklogButKeepsInFlightAndBoundedTerminalNotice() = runBlocking {
        val box = mailbox(replay = 8, bytes = 12)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val seen = mutableListOf<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            box.events.collect {
                seen += it
                if (it == "in-flight") { entered.complete(Unit); withTimeout(5_000) { release.await() } }
            }
        }
        try {
            box.tryOffer("in-flight", 8)
            withTimeout(1_000) { entered.await() }
            box.tryOffer("backlog", 4)
            box.finish(ProtocolEventMailbox.Terminal("terminated", 4))
            box.finish() // Cleanup cannot erase a previously chosen terminal.
            assertEquals(8L, box.retainedBytes)
            assertEquals(4L, box.terminalBytes)
            assertEquals(ProtocolEventMailbox.Admission.CLOSED, box.tryOffer("late", 1))
            release.complete(Unit)
            withTimeout(1_000) { collector.join() }
            assertEquals(listOf("in-flight", "terminated"), seen)
            assertEquals(0L, box.retainedBytes)
            assertEquals(listOf("terminated"), withTimeout(1_000) { box.events.toList() })
        } finally { release.complete(Unit); try { stop(collector) } finally { box.finish() } }
    }

    @Test(timeout = 15_000) fun cleanCloseBeforeSubscriptionRetainsNoPayloadOrLaterFailure() = runBlocking {
        val box = mailbox(replay = 8, bytes = 12)
        box.tryOffer("replay", 8)
        box.finish()
        box.finish(ProtocolEventMailbox.Terminal("late-failure", 4))
        assertEquals(0L, box.retainedBytes)
        assertEquals(0L, box.terminalBytes)
        assertEquals(emptyList<String>(), withTimeout(1_000) { box.events.toList() })
    }

    @Test(timeout = 15_000) fun independentMailboxesCannotReplayAnOldSessionsEvents() = runBlocking {
        val old = mailbox(replay = 8, bytes = 12)
        val fresh = mailbox(replay = 8, bytes = 12)
        try {
            old.tryOffer("old-session", 8)
            old.finish(ProtocolEventMailbox.Terminal("old-terminated", 4))
            fresh.tryOffer("new-session", 8)
            assertEquals("new-session", withTimeout(1_000) { fresh.events.first() })
            assertEquals(listOf("old-terminated"), withTimeout(1_000) { old.events.toList() })
        } finally { old.finish(); fresh.finish() }
    }

    @Test(timeout = 15_000) fun countCapacityExcludesAnOldInFlightEntryOutsideReplay() = runBlocking {
        val box = ProtocolEventMailbox<String>(capacity = 2, replay = 0, maxRetainedBytes = 6, terminalReserveBytes = 4)
        val entered = CompletableDeferred<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            box.events.collect { entered.complete(Unit); withTimeout(5_000) { awaitCancellation() } }
        }
        try {
            box.tryOffer("in-flight", 1)
            withTimeout(1_000) { entered.await() }
            assertEquals(ProtocolEventMailbox.Admission.ACCEPTED, box.tryOffer("queued-a", 1))
            assertEquals(ProtocolEventMailbox.Admission.ACCEPTED, box.tryOffer("queued-b", 1))
            assertEquals(ProtocolEventMailbox.Admission.OVERFLOW, box.tryOffer("queued-c", 1))
            assertEquals(3L, box.retainedBytes)
        } finally { try { stop(collector) } finally { box.finish() } }
    }

    private fun mailbox(replay: Int, bytes: Long) = ProtocolEventMailbox<String>(
        capacity = 16, replay = replay, maxRetainedBytes = bytes, terminalReserveBytes = 4,
    )

    private suspend fun stop(job: Job) {
        job.cancel()
        withContext(NonCancellable) { withTimeout(1_000) { job.join() } }
    }
}
