package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual production IO handoff and lifecycle; every process and stream is inert. */
class LocalHostedProcessHandoffR40Test {
    @Test(timeout = 15_000) fun cancelledReturnDispatchClosesUndeliveredHandle() {
        scenario {
            val dispatcher = QueuedDispatcher().also { dispatchers += it }
            val delivered = AtomicBoolean(false)
            val allowCreation = CompletableDeferred<Unit>()
            val caller = callerScope(dispatcher).launch {
                handoffLocalHostedProcess { allowCreation.await(); acquire() }
                delivered.set(true)
            }
            dispatcher.take().run() // Enter the real production IO boundary.
            // Only release after the first caller dispatch returns, guaranteeing suspension
            // even if IO would otherwise finish before withContext can suspend its caller.
            allowCreation.complete(Unit)
            val returnDispatch = dispatcher.take() // IO completed; caller has not received it.
            val hosted = checkNotNull(created.get())
            assertTrue(hosted.isAlive)
            assertEquals(0, cleanups.get())
            caller.cancel(CancellationException("Cancel queued return before delivery"))
            returnDispatch.run()
            withTimeout(2_000) { caller.join() }
            assertFalse("Cancelled return must not deliver a handle", delivered.get())
            assertOwnedCleanup(hosted, "Return-dispatch cancellation")
            assertWorkspaceActive()
        }
    }

    @Test(timeout = 15_000) fun sameIoCancellationAfterCreationClosesUndeliveredHandle() {
        scenario {
            val delivered = AtomicBoolean(false)
            val caller = callerScope(Dispatchers.IO).launch {
                val operation = currentCoroutineContext().job
                handoffLocalHostedProcess {
                    val hosted = acquire()
                    // No suspension or dispatcher switch between acquisition and cancellation.
                    operation.cancel(CancellationException("Cancel same-IO creation before delivery"))
                    hosted
                }
                delivered.set(true)
            }
            withTimeout(2_000) { caller.join() }
            val hosted = checkNotNull(created.get())
            assertFalse("Cancelled same-IO completion must not deliver a handle", delivered.get())
            assertOwnedCleanup(hosted, "Same-IO completion cancellation")
            assertWorkspaceActive()
        }
    }

    @Test(timeout = 15_000) fun deliveredHandleSurvivesCallerCancellationAndPreservesNaturalExitOutput() {
        scenario {
            val delivered = CompletableDeferred<LocalHostedProcess>()
            val caller = callerScope(Dispatchers.Default).launch {
                delivered.complete(handoffLocalHostedProcess { acquire() })
                awaitCancellation()
            }
            val hosted = withTimeout(2_000) { delivered.await() }
            withTimeout(2_000) { process.waitStarted.await(); caller.cancelAndJoin() }
            assertTrue("A delivered handle belongs to its workspace", hosted.isAlive)
            assertEquals(0, cleanups.get())
            assertWorkspaceActive()
            process.finish(7)
            assertEquals(7, withTimeout(2_000) { hosted.awaitExit() })
            assertEquals(7, hosted.exitCode)
            assertEquals(1, cleanups.get())
            assertEquals(0, process.destroyCalls.get())
            assertFalse("Natural exit must leave unread output open", process.output.closed.get())
            assertEquals("trailing-response", hosted.stdout.readBytes().toString(Charsets.UTF_8))
            assertWorkspaceActive()
        }
    }

    @Test(timeout = 15_000) fun repeatedCloseCleansOnceAndLeavesUnrelatedPeerAlive() {
        scenario {
            val hosted = handoffLocalHostedProcess { acquire() }
            val unrelated = FakeProcess()
            val unrelatedCleanups = AtomicInteger()
            val unrelatedHosted = LocalHostedProcess(unrelated, workspace) { unrelatedCleanups.incrementAndGet() }
            try {
                coroutineScope { (1..12).map { launch(Dispatchers.Default) { hosted.close() } }.joinAll() }
                assertOwnedCleanup(hosted, "Repeated explicit close")
                assertEquals(1, process.destroyCalls.get())
                assertTrue(unrelatedHosted.isAlive)
                assertEquals(0, unrelatedCleanups.get())
                assertWorkspaceActive()
            } finally {
                unrelatedHosted.close()
                unrelated.finish(143)
                withTimeout(2_000) { unrelatedHosted.awaitExit() }
            }
        }
    }

