package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.Proxy as ReflectionProxy
import java.net.Proxy
import java.net.URL
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.transport.HttpConfig
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.TransportHttp
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory2
import org.junit.Assert.*
import org.junit.Test

/** All responses are in-memory proxies. These tests cannot make a network connection. */
class GitCredentialTlsPolicyTest {
    private val origin = URIish("https://github.com/fixture/repo.git")
    private val request = URL("https://github.com/fixture/repo.git/info/refs?service=git-upload-pack")

    @Test fun inheritedAndUriScopedTlsMustBeVerifiedWithoutConfigMutation() {
        val parent = Config().apply { setBoolean("http", null, "sslVerify", false) }
        val config = Config(parent)
        val delegate = RecordingFactory()
        val before = config.toText()
        assertThrows(IOException::class.java) { GitCredentialConnectionFactory(delegate, config, origin) }
        assertEquals(before, config.toText())
        assertEquals(0, delegate.requests.size)
        config.setBoolean("http", "https://github.com/fixture", "sslVerify", true)
        val guarded = GitCredentialConnectionFactory(delegate, config, origin)
        guarded.create(request)
        assertEquals(1, delegate.requests.size)
        assertThrows(IOException::class.java) {
            guarded.create(URL("https://github.com/sibling/repo.git/info/refs?service=git-upload-pack"))
        }
        assertEquals(1, delegate.requests.size)
        config.setBoolean("http", "https://github.com/fixture/repo.git/info", "sslVerify", false)
        assertThrows(IOException::class.java) { guarded.create(request) }
        assertEquals(1, delegate.requests.size)
    }

    @Test fun effectiveHeaderAndCookieResetsUseJGitSemantics() {
        val parent = Config().apply {
            setString("http", null, "extraHeader", "Authorization: inherited-fixture")
            setString("http", null, "cookieFile", "fixture-cookie-file")
        }
        val config = Config(parent)
        val before = parent.toText()
        assertThrows(IOException::class.java) { GitCredentialConnectionFactory(RecordingFactory(), config, origin) }
        // The parser represents an empty configured value with JGit's null sentinel.
        // setStringList(listOf("")) is a different in-memory value in this pinned release.
        config.fromText("[http \"https://github.com/fixture\"]\n\textraHeader =\n")
        assertTrue(HttpConfig(config, origin).extraHeaders.isEmpty())
        config.setString("http", "https://github.com/fixture", "cookieFile", "")
        assertEquals("", HttpConfig(config, origin).cookieFile)
        GitCredentialConnectionFactory(RecordingFactory(), config, origin).create(request)
        config.setStringList("http", "https://github.com/fixture", "extraHeader", listOf("X-Fixture: allowed"))
        GitCredentialConnectionFactory(RecordingFactory(), config, origin).create(request)
        for (header in listOf("Host: example.invalid", "Cookie: fixture", "Proxy-Authorization: fixture", "X-Fixture: yes\r\nHost: example.invalid")) {
            config.setString("http", "https://github.com/fixture", "extraHeader", header)
            assertThrows(IOException::class.java) { GitCredentialConnectionFactory(RecordingFactory(), config, origin) }
        }
        assertEquals(before, parent.toText())
    }

    @Test fun actualDefaultTransportAdmitsCredentialCallbackWithoutInitializingHeaders() {
        InMemoryRepository(DfsRepositoryDescription("default-headers-fixture")).use { repo ->
            repo.config.setString("http", "https://github.com/fixture", "extraHeader", "X-Fixture: configured")
            val before = repo.config.toText()
            (Transport.open(repo, origin) as TransportHttp).use { transport ->
                val originalFactory = transport.httpConnectionFactory

                GitTransportSecurity.callback(true).configure(transport)
                assertTrue(transport.httpConnectionFactory is GitCredentialConnectionFactory)
                assertNotSame(originalFactory, transport.httpConnectionFactory)
                assertEquals(listOf("X-Fixture: configured"), HttpConfig(repo.config, origin).extraHeaders)
            }
            assertEquals(before, repo.config.toText())
        }
    }

    @Test fun callbackPreservesExistingAdditionalHeadersAndRejectsUnsafeOnes() {
        InMemoryRepository(DfsRepositoryDescription("additional-headers-fixture")).use { repo ->
            (Transport.open(repo, origin) as TransportHttp).use { transport ->
                val supplied = linkedMapOf("X-Fixture" to "programmatic")
                transport.additionalHeaders = supplied
                GitTransportSecurity.callback(true).configure(transport)
                assertEquals(supplied, transport.additionalHeaders)
            }
            for (name in listOf("Host", "Authorization", "Cookie", "Proxy-Authorization")) {
                (Transport.open(repo, origin) as TransportHttp).use { transport ->
                    val supplied = linkedMapOf(name to "synthetic-fixture")
                    transport.additionalHeaders = supplied
                    val originalFactory = transport.httpConnectionFactory
                    assertThrows(IOException::class.java) { GitTransportSecurity.callback(true).configure(transport) }
                    assertSame(originalFactory, transport.httpConnectionFactory)
                    assertEquals(supplied, transport.additionalHeaders)
                }
            }
            (Transport.open(repo, origin) as TransportHttp).use { transport ->
                transport.setAdditionalHeaders(null)
                GitTransportSecurity.callback(true).configure(transport)
                assertTrue(transport.httpConnectionFactory is GitCredentialConnectionFactory)
            }
        }
    }

