package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal class AgentAudioTranscription(private val transport: Transport = HttpTransport) {
    internal fun interface Transport { suspend fun execute(provider: AiProvider, request: Request): Pair<Int, ByteArray> }
    suspend fun execute(args: JsonObject, c: AgentTools.Ctx, permission: suspend (String, String) -> String?): String = withContext(Dispatchers.IO) {
        require(c.mode == AgentMode.BUILD) { "Speech transcription requires BUILD mode" }
        val path = string(args, "path")
        require(path.length in 1..500 && !java.io.File(path).isAbsolute &&
            (path.startsWith(".droide/chat-media/blobs/") || AgentWorkspacePathPolicy.visible(path))) { "Use a visible project audio file or owned attachment" }
        val destination = string(args, "destination")
        ProjectCopyEvidence.validateDestination(destination)
        require(AgentWorkspacePathPolicy.visible(destination) && destination.substringAfterLast('.').lowercase() in setOf("txt", "md")) { "Choose a new visible .txt or .md transcript" }
        ExplorerFilePolicy.requireUserTarget(c.files.root, destination)
        require(!c.files.exists(destination) && c.documentAuthority?.snapshot(destination)?.dirty != true) { "Transcript destination already exists or has unsaved changes" }
        val model = ProviderModelIdentity.requireCanonical(string(args, "model"))
        val provider = ProviderRuntimeGuard.requireBoundProvider(c.llmConfig.providerId, c.llmConfig.baseUrl, c.providerConfiguration)
        val api = args["api"]?.let { string(args, "api") }
        require(api == null || api == "openai_audio") { "Unsupported speech API" }
        val compatible = api == "openai_audio"
        require(provider.authScheme == ProviderAuthScheme.BEARER && provider.wireProtocol in setOf(
            ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, ProviderWireProtocol.OPENAI_RESPONSES) &&
            (provider.id == "openai" || compatible) && c.llmConfig.apiKey.isNotBlank()) {
            "Transcription requires an OpenAI API connection or an explicitly documented compatible api=openai_audio connection; the provider was not switched"
        }
        val language = args["language"]?.let { string(args, "language").also { value -> require(value.matches(Regex("[a-z]{2,3}"))) { "Use an ISO language code" } } }
        val endpoint = c.llmConfig.baseUrl.trimEnd('/') + "/audio/transcriptions"
        permission("read", path)?.let { return@withContext it }
        permission("edit", destination)?.let { return@withContext it }
        permission("webfetch", endpoint)?.let { return@withContext it }
        check(c.mediaRequestCurrent()) { "Transcription request is no longer current" }
        val source = AgentMediaTools.source(c.workDir, path, c.sessionId.orEmpty())
        if (!path.startsWith(".droide/chat-media/blobs/")) require(AgentWorkspacePathPolicy.visible(source.canonicalFile.relativeTo(c.workDir.canonicalFile).invariantSeparatorsPath)) { "Audio resolves into internal state" }
        fun observation() = java.nio.file.Files.readAttributes(source.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS).let {
            require(it.isRegularFile); listOf(it.fileKey(), it.size(), it.lastModifiedTime(), it.creationTime())
        }
        val before = observation()
        val type = TYPES[source.extension.lowercase()] ?: error("Unsupported speech upload format")
        require(source.length() in 1..MAX_UPLOAD.toLong()) { "Speech upload must be <=20 MiB; trim/convert it locally first" }
        val bytes = AgentMediaTools.noFollowInput(source).use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= MAX_UPLOAD) { "Speech upload exceeds 20 MiB" }; output.write(buffer, 0, count)
            }; output.toByteArray()
        }
        require(bytes.isNotEmpty() && observation() == before) { "Audio changed during capture; inspect it again before uploading" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model).addFormDataPart("response_format", "json")
            .addFormDataPart("file", "audio.${source.extension.lowercase()}", bytes.toRequestBody(type.toMediaType()))
            .apply { language?.let { addFormDataPart("language", it) } }.build()
        val request = Request.Builder().url(endpoint).post(body).apply { ProviderTransportV2.applyAuth(this, provider, c.llmConfig.apiKey) }.build()
        check(c.mediaRequestCurrent())
        c.onProgress(AgentToolProgress(detail = "Transcribing audio with the configured provider"))
        val (code, response) = try { transport.execute(provider, ProviderRequestAuthorizer.authorize(request, provider, c.llmConfig.apiKey)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error("Speech provider request failed; completion is unknown. No automatic retry was performed.") }
        require(code in 200..299) { "Speech provider failed (HTTP $code). Check transcription-model access and provider status. No automatic retry was performed." }
        require(response.size <= MAX_RESPONSE) { "Transcript response exceeds two MiB" }
        val text = runCatching { (Json.parseToJsonElement(response.toString(Charsets.UTF_8)) as? JsonObject)?.get("text")
            ?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content }.getOrNull()
        require(text != null && text.isNotBlank() && text.length <= 200_000) { "Provider returned no bounded transcript; no transcript file was created" }
        currentCoroutineContext().ensureActive(); check(c.mediaRequestCurrent())
        try { c.files.writeTextIfUnchanged(destination, text, DiskFileVersion.fromText(null)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw IllegalStateException("Transcript publication did not settle; inspect $destination before retrying. Do not repeat the paid speech request automatically.", failure) }
        buildJsonObject {
            put("ok", true); put("path", destination); put("sha256", DiskFileVersion.fromText(text).sha256)
            put("characters", text.length); put("remote_transcription", true); put("provider", provider.id); put("requested_model", model)
            put("notice", "Transcript saved. Read it with read_file; verify names, numbers and wording. This is speech recognition, not verified speaker attribution or audio-quality analysis.")
        }.toString()
    }
    private object HttpTransport : Transport {
        private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS).callTimeout(240, TimeUnit.SECONDS).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
            }.build()
        override suspend fun execute(provider: AiProvider, request: Request): Pair<Int, ByteArray> {
            val target = NetworkSecurity.validateProviderTarget(provider, request.url.toString())
            return client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(request).withCancellableResponse { response ->
                if (!response.isSuccessful) response.code to ByteArray(0) else {
                    val source = requireNotNull(response.body?.source()) { "Empty speech response" }
                    source.request(MAX_RESPONSE + 1L); require(source.buffer.size <= MAX_RESPONSE)
                    response.code to source.buffer.readByteArray()
                }
            }
        }
    }
    companion object {
        private const val MAX_UPLOAD = 20 * 1024 * 1024
        private const val MAX_RESPONSE = 2 * 1024 * 1024
        private val TYPES = mapOf("mp3" to "audio/mpeg", "wav" to "audio/wav", "m4a" to "audio/mp4", "flac" to "audio/flac", "ogg" to "audio/ogg", "webm" to "audio/webm")
        private fun string(args: JsonObject, key: String): String = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("$key must be a string")
    }
}
