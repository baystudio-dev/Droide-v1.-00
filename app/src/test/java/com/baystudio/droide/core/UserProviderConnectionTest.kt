package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UserProviderConnectionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @After fun clear() {
        File(app.filesDir, "user-providers.json").delete()
        ProviderRegistry.installUserConfigured(emptyList())
        ProviderModelCertificationRegistry.revoke("user.fixture")
    }

    private fun server(handler: (ProviderLoopbackServer.Exchange) -> Unit): ProviderLoopbackServer =
        ProviderLoopbackServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).also {
            it.createContext("/", handler); it.start()
        }
    private fun respond(exchange: ProviderLoopbackServer.Exchange, body: String, status: Int = 200) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }; exchange.close()
    }
    private fun definition(server: ProviderLoopbackServer) = UserProviderDefinition(
        "user.fixture", "Fixture", "http://127.0.0.1:${server.address.port}/v1", requiresKey = false,
    )

    @Test(timeout = 15000) fun savedProviderSurvivesBackendReloadThenDiscoversCertifiesAndRemoves() = runBlocking {
        val calls = AtomicInteger()
        val server = server { calls.incrementAndGet(); respond(it, """{"data":[{"id":"fixture-model"}]}""") }
        try {
            val backend = ProviderConnectionBackend(app)
            val saved = backend.saveCustomProvider(definition(server)).getOrThrow()
            assertEquals(0, calls.get())
            val restored = ProviderConnectionBackend(app)
            assertEquals(saved, restored.customProvider(saved.id))
            val connected = restored.connect(saved.id, "none").getOrThrow()
            assertEquals(listOf("fixture-model"), connected.models)
            assertEquals(ProviderConnectionBackend.Status.CONNECTED, connected.snapshot.status)
            ProviderRuntimeGuard.requireCertifiedSelection(saved.id, saved.baseUrl, "fixture-model", ProviderRegistry.prepareConfigured(emptyList()))
            restored.removeCustomProvider(saved).getOrThrow()
            assertNull(ProviderRegistry.findById(saved.id))
            assertNull(ProviderConnectionStateStore(app).get(saved.id))
            assertTrue(UserProviderCodec.decode(File(app.filesDir, "user-providers.json").readText()).isEmpty())
        } finally { server.stop(0) }
    }

    @Test(timeout = 15000) fun manualModelsWorkWithoutCatalogForEverySupportedProtocol() = runBlocking {
        for (protocol in ProviderWireProtocol.entries) {
            val paths = mutableListOf<String>()
            val server = server { exchange ->
                paths += exchange.requestURI.path
                assertEquals("POST", exchange.requestMethod)
                val body = when (protocol) {
                    ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> """{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""
                    ProviderWireProtocol.OPENAI_RESPONSES -> """{"status":"completed","output":[]}"""
                    ProviderWireProtocol.ANTHROPIC_MESSAGES -> """{"type":"message","content":[{"type":"text","text":"OK"}]}"""
                    ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> """{"candidates":[{"content":{"parts":[{"text":"OK"}]}}]}"""
                }
                respond(exchange, body)
            }
            try {
                val provider = definition(server).copy(protocol = protocol, models = listOf("manual-model")).provider()
                assertEquals(listOf("manual-model"), ProviderAuthManager.validate(provider, "").getOrThrow())
                assertEquals(1, paths.size)
                assertFalse(paths.contains("/v1/models"))
            } finally { server.stop(0) }
        }
    }

    @Test(timeout = 15000) fun failedManualModelNeverCommitsPartialConnection() = runBlocking {
        val count = AtomicInteger()
        val server = server {
            if (count.incrementAndGet() == 1) respond(it, """{"choices":[{"message":{"content":"OK"}}]}""")
            else respond(it, """{"error":{"message":"not authorized"}}""", 401)
        }
        try {
            val backend = ProviderConnectionBackend(app)
            val saved = backend.saveCustomProvider(definition(server).copy(models = listOf("one", "two"))).getOrThrow()
            assertTrue(backend.connect(saved.id, "none").isFailure)
            assertEquals(2, count.get())
            assertEquals(ProviderConnectionBackend.Status.DISCONNECTED, backend.snapshot(saved.id).status)
            assertNull(ProviderConnectionStateStore(app).get(saved.id))
            assertFalse(ProviderModelCertificationRegistry.isCertified(saved.provider(), "one"))
        } finally { server.stop(0) }
    }

    @Test(timeout = 15000) fun editingDestinationNeverReusesPreviousCredential() = runBlocking {
        val count = AtomicInteger()
        val server = server { count.incrementAndGet(); respond(it, "{}") }
        try {
            val backend = ProviderConnectionBackend(app)
            val old = backend.saveCustomProvider(definition(server).copy(baseUrl = "https://previous.example/v1", requiresKey = true)).getOrThrow()
            val oldModel = ProviderRuntimeCapabilities.parse(old.provider(), ProviderModelCatalog.Entry("old-model",
                Json.parseToJsonElement("""{"id":"old-model","max_output_tokens":1234}""").jsonObject))!!
            ModelCapabilityRegistry.installRuntime(oldModel)
            assertNotNull(ModelCapabilityRegistry.resolve(old.id, "old-model"))
            ProviderConnectionStateStore(app).put(ProviderConnectionRecord(old.id, "api-key", 1L, 1, ProviderConnectionIdentity.fingerprint(old.provider())))
            val secrets = app.getSharedPreferences("droide_secrets_v1", Context.MODE_PRIVATE)
            secrets.edit().putString(old.id, "do-not-decrypt-or-forward").commit()
            backend.saveCustomProvider(old.copy(baseUrl = definition(server).baseUrl), old).getOrThrow()
            assertNull(ModelCapabilityRegistry.resolve(old.id, "old-model"))
            assertEquals(ProviderConnectionBackend.Status.DISCONNECTED, backend.snapshot(old.id).status)
            assertTrue(backend.revalidate(old.id).isFailure)
            assertEquals(0, count.get())
            assertEquals("do-not-decrypt-or-forward", secrets.getString(old.id, null))
        } finally { server.stop(0) }
    }
}