    @Test fun unsafeRedirectOriginsAreRefusedBeforeDelegateCreation() {
        val delegate = RecordingFactory()
        val guarded = GitCredentialConnectionFactory(delegate, Config(), origin)
        guarded.create(request)
        for (raw in listOf(
            "http://github.com/fixture/repo.git/info/refs",
            "https://github.com:8443/fixture/repo.git/info/refs",
            "https://example.invalid/fixture/repo.git/info/refs",
            "https://fixture@github.com/fixture/repo.git/info/refs",
        )) assertThrows(IOException::class.java) { guarded.create(URL(raw)) }
        assertEquals(1, delegate.requests.size)
        guarded.create(URL("https://github.com:443/new/repo.git/info/refs?service=git-upload-pack"))
        assertEquals(2, delegate.requests.size)
    }

    @Test fun factory2TlsConfigurationAndCloseArePreservedAndFalseNeverDelegates() {
        val delegate = RecordingFactory()
        val guarded = GitCredentialConnectionFactory(delegate, Config(), origin)
        val connection = guarded.create(request)
        val session = guarded.newSession()
        assertSame(connection, session.configure(connection, true))
        assertEquals(listOf(true), delegate.sslFlags)
        assertThrows(IOException::class.java) { session.configure(connection, false) }
        assertEquals(listOf(true), delegate.sslFlags)
        session.close()
        assertEquals(1, delegate.closed)
    }

    @Test fun realJGitCachedBasicCannotFollowSameHostDifferentPortRedirect() {
        InMemoryRepository(DfsRepositoryDescription("redirect-fixture")).use { repo ->
            repo.config.setString("remote", "origin", "url", origin.toString())
            val configBefore = repo.config.toText()
            val delegate = RecordingFactory(listOf(
                Response(401, mapOf("WWW-Authenticate" to listOf("Basic realm=\"fixture\""))),
                Response(302, mapOf("Location" to listOf("https://github.com:8443/fixture/repo.git/info/refs?service=git-upload-pack"))),
            ))
            val credential = GitTransportAuth.forRemote(repo, "origin", false, "fixture", "synthetic-token")!!
            try {
                (Transport.open(repo, origin) as TransportHttp).use { transport ->
                    transport.httpConnectionFactory = delegate
                    transport.credentialsProvider = credential
                    GitTransportSecurity.callback(true).configure(transport)
                    assertThrows(org.eclipse.jgit.errors.TransportException::class.java) { transport.openFetch() }
                }
            } finally { credential.clear() }
            assertEquals(2, delegate.requests.size)
            assertNull(delegate.requests[0].headers["Authorization"])
            assertTrue(delegate.requests[1].headers["Authorization"]!!.startsWith("Basic "))
            assertTrue(delegate.requests.all { it.url.port in listOf(-1, 443) })
            assertEquals(configBefore, repo.config.toText())
        }
    }

    @Test fun anonymousCallbackDoesNotChangeHttpFactoryOrTlsConfiguration() {
        InMemoryRepository(DfsRepositoryDescription("anonymous-fixture")).use { repo ->
            repo.config.setBoolean("http", null, "sslVerify", false)
            val before = repo.config.toText()
            (Transport.open(repo, URIish("https://example.invalid/repo.git")) as TransportHttp).use { transport ->
                val delegate = RecordingFactory()
                transport.httpConnectionFactory = delegate
                GitTransportSecurity.callback(false).configure(transport)
                assertSame(delegate, transport.httpConnectionFactory)
            }
            assertEquals(before, repo.config.toText())
        }
    }

    private data class Response(val code: Int = 200, val headers: Map<String, List<String>> = emptyMap())
    private data class Request(val url: URL, val headers: MutableMap<String, String> = linkedMapOf())

    private class RecordingFactory(private val responses: List<Response> = emptyList()) : HttpConnectionFactory2 {
        val requests = mutableListOf<Request>()
        val sslFlags = mutableListOf<Boolean>()
        var closed = 0
        override fun create(url: URL): HttpConnection = create(url, Proxy.NO_PROXY)
        override fun create(url: URL, proxy: Proxy): HttpConnection {
            val response = responses.getOrElse(requests.size) { Response() }
            val request = Request(url)
            requests += request
            var requestMethod = "GET"
            return ReflectionProxy.newProxyInstance(HttpConnection::class.java.classLoader, arrayOf(HttpConnection::class.java)) { _, method, args ->
                when (method.name) {
                    "getURL" -> url
                    "getResponseCode" -> response.code
                    "getResponseMessage" -> "Synthetic response"
                    "getHeaderFields" -> if (args.isNullOrEmpty()) response.headers else response.headers.entries.firstOrNull { it.key.equals(args[0] as String, true) }?.value.orEmpty()
                    "getHeaderField" -> response.headers.entries.firstOrNull { it.key.equals(args!![0] as String, true) }?.value?.firstOrNull()
                    "setRequestProperty" -> { request.headers[args!![0] as String] = args[1] as String; null }
                    "setRequestMethod" -> { requestMethod = args!![0] as String; null }
                    "getRequestMethod" -> requestMethod
                    "getContentType" -> "application/x-git-upload-pack-advertisement"
                    "getContentLength" -> 0
                    "getInputStream" -> ByteArrayInputStream(byteArrayOf())
                    "getOutputStream" -> ByteArrayOutputStream()
                    "usingProxy" -> false
                    "setUseCaches", "setConnectTimeout", "setReadTimeout", "setInstanceFollowRedirects", "setDoOutput", "setFixedLengthStreamingMode", "setChunkedStreamingMode", "connect" -> null
                    "toString" -> "SyntheticHttpConnection"
                    else -> throw AssertionError("Unexpected synthetic connection call: ${method.name}")
                }
            } as HttpConnection
        }
        override fun newSession(): HttpConnectionFactory2.GitSession = object : HttpConnectionFactory2.GitSession {
            override fun configure(connection: HttpConnection, sslVerify: Boolean): HttpConnection {
                sslFlags += sslVerify
                return connection
            }
            override fun close() { closed++ }
        }
    }
}
