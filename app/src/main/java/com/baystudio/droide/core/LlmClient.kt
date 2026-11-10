package com.baystudio.droide.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Call
import okio.BufferedSource
import okio.BufferedSink

class LlmClient(private val providerConfiguration: ProviderRegistry.ConfiguredSnapshot? = null) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(AgentTimeoutPolicy.CONNECT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(AgentTimeoutPolicy.RESPONSE_IDLE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(AgentTimeoutPolicy.RESPONSE_IDLE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        // Generation POSTs must not be silently replayed after an ambiguous disconnect. Explicit
        // transient HTTP responses are handled only by ProviderRetryPolicy.
        .retryOnConnectionFailure(false)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code != 503) response else {
                // OkHttp 4.12 follows 503 + Retry-After: 0 even with connection retries disabled, and parses
                // large numeric headers as Int.
                checkNotNull(chain.request().tag(PolicyRetryHeader::class.java)).retryAfter = response.header("Retry-After")
                response.newBuilder().removeHeader("Retry-After").build()
            }
        }
        .followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }

    private class PolicyRetryHeader {
        @Volatile var retryAfter: String? = null
    }

    private fun Request.withPolicyRetryHeader(): Request = newBuilder()
        .tag(PolicyRetryHeader::class.java, PolicyRetryHeader()).build()

    private fun retryAfter(response: Response): String? {
        if (response.code != 503) return response.header("Retry-After")
        val original = response.request.tag(PolicyRetryHeader::class.java)
        return if (original != null) original.retryAfter else response.header("Retry-After")
    }

    private fun policyOwnedRequestBody(body: String): RequestBody {
        val delegate = body.toRequestBody("application/json".toMediaType())
        return object : RequestBody() {
            override fun contentType() = delegate.contentType()
            override fun contentLength() = delegate.contentLength()
            override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
            // The immutable body may be written by a new explicitly admitted policy attempt, but never by
            // OkHttp's automatic HTTP follow-ups in the same call.
            override fun isOneShot() = true
        }
    }

    private suspend fun <T> Call.withProviderResponse(request: Request, secret: String, providerId: String = "", block: suspend (Response, ProviderFailureProgress) -> T): T {
        var httpStatus: Int? = null
        val progress = ProviderFailureProgress(providerId, json)
        val secrets = ProviderFailure.requestSecrets(request::header) + secret
        return try {
            withCancellableResponse { response ->
                httpStatus = response.code
                block(response, progress)
            }
        } catch (failure: ProviderErrorPayloadException) {
            if (progress.isCallbackFailure(failure)) throw failure
            throw ProviderStreamFailure.fromPayload(failure, secrets, httpStatus, progress.snapshot(secrets))
        } catch (failure: Exception) {
            // Keep cancellation and HTTP rejection identity for the existing retry and context-overflow
            // gates. Parser/unknown-event errors can include raw input.
            if (failure is CancellationException || failure is LlmHttpException || progress.isCallbackFailure(failure)) throw failure
            // kotlinx JSON diagnostics may already have clipped the input and a credential into an
            // unmatchable prefix. Never relay their raw context.
            if (failure is SerializationException) throw ProviderStreamFailure.fromUnsafeMessage(
                "Provider returned malformed JSON.", emptyList(), httpStatus, progress.snapshot(secrets),
            )
            val message = failure.message ?: "Provider response was interrupted."
            throw ProviderStreamFailure.fromUnsafeMessage(message, secrets, httpStatus, progress.snapshot(secrets))
        }
    }

    suspend fun chatCompletions(baseUrl: String, apiKey: String, body: String): String =
        withContext(Dispatchers.IO) {
            val request = ProviderModelRequestIdentity.canonicalize(body, json)
            val prepared = ProviderRequestBudget.prepare(json.parseToJsonElement(request.body).jsonObject, "", request.modelId)
            val requestBody = ProviderToolSchema.openAiRequest(prepared.toString(), json)
            val target = NetworkSecurity.validatePublicHttpsTarget("${baseUrl.trimEnd('/')}/chat/completions")
            if (requestBody.length > requestCharLimit(requestBody)) throw LlmRequestTooLargeException(requestBody.length)
            val builder = Request.Builder().url(target.url)
                .addHeader("Content-Type", "application/json")
            if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer $apiKey")
            val req = builder.post(policyOwnedRequestBody(requestBody)).build()
            ProviderRetryPolicy.beforeStreamingEvents({}) {
                client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(req.withPolicyRetryHeader()).withProviderResponse(req, apiKey) { response, progress ->
                    if (!response.isSuccessful) {
                        val errorBody = readBoundedBody(response.body?.source())
                        throw httpFailure(response.code, errorBody, req, retryAfter(response), response.header("retry-after-ms"))
                    }
                    val raw = readBoundedBody(response.body?.source())
                    progress.observe(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, raw, buffered = true)
                    checkedProviderBody(raw)
                }
            }
        }

    suspend fun chatCompletions(
        providerId: String,
        baseUrl: String,
        apiKey: String,
        expectedModelId: String,
        body: String,
    ): String = bufferedCompletion(providerId, baseUrl, apiKey, expectedModelId, body, retry = true)

    /** A bounded utility request. No HTTP retries or shared agent conversation state. */
    internal suspend fun sessionTitleCompletion(config: AgentConfig, body: String): String =
        bufferedCompletion(config.providerId, config.baseUrl, config.apiKey, config.model, body, retry = false)

    private suspend fun bufferedCompletion(providerId: String, baseUrl: String, apiKey: String,
        expectedModelId: String, body: String, retry: Boolean): String = withContext(Dispatchers.IO) {
            val expectedModel = ProviderModelIdentity.requireCanonical(expectedModelId)
            val request = ProviderModelRequestIdentity.canonicalize(body, json)
            require(request.modelId == expectedModel) { "Provider request model identity drifted from the selected model" }
            val provider = requireRuntimeProvider(providerId, baseUrl, expectedModel)
            val preparedBody = ProviderRequestBudget.prepare(json.parseToJsonElement(request.body).jsonObject, provider.id, request.modelId, provider).toString()
            validateCapabilities(provider, request.modelId, preparedBody)
            val spec = ProviderTransportV2.spec(provider, request.modelId)
            val requestBody = when (spec.protocol) {
                ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> ProviderToolSchema.openAiRequest(preparedBody, json, provider)
                ProviderWireProtocol.OPENAI_RESPONSES -> OpenAiResponsesAdapter.encodeRequest(preparedBody, streaming = false, json)
                ProviderWireProtocol.ANTHROPIC_MESSAGES -> AnthropicMessagesAdapter.encodeRequest(preparedBody, streaming = false, json, provider.id)
                ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> GoogleGenerateContentAdapter.encodeRequest(preparedBody, json, provider.id)
            }
            val limit = requestCharLimit(preparedBody)
            if (requestBody.length > limit) throw LlmRequestTooLargeException(requestBody.length)
            val requestUrl = when (spec.protocol) {
                ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> GoogleGenerateContentAdapter.requestUrl(
                    baseUrl, request.modelId, streaming = false
                )
                else -> "${baseUrl.trimEnd('/')}${spec.chatPath}"
            }
            val target = NetworkSecurity.validateProviderTarget(provider, requestUrl)
            val builder = Request.Builder().url(target.url).addHeader("Content-Type", "application/json")
            ProviderTransportV2.applyAuth(builder, provider, apiKey)
            val unsigned = builder.post(policyOwnedRequestBody(requestBody)).build()
            val req = ProviderRequestAuthorizer.authorize(unsigned, provider, apiKey)
            val execute: suspend () -> String = {
                client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(req.withPolicyRetryHeader()).withProviderResponse(req, apiKey, provider.id) { response, progress ->
                    if (!response.isSuccessful) {
                        val errorBody = readBoundedBody(response.body?.source())
                        throw httpFailure(response.code, errorBody, req, retryAfter(response), response.header("retry-after-ms"))
                    }
                    val raw = if (retry) readBoundedBody(response.body?.source()) else {
                        val source = response.body?.source()
                        source?.request(65_537)
                        require(source == null || source.buffer.size <= 65_536) { "Title response is too large" }
                        source?.buffer?.readUtf8().orEmpty()
                    }
                    progress.observe(spec.protocol, raw, buffered = true)
                    when (spec.protocol) {
                        ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> ChatReasoningContinuation.capture(checkedProviderBody(raw), provider, request.modelId, json)
                        ProviderWireProtocol.OPENAI_RESPONSES -> OpenAiResponsesAdapter.normalizeBuffered(raw, json)
                        ProviderWireProtocol.ANTHROPIC_MESSAGES -> AnthropicMessagesAdapter.normalizeBuffered(raw, json)
                        ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> GoogleGenerateContentAdapter.normalizeBuffered(raw, json)
                    }
                }
            }
            if (retry) ProviderRetryPolicy.beforeStreamingEvents({}) { execute() } else execute()
        }

    suspend fun chatCompletionsStream(
        providerId: String,
        baseUrl: String,
        apiKey: String,
        expectedModelId: String,
        body: String,
        onEvent: suspend (LlmStreamEvent) -> Unit,
    ): LlmStreamResult = withContext(Dispatchers.IO) {
        val expectedModel = ProviderModelIdentity.requireCanonical(expectedModelId)
        val request = ProviderModelRequestIdentity.canonicalize(body, json)
        require(request.modelId == expectedModel) { "Provider request model identity drifted from the selected model" }
        val provider = requireRuntimeProvider(providerId, baseUrl, expectedModel)
        val preparedBody = ProviderRequestBudget.prepare(json.parseToJsonElement(request.body).jsonObject, provider.id, request.modelId, provider).toString()
        validateCapabilities(provider, request.modelId, preparedBody)
        val spec = ProviderTransportV2.spec(provider, request.modelId)
        ProviderRetryPolicy.beforeStreamingEvents(onEvent) { safeEvent ->
            when (spec.protocol) {
                ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> streamOpenAi(provider, baseUrl, apiKey, preparedBody, request.modelId, spec, safeEvent)
                ProviderWireProtocol.OPENAI_RESPONSES -> streamResponses(provider, baseUrl, apiKey, preparedBody, spec, safeEvent)
                ProviderWireProtocol.ANTHROPIC_MESSAGES -> streamAnthropic(provider, baseUrl, apiKey, preparedBody, safeEvent)
                ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> streamGoogle(provider, baseUrl, apiKey, preparedBody, safeEvent)
            }
        }
    }

    internal fun ownedChatTarget(providerId: String, baseUrl: String, expectedModelId: String): ProviderReplayTarget? {
        val model = ProviderModelIdentity.requireCanonical(expectedModelId)
        val provider = requireRuntimeProvider(providerId, baseUrl, model)
        val spec = ProviderTransportV2.spec(provider, model)
        if (spec.protocol != ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS || provider.authScheme == ProviderAuthScheme.AWS_SIGV4_BEDROCK) return null
        val target = NetworkSecurity.validateProviderTarget(provider, "${baseUrl.trimEnd('/')}${spec.chatPath}")
        return ProviderReplayTarget(provider.id, model, ProviderReplayTarget.Protocol.OPENAI_CHAT, OwnedChatEndpointIdentity.fromValidatedUrl(target.url))
    }

    /** Source-backed Chat route only. */

    internal suspend fun streamOwnedChat(
        providerId: String, baseUrl: String, apiKey: String, expectedModelId: String,
        options: JsonObject, history: List<SourceAwareChatHistoryWriter.Item>,
        responseSession: OwnedResponseContentStore.Session,
        callSession: (String) -> OwnedResponseContentStore.Session,
        onRecovery: (OwnedChatStreamAccumulator.Recovery) -> Unit,
        onEvent: suspend (LlmStreamEvent) -> Unit,
    ): OwnedChatResult = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val checkCancelled = { context.ensureActive() }
        require("messages" !in options) { "Typed history must be provided separately" }
        val model = ProviderModelIdentity.requireCanonical(expectedModelId)
        val canonical = ProviderModelRequestIdentity.canonicalize(options.toString(), json)
        require(canonical.modelId == model) { "Provider request model identity drifted from the selected model" }
        val provider = requireRuntimeProvider(providerId, baseUrl, model)
        val spec = ProviderTransportV2.spec(provider, model)
        require(spec.protocol == ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { "Source-backed tools currently require Chat transport" }
        require(provider.authScheme != ProviderAuthScheme.AWS_SIGV4_BEDROCK) { "Source-backed request signing is not supported for this authentication scheme" }
        val prepared = ProviderRequestBudget.withOutputLimit(json.parseToJsonElement(canonical.body).jsonObject, provider.id, model, provider)
        validateCapabilities(provider, model, prepared.toString())
        val converted = json.parseToJsonElement(enableStreaming(ProviderToolSchema.openAiRequest(prepared.toString(), json, provider))).jsonObject
        val nativeOptions = JsonObject(converted.filterKeys { it != "messages" })
        require(nativeOptions.toString().length <= MAX_REQUEST_CHARS) { "Provider request options and tool schemas are too large" }
        val networkTarget = NetworkSecurity.validateProviderTarget(provider, "${baseUrl.trimEnd('/')}${spec.chatPath}")
        val replayTarget = ProviderReplayTarget(provider.id, model, ProviderReplayTarget.Protocol.OPENAI_CHAT, OwnedChatEndpointIdentity.fromValidatedUrl(networkTarget.url))
        val normalizedHistory = history.mapNotNull { item ->
            when (item) {
                is SourceAwareChatHistoryWriter.Item.OwnedAssistantGroup -> item
                is SourceAwareChatHistoryWriter.Item.OwnedAssistant -> item
                is SourceAwareChatHistoryWriter.Item.Legacy -> AgentResponseOutcome.providerHistory(listOf(item.message)).singleOrNull()?.let {
                    SourceAwareChatHistoryWriter.Item.Legacy(ChatReasoningContinuation.restore(it, provider, model))
                }
            }
        }
        val requestBody = OwnedChatRequestBody.admit(nativeOptions, normalizedHistory, replayTarget, provider, checkCancelled)
        val builder = Request.Builder().url(networkTarget.url).addHeader("Content-Type", "application/json").addHeader("Accept", "text/event-stream, application/json")
        ProviderTransportV2.applyAuth(builder, provider, apiKey)
        val req = ProviderRequestAuthorizer.authorize(builder.post(requestBody).build(), provider, apiKey)
        ProviderRetryPolicy.beforeStreamingEvents(onEvent) { safeEvent ->
            client.newBuilder().dns(NetworkSecurity.pinnedDns(networkTarget)).build().newCall(req.withPolicyRetryHeader())
                .withProviderResponse(req, apiKey, provider.id) { response, progress ->
                    if (!response.isSuccessful) throw httpFailure(response.code, readBoundedBody(response.body?.source()), req, retryAfter(response), response.header("retry-after-ms"))
                    val accumulator = OwnedChatStreamAccumulator(responseSession, callSession, replayTarget, json, checkCancelled)
                    var primaryFailure: Exception? = null
                    try {
                        val source = requireNotNull(response.body?.source()) { "Empty provider stream response" }
                        val idle = ProviderStreamIdleGuard(ProviderStreamPolicy.idleTimeoutMillis(provider))
                        val first = readStreamLine(source, idle) ?: error("Empty provider stream response")
                        var done = false
                        val emittedToolNames = mutableMapOf<Int, String>()
                        suspend fun accept(payload: String) {
                            accumulator.accept(payload).forEach { event ->
                                val legacy = when(event) {
                                    is OwnedChatStreamAccumulator.Progress.Text -> LlmStreamEvent.TextDelta(event.delta)
                                    is OwnedChatStreamAccumulator.Progress.Thinking -> LlmStreamEvent.Thinking
                                    is OwnedChatStreamAccumulator.Progress.Tool -> {
                                        val previous = emittedToolNames.put(event.index, event.name).orEmpty()
                                        require(event.name.startsWith(previous)) { "Tool name changed after progress publication" }
                                        LlmStreamEvent.ToolCallDelta(event.index,event.id,event.name.removePrefix(previous).takeIf { it.isNotEmpty() },null)
                                    }
                                }
                                progress.emit(legacy, safeEvent)
                            }
                        }
                        if (!isEventStream(first, response.header("Content-Type"))) {
                            val buffered=StringBuilder(first)
                            while(true) {
                                checkCancelled()
                                val line=readStreamLine(source,idle) ?: break
                                require(buffered.length.toLong()+1+line.length <= MAX_RESPONSE_CHARS) { "Buffered provider response exceeds the bounded compatibility limit" }
                                buffered.append('\n').append(line)
                            }
                            val raw=ProviderPayloadLimits.checkedJson(buffered.toString())
                            progress.observe(spec.protocol,raw,buffered=true)
                            accept(OwnedChatBufferedCompatibility.event(raw,json))
                        } else {
                            readSsePayloads(source, first, idle) { payload ->
                                progress.observe(spec.protocol, payload)
                                accept(payload)
                                done = payload.trim() == "[DONE]"; done
                            }
                        }
                        checkCancelled()
                        accumulator.finishAtTransportEnd(if(done) OwnedChatStreamAccumulator.TransportEnd.DONE_MARKER else OwnedChatStreamAccumulator.TransportEnd.CLEAN_EOF)
                    } catch(failure: Exception) {
                        primaryFailure = failure
                        accumulator.transportFailed()
                        try { onRecovery(accumulator.checkpointIncomplete()) } catch(recoveryFailure: Exception) { failure.addSuppressed(recoveryFailure) }
                        throw failure
                    } finally {
                        try { accumulator.close() } catch(closeFailure: Exception) {
                            if (primaryFailure != null) primaryFailure.addSuppressed(closeFailure) else throw closeFailure
                        }
                    }
                }
        }
    }

    private suspend fun streamOpenAi(
        provider: AiProvider,
        baseUrl: String,
        apiKey: String,
        body: String,
        modelId: String,
        spec: ProviderTransportSpec,
        onEvent: suspend (LlmStreamEvent) -> Unit,
    ): LlmStreamResult {
        val requestBody = enableStreaming(ProviderToolSchema.openAiRequest(body, json, provider))
        val target = NetworkSecurity.validateProviderTarget(provider, "${baseUrl.trimEnd('/')}${spec.chatPath}")
        if (requestBody.length > requestCharLimit(body)) throw LlmRequestTooLargeException(requestBody.length)
        val builder = Request.Builder().url(target.url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream, application/json")
        ProviderTransportV2.applyAuth(builder, provider, apiKey)
        val unsigned = builder.post(policyOwnedRequestBody(requestBody)).build()
        val req = ProviderRequestAuthorizer.authorize(unsigned, provider, apiKey)

        return client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(req.withPolicyRetryHeader()).withProviderResponse(req, apiKey, provider.id) { response, progress ->
            if (!response.isSuccessful) {
                val errorBody = readBoundedBody(response.body?.source())
                throw httpFailure(response.code, errorBody, req, retryAfter(response), response.header("retry-after-ms"))
            }
            val source = response.body?.source() ?: throw IllegalStateException("Empty provider stream response")

            val streamIdle = ProviderStreamIdleGuard(ProviderStreamPolicy.idleTimeoutMillis(provider))
            val firstLine = readStreamLine(source, streamIdle) ?: throw IllegalStateException("Empty provider stream response")
            var streamedChars = firstLine.length
            check(streamedChars <= MAX_RESPONSE_CHARS) { "LLM streamed response is too large" }
            val eventStream = isEventStream(firstLine, response.header("Content-Type"))
            if (!eventStream) {
                val remainder = StringBuilder(firstLine)
                while (true) {
                    val line = ProviderPayloadLimits.readLine(source) ?: break
                    streamedChars += line.length + 1
                    if (streamedChars > MAX_RESPONSE_CHARS) throw IllegalStateException("LLM response is too large")
                    remainder.append('\n').append(line)
                }
                progress.observe(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, remainder.toString(), buffered = true)
                val completed = ChatReasoningContinuation.capture(
                    checkedProviderBody(ProviderPayloadLimits.checkedJson(remainder.toString())), provider, modelId, json)
                val content = extractBufferedAssistantContent(completed)
                if (content.isNotEmpty()) {
                    val event = LlmStreamEvent.BufferedResponse(content)
                    progress.emit(event, onEvent)
                }
                return@withProviderResponse LlmStreamResult(completed, LlmStreamMode.BUFFERED)
            }

            val accumulator = OpenAiChatStreamAccumulator(json)
            readSsePayloads(source, firstLine, streamIdle) { payload ->
                progress.observe(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, payload)
                if (payload.trim() == "[DONE]") true else {
                    accumulator.accept(payload).forEach { progress.emit(it, onEvent) }
                    false
                }
            }
            LlmStreamResult(ChatReasoningContinuation.capture(
                completedStreamResponse(accumulator.completedResponse()), provider, modelId, json), LlmStreamMode.NATIVE)
        }
    }

    private suspend fun streamResponses(
        provider: AiProvider,
        baseUrl: String,
        apiKey: String,
        body: String,
        spec: ProviderTransportSpec,
        onEvent: suspend (LlmStreamEvent) -> Unit,
    ): LlmStreamResult {
        val requestBody = OpenAiResponsesAdapter.encodeRequest(body, streaming = true, json)
        if (requestBody.length > requestCharLimit(body)) throw LlmRequestTooLargeException(requestBody.length)
        val target = NetworkSecurity.validateProviderTarget(provider, "${baseUrl.trimEnd('/')}${spec.chatPath}")
        val builder = Request.Builder().url(target.url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream, application/json")
        ProviderTransportV2.applyAuth(builder, provider, apiKey)
        val unsigned = builder.post(policyOwnedRequestBody(requestBody)).build()
        val req = ProviderRequestAuthorizer.authorize(unsigned, provider, apiKey)
        return client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(req.withPolicyRetryHeader()).withProviderResponse(req, apiKey, provider.id) { response, progress ->
            if (!response.isSuccessful) {
                val errorBody = readBoundedBody(response.body?.source())
                throw httpFailure(response.code, errorBody, req, retryAfter(response), response.header("retry-after-ms"))
            }
            val source = response.body?.source() ?: throw IllegalStateException("Empty OpenAI Responses response")
            val streamIdle = ProviderStreamIdleGuard(ProviderStreamPolicy.idleTimeoutMillis(provider))
            val firstLine = readStreamLine(source, streamIdle) ?: throw IllegalStateException("Empty OpenAI Responses response")
            var streamedChars = firstLine.length + 1
            if (streamedChars > MAX_RESPONSE_CHARS) throw IllegalStateException("LLM streamed response is too large")
            val eventStream = firstLine.startsWith("event:") || firstLine.startsWith("data:") ||
                firstLine.startsWith(":") || response.header("Content-Type").orEmpty().contains("text/event-stream", ignoreCase = true)
            if (!eventStream) {
                val remainder = StringBuilder(firstLine)
                while (true) {
                    val line = ProviderPayloadLimits.readLine(source) ?: break
                    streamedChars += line.length + 1
                    if (streamedChars > MAX_RESPONSE_CHARS) throw IllegalStateException("LLM response is too large")
                    remainder.append('\n').append(line)
                }
                progress.observe(ProviderWireProtocol.OPENAI_RESPONSES, remainder.toString(), buffered = true)
                val completed = OpenAiResponsesAdapter.normalizeBuffered(ProviderPayloadLimits.checkedJson(remainder.toString()), json)
                val content = extractBufferedAssistantContent(completed)
                if (content.isNotEmpty()) {
                    val event = LlmStreamEvent.BufferedResponse(content)
                    progress.emit(event, onEvent)
                }
                return@withProviderResponse LlmStreamResult(completed, LlmStreamMode.BUFFERED)
            }

            val accumulator = OpenAiResponsesAdapter.StreamAccumulator(json)
            readSsePayloads(source, firstLine, streamIdle) { payload ->
                if (payload.trim() == "[DONE]") false else {
                    progress.observe(ProviderWireProtocol.OPENAI_RESPONSES, payload)
                    accumulator.accept(payload).forEach { progress.emit(it, onEvent) }
                    val event = json.parseToJsonElement(payload).jsonObject
                    val type = event["type"]?.jsonPrimitive?.contentOrNull
                    type == "response.completed" || type == "response.incomplete"
                }
            }
            LlmStreamResult(accumulator.completedResponse(), LlmStreamMode.NATIVE)
        }
    }

    private suspend fun streamAnthropic(
        provider: AiProvider,
        baseUrl: String,
        apiKey: String,
        body: String,
        onEvent: suspend (LlmStreamEvent) -> Unit,
    ): LlmStreamResult {
        val requestBody = AnthropicMessagesAdapter.encodeRequest(body, streaming = true, json, provider.id)
        val spec = ProviderTransportV2.spec(provider)
        val target = NetworkSecurity.validateProviderTarget(provider, "${baseUrl.trimEnd('/')}${spec.chatPath}")
        if (requestBody.length > requestCharLimit(body)) throw LlmRequestTooLargeException(requestBody.length)
        val builder = Request.Builder().url(target.url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream, application/json")
        ProviderTransportV2.applyAuth(builder, provider, apiKey)
        val unsigned = builder.post(policyOwnedRequestBody(requestBody)).build()
        val req = ProviderRequestAuthorizer.authorize(unsigned, provider, apiKey)

        return client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(req.withPolicyRetryHeader()).withProviderResponse(req, apiKey, provider.id) { response, progress ->
            if (!response.isSuccessful) {
                val errorBody = readBoundedBody(response.body?.source())
                throw httpFailure(response.code, errorBody, req, retryAfter(response), response.header("retry-after-ms"))
            }
            val source = response.body?.source() ?: throw IllegalStateException("Empty provider stream response")
            val streamIdle = ProviderStreamIdleGuard(ProviderStreamPolicy.idleTimeoutMillis(provider))
            val firstLine = readStreamLine(source, streamIdle) ?: throw IllegalStateException("Empty provider stream response")
            var streamedChars = firstLine.length
            check(streamedChars <= MAX_RESPONSE_CHARS) { "LLM streamed response is too large" }
            val eventStream = isEventStream(firstLine, response.header("Content-Type"))
            if (!eventStream) {
                val remainder = StringBuilder(firstLine)
                while (true) {
                    val line = ProviderPayloadLimits.readLine(source) ?: break
                    streamedChars += line.length + 1
                    if (streamedChars > MAX_RESPONSE_CHARS) throw IllegalStateException("LLM response is too large")
                    remainder.append('\n').append(line)
                }
                progress.observe(ProviderWireProtocol.ANTHROPIC_MESSAGES, remainder.toString(), buffered = true)
                val completed = ProviderResponseCompletion.anthropic(AnthropicMessagesAdapter.normalizeBuffered(ProviderPayloadLimits.checkedJson(remainder.toString()), json), json)
                val content = extractBufferedAssistantContent(completed)
                if (content.isNotEmpty()) {
                    val event = LlmStreamEvent.BufferedResponse(content)
                    progress.emit(event, onEvent)
                }
                return@withProviderResponse LlmStreamResult(completed, LlmStreamMode.BUFFERED)
            }

            val accumulator = AnthropicMessagesAdapter.StreamAccumulator(json)
            var stopped = false
            readSsePayloads(source, firstLine, streamIdle) { payload ->
                progress.observe(ProviderWireProtocol.ANTHROPIC_MESSAGES, payload)
                accumulator.accept(payload).forEach { progress.emit(it, onEvent) }
                val type = json.parseToJsonElement(payload).jsonObject["type"]?.jsonPrimitive?.contentOrNull
                if (type == "message_stop") stopped = true
                stopped
            }
            check(stopped) { "Anthropic stream ended before message_stop" }
            LlmStreamResult(ProviderResponseCompletion.anthropic(accumulator.completedResponse(), json), LlmStreamMode.NATIVE)
        }
    }

    private suspend fun streamGoogle(
        provider: AiProvider,
        baseUrl: String,
        apiKey: String,
        body: String,
        onEvent: suspend (LlmStreamEvent) -> Unit,
    ): LlmStreamResult {
        val requestBody = GoogleGenerateContentAdapter.encodeRequest(body, json, provider.id)
        if (requestBody.length > requestCharLimit(body)) throw LlmRequestTooLargeException(requestBody.length)
        val model = GoogleGenerateContentAdapter.modelFromBody(body, json)
        val target = NetworkSecurity.validateProviderTarget(
            provider, GoogleGenerateContentAdapter.requestUrl(baseUrl, model, streaming = true)
        )
        val builder = Request.Builder().url(target.url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream, application/json")
        ProviderTransportV2.applyAuth(builder, provider, apiKey)
        val unsigned = builder.post(policyOwnedRequestBody(requestBody)).build()
        val req = ProviderRequestAuthorizer.authorize(unsigned, provider, apiKey)
        return client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(req.withPolicyRetryHeader()).withProviderResponse(req, apiKey, provider.id) { response, progress ->
            if (!response.isSuccessful) {
                val errorBody = readBoundedBody(response.body?.source())
                throw httpFailure(response.code, errorBody, req, retryAfter(response), response.header("retry-after-ms"))
            }
            val source = response.body?.source() ?: throw IllegalStateException("Empty provider stream response")
            val streamIdle = ProviderStreamIdleGuard(ProviderStreamPolicy.idleTimeoutMillis(provider))
            val firstLine = readStreamLine(source, streamIdle) ?: throw IllegalStateException("Empty provider stream response")
            var streamedChars = firstLine.length
            check(streamedChars <= MAX_RESPONSE_CHARS) { "LLM streamed response is too large" }
            val eventStream = isEventStream(firstLine, response.header("Content-Type"))
            if (!eventStream) {
                val remainder = StringBuilder(firstLine)
                while (true) {
                    val line = ProviderPayloadLimits.readLine(source) ?: break
                    streamedChars += line.length + 1
                    if (streamedChars > MAX_RESPONSE_CHARS) throw IllegalStateException("LLM response is too large")
                    remainder.append('\n').append(line)
                }
                val raw = ProviderPayloadLimits.checkedJson(remainder.toString())
                progress.observe(ProviderWireProtocol.GOOGLE_GENERATE_CONTENT, raw, buffered = true)
                // A streaming endpoint can return a JSON error under HTTP 200. Classify it before
                // successful-response finishReason validation.
                val normalized = GoogleGenerateContentAdapter.normalizeBuffered(raw, json)
                val terminal = googleCompletionReason(json.parseToJsonElement(raw).jsonObject)
                check(terminal != null) { "Google response is missing finishReason or prompt block" }
                val completed = completedGoogleStreamResponse(normalized, terminal)
                val content = extractBufferedAssistantContent(completed)
                if (content.isNotEmpty()) {
                    val event = LlmStreamEvent.BufferedResponse(content)
                    progress.emit(event, onEvent)
                }
                return@withProviderResponse LlmStreamResult(completed, LlmStreamMode.BUFFERED)
            }
            val accumulator = GoogleGenerateContentAdapter.StreamAccumulator(json)
            var terminal: String? = null
            readSsePayloads(source, firstLine, streamIdle) { payload ->
                progress.observe(ProviderWireProtocol.GOOGLE_GENERATE_CONTENT, payload)
                val root = json.parseToJsonElement(payload) as? JsonObject
                    ?: throw IllegalStateException("Invalid Google SSE JSON")
                googleCompletionReason(root)?.let { reason ->
                    check(terminal == null || terminal == reason) { "Google stream changed finishReason" }
                    terminal = reason
                }
                accumulator.accept(payload).forEach { progress.emit(it, onEvent) }
                false
            }
            val reason = terminal ?: throw IllegalStateException("Google stream ended before finishReason or prompt block")
            LlmStreamResult(completedGoogleStreamResponse(accumulator.completedResponse(), reason), LlmStreamMode.NATIVE)
        }
    }

    private fun isEventStream(firstLine: String, contentType: String?): Boolean =
        firstLine.startsWith("data:") || firstLine.startsWith("event:") || firstLine.startsWith(":") ||
            contentType.orEmpty().contains("text/event-stream", ignoreCase = true)

    private suspend fun readSsePayloads(
        source: BufferedSource,
        firstLine: String,
        idle: ProviderStreamIdleGuard,
        consume: suspend (String) -> Boolean,
    ) {
        val framing = ProviderSseFraming()
        suspend fun dispatch(payload: String?): Boolean {
            if (payload == null) return false
            val done = consume(payload)
            // The next-event timeout starts after dispatch/backpressure completes.

            idle.progress()
            return done
        }
        suspend fun line(raw: String): Boolean {
            currentCoroutineContext().ensureActive()
            // Measure stream inactivity between decoded events, including heartbeats, rather than visible
            // text.
            idle.check()
            return dispatch(framing.line(raw))
        }
        var done = line(firstLine)
        while (!done) {
            currentCoroutineContext().ensureActive()
            idle.check()
            val next = readStreamLine(source, idle)
            if (next == null) {
                dispatch(framing.finish())
                break
            }
            done = line(next)
        }
    }

    private fun readStreamLine(source: BufferedSource, idle: ProviderStreamIdleGuard): String? {
        idle.check()
        // Do not give partial/comment traffic another full socket timeout.
        val timeout = source.timeout()
        val hadDeadline = timeout.hasDeadline()
        val oldDeadline = if (hadDeadline) timeout.deadlineNanoTime() else 0L
        val remainingDeadline = idle.deadlineNanos()
        val oldReadTimeout = timeout.timeoutNanos()
        val now = System.nanoTime()
        val effectiveDeadline = if (hadDeadline && oldDeadline - now < remainingDeadline - now) oldDeadline else remainingDeadline
        return try {

            timeout.timeout(idle.timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            timeout.deadlineNanoTime(effectiveDeadline)
            ProviderPayloadLimits.readLine(source)
        } finally {
            if (hadDeadline) timeout.deadlineNanoTime(oldDeadline) else timeout.clearDeadline()
            timeout.timeout(oldReadTimeout, java.util.concurrent.TimeUnit.NANOSECONDS)
        }
    }

    private fun googleCompletionReason(root: JsonObject): String? = GoogleGenerateContentAdapter.completionReason(root)

    private fun completedGoogleStreamResponse(body: String, nativeReason: String): String =
        ProviderResponseCompletion.google(body, nativeReason, json)

    private fun completedStreamResponse(body: String): String = ProviderResponseCompletion.complete(body, json)

    private fun enableStreaming(body: String): String {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw IllegalArgumentException("LLM request body must be a JSON object")
        val existingStreamOptions = root["stream_options"] as? JsonObject
        return buildJsonObject {
            root.forEach { (key, value) ->
                if (key != "stream_options") put(key, value)
            }
            put("stream", true)
            put("stream_options", buildJsonObject {
                existingStreamOptions?.forEach { (key, value) -> put(key, value) }

                put("include_usage", true)
            })
        }.toString()
    }

    private fun extractBufferedAssistantContent(body: String): String = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        choice?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
    }.getOrDefault("")

    private fun readBoundedBody(source: okio.BufferedSource?): String {
        if (source == null) return ""
        source.request(MAX_RESPONSE_CHARS.toLong() + 1L)
        if (source.buffer.size > MAX_RESPONSE_CHARS) throw IllegalStateException("LLM response is too large")
        return ProviderPayloadLimits.checkedJson(source.buffer.readUtf8())
    }

    private fun httpFailure(status: Int, body: String, request: Request, retryAfter: String? = null, retryAfterMs: String? = null): LlmHttpException {
        val secrets = ProviderFailure.requestSecrets(request::header)
        return LlmHttpException(status, ProviderFailure.redact(body, secrets), retryAfter, retryAfterMs)
    }

    private fun checkedProviderBody(body: String): String {
        // Buffered callers need the same completion admission as streaming: an unfinished batch must
        // never become executable merely because one call's arguments are valid JSON.
        return ProviderResponseCompletion.bufferedChat(body, json)
    }

    /** Compatibility entry point for existing callers; production uses the same pure helper. */
    internal class StreamAccumulator(json: Json) {
        private val delegate = OpenAiChatStreamAccumulator(json)
        fun accept(payload: String): List<LlmStreamEvent> = delegate.accept(payload)
        fun completedResponse(): String = delegate.completedResponse()
    }

    internal fun estimateContext(systemText: String, history: List<JsonObject>, tools: JsonArray?,
        providerId: String, baseUrl: String, modelId: String): ContextEstimate =
        estimateContext(systemText, history, tools, providerId, baseUrl, modelId,
            ProviderRequestBudget.defaultOutputTokens(providerId, modelId))

    internal fun estimateContext(systemText: String, history: List<JsonObject>, tools: JsonArray?,
        providerId: String, baseUrl: String, modelId: String, reservedOutputTokens: Int): ContextEstimate {
        val canonicalModel = ProviderModelIdentity.requireCanonical(modelId)
        val provider = requireRuntimeProvider(providerId, baseUrl, canonicalModel)
        return ContextBudget.estimate(systemText, history, tools, providerId, canonicalModel,
            ProviderTransportV2.spec(provider, canonicalModel), reservedOutputTokens)
    }

    private fun requireRuntimeProvider(providerId: String, baseUrl: String, modelId: String): AiProvider =
        ProviderRuntimeGuard.requireCertifiedSelection(
            providerId,
            baseUrl,
            ProviderModelIdentity.requireCanonical(modelId),
            providerConfiguration,
        )

    private fun validateCapabilities(provider: AiProvider, model: String, body: String) {
        val root = json.parseToJsonElement(body).jsonObject
        ProviderModelRequestPolicy.requireToolSupport(provider, model, (root["tools"] as? JsonArray)?.isNotEmpty() == true)
        val capability = ModelCapabilityRegistry.resolve(provider.id, model) ?: return
        if (capability.temperature == false) require(root["temperature"] == null) { "Model '$model' does not support temperature." }
        capability.outputTokens?.let { cap ->
            for (field in listOf("max_tokens", "max_completion_tokens", "max_output_tokens")) {
                root[field]?.let { raw ->
                    val limit = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                    require(limit != null && limit in 1..cap.toLong()) { "Model '$model' $field must be within its output limit ($cap)." }
                }
            }
        }
    }

    private fun requestCharLimit(body: String): Int =
        if (body.contains("\"type\":\"image_url\"")) MAX_MULTIMODAL_REQUEST_CHARS else MAX_REQUEST_CHARS

    companion object {
        const val MAX_REQUEST_CHARS = 1_500_000
        const val MAX_MULTIMODAL_REQUEST_CHARS = 13_000_000
        const val MAX_RESPONSE_CHARS = 2_000_000
    }
}
