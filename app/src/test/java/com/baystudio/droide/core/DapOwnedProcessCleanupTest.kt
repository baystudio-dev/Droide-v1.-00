package com.baystudio.droide.core

import java.io.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DapOwnedProcessCleanupTest {
    private class Host : StdioProcessHost, AutoCloseable {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        val closed = AtomicBoolean(false)
        val closedSignal = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Int>()
        val startEntered = CompletableDeferred<Unit>()
        var startGate: CompletableDeferred<Unit>? = null
        private val peerWriter = PipedOutputStream()
        private val reader = PipedInputStream(peerWriter, 4096)
        override suspend fun resolveExecutable(command: String) = command
        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
            startEntered.complete(Unit)
            startGate?.await()
            return object : HostedStdioProcess {
                override val stdin: OutputStream = ByteArrayOutputStream()
                override val stdout: InputStream = reader
                override val stderr: InputStream = ByteArrayInputStream(byteArrayOf())
                override val isAlive get() = !closed.get()
                override val exitCode: Int? get() = null
                override suspend fun awaitExit() = exited.await()
                override fun close() = this@Host.close()
            }
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { reader.close() }
            runCatching { peerWriter.close() }
            closedSignal.complete(Unit)
        }
    }

    @Test(timeout = 10_000) fun alreadyClosedTransportStillAwaitsItsAsynchronousOwnedProcessCleanup() = runBlocking {
        val host = Host()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dap = DapProcess(scope, File("."), listOf("adapter"), processHost = host)
        try {
            dap.start()
            dap.close()
            val cleanup = async(start = CoroutineStart.UNDISPATCHED) { dap.closeAndAwaitExit() }
            assertTrue(host.closed.get())
            assertFalse(cleanup.isCompleted)
            host.exited.complete(143)
            cleanup.await()
            assertTrue(scope.isActive)
        } finally { host.close(); host.exited.complete(143); scope.cancel() }
    }

    @Test(timeout = 10_000) fun unpublishedLateStartupIsClosedAndReapedBeforeCleanupReturns() = runBlocking {
        val host = Host().apply { startGate = CompletableDeferred() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dap = DapProcess(scope, File("."), listOf("adapter"), processHost = host)
        try {
            val startup = async { runCatching { dap.start() } }
            host.startEntered.await()
            dap.close()
            val cleanup = async(start = CoroutineStart.UNDISPATCHED) { dap.closeAndAwaitExit() }
            assertFalse(cleanup.isCompleted)
            host.startGate!!.complete(Unit)
            assertTrue(startup.await().isFailure)
            host.closedSignal.await()
            assertFalse(cleanup.isCompleted)
            host.exited.complete(143)
            cleanup.await()
            assertTrue(scope.isActive)
        } finally { host.close(); host.startGate!!.complete(Unit); host.exited.complete(143); scope.cancel() }
    }

    @Test(timeout = 10_000) fun failedLeaseCleanupIsObservableAfterTransportWasDetached() = runBlocking {
        val host = Host()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dap = DapProcess(scope, File("."), listOf("adapter"), processHost = host)
        try {
            dap.start()
            dap.close()
            host.exited.completeExceptionally(IllegalStateException("Owned lease failed"))
            val failure = runCatching { dap.closeAndAwaitExit() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("Owned lease failed", failure?.message)
            assertTrue(scope.isActive)
        } finally { host.close(); scope.cancel() }
    }

    @Test(timeout = 10_000) fun unresponsiveOwnedCleanupReportsBoundedFailureAndKeepsIdeOwnerAlive() = runBlocking {
        val host = Host()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dap = DapProcess(scope, File("."), listOf("adapter"), processHost = host)
        try {
            dap.start()
            val failure = withTimeout(2_000) { runCatching { dap.closeAndAwaitExit(100) }.exceptionOrNull() }
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message!!.contains("100ms"))
            assertTrue(host.closed.get())
            assertTrue(scope.isActive)
        } finally { host.close(); host.exited.complete(143); scope.cancel() }
    }

    @Test(timeout = 10_000) fun stoppingBeforeStartupNeverCreatesAPeer() = runBlocking {
        val host = Host()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dap = DapProcess(scope, File("."), listOf("adapter"), processHost = host)
        try {
            dap.closeAndAwaitExit()
            assertTrue(runCatching { dap.start() }.isFailure)
            assertFalse(host.startEntered.isCompleted)
        } finally { host.close(); scope.cancel() }
    }
}
