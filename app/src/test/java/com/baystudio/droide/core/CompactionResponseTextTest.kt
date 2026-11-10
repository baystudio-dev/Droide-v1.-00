package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CompactionResponseTextTest {
    @Test fun completedButBlankCheckpointIsRejected() {
        for (content in listOf("", " ", "\n\r\t")) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                CompactionResponseText.extract(response(content))
            }
            assertTrue(failure.message.orEmpty().contains("empty checkpoint"))
        }
    }

    @Test fun completedNonblankCheckpointRetainsItsText() {
        val content = "  Keep the user's restrictions and the unresolved test failure.\n"
        assertEquals(content, CompactionResponseText.extract(response(content)))
    }

    @Test fun incompleteCheckpointIsRejectedEvenWhenItContainsText() {
        for (finish in listOf("length", "content_filter", "tool_calls", null)) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                CompactionResponseText.extract(response("Partial checkpoint", finish))
            }
            assertTrue(failure.message.orEmpty().contains("incomplete"))
        }
    }

    @Test fun missingCheckpointTextIsRejected() {
        for (content in listOf<JsonElement?>(null, JsonNull, buildJsonObject {}, JsonPrimitive(42), JsonPrimitive(true))) {
            val response = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
                put("finish_reason", "stop")
                put("message", buildJsonObject {
                    put("role", "assistant")
                    if (content != null) put("content", content)
                })
            }) }) }
            val failure = assertThrows(IllegalArgumentException::class.java) {
                CompactionResponseText.extract(response.toString())
            }
            assertTrue(failure.message.orEmpty().contains("existing conversation was preserved"))
        }
    }

    @Test fun refusalIsRejectedEvenWithCanonicalStopAndPublicText() {
        for (content in listOf("null", "\"Partial summary\"")) {
            val response = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":$content,"refusal":"Cannot summarize this input."}}]}"""
            assertThrows(IllegalArgumentException::class.java) { CompactionResponseText.extract(response) }
        }
    }

    @Test fun toolOnlyReplyIsNotACheckpoint() {
        val response = """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"","tool_calls":[{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{}"}}]}}]}"""
        assertThrows(IllegalArgumentException::class.java) { CompactionResponseText.extract(response) }
    }

    @Test fun nativeFailureCannotBecomeACheckpointThroughCanonicalStop() {
        for (native in listOf("MAX_TOKENS", "MALFORMED_FUNCTION_CALL", "SAFETY", "PROMPT_BLOCK:SAFETY")) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                CompactionResponseText.extract(response("Partial summary", native = native))
            }
            assertTrue(failure.message.orEmpty().contains("incomplete"))
        }
    }

    @Test fun toolsAreRejectedEvenWithCanonicalStopAndPublicText() {
        val response = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Partial summary","tool_calls":[{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{}"}}]}}]}"""
        assertThrows(IllegalArgumentException::class.java) { CompactionResponseText.extract(response) }
    }

    @Test fun validNativeStopRetainsTheExactPublicText() {
        val content = "  Valid checkpoint.\n"
        assertEquals(content, CompactionResponseText.extract(response(content, native = "STOP")))
    }

    private fun response(content: String, finish: String? = "stop", native: String? = null) = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            if (finish != null) put("finish_reason", finish)
            if (native != null) put("_droide_google_finish_reason", native)
            put("message", buildJsonObject {
                put("role", "assistant")
                put("content", content)
            })
        }) })
    }.toString()
}
