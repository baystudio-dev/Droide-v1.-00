package com.baystudio.droide.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Deterministic store/resource ownership checks; no provider, terminal or native execution. */
class SubagentExecutionOwnershipRegressionTest {
    @Test fun everyExecutionTransitionRejectsStoppedTerminalNewerAndWrongStateOwnersWithoutWriting() = runBlocking {
        val transitions = listOf(
            SubagentTaskState.QUEUED to SubagentTaskState.STARTING,
            SubagentTaskState.STARTING to SubagentTaskState.RUNNING,
            SubagentTaskState.RUNNING to SubagentTaskState.IDLE,
        )
        for ((expected, next) in transitions) {
            val admitted = task().copy(state = expected)
            val rows = listOf(
                admitted.copy(state = SubagentTaskState.KILLED, delivery = SubagentDeliveryState.DELIVERED, error = "Parent Stop"),
                admitted.copy(state = SubagentTaskState.IDLE, result = "Already committed"),
                admitted.copy(attempt = admitted.attempt + 1),
                admitted.copy(state = if (expected == SubagentTaskState.QUEUED) SubagentTaskState.RUNNING else SubagentTaskState.QUEUED),
            )
            for (current in rows) {
                val root = Files.createTempDirectory("droide-owned-transition-").toFile()
                try {
                    var publications = 0
                    val store = SubagentTaskStore(root) { temporary, target ->
                        publications++
                        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        Unit
                    }
                    store.create(current)
                    val file = root.resolve(".droide/subagents/${current.id}.json")
                    val bytes = file.readBytes()
                    var transformCalled = false
                    val outcome = store.transition(admitted.id, admitted.attempt, expected) {
                        transformCalled = true
                        it.copy(state = next, result = "Must not publish")
                    }!!
                    assertFalse("$expected -> $next must reject $current", outcome.applied)
                    assertFalse(transformCalled)
                    assertEquals(current, outcome.record)
                    assertArrayEquals(bytes, file.readBytes())
                    assertEquals(1, publications)
                } finally { PathSecurity.deleteTreeNoFollow(root) }
            }
        }
    }

