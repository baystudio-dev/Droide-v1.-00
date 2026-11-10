package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Deliberate bounded compatibility bridge for the unchanged small-tool pipeline. */
internal object OwnedChatLegacyProjection {
    /** Public execution/history projection ONLY after native continuation is durably retained.
     * Receipt is registry-issued and independently owner-authorized, not a caller Boolean bypass. */
    fun publicResponse(result: OwnedChatResult, retained: NativeAssistantRegistry.BoundAssistant,
        provider: AiProvider, callSession: (String) -> OwnedResponseContentStore.Session,
        checkCancelled: () -> Unit = {}): String? {
        require(!result.hasRefusal) { "Refused responses cannot become public normal/tool outcomes" }
        require(result.finishReason == if (result.calls.isEmpty()) "stop" else "tool_calls")
        require(retained.assistant.target == result.target && result.target.providerId == provider.id)
        require(result.target.protocol == ProviderReplayTarget.Protocol.OPENAI_CHAT)
        require(retained.callIds == result.calls.map { it.id })
        val path = ProviderTransportV2.spec(provider,result.target.modelId).chatPath
        require(result.target.endpointIdentity == OwnedChatEndpointIdentity.fromValidatedUrl(
            NetworkSecurity.validateProviderTarget(provider,"${provider.baseUrl.trimEnd('/')}$path").url)) { "Legacy projection target changed" }
        val reference = retained.assistant.reference
        val native = result.nativeAssistantMessage
        require(native.kind == OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION)
        require(reference.handle.serialized == native.handle.serialized && reference.sha256 == native.sha256 && reference.byteLength == native.bytes)
        fun verify(source: StreamedFileArgumentExtractor.Source, session: OwnedResponseContentStore.Session,
            kind: OwnedResponseContentStore.Kind) {
            checkCancelled()
            require(source.kind == kind)
            val actual = session.openCompleted(source.handle, checkCancelled).content
            require(actual.kind == kind && actual.byteLength == source.bytes && actual.utf16Length == source.utf16Units && actual.sha256 == source.sha256) {
                "Public projection source changed after admission"
            }
        }
        // Verification scans private bytes for integrity, but never creates their String/JSON tree.
        verify(native, retained.responseSession, OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION)
        verify(result.publicText, retained.responseSession, OwnedResponseContentStore.Kind.ANSWER)
        val sessions = result.calls.map { call -> callSession(call.id).also {
            verify(call.arguments, it, OwnedResponseContentStore.Kind.TOOL_ARGUMENTS)
        } }
        if (result.publicText.utf16Units > LlmClient.MAX_RESPONSE_CHARS || result.calls.any { it.arguments.utf16Units > 128_000 }) return null
        val out = StringBuilder()
        fun append(value: String) {
            if (out.length.toLong() + value.length > LlmClient.MAX_RESPONSE_CHARS) throw ProjectionLimit()
            out.append(value)
        }
        fun escaped(value: String) {
            value.forEachIndexed { index, c ->
                if (index % 4096 == 0) checkCancelled()
                when(c) {
                    '\"' -> append("\\\""); '\\' -> append("\\\\")
                    '\b' -> append("\\b"); '\u000c' -> append("\\f"); '\n' -> append("\\n")
                    '\r' -> append("\\r"); '\t' -> append("\\t")
                    else -> if (c.code < 32) append("\\u" + c.code.toString(16).padStart(4,'0')) else {
                        if (out.length == LlmClient.MAX_RESPONSE_CHARS) throw ProjectionLimit()
                        out.append(c)
                    }
                }
            }
        }
        fun quote(value: String) { append("\""); escaped(value); append("\"") }
        fun quoteSource(source: StreamedFileArgumentExtractor.Source, session: OwnedResponseContentStore.Session) {
            append("\"")
            session.openPages(source.handle, checkCancelled).use { pages ->
                while(true) { checkCancelled(); val page = pages.readPage() ?: break; escaped(page) }
            }
            append("\"")
        }
        return try {
            append("{")
            result.responseId?.let { append("\"id\":");quote(it);append(",") }
            result.reportedModel?.let { append("\"model\":");quote(it);append(",") }
            result.usage?.let { append("\"usage\":");append(it.toString());append(",") }
            append("\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":")
            quoteSource(result.publicText,retained.responseSession)
            if(result.calls.isNotEmpty()) {
                append(",\"tool_calls\":[")
                result.calls.forEachIndexed { i,call ->
                    if(i>0)append(",")
                    append("{\"id\":");quote(call.id);append(",\"type\":\"function\",\"function\":{\"name\":");quote(call.name)
                    append(",\"arguments\":");quoteSource(call.arguments,sessions[i]);append("}}")
                }
                append("]")
            }
            append("},\"finish_reason\":");quote(result.finishReason)
            result.nativeFinishReason?.let { append(",\"native_finish_reason\":");quote(it) }
            append("}]}");checkCancelled();out.toString()
        } catch(_: ProjectionLimit) { null } // Whole projection rejected, never truncated.
    }
    private class ProjectionLimit : RuntimeException()

    fun response(result: OwnedChatResult, responseSession: OwnedResponseContentStore.Session,
        provider: AiProvider, maximumNativeChars: Int = LlmClient.MAX_RESPONSE_CHARS,
        maximumArgumentChars: Int = 128_000, checkCancelled: () -> Unit = {}): String? {
        require(maximumNativeChars in 1..LlmClient.MAX_RESPONSE_CHARS && maximumArgumentChars in 1..128_000)
        require(result.target.providerId == provider.id && result.target.protocol == ProviderReplayTarget.Protocol.OPENAI_CHAT)
        require(result.nativeAssistantMessage.kind == OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION)
        val path=ProviderTransportV2.spec(provider,result.target.modelId).chatPath
        require(result.target.endpointIdentity == OwnedChatEndpointIdentity.fromValidatedUrl(
            NetworkSecurity.validateProviderTarget(provider,"${provider.baseUrl.trimEnd('/')}$path").url)) { "Legacy projection target changed" }
        if (result.nativeAssistantMessage.utf16Units > maximumNativeChars || result.calls.any { it.arguments.utf16Units > maximumArgumentChars }) return null
        result.nativeAssistantMessage.verify(responseSession)
        val text=StringBuilder()
        responseSession.openPages(result.nativeAssistantMessage.handle).use { pages ->
            while(true){checkCancelled();val page=pages.readPage() ?: break
                if(text.length.toLong()+page.length>maximumNativeChars)return null
                text.append(page)
            }
        }
        checkCancelled()
        val message=Json.parseToJsonElement(text.toString()).jsonObject
        val root=buildJsonObject {
            result.responseId?.let{put("id",it)};result.reportedModel?.let{put("model",it)};result.usage?.let{put("usage",it)}
            put("choices",buildJsonArray{add(buildJsonObject{put("index",0);put("message",message);put("finish_reason",result.finishReason);result.nativeFinishReason?.let{put("native_finish_reason",it)}})})
        }
        val normalized=ChatReasoningContinuation.capture(root.toString(),provider,result.target.modelId,Json)
        checkCancelled()
        return normalized.takeIf { it.length<=maximumNativeChars }
    }
}
