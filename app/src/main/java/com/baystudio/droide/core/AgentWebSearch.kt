package com.baystudio.droide.core

import java.net.IDN
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

internal data class AgentWebSearchHit(val title: String, val url: String, val snippet: String)
internal data class AgentWebSearchAttempt(val engine: String, val status: String, val detail: String)
internal data class AgentWebSearchResult(val engine: String, val hits: List<AgentWebSearchHit>,
    val attempts: List<AgentWebSearchAttempt>, val fallbacks: List<String>, val truncated: Boolean)

internal class AgentWebSearch(private val transport: AgentWebTransport, private val clock: Clock = Clock.systemUTC(),
    private val attemptTimeoutMs: Long = 12_000, private val totalTimeoutMs: Long = 60_000,
    private val nanoTime: () -> Long = System::nanoTime) {
    private val cooldowns = ConcurrentHashMap<String, Long>()
    data class Request(val query: String, val engine: String = "auto", val maxResults: Int = 10,
        val domains: List<String> = emptyList(), val recencyDays: Int? = null) {
        init {
            require(query.isNotBlank() && query.length <= 1_000) { "Search query must contain 1..1000 characters" }
            require(engine in listOf("auto", "duckduckgo", "bing", "brave")) { "Unknown search engine" }
            require(maxResults in 1..20) { "max_results must be between 1 and 20" }
            require(domains.size <= 8) { "At most eight search domains" }
            require(recencyDays == null || recencyDays in 1..3_650) { "recency_days must be between 1 and 3650" }
            domains.forEach(::normalizeDomain)
        }
    }

    suspend fun search(request: Request, authorizeRedirect: suspend (String) -> Unit,
        progress: (String) -> Unit = {}): AgentWebSearchResult {
        val attempts = mutableListOf<AgentWebSearchAttempt>()
        val engines = if (request.engine == "auto") listOf("duckduckgo", "bing", "brave") else listOf(request.engine)
        val fallbacks = engines.map { browserEndpoint(it, request.query) }
        return try { withAgentWebDeadline(totalTimeoutMs, nanoTime) { totalDeadline ->
        val query = buildString {
            append(request.query.trim())
            if (request.domains.isNotEmpty()) append(request.domains.joinToString(" OR site:", " (site:", ")") { normalizeDomain(it) })
            request.recencyDays?.let { append(" after:").append(LocalDate.now(clock).minusDays(it.toLong())) }
        }
        var empty: AgentWebSearchResult? = null
        for (engine in engines) {
            if ((cooldowns[engine] ?: 0L) > clock.millis()) {
                attempts += AgentWebSearchAttempt(engine, "cooldown", "Search endpoint recently blocked or rate limited requests; trying another engine")
                continue
            }
            for (url in endpoints(engine, query)) {
            progress("Searching the web · $engine · ${url.toHttpUrl().host}")
            try {
                val response = withAgentWebDeadline(attemptTimeoutMs, nanoTime) { attemptDeadline -> transport.getText(url) { target ->
                    // Search permission authorizes only these engine domains; unrelated redirects need fetch permission.
                    val host = target.toHttpUrl().host
                    if (!searchHost(engine, host)) {
                        // Transport pauses its own budget around this callback. Pause both search budgets too,
                        // including time spent waiting for another request's serialized permission prompt.
                        totalDeadline.withoutTime { attemptDeadline.withoutTime { authorizeRedirect(target) } }
                    }
                } }
                if (response.status in setOf(403, 429)) {
                    cooldowns.merge(engine, clock.millis() + cooldownMs(response.retryAfter), ::maxOf)
                    throw AgentWebSearchBlocked("HTTP ${response.status}; public search requires a wait or browser verification")
                }
                require(response.status in 200..299) { "HTTP ${response.status}" }
                val parsed = AgentWebWork.parse { parse(engine, response) }
                val filtered = parsed.filter { hit -> request.domains.isEmpty() || request.domains.any { matchesDomain(hit.url, it) } }
                    .distinctBy { it.url }.take(request.maxResults)
                attempts += AgentWebSearchAttempt(engine, if (filtered.isEmpty()) "empty" else "succeeded",
                    if (filtered.isEmpty()) "No results match the requested domain filter" else "${filtered.size} source links parsed")
                val result = AgentWebSearchResult(engine, filtered, attempts.toList(), fallbacks, response.truncated)
                if (filtered.isNotEmpty()) return@withAgentWebDeadline result
                empty = result
                break
            } catch (timeout: AgentWebDeadlineExceeded) {
                currentCoroutineContext().ensureActive()
                attempts += AgentWebSearchAttempt(engine, "failed", "Search endpoint timed out; continuing with a bounded fallback")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: AgentWebPermissionDenied) { throw failure }
            catch (blocked: AgentWebSearchBlocked) {
                cooldowns.merge(engine, clock.millis() + 60_000, ::maxOf)
                attempts += AgentWebSearchAttempt(engine, "failed", blocked.message.orEmpty().take(300))
                break
            }
            catch (failure: Exception) {
                attempts += AgentWebSearchAttempt(engine, "failed", failure.message.orEmpty().take(300))
            }
            }
        }
        empty?.copy(attempts = attempts.toList()) ?: throw AgentWebSearchUnavailable(attempts, fallbacks)
    } } catch (timeout: AgentWebDeadlineExceeded) {
        currentCoroutineContext().ensureActive()
        throw AgentWebSearchUnavailable(attempts.toList() + AgentWebSearchAttempt("auto", "failed", "Search time budget reached"), fallbacks)
    }
    }

    private fun cooldownMs(raw: String?): Long {
        val seconds = raw?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        if (seconds != null) return seconds.coerceIn(60L, (Long.MAX_VALUE - clock.millis()) / 1_000) * 1_000
        val date = raw?.let { runCatching { java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
        return date?.let { (it - clock.millis()).coerceAtLeast(60_000L) } ?: 60_000
    }

    companion object {
        fun normalizeDomain(raw: String): String {
            require(raw.isNotBlank() && raw.length <= 253 && !raw.contains(Regex("[/:@?#\\s]"))) { "domains must contain hostnames, not URLs or query syntax" }
            val domain = IDN.toASCII(raw.trimEnd('.').lowercase())
            require(domain.contains('.') && domain.split('.').all { it.isNotEmpty() && it.length <= 63 &&
                it.first() != '-' && it.last() != '-' && it.all { c -> c.isLetterOrDigit() || c == '-' } }) { "Invalid search domain" }
            AgentWebUrl.normalize("https://$domain/")
            return domain
        }

        fun matchesDomain(url: String, domain: String): Boolean {
            val host = url.toHttpUrl().host
            val expected = normalizeDomain(domain)
            return host == expected || host.endsWith(".$expected")
        }

        fun endpoint(engine: String, query: String): String = when (engine) {
            "duckduckgo" -> "https://html.duckduckgo.com/html/".toHttpUrl().newBuilder().addQueryParameter("q", query).build().toString()
            "bing" -> "https://www.bing.com/search".toHttpUrl().newBuilder().addQueryParameter("format", "rss").addQueryParameter("q", query).build().toString()
            "brave" -> "https://search.brave.com/search".toHttpUrl().newBuilder().addQueryParameter("q", query).build().toString()
            else -> error("Unknown search engine")
        }

        private fun endpoints(engine: String, query: String): List<String> = listOf(endpoint(engine, query)) + when (engine) {
            "duckduckgo" -> listOf("https://lite.duckduckgo.com/lite/".toHttpUrl().newBuilder().addQueryParameter("q", query).build().toString())
            "bing" -> listOf(browserEndpoint(engine, query))
            else -> emptyList()
        }

        private fun browserEndpoint(engine: String, query: String): String = when (engine) {
            "bing" -> "https://www.bing.com/search".toHttpUrl().newBuilder().addQueryParameter("q", query).build().toString()
            else -> endpoint(engine, query)
        }

        private fun searchHost(engine: String, host: String): Boolean = when (engine) {
            "duckduckgo" -> host == "duckduckgo.com" || host.endsWith(".duckduckgo.com")
            "bing" -> host == "bing.com" || host.endsWith(".bing.com")
            "brave" -> host == "search.brave.com"
            else -> false
        }

        fun parse(engine: String, response: AgentWebResponse): List<AgentWebSearchHit> {
            val text = response.text()
            val rss = engine == "bing" && Regex("(?i)<rss(?:\\s|>)").containsMatchIn(text.take(4_096))
            val doc = Jsoup.parse(text, response.finalUrl, if (rss) Parser.xmlParser() else Parser.htmlParser())
            val visible = doc.text().lowercase()
            if (doc.select("form[action*=challenge],iframe[src*=captcha],.g-recaptcha,#challenge-form").isNotEmpty() ||
                (doc.select("a.result__a,a.result-link,.b_algo,.snippet,rss > channel").isEmpty() &&
                    listOf("captcha", "human verification", "verify you are human", "unusual traffic").any(visible::contains))) {
                throw AgentWebSearchBlocked("Public search requires human verification; use browser or an approved search service")
            }
            val hits = when (engine) {
                "bing" -> {
                    if (rss) require(doc.selectFirst("rss > channel") != null) { "Search RSS has no result channel" }
                    if (rss) doc.select("channel > item").take(100).mapNotNull { item ->
                        val url = runCatching { AgentWebUrl.normalize(item.selectFirst("link")?.text().orEmpty(), true) }.getOrNull() ?: return@mapNotNull null
                        AgentWebSearchHit(item.selectFirst("title")?.text().orEmpty().take(256), url,
                            Jsoup.parse(item.selectFirst("description")?.text().orEmpty()).text().take(600))
                    } else doc.select("li.b_algo").take(100).mapNotNull { item ->
                        if (item.hasClass("b_ad")) return@mapNotNull null
                        val anchor = item.selectFirst("h2 a[href]") ?: return@mapNotNull null
                        val raw = AgentWebUrl.resolve(response.finalUrl, anchor.attr("href")) ?: return@mapNotNull null
                        val parsed = raw.toHttpUrl()
                        val source = if (searchHost(engine, parsed.host)) {
                            val encoded = parsed.queryParameter("u")?.takeIf { it.startsWith("a1") } ?: return@mapNotNull null
                            runCatching { java.util.Base64.getUrlDecoder().decode(encoded.drop(2)).toString(Charsets.UTF_8) }.getOrNull()
                                ?: return@mapNotNull null
                        } else raw
                        val url = runCatching { AgentWebUrl.normalize(source, true) }.getOrNull() ?: return@mapNotNull null
                        AgentWebSearchHit(anchor.text().take(256), url, item.selectFirst(".b_caption p,p")?.text().orEmpty().take(600))
                    }
                }
                "duckduckgo" -> doc.select("a.result__a,a.result-link").take(100).mapNotNull { a ->
                    val raw = AgentWebUrl.resolve(response.finalUrl, a.attr("href")) ?: return@mapNotNull null
                    val parsed = raw.toHttpUrl()
                    val source = if (searchHost(engine, parsed.host)) parsed.queryParameter("uddg") ?: return@mapNotNull null else raw
                    val url = runCatching { AgentWebUrl.normalize(source, true) }.getOrNull() ?: return@mapNotNull null
                    val container = a.closest(".result") ?: a.closest("tr") ?: a.parent()
                    val snippet = container?.selectFirst(".result__snippet,.result-snippet")?.text().orEmpty()
                    AgentWebSearchHit(a.text().take(256), url, snippet.take(600))
                }
                "brave" -> doc.select(".snippet").take(100).mapNotNull { container ->
                    if (container.attr("data-type").lowercase() in setOf("ad", "ads", "sponsored")) return@mapNotNull null
                    val title = container.selectFirst(".title,h3,h2") ?: return@mapNotNull null
                    val a = if (title.tagName() == "a") title else title.closest("a") ?: container.selectFirst("a[href]") ?: return@mapNotNull null
                    val url = AgentWebUrl.resolve(response.finalUrl, a.attr("href")) ?: return@mapNotNull null
                    if (url.toHttpUrl().host == "search.brave.com") return@mapNotNull null
                    AgentWebSearchHit(title.text().take(256), url,
                        container.selectFirst(".snippet-description,.snippet-content,.description,.generic-snippet,.content")?.text().orEmpty().take(600))
                }
                else -> error("Unknown search engine")
            }.filter { it.title.isNotBlank() && !searchLandingPage(it.url) }
            if (hits.isEmpty()) {
                val visible = doc.body().text().lowercase()
                val genuineEmpty = doc.select(".no-results,.no-results__message").isNotEmpty() ||
                    visible.contains("no results found") || visible.contains("no results for") || (rss && doc.select("channel > item").isEmpty())
                require(genuineEmpty) { "Search was blocked, requires JavaScript/human verification, or returned an unrecognized layout. Use the visible browser fallback." }
            }
            return hits
        }

        private fun searchLandingPage(raw: String): Boolean {
            val url = raw.toHttpUrl()
            val engineHost = listOf("google.com", "bing.com", "duckduckgo.com", "search.brave.com").any { url.host == it || url.host.endsWith(".$it") }
            return engineHost && url.encodedPath in setOf("/", "/search", "/search/", "/html/", "/lite/", "/challenge", "/sorry/")
        }
    }
}

internal class AgentWebSearchBlocked(message: String) : Exception(message)

internal class AgentWebPermissionDenied(val result: String) : Exception(result)
internal class AgentWebSearchUnavailable(val attempts: List<AgentWebSearchAttempt>, val fallbacks: List<String>) :
    Exception("Public search endpoints are unavailable; try an adequate source URL with web(open) or an approved search service. Use browser only when required content needs rendering or interaction.")
