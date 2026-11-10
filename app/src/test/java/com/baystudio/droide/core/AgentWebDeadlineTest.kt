package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class AgentWebDeadlineTest {
    private class TestTime {
        private val nanos = AtomicLong(0)
        fun nanoTime(): Long = nanos.get()
        fun advanceMs(milliseconds: Long) { nanos.addAndGet(milliseconds * 1_000_000L) }
    }

    /** Wall time is only a deadlock watchdog; budget assertions use the controlled clock. */
    private fun bounded(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(10_000, block)
    }

    @Test fun authorizationWaitLongerThanBudgetPreservesActiveBudget() = bounded {
        val time = TestTime()
        val entered = CompletableDeferred<Unit>()
        val approved = CompletableDeferred<Unit>()
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            withAgentWebDeadline(60_000, time::nanoTime) { deadline ->
                time.advanceMs(20_000)
                deadline.withoutTime {
                    entered.complete(Unit)
                    approved.await()
                }
                time.advanceMs(39_999)
                "authorized"
            }
        }
        try {
            entered.await()
            time.advanceMs(600_000)
            yield()
            assertFalse(operation.isCompleted)
        } finally { approved.complete(Unit) }
        assertEquals("authorized", operation.await())
        currentCoroutineContext().ensureActive()
    }

    @Test fun nestedDeadlinesExcludeAuthorizationFromBothBudgets() = bounded {
        val time = TestTime()
        val entered = CompletableDeferred<Unit>()
        val approved = CompletableDeferred<Unit>()
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            withAgentWebDeadline(120_000, time::nanoTime) { total ->
                time.advanceMs(10_000)
                withAgentWebDeadline(60_000, time::nanoTime) { attempt ->
                    time.advanceMs(20_000)
                    total.withoutTime {
                        attempt.withoutTime {
                            entered.complete(Unit)
                            approved.await()
                        }
                    }
                    time.advanceMs(39_999)
                    "result"
                }
            }
        }
        try {
            entered.await()
            time.advanceMs(1_200_000)
            yield()
            assertFalse(operation.isCompleted)
        } finally { approved.complete(Unit) }
        assertEquals("result", operation.await())
    }

    @Test fun searchRedirectAuthorizationExcludesBothAttemptAndTotalBudgets() = bounded {
        val time = TestTime()
        val entered = CompletableDeferred<Unit>()
        val approved = CompletableDeferred<Unit>()
        val redirect = "https://unrelated.example/verify"
        val requests = mutableListOf<String>()
        val transport = AgentWebTransport { url, beforeRequest ->
            requests += url
            beforeRequest(redirect)
            webHtml(redirect, webSearchHtml())
        }
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            AgentWebSearch(transport, nanoTime = time::nanoTime).search(AgentWebSearch.Request("docs"), { target ->
                assertEquals(redirect, target)
                entered.complete(Unit)
                approved.await()
            })
        }
        try {
            entered.await()
            time.advanceMs(100_000)
            yield()
            assertFalse(operation.isCompleted)
            assertEquals(1, requests.size)
        } finally { approved.complete(Unit) }
        val result = operation.await()
        assertEquals("duckduckgo", result.engine)
        assertEquals("https://example.com/source", result.hits.single().url)
        assertEquals(listOf("succeeded"), result.attempts.map { it.status })
        assertEquals(1, requests.size)
    }

    @Test fun activeBudgetBeforeAndAfterAuthorizationIsCumulative() = bounded {
        val time = TestTime()
        var expiredCallbackEntered = false
        val failure = runCatching {
            withAgentWebDeadline(60_000, time::nanoTime) { deadline ->
                time.advanceMs(20_000)
                deadline.withoutTime { time.advanceMs(600_000) }
                time.advanceMs(39_999)
                deadline.withoutTime { time.advanceMs(600_000) }
                time.advanceMs(1)
                deadline.withoutTime { expiredCallbackEntered = true }
            }
        }.exceptionOrNull()
        assertTrue(failure is AgentWebDeadlineExceeded)
        assertEquals(60_000L, (failure as AgentWebDeadlineExceeded).timeoutMs)
        assertFalse(expiredCallbackEntered)
        currentCoroutineContext().ensureActive()
    }

    @Test fun expiredBudgetRejectsAuthorizationBeforeTimerCanRun() = bounded {
        val time = TestTime()
        var callbackEntered = false
        val failure = runCatching {
            withAgentWebDeadline(60_000, time::nanoTime) { deadline ->

                time.advanceMs(60_000)
                deadline.withoutTime { callbackEntered = true }
            }
        }.exceptionOrNull()
        assertTrue(failure is AgentWebDeadlineExceeded)
        assertFalse(callbackEntered)
    }

    @Test fun completionRejectsWorkThatConsumedItsBudgetBeforeTimerCanRun() = bounded {
        val time = TestTime()
        val failure = runCatching {
            withAgentWebDeadline(60_000, time::nanoTime) {
                time.advanceMs(60_000)
                "too late"
            }
        }.exceptionOrNull()
        assertTrue(failure is AgentWebDeadlineExceeded)
        assertEquals(60_000L, (failure as AgentWebDeadlineExceeded).timeoutMs)
        currentCoroutineContext().ensureActive()
    }

    @Test fun timerCancelsSuspendedActiveWork() = bounded {
        val time = TestTime()
        var entered = false
        var cleanedUp = false
        val failure = runCatching {
            withAgentWebDeadline(60_000, time::nanoTime) {
                entered = true
                time.advanceMs(60_000)

                try { awaitCancellation() } finally { cleanedUp = true }
            }
        }.exceptionOrNull()
        assertTrue(entered)
        assertTrue(cleanedUp)
        assertTrue(failure is AgentWebDeadlineExceeded)
        assertEquals(60_000L, (failure as AgentWebDeadlineExceeded).timeoutMs)
        currentCoroutineContext().ensureActive()
    }

    @Test fun enclosingTimerExpiryIsNotTranslatedByNestedDeadline() = bounded {
        val time = TestTime()
        var innerFailure: Throwable? = null
        var authorizationExited = false
        val failure = runCatching {
            withAgentWebDeadline(60_000, time::nanoTime) {
                try {
                    withAgentWebDeadline(120_000, time::nanoTime) { attempt ->
                        // Only the inner budget pauses, so the enclosing timer must still expire.
                        attempt.withoutTime {
                            time.advanceMs(60_000)
                            try { awaitCancellation() } finally { authorizationExited = true }
                        }
                    }
                } catch (error: Throwable) {
                    innerFailure = error
                    throw error
                }
            }
        }.exceptionOrNull()
        assertTrue(authorizationExited)
        assertTrue(innerFailure is CancellationException)
        assertTrue(failure is AgentWebDeadlineExceeded)
        assertEquals(60_000L, (failure as AgentWebDeadlineExceeded).timeoutMs)
        currentCoroutineContext().ensureActive()
    }

    @Test fun innerExpiryCanBeHandledWithoutCancellingEnclosingDeadline() = bounded {
        val time = TestTime()
        val result = withAgentWebDeadline(120_000, time::nanoTime) { total ->
            val failure = runCatching {
                withAgentWebDeadline(60_000, time::nanoTime) {
                    time.advanceMs(60_000)
                }
            }.exceptionOrNull()
            assertTrue(failure is AgentWebDeadlineExceeded)
            assertEquals(60_000L, (failure as AgentWebDeadlineExceeded).timeoutMs)
            currentCoroutineContext().ensureActive()
            total.withoutTime { time.advanceMs(600_000) }
            time.advanceMs(59_999)
            "recovered"
        }
        assertEquals("recovered", result)
    }

    @Test fun parentCancellationInterruptsAuthorizationAndReleasesTransferCapacity() = bounded {
        val time = TestTime()
        val entered = Channel<Int>(Channel.UNLIMITED)
        val exited = Channel<Int>(Channel.UNLIMITED)
        val parent = Job(currentCoroutineContext().job)
        val parentScope = CoroutineScope(currentCoroutineContext() + parent)
        val cancelled = CancellationException("Caller cancelled authorization")
        val operations = List(AgentWebWork.MAX_TRANSFERS) { index ->
            parentScope.async(start = CoroutineStart.UNDISPATCHED) {
                AgentWebWork.transfer {
                    withAgentWebDeadline(60_000, time::nanoTime) { deadline ->
                        deadline.withoutTime {
                            entered.send(index)
                            try { awaitCancellation() } finally { check(exited.trySend(index).isSuccess) }
                        }
                    }
                }
            }
        }
        try {
            assertEquals(operations.indices.toSet(), List(operations.size) { entered.receive() }.toSet())
            val replacement = async(start = CoroutineStart.UNDISPATCHED) {
                AgentWebWork.transfer { withAgentWebDeadline(60_000, time::nanoTime) { "replacement" } }
            }
            assertFalse(replacement.isCompleted)
            time.advanceMs(600_000)
            parent.cancel(cancelled)
            operations.forEach { operation ->
                val failure = runCatching { operation.await() }.exceptionOrNull()
                assertTrue(failure is CancellationException)
                assertEquals(cancelled.message, failure?.message)
            }
            assertEquals(operations.indices.toSet(), List(operations.size) { exited.receive() }.toSet())
            assertEquals("replacement", replacement.await())

            val release = CompletableDeferred<Unit>()
            val future = List(AgentWebWork.MAX_TRANSFERS) { index ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    AgentWebWork.transfer {
                        withAgentWebDeadline(60_000, time::nanoTime) {
                            entered.send(index)
                            release.await()
                            index
                        }
                    }
                }
            }
            try {
                assertEquals(future.indices.toSet(), List(future.size) { entered.receive() }.toSet())
                assertTrue(future.none { it.isCompleted })
            } finally { release.complete(Unit) }
            assertEquals(future.indices.toList(), future.awaitAll())
        } finally { parent.cancelAndJoin() }
    }

    @Test fun completionStopsOwnedTimerAndLeavesParentUsable() = bounded {
        val time = TestTime()
        lateinit var ownedJob: Job
        val result = withAgentWebDeadline(60_000, time::nanoTime) {
            ownedJob = currentCoroutineContext().job
            yield() // Allow the owned timer to start waiting before completing the operation.
            "complete"
        }
        assertEquals("complete", result)
        assertTrue(ownedJob.isCompleted)
        assertTrue(ownedJob.children.none())
        time.advanceMs(600_000)
        yield()
        currentCoroutineContext().ensureActive()
        assertEquals("next", withAgentWebDeadline(60_000, time::nanoTime) { "next" })
    }
}