    @Test(timeout = 15_000) fun creationFailurePreservesFailureAndWorkspaceWork() {
        scenario {
            val expected = IOException("Fake process startup failed before acquisition")
            val failure = runCatching { handoffLocalHostedProcess { throw expected } }.exceptionOrNull()
            // Coroutine stacktrace recovery may wrap the same original throwable.
            assertTrue(generateSequence(failure) { it.cause }.any { it === expected })
            assertNull(created.get())
            assertEquals(0, cleanups.get())
            assertWorkspaceActive()
        }
    }

    private fun scenario(block: suspend Fixture.() -> Unit): Unit = runBlocking {
        val fixture = Fixture()
        try { withTimeout(8_000) { fixture.block() } }
        finally { withContext(NonCancellable) { fixture.dispose() } }
        Unit
    }

    private class Fixture {
        private val workspaceJob = SupervisorJob()
        val workspace = CoroutineScope(workspaceJob + Dispatchers.IO)
        private val sibling = workspace.launch { awaitCancellation() }
        val process = FakeProcess()
        val cleanups = AtomicInteger()
        val created = AtomicReference<LocalHostedProcess?>(null)
        val dispatchers = mutableListOf<QueuedDispatcher>()
        private val callerJobs = mutableListOf<Job>()

        fun acquire(): LocalHostedProcess = LocalHostedProcess(process, workspace) { cleanups.incrementAndGet() }
            .also { check(created.compareAndSet(null, it)) }

        fun callerScope(dispatcher: CoroutineDispatcher): CoroutineScope {
            val job = SupervisorJob().also { callerJobs += it }
            return CoroutineScope(job + dispatcher)
        }

        fun assertWorkspaceActive() {
            assertTrue("Workspace owner must remain live", workspaceJob.isActive)
            assertTrue("Unrelated workspace work must remain live", sibling.isActive)
        }

        suspend fun assertOwnedCleanup(hosted: LocalHostedProcess, phase: String) {
            val settled = withTimeoutOrNull(2_000) { hosted.awaitExit(); true } == true
            assertTrue("$phase lost its handle: alive=${process.isAlive}, cleanups=${cleanups.get()}", settled)
            assertFalse(process.isAlive)
            assertFalse(hosted.isAlive)
            assertEquals(1, cleanups.get())
        }

        suspend fun dispose() {
            callerJobs.forEach { it.cancel() }
            created.get()?.close()
            process.finish(143)
            workspaceJob.cancel()
            withTimeout(3_000) {
                // Run only already queued caller continuations so cancellation can settle.
                while (callerJobs.any { !it.isCompleted }) {
                    dispatchers.forEach { it.drain() }
                    delay(1)
                }
                created.get()?.awaitExit()
                workspaceJob.join()
            }
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        fun take(): Runnable = checkNotNull(pending.poll(2, TimeUnit.SECONDS)) { "Caller dispatch watchdog expired" }
        fun drain() { while (true) (pending.poll() ?: return).run() }
    }

    private class TrackedInput : ByteArrayInputStream("trailing-response".toByteArray()) {
        val closed = AtomicBoolean(false)
        override fun close() { closed.set(true) }
        override fun read(): Int { check(!closed.get()); return super.read() }
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            check(!closed.get()); return super.read(bytes, offset, count)
        }
    }

    private class FakeProcess : Process() {
        val waitStarted = CompletableDeferred<Unit>()
        val destroyCalls = AtomicInteger()
        val output = TrackedInput()
        private val error = TrackedInput()
        private val input = ByteArrayOutputStream()
        private val alive = AtomicBoolean(true)
        private val exited = CountDownLatch(1)
        @Volatile private var result = 143
        override fun getOutputStream() = input
        override fun getInputStream() = output
        override fun getErrorStream() = error
        override fun isAlive() = alive.get()
        override fun waitFor(): Int {
            waitStarted.complete(Unit)
            check(exited.await(10, TimeUnit.SECONDS)) { "Fake process watchdog expired" }
            return result
        }
        override fun waitFor(timeout: Long, unit: TimeUnit) = exited.await(timeout, unit)
        override fun exitValue(): Int { check(!alive.get()); return result }
        override fun destroy() { destroyCalls.incrementAndGet(); finish(143) }
        override fun destroyForcibly(): Process { destroyCalls.incrementAndGet(); finish(137); return this }
        fun finish(code: Int) { result = code; alive.set(false); exited.countDown() }
    }
}
