package com.baystudio.droide.core

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class BackgroundSourceLaunchTest {
    @get:org.junit.Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private val owner = OwnedResponseContentStore.Owner("session", "run", "call")
    private class Terminal : ITerminalSession by AgentWorkflowTerminal() {
        val calls = AtomicInteger()
        @Volatile var persisted = false
        @Volatile var failed = false
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult {
            check(persisted)
            calls.incrementAndGet()
            if (cmd == "active fixture") awaitCancellation()
            if (failed) throw java.io.IOException("FAILED E2BIG deliberately ambiguous fixture")
            return ExecResult(0, cmd, false)
        }
    }
    private fun launch(manager: BackgroundCommandManager, current: () -> Unit = {}, persist: (BackgroundCommandManager.SourceLaunchIntent) -> Unit) =
        manager.startSource("printf retained", owner, "a".repeat(64), requireCurrent = current, persistIntent = persist)

    @Test fun durableIntentPrecedesActivationAndReceiptCannotCrossManagers() = runBlocking {
        val terminal = Terminal(); val manager = BackgroundCommandManager(terminal)
        val other = BackgroundCommandManager(Terminal())
        try {
            val receipt = launch(manager) { intent ->
                assertEquals(0, terminal.calls.get())
                assertNotNull(manager.snapshot(intent.jobId))
                terminal.persisted = true
            }
            assertEquals(BackgroundCommandManager.SourceActivation.ACTIVATED, receipt.activation)
            assertSame(receipt.intent, manager.verifySourceLaunch(receipt))
            assertTrue(runCatching { other.verifySourceLaunch(receipt) }.isFailure)
            withTimeout(2000) { while (terminal.calls.get() == 0) delay(5) }
            assertEquals(1, terminal.calls.get())
        } finally { manager.close(); other.close() }
    }

    @Test fun persistenceFailureDiscardsOnlyOwnedInertEntry() {
        val terminal = Terminal(); val manager = BackgroundCommandManager(terminal)
        try {
            val failure = runCatching { launch(manager) { throw java.io.IOException("disk full") } }.exceptionOrNull()
                as BackgroundCommandManager.SourceLaunchFailure
            assertEquals(BackgroundCommandManager.SourceActivation.NO_COMMAND_ACTIVATION, failure.receipt.activation)
            manager.verifySourceLaunch(failure.receipt)
            assertTrue(manager.list().isEmpty()); assertEquals(0, terminal.calls.get())
        } finally { manager.close() }
    }

    @Test fun killCloseAndOwnerCancellationDuringPersistenceNeverActivate() {
        repeat(3) { mode ->
            val terminal = Terminal(); val manager = BackgroundCommandManager(terminal); val ownerJob = Job()
            try {
                val failure = runCatching {
                    launch(manager, { ownerJob.ensureActive() }) { intent ->
                        when (mode) {
                            0 -> runBlocking { manager.kill(intent.jobId) }
                            1 -> manager.close()
                            else -> ownerJob.cancel()
                        }
                    }
                }.exceptionOrNull()
                val receipt = when (failure) {
                    is BackgroundCommandManager.SourceLaunchCancelled -> failure.receipt
                    is BackgroundCommandManager.SourceLaunchFailure -> failure.receipt
                    else -> error("Expected typed no-activation evidence: $failure")
                }
                assertEquals(BackgroundCommandManager.SourceActivation.NO_COMMAND_ACTIVATION, receipt.activation)
                assertEquals(0, terminal.calls.get()); assertTrue(manager.list().isEmpty())
            } finally { manager.close(); ownerJob.cancel() }
        }
    }

    @Test fun persistenceFailureCannotRemoveUnrelatedLegacyJob() = runBlocking {
        val terminal = Terminal().apply { persisted = true }; val manager = BackgroundCommandManager(terminal)
        try {
            val legacy = manager.start("legacy unchanged")
            withTimeout(2000) { while (terminal.calls.get() == 0) delay(5) }
            val failure = runCatching { launch(manager) { throw java.io.IOException("disk full") } }.exceptionOrNull()
            assertTrue(failure is BackgroundCommandManager.SourceLaunchFailure)
            assertNotNull(manager.snapshot(legacy.id))
            assertEquals(listOf(legacy.id), manager.list().map { it.id })
            assertEquals(1, terminal.calls.get())
        } finally { manager.close() }
    }

    @Test fun failedPersistenceAtLastSlotRetainsCompletedOutputsAndActiveJob() = runBlocking {
        val terminal = Terminal().apply { persisted = true }; val manager = BackgroundCommandManager(terminal)
        try {
            repeat(BackgroundCommandManager.MAX_JOBS - 2) { index ->
                val entry = manager.start("completed-$index")
                withTimeout(2000) { while (manager.snapshot(entry.id)?.state == BackgroundCommandManager.State.RUNNING) delay(1) }
            }
            val active = manager.start("active fixture")
            withTimeout(2000) { while (terminal.calls.get() < BackgroundCommandManager.MAX_JOBS - 1) delay(1) }
            val before = manager.list()
            val failure = runCatching { launch(manager) { throw java.io.IOException("disk full") } }.exceptionOrNull()
            assertTrue(failure is BackgroundCommandManager.SourceLaunchFailure)
            assertEquals(before, manager.list())
            assertEquals(BackgroundCommandManager.State.RUNNING, manager.snapshot(active.id)?.state)
            assertEquals(BackgroundCommandManager.MAX_JOBS - 1, terminal.calls.get())
        } finally { manager.closeAndJoin() }
    }

    @Test fun saturatedHistoryFailedPersistenceKeepsEveryPriorOutput() = runBlocking {
        val terminal = Terminal().apply { persisted = true }; val manager = BackgroundCommandManager(terminal)
        try {
            repeat(BackgroundCommandManager.MAX_JOBS) { index ->
                val entry = manager.start("retained-$index")
                withTimeout(2000) { while (manager.snapshot(entry.id)?.state == BackgroundCommandManager.State.RUNNING) delay(1) }
            }
            val before = manager.list()
            var persisted = false
            assertTrue(runCatching { launch(manager) { persisted = true; error("disk full") } }.isFailure)
            assertTrue(persisted)
            assertEquals(before, manager.list())
            assertEquals(BackgroundCommandManager.MAX_JOBS, terminal.calls.get())
        } finally { manager.close() }
    }

    @Test fun saturatedActivationPrunesExactlyOneAndReceiptSurvivesLaterPruning() = runBlocking {
        val terminal = Terminal().apply { persisted = true }; val manager = BackgroundCommandManager(terminal)
        try {
            repeat(BackgroundCommandManager.MAX_JOBS) { index ->
                val entry = manager.start("retained-$index")
                withTimeout(2000) { while (manager.snapshot(entry.id)?.state == BackgroundCommandManager.State.RUNNING) delay(1) }
            }
            val before = manager.list()
            val receipt = launch(manager) { intent ->
                assertEquals(BackgroundCommandManager.MAX_JOBS + 1, manager.list().size)
                assertEquals(before, manager.list().filter { it.id != intent.jobId })
            }
            assertEquals(BackgroundCommandManager.MAX_JOBS, manager.list().size)
            assertNull(manager.snapshot(before.single { it.command == "retained-0" }.id))
            before.filter { it.command != "retained-0" }.forEach { assertNotNull(manager.snapshot(it.id)) }
            withTimeout(2000) { while (manager.inspectSourceLaunch(receipt).snapshot.state == BackgroundCommandManager.State.RUNNING) delay(1) }
            repeat(BackgroundCommandManager.MAX_JOBS) { index ->
                val entry = manager.start("later-$index")
                withTimeout(2000) { while (manager.snapshot(entry.id)?.state == BackgroundCommandManager.State.RUNNING) delay(1) }
            }
            assertNull(manager.snapshot(receipt.intent.jobId))
            assertEquals(BackgroundCommandManager.State.EXITED, manager.inspectSourceLaunch(receipt).snapshot.state)
            manager.close()
            assertEquals(BackgroundCommandManager.State.EXITED, manager.inspectSourceLaunch(receipt).snapshot.state)
        } finally { manager.close() }
    }

    @Test fun twoInertReservationsConsumeOnlyOneRetainedSlotWhenOtherFails() = runBlocking {
        val terminal = Terminal().apply { persisted = true }; val manager = BackgroundCommandManager(terminal)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        try {
            repeat(BackgroundCommandManager.MAX_JOBS) { index ->
                val entry = manager.start("retained-$index")
                withTimeout(2000) { while (manager.snapshot(entry.id)?.state == BackgroundCommandManager.State.RUNNING) delay(1) }
            }
            val before = manager.list()
            var pending: java.util.concurrent.Future<Throwable?>? = null
            val receipt = launch(manager) {
                pending = pool.submit<Throwable?> {
                    runCatching { launch(manager) {
                        entered.countDown(); check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                        throw java.io.IOException("disk full")
                    } }.exceptionOrNull()
                }
                check(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(34, manager.list().size)
            }
            assertEquals(33, manager.list().size) //32 retained +1 inert, not32 total
            release.countDown()
            assertTrue(pending!!.get(3, java.util.concurrent.TimeUnit.SECONDS) is BackgroundCommandManager.SourceLaunchFailure)
            assertEquals(32, manager.list().size)
            assertNull(manager.snapshot(before.single { it.command == "retained-0" }.id))
            before.filter { it.command != "retained-0" }.forEach { assertNotNull(manager.snapshot(it.id)) }
            assertNotNull(manager.snapshot(receipt.intent.jobId))
        } finally { release.countDown(); pool.shutdownNow(); manager.closeAndJoin() }
    }

    @Test fun legacyAdmissionDoesNotChargePendingSourceReservationToRetention() = runBlocking {
        val terminal = Terminal().apply { persisted = true }; val manager = BackgroundCommandManager(terminal)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        try {
            repeat(32) { index ->
                val entry = manager.start("retained-$index")
                withTimeout(2000) { while (manager.snapshot(entry.id)?.state == BackgroundCommandManager.State.RUNNING) delay(1) }
            }
            val before = manager.list()
            val pending = pool.submit<Throwable?> { runCatching { launch(manager) {
                entered.countDown(); check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                throw java.io.IOException("disk full")
            } }.exceptionOrNull() }
            check(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val legacy = manager.start("new legacy")
            assertEquals(33, manager.list().size)
            release.countDown()
            assertTrue(pending.get(3, java.util.concurrent.TimeUnit.SECONDS) is BackgroundCommandManager.SourceLaunchFailure)
            assertEquals(32, manager.list().size)
            assertNull(manager.snapshot(before.single { it.command == "retained-0" }.id))
            before.filter { it.command != "retained-0" }.forEach { assertNotNull(manager.snapshot(it.id)) }
            assertNotNull(manager.snapshot(legacy.id))
        } finally { release.countDown(); pool.shutdownNow(); manager.closeAndJoin() }
    }

    @Test fun terminalErrorTextCannotChangeActivatedReceiptIntoNoEffect() = runBlocking {
        val terminal = Terminal().apply { failed = true }; val manager = BackgroundCommandManager(terminal)
        try {
            val receipt = launch(manager) { terminal.persisted = true }
            withTimeout(2000) { while (manager.snapshot(receipt.intent.jobId)?.state == BackgroundCommandManager.State.RUNNING) delay(5) }
            assertEquals(BackgroundCommandManager.State.FAILED, manager.snapshot(receipt.intent.jobId)?.state)
            assertEquals(BackgroundCommandManager.SourceActivation.ACTIVATED, receipt.activation)
            assertEquals(1, terminal.calls.get())
        } finally { manager.close() }
    }
}
