package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UserToolchainEnvironmentCleanupTest {
    @Test fun cleanReceiptPreservesResult() = runBlocking {
        assertEquals(42, UserToolchainEnvironmentCleanup.capture { completed -> completed(null); 42 })
    }

    @Test fun failedReceiptRejectsOtherwiseSuccessfulCapture() = runBlocking {
        val receipt = IllegalStateException("private cleanup detail")
        try {
            UserToolchainEnvironmentCleanup.capture { completed -> completed(receipt); 42 }
            fail("A failed receipt must not return a successful result")
        } catch (failure: UserEnvironmentCleanupUnconfirmed) {
            assertSame(receipt, failure.cause)
            assertFalse(failure.message!!.contains("private cleanup detail"))
        }
    }

    @Test fun failedReceiptOverridesOperationException() = runBlocking {
        val receipt = IllegalStateException("cleanup")
        val operation = IllegalArgumentException("operation")
        try {
            UserToolchainEnvironmentCleanup.capture<Unit> { completed ->
                try { throw operation } finally { completed(receipt) }
            }
            fail("Unknown cleanup must not become an ordinary capture failure")
        } catch (failure: UserEnvironmentCleanupUnconfirmed) {
            assertSame(receipt, failure.cause)
            assertTrue(failure.suppressed.any { it === operation })
        }
    }

    @Test fun ordinaryOperationFailureWithoutFailedReceiptIsPreserved() = runBlocking {
        val operation = IllegalArgumentException("operation")
        try {
            UserToolchainEnvironmentCleanup.capture<Unit> { completed ->
                try { throw operation } finally { completed(null) }
            }
            fail("Expected operation failure")
        } catch (failure: IllegalArgumentException) { assertSame(operation, failure) }
    }

    @Test fun cacheDoesNotSwallowOrCacheFailedCleanup() = runBlocking {
        val cache = UserToolchainEnvironmentCache()
        var calls = 0
        repeat(2) {
            try {
                cache.resolve("key", "/work") {
                    calls++
                    UserToolchainEnvironmentCleanup.capture { completed ->
                        completed(IllegalStateException("cleanup"))
                        UserToolchainEnvironmentCache.Capture("", 1)
                    }
                }
                fail("Cleanup failure must escape cache fallback")
            } catch (_: UserEnvironmentCleanupUnconfirmed) { }
        }
        assertEquals(2, calls)
    }

    @Test fun ownTimeoutWithFailedCleanupIsFatalNotFallback() = runBlocking {
        val cache = UserToolchainEnvironmentCache(timeoutMs = 20)
        try {
            cache.resolve("timeout", "/work") {
                UserToolchainEnvironmentCleanup.capture { completed ->
                    try { awaitCancellation() }
                    finally { completed(IllegalStateException("timeout cleanup")) }
                }
            }
            fail("Own timeout must not conceal unknown cleanup")
        } catch (_: UserEnvironmentCleanupUnconfirmed) { }
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun parentCancellationWinsAndDoesNotCacheFailedCleanup() = runBlocking {
        val cache = UserToolchainEnvironmentCache()
        val entered = CompletableDeferred<Unit>()
        var cancellationObserved = false
        val child = launch {
            try {
                cache.resolve("cancel", "/work") {
                    UserToolchainEnvironmentCleanup.capture { completed ->
                        entered.complete(Unit)
                        try { awaitCancellation() }
                        finally { completed(IllegalStateException("cancel cleanup")) }
                    }
                }
                fail("Cancelled lookup must not return")
            } catch (cancelled: CancellationException) {
                cancellationObserved = true
                assertTrue(cancelled.suppressed.any { it is UserEnvironmentCleanupUnconfirmed })
                throw cancelled
            }
        }
        entered.await(); child.cancelAndJoin()
        assertTrue(child.isCancelled)
        assertTrue(cancellationObserved)
        var retried = false
        val result = cache.resolve("cancel", "/work") {
            retried = true
            UserToolchainEnvironmentCache.Capture("", 1)
        }
        assertTrue(retried)
        assertEquals(UserToolchainEnvironmentCache.Fallback.SHELL_FAILED, result.fallback)
    }
}
