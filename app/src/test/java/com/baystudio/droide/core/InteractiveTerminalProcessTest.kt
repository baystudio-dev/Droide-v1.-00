package com.baystudio.droide.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real pipe shells exercise startup/exit/close races without Android's native PTY. */
class InteractiveTerminalProcessTest {
    @Test fun initializationIsOrderedBeforeEarlyInputAndOccursOncePerGeneration() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val text = StringBuffer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val terminal = InteractiveTerminalProcess(scope, {
            entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
            ProcessBuilder("/bin/sh", "-s").redirectErrorStream(true).start()
        }, {}, text::append, initialCommand = "printf 'INITIAL_READY\\n'")
        try {
            terminal.start()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            terminal.send("printf 'USER_COMMAND_ONCE\\n'")
            terminal.start()
            release.countDown()
            waitUntil { text.toString().contains("USER_COMMAND_ONCE") }
            assertEquals("INITIAL_READY\nUSER_COMMAND_ONCE\n", text.toString())
            terminal.restart()
            waitUntil { text.toString().split("INITIAL_READY").size == 3 }
            assertEquals(1, text.toString().split("USER_COMMAND_ONCE").size - 1)
        } finally { release.countDown(); terminal.destroy(); scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun concurrentStartsCreateOneShellAndRetainStartupInput() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val starts = AtomicInteger()
        val children = CopyOnWriteArrayList<Process>()
        val text = StringBuffer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val terminal = InteractiveTerminalProcess(scope, {
            starts.incrementAndGet()
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            ProcessBuilder("/bin/sh").redirectErrorStream(true).start().also(children::add)
        }, {}, text::append)
        try {
            terminal.send("printf 'startup 日本語 😀\\n'")
            coroutineScope { repeat(24) { launch(Dispatchers.Default) { terminal.start() } } }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(1, starts.get())
            release.countDown()
            waitUntil { text.toString().contains("startup 日本語 😀") }
            terminal.send("printf 'second\\n'")
            waitUntil { text.toString().contains("second") }
            assertEquals(1, starts.get())
        } finally {
            release.countDown(); terminal.destroy()
            scope.coroutineContext.job.cancelAndJoin()
            children.forEach { assertFalse(it.isAlive) }
        }
    }

    @Test fun destroyingDuringLaunchReapsLateChildAndRejectsFurtherInput() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val children = CopyOnWriteArrayList<Process>()
        val cleanups = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val terminal = InteractiveTerminalProcess(scope, {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start().also(children::add)
        }, { cleanups.incrementAndGet() }, {})
        try {
            terminal.start()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            terminal.destroy()
            assertTrue(runCatching { terminal.send("echo forbidden") }.isFailure)
            assertTrue(runCatching { terminal.start() }.isFailure)
            release.countDown()
            waitUntil { cleanups.get() == 1 && children.size == 1 && !children[0].isAlive }
        } finally {
            release.countDown(); terminal.destroy()
            scope.coroutineContext.job.cancelAndJoin()
            children.forEach { ProcessIo.terminate(it) }
        }
    }

    @Test fun exitedShellRejectsInputAndExplicitStartCreatesFreshGeneration() = runBlocking {
        val children = CopyOnWriteArrayList<Process>()
        val text = StringBuffer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val terminal = InteractiveTerminalProcess(scope, {
            ProcessBuilder("/bin/sh").redirectErrorStream(true).start().also(children::add)
        }, {}, text::append)
        try {
            terminal.start(); terminal.send("printf 'first'; exit 9")
            waitUntil { text.toString().contains("[process exited: 9]") }
            waitUntil { runCatching { terminal.send("lost-input") }.isFailure }
            terminal.start(); terminal.send("printf 'fresh-generation\\n'")
            waitUntil { text.toString().contains("fresh-generation") }
            assertEquals(2, children.size)
            assertFalse(children.first().isAlive)
        } finally {
            terminal.destroy(); scope.coroutineContext.job.cancelAndJoin()
            children.forEach { assertFalse(it.isAlive) }
        }
    }

    @Test fun restartReapsPriorShellAndSendsOnlyToReplacement() = runBlocking {
        val children = CopyOnWriteArrayList<Process>()
        val text = StringBuffer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val terminal = InteractiveTerminalProcess(scope, {
            ProcessBuilder("/bin/sh").redirectErrorStream(true).start().also(children::add)
        }, {}, text::append)
        try {
            terminal.start(); terminal.send("printf 'ready\\n'")
            waitUntil { text.toString().contains("ready") }
            terminal.restart(); terminal.send("printf 'replacement\\n'")
            waitUntil { text.toString().contains("replacement") }
            assertEquals(2, children.size)
            assertFalse(children.first().isAlive)
            assertTrue(children.last().isAlive)
        } finally {
            terminal.destroy(); scope.coroutineContext.job.cancelAndJoin()
            children.forEach { assertFalse(it.isAlive) }
        }
    }

    @Test fun startupQueueBoundsInputAndCancellationDoesNotLeak() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val terminal = InteractiveTerminalProcess(scope, { error("Cancelled scope must not launch") }, {}, {})
        repeat(32) { terminal.send("echo queued-$it") }
        assertTrue(runCatching { terminal.send("too many") }.isFailure)
        assertTrue(runCatching { terminal.send("nul\u0000") }.isFailure)
        scope.coroutineContext.job.cancelAndJoin()
        assertTrue(runCatching { terminal.start() }.exceptionOrNull() is CancellationException)
        terminal.destroy()
    }

    @Test fun cleanupFailureDoesNotEscapeTheTerminalWorkerOrBreakAnotherSession() = runBlocking {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val text = StringBuffer()
        val cleanupFinished = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, error -> uncaught += error })
        val children = CopyOnWriteArrayList<Process>()
        val failed = InteractiveTerminalProcess(scope, {
            ProcessBuilder("/bin/sh", "-c", "exit 0").start().also(children::add)
        }, { cleanupFinished.countDown(); throw IOException("Lease cleanup unavailable") }, text::append)
        val healthy = InteractiveTerminalProcess(scope, {
            ProcessBuilder("/bin/sh").redirectErrorStream(true).start().also(children::add)
        }, {}, text::append)
        try {
            failed.start()
            assertTrue(cleanupFinished.await(3, TimeUnit.SECONDS))
            waitUntil { scope.coroutineContext.job.children.none() }
            assertTrue("Terminal cleanup escaped as an uncaught coroutine failure: $uncaught", uncaught.isEmpty())
            assertTrue(text.toString().contains("cleanup"))
            healthy.start(); healthy.send("printf 'other-session-alive\\n'")
            waitUntil { text.toString().contains("other-session-alive") }
        } finally {
            failed.destroy(); healthy.destroy(); scope.coroutineContext.job.cancelAndJoin()
            children.forEach { assertFalse(it.isAlive) }
        }
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }
}
