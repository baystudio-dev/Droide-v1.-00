package com.baystudio.droide.core

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class AgentWebSearchWorkflowTest {
    @Test fun timedOutHtmlEndpointUsesPublicLiteWithoutDroppingTheSearch() = runBlocking {
        var canceled = false
        val transport = WebFixtureTransport { url ->
            if (url.contains("html.duckduckgo")) try { awaitCancellation() } finally { canceled = true }
            else webHtml(url, "<a class='result-link' href='https://docs.example/guide'>Real documentation</a>")
        }
        val result = AgentWebSearch(transport, attemptTimeoutMs = 1_000).search(AgentWebSearch.Request("docs", "duckduckgo"), {})
        assertTrue(canceled)
        assertEquals("https://docs.example/guide", result.hits.single().url)
        assertEquals(listOf("failed", "succeeded"), result.attempts.map { it.status })
        assertEquals(2, transport.requests.size)
    }

    @Test fun genericBingRssLandingPagesFallBackToActualHtmlSourceLinks() = runBlocking {
        val encoded = "a1" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("https://docs.example/real".toByteArray())
        val transport = WebFixtureTransport { url -> if (url.toHttpUrl().queryParameter("format") == "rss")
            webHtml(url, "<rss><channel><item><title>Bing</title><link>https://www.bing.com/</link></item></channel></rss>")
            else webHtml(url, "<li class='b_algo'><h2><a href='https://www.bing.com/ck/a?u=$encoded'>Real source</a></h2><div class='b_caption'><p>Actual excerpt</p></div></li>")
        }
        val result = AgentWebSearch(transport).search(AgentWebSearch.Request("docs", "bing"), {})
        assertEquals("https://docs.example/real", result.hits.single().url)
        assertEquals("Actual excerpt", result.hits.single().snippet)
        assertEquals(listOf("failed", "succeeded"), result.attempts.map { it.status })
        assertNull(result.fallbacks.single().toHttpUrl().queryParameter("format"))
    }

    @Test fun rateLimitedEngineIsNotHammeredAndIsRetriedAfterItsServerWaitExpires() = runBlocking {
        var time = Instant.parse("2026-10-08T00:00:00Z")
        val clock = object : Clock() {
            override fun instant() = time
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
        }
        var first = true
        val transport = WebFixtureTransport { url ->
            if (url.contains("duckduckgo") && first) AgentWebResponse(429, byteArrayOf(), url, retryAfter = "90")
            else if (url.contains("bing")) webHtml(url, "<rss><channel><item><title>Docs</title><link>https://docs.example/guide</link></item></channel></rss>")
            else webHtml(url, webSearchHtml("https://docs.example/guide"))
        }
        val search = AgentWebSearch(transport, clock)
        search.search(AgentWebSearch.Request("first"), {})
        first = false; time = time.plusSeconds(61)
        val second = search.search(AgentWebSearch.Request("second"), {})
        assertEquals("cooldown", second.attempts.first().status)
        assertEquals(1, transport.requests.count { it.contains("duckduckgo") })
        time = time.plusSeconds(30)
        assertEquals("duckduckgo", search.search(AgentWebSearch.Request("third"), {}).engine)
        assertEquals(2, transport.requests.count { it.contains("duckduckgo") })
    }

    @Test fun parentCancellationNeverLaunchesAFallback() = runBlocking {
        val transport = WebFixtureTransport { awaitCancellation() }
        val failure = runCatching { withTimeout(100) {
            AgentWebSearch(transport, attemptTimeoutMs = 5_000).search(AgentWebSearch.Request("docs"), {})
        } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, transport.requests.size)
    }

    @Test fun overallBudgetReturnsTruthfulAttemptsRatherThanLosingEarlierFailures() = runBlocking {
        val transport = WebFixtureTransport { url ->
            if (url.contains("duckduckgo")) AgentWebResponse(429, byteArrayOf(), url) else awaitCancellation()
        }
        val failure = runCatching { AgentWebSearch(transport, attemptTimeoutMs = 5_000, totalTimeoutMs = 1_000)
            .search(AgentWebSearch.Request("docs"), {}) }.exceptionOrNull() as AgentWebSearchUnavailable
        assertEquals("duckduckgo", failure.attempts.first().engine)
        assertTrue(failure.attempts.last().detail.contains("time budget"))
        assertEquals(2, transport.requests.size)
    }

    @Test fun duckDuckGoRedirectUnwrapPreservesActualSourceTitleAndSnippet() {
        val body="<div class='result'><a class='result__a' href='//duckduckgo.com/l/?uddg=https%3A%2F%2Fdocs.example%2Fa%3Fq%3Da%2520b'>Official title</a><a class='result__snippet'>Source description</a></div>"
        val result=AgentWebSearch.parse("duckduckgo",webHtml("https://html.duckduckgo.com/html/",body)).single()
        assertEquals("https://docs.example/a?q=a%20b",result.url); assertEquals("Official title",result.title); assertEquals("Source description",result.snippet)
    }
    @Test fun bingRssAndBraveExcludeAdvertisementsAndUnsafeLinks() {
        val rss="<rss><channel><item><title>Docs</title><link>https://docs.example/page</link><description>&lt;b&gt;Real description&lt;/b&gt;</description></item><item><title>Private</title><link>http://127.0.0.1/</link></item></channel></rss>"
        assertEquals("Real description",AgentWebSearch.parse("bing",webHtml("https://www.bing.com/search",rss)).single().snippet)
        val brave="<div class='snippet' data-type='ad'><a href='https://advert.example'><h3>Ad</h3></a></div><div class='snippet'><a href='https://docs.example'><h3>Source</h3></a><div class='snippet-description'>Description</div></div>"
        assertEquals("https://docs.example/",AgentWebSearch.parse("brave",webHtml("https://search.brave.com/search",brave)).single().url)
    }
    @Test fun autoSearchRetriesBlockedAndChangedLayoutsTruthfully() = runBlocking {
        val transport=WebFixtureTransport { url -> when {
            url.contains("duckduckgo") -> AgentWebResponse(429,"Rate limit".toByteArray(),url)
            url.contains("bing") -> webHtml(url,"<html>CAPTCHA</html>")
            else -> webHtml(url,"<div class='snippet'><a href='https://docs.example'><h3>Real source</h3></a></div>")
        } }
        val result=AgentWebSearch(transport).search(AgentWebSearch.Request("docs"),{})
        assertEquals(listOf("failed","failed","succeeded"),result.attempts.map { it.status }); assertEquals("brave",result.engine)
        assertEquals(3,transport.requests.size)
    }
    @Test fun allChallengesAreFailureWithUsableBrowserFallbacks() = runBlocking {
        val search=AgentWebSearch(WebFixtureTransport { webHtml(it,"<html>Human verification required</html>") })
        val failure=runCatching { search.search(AgentWebSearch.Request("docs"),{}) }.exceptionOrNull() as AgentWebSearchUnavailable
        assertEquals(3,failure.attempts.size); assertTrue(failure.attempts.all { it.status=="failed" }); assertEquals(3,failure.fallbacks.size)
    }
    @Test fun recognizedEmptyResultIsDifferentFromBlockedSearch() = runBlocking {
        val search=AgentWebSearch(WebFixtureTransport { webHtml(it,"<div class='no-results'>No results found</div>") })
        val result=search.search(AgentWebSearch.Request("unique","duckduckgo"),{})
        assertTrue(result.hits.isEmpty()); assertEquals("empty",result.attempts.single().status)
    }
    @Test fun domainsAreActualHostFiltersAndRecencyIsEncodedAsAHint() = runBlocking {
        val clock=Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"),ZoneOffset.UTC)
        val transport=WebFixtureTransport { webHtml(it,webSearchHtml("https://sub.docs.example/guide") + webSearchHtml("https://docs.example.evil.example/fake")) }
        val result=AgentWebSearch(transport,clock).search(AgentWebSearch.Request("Android & Kotlin","duckduckgo",10,listOf("docs.example"),7),{})
        assertEquals("https://sub.docs.example/guide",result.hits.single().url)
        assertEquals("Android & Kotlin (site:docs.example) after:2026-09-27",transport.requests.single().toHttpUrl().queryParameter("q"))
    }
    @Test fun unsafeDomainQueryInjectionAndCredentialUrlsAreRejected() {
        listOf("127.0.0.1","localhost","https://docs.example","docs.example OR site:evil.example","user@docs.example").forEach {
            assertTrue(runCatching { AgentWebSearch.Request("docs",domains=listOf(it)) }.isFailure)
        }
        listOf("file:///etc/passwd","https://user:pass@example.com","https://[::1]/","https://10.0.0.2/").forEach {
            assertTrue(runCatching { AgentWebUrl.normalize(it) }.isFailure)
        }
        assertEquals("https://example.com/",AgentWebUrl.normalize("http://example.com/"))
        assertTrue(runCatching { AgentWebUrl.normalize("https://example.com/" + "雪".repeat(1_000)) }.isFailure)
    }
    @Test fun searchRedirectPermissionFailureDoesNotFallBackToAnotherEngine() = runBlocking {
        var requests=0
        val transport=AgentWebTransport { _,check -> requests++; check("https://unrelated.example/verify"); error("must be denied") }
        val failure=runCatching { AgentWebSearch(transport).search(AgentWebSearch.Request("docs"), { throw AgentWebPermissionDenied("DENIED redirect") }) }.exceptionOrNull()
        assertTrue(failure is AgentWebPermissionDenied); assertEquals(1,requests)
    }
}
