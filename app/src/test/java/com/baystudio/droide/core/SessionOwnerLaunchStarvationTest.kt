package com.baystudio.droide.core

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SessionOwnerLaunchStarvationTest {
    private class ActualChild : HostedStdioProcess {
        private val child = ProcessBuilder("/bin/sh", "-c", "exec sleep 60").start()
        val stopRequested = CompletableDeferred<Unit>()
        val closeCalls = AtomicInteger()
        override val stdin get() = child.outputStream
        override val stdout get() = child.inputStream
        override val stderr get() = child.errorStream
        override val isAlive get() = child.isAlive
        override val exitCode get() = if (child.isAlive) null else child.exitValue()
        override fun close() { closeCalls.incrementAndGet(); child.destroy(); stopRequested.complete(Unit) }
        override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) { child.waitFor() }
        fun fixtureCleanup() {
            child.destroyForcibly()
            check(child.waitFor(5, TimeUnit.SECONDS))
            stdin.close(); stdout.close(); stderr.close()
        }
    }

    @Test(timeout = 10_000)
    fun knownCliChildReceivesStopBeforeCancelledLaunchCleanupReturns() = runBlocking {
        val owner = SessionHostedProcessOwner()
        val child = ActualChild()
        val launchEntered = CompletableDeferred<Unit>()
        val launchFinalizing = CompletableDeferred<Unit>()
        val releaseFinalizer = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val delegate = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = command
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                if (argv.first() == "existing") return child
                launchEntered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) {
                        launchFinalizing.complete(Unit)
                        releaseFinalizer.await()
                    }
                }
            }
        }
        val host = owner.bind(delegate)
        var pending: Job? = null
        var stopping: Deferred<Unit>? = null
        try {
            assertSame(child, host.start(listOf("existing")))
            pending = scope.launch { host.start(listOf("held-launch")) }
            launchEntered.await()
            stopping = scope.async { owner.stopAndAwait() }
            withTimeout(2_000) { launchFinalizing.await() }
            assertFalse("Cleanup must still wait for in-flight launch ownership", stopping.isCompleted)
            val received = withTimeoutOrNull(750) { child.stopRequested.await(); true } == true
            assertTrue("Known live CLI child must receive stop before cancelled launch finalizer is released", received)
            withTimeout(2_000) { child.awaitExit() }
            assertFalse(child.isAlive)
        } finally {
            releaseFinalizer.complete(Unit)
            pending?.join()
            child.close()
            stopping?.await()
            scope.cancel()
            child.fixtureCleanup()
        }
    }
}
