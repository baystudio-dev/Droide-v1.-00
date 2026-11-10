package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class JavascriptDebugTargetRequestTest {
    @Test fun officialReverseRequestWithoutNestedRequestIsNormalized() {
        val target = JavascriptDebugTargetRequest.parse(request())
        assertEquals("a29ff0479bae44ec6dde3a5e", target.id)
        assertEquals("launch", target.request)
        assertEquals("launch", target.configuration["request"]!!.jsonPrimitive.content)
    }
    @Test fun explicitMatchingNestedRequestAndAttachRemainSupported() {
        val launch = request()
        val config = launch["configuration"]!!.jsonObject
        val attach = JsonObject(launch + mapOf("request" to JsonPrimitive("attach"),
            "configuration" to JsonObject(config + ("request" to JsonPrimitive("attach")))))
        assertEquals("attach", JavascriptDebugTargetRequest.parse(attach).request)
    }
    @Test fun conflictingNestedRequestIsRejected() {
        val launch = request()
        val config = launch["configuration"]!!.jsonObject
        assertThrows(IllegalArgumentException::class.java) {
            JavascriptDebugTargetRequest.parse(JsonObject(launch + ("configuration" to JsonObject(config + ("request" to JsonPrimitive("attach"))))))
        }
    }
    @Test fun foreignTypesMalformedTargetIdsAndOversizedRequestsAreRejected() {
        val launch = request()
        val config = launch["configuration"]!!.jsonObject
        for (bad in listOf(config + ("type" to JsonPrimitive("python")), config + ("__pendingTargetId" to JsonPrimitive("../other")),
            config + ("__pendingTargetId" to JsonPrimitive(123)), config + ("name" to JsonPrimitive("x".repeat(65_536))))) {
            assertThrows(IllegalArgumentException::class.java) { JavascriptDebugTargetRequest.parse(JsonObject(launch + ("configuration" to JsonObject(bad)))) }
        }
    }
    private fun request() = buildJsonObject {
        put("request", "launch")
        putJsonObject("configuration") {
            put("type", "pwa-node"); put("name", "main.js [162104]"); put("__pendingTargetId", "a29ff0479bae44ec6dde3a5e")
        }
    }
}
