package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceOperationAwaitTest {
    @Test(timeout = 10_000) fun agentStopCancelsAndReapsOnlyItsOwnedRetainedOperation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val entered = CompletableDeferred<Unit>()
            val cleaned = CompletableDeferred<Unit>()
            val unrelated = scope.async { awaitCancellation() }
            val operation = scope.async { entered.complete(Unit); try { awaitCancellation() } finally { cleaned.complete(Unit) } }
            val observer = launch(start = CoroutineStart.UNDISPATCHED) { withContext(AgentOwnedWorkspaceOperation()) { awaitWorkspaceOperation(operation) } }
            withTimeout(2_000) { entered.await() }
            withTimeout(2_000) { observer.cancelAndJoin() }
            assertTrue(operation.isCancelled); assertTrue(cleaned.isCompleted)
            assertTrue(unrelated.isActive)
        } finally { scope.cancel() }
    }

    @Test(timeout = 10_000) fun uiNavigationCancelsOnlyTheObserverAndRetainsWorkspaceBuild() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val finish = CompletableDeferred<String>()
            val operation = scope.async { finish.await() }
            val entered = CompletableDeferred<Unit>()
            val observer = launch { entered.complete(Unit); awaitWorkspaceOperation(operation) }
            entered.await(); observer.cancelAndJoin()
            assertTrue(operation.isActive)
            finish.complete("artifact-ready")
            assertEquals("artifact-ready", operation.await())
        } finally { scope.cancel() }
    }

    @Test fun successAndFailureRemainActualOperationEvidence() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withContext(AgentOwnedWorkspaceOperation()) {
                assertEquals(42, awaitWorkspaceOperation(scope.async { 42 }))
                val failure = IllegalStateException("Gradle failed")
                try { awaitWorkspaceOperation(scope.async { throw failure }); fail("Expected operation failure") }
                catch (actual: IllegalStateException) {
                    assertEquals(failure.javaClass, actual.javaClass)
                    assertEquals(failure.message, actual.message)
                    // Coroutine stack recovery may copy the exception while retaining its cause.
                    assertTrue(actual === failure || actual.cause === failure)
                }
            }
        } finally { scope.cancel() }
    }
}
