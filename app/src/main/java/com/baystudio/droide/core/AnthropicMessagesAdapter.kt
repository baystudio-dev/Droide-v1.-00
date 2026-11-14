package com.baystudio.droide.core

import kotlinx.serialization.json.*


internal object AnthropicMessagesAdapter {
    private const val DEFAULT_MAX_TOKENS = 8_192
    internal const val PRIVATE_CONTENT_FIELD = "_droide_anthropic_private_content"
    internal const val NATIVE_STOP_REASON_FIELD = "_droide_anthropic_stop_reason"

    // Adapter-owned diagnostics are protocol enums, never arbitrary provider prose.
    private val nativeStopReasons = setOf(
        "end_turn", "max_tokens", "stop_sequence", "tool_use", "pause_turn", "refusal",
        "model_context_window_exceeded",
    )

    internal fun safeNativeStopReason(reason: String?): String? = when {
        reason.isNullOrBlank() -> null
        reason in nativeStopReasons -> reason
        else -> "UNKNOWN"
    }

    fun encodeRequest(body: String, streaming: Boolean, json: Json, providerId: String = "anthropic"): String {
        val parsed = json.parseToJsonElement(ProviderPayloadLimits.checkedRequest(body)) as? JsonObject
            ?: throw IllegalArgumentException("LLM request body must be a JSON object")
        val root = ProviderToolSchema.normalizeRequest(parsed)
        val model = (root["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("Anthropic request requires a string model")
        val thinking = root["thinking"]?.let { raw ->
            val config = raw as? JsonObject
                ?: throw IllegalArgumentException("Anthropic thinking must be an object for model $model")
            validateThinkingConfig(config, model)
        }
        val sourceMessages = root["messages"] as? JsonArray ?: JsonArray(emptyList())
        val system = systemContent(sourceMessages)
        val messages = convertMessages(sourceMessages, json)
        return buildJsonObject {
            put("model", model)
            put("max_tokens", resolvedMaxTokens(root, thinking, providerId, model))
            if (system != null) put("system", system)
            put("messages", messages)
            convertTools(root["tools"] as? JsonArray)?.let { tools ->
                put("tools", tools)
                when (val choice = root["tool_choice"]) {
                    is JsonPrimitive -> if (choice.contentOrNull == "auto") {
                        put("tool_choice", buildJsonObject { put("type", "auto") })
                    }
                    is JsonObject -> put("tool_choice", choice)
                    else -> Unit
                }
            }
            thinking?.let { put("thinking", it) }
            (root["output_config"] as? JsonObject)?.let { put("output_config", it) }
            

            if (thinking == null) {
                (root["temperature"] as? JsonPrimitive)?.let { put("temperature", it) }
                (root["top_p"] as? JsonPrimitive)?.let { put("top_p", it) }
            }
            put("stream", streaming)
        }.toString()
    }

    fun normalizeBuffered(body: String, json: Json): String {
        val root = json.parseToJsonElement(ProviderPayloadLimits.checkedJson(body)) as? JsonObject
            ?: throw IllegalStateException("Malformed Anthropic response")
        root["error"]?.takeUnless { it == JsonNull }?.let { throw ProviderErrorPayloadException(it) }
        val stopReason = root["stop_reason"]?.jsonPrimitive?.contentOrNull
        val content = root["content"] as? JsonArray ?: JsonArray(emptyList())
        val text = buildString {
            content.forEach { raw ->
                val block = raw as? JsonObject ?: return@forEach
                if (block["type"]?.jsonPrimitive?.contentOrNull == "text") {
                    append(block["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                }
            }
        }
        val toolCalls = buildJsonArray {
            content.forEachIndexed { index, raw ->
                val block = raw as? JsonObject ?: return@forEachIndexed
                if (block["type"]?.jsonPrimitive?.contentOrNull != "tool_use") return@forEachIndexed
                val id = block["id"]?.jsonPrimitive?.contentOrNull ?: "anthropic_tool_$index"
                val name = block["name"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                val input = block["input"] as? JsonObject
                require(stopReason != "tool_use" || input != null) { "Anthropic tool input must be a JSON object" }
                add(openAiToolCall(index, id, name, (input ?: buildJsonObject {}).toString()))
            }
        }
        return normalizedResponse(
            id = root["id"]?.jsonPrimitive?.contentOrNull,
            model = root["model"]?.jsonPrimitive?.contentOrNull,
            text = text,
            tools = toolCalls,
            stopReason = stopReason,
            usage = root["usage"] as? JsonObject,
            hasNativeToolBlocks = content.any { (it as? JsonObject)?.get("type") == JsonPrimitive("tool_use") },
            // Incomplete/rejected native blocks are not replayable. Match the
            // streaming path so their private validation cannot discard public
            // partial text and reported usage before outcome admission.
            privateContent = if (stopReason == "tool_use") privateContinuationContent(content, toolCalls, json) else null,
        )
    }

    class StreamAccumulator(private val json: Json) {
        private val capacity = ProviderPayloadLimits.RetainedBudget()
        val retainedRevision: Long get() = capacity.revision
        private data class ToolParts(
            var id: String = "",
            var name: String = "",
            val arguments: StringBuilder = StringBuilder(),
        )

        private data class RawBlock(
            var type: String = "",
            var id: String = "",
            var name: String = "",
            val text: StringBuilder = StringBuilder(),
            val arguments: StringBuilder = StringBuilder(),
            val thinking: StringBuilder = StringBuilder(),
            val signature: StringBuilder = StringBuilder(),
            var redactedData: String = "",
            var toolInputStartedAsObject: Boolean = false,
        )

        private val text = StringBuilder()
        private val rawBlocks = sortedMapOf<Int, RawBlock>()
        private val tools = sortedMapOf<Int, ToolParts>()
        private var responseId: String? = null
        private var model: String? = null
        private var stopReason: String? = null
        private var usage: JsonObject? = null

        fun accept(payload: String): List<LlmStreamEvent> {
            capacity.validateEvent(payload)
            val root = runCatching { json.parseToJsonElement(payload).jsonObject }
                .getOrElse { throw IllegalStateException("Invalid Anthropic SSE JSON") }
            if (root["type"]?.jsonPrimitive?.contentOrNull == "error") {
                throw ProviderErrorPayloadException(root["error"]?.takeUnless { it == JsonNull } ?: root)
            }
            when (root["type"]?.jsonPrimitive?.contentOrNull) {
                "message_start" -> {
                    val message = root["message"] as? JsonObject
                    message?.get("id")?.jsonPrimitive?.contentOrNull?.let { capacity.replace("id", it.length.toLong()); responseId = it }
                    message?.get("model")?.jsonPrimitive?.contentOrNull?.let { capacity.replace("model", it.length.toLong()); model = it }
                    captureUsage(message?.get("usage") as? JsonObject)
                }
                "content_block_start" -> {
                    val index = root["index"]?.jsonPrimitive?.intOrNull ?: rawBlocks.size
                    val block = root["content_block"] as? JsonObject ?: return emptyList()
                    val type = block["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val raw = rawBlock(index)
                    capacity.replace("block:$index:type", type.length.toLong())
                    raw.type = type
                    when (type) {
                        "text" -> capacity.append(raw.text, block["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        "thinking" -> {
                            capacity.append(raw.thinking, block["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty())
                            capacity.append(raw.signature, block["signature"]?.jsonPrimitive?.contentOrNull.orEmpty())
                            return listOf(LlmStreamEvent.Thinking)
                        }
                        "redacted_thinking" -> {
                            val data = block["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            capacity.replace("block:$index:redacted", data.length.toLong())
                            raw.redactedData = data
                        }
                        "tool_use" -> {
                            capacity.replace("block:$index:identity",
                                (block["id"]?.jsonPrimitive?.contentOrNull.orEmpty().length.toLong() +
                                    block["name"]?.jsonPrimitive?.contentOrNull.orEmpty().length) * 2)
                            raw.id = block["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            raw.name = block["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val initial = block["input"] as? JsonObject
                            raw.toolInputStartedAsObject = initial != null
                            if (initial != null && initial.isNotEmpty()) capacity.append(raw.arguments, initial.toString())
                            val part = tool(index)
                            part.id = raw.id
                            part.name = raw.name
                            if (initial != null && initial.isNotEmpty()) capacity.append(part.arguments, initial.toString())
                            return listOf(LlmStreamEvent.ToolCallDelta(index, part.id, part.name, null))
                        }
                    }
                }
                "content_block_delta" -> {
                    val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val delta = root["delta"] as? JsonObject ?: return emptyList()
                    return when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> {
                            val fragment = delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (fragment.isEmpty()) emptyList() else {
                                capacity.append(text, fragment)
                                capacity.append(rawBlock(index, "text").text, fragment)
                                listOf(LlmStreamEvent.TextDelta(fragment))
                            }
                        }
                        "input_json_delta" -> {
                            val fragment = delta["partial_json"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (fragment.isEmpty()) emptyList() else {
                                capacity.append(tool(index).arguments, fragment)
                                capacity.append(rawBlock(index, "tool_use").arguments, fragment)
                                listOf(LlmStreamEvent.ToolCallDelta(index, null, null, fragment))
                            }
                        }
                        "thinking_delta" -> {
                            capacity.append(rawBlock(index, "thinking").thinking, delta["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty())
                            emptyList()
                        }
                        "signature_delta" -> {
                            capacity.append(rawBlock(index, "thinking").signature, delta["signature"]?.jsonPrimitive?.contentOrNull.orEmpty())
                            emptyList()
                        }
                        else -> emptyList() // private reasoning is retained for provider continuation, never rendered
                    }
                }
                "message_delta" -> {
                    val delta = root["delta"] as? JsonObject
                    delta?.get("stop_reason")?.takeUnless { it == JsonNull }?.let { reason ->
                        check(reason is JsonPrimitive && reason.isString) { "Anthropic stream returned malformed stop_reason" }
                        check(stopReason == null || stopReason == reason.content) { "Anthropic stream changed stop_reason" }
                        capacity.replace("stopReason", reason.content.length.toLong())
                        stopReason = reason.content
                    }
                    captureUsage(root["usage"] as? JsonObject)
                }
            }
            return emptyList()
        }

        fun completedResponse(): String {
            val calls = buildJsonArray {
                tools.forEach { (index, part) ->
                    add(openAiToolCall(index, part.id.ifBlank { "anthropic_tool_$index" }, part.name, part.arguments.toString().ifBlank { "{}" }))
                }
            }
            return normalizedResponse(
                responseId, model, text.toString(), calls, stopReason, usage,
                hasNativeToolBlocks = rawBlocks.values.any { it.type == "tool_use" },
                privateContent = if (stopReason == "tool_use") privateContinuationContent(rawContinuationContent(), calls, json) else null,
            )
        }

        private fun rawContinuationContent(): JsonArray = buildJsonArray {
            rawBlocks.forEach { (_, block) ->
                when (block.type) {
                    "text" -> add(buildJsonObject { put("type", "text"); put("text", block.text.toString()) })
                    "thinking" -> add(buildJsonObject {
                        put("type", "thinking")
                        put("thinking", block.thinking.toString())
                        put("signature", block.signature.toString())
                    })
                    "redacted_thinking" -> add(buildJsonObject {
                        put("type", "redacted_thinking"); put("data", block.redactedData)
                    })
                    "tool_use" -> {
                        check(block.toolInputStartedAsObject) { "Anthropic streamed tool input must start with a JSON object" }
                        val inputText = block.arguments.toString().ifBlank { "{}" }
                        val input = runCatching { json.parseToJsonElement(ProviderPayloadLimits.checkedJson(inputText)) as? JsonObject }.getOrNull()
                            ?: throw IllegalStateException("Anthropic streamed tool input is not a JSON object")
                        add(buildJsonObject {
                            put("type", "tool_use"); put("id", block.id); put("name", block.name); put("input", input)
                        })
                    }
                }
            }
        }

        private fun rawBlock(index: Int, type: String = ""): RawBlock = rawBlocks.getOrPut(index) {
            capacity.reserve(512)
            RawBlock(type = type)
        }

        private fun tool(index: Int): ToolParts = tools.getOrPut(index) {
            capacity.reserve(256)
            ToolParts()
        }

        private fun captureUsage(snapshot: JsonObject?) {
            if (snapshot == null) return
            // message_delta usage is cumulative. Replace reported fields and retain
            // earlier input/cache fields without manufacturing absent counts as zero.
            snapshot.forEach { (key, value) ->
                capacity.replace("usage:$key", key.length.toLong() + ProviderPayloadLimits.retainedJsonUnits(value))
            }
            usage = JsonObject(usage.orEmpty() + snapshot)
        }
    }

    private fun systemContent(messages: JsonArray): JsonElement? {
        val blocks = buildJsonArray {
            messages.forEach { raw ->
                val msg = raw as? JsonObject ?: return@forEach
                if (msg["role"]?.jsonPrimitive?.contentOrNull != "system") return@forEach
                when (val content = msg["content"]) {
                    is JsonPrimitive -> add(buildJsonObject { put("type", "text"); put("text", content.content) })
                    is JsonArray -> content.forEach { part ->
                        val obj = part as? JsonObject ?: return@forEach
                        if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") add(obj)
                    }
                    else -> Unit
                }
            }
        }
        if (blocks.isEmpty()) return null
        if (blocks.size == 1) {
            val one = blocks.first() as? JsonObject
            if (one != null && "cache_control" !in one) return one["text"] ?: blocks
        }
        return blocks
    }

    private fun convertMessages(messages: JsonArray, json: Json): JsonArray {
        val out = mutableListOf<JsonObject>()
        var i = 0
        while (i < messages.size) {
            val msg = messages[i] as? JsonObject
            if (msg == null) { i++; continue }
            when (msg["role"]?.jsonPrimitive?.contentOrNull) {
                "system" -> i++
                "tool" -> {
                    val results = buildJsonArray {
                        while (i < messages.size) {
                            val tool = messages[i] as? JsonObject ?: break
                            if (tool["role"]?.jsonPrimitive?.contentOrNull != "tool") break
                            val callId = tool["tool_call_id"]?.jsonPrimitive?.contentOrNull
                                ?: throw IllegalArgumentException("Anthropic tool result is missing tool_call_id")
                            val content = tool["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            add(buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", callId)
                                put("content", content)
                            })
                            i++
                        }
                    }
                    out += buildJsonObject { put("role", "user"); put("content", results) }
                }
                "assistant" -> {
                    val calls = msg["tool_calls"] as? JsonArray
                    val private = validatedNativeContent(msg, json)
                    val blocks = if (private != null) {
                        private
                    } else buildJsonArray {
                        appendContentBlocks(this, msg["content"], allowImages = false)
                        calls?.forEachIndexed { index, raw ->
                            val call = raw as? JsonObject ?: return@forEachIndexed
                            val fn = call["function"] as? JsonObject ?: return@forEachIndexed
                            val id = call["id"]?.jsonPrimitive?.contentOrNull ?: "tool_$index"
                            val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                            val args = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                            val input = runCatching { json.parseToJsonElement(ProviderPayloadLimits.checkedJson(args)) as? JsonObject }.getOrNull()
                                ?: throw IllegalArgumentException("Anthropic tool input must be a JSON object")
                            add(buildJsonObject {
                                put("type", "tool_use"); put("id", id); put("name", name); put("input", input)
                            })
                        }
                    }
                    out += buildJsonObject { put("role", "assistant"); put("content", blocks) }
                    i++
                }
                "user" -> {
                    val blocks = buildJsonArray { appendContentBlocks(this, msg["content"], allowImages = true) }
                    out += buildJsonObject { put("role", "user"); put("content", blocks) }
                    i++
                }
                else -> throw IllegalArgumentException("Unsupported message role for Anthropic")
            }
        }
        return JsonArray(out)
    }

    private fun appendContentBlocks(target: JsonArrayBuilder, content: JsonElement?, allowImages: Boolean) {
        when (content) {
            is JsonPrimitive -> if (content.content.isNotEmpty()) target.add(buildJsonObject { put("type", "text"); put("text", content.content) })
            is JsonArray -> content.forEach { raw ->
                val part = raw as? JsonObject ?: return@forEach
                when (part["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> target.add(buildJsonObject {
                        put("type", "text"); put("text", part["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        part["cache_control"]?.let { put("cache_control", it) }
                    })
                    "image_url" -> {
                        require(allowImages) { "Images are only supported in user messages" }
                        target.add(convertImage(part))
                    }
                    else -> throw IllegalArgumentException("Unsupported multimodal content block for Anthropic")
                }
            }
            null -> Unit
            else -> throw IllegalArgumentException("Unsupported message content for Anthropic")
        }
    }

    private fun convertImage(part: JsonObject): JsonObject {
        val url = (part["image_url"] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Anthropic image block is missing URL")
        val match = DATA_IMAGE.matchEntire(url) ?: throw IllegalArgumentException("Anthropic image input requires a staged data image")
        val media = match.groupValues[1]
        val data = match.groupValues[2]
        return buildJsonObject {
            put("type", "image")
            put("source", buildJsonObject {
                put("type", "base64"); put("media_type", media); put("data", data)
            })
        }
    }

    private fun convertTools(tools: JsonArray?): JsonArray? {
        if (tools == null) return null
        return buildJsonArray {
            tools.forEach { raw ->
                val wrapper = raw as? JsonObject
                    ?: throw IllegalArgumentException("Anthropic function tool must be an object")
                require(wrapper["type"] == JsonPrimitive("function")) { "Unsupported Anthropic tool type" }
                val fn = wrapper["function"] as? JsonObject
                    ?: throw IllegalArgumentException("Anthropic function tool is missing function")
                val name = (fn["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IllegalArgumentException("Anthropic function tool is missing a string name")
                add(buildJsonObject {
                    put("name", name)
                    fn["description"]?.let { put("description", it) }
                    put("input_schema", ProviderToolSchema.parameters(fn["parameters"], name))
                })
            }
        }
    }

    private fun resolvedMaxTokens(root: JsonObject, thinking: JsonObject?, providerId: String, model: String): JsonPrimitive {
        val raw = ProviderRequestBudget.explicitOutputTokens(root)?.let(::JsonPrimitive)
        val requested = if (raw == null) DEFAULT_MAX_TOKENS else {
            val value = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            require(value != null && value > 0) { "Anthropic max_tokens must be a positive integer for model $model" }
            value
        }
        val cap = ReasoningSupport.modelOutputLimit(providerId, model)
        require(raw == null || cap == null || requested <= cap) {
            "Anthropic max_tokens $requested exceeds model $model output limit $cap"
        }
        val budget = thinking?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "enabled" }
            ?.get("budget_tokens")?.jsonPrimitive?.intOrNull
        // Adjust only the implicit default. Explicit max_tokens and thinking budgets are user contracts.
        val desired = if (raw == null && budget != null) maxOf(requested.toLong(), budget.toLong() + 4_096L) else requested.toLong()
        val maximum = if (raw == null && cap != null) minOf(desired, cap.toLong()) else desired
        require(maximum <= Int.MAX_VALUE) { "Anthropic thinking budget is too large for model $model" }
        require(budget == null || budget < maximum) {
            "Anthropic thinking budget_tokens $budget must be below max_tokens $maximum for model $model"
        }
        return JsonPrimitive(maximum.toInt())
    }

    private fun validateThinkingConfig(thinking: JsonObject, model: String): JsonObject {
        return when (thinking["type"]?.jsonPrimitive?.contentOrNull) {
            "adaptive" -> {
                require(ReasoningSupport.anthropicThinkingMode(model) == ReasoningSupport.AnthropicThinkingMode.ADAPTIVE) {
                    "Anthropic adaptive thinking is not supported for model $model"
                }
                require("budget_tokens" !in thinking) { "Anthropic adaptive thinking does not accept budget_tokens for model $model" }
                thinking
            }
            "enabled" -> {
                require(ReasoningSupport.anthropicThinkingMode(model) != null) { "Anthropic manual thinking is not supported for model $model" }
                val budget = (thinking["budget_tokens"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                    ?: throw IllegalArgumentException("Anthropic manual thinking requires integer budget_tokens for model $model")
                require(budget >= 1_024) { "Anthropic thinking budget_tokens must be >= 1024 for model $model" }
                thinking
            }
            else -> throw IllegalArgumentException("Unsupported Anthropic thinking configuration")
        }
    }

    private fun privateContinuationContent(content: JsonArray, tools: JsonArray, json: Json): JsonArray? {
        if (tools.isEmpty()) return null
        val hasThinking = content.any { raw ->
            val type = (raw as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
            type == "thinking" || type == "redacted_thinking"
        }
        if (!hasThinking) return null
        validatePrivateContinuation(content, tools, json)
        return content
    }

    /** Shared with context accounting; encoding remains the final validation authority. */
    internal fun validatedNativeContent(message: JsonObject, json: Json): JsonArray? {
        val native = message[PRIVATE_CONTENT_FIELD] as? JsonArray ?: return null
        validatePrivateContinuation(native, message["tool_calls"] as? JsonArray, json)
        return native
    }

    private fun validatePrivateContinuation(content: JsonArray, calls: JsonArray?, json: Json) {
        require(calls != null && calls.isNotEmpty()) { "Anthropic private continuation requires tool_calls" }
        val expected = linkedMapOf<String, Pair<String, JsonObject>>()
        calls.forEachIndexed { index, raw ->
            val call = raw as? JsonObject ?: throw IllegalArgumentException("Malformed normalized Anthropic tool call")
            val id = call["id"]?.jsonPrimitive?.contentOrNull ?: "tool_$index"
            val fn = call["function"] as? JsonObject ?: throw IllegalArgumentException("Malformed normalized Anthropic tool function")
            val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("Anthropic tool call missing name")
            val args = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
            val input = runCatching { json.parseToJsonElement(ProviderPayloadLimits.checkedJson(args)) as? JsonObject }.getOrNull()
                ?: throw IllegalArgumentException("Anthropic tool input must be a JSON object")
            expected[id] = name to input
        }
        var thinkingSeen = false
        val actualIds = linkedSetOf<String>()
        content.forEach { raw ->
            val block = raw as? JsonObject ?: throw IllegalArgumentException("Malformed Anthropic private content block")
            when (block["type"]?.jsonPrimitive?.contentOrNull) {
                "thinking" -> {
                    block["thinking"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("Anthropic thinking block missing thinking")
                    block["signature"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("Anthropic thinking block missing signature")
                    thinkingSeen = true
                }
                "redacted_thinking" -> {
                    block["data"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("Anthropic redacted_thinking block missing data")
                    thinkingSeen = true
                }
                "text" -> block["text"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("Anthropic text block missing text")
                "tool_use" -> {
                    val id = block["id"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("Anthropic private tool_use missing id")
                    val name = block["name"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("Anthropic private tool_use missing name")
                    val input = block["input"] as? JsonObject
                        ?: throw IllegalArgumentException("Anthropic private tool_use input must be an object")
                    val wanted = expected[id] ?: throw IllegalArgumentException("Anthropic private tool_use id does not match normalized tool_calls")
                    require(wanted.first == name && wanted.second == input) { "Anthropic private tool_use payload mismatch" }
                    actualIds += id
                }
                else -> throw IllegalArgumentException("Unsupported Anthropic private continuation block")
            }
        }
        require(thinkingSeen) { "Anthropic private continuation must contain thinking or redacted_thinking" }
        require(actualIds == expected.keys) { "Anthropic private continuation tool set mismatch" }
    }

    private fun openAiToolCall(index: Int, id: String, name: String, arguments: String): JsonObject = buildJsonObject {
        put("index", index); put("id", id); put("type", "function")
        put("function", buildJsonObject { put("name", name); put("arguments", arguments) })
    }

    private fun normalizedResponse(
        id: String?, model: String?, text: String, tools: JsonArray, stopReason: String?, usage: JsonObject?,
        privateContent: JsonArray? = null,
        hasNativeToolBlocks: Boolean = tools.isNotEmpty(),
    ): String = buildJsonObject {
        id?.let { put("id", it) }
        model?.let { put("model", it) }
        put("choices", buildJsonArray { add(buildJsonObject {
            put("index", 0)
            put("message", buildJsonObject {
                put("role", "assistant"); put("content", text)
                if (tools.isNotEmpty() && stopReason == "tool_use") put("tool_calls", tools)
                if (stopReason == "tool_use") privateContent?.let { put(PRIVATE_CONTENT_FIELD, it) }
            })
            val finishReason = when (stopReason) {
                "tool_use" -> JsonPrimitive("tool_calls")
                "end_turn", "stop_sequence" -> JsonPrimitive(if (hasNativeToolBlocks) "invalid_tool_turn" else "stop")
                "max_tokens" -> JsonPrimitive("length")
                null -> JsonNull
                else -> JsonPrimitive(stopReason)
            }
            put("finish_reason", finishReason)
            safeNativeStopReason(stopReason)?.let { put(NATIVE_STOP_REASON_FIELD, it) }
        }) })
        usage?.let { put("usage", normalizeUsage(it)) }
    }.toString()

    internal fun normalizeUsage(usage: JsonObject): JsonObject = buildJsonObject {
        // Native input_tokens excludes cache reads and writes; canonical prompt_tokens
        // includes them, matching the subset semantics used by the generic tracker.
        val input = usageCount(usage, "input_tokens")
        val output = usageCount(usage, "output_tokens")
        val cacheRead = usageCount(usage, "cache_read_input_tokens", optional = true)
        val cacheWrite = usageCount(usage, "cache_creation_input_tokens", optional = true)
        val prompt = if (input != null && cacheRead != null && cacheWrite != null)
            checkedUsageSum(input, cacheRead, cacheWrite) else null
        prompt?.let { put("prompt_tokens", it) }
        output?.let { put("completion_tokens", it) }
        if (prompt != null && output != null) {
            checkedUsageSum(prompt, output)?.let { put("total_tokens", it) }
        }
        val details = buildJsonObject {
            // Preserve present invalid details for downstream completeness validation.
            usage["cache_read_input_tokens"]?.let { put("cached_tokens", it) }
            usage["cache_creation_input_tokens"]?.let { put("cache_write_tokens", it) }
        }
        if (details.isNotEmpty()) put("prompt_tokens_details", details)
        usage["cost"]?.let { put("cost", it) }
        usage["cost_usd"]?.let { put("cost_usd", it) }
    }

    private fun usageCount(usage: JsonObject, field: String, optional: Boolean = false): Int? {
        val raw = usage[field] ?: return if (optional) 0 else null
        return (raw as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 }
    }

    private fun checkedUsageSum(vararg counts: Int): Int? =
        counts.sumOf { it.toLong() }.takeIf { it <= Int.MAX_VALUE }?.toInt()

    private val DATA_IMAGE = Regex("^data:(image/(?:png|jpeg|gif|webp));base64,([A-Za-z0-9+/=]+)$")
}
