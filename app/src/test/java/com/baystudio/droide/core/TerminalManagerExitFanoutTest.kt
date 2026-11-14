package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class TerminalManagerExitFanoutTest {
    @get:org.junit.Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private class ChildSession : ITerminalSession {
        override val output = MutableStateFlow("")
        val child = ProcessBuilder("/bin/sh", "-c", "exec sleep 60").start()
        val requested = CompletableDeferred<Unit>()
        val receipt = CompletableDeferred<Unit>()
        val stops = AtomicInteger()
        val destroys = AtomicInteger()
        @Volatile var beforeStop: () -> Unit = {}
        @Volatile var failure: Throwable? = null
        override fun start() {}
        override fun send(cmd: String) {}
        override fun interrupt() {}
        override fun clear() {}
        override fun destroy() { destroys.incrementAndGet(); child.destroyForcibly() }
        override suspend fun execOnce(cmd: String, timeoutMs: Long) = ExecResult(0, "", false)
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = ExecResult(0, "", false)
        override suspend fun stopAndAwaitForExit() {
            stops.incrementAndGet(); requested.complete(Unit)
            beforeStop()
            child.destroyForcibly()
            check(child.waitFor(3, TimeUnit.SECONDS))
            receipt.await()
            failure?.let { throw it }
        }
        fun cleanup() { receipt.complete(Unit); child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS) }
    }
    @Test fun heldFirstReceiptCannotLeaveSiblingChildrenRunning() = runBlocking {
        val root = Files.createTempDirectory("droide-exit-fanout-").toFile()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val children = List(3) { ChildSession() }
        var index = 0
        val manager = TerminalManager(owner, root) { _, _, _ -> children[index++] }
        var exiting: Deferred<Unit>? = null
        try {
            repeat(3) { manager.createLocal() }
            children.drop(1).forEach { it.receipt.complete(Unit) }
            exiting = async(Dispatchers.Default) { manager.stopAndAwaitForExit() }
            withTimeout(3_000) { children.forEach { it.requested.await() } }
            assertTrue(children.all { it.child.waitFor(3, TimeUnit.SECONDS) })
            assertFalse("Stop request and child death are not the held cleanup receipt", exiting.isCompleted)
            children.first().receipt.complete(Unit)
            withTimeout(3_000) { exiting.await() }
            assertTrue(manager.tabs.value.isEmpty())
            assertTrue(children.all { it.stops.get() == 1 })
            assertTrue("Final disposal must not issue another destroy", children.all { it.destroys.get() == 0 })
        } finally {
            children.forEach { it.cleanup() }; exiting?.cancelAndJoin(); manager.dispose(false); owner.cancel()
            PathSecurity.deleteTreeNoFollow(root)
        }
    }
    private fun withChildren(count: Int = 3, block: suspend CoroutineScope.(TerminalManager, List<ChildSession>, CoroutineScope, File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-terminal-exit-cases-").toFile()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val children = List(count) { ChildSession() }
        var index = 0
        val manager = TerminalManager(owner, root) { _, _, _ -> children[index++] }
        try { supervisorScope { block(manager, children, owner, root) } }
        finally {
            children.forEach { it.cleanup() }; manager.dispose(false)
            runCatching { withTimeout(5_000) { manager.stopAndAwaitForExit() } }
            owner.cancel(); PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun synchronousBlockedFirstStopCannotStarveOtherWorkers() = withChildren { manager, children, _, _ ->
        val release = java.util.concurrent.CountDownLatch(1)
        children.first().beforeStop = { check(release.await(5, TimeUnit.SECONDS)) }
        children.forEach { it.receipt.complete(Unit) }
        repeat(3) { manager.createLocal() }
        val exiting = async { manager.stopAndAwaitForExit() }
        try {
            withTimeout(3_000) { children.forEach { it.requested.await() } }
            assertTrue(children.drop(1).all { it.child.waitFor(3, TimeUnit.SECONDS) })
            assertTrue(children.first().child.isAlive); assertFalse(exiting.isCompleted)
        } finally { release.countDown() }
        withTimeout(3_000) { exiting.await() }
        assertTrue(children.all { it.stops.get() == 1 && it.destroys.get() == 0 })
    }

    @Test fun cancelledObserverAndRuntimeDoNotCancelRetainedStopOrReplaySuccess() = withChildren { manager, children, owner, _ ->
        repeat(3) { manager.createLocal() }
        children.drop(1).forEach { it.receipt.complete(Unit) }
        val exiting = async { manager.stopAndAwaitForExit() }
        withTimeout(3_000) { children.forEach { it.requested.await() } }
        exiting.cancelAndJoin(); owner.cancel()
        assertFalse(manager.canCreateTab)
        val retry = async { manager.stopAndAwaitForExit() }
        yield(); assertFalse(retry.isCompleted)
        children.first().receipt.complete(Unit)
        withTimeout(3_000) { retry.await() }
        manager.stopAndAwaitForExit()
        assertTrue(children.all { it.stops.get() == 1 && it.destroys.get() == 0 })
    }

    @Test fun explicitRetryRepeatsOnlyFailedReceiptAndReportsFatalSafely() = withChildren { manager, children, _, _ ->
        val fatal = AssertionError("terminal transport failure")
        children.first().failure = fatal
        children.forEach { it.receipt.complete(Unit) }
        repeat(3) { manager.createLocal() }
        val failure = runCatching { withTimeout(3_000) { manager.stopAndAwaitForExit() } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("Terminal cleanup failed before verified completion", failure!!.message)
        // Coroutine stack recovery can add a wrapper; the original cause must survive intact.
        assertTrue(generateSequence(failure) { it.cause }.any { it === fatal })
        assertTrue(children.all { !it.child.isAlive && it.stops.get() == 1 })
        assertEquals(3, manager.tabs.value.size)
        children.first().failure = null
        withTimeout(3_000) { manager.stopAndAwaitForExit() }
        assertEquals(2, children.first().stops.get())
        assertTrue(children.drop(1).all { it.stops.get() == 1 })
        assertTrue(children.all { it.destroys.get() == 0 })
    }

    @Test fun closeDuringExitKeepsExactAttemptAndDoesNotDestroyAgain() = withChildren { manager, children, _, _ ->
        val tabs = List(3) { manager.createLocal() }
        val exiting = async { manager.stopAndAwaitForExit() }
        withTimeout(3_000) { children.forEach { it.requested.await() } }
        manager.close(tabs.first().id).getOrThrow()
        manager.dispose(false).getOrThrow()
        assertTrue(children.all { it.stops.get() == 1 && it.destroys.get() == 0 })
        children.forEach { it.receipt.complete(Unit) }
        withTimeout(3_000) { exiting.await() }
        manager.dispose(false).getOrThrow(); manager.stopAndAwaitForExit()
        assertTrue(children.all { it.stops.get() == 1 && it.destroys.get() == 0 })
    }

    @Test fun existingRetirementIsReusedWithoutConcurrentStop() = withChildren { manager, children, _, _ ->
        val tabs = List(3) { manager.createLocal() }
        manager.close(tabs.first().id).getOrThrow()
        withTimeout(3_000) { children.first().requested.await() }
        val exiting = async { manager.stopAndAwaitForExit() }
        withTimeout(3_000) { children.drop(1).forEach { it.requested.await() } }
        assertEquals(1, children.first().stops.get())
        children.forEach { it.receipt.complete(Unit) }
        withTimeout(3_000) { exiting.await() }
        assertTrue(children.all { it.stops.get() == 1 })
        assertEquals(1, children.first().destroys.get())
        assertTrue(children.drop(1).all { it.destroys.get() == 0 })
    }

    @Test fun duplicateHandlesAndConcurrentObserversShareOneAttempt() = withChildren(1) { _, children, owner, root ->
        val child = children.single()
        val manager = TerminalManager(owner, root) { _, _, _ -> child }
        try {
            repeat(3) { manager.createLocal() }
            val first = async { manager.stopAndAwaitForExit() }
            withTimeout(3_000) { child.requested.await() }
            val second = async { manager.stopAndAwaitForExit() }
            yield(); assertEquals(1, child.stops.get())
            child.receipt.complete(Unit)
            withTimeout(3_000) { first.await(); second.await() }
            assertEquals(1, child.stops.get()); assertEquals(0, child.destroys.get())
        } finally { manager.dispose(false) }
    }

    @Test fun pendingCancelledPreparationCannotDelayExistingChildStopRequests() = withChildren(2) { _, children, owner, root ->
        val setupEntered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseSetup = CompletableDeferred<Unit>()
        var index = 0
        val manager = TerminalManager(owner, root,
            linuxLaunchSpecFactory = { _, _ ->
                setupEntered.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanupEntered.complete(Unit); releaseSetup.await() } }
            }, localSessionFactory = { _, _, _ -> children[index++] })
        try {
            repeat(2) { manager.createLocal() }
            children.forEach { it.receipt.complete(Unit) }
            val setup = async { manager.openLinuxTerminal(qemu = true) }
            setupEntered.await()
            val exiting = async { manager.stopAndAwaitForExit() }
            cleanupEntered.await()
            withTimeout(3_000) { children.forEach { it.requested.await() } }
            assertTrue(children.all { it.child.waitFor(3, TimeUnit.SECONDS) })
            assertFalse(exiting.isCompleted)
            releaseSetup.complete(Unit)
            assertTrue(runCatching { setup.await() }.exceptionOrNull() is CancellationException)
            withTimeout(3_000) { exiting.await() }
            assertTrue(children.all { it.stops.get() == 1 })
        } finally { releaseSetup.complete(Unit); manager.dispose(false) }
    }

}