    @Test fun admittedAttemptAdvancesThroughExactStatesAndKeepsCreationAndStartTimes() = runBlocking {
        val root = Files.createTempDirectory("droide-owned-success-").toFile()
        try {
            val store = SubagentTaskStore(root)
            val admitted = store.create(task())
            val starting = store.transition(admitted.id, admitted.attempt, SubagentTaskState.QUEUED) {
                it.copy(state = SubagentTaskState.STARTING)
            }!!
            assertTrue(starting.applied)
            val running = store.transition(admitted.id, admitted.attempt, SubagentTaskState.STARTING) {
                it.copy(state = SubagentTaskState.RUNNING, startedAtMs = 300L)
            }!!
            assertTrue(running.applied)
            val completed = store.transition(admitted.id, admitted.attempt, SubagentTaskState.RUNNING) {
                it.copy(state = SubagentTaskState.IDLE, endedAtMs = 400L, result = "Owned result")
            }!!
            assertTrue(completed.applied)
            assertEquals(SubagentTaskState.IDLE, completed.record.state)
            assertEquals(admitted.attempt, completed.record.attempt)
            assertEquals(admitted.createdAtMs, completed.record.createdAtMs)
            assertEquals(300L, completed.record.startedAtMs)
            assertEquals(400L, completed.record.endedAtMs)
            assertEquals("Owned result", completed.record.result)
            assertEquals(completed.record, store.get(admitted.id))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun staleResumeCannotReplaceAnAlreadyAdmittedNewAttempt() = runBlocking {
        val root = Files.createTempDirectory("droide-owned-resume-").toFile()
        try {
            val store = SubagentTaskStore(root)
            val prior = store.create(task().copy(state = SubagentTaskState.ERROR))
            val first = store.transition(prior.id, prior.attempt, prior.state) {
                it.copy(state = SubagentTaskState.QUEUED, attempt = it.attempt + 1)
            }!!
            val duplicate = store.transition(prior.id, prior.attempt, prior.state) {
                error("A stale resume must not transform the new attempt")
            }!!
            assertTrue(first.applied)
            assertFalse(duplicate.applied)
            assertEquals(first.record, duplicate.record)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun onlyTheAdmittedBackgroundAttemptCanDeliverAPendingTerminalRecord() {
        val admitted = task()
        for (state in listOf(SubagentTaskState.IDLE, SubagentTaskState.ERROR, SubagentTaskState.KILLED, SubagentTaskState.INTERRUPTED)) {
            val completed = admitted.copy(state = state)
            assertTrue(SubagentManager.canDeliverCompletion(admitted, completed))
            for (wrong in listOf(
                completed.copy(attempt = admitted.attempt + 1),
                completed.copy(id = "task-someone-else"),
                completed.copy(parentSessionId = "another-parent"),
                completed.copy(childSessionId = "another-child"),
                completed.copy(background = false),
                completed.copy(delivery = SubagentDeliveryState.DELIVERED),
                completed.copy(delivery = SubagentDeliveryState.NONE),
            )) assertFalse(SubagentManager.canDeliverCompletion(admitted, wrong))
            assertFalse(SubagentManager.canDeliverCompletion(admitted.copy(background = false), completed))
        }
        for (state in listOf(SubagentTaskState.QUEUED, SubagentTaskState.STARTING, SubagentTaskState.RUNNING)) {
            assertFalse(SubagentManager.canDeliverCompletion(admitted, admitted.copy(state = state)))
        }
    }

    @Test fun delayedStopCannotCancelOrRewriteANewerAttempt() = runBlocking {
        val root = Files.createTempDirectory("droide-owned-stop-").toFile()
        try {
            val store = SubagentTaskStore(root)
            val stoppedAttempt = task().copy(state = SubagentTaskState.RUNNING)
            val newer = store.create(stoppedAttempt.copy(attempt = stoppedAttempt.attempt + 1))
            val file = root.resolve(".droide/subagents/${newer.id}.json")
            val bytes = file.readBytes()
            val ignored = store.cancelAttempt(stoppedAttempt)!!
            assertFalse(ignored.applied)
            assertEquals(newer, ignored.record)
            assertArrayEquals(bytes, file.readBytes())
            val cancellation = store.cancelAttempt(newer)!!
            assertTrue(cancellation.applied)
            val cancelled = cancellation.record
            assertEquals(newer.attempt, cancelled.attempt)
            assertEquals(SubagentTaskState.KILLED, cancelled.state)
            assertEquals(SubagentDeliveryState.DELIVERED, cancelled.delivery)
            assertEquals(newer.createdAtMs, cancelled.createdAtMs)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun cancellingAnAlreadyCommittedTerminalRecordPreservesItsExactBytesAndDelivery() = runBlocking {
        for (state in listOf(SubagentTaskState.IDLE, SubagentTaskState.ERROR, SubagentTaskState.INTERRUPTED, SubagentTaskState.KILLED)) {
            for (delivery in listOf(SubagentDeliveryState.PENDING, SubagentDeliveryState.DELIVERED)) {
                val root = Files.createTempDirectory("droide-terminal-stop-").toFile()
                try {
                    var publications = 0
                    val store = SubagentTaskStore(root) { temporary, target ->
                        publications++
                        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        Unit
                    }
                    val committed = store.create(task().copy(
                        state = state, delivery = delivery, result = "Keep committed result",
                        error = "Keep committed error", startedAtMs = 300L, endedAtMs = 400L,
                    ))
                    val file = root.resolve(".droide/subagents/${committed.id}.json")
                    val bytes = file.readBytes()
                    val stopped = store.cancelAttempt(committed.copy(state = SubagentTaskState.RUNNING))!!
                    assertFalse(stopped.applied)
                    assertEquals(committed, stopped.record)
                    assertArrayEquals(bytes, file.readBytes())
                    assertEquals(1, publications)
                } finally { PathSecurity.deleteTreeNoFollow(root) }
            }
        }
    }

    @Test fun cancellationOfEveryActiveStateCommitsKilledDeliveredForOnlyThatAttempt() = runBlocking {
        for (state in listOf(SubagentTaskState.QUEUED, SubagentTaskState.STARTING, SubagentTaskState.RUNNING)) {
            val root = Files.createTempDirectory("droide-active-stop-").toFile()
            try {
                val store = SubagentTaskStore(root)
                val active = store.create(task().copy(state = state))
                val stopped = store.cancelAttempt(active)!!
                assertTrue(stopped.applied)
                assertEquals(active.attempt, stopped.record.attempt)
                assertEquals(active.createdAtMs, stopped.record.createdAtMs)
                assertEquals(SubagentTaskState.KILLED, stopped.record.state)
                assertEquals(SubagentDeliveryState.DELIVERED, stopped.record.delivery)
                assertEquals(stopped.record, store.get(active.id))
            } finally { PathSecurity.deleteTreeNoFollow(root) }
        }
    }

    @Test fun partialBootstrapClosesEverySuccessfullyRegisteredResourceInReverseOrder() = runBlocking {
        val names = listOf("terminal", "lsp", "plugins", "child")
        for (acquired in 1..names.size) {
            val closed = mutableListOf<String>()
            val startup = IOException("Failure after resource $acquired")
            val resources = SubagentOwnedResources { _, failure -> throw AssertionError(failure) }
            val actual = try {
                resources.use {
                    names.take(acquired).forEach { name -> resources.register(name) { closed.add(name) } }
                    throw startup
                }
            } catch (failure: Throwable) { failure }
            assertSame(startup, actual)
            assertEquals(names.take(acquired).asReversed(), closed)
        }
    }

    @Test fun cleanupCancellationAndOrdinaryFailureCannotMaskThePrimaryOrSkipRemainingResources() = runBlocking {
        for (primary in listOf(CancellationException("Original Stop"), IOException("Original execution failure"))) {
            val closed = mutableListOf<String>()
            val reported = mutableListOf<Exception>()
            val cleanupCancel = CancellationException("Cleanup cancelled")
            val cleanupError = IOException("Cleanup failed")
            val resources = SubagentOwnedResources { _, failure -> reported.add(failure) }
            val actual = try {
                resources.use {
                    resources.register("terminal") { closed.add("terminal") }
                    resources.register("lsp") { closed.add("lsp"); throw cleanupError }
                    resources.register("child") { closed.add("child"); throw cleanupCancel }
                    throw primary
                }
            } catch (failure: Throwable) { failure }
            assertSame(primary, actual)
            assertEquals(listOf("child", "lsp", "terminal"), closed)
            assertEquals(listOf(cleanupCancel, cleanupError), primary.suppressed.toList())
            assertEquals(listOf(cleanupError), reported)
        }
    }

    @Test fun ordinaryCleanupFailureReportsWithoutReplacingACompletedResult() = runBlocking {
        val closed = mutableListOf<String>()
        val reported = mutableListOf<Pair<String, Exception>>()
        val cleanupError = IOException("Cannot close child")
        val resources = SubagentOwnedResources { operation, failure -> reported.add(operation to failure) }
        val result = resources.use {
            resources.register("terminal") { closed.add("terminal") }
            resources.register("child") { closed.add("child"); throw cleanupError }
            "Committed result"
        }
        assertEquals("Committed result", result)
        assertEquals(listOf("child", "terminal"), closed)
        assertEquals(listOf("child" to cleanupError), reported)
    }

    @Test fun cleanupOnlyCancellationIsRethrownAfterAllResourcesAreClosed() = runBlocking {
        val closed = mutableListOf<String>()
        val cancellation = CancellationException("Cancelled during cleanup")
        val resources = SubagentOwnedResources { _, failure -> throw AssertionError(failure) }
        val actual = try {
            resources.use {
                resources.register("terminal") { closed.add("terminal") }
                resources.register("child") { closed.add("child"); throw cancellation }
                "Completed"
            }
            null
        } catch (failure: Throwable) { failure }
        assertSame(cancellation, actual)
        assertEquals(listOf("child", "terminal"), closed)
    }

    @Test fun fatalCleanupCannotBeHiddenInsideARecoverablePrimaryFailure() = runBlocking {
        val primary = IOException("Recoverable execution failure")
        val fatal = AssertionError("Fatal cleanup failure")
        val closed = mutableListOf<String>()
        val resources = SubagentOwnedResources { _, failure -> throw AssertionError(failure) }
        val actual = try {
            resources.use {
                resources.register("terminal") { closed.add("terminal") }
                resources.register("child") { closed.add("child"); throw fatal }
                throw primary
            }
        } catch (failure: Throwable) { failure }
        assertSame(fatal, actual)
        assertTrue(fatal.suppressed.contains(primary))
        assertEquals(listOf("child", "terminal"), closed)
    }

    @Test fun primaryFatalErrorSurvivesCleanupCancellation() = runBlocking {
        val fatal = AssertionError("Fatal execution failure")
        val cleanupCancel = CancellationException("Cleanup cancelled")
        val closed = mutableListOf<String>()
        val resources = SubagentOwnedResources { _, failure -> throw AssertionError(failure) }
        val actual = try {
            resources.use {
                resources.register("terminal") { closed.add("terminal") }
                resources.register("child") { closed.add("child"); throw cleanupCancel }
                throw fatal
            }
        } catch (failure: Throwable) { failure }
        assertSame(fatal, actual)
        assertEquals(listOf(cleanupCancel), fatal.suppressed.toList())
        assertEquals(listOf("child", "terminal"), closed)
    }

    @Test fun aFailingCleanupReporterCannotSkipRemainingDisposers() = runBlocking {
        val reporting = CancellationException("Reporter cancelled")
        val closed = mutableListOf<String>()
        val resources = SubagentOwnedResources { _, _ -> throw reporting }
        val actual = try {
            resources.use {
                resources.register("terminal") { closed.add("terminal") }
                resources.register("child") { closed.add("child"); throw IOException("Close failed") }
                "Completed"
            }
            null
        } catch (failure: Throwable) { failure }
        assertSame(reporting, actual)
        assertEquals(listOf("child", "terminal"), closed)
    }

    private fun task() = SubagentTaskRecord(
        id = "task-owned-transition", parentSessionId = "parent-owned", childSessionId = "child-owned",
        profile = "explore", description = "Owned execution", background = true,
        state = SubagentTaskState.QUEUED, delivery = SubagentDeliveryState.PENDING,
        attempt = 3, createdAtMs = 100L, updatedAtMs = 200L,
    )
}
