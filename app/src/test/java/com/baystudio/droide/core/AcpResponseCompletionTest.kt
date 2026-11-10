package com.baystudio.droide.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AcpResponseCompletionTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun result(body: String = "{}") = json("{\"result\":$body}")
    private fun await(deferred: CompletableDeferred<JsonObject>): JsonObject = runBlocking { withTimeout(1_000) { deferred.await() } }
    private fun call(method: String = "session/set_mode", callback: ((JsonObject) -> Unit)? = null) =
        AcpOutboundCall(CompletableDeferred(), callback, method, if (method == "session/load") "loaded" else null)

    @Test fun callbackRunsSynchronouslyBeforeWaiterCompletion() {
        val events = mutableListOf<String>()
        val thread = Thread.currentThread()
        val deferred = CompletableDeferred<JsonObject>()
        val call = AcpOutboundCall(deferred, {
            assertSame(thread, Thread.currentThread())
            assertFalse(deferred.isCompleted)
            events += "callback"
        }, "session/set_mode", null)
        deferred.invokeOnCompletion { events += "completed" }
        AcpResponseCompletion.complete(result(), call) { _, _, _ -> fail("Not a startup call") }
        events += "returned"
        assertEquals(listOf("callback", "completed", "returned"), events)
        assertEquals(json("{}"), await(deferred))
    }

    @Test fun startupRetentionPrecedesCallbackAndWaiterForBothStartupMethods() {
        for (method in listOf("session/new", "session/load")) {
            val events = mutableListOf<String>()
            val deferred = CompletableDeferred<JsonObject>()
            val call = AcpOutboundCall(deferred, { events += "callback" }, method, "requested")
            deferred.invokeOnCompletion { events += "completed" }
            val expected = json("{\"sessionId\":\"owned\"}")
            AcpResponseCompletion.complete(buildJsonObject { put("result", expected) }, call) { actualMethod, owner, payload ->
                assertEquals(method, actualMethod); assertEquals("requested", owner); assertSame(expected, payload)
                assertFalse(deferred.isCompleted); events += "retained"
            }
            assertEquals(listOf("retained", "callback", "completed"), events)
            assertSame(expected, await(deferred))
        }
    }

    @Test fun callbackExceptionCompletesFailedWaiterWithoutBreakingNextCompletion() {
        val expected = IllegalStateException("Mode not confirmed")
        val failed = call { throw expected }
        AcpResponseCompletion.complete(result(), failed) { _, _, _ -> fail("Not startup") }
        assertSame(expected, assertThrows(IllegalStateException::class.java) { await(failed.deferred) })
        val next = call()
        AcpResponseCompletion.complete(result("{\"next\":true}"), next) { _, _, _ -> fail("Not startup") }
        assertEquals(json("{\"next\":true}"), await(next.deferred))
    }

    @Test fun nonObjectResultsFailWaiterWithoutInvokingCallbacks() {
        for (body in listOf("null", "false", "17", "\"ack\"", "[]")) {
            val call = call { fail("Malformed result reached callback") }
            AcpResponseCompletion.complete(result(body), call) { _, _, _ -> fail("Malformed result reached startup") }
            val failure = assertThrows(IllegalStateException::class.java) { await(call.deferred) }
            assertEquals("ACP response result must be an object", failure.message)
        }
    }

    @Test fun malformedOrConflictingOutcomesCompleteExceptionallyWithoutCallback() {
        val cases = mapOf(
            "{}" to "ACP response must contain exactly one result or error",
            "{\"result\":{},\"error\":{}}" to "ACP response must contain exactly one result or error",
            "{\"error\":null}" to "ACP response error must be an object",
            "{\"error\":[]}" to "ACP response error must be an object",
            "{\"error\":{}}" to "ACP response error requires an integer code",
            "{\"error\":{\"code\":\"-32602\",\"message\":\"bad\"}}" to "ACP response error requires an integer code",
            "{\"error\":{\"code\":1.5,\"message\":\"bad\"}}" to "ACP response error requires an integer code",
            "{\"error\":{\"code\":-32602,\"message\":[]}}" to "ACP response error requires a string message")
        for ((body, message) in cases) {
            val call = call { fail("Error reached success callback") }
            AcpResponseCompletion.complete(json(body), call) { _, _, _ -> fail("Error reached startup") }
            assertTrue(call.deferred.isCompleted)
            val failure = assertThrows(Exception::class.java) { await(call.deferred) }
            assertEquals(message, failure.message)
        }
    }

    @Test fun validRpcErrorIsBoundedWithoutSplittingASurrogatePair() {
        val message = "x".repeat(1_998) + "🙂".repeat(20)
        val payload = buildJsonObject { put("error", buildJsonObject { put("code", -32602); put("message", message) }) }
        val call = call { fail("RPC error reached callback") }
        AcpResponseCompletion.complete(payload, call) { _, _, _ -> fail("Not startup") }
        val failure = assertThrows(IllegalStateException::class.java) { await(call.deferred) }
        assertEquals("x".repeat(1_998) + "…", failure.message)
        assertTrue(failure.message!!.length <= 2_000)
    }

    @Test fun startupRetentionFailureCompletesWaiterBeforeAnySuccessCallback() {
        val expected = IllegalArgumentException("Invalid owner")
        val call = call("session/new") { fail("Retention failure reached callback") }
        AcpResponseCompletion.complete(result(), call) { _, _, _ -> throw expected }
        assertSame(expected, assertThrows(IllegalArgumentException::class.java) { await(call.deferred) })
    }

    @Test fun nonStartupMethodsNeverRetainStartupConfiguration() {
        for (method in listOf("initialize", "session/set_mode", "session/set_config_option", "session/prompt")) {
            var callbacks = 0
            val call = call(method) { callbacks++ }
            AcpResponseCompletion.complete(result(), call) { _, _, _ -> fail("$method retained startup state") }
            assertEquals(json("{}"), await(call.deferred))
            assertEquals(1, callbacks)
        }
    }
}
