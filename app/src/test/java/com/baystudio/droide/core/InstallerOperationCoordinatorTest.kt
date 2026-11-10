package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class InstallerOperationCoordinatorTest {
    @Test fun nestedPreparationAndActivationKeepOneOwnerUntilRestorationFinishes() = runBlocking {
        val coordinator = InstallerOperationCoordinator()
        val stages = mutableListOf<String>()
        var starts = 0
        var finishes = 0
        withTimeout(5_000) {
            coordinator.operation(
                onStart = { starts++; stages += "start" },
                onFinish = { failure ->
                    assertNull(failure)
                    assertFalse(coordinator.active)
                    finishes++
                    stages += "ready"
                },
            ) {
                coordinator.operation(onStart = { fail("Nested step must retain its owner") }) {
                    stages += "activate"
                    assertTrue(coordinator.active)
                    assertTrue(coordinator.ownsCurrentOperation())
                }
                try { stages += "installed" } finally { stages += "restore default" }
            }
        }
        assertEquals(listOf("start", "activate", "installed", "restore default", "ready"), stages)
        assertEquals(1, starts)
        assertEquals(1, finishes)
    }

    @Test fun failedActivationPublishesFailureReleasesBusyStateAndAllowsRetry() = runBlocking {
        val coordinator = InstallerOperationCoordinator()
        val failure = IllegalStateException("Package verification failed")
        var terminalFailure: Throwable? = null
        try {
            coordinator.operation(onFinish = { terminalFailure = it; assertFalse(coordinator.active) }) {
                assertTrue(coordinator.active)
                throw failure
            }
            fail("Failed activation must propagate")
        } catch (actual: IllegalStateException) { assertRetainsFailure(failure, actual) }
        assertRetainsFailure(failure, terminalFailure)
        assertFalse(coordinator.active)
        assertEquals("retried", coordinator.operation { "retried" })
    }

    @Test fun cancellationWaitsForRollbackBeforeTerminalStateAndNextSnapshot() = runBlocking {
        val coordinator = InstallerOperationCoordinator()
        val started = CompletableDeferred<Unit>()
        val rollbackEntered = CompletableDeferred<Unit>()
        val rollbackComplete = CompletableDeferred<Unit>()
        val stages = mutableListOf<String>()
        val operation = launch {
            coordinator.operation(onFinish = {
                assertTrue(it is CancellationException)
                stages += "canceled"
            }) {
                started.complete(Unit)
                try { awaitCancellation() } finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        stages += "rollback"
                        rollbackEntered.complete(Unit)
                        rollbackComplete.await()
                    }
                }
            }
        }
        started.await()
        coordinator.cancel()
        rollbackEntered.await()
        assertTrue(coordinator.active)
        val readerStarted = CompletableDeferred<Unit>()
        val snapshot = async {
            readerStarted.complete(Unit)
            coordinator.snapshot { stages += "snapshot"; assertFalse(coordinator.active) }
        }
        readerStarted.await()
        assertFalse(snapshot.isCompleted)
        rollbackComplete.complete(Unit)
        operation.join()
        snapshot.await()
        assertEquals(listOf("rollback", "canceled", "snapshot"), stages)
    }

    @Test fun asynchronousRefreshCannotPublishBetweenMutationAndCommit() = runBlocking {
        val coordinator = InstallerOperationCoordinator()
        val mutated = CompletableDeferred<Unit>()
        val commit = CompletableDeferred<Unit>()
        var revision = 0
        var published = -1
        val operation = async {
            coordinator.operation {
                revision = 1
                mutated.complete(Unit)
                commit.await()
                revision = 2
            }
        }
        mutated.await()
        val refreshStarted = CompletableDeferred<Unit>()
        val refresh = async {
            refreshStarted.complete(Unit)
            coordinator.snapshot { published = revision }
        }
        refreshStarted.await()
        assertEquals(-1, published)
        commit.complete(Unit)
        operation.await()
        refresh.await()
        assertEquals(2, published)
    }

    @Test fun refreshOwnsItsWholeReadAndPublishesBeforeFollowingMutation() = runBlocking {
        val coordinator = InstallerOperationCoordinator()
        val read = CompletableDeferred<Unit>()
        val publish = CompletableDeferred<Unit>()
        val stages = mutableListOf<String>()
        val refresh = async {
            coordinator.snapshot {
                stages += "read"
                read.complete(Unit)
                publish.await()
                stages += "publish"
            }
        }
        read.await()
        val queued = CompletableDeferred<Unit>()
        val operation = async {
            queued.complete(Unit)
            coordinator.operation { stages += "mutate" }
        }
        queued.await()
        assertEquals(listOf("read"), stages)
        publish.complete(Unit)
        refresh.await()
        operation.await()
        assertEquals(listOf("read", "publish", "mutate"), stages)
    }

    @Test fun resolverCanRefreshWithinItsOwnTransactionWithoutDeadlock() = runBlocking {
        val coordinator = InstallerOperationCoordinator()
        val result = withTimeout(5_000) {
            coordinator.operation {
                coordinator.snapshot { assertTrue(coordinator.active); "fresh catalog" }
            }
        }
        assertEquals("fresh catalog", result)
    }

    @Test fun closeCancelsActiveOperationAndRejectsQueuedAndNewWork() = runBlocking {
        val coordinator = InstallerOperationCoordinator()
        val started = CompletableDeferred<Unit>()
        val operation = launch { coordinator.operation { started.complete(Unit); awaitCancellation() } }
        started.await()
        val queued = async {
            try {
                coordinator.operation { fail("Closed manager must not begin queued work") }
                false
            } catch (_: IllegalStateException) { true }
        }
        coordinator.close()
        operation.join()
        assertTrue(queued.await())
        assertFalse(coordinator.active)
        assertTrue(coordinator.isClosed)
        try { coordinator.snapshot { fail("Closed manager must not publish") }; fail("Closed read accepted") }
        catch (_: IllegalStateException) { }
        coordinator.close()
    }

    @Test fun cancellationDuringStartAlsoReachesTerminalHook() = runBlocking {
        val coordinator = InstallerOperationCoordinator()
        var terminal: Throwable? = null
        val operation = launch {
            coordinator.operation(
                onStart = { coordinator.cancel() },
                onFinish = { terminal = it; assertFalse(coordinator.active) },
            ) { fail("Canceled start must not mutate") }
        }
        operation.join()
        assertTrue(terminal is CancellationException)
        assertFalse(coordinator.active)
        assertEquals("next", coordinator.operation { "next" })
    }

    @Test fun canceledWaiterDoesNotCancelTheActiveOwner() = runBlocking {
        val coordinator = InstallerOperationCoordinator()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val owner = async { coordinator.operation { started.complete(Unit); finish.await(); "committed" } }
        started.await()
        val waiter = launch { coordinator.operation { fail("Canceled queued mutation ran") } }
        waiter.cancelAndJoin()
        assertTrue(coordinator.active)
        finish.complete(Unit)
        assertEquals("committed", owner.await())
    }

    @Test fun closeCancelsARefreshAndRejectsItsLatePublication() = runBlocking {
        val coordinator = InstallerOperationCoordinator()
        val started = CompletableDeferred<Unit>()
        val refresh = launch {
            coordinator.snapshot { started.complete(Unit); awaitCancellation() }
        }
        started.await()
        coordinator.close()
        refresh.join()
        assertTrue(refresh.isCancelled)
        var published = false
        assertFalse(coordinator.publish { published = true })
        assertFalse(published)
    }

    @Test fun originalFailureIsPreservedIfTerminalPublicationAlsoFails() = runBlocking {
        val coordinator = InstallerOperationCoordinator()
        val original = IllegalStateException("Verification failed")
        val publication = IllegalArgumentException("Publication failed")
        try {
            coordinator.operation(onFinish = { throw publication }) { throw original }
            fail("Failure must propagate")
        } catch (actual: IllegalStateException) {
            assertRetainsFailure(original, actual)
            // Coroutine stack recovery can wrap the original in a copy whose cause retains the suppressed
            // publication failure.
            val suppressed = generateSequence(actual as Throwable) { it.cause }
                .filter { it.javaClass == original.javaClass && it.message == original.message }
                .flatMap { it.suppressed.asSequence() }.distinct().toList()
            assertEquals(1, suppressed.size)
            assertRetainsFailure(publication, suppressed.single())
        }
        assertFalse(coordinator.active)
    }

    @Test fun republishingTheSameFailureCannotReplaceItWithSelfSuppressionError() = runBlocking {
        val coordinator = InstallerOperationCoordinator()
        val original = IllegalStateException("Verification failed")
        try {
            coordinator.operation(onFinish = { throw original }) { throw original }
            fail("Failure must propagate")
        } catch (actual: IllegalStateException) {
            assertRetainsFailure(original, actual)
        }
        assertFalse(coordinator.active)
    }
}

/** Coroutine debug recovery may copy exceptions; verify the failure type and reason. */
private fun assertRetainsFailure(expected: Throwable, actual: Throwable?) {
    assertNotNull("Original failure was lost", actual)
    assertEquals(expected.javaClass, actual!!.javaClass)
    assertEquals(expected.message, actual.message)
}
