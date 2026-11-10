package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

/** Display metadata never replaces the exact permission resource. */
class BrowserApprovalContextTest {
    private fun context(url: String) = BrowserApprovalContext("read", url, "main", "Read this page.")

    @Test fun siteContainsOnlySchemeHostAndPortWhileDetailsRetainTheExactUrl() {
        val url = "https://user:password@example.test:8443/path/to/page?private=value#section"
        val context = context(url)
        assertEquals("https://example.test:8443", context.site)
        assertEquals(url, context.pagePreview)
        assertEquals(url, context.url)
    }

    @Test fun siteSupportsIpv4AndIpv6LoopbackDevelopmentOrigins() {
        assertEquals("http://127.0.0.1:5173", context("http://127.0.0.1:5173/index.html").site)
        assertEquals("http://[::1]:8080", context("http://[::1]:8080/page?q=1").site)
        assertEquals("http://localhost:3000", context("http://localhost:3000/").site)
    }

    @Test fun unknownNonWebAndMalformedUrlsHaveAnExplicitFallback() {
        listOf("", "about:blank", "file:///tmp/index.html", "javascript:alert(1)", "data:text/plain,hello",
            "/relative/path", "//example.test/path", "https://", "https://bad host/path", "not a url",
            "https://example.test:65536/path", "https://example.test:-2/path").forEach { url ->
            assertEquals(url, "Site unavailable; see details", context(url).site)
        }
    }

    @Test fun schemeMatchingIsCaseInsensitiveWithoutRewritingTheOriginalUrl() {
        val url = "HTTPS://Example.test:443/CaseSensitivePath?Key=Value"
        val context = context(url)
        assertEquals("HTTPS://Example.test:443", context.site)
        assertEquals(url, context.url)
    }

    @Test fun pagePreviewAtTheLimitRemainsExactAndHasNoTruncationMarker() {
        val prefix = "https://example.test/"
        val url = prefix + "a".repeat(8_192 - prefix.length)
        assertEquals(8_192, url.length)
        assertEquals(url, context(url).pagePreview)
        assertEquals("https://example.test", context(url).site)
    }

    @Test fun oversizedPreviewIsBoundedAndMarkedWhileTheRawPermissionUrlRemainsExact() {
        val url = "https://example.test/" + "a".repeat(20_000)
        val context = context(url)
        assertEquals(url.take(8_192) + "… (URL truncated)", context.pagePreview)
        assertEquals("Site unavailable; see details", context.site)
        assertEquals(url, context.url)
    }

    @Test fun displayMetadataDoesNotChangeActionResourceOrSessionGrantIdentity() {
        val request = ApprovalRequest(7, "browser_interact", "Click an element", "Exact permission resource",
            action = "browser_interact", resource = "https://example.test/page#click:button-7",
            permissionScope = "build", approvalSessionId = "session-7",
            sessionGrantAction = "browser_interact", sessionGrantResource = "https://example.test/page#click:button-7")
        val presented = request.copy(browserContext = context("https://example.test/page"))
        assertEquals(request.id, presented.id)
        assertEquals(request.action, presented.action)
        assertEquals(request.resource, presented.resource)
        assertEquals(request.permissionScope, presented.permissionScope)
        assertEquals(request.approvalSessionId, presented.approvalSessionId)
        assertEquals(request.sessionGrantAction, presented.sessionGrantAction)
        assertEquals(request.sessionGrantResource, presented.sessionGrantResource)
        assertEquals(request.canAllowForSession, presented.canAllowForSession)
    }
}
