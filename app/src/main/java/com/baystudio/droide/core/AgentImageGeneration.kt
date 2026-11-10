package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Remote generation reuses the selected provider connection; no model or credentials are stored in media. */
class AgentImageGeneration internal constructor(
    private val transport: Transport = HttpTransport,
    private val storeFactory: (File) -> ChatMediaStore = { ChatMediaStore(it) },
) {
    internal fun interface Transport {
        suspend fun execute(provider: AiProvider, request: Request): Reply
    }

    internal data class Reply(val code: Int, val body: ByteArray, val requestId: String? = null)
    private data class Route(val provider: AiProvider, val baseUrl: String, val openAi: Boolean)
    private data class Options(val model: String, val prompt: String, val quality: String?)
    private class GenerationFailure(message: String) : IllegalStateException(message)

    internal suspend fun generate(
        args: JsonObject,
        c: AgentTools.Ctx,
        permission: suspend (String, String) -> String?,
    ): String {
        val operation = (args["operation"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "generate"
        require(operation in setOf("status", "models", "generate")) { "Unsupported image operation" }
        val api = args["api"]?.let { (it as? JsonPrimitive)?.takeIf { it.isString }?.content }
        require(api == null || api in setOf("openai_images", "gemini")) { "Unsupported image API" }
        val selectedRoute = route(c.llmConfig, c.providerConfiguration, api)
        if (operation == "status") return buildJsonObject {
            put("provider", c.llmConfig.providerId); put("chat_model", c.llmConfig.model)
            put("available", selectedRoute != null); put("account_access_verified", false)
            put("api", selectedRoute?.let { if (it.openAi) "openai_images" else "gemini" })
            put("notice", "Image engine is separate from chat model. Use models to inspect candidate IDs; availability is not account entitlement. For an explicitly documented compatible gateway supply api=openai_images or api=gemini; no provider is switched.")
        }.toString()
        if (operation == "models") return models(requireNotNull(selectedRoute) { "No supported image connection. Check status and the gateway's documented API." }, c.llmConfig.apiKey, permission)
        val options = options(args)
        val owner = c.sessionId?.takeIf { it.isNotBlank() }
            ?: throw GenerationFailure("Image generation requires a durable conversation.")
        require(c.appContext != null) { "Image generation is unavailable in this Agent context." }
        val publish = c.onMediaReady
            ?: throw GenerationFailure("Conversation attachment delivery is unavailable in this Agent context.")
        val route = selectedRoute
            ?: throw GenerationFailure("Image generation is unavailable for this connection. Use an official OpenAI/Gemini API connection, or explicitly select its documented compatible API. The provider was not changed.")
        val endpoint = endpoint(route, options.model)
        permission("edit", "chat_media:$owner:generate_image")?.let { return it }
        permission("webfetch", endpoint)?.let { return it }
        return withContext(Dispatchers.IO) {
            generationWorker.withLock {
                currentCoroutineContext().ensureActive()
                c.onProgress(AgentToolProgress(detail = "Generating image with the existing provider connection"))
                val request = request(route, options, c.llmConfig.apiKey)
                val reply = try {
                    transport.execute(route.provider, request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw GenerationFailure("Image generation request failed. No automatic retry was performed; check the provider connection and retry only when appropriate.")
                }
                currentCoroutineContext().ensureActive()
                if (reply.code !in 200..299) throw GenerationFailure(ProviderFailure.redact(httpFailure(reply.code) + requestIdentity(reply.requestId), listOf(c.llmConfig.apiKey)))
                require(reply.body.size.toLong() <= MAX_RESPONSE_BYTES) { "Image generation response exceeds the twelve MiB limit." }
                val (bytes, type) = parseImage(reply.body, route.openAi)
                val store = storeFactory(c.workDir)
                val extension = when (type) { "image/png" -> "png"; "image/jpeg" -> "jpg"; else -> "webp" }
                val ref = ByteArrayInputStream(bytes).use { store.import(owner, "generated-image.$extension", type, it) }
                try {
                    currentCoroutineContext().ensureActive()
                    // Buffer only this durable copy. A text-only chat model is never given pixels implicitly.
                    publish(ref)
                    "IMAGE_GENERATED " + buildJsonObject {
                        put("id", ref.id)
                        put("path", ref.path)
                        put("name", ref.name)
                        put("media_type", ref.mediaType)
                        put("bytes", ref.bytes)
                        put("expires_at", ref.expiresAt)
                        put("scope", "current_conversation")
                        put("remote_generation", true)
                        put("visual_interpretation_verified", false)
                    }
                } catch (failure: Throwable) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        try { store.release(owner, ref) } catch (cleanup: Exception) {
                            if (cleanup !== failure) failure.addSuppressed(cleanup)
                        }
                    }
                    if (failure is CancellationException) throw failure
                    throw GenerationFailure("Generated image could not be attached to this conversation; its new copy was released and no automatic retry was performed.")
                }
            }
        }
    }

    private suspend fun models(route: Route, secret: String, permission: suspend (String, String) -> String?): String {
        val endpoint = route.baseUrl + if (route.openAi) "/models" else "/models?pageSize=1000"
        permission("webfetch", endpoint)?.let { return it }
        val request = Request.Builder().url(endpoint).get().apply { ProviderTransportV2.applyAuth(this, route.provider, secret) }.build()
        val reply = try { transport.execute(route.provider, ProviderRequestAuthorizer.authorize(request, route.provider, secret)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw GenerationFailure("Image model discovery failed; check the current provider connection. No generation request was sent.") }
        if (reply.code !in 200..299) throw GenerationFailure("Image model discovery: " + ProviderFailure.redact(httpFailure(reply.code) + requestIdentity(reply.requestId), listOf(secret)))
        require(reply.body.size.toLong() <= MAX_RESPONSE_BYTES)
        val candidates = try { ProviderModelCatalog.parse(reply.body.toString(Charsets.UTF_8), route.provider, Json)
            .filter { entry -> val id = entry.id.lowercase(); "image" in id || "dall-e" in id }.take(50) }
        catch (_: Exception) { throw GenerationFailure("Image model catalog could not be read. Consult this provider's documented image models.") }
        return buildJsonObject {
            put("provider", route.provider.id); put("candidate_models", JsonArray(candidates.map { JsonPrimitive(it.id) }))
            put("account_access_verified", false)
            put("notice", "Name-based candidates, not a capability guarantee or exhaustive catalog. Verify image output support and account access. Imagen predict models cannot use Gemini generateContent. No image was generated.")
        }.toString()
    }

    private suspend fun request(route: Route, options: Options, secret: String): Request {
        val payload = if (route.openAi) openAiPayload(options) else googlePayload(options)
        return try {
            val builder = Request.Builder().url(endpoint(route, options.model))
                .header("Content-Type", "application/json").header("Accept", "application/json")
            ProviderTransportV2.applyAuth(builder, route.provider, secret)
            ProviderRequestAuthorizer.authorize(builder.post(payload.toString().toRequestBody(JSON_TYPE)).build(), route.provider, secret)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw GenerationFailure("The existing provider connection could not authorize image generation.")
        }
    }

    private fun options(args: JsonObject): Options {
        fun required(name: String): String = (args[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw GenerationFailure("Image generation requires string model and prompt arguments.")
        val model = try { ProviderModelIdentity.requireCanonical(required("model")) } catch (_: IllegalArgumentException) {
            throw GenerationFailure("Image-generation model must be a canonical, nonempty provider model ID of at most 240 characters.")
        }
        val prompt = required("prompt")
        require(prompt.isNotBlank() && prompt.length <= MAX_PROMPT_CHARS) { "Image-generation prompt must contain 1–4000 characters." }
        val quality = args["quality"]?.let { value ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in QUALITIES }
                ?: throw GenerationFailure("Image-generation quality must be low, medium, high or auto.")
        }
        return Options(model, prompt, quality)
    }

    private fun endpoint(route: Route, model: String): String = if (route.openAi) {
        route.baseUrl + "/images/generations"
    } else {
        require(model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,239}"))) {
            "Gemini image-generation model must be the canonical model name without a models/ prefix or URL."
        }
        checkNotNull(route.baseUrl.toHttpUrlOrNull()).newBuilder()
            .addPathSegment("models").addPathSegment("$model:generateContent").build().toString()
    }

    private fun openAiPayload(options: Options): JsonObject = buildJsonObject {
        put("model", options.model)
        put("prompt", options.prompt)
        put("n", 1)
        // Unknown compatible engines choose their own size; do not impose OpenAI-only options.
        if (options.model.startsWith("gpt-image-") || options.model == "gpt-image" || options.model in setOf("dall-e-2", "dall-e-3"))
            put("size", "1024x1024")
        when {
            options.model.startsWith("gpt-image-") || options.model == "gpt-image" -> {
                put("output_format", "jpeg")
                put("quality", options.quality ?: "auto")
            }
            options.model == "dall-e-3" -> {
                put("response_format", "b64_json")
                options.quality?.takeIf { it != "auto" }?.let { put("quality", if (it == "high") "hd" else "standard") }
            }
            options.model == "dall-e-2" -> {
                require(options.quality == null || options.quality == "auto") { "DALL-E 2 does not support a quality option; omit quality." }
                put("response_format", "b64_json")
            }
            else -> {
                require(options.quality == null || options.quality == "auto") { "Quality is not defined for this image model; omit quality." }
                // Compatible image APIs often return expiring URLs unless base64 is requested.
                put("response_format", "b64_json")
            }
        }
    }

    private fun googlePayload(options: Options): JsonObject {
        require(options.quality == null || options.quality == "auto") { "This Gemini image endpoint does not accept OpenAI quality options; omit quality." }
        return buildJsonObject {
            put("contents", buildJsonArray { add(buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray { add(buildJsonObject { put("text", options.prompt) }) })
            }) })
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add(JsonPrimitive("TEXT")); add(JsonPrimitive("IMAGE")) })
                put("candidateCount", 1)
            })
        }
    }

    private fun parseImage(body: ByteArray, openAi: Boolean): Pair<ByteArray, String> {
        val root = try { Json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject } catch (_: Exception) { null }
            ?: throw GenerationFailure("Provider returned invalid image-generation data; no image was published.")
        val encoded: String
        val declared: String?
        if (openAi) {
            val first = ((root["data"] as? JsonArray)?.firstOrNull() as? JsonObject)
                ?: throw GenerationFailure("Provider returned no generated image; check the image model and request options.")
            encoded = (first["b64_json"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw GenerationFailure("Provider did not return inline image bytes. Remote image URLs are not fetched automatically; use a model that returns base64 image data.")
            declared = null
        } else {
            val content = (((root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("content") as? JsonObject)
            val inline = (content?.get("parts") as? JsonArray)?.mapNotNull { part ->
                (part as? JsonObject)?.let { (it["inlineData"] ?: it["inline_data"]) as? JsonObject }
            }?.firstOrNull()
                ?: throw GenerationFailure("Provider returned no inline image; select an image-generation Gemini model supported by this account.")
            encoded = (inline["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw GenerationFailure("Provider returned no image bytes.")
            declared = ((inline["mimeType"] ?: inline["mime_type"]) as? JsonPrimitive)?.contentOrNull
                ?: throw GenerationFailure("Provider returned no image format.")
        }
        require(encoded.isNotEmpty() && encoded.length.toLong() <= MAX_BASE64_CHARS) { "Generated image exceeds the eight MiB limit or is empty." }
        val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
            throw GenerationFailure("Provider returned invalid base64 image data; no image was published.")
        }
        require(bytes.isNotEmpty() && bytes.size.toLong() <= MAX_IMAGE_BYTES) { "Generated image exceeds the eight MiB limit or is empty." }
        val type = VisionSupport.sniff(bytes)?.takeIf { it in IMAGE_TYPES }
            ?: throw GenerationFailure("Provider returned an unsupported image format; PNG, JPEG or WebP bytes are required.")
        require(declared == null || declared == type) { "Provider image format does not match the returned image bytes." }
        return bytes to type
    }

    private object HttpTransport : Transport {
        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS).callTimeout(360, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            // OkHttp follows 503 + Retry-After: 0 even when connection retry is disabled.
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
            }.build()

        override suspend fun execute(provider: AiProvider, request: Request): Reply {
            val target = NetworkSecurity.validateProviderTarget(provider, request.url.toString())
            currentCoroutineContext().ensureActive()
            return client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(request)
                .withCancellableResponse { response ->
                    if (!response.isSuccessful) Reply(response.code, ByteArray(0), response.header("x-request-id")) else {
                        val source = response.body?.source()
                            ?: throw GenerationFailure("Provider returned an empty image-generation response.")
                        source.request(MAX_RESPONSE_BYTES + 1L)
                        require(source.buffer.size <= MAX_RESPONSE_BYTES) { "Image generation response exceeds the twelve MiB limit." }
                        currentCoroutineContext().ensureActive()
                        Reply(response.code, source.buffer.readByteArray())
                    }
                }
        }
    }

    companion object {
        internal const val MAX_PROMPT_CHARS = 4_000
        internal const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
        internal const val MAX_RESPONSE_BYTES = 12L * 1024 * 1024
        private const val MAX_BASE64_CHARS = ((MAX_IMAGE_BYTES + 2L) / 3L) * 4L
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp")
        private val QUALITIES = setOf("low", "medium", "high", "auto")
        private val generationWorker = Mutex()

        fun supports(config: AgentConfig): Boolean = route(config) != null

        private fun route(config: AgentConfig, snapshot: ProviderRegistry.ConfiguredSnapshot? = null, api: String? = null): Route? {
            if (config.apiKey.isBlank()) return null
            val provider = ProviderRegistry.findById(config.providerId, snapshot) ?: return null
            val base = config.baseUrl.trimEnd('/')

            val googleBases = setOf("https://generativelanguage.googleapis.com/v1", "https://generativelanguage.googleapis.com/v1beta")
            val officialGoogleAlias = provider.authScheme == ProviderAuthScheme.GOOGLE_API_KEY &&
                provider.wireProtocol == ProviderWireProtocol.GOOGLE_GENERATE_CONTENT &&
                provider.baseUrl.trimEnd('/') in googleBases && base in googleBases
            if (!officialGoogleAlias && runCatching { ProviderRuntimeGuard.requireBoundProvider(config.providerId, base, snapshot) }.isFailure) return null
            return when {
                api == "openai_images" && provider.authScheme == ProviderAuthScheme.BEARER && provider.wireProtocol in
                    setOf(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, ProviderWireProtocol.OPENAI_RESPONSES) -> Route(provider, base, true)
                api == "gemini" && provider.wireProtocol == ProviderWireProtocol.GOOGLE_GENERATE_CONTENT &&
                    provider.authScheme == ProviderAuthScheme.GOOGLE_API_KEY -> Route(provider, base, false)
                api != null -> null
                provider.id == "openai" && provider.authScheme == ProviderAuthScheme.BEARER && base == "https://api.openai.com/v1" ->
                    Route(provider, base, true)
                provider.wireProtocol == ProviderWireProtocol.GOOGLE_GENERATE_CONTENT && provider.authScheme == ProviderAuthScheme.GOOGLE_API_KEY &&
                    base in setOf("https://generativelanguage.googleapis.com/v1beta", "https://generativelanguage.googleapis.com/v1") ->
                    Route(provider, base, false)
                else -> null
            }
        }

        private fun requestIdentity(value: String?): String = value?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) }
            ?.let { " Provider request ID: $it." }.orEmpty()

        private fun httpFailure(code: Int): String = when (code) {
            in 300..399 -> "Image provider requested a redirect; it was not followed and no image was published."
            401, 403 -> "The existing provider connection cannot authorize this image model. Check account access; no image was published."
            404 -> "Image-generation model or endpoint is unavailable. Select a current image-generation model supported by this provider/account."
            500, 502, 504 -> "Image provider service failed (HTTP $code). Completion is unknown; do not repeat a paid generation automatically. Check provider status and image-model access. No automatic retry was performed."
            429, 503 -> "Image provider is temporarily unavailable or rate-limited (HTTP $code). No automatic retry was performed."
            else -> "Image generation failed (HTTP $code). Check the image model, supported options and provider account; no automatic retry was performed."
        }
    }
}
