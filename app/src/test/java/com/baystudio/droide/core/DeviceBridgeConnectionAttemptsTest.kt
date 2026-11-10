package com.baystudio.droide.core

import java.io.Closeable
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DeviceBridgeConnectionAttemptsTest {
    @Test fun cancelledAttemptClosesOwnedResourcesOnceAndRejectsLatePublication() {
        val attempts = DeviceBridgeConnectionAttempts()
        val token = attempts.begin {}
        var closes = 0
        attempts.own(token, Closeable { closes++ })
        assertTrue(attempts.cancel(token))
        assertFalse(attempts.cancel(token))
        assertFalse(attempts.commit(token) { fail("stale publication") })
        assertEquals(1, closes)
    }

    @Test fun registrationAfterCancellationClosesImmediately() {
        val attempts = DeviceBridgeConnectionAttempts()
        val token = attempts.begin {}
        attempts.cancel(token)
        var closes = 0
        assertTrue(runCatching { attempts.own(token, Closeable { closes++ }) }.isFailure)
        assertEquals(1, closes)
    }

    @Test fun commitTransfersOnlyPublishedCandidateAndCancelCannotCloseIt() {
        val attempts = DeviceBridgeConnectionAttempts()
        val token = attempts.begin {}
        var publishedCloses = 0
        var temporaryCloses = 0
        val published = Closeable { publishedCloses++ }
        attempts.own(token, published)
        attempts.own(token, Closeable { temporaryCloses++ })
        assertTrue(attempts.commit(token, published) {})
        assertFalse(attempts.cancel(token))
        assertEquals(0, publishedCloses)
        assertEquals(1, temporaryCloses)
    }

    @Test fun oldFailureCannotAlterNewAttempt() {
        val attempts = DeviceBridgeConnectionAttempts()
        val old = attempts.begin {}
        attempts.cancel(old)
        val next = attempts.begin {}
        assertFalse(attempts.cancel(old) { fail("old cleanup") })
        assertSame(next, attempts.active())
    }

    @Test fun failedPublicationRetainsOwnedResourceUntilFailureCleanup() {
        val attempts = DeviceBridgeConnectionAttempts()
        val token = attempts.begin {}
        var closes = 0
        attempts.own(token, Closeable { closes++ })
        assertTrue(runCatching { attempts.commit(token) { error("publish failed") } }.isFailure)
        attempts.cancel(token)
        assertEquals(1, closes)
    }

    @Test fun commitBeforeCallerResumeMakesExplicitCancelANoOp() = runBlocking {
        val attempts = DeviceBridgeConnectionAttempts()
        lateinit var token: DeviceBridgeConnectionAttempts.Token
        lateinit var result: CancellableContinuation<String>
        var callerCancellations = 0
        val caller = async(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine<String> { continuation ->
                result = continuation
                token = attempts.begin(cancelCaller = {
                    callerCancellations++
                    continuation.cancel()
                }) {}
            }
        }
        assertTrue(attempts.commit(token) {})

        assertFalse(attempts.requestCancel(token) { fail("cancel must not publish after commit") })
        assertEquals(0, callerCancellations)
        result.resume("connected")
        assertEquals("connected", caller.await())
    }

    @Test fun staleCleanupCannotClearOrCancelNewerCallerHandle() {
        val attempts = DeviceBridgeConnectionAttempts()
        var oldCancellations = 0
        var nextCancellations = 0
        val old = attempts.begin(cancelCaller = { oldCancellations++ }) {}
        attempts.invalidate {}
        val next = attempts.begin(cancelCaller = { nextCancellations++ }) {}
        assertFalse(attempts.cancel(old))
        assertFalse(attempts.requestCancel(old))
        assertFalse(attempts.commit(old) { fail("stale publication") })
        assertSame(next, attempts.active())
        assertEquals(1, oldCancellations)
        assertEquals(0, nextCancellations)
        assertTrue(attempts.requestCancel(next))
        assertEquals(1, nextCancellations)
    }

    @Test fun explicitCancelBeforeCommitWinsExactlyOnce() {
        val attempts = DeviceBridgeConnectionAttempts()
        var callerCancellations = 0
        val token = attempts.begin(cancelCaller = { callerCancellations++ }) {}
        assertTrue(attempts.requestCancel(token))
        assertFalse(attempts.requestCancel(token))
        assertFalse(attempts.commit(token) { fail("cancelled publication") })
        assertEquals(1, callerCancellations)
    }

    @Test fun closeFencesFutureAttemptsAndCleansUpDespiteObserverFailure() {
        val attempts = DeviceBridgeConnectionAttempts()
        val token = attempts.begin {}
        var closes = 0
        attempts.own(token, Closeable { closes++ })
        assertTrue(runCatching { attempts.close { error("observer") } }.isFailure)
        assertEquals(1, closes)
        assertTrue(runCatching { attempts.begin {} }.isFailure)
        assertFalse(attempts.commit(token) {})
    }
}
