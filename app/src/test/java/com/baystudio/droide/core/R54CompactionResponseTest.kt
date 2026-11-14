package com.baystudio.droide.core

import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class R54CompactionResponseTest {
    @Test(timeout = 30_000) fun blankFirstChunkPreservesTheEntireOriginalContext() =
        compactWithBlankResponse(blankRequest = 1)

    @Test(timeout = 30_000) fun blankLaterChunkCannotBeSilentlyDropped() =
        compactWithBlankResponse(blankRequest = 2)

    @Test(timeout = 30_000) fun blankConsolidationPreservesTheEntireOriginalContext() =
        compactWithBlankResponse(blankRequest = 3)

    private fun compactWithBlankResponse(blankRequest: Int) = runBlocking {
        val server = ProviderLoopbackServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        val requests = AtomicInteger()
        val model = "compaction-response-fixture"
        val provider = AiProvider(id = "r54.compaction.${UUID.randomUUID()}", name = "Compaction response fixture",
            baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = model, free = true,
            needsKey = false, helpUrl = "", note = "test", wireProtocol = ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,
            authScheme = ProviderAuthScheme.NONE, networkScope = ProviderNetworkScope.LOOPBACK_ONLY)
        val dir = Files.createTempDirectory("droide-r54-compaction-response-").toFile()
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                exchange.requestBody.use { it.readBytes() }
                val request = requests.incrementAndGet()
                val content = if (request == blankRequest) " \n\t" else "Checkpoint for historical chunk $request."
                val bytes = response(content).toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        server.start()
        ProviderRegistry.installConfigured(listOf(provider))
        ProviderModelCertificationRegistry.certify(provider, listOf(model))
        try {
            val manager = SessionManager(dir)
            val config = AgentConfig(provider.baseUrl, "", model, provider.id)
            val session = manager.create().copy(apiHistory = listOf(
                message("user", "RESTRICTION_A " + "A".repeat(26_000)),
                message("assistant", "UNRESOLVED_ERROR_B " + "B".repeat(26_000)),
                message("user", "Continue within the existing restrictions."),
            ))
            manager.save(session)
            val selection = AgentHistory.selectForCompaction(session.apiHistory, 1000)
            assertEquals("The fixture must require two historical summaries before consolidation", 2,
                manager.chunkRenderedHistory(selection.historical, manager.compactionChunkChars(config)).size)
            val original = manager.getForUpdate(session.id)!!.apiHistory
            val failure = runCatching {
                manager.compact(session, config, keepRecentTokens = 1000)
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure!!.message.orEmpty().contains("empty checkpoint"))
            assertEquals("Stop immediately after an unusable summary", blankRequest, requests.get())
            assertEquals("No historical chunk may be lost before a valid checkpoint commits", original,
                manager.getForUpdate(session.id)!!.apiHistory)
        } finally {
            ProviderModelCertificationRegistry.revoke(provider.id)
            ModelCapabilityRegistry.revokeRuntime(provider.id)
            ProviderRegistry.installConfigured(emptyList())
            server.stop(0)
            executor.shutdownNow()
            PathSecurity.deleteTreeNoFollow(dir)
        }
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun response(content: String) = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            put("finish_reason", "stop")
            put("message", message("assistant", content))
        }) })
    }.toString()
}
