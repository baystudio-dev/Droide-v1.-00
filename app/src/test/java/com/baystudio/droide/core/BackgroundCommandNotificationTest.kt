package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class BackgroundCommandNotificationTest {
    @get:org.junit.Rule val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test fun silentOutputWaitUsesOneDeadlineAndNoPeriodicDispatches() = runTest {
        val terminal = ControlledTerminal()
        val manager = BackgroundCommandManager(terminal)
        try {
            val job = manager.start("quiet build")
            terminal.entered.await()
            val dispatcher = CountingDispatcher(StandardTestDispatcher(testScheduler))
            val waiting = async(dispatcher) { manager.awaitOutput(job.id, 0, 30_000) }
            runCurrent()
            val before = dispatcher.dispatches.get()
            advanceTimeBy(1_000)
            runCurrent()
            assertFalse(waiting.isCompleted)
            assertEquals("Quiet waits must not wake every 25 ms", before, dispatcher.dispatches.get())
            terminal.emit("first output")
            runCurrent()
            assertEquals("first output", waiting.await().output)
            assertEquals(1, terminal.executions.get())
        } finally { manager.close(); terminal.finish.complete(ExecResult(0, "", false)) }
    }

    @Test fun silentCompletionWaitAlsoAvoidsPeriodicDispatches() = runTest {
        val terminal = ControlledTerminal()
        val manager = BackgroundCommandManager(terminal)
        try {
            val job = manager.start("quiet build")
            terminal.entered.await()
            val dispatcher = CountingDispatcher(StandardTestDispatcher(testScheduler))
            val chunks = StringBuilder()
            val waiting = async(dispatcher) { manager.awaitCompletion(job.id, 30_000, chunks::append) }
            runCurrent()
            val before = dispatcher.dispatches.get()
            advanceTimeBy(1_000)
            runCurrent()
            assertFalse(waiting.isCompleted)
            assertEquals(before, dispatcher.dispatches.get())
            terminal.emit("progress")
            runCurrent()
            assertEquals("progress", chunks.toString())
            assertFalse(waiting.isCompleted)
            manager.kill(job.id)
            runCurrent()
            assertEquals(BackgroundCommandManager.State.KILLED, waiting.await().state)
            assertEquals("progress", chunks.toString())
        } finally { manager.close(); terminal.finish.complete(ExecResult(0, "", false)) }
    }

    @Test fun exitWithoutOutputWakesEveryConcurrentSubscriber() = runBlocking {
        val terminal = ControlledTerminal()
        val manager = BackgroundCommandManager(terminal)
        try {
            val job = manager.start("silent successful build")
            terminal.entered.await()
            val waits = (1..8).map { async(start = CoroutineStart.UNDISPATCHED) { manager.awaitOutput(job.id, 0, 30_000) } }
            terminal.finish.complete(ExecResult(0, "", false))
            val results = withTimeout(3_000) { waits.awaitAll() }
            assertTrue(results.all { it.state == BackgroundCommandManager.State.EXITED && it.exitCode == 0 })
            assertEquals(1, terminal.executions.get())
        } finally { manager.close(); terminal.finish.complete(ExecResult(0, "", false)) }
    }

    @Test fun closeAndForegroundStopWakeWaitersWithoutOutput() = runBlocking {
        for (close in listOf(false, true)) {
            val terminal = ControlledTerminal()
            val manager = BackgroundCommandManager(terminal)
            try {
                val job = manager.start("quiet build", stopWithAgent = true)
                terminal.entered.await()
                val waiting = async(start = CoroutineStart.UNDISPATCHED) { manager.awaitOutput(job.id, 0, 30_000) }
                if (close) manager.close() else manager.cancelForegroundCommands()
                val result = withTimeout(3_000) { waiting.await() }
                assertEquals(BackgroundCommandManager.State.KILLED, result.state)
                assertEquals(0, result.outputEndCursor)
            } finally { manager.close(); terminal.finish.complete(ExecResult(0, "", false)) }
        }
    }

    @Test fun outputPresentBeforeSubscriptionIsReturnedAndCallerCancellationLeavesDetachedJobAlive() = runBlocking {
        val terminal = ControlledTerminal()
        val manager = BackgroundCommandManager(terminal)
        try {
            val job = manager.start("detached server")
            terminal.entered.await()
            terminal.emit("ready")
            assertEquals("ready", withTimeout(1_000) { manager.awaitOutput(job.id, 0, 30_000) }.output)
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { manager.awaitOutput(job.id, 5, 30_000) }
            waiting.cancelAndJoin()
            assertEquals(BackgroundCommandManager.State.RUNNING, manager.snapshot(job.id)?.state)
            terminal.emit(" next")
            assertEquals("ready next", manager.awaitOutput(job.id, 5, 0).output)
            assertEquals(1, terminal.executions.get())
        } finally { manager.close(); terminal.finish.complete(ExecResult(0, "", false)) }
    }

    private class CountingDispatcher(private val delegate: TestDispatcher) : CoroutineDispatcher(), Delay by delegate {
        val dispatches = AtomicInteger()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            delegate.dispatch(context, block)
        }
    }

    private class ControlledTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<ExecResult>()
        val executions = AtomicInteger()
        @Volatile private var callback: (String) -> Unit = {}
        fun emit(value: String) = callback(value)
        override fun start() = Unit
        override fun send(cmd: String) = Unit
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = error("Not used")
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = error("Not used")
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult {
            executions.incrementAndGet()
            callback = onOutput
            entered.complete(Unit)
            return finish.await()
        }
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }
}
