package com.baystudio.droide.core

import java.io.IOException
import java.net.Proxy
import java.net.URL
import java.util.Locale
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.transport.HttpConfig
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import org.eclipse.jgit.transport.http.HttpConnectionFactory2

/** Installed only for an operation carrying an app-supplied GitHub credential. */
internal class GitCredentialConnectionFactory(
    private val delegate: HttpConnectionFactory,
    private val config: Config,
    originalUri: URIish,
) : HttpConnectionFactory2 {
    init {
        if (!GitHubGitTransportPolicy.isGitHubHttps(originalUri.toPrivateString())) {
            throw IOException("GitHub credentials require a clean HTTPS github.com remote")
        }
        requireVerifiedConfig(config, originalUri)
    }

    override fun create(url: URL): HttpConnection {
        admit(url)
        return delegate.create(url).also { admit(it.url) }
    }

    override fun create(url: URL, proxy: Proxy): HttpConnection {
        admit(url)
        return delegate.create(url, proxy).also { admit(it.url) }
    }

    override fun newSession(): HttpConnectionFactory2.GitSession {
        val session = (delegate as? HttpConnectionFactory2)?.newSession()
        return object : HttpConnectionFactory2.GitSession {
            override fun configure(connection: HttpConnection, sslVerify: Boolean): HttpConnection {
                admit(connection.url)
                if (!sslVerify) throw IOException("GitHub credentials require TLS certificate verification")
                return (session?.configure(connection, true) ?: connection).also { admit(it.url) }
            }

            override fun close() { session?.close() }
        }
    }

    private fun admit(url: URL) {
        // JGit adds service query parameters. Repository-URL policy is intentionally stricter.
        if (!GitHubGitTransportPolicy.isGitHubRequest(url)) {
            throw IOException("GitHub credential request blocked: HTTPS github.com on port 443 is required")
        }
        requireVerifiedConfig(config, URIish(url))
    }

    companion object {
        internal fun requireVerifiedConfig(config: Config, uri: URIish) {
            val http = try { HttpConfig(config, uri) } catch (_: IllegalArgumentException) {
                throw IOException("GitHub credential request blocked: invalid effective HTTP configuration")
            }
            if (!http.isSslVerify) throw IOException("GitHub credentials require TLS certificate verification")
            requireSafeHeaders(http.extraHeaders)
            if (!http.cookieFile.isNullOrBlank() || http.saveCookies) {
                throw IOException("GitHub credential requests cannot use configured HTTP cookie storage")
            }
            // Redirect mode/limit remain JGit's configuration. Every destination is admitted again
            // before creation, including redirects which otherwise retain cached Basic credentials.
        }

        internal fun requireSafeHeaders(headers: Collection<String>) {
            val forbidden = setOf("authorization", "proxy-authorization", "cookie", "host")
            for (header in headers) {
                val separator = header.indexOf(':')
                val name = header.substringBefore(':').trim()
                if (separator <= 0 || name.isEmpty() ||
                    name.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it !in "!#$%&'*+-.^_`|~" } ||
                    header.any { it == '\r' || it == '\n' || it == '\u0000' } ||
                    name.lowercase(Locale.ROOT) in forbidden) {
                    throw IOException("GitHub credential request blocked: unsafe effective HTTP extra header")
                }
            }
        }
    }
}
