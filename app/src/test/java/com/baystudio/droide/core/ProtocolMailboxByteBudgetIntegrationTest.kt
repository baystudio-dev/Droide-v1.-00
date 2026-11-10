package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolMailboxByteBudgetIntegrationTest {
    @Test(timeout = 15_000) fun rpcOptionalOverflowOmitsAndDoesNotBlockResponses() = runBlocking { exercise(false, false) }
    @Test(timeout = 15_000) fun dapOptionalOverflowOmitsAndDoesNotBlockResponses() = runBlocking { exercise(true, false) }
    @Test(timeout = 15_000) fun rpcCriticalByteOverflowFailsOnlyTheOwnedTransport() = runBlocking { exercise(false, true) }
    @Test(timeout = 15_000) fun dapCriticalByteOverflowPreservesTerminationAndFailsOnlyTheOwnedTransport() = runBlocking { exercise(true, true) }

    @Test(timeout = 15_000) fun dapOmissionDecorationChargesAddedFieldAndEscapingForMalformedBody() = runBlocking {
        fun output(body: JsonElement) = buildJsonObject {
            put("seq", 1); put("type", "event"); put("event", "output"); put("body", body)
        }
        val ordinary = output(buildJsonObject { put("output", "") })
        val missingField = output(buildJsonObject { put("a", 1) })
        val prefix = "[Droide: 1 output event(s) omitted while the debugger UI was busy]\n"
        val prefixBytes = JsonPrimitive(prefix).toString().toByteArray(Charsets.UTF_8).size - 2
        val limit = missingField.toString().toByteArray(Charsets.UTF_8).size.toLong() + prefixBytes
        val ordinaryBytes = ordinary.toString().toByteArray(Charsets.UTF_8).size
        assertTrue(2L * ordinaryBytes <= limit && 3L * ordinaryBytes > limit && limit < 1024)
        val host = DebugProtocolFixture()
        val owner = Job()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val dap = DapProcess(scope, File("."), listOf("fake-dap"), processHost = host, eventQueueByteLimit = limit)
        val peer = TinyPeer(host)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val seen = Channel<JsonElement?>(8)
        var deliveries = 0
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            dap.events.collect {
                deliveries++
                if (deliveries == 1) { entered.complete(Unit); withTimeout(10_000) { release.await() } }
                seen.send(it.body)
            }
        }
        try {
            dap.start()
            peer.send(ordinary)
            withTimeout(2_000) { entered.await() }
            peer.send(ordinary); peer.send(ordinary)
            barrier(dap, null, host, peer)
            release.complete(Unit)
            repeat(2) { withTimeout(2_000) { seen.receive() } }
            // Prefix-only accounting would fit exactly. The new output field's syntax makes the actual
            // rendered event too large even when empty.
            peer.send(missingField)
            barrier(dap, null, host, peer)
            peer.send(output(JsonNull))
            assertEquals(JsonNull, withTimeout(2_000) { seen.receive() })
            assertEquals(3, deliveries)
            assertTrue(dap.isRunning && owner.isActive)
        } finally {
            release.complete(Unit); collector.cancel(); dap.close(); host.close(); scope.cancel(); seen.close()
            withContext(NonCancellable) { withTimeout(2_000) { collector.join(); owner.join() } }
        }
    }

    private suspend fun exercise(debug: Boolean, critical: Boolean) = coroutineScope {
        val host = DebugProtocolFixture()
        val owner = Job()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val event = if (debug) buildJsonObject {
            put("seq", 1); put("type", "event"); put("event", if (critical) "critical" else "output")
            put("body", buildJsonObject { put("output", "x".repeat(80)) })
        } else buildJsonObject {
            put("jsonrpc", "2.0"); put("method", if (critical) "fixture/critical" else "window/logMessage")
            put("params", buildJsonObject { put("message", "x".repeat(80)) })
        }
        val limit = 2L * event.toString().toByteArray(Charsets.UTF_8).size
        assertTrue("The injected aggregate limit must stay below 1KiB", limit < 1024)
        val dap = if (debug) DapProcess(scope, File("."), listOf("fake-dap"), processHost = host, eventQueueByteLimit = limit) else null
        val rpc = if (!debug) JsonRpcProcess(scope, File("."), listOf("fake-rpc"), processHost = host, eventQueueByteLimit = limit) else null
        val peer = TinyPeer(host)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val seen = Channel<String>(8)
        val terminal = CompletableDeferred<Unit>()
        var deliveries = 0
        suspend fun onEvent(text: String, terminated: Boolean) {
            if (terminated) { terminal.complete(Unit); return }
            deliveries++
            if (deliveries == 1) { entered.complete(Unit); withTimeout(10_000) { release.await() } }
            seen.send(text)
        }

        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            if (dap != null) dap.events.collect {
                onEvent((it.body as? JsonObject)?.get("output")?.jsonPrimitive?.content.orEmpty(), it.name == "terminated")
            } else rpc!!.notifications.collect { onEvent(it.params.toString(), false) }
        }
        var pending: Deferred<Result<Unit>>? = null
        try {
            if (dap != null) dap.start() else rpc!!.start()
            peer.send(event)
            withTimeout(2_000) { entered.await() }
            if (critical) {
                val waiter = async {
                    runCatching {
                        if (dap != null) dap.request("fixture/no-response", timeoutMs = 5_000)
                        else rpc!!.request("fixture/no-response", timeoutMs = 5_000)
                        Unit
                    }
                }
                pending = waiter
                withTimeout(2_000) { host.requests.receive() }
                if (rpc != null) {
                    // The write mutex proves the pending request's frame writer has returned before overload; the
                    // fake peer never replies.
                    withTimeout(2_000) { rpc.notify("fixture/writer-fence") }
                    withTimeout(2_000) { host.requests.receive() }
                } else {
                    val fence = withTimeout(2_000) { dap!!.requestAsync("fixture/writer-fence") }
                    try { withTimeout(2_000) { host.requests.receive() } } finally { fence.cancel() }
                }
                peer.send(event) // first in flight + second pending exhausts bytes.
                try { peer.send(event) } catch (_: IOException) {   }
                val failure = withTimeout(2_000) { waiter.await().exceptionOrNull() }
                assertTrue("Critical overload must report an explicit session failure", failure is IllegalStateException)
                assertTrue(failure?.message.orEmpty().contains("limit", ignoreCase = true))
                withTimeout(2_000) { while (dap?.isRunning ?: rpc!!.isRunning) delay(5) }
                release.complete(Unit)
                withTimeout(2_000) { collector.join() }
                if (dap != null) withTimeout(2_000) { terminal.await() }
                assertEquals("Only the in-flight ordinary event survives failure", 1, deliveries)
            } else {
                peer.send(event)
                peer.send(event) // optional third event exceeds only the byte limit.
                barrier(dap, rpc, host, peer)
                assertTrue(dap?.isRunning ?: rpc!!.isRunning)
                if (rpc != null) assertEquals(1L, rpc.omittedNotifications)
                release.complete(Unit)
                repeat(2) { withTimeout(2_000) { seen.receive() } }
                peer.send(event)
                val recovered = withTimeout(2_000) { seen.receive() }
                if (dap != null) assertTrue(recovered.contains("1 output event(s) omitted"))
                assertEquals("An omitted event must not later be delivered", 3, deliveries)
                barrier(dap, rpc, host, peer)
            }
            assertTrue("Transport overload must leave the regular IDE owner alive", owner.isActive)
        } finally {
            release.complete(Unit)
            pending?.cancel()
            collector.cancel()
            dap?.close(); rpc?.close(); host.close(); scope.cancel(); seen.close()
            withContext(NonCancellable) { withTimeout(2_000) { collector.join(); owner.join(); pending?.join() } }
        }
    }

    private suspend fun barrier(dap: DapProcess?, rpc: JsonRpcProcess?, host: DebugProtocolFixture, peer: TinyPeer) = coroutineScope {
        withTimeout(2_000) {
            val pending = async {
                if (dap != null) dap.request("fixture/barrier", timeoutMs = 2_000)
                else rpc!!.request("fixture/barrier", timeoutMs = 2_000)
            }
            val request = host.requests.receive()
            peer.send(if (dap != null) buildJsonObject {
                put("seq", 1); put("type", "response"); put("request_seq", request["seq"]!!)
                put("success", true); put("body", buildJsonObject {})
            } else buildJsonObject { put("jsonrpc", "2.0"); put("id", request["id"]!!); put("result", JsonNull) })
            pending.await()
        }
    }

    private class TinyPeer(private val host: DebugProtocolFixture) {
        private var totalBytes = 0
        suspend fun send(message: JsonObject) {
            val frame = ByteArrayOutputStream()
            ContentLengthProtocol.writeMessage(frame, message.toString())
            assertTrue("Every complete peer frame must remain below 1KiB", frame.size() < 1024)
            totalBytes += frame.size()
            assertTrue("All complete peer frames must remain below 8KiB", totalBytes < 8 * 1024)
            withTimeout(2_000) { host.send(message) }
        }
    }
}
