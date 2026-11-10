package com.baystudio.droide.core

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Existing in-memory fixture only; no real process, network, or large payload. */
class ProtocolMailboxCompatibilityBaselineTest {
    @Test(timeout = 15_000) fun rpcDiscardsBeforeSubscriptionAndBetweenSubscriptions() = runBlocking {
        val host = DebugProtocolFixture()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val rpc = JsonRpcProcess(scope, File("."), listOf("fake-rpc"), processHost = host)
        val peer = TinyPeer(host)
        val seen = Channel<String>(16)
        var collector: Job? = null
        try {
            rpc.start()
            peer.rpcNotification("fixture/before")
            rpcBarrier(rpc, host, peer)
            val firstCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                rpc.notifications.collect { seen.send(it.method) }
            }
            collector = firstCollector
            peer.rpcNotification("fixture/during")
            assertEquals("fixture/during", withTimeout(2_000) { seen.receive() })
            withTimeout(2_000) { firstCollector.cancelAndJoin() }
            peer.rpcNotification("fixture/between")
            rpcBarrier(rpc, host, peer)
            collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                rpc.notifications.collect { seen.send(it.method) }
            }
            peer.rpcNotification("fixture/after")
            assertEquals("fixture/after", withTimeout(2_000) { seen.receive() })
        } finally { collector?.cancel(); rpc.close(); host.close(); scope.cancel(); seen.close() }
    }

    @Test(timeout = 15_000) fun dapReplaysLatestEightAcrossSequentialSubscriptions() = runBlocking {
        val host = DebugProtocolFixture()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val dap = DapProcess(scope, File("."), listOf("fake-dap"), processHost = host)
        val peer = TinyPeer(host)
        try {
            dap.start()
            repeat(10) { peer.dapEvent("fixture/$it") }
            dapBarrier(dap, host, peer)
            val first = withTimeout(2_000) { dap.events.take(8).map { it.name }.toList() }
            assertEquals((2..9).map { "fixture/$it" }, first)
            repeat(3) { peer.dapEvent("fixture/${it + 10}") }
            dapBarrier(dap, host, peer)
            val second = withTimeout(2_000) { dap.events.take(8).map { it.name }.toList() }
            assertEquals((5..12).map { "fixture/$it" }, second)
        } finally { dap.close(); host.close(); scope.cancel() }
    }

    @Test(timeout = 15_000) fun dapFailureBeforeFirstSubscriptionStillReportsTermination() = runBlocking {
        val host = DebugProtocolFixture()
        val owner = Job()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val dap = DapProcess(scope, File("."), listOf("fake-dap"), processHost = host)
        try {
            dap.start()
            host.close()
            withTimeout(2_000) { while (dap.isRunning) delay(5) }
            val event = withTimeout(2_000) { dap.events.first { it.name == "terminated" } }
            assertEquals("terminated", event.name)
            assertTrue(owner.isActive)
        } finally { dap.close(); host.close(); scope.cancel() }
    }

    @Test(timeout = 15_000) fun dapCriticalOverflowCannotLoseItsTerminalNoticeBehindFullQueue() = runBlocking {
        val host = DebugProtocolFixture()
        val owner = Job()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val dap = DapProcess(scope, File("."), listOf("fake-dap"), processHost = host)
        val peer = TinyPeer(host)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<Unit>()
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            dap.events.collect { event ->
                if (event.name == "fixture/gate") { entered.complete(Unit); withTimeout(10_000) { release.await() } }
                if (event.name == "terminated") terminal.complete(Unit)
            }
        }
        try {
            dap.start()
            peer.dapEvent("fixture/gate")
            withTimeout(2_000) { entered.await() }
            val pending = async { runCatching { dap.request("fixture/no-response", timeoutMs = 5_000) } }
            withTimeout(2_000) { host.requests.receive() }
            // Fixed count-capacity boundary, not a memory/stack stress input.
            withTimeout(3_000) {
                try { repeat(257) { peer.dapEvent("critical") } } catch (_: IOException) {   }
            }
            val failure = withTimeout(2_000) { pending.await().exceptionOrNull() }
            assertTrue("Critical overflow must fail pending requests explicitly", failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("limit", ignoreCase = true))
            withTimeout(2_000) { while (dap.isRunning) delay(5) }
            release.complete(Unit)
            withTimeout(2_000) { terminal.await() }
            assertTrue("Transport failure must not cancel the IDE owner", owner.isActive)
        } finally { release.complete(Unit); collector.cancel(); dap.close(); host.close(); scope.cancel() }
    }

    private suspend fun rpcBarrier(rpc: JsonRpcProcess, host: DebugProtocolFixture, peer: TinyPeer) = coroutineScope {
        withTimeout(2_000) {
            val pending = async { rpc.request("fixture/barrier", timeoutMs = 2_000) }
            val request = host.requests.receive()
            peer.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", request["id"]!!); put("result", JsonNull) })
            pending.await()
        }
    }

    private suspend fun dapBarrier(dap: DapProcess, host: DebugProtocolFixture, peer: TinyPeer) = coroutineScope {
        withTimeout(2_000) {
            val pending = async { dap.request("fixture/barrier", timeoutMs = 2_000) }
            val request = host.requests.receive()
            peer.send(buildJsonObject {
                put("seq", 1); put("type", "response"); put("request_seq", request["seq"]!!)
                put("success", true); put("body", buildJsonObject {})
            })
            pending.await()
        }
    }

    private class TinyPeer(private val host: DebugProtocolFixture) {
        private var totalFrameBytes = 0
        suspend fun rpcNotification(method: String) = send(buildJsonObject { put("jsonrpc", "2.0"); put("method", method) })
        suspend fun dapEvent(name: String) = send(buildJsonObject { put("seq", 1); put("type", "event"); put("event", name) })
        suspend fun send(message: JsonObject) {
            val framed = ByteArrayOutputStream()
            ContentLengthProtocol.writeMessage(framed, message.toString())
            assertTrue("Each complete peer frame must stay below 1KiB", framed.size() < 1024)
            totalFrameBytes += framed.size()
            assertTrue("Total complete peer frames must stay below 32KiB", totalFrameBytes < 32 * 1024)
            withTimeout(2_000) { host.send(message) }
        }
    }
}
