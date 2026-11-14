package com.baystudio.droide.core

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** Credential responses through the production token client on real local sockets. */
class ProviderCredentialResponsePolicyTest {
    @Test(timeout = 15_000) fun malformedHttp200NeverExposesAClippedTokenPrefixOrRetries() {
        val messages = listOf(SECRET, "second-credential-prefix-fixture-" + "z".repeat(710)).map { token ->
            CredentialResponseFixture(200, """{"broken" "access_token":"$token"}""").use { fixture ->
                val failure = assertThrows(IllegalStateException::class.java) { fixture.execute() }
                val message = requireNotNull(failure.message)
                assertEquals("Credential endpoint returned malformed JSON (HTTP 200)", message)
                assertFalse(message.contains("credential-prefix"))
                assertFalse(message.contains("JSON input"))
                assertFalse(message.contains("access_token"))
                assertCredentialSafeFailure(failure)
                assertEquals(1, fixture.requests.get())
                message
            }
        }
        assertEquals("Malformed response diagnostics must not depend on credential contents", messages[0], messages[1])
    }

    @Test(timeout = 20_000) fun httpFailuresKeepTheRealStatusAndOnlyAllowlistedOauthCodes() {
        val cases = listOf(
            Triple(401, """{"error":"invalid_grant","error_description":"echo $SECRET","access_token":"$SECRET"}""", "invalid_grant"),
            Triple(503, """{"broken" "echo":"$SECRET"}""", null),
            Triple(403, """{"error":"attacker-code-$SECRET","error_description":"echo $SECRET"}""", null),
        )
        cases.forEach { (status, body, safeCode) ->
            CredentialResponseFixture(status, body).use { fixture ->
                val failure = assertThrows(IllegalStateException::class.java) { fixture.execute() }
                val message = requireNotNull(failure.message)
                assertTrue(message.contains("HTTP $status"))
                if (safeCode != null) assertTrue(message.contains(safeCode))
                assertFalse(message.contains(SECRET_PREFIX))
                assertFalse(message.contains("attacker-code"))
                assertFalse(message.contains("error_description"))
                assertFalse(message.contains("JSON input"))
                assertCredentialSafeFailure(failure)
                assertEquals(1, fixture.requests.get())
            }
        }
        assertTrue(ProviderCredentialResponsePolicy.rejection("OAuth token endpoint", "invalid_grant").message!!.contains("invalid_grant"))
        val rejection = ProviderCredentialResponsePolicy.rejection("OAuth token endpoint", "attacker-code-$SECRET")
        assertFalse(rejection.message!!.contains("attacker-code"))
        assertFalse(rejection.message!!.contains(SECRET_PREFIX))
        assertCredentialSafeFailure(rejection)
    }

    @Test(timeout = 10_000) fun successfulTokenResponseKeepsItsBodyAndCredentialFieldsUnchanged() {
        val body = """{"access_token":"$SECRET","refresh_token":"rotated-refresh-fixture","expires_in":3600,"token_type":"Bearer","extra":{"preserved":true}}"""
        CredentialResponseFixture(200, body).use { fixture ->
            val actual = fixture.execute()
            assertEquals(body, actual)
            val parsed = ProviderCredentialResponsePolicy.parseObject(actual, "Credential endpoint", 200)
            assertEquals(JsonPrimitive(SECRET), parsed["access_token"])
            assertEquals(JsonPrimitive("rotated-refresh-fixture"), parsed["refresh_token"])
            assertEquals(JsonPrimitive(3600), parsed["expires_in"])
            assertEquals(JsonPrimitive("Bearer"), parsed["token_type"])
            assertTrue(parsed.containsKey("extra"))
            assertEquals(1, fixture.requests.get())
        }
    }

    @Test fun metadataParseWithoutAResponseStatusUsesAConstantDiagnostic() {
        val failures = listOf(
            """{"broken" "access_token":"$SECRET"}""",
            """{"access_token":"$SECRET","expires_in":}""",
        ).map { body ->
            assertThrows(IllegalStateException::class.java) {
                ProviderCredentialResponsePolicy.parseObject(body, "Google metadata response")
            }.also { failure ->
                val message = requireNotNull(failure.message)
                assertEquals("Google metadata response returned malformed JSON", message)
                assertFalse(message.contains("HTTP"))
                assertFalse(message.contains(SECRET_PREFIX))
                assertFalse(message.contains("JSON input"))
                assertCredentialSafeFailure(failure)
            }
        }
        assertEquals(failures[0].message, failures[1].message)
        val nonObject = assertThrows(IllegalStateException::class.java) {
            ProviderCredentialResponsePolicy.parseObject("""["$SECRET"]""", "Google metadata response")
        }
        assertEquals("Google metadata response returned invalid JSON", nonObject.message)
        assertCredentialSafeFailure(nonObject)
    }

    private fun assertCredentialSafeFailure(failure: Throwable) {
        // Coroutine recovery may attach the original safe exception as a cause.
        // Assert the complete diagnostic boundary rather than requiring no cause.
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = failure
        while (current != null && seen.add(current)) {
            assertFalse("A raw serialization cause must not escape", current is SerializationException)
            current = current.cause
        }
        val diagnostic = failure.stackTraceToString()
        listOf("credential-prefix", SECRET_PREFIX, "JSON input", "access_token", "error_description", "attacker-code").forEach { raw ->
            assertFalse("Raw credential response text must not escape: $raw", diagnostic.contains(raw))
        }
    }

    private class CredentialResponseFixture(status: Int, body: String) : AutoCloseable {
        val requests = AtomicInteger()
        private val handlerFailure = AtomicReference<Throwable?>()
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "droide-credential-response-fixture").apply { isDaemon = true }
        }
        private val server = ProviderLoopbackServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        private val provider = AiProvider(
            id = "credential.response.fixture", name = "Credential response fixture",
            baseUrl = "http://127.0.0.1:${server.address.port}", model = "fixture", free = true,
            needsKey = false, helpUrl = "", note = "test", authScheme = ProviderAuthScheme.NONE,
            networkScope = ProviderNetworkScope.LOOPBACK_ONLY,
        )

        init {
            server.executor = executor
            server.createContext("/token") { exchange ->
                try {
                    exchange.requestBody.use { it.readBytes() }
                    requests.incrementAndGet()
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } catch (failure: Throwable) {
                    handlerFailure.set(failure)
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        fun execute(): String = runBlocking {
            val target = NetworkSecurity.validateProviderTarget(provider, "${provider.baseUrl}/token")
            val request = Request.Builder().url(target.url).post(
                FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", SECRET).build(),
            ).build()
            withTimeout(4_000) { ProviderRequestAuthorizer.executeTokenRequest(target, request) }
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Credential fixture executor did not stop" }
            handlerFailure.get()?.let { throw AssertionError("Credential fixture handler failed", it) }
        }
    }

    private companion object {
        const val SECRET_PREFIX = "credential-prefix-fixture-"
        val SECRET = SECRET_PREFIX + "s".repeat(710)
    }
}
