package com.baystudio.droide.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*

internal data class AcpOutboundCall(
    val deferred: CompletableDeferred<JsonObject>,
    val onResponse: ((JsonObject) -> Unit)?,
    val method: String,
    val requestedSessionId: String?,
)

/** Runs on the reader thread: retain startup, apply callback, then complete the waiter. */
internal object AcpResponseCompletion {
    fun complete(msg: JsonObject, call: AcpOutboundCall,
                 retainStartupConfiguration: (String, String?, JsonObject) -> Unit) {
        try {
            require(("result" in msg) != ("error" in msg)) { "ACP response must contain exactly one result or error" }
            if ("error" in msg) {
                val errorObject = msg["error"] as? JsonObject
                    ?: error("ACP response error must be an object")
                val code = errorObject["code"] as? JsonPrimitive
                require(code != null && !code.isString && code.intOrNull != null) { "ACP response error requires an integer code" }
                val message = (errorObject["message"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    ?: error("ACP response error requires a string message")
                throw IllegalStateException(boundedDiagnosticText(message, 2_000))
            }
            // ACP methods handled here return objects. Never turn null,
            // a scalar, or an array into a successful empty mode response.
            val result = msg["result"] as? JsonObject
                ?: error("ACP response result must be an object")
            if (call.method == "session/new" || call.method == "session/load") retainStartupConfiguration(call.method, call.requestedSessionId, result)
            call.onResponse?.invoke(result)
            call.deferred.complete(result)
        } catch (failure: Exception) {
            call.deferred.completeExceptionally(failure)
        }
    }
}
