package com.baystudio.droide.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/**
 * Independent, memory-only HTTPS session for the official public form. No browser, account/client
 * reuse, global cookie jar, project diagnostics, logging, credentials, cache or POST retries.
 * The optional interceptor is an in-process JVM fixture boundary; production uses the default.
 */
internal class OfficialBugReportTransport(
    private val dispatchGuard: (block: () -> Unit) -> Unit = { it() },
    testInterceptor: Interceptor? = null,
) : BugReportTransport {
    private data class Session(val token: String, val cookie: Cookie)
    private val lock = Any()
    private var session: Session? = null
    private var generation = 0L
    private var closed = false
    internal val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .apply { if (testInterceptor != null) addInterceptor(testInterceptor) }
        .build()

    override suspend fun prepare(): BugReportPreparation {
        val expectedGeneration = synchronized(lock) {
            if (closed) return BugReportPreparation.CLOSED
            if (session?.cookie?.expiresAt?.let { it > System.currentTimeMillis() } == true) {
                return BugReportPreparation.READY
            }
            session = null
            generation
        }
        try {
            currentCoroutineContext().ensureActive()
            val request = Request.Builder().url(BugReportPolicy.CSRF_URL)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-store")
                .get().build()
            val prepared = withContext(Dispatchers.IO) {
                GuardedCall(client.newCall(request), dispatchGuard) {
                    synchronized(lock) {
                        check(!closed && generation == expectedGeneration) { "Report session retired" }
                    }
                }.withCancellableResponse { response ->
                    requireExactResponse(response, BugReportPolicy.CSRF_URL)
                    if (response.code !in 200..299) return@withCancellableResponse null
                    val json = readJson(response) ?: return@withCancellableResponse null
                    if (strictBoolean(json["ok"]) != true) return@withCancellableResponse null
                    val token = string(json["token"])?.takeIf { TOKEN.matches(it) }
                        ?: return@withCancellableResponse null
                    val cookies = Cookie.parseAll(request.url, response.headers).filter { cookie ->
                        cookie.name == COOKIE_NAME && cookie.domain == request.url.host && cookie.hostOnly &&
                            cookie.path == "/" && cookie.secure && cookie.httpOnly &&
                            cookie.expiresAt > System.currentTimeMillis() && COOKIE_VALUE.matches(cookie.value)
                    }
                    // Ambiguous duplicate session cookies fail closed instead of choosing an identity.
                    if (cookies.size != 1) return@withCancellableResponse null
                    Session(token, cookies.single())
                }
            }
            currentCoroutineContext().ensureActive()
            return synchronized(lock) {
                if (closed) BugReportPreparation.CLOSED
                else if (generation != expectedGeneration || prepared == null) BugReportPreparation.FAILED
                else { session = prepared; BugReportPreparation.READY }
            }
        } catch (cancelled: CancellationException) {
            clearSession()
            throw cancelled
        } catch (_: Exception) {
            clearSession()
            return BugReportPreparation.FAILED
        }
    }

    override suspend fun submit(snapshot: BugReportDraft, onPostStarted: () -> Unit): BugReportOutcome {
        val errors = BugReportPolicy.validate(snapshot)
        if (errors.isNotEmpty()) return BugReportOutcome.Invalid(errors)
        if (prepare() != BugReportPreparation.READY) return BugReportOutcome.PreparationFailed
        val current = synchronized(lock) { session } ?: return BugReportOutcome.PreparationFailed
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            BugReportPolicy.formFields(snapshot).forEach { (name, value) -> addFormDataPart(name, value) }
        }.build()
        val request = Request.Builder().url(BugReportPolicy.SUBMIT_URL)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            // The official form validates its origin in addition to the session/CSRF pair.
            // Native requests must supply the same fixed form origin; redirects stay disabled.
            .header("Origin", BugReportPolicy.ORIGIN)
            .header("Referer", BugReportPolicy.FORM_URL)
            .header("X-Droide-CSRF", current.token)
            .header("Cookie", "${current.cookie.name}=${current.cookie.value}")
            .post(SingleSendBody(multipart))
            .build()
        var postStarted = false
        try {
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (closed || session !== current) return BugReportOutcome.PreparationFailed
            }
            // One-shot body prevents OkHttp's 503/Retry-After follow-up, beyond retryOnFailure.
            val result = withContext(Dispatchers.IO) {
                GuardedCall(client.newCall(request), dispatchGuard) {
                    synchronized(lock) {
                        check(!closed && session === current) { "Report session retired" }
                    }
                    // Mark and enqueue within the caller's admission fence. A rejected guard cannot POST.
                    onPostStarted()
                    postStarted = true
                }.withCancellableResponse { response ->
                    requireExactResponse(response, BugReportPolicy.SUBMIT_URL)
                    decodeSubmission(response.code, readJson(response))
                }
            }
            if (result !is BugReportOutcome.Rejected || result.error != BugReportError.TOO_FAST) clearSession()
            return result
        } catch (cancelled: CancellationException) {
            clearSession()
            throw cancelled
        } catch (_: Exception) {
            clearSession()
            return if (postStarted) BugReportOutcome.UnknownSubmission else BugReportOutcome.PreparationFailed
        }
    }

    override fun clearSession() {
        synchronized(lock) { generation++; session = null }
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    override fun close() {
        synchronized(lock) { closed = true }
        clearSession()
        client.dispatcher.executorService.shutdown()
    }

    private fun requireExactResponse(response: Response, expected: String) {
        if (response.request.url.toString() != expected || !response.request.url.isHttps) {
            throw IOException("Unexpected report response origin")
        }
    }

    private fun readJson(response: Response): JsonObject? {
        val body = response.body ?: return null
        val media = body.contentType()
        if (media?.type != "application" || media.subtype != "json") return null
        if (media.charset(Charsets.UTF_8) != Charsets.UTF_8) return null
        if (body.contentLength() > BugReportPolicy.MAX_RESPONSE_BYTES) throw IOException("Report response too large")
        val source = body.source()
        val buffer = okio.Buffer()
        var size = 0L
        while (true) {
            val count = source.read(buffer, minOf(4096L, BugReportPolicy.MAX_RESPONSE_BYTES + 1L - size))
            if (count == -1L) break
            size += count
            if (size > BugReportPolicy.MAX_RESPONSE_BYTES) throw IOException("Report response too large")
        }
        return try {
            val utf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buffer.readByteArray())).toString()
            if (!isFlatUniqueObject(utf8)) return null
            Json.parseToJsonElement(utf8) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    /** Bounded, nonrecursive structural preflight: no nested values or duplicate decoded keys. */
    private fun isFlatUniqueObject(text: String): Boolean {
        var index = 0
        fun whitespace() { while (index < text.length && text[index] in " \t\r\n") index++ }
        fun quotedEnd(): Int {
            if (index >= text.length || text[index] != '"') return -1
            var at = index + 1
            while (at < text.length) {
                when (text[at]) {
                    '"' -> return at + 1
                    '\\' -> at++ // Full JSON parser validates escapes; this only finds the boundary.
                }
                at++
            }
            return -1
        }
        whitespace()
        if (index >= text.length || text[index++] != '{') return false
        whitespace()
        val keys = HashSet<String>()
        if (index < text.length && text[index] == '}') {
            index++; whitespace(); return index == text.length
        }
        while (index < text.length) {
            val keyEnd = quotedEnd()
            if (keyEnd < 0) return false
            val key = (Json.parseToJsonElement(text.substring(index, keyEnd)) as? JsonPrimitive)
                ?.takeIf { it.isString }?.content ?: return false
            if (!keys.add(key)) return false
            index = keyEnd; whitespace()
            if (index >= text.length || text[index++] != ':') return false
            whitespace()
            if (index >= text.length) return false
            if (text[index] == '"') {
                val valueEnd = quotedEnd()
                if (valueEnd < 0) return false
                index = valueEnd
            } else {
                val start = index
                while (index < text.length && text[index] != ',' && text[index] != '}') {
                    if (text[index] in "{[\"") return false
                    index++
                }
                if (text.substring(start, index).trim().isEmpty()) return false
            }
            whitespace()
            if (index >= text.length) return false
            when (text[index++]) {
                '}' -> { whitespace(); return index == text.length }
                ',' -> whitespace()
                else -> return false
            }
        }
        return false
    }

    internal companion object {
        private const val COOKIE_NAME = "droide_support_v2"
        private val TOKEN = Regex("[0-9a-fA-F]{64}")
        private val COOKIE_VALUE = Regex("[A-Za-z0-9,-]{1,256}")
        private val REPORT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
        private fun strictBoolean(value: kotlinx.serialization.json.JsonElement?): Boolean? =
            (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        private fun string(value: kotlinx.serialization.json.JsonElement?): String? =
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content

        internal fun decodeSubmission(status: Int, json: JsonObject?): BugReportOutcome {
            if (json == null || status in 300..399) return BugReportOutcome.UnknownSubmission
            return when (strictBoolean(json["ok"])) {
                true -> if (status in 200..299) {
                    BugReportOutcome.Accepted(string(json["report_id"])?.takeIf { REPORT_ID.matches(it) })
                } else BugReportOutcome.UnknownSubmission
                false -> BugReportError.entries.firstOrNull { it.code == string(json["error"]) }
                    ?.let { BugReportOutcome.Rejected(it) } ?: BugReportOutcome.UnknownSubmission
                null -> BugReportOutcome.UnknownSubmission
            }
        }
    }

    private class GuardedCall(
        private val delegate: Call,
        private val guard: (block: () -> Unit) -> Unit,
        private val onDispatch: () -> Unit,
    ) : Call by delegate {
        override fun enqueue(responseCallback: Callback) {
            guard { onDispatch(); delegate.enqueue(responseCallback) }
        }
        override fun clone(): Call = GuardedCall(delegate.clone(), guard, onDispatch)
    }

    /** Never replay report bytes for retries, authentication challenges or follow-up responses. */
    private class SingleSendBody(private val body: RequestBody) : RequestBody() {
        override fun contentType(): MediaType? = body.contentType()
        override fun contentLength(): Long = body.contentLength()
        override fun isOneShot(): Boolean = true
        override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
    }
}
