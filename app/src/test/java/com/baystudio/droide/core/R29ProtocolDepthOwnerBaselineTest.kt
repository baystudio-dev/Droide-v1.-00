package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** In-memory streams only. The fake executionScope never starts a real process. */
class R29ProtocolDepthOwnerBaselineTest {
    @Test(timeout = 15_000) fun rpcDepth128ResponseIsAccepted() = runBlocking { verifyResponse(dap = false, reject = false) }
    @Test(timeout = 15_000) fun dapDepth128ResponseIsAccepted() = runBlocking { verifyResponse(dap = true, reject = false) }
    @Test(timeout = 15_000) fun rpcDepth129FailsTransportButKeepsOwnerAlive() = runBlocking { verifyResponse(dap = false, reject = true) }
    @Test(timeout = 15_000) fun dapDepth129FailsTransportButKeepsOwnerAlive() = runBlocking { verifyResponse(dap = true, reject = true) }

    private suspend fun verifyResponse(dap: Boolean, reject: Boolean) = coroutineScope {
        val host = Host()
        // Deliberately a regular Job: transport isolation must not rely on its
        // caller already using SupervisorJob.
        val owner = Job()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val client = Client(scope, host, dap)
        try {
            client.start()
            val pending = async { runCatching { client.request() } }
            withTimeout(2_000) { host.writeStarted.await() }
            // The envelope contributes one level; the leaf is a scalar.
            val arrayDepth = if (reject) 128 else 127
            val nested = "[".repeat(arrayDepth) + "0" + "]".repeat(arrayDepth)
            val payload = if (dap) {
                "{\"seq\":1,\"type\":\"response\",\"request_seq\":1,\"success\":true,\"body\":$nested}"
            } else {
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":$nested}"
            }
            host.frame(payload)
            val result = withTimeout(2_000) { pending.await() }
            if (reject) {
                val failure = result.exceptionOrNull()
                assertTrue("Expected an explicit nesting-limit failure, got $failure", failure is IllegalStateException)
                assertTrue(failure?.message.orEmpty().contains("nesting", ignoreCase = true))
                withTimeout(2_000) { host.closedSignal.await() }
                assertFalse("Rejected payload must close the transport", client.running)
            } else {
                assertTrue("Depth-128 response should be accepted: ${result.exceptionOrNull()}", result.isSuccess)
                assertTrue("Accepted response must keep the transport open", client.running)
            }
            assertTrue("Protocol handling must not cancel its owner", owner.isActive)
            assertEquals(42, withTimeout(2_000) { scope.async { 42 }.await() })
        } finally {
            client.close()
            host.close()
            scope.cancel()
        }
    }

    private class Host : StdioProcessHost, AutoCloseable {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        private val serverOutput = PipedOutputStream()
        private val input = PipedInputStream(serverOutput, 2048)
        private val closed = AtomicBoolean(false)
        val writeStarted = CompletableDeferred<Unit>()
        val closedSignal = CompletableDeferred<Unit>()

        override suspend fun resolveExecutable(command: String): String = command

        override suspend fun start(
            argv: List<String>,
            environment: Map<String, String>,
            resourceLimits: ProcessResourceLimits?,
        ): HostedStdioProcess = object : HostedStdioProcess {
            override val stdin = object : OutputStream() {
                override fun write(value: Int) { writeStarted.complete(Unit) }
                override fun write(buffer: ByteArray, offset: Int, length: Int) { writeStarted.complete(Unit) }
            }
            override val stdout = input
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive get() = !closed.get()
            override val exitCode: Int? get() = if (closed.get()) 0 else null
            override suspend fun awaitExit(): Int { closedSignal.await(); return 0 }
            override fun close() = this@Host.close()
        }

        fun frame(payload: String) {
            assertTrue("Every payload must stay below 1 KiB", payload.toByteArray(Charsets.UTF_8).size < 1024)
            val framed = ByteArrayOutputStream()
            ContentLengthProtocol.writeMessage(framed, payload)
            assertTrue("The entire frame must stay below 1 KiB", framed.size() < 1024)
            // The 2 KiB pipe fits the complete frame; no producer flood is used.
            serverOutput.write(framed.toByteArray())
            serverOutput.flush()
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { input.close() }
            runCatching { serverOutput.close() }
            closedSignal.complete(Unit)
        }
    }

    private class Client(scope: CoroutineScope, host: Host, dap: Boolean) : AutoCloseable {
        private val rpc = if (!dap) JsonRpcProcess(scope, File("."), listOf("fake-rpc"), processHost = host) else null
        private val debug = if (dap) DapProcess(scope, File("."), listOf("fake-dap"), processHost = host) else null
        val running get() = rpc?.isRunning ?: debug!!.isRunning
        suspend fun start() { if (rpc != null) rpc.start() else debug!!.start() }
        suspend fun request() {
            if (rpc != null) rpc.request("depth-fixture", timeoutMs = 3_000)
            else debug!!.request("depth-fixture", timeoutMs = 3_000)
        }
        override fun close() { rpc?.close(); debug?.close() }
    }
}
