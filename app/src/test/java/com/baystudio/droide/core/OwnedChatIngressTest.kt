package com.baystudio.droide.core

import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class OwnedChatIngressTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val target = ProviderReplayTarget("p", "m", ProviderReplayTarget.Protocol.OPENAI_CHAT, "https://provider.invalid")
    private val disk = OwnedResponseContentStore.HeadroomAdmission({ 1L shl 40 }, 8192)
    private class Context(val root: Path, val store: OwnedResponseContentStore) {
        val response = store.authorize(OwnedResponseContentStore.Owner("s", "r", "response"))
        fun call(id: String) = store.authorize(OwnedResponseContentStore.Owner("s", "r", id))
    }
    private inline fun fixture(block: (Context) -> Unit) {
        val root = Files.createTempDirectory("owned-chat-test-")
        try { block(Context(root, OwnedResponseContentStore(root, disk, 1024))) }
        finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    private fun accumulator(c: Context) = OwnedChatStreamAccumulator(c.response, c::call, target, json)
    private fun event(fragment: String, first: Boolean = false, index: Int = 0, id: String = "call", name: String = "write_file") = buildJsonObject {
        put("id", "response"); put("model", "m"); put("choices", buildJsonArray { add(buildJsonObject {
            put("delta", buildJsonObject { put("tool_calls", buildJsonArray { add(buildJsonObject {
                put("index", index); if (first) { put("id", id); put("type", "function") }
                put("function", buildJsonObject { if (first) put("name", name); put("arguments", fragment) })
            }) }) })
        }) })
    }.toString()
    private val terminal = """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""
    private fun sse(vararg values: String) = Buffer().apply { values.forEach { writeUtf8("data: $it\n\n") } }
    private fun read(c: Context, source: StreamedFileArgumentExtractor.Source, session: OwnedResponseContentStore.Session): String =
        session.openPages(source.handle).use { pages -> buildString { while (true) append(pages.readPage() ?: break) } }

    @Test fun realHttpFragmentedLargeArgumentsReachTypedPlannerAndPreserveReplayMetadata() = fixture { c ->
        val server = ProviderLoopbackServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val chunk = "0123456789abcdef".repeat(512)
        server.createContext("/chat") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                fun send(payload: String) {
                    val bytes = "data: $payload\r\n\r\n".toByteArray()
                    var offset = 0
                    while (offset < bytes.size) { val n = minOf(127, bytes.size - offset); output.write(bytes, offset, n); offset += n }
                }
                send("""{"choices":[{"delta":{"content":"Ready","reasoning_content":"private reasoning"}}]}""")
                send(event("{\"path\":\"large.txt\",\"content\":\"", true))
                repeat(64) { send(event(chunk)) }
                send(event("\"}")); send(terminal)
                send("""{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":20}}""")
                send("[DONE]")
            }
        }
        server.start()
        try {
            accumulator(c).use { acc ->
                val response = OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/chat").build()).execute()
                val result = OwnedChatSseIngress.readResponse(response, acc)
                assertEquals(1, result.calls.size); assertTrue(result.calls.single().arguments.bytes > 128_000)
                assertEquals("Ready", read(c, result.publicText, c.response))
                assertEquals(JsonPrimitive(20), result.usage!!["completion_tokens"])
                val native = json.parseToJsonElement(read(c, result.nativeAssistantMessage, c.response)).jsonObject
                assertEquals(JsonPrimitive("private reasoning"), native["reasoning_content"])
                val call = result.calls.single()
                val nativeArgs = native["tool_calls"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content
                val sha = MessageDigest.getInstance("SHA-256").digest(nativeArgs.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(call.arguments.sha256, sha)
                assertFalse(nativeArgs.contains(call.arguments.handle.serialized))
                val plan = OwnedChatToolPlanner.prepare(result, c::call, json)
                val write = plan.entries.single().input as OwnedChatToolPlanner.Input.WriteFile
                assertEquals(524_288L, write.prepared.content!!.bytes)
                assertEquals("large.txt", write.prepared.requestedPath)
            }
        } finally { server.stop(0) }
    }

    @Test fun fragmentedArgumentsBeyondTwoMillionAreNeverReassembledAsRawArgumentString() = fixture { c ->
        accumulator(c).use { acc ->
            acc.accept(event("{\"path\":\"a\",\"content\":\"", true))
            val chunk = "x".repeat(8192)
            repeat(260) { acc.accept(event(chunk)) }
            acc.accept(event("\"}")); acc.accept(terminal); acc.accept("[DONE]")
            val result = acc.finishAtTransportEnd(OwnedChatStreamAccumulator.TransportEnd.DONE_MARKER)
            assertTrue(result.calls.single().arguments.bytes > 2_000_000)
            val plan = OwnedChatToolPlanner.prepare(result, c::call, json)
            assertEquals(2_129_920L, (plan.entries.single().input as OwnedChatToolPlanner.Input.WriteFile).prepared.content!!.bytes)
        }
    }

    @Test fun mixedBatchPreservesLegacyToolsAndTheirExistingArgumentGate() = fixture { c ->
        accumulator(c).use { acc ->
            acc.accept(event("{\"path\":\"a\",\"content\":\"x\"}", true))
            acc.accept(event("{\"path\":\"a\"}", true, 1, "read", "read_file"))
            acc.accept(terminal); acc.accept("[DONE]")
            val result = acc.finishAtTransportEnd(OwnedChatStreamAccumulator.TransportEnd.DONE_MARKER)
            val plan = OwnedChatToolPlanner.prepare(result, c::call, json)
            assertEquals(2, plan.entries.size)
            assertTrue(plan.entries[0].input is OwnedChatToolPlanner.Input.WriteFile)
            assertEquals(JsonPrimitive("a"), (plan.entries[1].input as OwnedChatToolPlanner.Input.Legacy).arguments["path"])
            assertThrows(IllegalArgumentException::class.java) { OwnedChatToolPlanner.prepare(result, c::call, json, maximumLegacyArgumentChars = 2) }
        }
    }

    @Test fun missingTerminalIncompleteArgumentsOrUnknownFinishNeverYieldCalls() = fixture { c ->
        for (events in listOf(
            arrayOf(event("{}", true), "[DONE]"),
            arrayOf(event("{", true), terminal, "[DONE]"),
            arrayOf(event("{}", true), """{"choices":[{"delta":{},"finish_reason":"length"}]}""", "[DONE]"),
            arrayOf(event("{}", true), """{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"),
        )) accumulator(c).use { acc ->
            assertThrows(Exception::class.java) { OwnedChatSseIngress.read(sse(*events), acc) }
            assertThrows(IllegalStateException::class.java) { acc.finishAtTransportEnd(OwnedChatStreamAccumulator.TransportEnd.DONE_MARKER) }
        }
    }

    @Test fun duplicateOuterKeysChangedIdentityAndLateDataFailClosed() = fixture { c ->
        for (bad in listOf(
            """{"choices":[null]}""",
            """{"choices":[{"delta":{"role":"user"}}]}""",
            """{"choices":[],"cho\u0069ces":[]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"one","id":"two","function":{"name":"write_file","arguments":"{}"}}]}}]}""",
        )) accumulator(c).use { acc -> assertThrows(Exception::class.java) { acc.accept(bad) } }
        accumulator(c).use { acc -> acc.accept(event("{}", true)); assertThrows(IllegalArgumentException::class.java) { acc.accept(event("", true, id = "changed")) } }
        accumulator(c).use { acc -> acc.accept(event("{}", true)); acc.accept(terminal); assertThrows(IllegalArgumentException::class.java) { acc.accept(event(" ")) } }
        accumulator(c).use { acc -> assertThrows(IllegalArgumentException::class.java) { acc.accept(event("{}")) } }
    }

    @Test fun cancelledProgressCannotBeResumedIntoCompletedToolResult() = fixture { c ->
        accumulator(c).use { acc ->
            assertThrows(CancellationException::class.java) { OwnedChatSseIngress.read(sse(event("{", true), terminal, "[DONE]"), acc,
                progress = { throw CancellationException() }) }
            assertThrows(IllegalStateException::class.java) { acc.finishAtTransportEnd(OwnedChatStreamAccumulator.TransportEnd.CLEAN_EOF) }
            val checkpoints = acc.checkpointIncomplete()
            assertTrue(checkpoints.checkpoints.isNotEmpty())
            assertTrue(checkpoints.checkpoints.all { it.content.state == OwnedResponseContentStore.State.INCOMPLETE })
        }
    }

    @Test fun cleanEofAfterExplicitTerminalIsAcceptedButNoImplicitEmptyArguments() = fixture { c ->
        accumulator(c).use { acc ->
            val result = OwnedChatSseIngress.read(sse(event("{}", true, name = "read_file"), terminal), acc)
            assertEquals("tool_calls", result.finishReason)
        }
        accumulator(c).use { acc ->
            assertThrows(Exception::class.java) { OwnedChatSseIngress.read(sse(event("", true), terminal, "[DONE]"), acc) }
        }
    }

    @Test fun callerTimeoutAndEarlierDeadlineAreRestoredOnSuccessAndFailure() = fixture { c ->
        for (valid in listOf(true, false)) {
            val body = if (valid) sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]") else sse("malformed")
            val timeout = Timeout().timeout(7, java.util.concurrent.TimeUnit.SECONDS)
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            timeout.deadlineNanoTime(deadline)
            val observed = mutableListOf<Long>()
            val source = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    observed += timeout.deadlineNanoTime()
                    return body.read(sink, byteCount)
                }
                override fun timeout() = timeout
                override fun close() = Unit
            }.buffer()
            accumulator(c).use { acc ->
                if (valid) OwnedChatSseIngress.read(source, acc, idleMillis = 60_000)
                else assertThrows(Exception::class.java) { OwnedChatSseIngress.read(source, acc, idleMillis = 60_000) }
            }
            assertTrue(observed.isNotEmpty()); assertTrue(observed.all { it == deadline })
            assertEquals(deadline, timeout.deadlineNanoTime())
            assertEquals(java.util.concurrent.TimeUnit.SECONDS.toNanos(7), timeout.timeoutNanos())
        }
        val timeout = Timeout().timeout(3, java.util.concurrent.TimeUnit.SECONDS)
        val body = sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]")
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long) = body.read(sink, byteCount)
            override fun timeout() = timeout
            override fun close() = Unit
        }.buffer()
        accumulator(c).use { OwnedChatSseIngress.read(source, it) }
        assertFalse(timeout.hasDeadline())
        assertEquals(java.util.concurrent.TimeUnit.SECONDS.toNanos(3), timeout.timeoutNanos())
    }

    @Test fun slowProgressBackpressureDoesNotConsumeProviderIdleWindow() = fixture { c ->
        val body = sse("""{"choices":[{"delta":{"content":"ok"}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]")
        accumulator(c).use { acc ->
            val result = OwnedChatSseIngress.read(body, acc, idleMillis = 200,
                progress = { Thread.sleep(300) })
            assertEquals("ok", read(c, result.publicText, c.response))
        }
    }

    @Test fun realHttpCallCancellationInterruptsBlockedReadWithoutTypedCompletion() = fixture { c ->
        val release = java.util.concurrent.CountDownLatch(1)
        val server = ProviderLoopbackServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.use { output ->
                    output.write(("data: " + event("{", true) + "\n\n").toByteArray()); output.flush()
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
            } finally { exchange.close() }
        }
        server.start()
        try {
            accumulator(c).use { acc ->
                val call = OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/chat").build())
                val response = call.execute()
                assertThrows(java.io.IOException::class.java) { OwnedChatSseIngress.readResponse(response, acc,
                    progress = { call.cancel() }) }
                assertThrows(IllegalStateException::class.java) { acc.finishAtTransportEnd(OwnedChatStreamAccumulator.TransportEnd.CLEAN_EOF) }
            }
        } finally { release.countDown(); server.stop(0) }
    }

    @Test fun emptyUnknownMetadataEventsDoNotConsumeLifetimeWireBudget() = fixture { c ->
        val source = Buffer()
        val metadata = """{"choices":[],"discarded":"${"z".repeat(1000)}"}"""
        repeat(2100) { source.writeUtf8("data: $metadata\n\n") }
        source.writeAll(sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"))
        accumulator(c).use { acc -> assertEquals("ok", read(c, OwnedChatSseIngress.read(source, acc).publicText, c.response)) }
    }
    @Test fun refusalKeepsPrivateReasoningButCannotAdmitToolOrNormalOutcome() = fixture { c ->
        val a=accumulator(c)
        a.accept("""{"choices":[{"delta":{"reasoning_content":"private","refusal":"No"}}]}""")
        a.accept("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""");a.accept("[DONE]")
        val result=a.finishAtTransportEnd(OwnedChatStreamAccumulator.TransportEnd.DONE_MARKER)
        assertTrue(result.hasRefusal);assertTrue(result.hasPrivateContinuation);assertFalse(result.hasMeaningfulPublicText);assertTrue(result.calls.isEmpty())
        val native=json.parseToJsonElement(read(c,result.nativeAssistantMessage,c.response)).jsonObject
        assertEquals("private",native["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("No",native["refusal"]!!.jsonPrimitive.content)
        assertFalse(read(c,result.publicText,c.response).contains("private"))
    }

}
