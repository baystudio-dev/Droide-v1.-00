package com.baystudio.droide.core

import java.lang.reflect.InvocationTargetException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalLlamaToolCapabilityCredentialTest {
    @Test fun nestedMalformedProbeArgumentsNeverExposeParserCredentialContext() {
        val secret = "resolved-local-probe-token-fixture-" + "s".repeat(200)
        val arguments = """{"nonce" "echo":"$secret"}"""
        val response = buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject {
                put("message", buildJsonObject {
                    put("tool_calls", buildJsonArray { add(buildJsonObject {
                        put("id", "probe-call"); put("type", "function")
                        put("function", buildJsonObject {
                            put("name", LocalLlamaToolCapabilityPolicy.TOOL_NAME); put("arguments", arguments)
                        })
                    }) })
                })
            }) })
        }.toString()
        val certifier = LocalLlamaToolCapabilityCertifier(RuntimeEnvironment.getApplication())
        val boundary = LocalLlamaToolCapabilityCertifier::class.java.getDeclaredMethod(
            "validateOrReject", String::class.java, kotlin.jvm.functions.Function0::class.java,
        ).apply { isAccessible = true }
        val validator: () -> Any = { LocalLlamaToolCapabilityPolicy.requireSingleToolCall(response, "nonce-fixture") }
        val wrapped = assertThrows(InvocationTargetException::class.java) {
            boundary.invoke(certifier, "f".repeat(64), validator)
        }
        val rejected = wrapped.cause as LocalLlamaToolCapabilityCertifier.ProbeRejectedException
        assertTrue(rejected.message!!.contains("Malformed tool-probe JSON."))
        assertFalse(rejected.message!!.contains("resolved-local-probe"))
        assertFalse(rejected.message!!.contains("JSON input"))
        assertNull(rejected.cause)
    }
}
