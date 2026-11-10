package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal object GoogleGenerateContentAdapter {
    internal const val PRIVATE_CONTENT_FIELD = "_droide_google_private_parts"
    internal const val NATIVE_FINISH_REASON_FIELD = "_droide_google_finish_reason"
    private const val NUMERIC_THINKING_ANSWER_RESERVE = 4_096

    // Diagnostic metadata is an enum owned by this adapter, never provider prose.
    private val nativeFinishReasons = setOf(
        "FINISH_REASON_UNSPECIFIED", "STOP", "MAX_TOKENS", "SAFETY", "RECITATION", "LANGUAGE", "OTHER",
        "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "MALFORMED_FUNCTION_CALL", "IMAGE_SAFETY",
        "IMAGE_PROHIBITED_CONTENT", "IMAGE_OTHER", "NO_IMAGE", "IMAGE_RECITATION", "UNEXPECTED_TOOL_CALL",
        "TOO_MANY_TOOL_CALLS", "MISSING_THOUGHT_SIGNATURE", "MALFORMED_RESPONSE", "ESCALATION",
        "PUP_LIMITED_DISABLED", "CONTINUATION",
    )
    private val nativeBlockReasons = setOf("SAFETY", "OTHER", "BLOCKLIST", "PROHIBITED_CONTENT", "IMAGE_SAFETY")

    internal fun safeNativeCompletionReason(reason: String?): String? = when {
        reason.isNullOrBlank() -> null
        reason.startsWith("PROMPT_BLOCK:") -> "PROMPT_BLOCK:" +
            reason.removePrefix("PROMPT_BLOCK:").let { if (it in nativeBlockReasons) it else "UNKNOWN" }
        reason in nativeFinishReasons -> reason
        else -> "UNKNOWN"
    }

    internal fun completionReason(root: JsonObject): String? {
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        val finish = (candidate?.get("finishReason") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!finish.isNullOrBlank()) return finish
        val block = ((root["promptFeedback"] as? JsonObject)?.get("blockReason") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        return block?.takeUnless { it.isBlank() || it == "BLOCK_REASON_UNSPECIFIED" }?.let { "PROMPT_BLOCK:$it" }
    }

    fun modelFromBody(body: String, json: Json): String {
        val root = json.parseToJsonElement(ProviderPayloadLimits.checkedRequest(body)) as? JsonObject
            ?: throw IllegalArgumentException("LLM request body must be a JSON object")
        return normalizeModel(root["model"]?.jsonPrimitive?.contentOrNull)
    }

    fun requestUrl(baseUrl: String, model: String, streaming: Boolean): String {
        val safe = normalizeModel(model)
        val action = if (streaming) "streamGenerateContent?alt=sse" else "generateContent"
        return "${baseUrl.trimEnd('/')}/models/$safe:$action"
    }

    fun encodeRequest(body: String, json: Json, providerId: String = "gemini"): String {
        val parsed = json.parseToJsonElement(ProviderPayloadLimits.checkedRequest(body)) as? JsonObject
            ?: throw IllegalArgumentException("LLM request body must be a JSON object")
        val root = ProviderToolSchema.normalizeRequest(parsed)
        val model = modelFromBody(body, json)
        require(!ProviderModelRequestPolicy.isGoogleSpeechModel(model)) {
            "Model '$model' generates speech (TTS). Select a Gemini text chat model for the Agent."
        }
        val messages = root["messages"] as? JsonArray ?: JsonArray(emptyList())
        val callNames = toolCallNames(messages)
        return buildJsonObject {
            systemInstruction(messages)?.let { put("systemInstruction", it) }
            put("contents", convertMessages(messages, callNames, json))
            convertTools(root["tools"] as? JsonArray)?.let { put("tools", it) }
            val generation = buildJsonObject {
                ProviderRequestBudget.explicitOutputTokens(root)?.let { put("maxOutputTokens", it) }
                root["temperature"]?.let { put("temperature", it) }
                root["top_p"]?.let { put("topP", it) }
                root["reasoning_effort"]?.jsonPrimitive?.contentOrNull?.let { effort ->
                    put("thinkingConfig", googleThinkingConfig(providerId, model, effort, ProviderRequestBudget.explicitOutputTokens(root)))
                }
            }
            if (generation.isNotEmpty()) put("generationConfig", generation)
        }.toString()
    }

    internal fun thinkingBudget(providerId: String, model: String, effort: String): Int? =
        (googleThinkingConfig(providerId, model, effort)["thinkingBudget"] as? JsonPrimitive)?.intOrNull

    private fun googleThinkingConfig(providerId: String, model: String, rawEffort: String, outputLimit: Int? = null): JsonObject {
        val effort = rawEffort.trim().lowercase()
        require(effort == "low" || effort == "high") {
            "Native Gemini does not support reasoning effort: $rawEffort"
        }
        return when (ReasoningSupport.googleNativeThinkingMode(model)) {
            GoogleNativeThinkingMode.BUDGET -> {
                val budget = if (effort == "low") 1_024 else 24_576
                val capability = ModelCapabilityRegistry.resolve(providerId, model)
                val minimum = maxOf(1, capability?.reasoningBudgetMin ?: if ("pro" in model.lowercase()) 128 else 1)
                val ceiling = listOfNotNull(capability?.outputTokens, outputLimit).minOrNull()
                val maximumForOutput = ceiling?.let {
                    require(it > 0) { "Gemini model '$model' output limit must be positive" }
                    // These are derived effort presets, not explicit reasoning-token contracts.

                    val answerReserve = minOf(NUMERIC_THINKING_ANSWER_RESERVE, (it / 2).coerceAtLeast(1))
                    it - answerReserve
                } ?: Int.MAX_VALUE
                val maximum = minOf(capability?.reasoningBudgetMax ?: Int.MAX_VALUE, maximumForOutput)
                require(maximum >= minimum) {
                    "Gemini model '$model' output/reasoning limits cannot fit its minimum thinking budget and answer allowance"
                }
                buildJsonObject { put("includeThoughts", false); put("thinkingBudget", budget.coerceIn(minimum, maximum)) }
            }
            GoogleNativeThinkingMode.LEVEL -> buildJsonObject {
                put("includeThoughts", false)
                put("thinkingLevel", effort.uppercase(java.util.Locale.ROOT))
            }
            null -> throw IllegalArgumentException("Native Gemini reasoning requires a supported Gemini 2.5 or 3+ model: $model")
        }
    }

    fun normalizeBuffered(body: String, json: Json): String {
        val root = json.parseToJsonElement(ProviderPayloadLimits.checkedJson(body)) as? JsonObject
            ?: throw IllegalStateException("Malformed Google GenerateContent response")
        root["error"]?.takeUnless { it == JsonNull }?.let { throw ProviderErrorPayloadException(it) }
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        return normalized(candidate, root["usageMetadata"] as? JsonObject, completionReason(root))
    }

    class StreamAccumulator(private val json: Json) {
        private val capacity = ProviderPayloadLimits.RetainedBudget()
        val retainedRevision: Long get() = capacity.revision
        private val text = StringBuilder()
        private val calls = mutableListOf<JsonObject>()
        private data class ContinuationPart(
            val raw: JsonObject? = null,
            val text: StringBuilder? = null,
            val thought: JsonElement? = null,
        )
        private val continuationParts = mutableListOf<ContinuationPart>()
        private val seenCalls = hashSetOf<String>()
        private var finishReason: String? = null
        private var nativeReason: String? = null
        private var usage: JsonObject? = null

        fun accept(payload: String): List<LlmStreamEvent> {
            capacity.validateEvent(payload)
            val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
                ?: throw IllegalStateException("Invalid Gemini SSE JSON")
            root["error"]?.takeUnless { it == JsonNull }?.let { throw ProviderErrorPayloadException(it) }
            completionReason(root)?.let { capacity.replace("nativeReason", it.length.toLong()); nativeReason = it }
            (root["usageMetadata"] as? JsonObject)?.let { capacity.replace("usage", ProviderPayloadLimits.retainedJsonUnits(it)); usage = it }
            val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return emptyList()
            candidate["finishReason"]?.jsonPrimitive?.contentOrNull?.let { capacity.replace("finish", it.length.toLong()); finishReason = it }
            val parts = ((candidate["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
            val events = mutableListOf<LlmStreamEvent>()
            parts.forEach { raw ->
                val part = raw as? JsonObject ?: return@forEach
                part["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { delta ->
                    if (part["thought"] == JsonPrimitive(true)) events += LlmStreamEvent.Thinking
                    else { capacity.append(text, delta); events += LlmStreamEvent.TextDelta(delta) }
                }
                val fc = part["functionCall"] as? JsonObject
                if (fc == null) {
                    appendContinuation(part)
                    return@forEach
                }
                val name = fc["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val id = fc["id"]?.jsonPrimitive?.contentOrNull ?: "gemini_tool_${calls.size}"
                val args = (fc["args"] as? JsonObject ?: buildJsonObject {}).toString()
                val key = "$id\u0000$name\u0000$args"
                if (key !in seenCalls) {
                    capacity.reserve(key.length.toLong() + 256)
                    seenCalls.add(key)
                    val index = calls.size
                    val call = openAiToolCall(index, id, name, args)
                    capacity.reserve(ProviderPayloadLimits.retainedJsonUnits(call) + 256)
                    calls += call
                    appendContinuation(part)
                    events += LlmStreamEvent.ToolCallDelta(index, id, name, args)
                }
            }
            return events
        }

        // Only adjacent plain text fragments with identical thought flags can be combined.

        private fun appendContinuation(part: JsonObject) {
            val fragment = (part["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val thought = part["thought"]
            val plainText = fragment != null && part.keys.all { it == "text" || it == "thought" } &&
                (thought == null || thought == JsonPrimitive(true) || thought == JsonPrimitive(false))
            if (plainText) {
                val previous = continuationParts.lastOrNull()
                val target = if (previous?.text != null && previous.thought == thought) previous else {
                    capacity.reserve(256)
                    ContinuationPart(text = StringBuilder(), thought = thought).also { continuationParts += it }
                }
                capacity.append(checkNotNull(target.text), checkNotNull(fragment))
            } else {
                capacity.reserve(ProviderPayloadLimits.retainedJsonUnits(part) + 256)
                continuationParts += ContinuationPart(raw = part)
            }
        }

        fun completedResponse(): String = normalizedFromParts(
            text.toString(), JsonArray(calls), finishReason, usage,
            JsonArray(continuationParts.map { part -> part.raw ?: buildJsonObject {
                put("text", checkNotNull(part.text).toString())
                part.thought?.let { put("thought", it) }
            } }), nativeReason,
        )
    }

    private fun normalizeModel(raw: String?): String {
        val value = raw?.removePrefix("models/")?.trim().orEmpty()
        require(value.matches(Regex("[A-Za-z0-9._:-]{1,200}"))) { "Invalid Gemini model ID" }
        return value
    }

    private fun systemInstruction(messages: JsonArray): JsonObject? {
        val texts = messages.mapNotNull { raw ->
            val msg = raw as? JsonObject ?: return@mapNotNull null
            if (msg["role"]?.jsonPrimitive?.contentOrNull != "system") return@mapNotNull null
            extractText(msg["content"])
        }.filter(String::isNotBlank)
        if (texts.isEmpty()) return null
        return buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", texts.joinToString("\n\n")) }) }) }
    }

    private fun toolCallNames(messages: JsonArray): Map<String, String> = buildMap {
        messages.forEach { raw ->
            val msg = raw as? JsonObject ?: return@forEach
            (msg["tool_calls"] as? JsonArray)?.forEach { callRaw ->
                val call = callRaw as? JsonObject ?: return@forEach
                val id = call["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val name = ((call["function"] as? JsonObject)?.get("name"))?.jsonPrimitive?.contentOrNull ?: return@forEach
                put(id, name)
            }
        }
    }

    private fun convertMessages(messages: JsonArray, callNames: Map<String, String>, json: Json): JsonArray {
        val nativeIds = buildSet {
            messages.forEach { raw ->
                val msg = raw as? JsonObject ?: return@forEach
                val private = msg[PRIVATE_CONTENT_FIELD] as? JsonArray
                if (private == null) (msg["tool_calls"] as? JsonArray).orEmpty().forEach { call ->
                    (call as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.let(::add)
                } else private.forEach { part ->
                    ((part as? JsonObject)?.get("functionCall") as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.let(::add)
                }
            }
        }
        val entries = buildJsonArray {
            messages.forEach { raw ->
                val msg = raw as? JsonObject ?: return@forEach
                when (msg["role"]?.jsonPrimitive?.contentOrNull) {
                    "system" -> Unit
                    "user" -> add(buildJsonObject { put("role", "user"); put("parts", contentParts(msg["content"], true)) })
                    "assistant" -> add(buildJsonObject {
                        put("role", "model")
                        val private = validatedNativeContent(msg, json)
                        if (private != null) {
                            put("parts", private)
                            return@buildJsonObject
                        }
                        put("parts", buildJsonArray {
                            contentParts(msg["content"], false).forEach(::add)
                            (msg["tool_calls"] as? JsonArray)?.forEachIndexed { index, callRaw ->
                                val call = callRaw as? JsonObject ?: return@forEachIndexed
                                val fn = call["function"] as? JsonObject ?: return@forEachIndexed
                                val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                                val argsRaw = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                                val args = runCatching { json.parseToJsonElement(ProviderPayloadLimits.checkedJson(argsRaw)) as? JsonObject }.getOrNull()
                                    ?: throw IllegalArgumentException("Gemini tool input must be a JSON object")
                                add(buildJsonObject { put("functionCall", buildJsonObject {
                                    put("id", call["id"]?.jsonPrimitive?.contentOrNull ?: "gemini_tool_$index")
                                    put("name", name); put("args", args)
                                }) })
                            }
                        })
                    })
                    "tool" -> {
                        val id = msg["tool_call_id"]?.jsonPrimitive?.contentOrNull
                            ?: throw IllegalArgumentException("Gemini tool result is missing tool_call_id")
                        val name = callNames[id] ?: throw IllegalArgumentException("Gemini tool result has unknown tool_call_id")
                        val output = msg["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        add(buildJsonObject { put("role", "user"); put("parts", buildJsonArray { add(buildJsonObject {
                            put("functionResponse", buildJsonObject {
                                if (id in nativeIds) put("id", id)
                                put("name", name); put("response", buildJsonObject { put("output", output) })
                            })
                        }) }) })
                    }
                    else -> throw IllegalArgumentException("Unsupported message role for Gemini")
                }
            }
        }
        val grouped = mutableListOf<JsonObject>()
        entries.forEach { raw ->
            val entry = raw.jsonObject
            val last = grouped.lastOrNull()
            fun isResult(value: JsonObject?) = value?.get("role") == JsonPrimitive("user") &&
                (value["parts"] as? JsonArray)?.let { parts -> parts.isNotEmpty() && parts.all { (it as? JsonObject)?.containsKey("functionResponse") == true } } == true
            if (isResult(last) && isResult(entry)) {
                grouped[grouped.lastIndex] = JsonObject(last!! + ("parts" to JsonArray(last["parts"]!!.jsonArray + entry["parts"]!!.jsonArray)))
            } else grouped += entry
        }
        return JsonArray(grouped)
    }

    private fun contentParts(content: JsonElement?, allowImages: Boolean): JsonArray = buildJsonArray {
        when (content) {
            is JsonPrimitive -> if (content.content.isNotEmpty()) add(buildJsonObject { put("text", content.content) })
            is JsonArray -> content.forEach { raw ->
                val part = raw as? JsonObject ?: return@forEach
                when (part["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> add(buildJsonObject { put("text", part["text"]?.jsonPrimitive?.contentOrNull.orEmpty()) })
                    "image_url" -> {
                        require(allowImages) { "Images are only supported in user messages" }
                        add(convertImage(part))
                    }
                    else -> throw IllegalArgumentException("Unsupported multimodal content block for Gemini")
                }
            }
            null -> Unit
            else -> throw IllegalArgumentException("Unsupported message content for Gemini")
        }
    }

    private fun convertImage(part: JsonObject): JsonObject {
        val url = (part["image_url"] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Gemini image block is missing URL")
        val match = DATA_IMAGE.matchEntire(url) ?: throw IllegalArgumentException("Gemini image input requires a staged data image")
        return buildJsonObject { put("inlineData", buildJsonObject {
            put("mimeType", match.groupValues[1]); put("data", match.groupValues[2])
        }) }
    }

    private fun convertTools(tools: JsonArray?): JsonArray? {
        if (tools == null || tools.isEmpty()) return null
        val declarations = buildJsonArray {
            tools.forEach { raw ->
                val wrapper = raw as? JsonObject
                    ?: throw IllegalArgumentException("Google function tool must be an object")
                require(wrapper["type"] == JsonPrimitive("function")) { "Unsupported Google tool type" }
                val fn = wrapper["function"] as? JsonObject
                    ?: throw IllegalArgumentException("Google function tool is missing function")
                val name = (fn["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IllegalArgumentException("Google function tool is missing a string name")
                add(buildJsonObject {
                    put("name", name); fn["description"]?.let { put("description", it) }
                    put("parametersJsonSchema", ProviderToolSchema.parameters(fn["parameters"], name))
                })
            }
        }
        return if (declarations.isEmpty()) null else buildJsonArray { add(buildJsonObject { put("functionDeclarations", declarations) }) }
    }

    private fun extractText(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.content
        is JsonArray -> content.mapNotNull { raw ->
            val obj = raw as? JsonObject ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") obj["text"]?.jsonPrimitive?.contentOrNull else null
        }.joinToString("\n")
        else -> ""
    }

    private fun normalized(candidate: JsonObject?, usage: JsonObject?, nativeReason: String?): String {
        val parts = (((candidate?.get("content") as? JsonObject)?.get("parts")) as? JsonArray).orEmpty()
        val text = buildString { parts.forEach { raw ->
            val part = raw as? JsonObject ?: return@forEach
            if (part["thought"] != JsonPrimitive(true)) part["text"]?.jsonPrimitive?.contentOrNull?.let(::append)
        } }
        val tools = buildJsonArray {
            var index = 0
            parts.forEach { raw ->
                val fc = (raw as? JsonObject)?.get("functionCall") as? JsonObject ?: return@forEach
                val name = fc["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val id = fc["id"]?.jsonPrimitive?.contentOrNull ?: "gemini_tool_$index"
                add(openAiToolCall(index, id, name, (fc["args"] as? JsonObject ?: buildJsonObject {}).toString()))
                index++
            }
        }
        return normalizedFromParts(text, tools, candidate?.get("finishReason")?.jsonPrimitive?.contentOrNull, usage, JsonArray(parts), nativeReason)
    }

    private fun normalizedFromParts(text: String, tools: JsonArray, stop: String?, usage: JsonObject?, continuationParts: JsonArray, nativeReason: String?): String = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            safeNativeCompletionReason(nativeReason)?.let { put(NATIVE_FINISH_REASON_FIELD, it) }
            put("index", 0); put("message", buildJsonObject {
                put("role", "assistant"); put("content", text)
                if (tools.isNotEmpty() && stop == "STOP") {
                    validateContinuation(continuationParts, tools, Json)
                    put("tool_calls", tools)
                    put(PRIVATE_CONTENT_FIELD, continuationParts)
                }
            })
            put("finish_reason", when {
                tools.isNotEmpty() && stop == "STOP" -> JsonPrimitive("tool_calls")
                stop == "MAX_TOKENS" -> JsonPrimitive("length")
                stop == null -> JsonNull
                stop == "STOP" -> JsonPrimitive("stop")
                else -> JsonPrimitive("content_filter")
            })
        }) })
        usage?.let { put("usage", normalizeUsage(it)) }
    }.toString()

    /** Uses exactly the same native selection and validation as request conversion. */
    internal fun validatedNativeContent(message: JsonObject, json: Json): JsonArray? {
        val native = message[PRIVATE_CONTENT_FIELD] as? JsonArray ?: return null
        validateContinuation(native, message["tool_calls"] as? JsonArray, json)
        return native
    }

    private fun validateContinuation(parts: JsonArray, tools: JsonArray?, json: Json) {
        require(!tools.isNullOrEmpty()) { "Google private continuation requires tool_calls" }
        val functions = parts.mapNotNull { raw ->
            val part = raw as? JsonObject ?: throw IllegalArgumentException("Malformed Google private part")
            require(part.keys.all { it in setOf("text", "thought", "thoughtSignature", "functionCall") }) { "Unsupported Google private continuation part" }
            part["functionCall"] as? JsonObject
        }
        require(functions.size == tools.size) { "Google private continuation tool set mismatch" }
        functions.zip(tools).forEachIndexed { index, (actual, raw) ->
            val expected = raw as? JsonObject ?: throw IllegalArgumentException("Malformed normalized Google tool call")
            val fn = expected["function"] as? JsonObject ?: throw IllegalArgumentException("Malformed normalized Google tool function")
            val args = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
            val input = json.parseToJsonElement(ProviderPayloadLimits.checkedJson(args)) as? JsonObject ?: throw IllegalArgumentException("Google tool input must be an object")
            require(actual["name"] == fn["name"] && (actual["args"] ?: buildJsonObject {}) == input) { "Google private tool payload mismatch" }
            val id = actual["id"]?.jsonPrimitive?.contentOrNull ?: "gemini_tool_$index"
            require(expected["id"] == JsonPrimitive(id)) { "Google private tool id mismatch" }
        }
    }

    internal fun normalizeUsage(usage: JsonObject): JsonObject = buildJsonObject {

        val prompt = usageCount(usage, "promptTokenCount")
        val toolPrompt = usageCount(usage, "toolUsePromptTokenCount", optional = true)
        val candidates = usageCount(usage, "candidatesTokenCount")
        val thoughts = usageCount(usage, "thoughtsTokenCount", optional = true)
        if (prompt != null && toolPrompt != null) {
            checkedUsageSum(prompt, toolPrompt)?.let { put("prompt_tokens", it) }
        }
        if (candidates != null && thoughts != null) {
            checkedUsageSum(candidates, thoughts)?.let { put("completion_tokens", it) }
        }
        // Keep the provider total even when the breakdown is partial or disagrees; the tracker
        // independently validates its value and breakdown completeness.
        usage["totalTokenCount"]?.let { put("total_tokens", it) }
        val promptDetails = buildJsonObject {
            // Cached content is already part of promptTokenCount. Never add it again.
            usage["cachedContentTokenCount"]?.let { put("cached_tokens", it) }
            usage["toolUsePromptTokenCount"]?.let { put("tool_use_prompt_tokens", it) }
        }
        if (promptDetails.isNotEmpty()) put("prompt_tokens_details", promptDetails)
        usage["thoughtsTokenCount"]?.let { thoughtsCount ->
            put("completion_tokens_details", buildJsonObject { put("reasoning_tokens", thoughtsCount) })
        }
    }

    private fun usageCount(usage: JsonObject, field: String, optional: Boolean = false): Int? {
        val raw = usage[field] ?: return if (optional) 0 else null
        return (raw as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 }
    }

    private fun checkedUsageSum(vararg counts: Int): Int? =
        counts.sumOf { it.toLong() }.takeIf { it <= Int.MAX_VALUE }?.toInt()

    private fun openAiToolCall(index: Int, id: String, name: String, arguments: String): JsonObject = buildJsonObject {
        put("index", index); put("id", id); put("type", "function")
        put("function", buildJsonObject { put("name", name); put("arguments", arguments) })
    }

    private val DATA_IMAGE = Regex("^data:(image/(?:png|jpeg|gif|webp));base64,([A-Za-z0-9+/=]+)$")
}
