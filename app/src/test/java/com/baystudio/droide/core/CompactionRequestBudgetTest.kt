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

class CompactionRequestBudgetTest {
    @Test fun everyAdaptiveBranchSharesAFiniteRequestAndDepthBudget() {
        val budget = CompactionRequestBudget()
        repeat(CompactionRequestBudget.MAX_REQUESTS) { budget.enter(0) }
        assertThrows(IllegalStateException::class.java) { budget.enter(0) }
        assertThrows(IllegalStateException::class.java) { CompactionRequestBudget().enter(CompactionRequestBudget.MAX_DEPTH + 1) }
    }

    @Test(timeout = 30_000) fun overflowingNonShrinkingAdaptiveSummariesStopAfterThreeRequestsAndPreserveSession() = runBlocking {
        val server = ProviderLoopbackServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        val requests = AtomicInteger()
        val model = "compaction-fixture"
        val provider = AiProvider(id = "compaction.${UUID.randomUUID()}", name = "Compaction fixture",
            baseUrl = "http://127.0.0.1:${server.address.port}/v1", model = model, free = true,
            needsKey = false, helpUrl = "", note = "test", wireProtocol = ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,
            authScheme = ProviderAuthScheme.NONE, networkScope = ProviderNetworkScope.LOOPBACK_ONLY)
        val dir = Files.createTempDirectory("droide-compaction-budget-").toFile()
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                requests.incrementAndGet()
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                val prompt = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
                val overflow = prompt.length > 16_000
                val response = if (overflow) """{"error":{"message":"maximum context length exceeded"}}""" else buildJsonObject {
                    put("choices", buildJsonArray { add(buildJsonObject {
                        put("message", buildJsonObject { put("role", "assistant"); put("content", "S".repeat(12_000)) })
                        put("finish_reason", "stop")
                    }) })
                }.toString()
                val bytes = response.toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(if (overflow) 400 else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        server.start()
        ProviderRegistry.installConfigured(listOf(provider))
        ProviderModelCertificationRegistry.certify(provider, listOf(model))
        try {
            val manager = SessionManager(dir)
            fun message(role: String, text: String) = buildJsonObject { put("role", role); put("content", text) }
            val session = manager.create().copy(apiHistory = listOf(
                message("user", "U".repeat(20_000)), message("assistant", "done"), message("user", "continue"),
            ))
            manager.save(session)
            val failure = runCatching { manager.compact(session, AgentConfig(provider.baseUrl, "", model, provider.id), keepRecentTokens = 1000) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message.orEmpty().contains("compaction budget"))
            assertEquals(3, requests.get())
            assertEquals(session.apiHistory, manager.getForUpdate(session.id)!!.apiHistory)
        } finally {
            ProviderModelCertificationRegistry.revoke(provider.id)
            ModelCapabilityRegistry.revokeRuntime(provider.id)
            ProviderRegistry.installConfigured(emptyList())
            server.stop(0); executor.shutdownNow()
            PathSecurity.deleteTreeNoFollow(dir)
        }
    }
}
