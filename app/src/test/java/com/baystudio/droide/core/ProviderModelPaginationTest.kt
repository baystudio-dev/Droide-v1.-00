package com.baystudio.droide.core

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderModelPaginationTest {
    private fun provider(protocol: ProviderWireProtocol) = AiProvider(
        "custom.pages-${UUID.randomUUID()}", "Pages", "https://example.invalid/v1", "", false, false, "", "",
        wireProtocol = protocol, authScheme = ProviderAuthScheme.NONE,
    )

    @Test fun googlePagesUseEncodedOpaqueTokensOnTheOriginalModelsPath() = runBlocking {
        val paths = mutableListOf<String>()
        val result = ProviderModelPagination.collect(provider(ProviderWireProtocol.GOOGLE_GENERATE_CONTENT), Json) { path ->
            paths += path
            if (paths.size == 1) """{"models":[{"name":"models/first"}],"nextPageToken":"https://other.invalid/?x=1&y=two words"}"""
            else """{"models":[{"name":"models/second"}]}"""
        }
        assertEquals(listOf("first", "second"), result.map { it.id })
        assertEquals(listOf("/models", "/models?pageToken=https%3A%2F%2Fother.invalid%2F%3Fx%3D1%26y%3Dtwo%20words"), paths)
    }

    @Test fun anthropicPagesUseAfterIdAndDeduplicateOverlappingRows() = runBlocking {
        val paths = mutableListOf<String>()
        val result = ProviderModelPagination.collect(provider(ProviderWireProtocol.ANTHROPIC_MESSAGES), Json) { path ->
            paths += path
            if (paths.size == 1) """{"data":[{"id":"first"}],"has_more":true,"last_id":"first"}"""
            else """{"data":[{"id":"first"},{"id":"second"}],"has_more":false}"""
        }
        assertEquals(listOf("first", "second"), result.map { it.id })
        assertEquals(listOf("/models", "/models?after_id=first"), paths)
    }

    @Test fun repeatedAndMissingCursorsFailRatherThanReturnPartialSuccess() = runBlocking {
        var calls = 0
        val duplicate = runCatching { ProviderModelPagination.collect(provider(ProviderWireProtocol.GOOGLE_GENERATE_CONTENT), Json) {
            calls++; """{"models":[{"name":"models/first"}],"nextPageToken":"repeat"}"""
        } }.exceptionOrNull()
        assertTrue(duplicate is IllegalArgumentException)
        assertEquals(2, calls)
        assertTrue(runCatching { ProviderModelPagination.collect(provider(ProviderWireProtocol.ANTHROPIC_MESSAGES), Json) {
            """{"data":[{"id":"first"}],"has_more":true}"""
        } }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun invalidCursorTypesAndFlagsFailLocally() = runBlocking {
        for (payload in listOf(
            """{"models":[],"nextPageToken":9}""",
            """{"models":[],"nextPageToken":"bad\nvalue"}""",
        )) assertTrue(runCatching {
            ProviderModelPagination.collect(provider(ProviderWireProtocol.GOOGLE_GENERATE_CONTENT), Json) { payload }
        }.isFailure)
        assertTrue(runCatching {
            ProviderModelPagination.collect(provider(ProviderWireProtocol.ANTHROPIC_MESSAGES), Json) {
                """{"data":[],"has_more":"true","last_id":"id"}"""
            }
        }.isFailure)
    }

    @Test fun arbitraryCompatibleGatewaysDoNotAcquireAnInventedPaginationProtocol() = runBlocking {
        var calls = 0
        val rows = ProviderModelPagination.collect(provider(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS), Json) {
            calls++; """{"data":[{"id":"models/exact"}],"has_more":true,"nextPageToken":"not-native"}"""
        }
        assertEquals(1, calls)
        assertEquals(listOf("models/exact"), rows.map { it.id })
    }

    @Test fun pageAndAggregateByteBudgetsBoundDiscovery() = runBlocking {
        var calls = 0
        val failure = runCatching { ProviderModelPagination.collect(provider(ProviderWireProtocol.GOOGLE_GENERATE_CONTENT), Json) {
            calls++; """{"models":[],"nextPageToken":"page-$calls"}"""
        } }.exceptionOrNull()
        assertTrue(failure!!.message.orEmpty().contains("page budget"))
        assertEquals(32, calls)
        val oversized = runCatching { ProviderModelPagination.collect(provider(ProviderWireProtocol.GOOGLE_GENERATE_CONTENT), Json) {
            " ".repeat(8 * 1024 * 1024 + 1)
        } }.exceptionOrNull()
        assertTrue(oversized!!.message.orEmpty().contains("aggregate pagination budget"))
    }

    @Test fun existingThousandModelCapStopsFurtherRequests() = runBlocking {
        var calls = 0
        val rows = ProviderModelPagination.collect(provider(ProviderWireProtocol.ANTHROPIC_MESSAGES), Json) {
            calls++
            buildJsonObject {
                put("data", JsonArray((1..1000).map { buildJsonObject { put("id", "m$it") } }))
                put("has_more", true); put("last_id", "m1000")
            }.toString()
        }
        assertEquals(1000, rows.size)
        assertEquals(1, calls)
    }

    @Test fun cancellationAfterFetchDoesNotPublishACompletedCatalog() = runBlocking {
        var completed = false
        var cancelled = false
        val job = launch {
            try {
                ProviderModelPagination.collect(provider(ProviderWireProtocol.GOOGLE_GENERATE_CONTENT), Json) {
                    currentCoroutineContext().cancel()
                    """{"models":[{"name":"models/first"}]}"""
                }
                completed = true
            } catch (_: CancellationException) { cancelled = true }
        }
        job.join()
        assertFalse(completed)
        assertTrue(cancelled)
    }

    private class Fixture(protocol: ProviderWireProtocol, val failSecond: Boolean = false) : AutoCloseable {
        private val server = ProviderLoopbackServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        private val executor = Executors.newSingleThreadExecutor()
        val paths = CopyOnWriteArrayList<String>()
        val provider = AiProvider(
            "custom.pages-${UUID.randomUUID()}", "Pages", "http://127.0.0.1:${server.address.port}/v1", "", false, false, "", "",
            wireProtocol = protocol, authScheme = ProviderAuthScheme.NONE, networkScope = ProviderNetworkScope.LOOPBACK_ONLY,
        )
        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                try {
                    paths += exchange.requestURI.toString()
                    val second = paths.size > 1
                    val row = if (second) "second" else "first"
                    val body = if (second && failSecond) """{"error":"fixture failure"}"""
                    else if (protocol == ProviderWireProtocol.GOOGLE_GENERATE_CONTENT) {
                        """{"models":[{"name":"models/$row","outputTokenLimit":32768}]${if (!second) ",\"nextPageToken\":\"cursor\"" else ""}}"""
                    } else """{"data":[{"id":"$row","max_output_tokens":32768}],"has_more":${!second},"last_id":"$row"}"""
                    val bytes = body.toByteArray()
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(if (second && failSecond) 503 else 200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } finally { exchange.close() }
            }
            server.start()
        }
        override fun close() {
            ModelCapabilityRegistry.revokeRuntime(provider.id)
            server.stop(0); executor.shutdownNow()
        }
    }

    @Test(timeout = 20_000) fun authenticationDiscoveryFetchesBothNativePagesBeforePublishingCapabilities() = runBlocking {
        for (protocol in listOf(ProviderWireProtocol.GOOGLE_GENERATE_CONTENT, ProviderWireProtocol.ANTHROPIC_MESSAGES)) {
            Fixture(protocol).use { fixture ->
                val result = ProviderAuthManager.validate(fixture.provider, "")
                assertEquals(listOf("first", "second"), result.getOrThrow())
                assertEquals(2, fixture.paths.size)
                assertTrue(fixture.paths.all { it.startsWith("/v1/models") })
                assertEquals(32_768, ModelCapabilityRegistry.resolve(fixture.provider.id, "second")?.outputTokens)
            }
        }
    }

    @Test(timeout = 20_000) fun failedSecondPageDoesNotPublishPartialCapabilitiesOrSuccessfulDiscovery() = runBlocking {
        Fixture(ProviderWireProtocol.ANTHROPIC_MESSAGES, failSecond = true).use { fixture ->
            assertTrue(ProviderAuthManager.validate(fixture.provider, "").isFailure)
            assertEquals(2, fixture.paths.size)
            assertNull(ModelCapabilityRegistry.resolve(fixture.provider.id, "first"))
        }
    }
}
