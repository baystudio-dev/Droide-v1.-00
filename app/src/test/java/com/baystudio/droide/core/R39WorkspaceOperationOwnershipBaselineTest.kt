package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Tests the existing ownership primitives, not a constructed Agent capability bridge. */
class R39WorkspaceOperationOwnershipBaselineTest {
    private enum class Kind { CONTRIBUTED, NEXT }

    @Test(timeout = 10_000) fun markedAgentStopKeepsAdmissionUntilCleanupAndLeavesUnrelatedWorkAlive() = runBlocking {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Default)
        val gate = BuildRunDebugOperationGate<Kind>()
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cleaned = false
        val unrelated = scope.async { awaitCancellation() }
        val operation = scope.async {
            gate.run(Kind.CONTRIBUTED) {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) {
                        cleaning.complete(Unit)
                        withTimeout(2_000) { release.await() }
                        cleaned = true
                    }
                }
            }
        }
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(AgentOwnedWorkspaceOperation()) { awaitWorkspaceOperation(operation) }
        }
        try {
            withTimeout(2_000) { entered.await() }
            observer.cancel(CancellationException("Agent Stop"))
            withTimeout(2_000) { cleaning.await() }
            assertFalse(observer.isCompleted)
            assertFalse(operation.isCompleted)
            assertTrue(unrelated.isActive)
            assertTrue(runCatching { gate.run(Kind.NEXT) { Unit } }.exceptionOrNull() is IllegalStateException)
            release.complete(Unit)
            withTimeout(2_000) { observer.join() }
            assertTrue(operation.isCancelled)
            assertTrue(operation.isCompleted)
            assertTrue(cleaned)
            assertTrue(unrelated.isActive)
            assertEquals("next", gate.run(Kind.NEXT) { "next" })
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) {
                withTimeout(2_000) { observer.cancelAndJoin(); scopeJob.cancelAndJoin() }
            }
        }
    }

    @Test(timeout = 10_000) fun unmarkedUiNavigationRetainsWorkUntilExplicitGateStop() = runBlocking {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Default)
        val gate = BuildRunDebugOperationGate<Kind>()
        val entered = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val unrelated = scope.async { awaitCancellation() }
        val operation = scope.async {
            gate.run(Kind.CONTRIBUTED) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cleaned.complete(Unit) }
            }
        }
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { awaitWorkspaceOperation(operation) }
        try {
            withTimeout(2_000) { entered.await(); observer.cancelAndJoin() }
            assertTrue(operation.isActive)
            assertFalse(cleaned.isCompleted)
            assertTrue(gate.cancelActive("Explicit workspace Stop"))
            withTimeout(2_000) { operation.join() }
            assertTrue(operation.isCancelled)
            assertTrue(cleaned.isCompleted)
            assertTrue(unrelated.isActive)
            assertFalse(gate.cancelActive("Already stopped"))
        } finally {
            withContext(NonCancellable) {
                withTimeout(2_000) { observer.cancelAndJoin(); scopeJob.cancelAndJoin() }
            }
        }
    }
}
