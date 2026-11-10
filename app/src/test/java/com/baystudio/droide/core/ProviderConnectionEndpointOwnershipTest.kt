package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProviderConnectionEndpointOwnershipTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun provider(baseUrl: String) = AiProvider("custom.receipt", "Receipt", baseUrl,
        "", true, false, "", "", authScheme = ProviderAuthScheme.NONE,
        networkScope = ProviderNetworkScope.LOOPBACK_ONLY)

    @Test(timeout = 15_000) fun changedEndpointCannotReadStoredSecretOrMakeValidationRequest() = runBlocking {
        val server = ProviderLoopbackServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val calls = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        val current = provider("http://127.0.0.1:${server.address.port}/v1")
            .copy(needsKey = true, authScheme = ProviderAuthScheme.BEARER)
        val original = current.copy(baseUrl = "https://previous.example/v1")
        val store = ProviderConnectionStateStore(app)
        val rawSecrets = app.getSharedPreferences("droide_secrets_v1", Context.MODE_PRIVATE)
        val receipt = ProviderConnectionRecord(original.id, "api-key", 1L, 1,
            ProviderConnectionIdentity.fingerprint(original))
        try {
            assertTrue(store.put(receipt))

            assertTrue(rawSecrets.edit().putString(original.id, "fixture-preserve-without-decrypting").commit())
            ProviderRegistry.installConfigured(listOf(current))
            val backend = ProviderConnectionBackend(app)
            assertEquals(ProviderConnectionBackend.Status.DISCONNECTED, backend.snapshot(current.id).status)
            assertTrue(backend.revalidate(current.id).isFailure)
            assertEquals(0, calls.get())
            assertEquals(receipt, store.get(original.id))
            assertEquals("fixture-preserve-without-decrypting", rawSecrets.getString(original.id, null))
        } finally {
            store.remove(original.id); rawSecrets.edit().remove(original.id).commit()
            ProviderRegistry.installConfigured(emptyList())
            server.stop(0); executor.shutdownNow()
        }
    }

    @Test(timeout = 15_000) fun successfulConnectionPersistsBindingAndSameEndpointRevalidatesWithoutSetup() = runBlocking {
        val server = ProviderLoopbackServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        val calls = AtomicInteger()
        server.executor = executor
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            val bytes = """{"data":[{"id":"receipt-fixture"}]}""".toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        val current = provider("http://127.0.0.1:${server.address.port}/v1")
        val store = ProviderConnectionStateStore(app)
        try {
            ProviderRegistry.installConfigured(listOf(current))
            val backend = ProviderConnectionBackend(app)
            assertTrue(backend.connect(current.id, "none").getOrThrow().models.contains("receipt-fixture"))
            val saved = checkNotNull(store.get(current.id))
            assertEquals(ProviderConnectionIdentity.fingerprint(current), saved.endpointFingerprint)
            ProviderRegistry.installConfigured(listOf(current.copy(name = "Second project's label")))
            assertEquals(ProviderConnectionBackend.Status.CONNECTED, backend.snapshot(current.id).status)
            assertTrue(backend.revalidate(current.id).isSuccess)
            assertEquals(2, calls.get())
            assertTrue(store.remove(current.id))
            assertNull(store.get(current.id))
            assertFalse(app.getSharedPreferences("droide_provider_connections_v1", Context.MODE_PRIVATE)
                .contains("endpoint.${current.id}"))
        } finally {
            store.remove(current.id); ProviderModelCertificationRegistry.revoke(current.id)
            ProviderRegistry.installConfigured(emptyList())
            server.stop(0); executor.shutdownNow()
        }
    }
}
