package com.baystudio.droide.core

// Protocols are selected by provider authority and explicit metadata, never a routed model name alone.
internal object ProviderModelRequestPolicy {
    fun protocol(provider: AiProvider, modelId: String?): ProviderWireProtocol {
        if (provider.wireProtocol != ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS || modelId == null) return provider.wireProtocol
        val endpoints = ModelCapabilityRegistry.resolve(provider.id, modelId)?.supportedEndpoints.orEmpty().map(::endpoint).toSet()
        if ("responses" in endpoints) return ProviderWireProtocol.OPENAI_RESPONSES
        if (endpoints.isNotEmpty()) return provider.wireProtocol
        if (provider.id == "openai" && reasoningResponses.containsMatchIn(modelId)) return ProviderWireProtocol.OPENAI_RESPONSES
        return provider.wireProtocol
    }

    fun isConversational(provider: AiProvider, modelId: String): Boolean {
        // Google lists speech models under generateContent too. That action alone does not
        // establish text chat or function-calling support, even when discovery omits modalities.
        if (isGoogleSpeechModel(modelId)) return false
        val capability = ModelCapabilityRegistry.resolve(provider.id, modelId)
        if (capability?.transportCompatible == false) return false
        if (!capability?.outputModalities.isNullOrEmpty() && "text" !in capability!!.outputModalities) return false
        if (provider.id == "openai" && nonChatOpenAi.containsMatchIn(modelId)) return false
        val endpoints = capability?.supportedEndpoints.orEmpty().map(::endpoint).toSet()
        val compatibleEndpoints = when (protocol(provider, modelId)) {
            ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> setOf("chat/completions")
            ProviderWireProtocol.OPENAI_RESPONSES -> setOf("responses")
            ProviderWireProtocol.ANTHROPIC_MESSAGES -> setOf("messages")
            ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> setOf("generatecontent", "streamgeneratecontent")
        }
        if (endpoints.isNotEmpty() && endpoints.none { it in compatibleEndpoints }) return false
        return true
    }

    fun requireConversational(provider: AiProvider, modelId: String) {
        require(!isGoogleSpeechModel(modelId)) {
            "Model '$modelId' generates speech (TTS) and cannot run the coding Agent. Select a Gemini text chat model."
        }
        require(isConversational(provider, modelId)) { "Model '$modelId' does not advertise a supported conversational endpoint or text output. Select a chat model." }
    }

    fun isGoogleSpeechModel(modelId: String): Boolean = googleSpeech.containsMatchIn(modelId)

    fun requireToolSupport(provider: AiProvider, modelId: String, hasTools: Boolean) {
        require(!hasTools || ModelCapabilityRegistry.allowsToolUse(provider.id, modelId)) {
            "Model '$modelId' does not support function tools. Use a text-only request or select a tool-capable model."
        }
    }

    private fun endpoint(raw: String): String = when (val value = raw.trim().lowercase().removePrefix("/v1/").removePrefix("/")) {
        "chat", "chat_completions", "chat-completions", "openai_chat_completions" -> "chat/completions"
        "openai_responses" -> "responses"
        "anthropic_messages" -> "messages"
        "google_generate_content", "generate_content" -> "generatecontent"
        else -> value
    }

    private val reasoningResponses = Regex("^(?:gpt-(?:[5-9]|[1-9]\\d+)(?=[.-]|$)|o[34](?=[.-]|$)|o1-pro(?=-|$))")
    private val nonChatOpenAi = Regex("^(?:text-embedding-|whisper-|tts-|dall-e-|gpt-image-|gpt-4o(?:-mini)?-(?:transcribe|tts)|(?:davinci|babbage)-002$)")
    private val googleSpeech = Regex("(?:^|/)gemini-[a-z0-9.-]+-tts(?:[-.]|$)", RegexOption.IGNORE_CASE)
}
