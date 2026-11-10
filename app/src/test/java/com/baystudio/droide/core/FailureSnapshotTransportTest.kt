package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FailureSnapshotTransportTest {
    @Test(timeout = 50_000)
    fun bufferedTerminalErrorsRetainReportedUsageAndOnlyPublicTextThroughBothApis() {
        ProviderWireProtocol.entries.forEach { protocol ->
            listOf(false, true).forEach { streamingRequest ->
                withProviderStreamFixture(protocol) { fixture ->
                    fixture.forceBufferedResponse = true
                    fixture.response = { _, _, _ -> bufferedCandidate(protocol, failed = true).toString() }
                    val events = mutableListOf<LlmStreamEvent>()
                    val failure = providerFailure {
                        if (streamingRequest) fixture.stream(events) else fixture.buffered()
                    }

                    assertEquals(ProviderStreamFailureKind.TEMPORARY_SERVICE, failure.kind)
                    assertSnapshot(failure, PUBLIC_TEXT, 9)
                    assertTrue("Failed buffered bodies must not publish success events: $protocol", events.isEmpty())
                    assertEquals(1, fixture.requests.size)
                }
            }
        }
    }

    @Test(timeout = 50_000)
    fun bufferedMalformedCandidatesRetainReportedUsageAndPublicTextThroughBothApis() {
        ProviderWireProtocol.entries.forEach { protocol ->
            listOf(false, true).forEach { streamingRequest ->
                withProviderStreamFixture(protocol) { fixture ->
                    fixture.forceBufferedResponse = true
                    fixture.response = { _, _, _ -> bufferedCandidate(protocol, malformed = true).toString() }
                    val events = mutableListOf<LlmStreamEvent>()
                    val failure = providerFailure {
                        if (streamingRequest) fixture.stream(events) else fixture.buffered()
                    }

                    assertEquals(ProviderStreamFailureKind.UNKNOWN, failure.kind)
                    assertSnapshot(failure, PUBLIC_TEXT, 9)
                    assertTrue("Invalid buffered candidates must not publish success events: $protocol", events.isEmpty())
                    assertEquals(1, fixture.requests.size)
                }
            }
        }
    }

    @Test(timeout = 30_000)
    fun nativeTerminalErrorsRetainLatestUsageWithoutDuplicatingCumulativePublicText() {
        ProviderWireProtocol.entries.forEach { protocol ->
            withProviderStreamFixture(protocol) { fixture ->
                fixture.response = { _, _, _ ->
                    prefix(fixture) + fixture.event(terminalError(protocol).toString())
                }
                val events = mutableListOf<LlmStreamEvent>()
                val failure = providerFailure { fixture.stream(events) }

                assertEquals(ProviderStreamFailureKind.TEMPORARY_SERVICE, failure.kind)
                assertSnapshot(failure, PUBLIC_TEXT, 9)
                assertPublicDeltas(events)
                assertTrue("The partial tool must have reached transport: $protocol",
                    events.any { it is LlmStreamEvent.ToolCallDelta })
                assertEquals(1, fixture.requests.size)
            }
        }
    }

    @Test(timeout = 30_000)
    fun nativeMalformedCandidatesKeepEarlierPublicDeltasAndSameEnvelopeUsage() {
        ProviderWireProtocol.entries.forEach { protocol ->
            withProviderStreamFixture(protocol) { fixture ->
                fixture.response = { _, _, _ ->
                    prefix(fixture) + fixture.event(malformedEvent(protocol).toString())
                }
                val events = mutableListOf<LlmStreamEvent>()
                val failure = providerFailure { fixture.stream(events) }

                assertEquals(ProviderStreamFailureKind.UNKNOWN, failure.kind)
                assertSnapshot(failure, PUBLIC_TEXT, 9)
                assertPublicDeltas(events)
                assertEquals(1, fixture.requests.size)
            }
        }
    }

    @Test(timeout = 30_000)
    fun malformedSseJsonKeepsAlreadyReportedUsageAndPublicDeltasWithoutReplay() {
        ProviderWireProtocol.entries.forEach { protocol ->
            withProviderStreamFixture(protocol) { fixture ->
                fixture.response = { _, _, _ ->
                    prefix(fixture) + fixture.event(usageEvent(protocol, 9).toString()) + fixture.event("{")
                }
                val events = mutableListOf<LlmStreamEvent>()
                val failure = providerFailure { fixture.stream(events) }

                assertEquals(ProviderStreamFailureKind.UNKNOWN, failure.kind)
                assertSnapshot(failure, PUBLIC_TEXT, 9)
                assertPublicDeltas(events)
                assertEquals(1, fixture.requests.size)
            }
        }
    }

    @Test(timeout = 10_000)
    fun malformedResponsesTerminalCandidateRetainsUnemittedPublicSuffixOnlyInSnapshot() {
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_RESPONSES) { fixture ->
            fixture.response = { _, _, _ ->
                fixture.prefix() + privateEvents(fixture) +
                    fixture.event(malformedEvent(fixture.protocol).toString())
            }
            val events = mutableListOf<LlmStreamEvent>()
            val failure = providerFailure { fixture.stream(events) }

            // Terminal projection fails on the malformed tool before any terminal text callback. Its
            // validated public fields must still be available to the failure snapshot.
            assertSnapshot(failure, PUBLIC_TEXT, 9)
            assertEquals(listOf(PUBLIC_PREFIX), events.filterIsInstance<LlmStreamEvent.TextDelta>().map { it.text })
            assertFalse(events.any { it is LlmStreamEvent.BufferedResponse || it is LlmStreamEvent.RetryScheduled })
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test(timeout = 50_000)
    fun callbackCancellationIdentitySurvivesNativeAndBufferedStreamingAcrossFourProtocols() {
        ProviderWireProtocol.entries.forEach { protocol ->
            listOf(false, true).forEach { bufferedResponse ->
                withProviderStreamFixture(protocol) { fixture ->
                    fixture.forceBufferedResponse = bufferedResponse
                    fixture.ignoreExpectedClientDisconnect = true
                    fixture.response = { _, _, _ ->
                        if (bufferedResponse) bufferedSuccess(protocol) else fixture.prefix()
                    }
                    val cancellation = CancellationException("R56 callback cancellation")
                    val events = mutableListOf<LlmStreamEvent>()
                    val actual = runBlocking {
                        withTimeout(4_000) {
                            try {
                                stream(fixture) { event ->
                                    events += event
                                    throw cancellation
                                }
                                null
                            } catch (failure: Throwable) {
                                failure
                            }
                        }
                    }

                    assertSame("Callback cancellation must keep identity: $protocol buffered=$bufferedResponse", cancellation, actual)
                    assertEquals(1, events.size)
                    if (bufferedResponse) {
                        assertEquals(LlmStreamEvent.BufferedResponse(PUBLIC_PREFIX), events.single())
                    } else {
                        assertEquals(LlmStreamEvent.TextDelta(PUBLIC_PREFIX), events.single())
                    }
                    assertEquals(1, fixture.requests.size)
                }
            }
        }
    }

    private fun providerFailure(block: suspend () -> Any?): LlmProviderStreamException =
        assertThrows(LlmProviderStreamException::class.java) {
            runBlocking { withTimeout(4_000) { block() } }
        }

    private fun assertSnapshot(failure: LlmProviderStreamException, text: String, total: Int) {
        assertEquals(200, failure.httpStatus)
        assertNull(failure.cause)
        val snapshot = requireNotNull(failure.publicSnapshot) { "Transport failure did not carry a snapshot" }
        assertEquals(text, snapshot.publicText)
        assertEquals(2, snapshot.usage.prompt)
        assertEquals(total - 2, snapshot.usage.completion)
        assertEquals(total, snapshot.usage.total)
        assertEquals(UsageAuthority.AUTHORITATIVE, snapshot.usage.tokenAuthority)
        assertEquals(UsageAuthority.UNKNOWN, snapshot.usage.costAuthority)
        assertEquals(1L, snapshot.usage.requestCount)
        assertEquals(1L, snapshot.usage.knownTokenRequestCount)
        assertTrue(snapshot.usage.breakdownComplete)
        val exposed = snapshot.toString() + failure.message.orEmpty() + failure.responseBody
        listOf(PRIVATE_THOUGHT, PRIVATE_TOOL, PRIVATE_CONTINUATION, "pending.txt", "pending-call", "write_file", PROVIDER_STREAM_SECRET)
            .forEach { forbidden -> assertFalse("Failure retained private data: $forbidden", exposed.contains(forbidden)) }
    }

    private fun assertPublicDeltas(events: List<LlmStreamEvent>) {
        assertEquals(listOf(PUBLIC_PREFIX, PUBLIC_SUFFIX),
            events.filterIsInstance<LlmStreamEvent.TextDelta>().map { it.text })
        assertFalse(events.any { it is LlmStreamEvent.BufferedResponse || it is LlmStreamEvent.RetryScheduled })
    }

    private fun prefix(fixture: ProviderStreamFixture): String {
        val closeAnthropicTool = if (fixture.protocol == ProviderWireProtocol.ANTHROPIC_MESSAGES) {
            fixture.event("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"}"}}""") +
                fixture.event("""{"type":"content_block_stop","index":1}""")
        } else ""
        val suffix = if (fixture.protocol == ProviderWireProtocol.ANTHROPIC_MESSAGES) {
            fixture.event("""{"type":"content_block_start","index":3,"content_block":{"type":"text","text":""}}""") +
                fixture.event(textEvent(fixture.protocol, PUBLIC_SUFFIX)) +
                fixture.event("""{"type":"content_block_stop","index":3}""")
        } else fixture.event(textEvent(fixture.protocol, PUBLIC_SUFFIX))
        return fixture.prefix() + fixture.event(usageEvent(fixture.protocol, 5).toString()) +
            closeAnthropicTool + privateEvents(fixture) + suffix
    }

    private fun privateEvents(fixture: ProviderStreamFixture): String = when (fixture.protocol) {
        ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> fixture.event(
            """{"choices":[{"index":0,"delta":{"reasoning_content":"$PRIVATE_THOUGHT","reasoning":"$PRIVATE_CONTINUATION"}}]}""")
        ProviderWireProtocol.OPENAI_RESPONSES -> fixture.event(
            """{"type":"response.reasoning_text.delta","output_index":2,"content_index":0,"delta":"$PRIVATE_THOUGHT"}""")
        ProviderWireProtocol.ANTHROPIC_MESSAGES -> fixture.event(
            """{"type":"content_block_start","index":2,"content_block":{"type":"thinking","thinking":"","signature":""}}""") +
            fixture.event("""{"type":"content_block_delta","index":2,"delta":{"type":"thinking_delta","thinking":"$PRIVATE_THOUGHT"}}""") +
            fixture.event("""{"type":"content_block_delta","index":2,"delta":{"type":"signature_delta","signature":"$PRIVATE_CONTINUATION"}}""") +
            fixture.event("""{"type":"content_block_stop","index":2}""")
        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> fixture.event(
            """{"candidates":[{"content":{"role":"model","parts":[{"thought":true,"text":"$PRIVATE_THOUGHT","thoughtSignature":"$PRIVATE_CONTINUATION"}]}}]}""")
    }

    private fun textEvent(protocol: ProviderWireProtocol, text: String): String = when (protocol) {
        ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> """{"choices":[{"index":0,"delta":{"content":"$text"}}]}"""
        ProviderWireProtocol.OPENAI_RESPONSES -> """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"$text"}"""
        ProviderWireProtocol.ANTHROPIC_MESSAGES -> """{"type":"content_block_delta","index":3,"delta":{"type":"text_delta","text":"$text"}}"""
        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> """{"candidates":[{"content":{"role":"model","parts":[{"text":"$text"}]}}]}"""
    }

    private fun reportedUsage(protocol: ProviderWireProtocol, total: Int): JsonObject = obj(when (protocol) {
        ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> """{"prompt_tokens":2,"completion_tokens":${total - 2},"total_tokens":$total}"""
        ProviderWireProtocol.OPENAI_RESPONSES -> """{"input_tokens":2,"output_tokens":${total - 2},"total_tokens":$total}"""
        ProviderWireProtocol.ANTHROPIC_MESSAGES -> """{"input_tokens":2,"output_tokens":${total - 2}}"""
        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> """{"promptTokenCount":2,"candidatesTokenCount":${total - 2},"totalTokenCount":$total}"""
    })

    private fun usageEvent(protocol: ProviderWireProtocol, total: Int): JsonObject = buildJsonObject {
        when (protocol) {
            ProviderWireProtocol.OPENAI_RESPONSES -> {
                put("type", "response.in_progress")
                put("response", buildJsonObject { put("usage", reportedUsage(protocol, total)) })
            }
            ProviderWireProtocol.ANTHROPIC_MESSAGES -> {
                put("type", "message_delta")
                put("delta", buildJsonObject {})
                put("usage", reportedUsage(protocol, total))
            }
            ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> put("usageMetadata", reportedUsage(protocol, total))
            ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> put("usage", reportedUsage(protocol, total))
        }
    }

    private fun bufferedCandidate(protocol: ProviderWireProtocol, failed: Boolean = false, malformed: Boolean = false): JsonObject {
        val candidate = obj(when (protocol) {
            ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> """{"choices":[{"index":0,"finish_reason":${if (malformed) "{}" else "\"tool_calls\""},"message":{"role":"assistant","content":"$PUBLIC_TEXT","reasoning_content":"$PRIVATE_THOUGHT","tool_calls":[{"id":"pending-call","type":"function","function":{"name":"write_file","arguments":"$PRIVATE_TOOL"}}]}}]}"""
            ProviderWireProtocol.OPENAI_RESPONSES -> """{"status":"${if (failed) "failed" else "completed"}","output":[{"type":"reasoning","summary":[],"encrypted_content":"$PRIVATE_CONTINUATION"},{"type":"function_call","call_id":"pending-call","name":"write_file","arguments":"$PRIVATE_TOOL"},{"type":"message","role":"assistant","content":[{"type":"output_text","text":"$PUBLIC_TEXT"}]}]}"""
            ProviderWireProtocol.ANTHROPIC_MESSAGES -> """{"type":"message","role":"assistant","stop_reason":${if (malformed) "{}" else "\"tool_use\""},"content":[{"type":"thinking","thinking":"$PRIVATE_THOUGHT","signature":"$PRIVATE_CONTINUATION"},{"type":"tool_use","id":"pending-call","name":"write_file","input":{"path":"$PRIVATE_TOOL"}},{"type":"text","text":"$PUBLIC_TEXT"}]}"""
            ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> """{"candidates":[{"finishReason":${if (malformed) "{}" else "\"STOP\""},"content":{"role":"model","parts":[{"thought":true,"text":"$PRIVATE_THOUGHT","thoughtSignature":"$PRIVATE_CONTINUATION"},{"functionCall":{"id":"pending-call","name":"write_file","args":{"path":"$PRIVATE_TOOL"}}},{"text":"$PUBLIC_TEXT"}]}}]}"""
        })
        return buildJsonObject {
            candidate.forEach { (key, value) -> put(key, value) }
            put(if (protocol == ProviderWireProtocol.GOOGLE_GENERATE_CONTENT) "usageMetadata" else "usage", reportedUsage(protocol, 9))
            if (failed) put("error", obj("""{"code":503,"type":"overloaded_error","message":"Interrupted; echo $PROVIDER_STREAM_SECRET"}"""))
        }
    }

    private fun terminalError(protocol: ProviderWireProtocol): JsonObject {
        val failed = bufferedCandidate(protocol, failed = true)
        return when (protocol) {
            ProviderWireProtocol.OPENAI_RESPONSES -> buildJsonObject { put("type", "response.failed"); put("response", failed) }
            ProviderWireProtocol.ANTHROPIC_MESSAGES -> JsonObject(failed + ("type" to JsonPrimitive("error")))
            else -> failed
        }
    }

    private fun malformedEvent(protocol: ProviderWireProtocol): JsonObject = when (protocol) {
        ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> JsonObject(usageEvent(protocol, 9) +
            obj("""{"choices":[{"index":0,"delta":{"content":true}}]}"""))
        ProviderWireProtocol.OPENAI_RESPONSES -> buildJsonObject {
            put("type", "response.completed")
            put("response", bufferedCandidate(protocol, malformed = true))
        }
        ProviderWireProtocol.ANTHROPIC_MESSAGES -> JsonObject(usageEvent(protocol, 9) +
            ("delta" to obj("""{"stop_reason":{}}""")))
        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> JsonObject(usageEvent(protocol, 9) +
            obj("""{"candidates":[{"finishReason":{},"content":{"role":"model","parts":[]}}]}"""))
    }

    private fun bufferedSuccess(protocol: ProviderWireProtocol): String = when (protocol) {
        ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> """{"choices":[{"message":{"role":"assistant","content":"$PUBLIC_PREFIX"},"finish_reason":"stop"}]}"""
        ProviderWireProtocol.OPENAI_RESPONSES -> """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"$PUBLIC_PREFIX"}]}]}"""
        ProviderWireProtocol.ANTHROPIC_MESSAGES -> """{"type":"message","role":"assistant","content":[{"type":"text","text":"$PUBLIC_PREFIX"}],"stop_reason":"end_turn"}"""
        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> """{"candidates":[{"content":{"role":"model","parts":[{"text":"$PUBLIC_PREFIX"}]},"finishReason":"STOP"}]}"""
    }

    private suspend fun stream(fixture: ProviderStreamFixture, onEvent: suspend (LlmStreamEvent) -> Unit): LlmStreamResult =
        LlmClient().chatCompletionsStream(fixture.provider.id, fixture.provider.baseUrl, fixture.secret, fixture.model,
            buildJsonObject {
                put("model", fixture.model)
                put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "hello") }) })
            }.toString(), onEvent)

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private companion object {
        const val PUBLIC_PREFIX = "public partial"
        const val PUBLIC_SUFFIX = " plus tail"
        const val PUBLIC_TEXT = PUBLIC_PREFIX + PUBLIC_SUFFIX
        const val PRIVATE_THOUGHT = "r56-private-thinking"
        const val PRIVATE_TOOL = "r56-private-tool-arguments"
        const val PRIVATE_CONTINUATION = "r56-private-continuation"
    }
}
