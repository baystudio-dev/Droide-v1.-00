package com.baystudio.droide.core

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class ProviderLongRunningTransportTest {
    private val protocols = ProviderWireProtocol.entries.toList()

    @Test fun productionGenerationClientHasThirtyMinuteIdleWithoutATotalCallDeadline() {
        val field = LlmClient::class.java.getDeclaredField("client").apply { isAccessible = true }
        val client = field.get(LlmClient()) as OkHttpClient
        assertEquals(1_800_000, client.readTimeoutMillis)
        assertEquals(1_800_000, client.writeTimeoutMillis)
        assertEquals(30_000, client.connectTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse("Ambiguous generation disconnects must not replay silently", client.retryOnConnectionFailure)
    }

    @Test(timeout = 30_000) fun silentBeforeResponseHeadersBeyondTenSecondsSurvivesEveryProtocol() =
        withFixtures(Behavior.DELAY_HEADERS) { fixtures -> runBlocking {
            withTimeout(25_000) { fixtures.map { fixture -> async(Dispatchers.IO) {
                assertEquals("ok", content(fixture.stream()))
            } }.awaitAll() }
        } }

    @Test(timeout = 30_000) fun silentAfterTextDeltaBeyondTenSecondsSurvivesEveryProtocol() =
        withFixtures(Behavior.DELAY_BODY) { fixtures -> runBlocking {
            withTimeout(25_000) { fixtures.map { fixture -> async(Dispatchers.IO) {
                assertEquals("ok", content(fixture.stream()))
            } }.awaitAll() }
        } }

    @Test(timeout = 160_000) fun activeStreamsContinuePastTheFormerTwoMinuteTotalLimit() =
        withFixtures(Behavior.LONG_ACTIVE) { fixtures -> runBlocking {
            val started = System.nanoTime()
            withTimeout(150_000) { fixtures.map { fixture -> async(Dispatchers.IO) {
                assertEquals("ok", content(fixture.stream()))
            } }.awaitAll() }
            assertTrue("The actual fixture must cross the former 120s deadline", elapsedMs(started) >= 124_000)
        } }

    @Test(timeout = 20_000) fun stopWhileWaitingForHeadersCancelsEveryProtocolPromptly() =
        withFixtures(Behavior.STALL_HEADERS) { fixtures -> runBlocking {
            val jobs = fixtures.map { fixture -> async(Dispatchers.IO) { fixture.stream() } }
            fixtures.forEach { assertTrue(it.entered.await(5, TimeUnit.SECONDS)) }
            assertPromptCancellation(jobs)
        } }

    @Test(timeout = 20_000) fun stopAfterPartialStreamingTextCancelsEveryProtocolPromptly() =
        withFixtures(Behavior.STALL_BODY) { fixtures -> runBlocking {
            val jobs = fixtures.map { fixture -> async(Dispatchers.IO) { fixture.stream() } }
            withTimeout(5_000) { fixtures.forEach { it.textSeen.await() } }
            assertPromptCancellation(jobs)
        } }

    @Test(timeout = 20_000) fun stopDuringAnIncompleteBufferedBodyCancelsEveryProtocolPromptly() =
        withFixtures(Behavior.STALL_BUFFERED) { fixtures -> runBlocking {
            val jobs = fixtures.map { fixture -> async(Dispatchers.IO) { fixture.buffered() } }
            fixtures.forEach { assertTrue(it.bodySent.await(5, TimeUnit.SECONDS)) }
            delay(100) // Headers/body prefix have arrived; cancellation now must cover body consumption.
            assertPromptCancellation(jobs)
        } }

    @Test(timeout = 10_000) fun aRealScaledReadIdleStillExpiresAsFailureRatherThanUserCancellation() =
        withFixtures(Behavior.STALL_BUFFERED, listOf(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS)) { fixtures -> runBlocking {
            val client = rawClient(250)
            val call = client.newCall(Request.Builder().url(fixtures.single().provider.baseUrl).build())
            val failure = runCatching { call.withCancellableResponse { it.body!!.string() } }.exceptionOrNull()
            assertTrue("Read idle must expire", failure is IOException)
            assertFalse(failure is CancellationException)
            assertTrue(currentCoroutineContext().isActive)
        } }

    @Test(timeout = 10_000) fun byteActivityRenewsTheIdleWindowWithoutAWholeResponseDeadline() =
        withFixtures(Behavior.BYTE_ACTIVITY, listOf(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS)) { fixtures -> runBlocking {
            val call = rawClient(1_000).newCall(Request.Builder().url(fixtures.single().provider.baseUrl).build())
            val started = System.nanoTime()
            assertEquals("xxxxxx", call.withCancellableResponse { it.body!!.string() })
            assertTrue(elapsedMs(started) >= 1_200)
            assertFalse("Normal completion must disarm the cancellation watcher", call.isCanceled())
        } }

    @Test(timeout = 10_000) fun normalCompletionClosesResponseAndAllowsAnotherCallWithoutCancellingEither() =
        withFixtures(Behavior.COMPLETE_BUFFERED, listOf(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS)) { fixtures -> runBlocking {
            val client = rawClient(2_000)
            repeat(2) {
                val call = client.newCall(Request.Builder().url(fixtures.single().provider.baseUrl).build())
                assertTrue(call.withCancellableResponse { it.body!!.string() }.contains("ok"))
                assertFalse(call.isCanceled())
            }
            assertFalse(currentCoroutineContext().job.children.any { it.isActive })
        } }

    private suspend fun assertPromptCancellation(jobs: List<Deferred<*>>) {
        val started = System.nanoTime()
        jobs.forEach { it.cancel() }
        withTimeout(2_000) { jobs.forEach { it.join() } }
        assertTrue("Stop must not wait for socket idle", elapsedMs(started) < 2_000)
        jobs.forEach { assertTrue(it.isCancelled) }
    }

    private fun content(result: LlmStreamResult) = Json.parseToJsonElement(result.responseBody).jsonObject
        .getValue("choices").jsonArray.single().jsonObject.getValue("message").jsonObject.getValue("content").jsonPrimitive.content
    private fun rawClient(readMs: Long) = OkHttpClient.Builder().readTimeout(readMs, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build()
    private fun elapsedMs(started: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    private fun withFixtures(behavior: Behavior, selected: List<ProviderWireProtocol> = protocols, block: (List<Fixture>) -> Unit) {
        val fixtures = selected.map { Fixture(it, behavior) }
        ProviderRegistry.installConfigured(fixtures.map { it.provider })
        fixtures.forEach { ProviderModelCertificationRegistry.certify(it.provider, listOf(it.model)) }
        try {
            block(fixtures)
            fixtures.forEach { it.handlerFailure.get()?.let { failure -> throw AssertionError("Loopback handler failed", failure) } }
        } finally {
            fixtures.forEach { fixture ->
                ProviderModelCertificationRegistry.revoke(fixture.provider.id)
                ModelCapabilityRegistry.revokeRuntime(fixture.provider.id)
                fixture.close()
            }
            ProviderRegistry.installConfigured(emptyList())
        }
    }

    private enum class Behavior { DELAY_HEADERS, DELAY_BODY, LONG_ACTIVE, STALL_HEADERS, STALL_BODY,
        STALL_BUFFERED, BYTE_ACTIVITY, COMPLETE_BUFFERED }

    private class Fixture(val protocol: ProviderWireProtocol, private val behavior: Behavior) : AutoCloseable {
        private val server = ProviderLoopbackServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        private val executor = Executors.newSingleThreadExecutor()
        private val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val bodySent = CountDownLatch(1)
        val textSeen = CompletableDeferred<Unit>()
        val handlerFailure = AtomicReference<Throwable?>()
        val model = when (protocol) {
            ProviderWireProtocol.ANTHROPIC_MESSAGES -> "claude-sonnet-4-5"
            ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> "gemini-2.5-flash"
            ProviderWireProtocol.OPENAI_RESPONSES -> "gpt-5"
            else -> "timeout-fixture"
        }
        val provider = AiProvider(id = "timeout.fixture.${UUID.randomUUID()}", name = "Timeout fixture",
            baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = model, free = true,
            needsKey = false, helpUrl = "", note = "test", wireProtocol = protocol,
            authScheme = ProviderAuthScheme.NONE, networkScope = ProviderNetworkScope.LOOPBACK_ONLY)

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                try {
                    exchange.requestBody.use { it.readBytes() }
                    entered.countDown()
                    if (behavior == Behavior.DELAY_HEADERS) Thread.sleep(11_100)
                    if (behavior == Behavior.STALL_HEADERS) release.await()
                    val buffered = behavior in listOf(Behavior.STALL_BUFFERED, Behavior.COMPLETE_BUFFERED, Behavior.BYTE_ACTIVITY)
                    exchange.responseHeaders.set("Content-Type", if (buffered) "application/json" else "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { output ->
                        when (behavior) {
                            Behavior.STALL_BUFFERED -> { output.write("{".toByteArray()); output.flush(); bodySent.countDown(); release.await() }
                            Behavior.COMPLETE_BUFFERED -> output.write(bufferedWire().toByteArray())
                            Behavior.BYTE_ACTIVITY -> repeat(6) { Thread.sleep(250); output.write('x'.code); output.flush() }
                            Behavior.LONG_ACTIVE -> {
                                val started = System.nanoTime()
                                while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 125_000) {
                                    output.write(": heartbeat\n\n".toByteArray()); output.flush(); Thread.sleep(250)
                                }
                                output.write((prefix() + terminal()).toByteArray())
                            }
                            else -> {
                                output.write(prefix().toByteArray()); output.flush(); bodySent.countDown()
                                if (behavior == Behavior.DELAY_BODY) Thread.sleep(11_100)
                                if (behavior == Behavior.STALL_BODY) release.await()
                                output.write(terminal().toByteArray())
                            }
                        }
                    }
                } catch (failure: Throwable) {
                    // Stop/fixture teardown intentionally disconnects sockets or interrupts waiting handlers.
                    if (release.count != 0L && behavior !in listOf(Behavior.STALL_HEADERS, Behavior.STALL_BODY, Behavior.STALL_BUFFERED))
                        handlerFailure.set(failure)
                } finally { exchange.close() }
            }
            server.start()
        }

        private fun request() = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "hello") }) })
        }.toString()
        suspend fun stream() = LlmClient().chatCompletionsStream(provider.id, provider.baseUrl, "", model, request()) {
            if (it is LlmStreamEvent.TextDelta) textSeen.complete(Unit)
        }
        suspend fun buffered() = LlmClient().chatCompletions(provider.id, provider.baseUrl, "", model, request())
        private fun event(json: String) = "data: $json\n\n"
        private fun prefix(): String = when (protocol) {
            ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> event("""{"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":null}]}""")
            ProviderWireProtocol.OPENAI_RESPONSES -> event("""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"ok"}""")
            ProviderWireProtocol.ANTHROPIC_MESSAGES -> event("""{"type":"message_start","message":{"id":"m1","model":"$model","content":[]}}""") +
                event("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""") +
                event("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""") +
                event("""{"type":"content_block_stop","index":0}""")
            ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> event("""{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]}}]}""")
        }
        private fun terminal(): String = when (protocol) {
            ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> event("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
            ProviderWireProtocol.OPENAI_RESPONSES -> event("""{"type":"response.completed","response":${bufferedWire()}}""")
            ProviderWireProtocol.ANTHROPIC_MESSAGES -> event("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""") + event("""{"type":"message_stop"}""")
            ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> event("""{"candidates":[{"finishReason":"STOP"}]}""")
        }
        private fun bufferedWire(): String = when (protocol) {
            ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> """{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
            ProviderWireProtocol.OPENAI_RESPONSES -> """{"id":"r1","model":"$model","status":"completed","output":[{"type":"message","id":"m1","role":"assistant","content":[{"type":"output_text","text":"ok"}]}]}"""
            ProviderWireProtocol.ANTHROPIC_MESSAGES -> """{"id":"m1","model":"$model","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""
            ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
        }
        override fun close() {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Timeout fixture executor did not stop" }
        }
    }
}
