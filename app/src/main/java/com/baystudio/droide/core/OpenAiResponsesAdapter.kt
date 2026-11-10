package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal object OpenAiResponsesAdapter {
    internal const val PRIVATE_CONTENT_FIELD = "_droide_openai_responses_output"
    private val requestFields = setOf("model", "messages", "tools", "tool_choice", "stream", "max_tokens",
        "max_completion_tokens", "max_output_tokens", "reasoning_effort", "temperature", "top_p", "parallel_tool_calls")

    fun encodeRequest(body: String, streaming: Boolean, json: Json): String {
        val parsed = json.parseToJsonElement(ProviderPayloadLimits.checkedRequest(body)) as? JsonObject ?: bad("request must be an object")
        require(parsed.keys.all { it in requestFields }) { "Unsupported OpenAI Responses request field" }
        val root = ProviderToolSchema.normalizeRequest(parsed)
        val model = string(root, "model")
        val messages = root["messages"] as? JsonArray ?: bad("messages must be an array")
        val tools = root["tools"] as? JsonArray
        return buildJsonObject {
            put("model", model)
            put("input", input(messages, json))
            put("store", false)
            put("include", buildJsonArray { add("reasoning.encrypted_content") })
            put("stream", streaming)
            if (tools != null) put("tools", JsonArray(tools.map { raw ->
                val wrapper = raw as? JsonObject ?: bad("tool must be an object")
                require(wrapper["type"] == JsonPrimitive("function")) { "OpenAI Responses supports function tools here" }
                val fn = wrapper["function"] as? JsonObject ?: bad("function tool is malformed")
                buildJsonObject {
                    put("type", "function"); put("name", string(fn, "name"))
                    fn["description"]?.let { require(it is JsonPrimitive && it.isString); put("description", it) }
                    put("parameters", ProviderToolSchema.parameters(fn["parameters"], string(fn, "name")))
                    val strict = fn["strict"] ?: JsonPrimitive(false)
                    require(strict is JsonPrimitive && strict.booleanOrNull != null) { "Invalid function strict flag" }
                    put("strict", strict)
                }
            }))
            root["tool_choice"]?.let { choice ->
                val translated = when (choice) {
                    is JsonPrimitive -> {
                        require(choice.isString && choice.content in setOf("auto", "none", "required")) { "Invalid Responses tool_choice" }
                        choice
                    }
                    is JsonObject -> {
                        require(choice["type"] == JsonPrimitive("function")) { "Invalid Responses tool_choice" }
                        val fn = choice["function"] as? JsonObject ?: bad("tool_choice function is missing")
                        val name = string(fn, "name")
                        require(tools.orEmpty().any { (it as? JsonObject)?.get("function")?.jsonObject?.get("name") == JsonPrimitive(name) }) { "Unknown tool_choice function" }
                        buildJsonObject { put("type", "function"); put("name", name) }
                    }
                    else -> bad("tool_choice is malformed")
                }
                put("tool_choice", translated)
            }
            val limits = listOf("max_tokens", "max_completion_tokens", "max_output_tokens").mapNotNull { root[it] }
            require(limits.distinct().size <= 1) { "Conflicting Responses output limits" }
            limits.firstOrNull()?.let {
                require(it is JsonPrimitive && !it.isString && it.longOrNull?.let { n -> n > 0 } == true) { "Invalid Responses output limit" }
                put("max_output_tokens", it)
            }
            root["reasoning_effort"]?.let {
                require(it is JsonPrimitive && it.isString && it.content in setOf("none", "minimal", "low", "medium", "high", "xhigh", "max")) { "Invalid Responses reasoning effort" }
                put("reasoning", buildJsonObject { put("effort", it) })
            }
            listOf("temperature", "top_p").forEach { key -> root[key]?.let {
                require(it is JsonPrimitive && !it.isString && it.doubleOrNull?.isFinite() == true) { "Invalid Responses $key" }
                put(key, it)
            } }
            root["parallel_tool_calls"]?.let {
                require(it is JsonPrimitive && it.booleanOrNull != null) { "Invalid Responses parallel_tool_calls" }
                put("parallel_tool_calls", it)
            }
        }.toString()
    }

    private fun input(messages: JsonArray, json: Json): JsonArray {
        val announced = mutableSetOf<String>()
        val resolved = mutableSetOf<String>()
        return buildJsonArray {
            messages.forEach { raw ->
                val msg = raw as? JsonObject ?: bad("message must be an object")
                when (val role = string(msg, "role")) {
                    "system", "developer", "user" -> {
                        require(msg[PRIVATE_CONTENT_FIELD] == null && msg["tool_calls"] == null) { "Only assistant messages can contain native output or tool calls" }
                        add(buildJsonObject { put("role", role); put("content", content(msg["content"], role == "user")) })
                    }
                    "assistant" -> {
                        val calls = when (val value = msg["tool_calls"]) {
                            null, JsonNull -> JsonArray(emptyList())
                            is JsonArray -> value
                            else -> bad("assistant tool_calls must be an array")
                        }
                        val native = validatedNativeContent(msg, json)
                        if (native != null) {
                            native.forEach(::add)
                        } else {
                            val text = messageText(msg["content"])
                            if (text.isNotEmpty()) add(buildJsonObject { put("role", "assistant"); put("content", text) })
                            calls.forEach { add(nativeCall(it, json)) }
                        }
                        calls.forEach { call ->
                            val id = string(call as? JsonObject ?: bad("tool call must be an object"), "id")
                            require(announced.add(id)) { "Duplicate Responses call_id in conversation" }
                        }
                    }
                    "tool" -> {
                        val id = string(msg, "tool_call_id")
                        require(id in announced && resolved.add(id)) { "Unknown or duplicate Responses tool result call_id" }
                        val output = msg["content"] as? JsonPrimitive ?: bad("tool output must be text")
                        require(output.isString) { "Responses tool output must be text" }
                        add(buildJsonObject { put("type", "function_call_output"); put("call_id", id); put("output", output) })
                    }
                    else -> bad("unsupported message role '$role'")
                }
            }
        }
    }

    private fun content(raw: JsonElement?, images: Boolean): JsonArray = when (raw) {
        is JsonPrimitive -> {
            require(raw.isString) { "Responses message content must be text" }
            buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", raw) }) }
        }
        is JsonArray -> JsonArray(raw.map { element ->
            val part = element as? JsonObject ?: bad("content block must be an object")
            when (string(part, "type")) {
                "text" -> buildJsonObject { put("type", "input_text"); put("text", string(part, "text", allowEmpty = true)) }
                "image_url" -> {
                    require(images) { "Responses images are only supported in user messages" }
                    val image = part["image_url"] as? JsonObject ?: bad("image_url must be an object")
                    val url = string(image, "url")
                    require(DATA_IMAGE.matches(url)) { "Responses image input requires a staged data image" }
                    val detail = image["detail"] ?: JsonPrimitive("auto")
                    require(detail is JsonPrimitive && detail.isString && detail.content in setOf("auto", "low", "high")) { "Invalid Responses image detail" }
                    buildJsonObject { put("type", "input_image"); put("image_url", url); put("detail", detail) }
                }
                else -> bad("unsupported content block")
            }
        })
        else -> bad("message content must be text or content blocks")
    }

    private fun messageText(raw: JsonElement?): String = when (raw) {
        null, JsonNull -> ""
        is JsonPrimitive -> { require(raw.isString) { "Assistant content must be text" }; raw.content }
        is JsonArray -> raw.joinToString("") { element ->
            val part = element as? JsonObject ?: bad("assistant content block must be an object")
            require(part["type"] == JsonPrimitive("text")) { "Assistant content must contain text only" }
            string(part, "text", allowEmpty = true)
        }
        else -> bad("assistant content must be text")
    }

    private fun nativeCall(raw: JsonElement, json: Json): JsonObject {
        val call = raw as? JsonObject ?: bad("tool call must be an object")
        require(call["type"] == JsonPrimitive("function")) { "Only function calls are supported" }
        val fn = call["function"] as? JsonObject ?: bad("tool call function is missing")
        val arguments = string(fn, "arguments")
        require(json.parseToJsonElement(ProviderPayloadLimits.checkedJson(arguments)) is JsonObject) { "Responses tool arguments must be a JSON object" }
        return buildJsonObject { put("type", "function_call"); put("call_id", string(call, "id")); put("name", string(fn, "name")); put("arguments", arguments) }
    }

    fun normalizeBuffered(body: String, json: Json): String {
        val response = json.parseToJsonElement(ProviderPayloadLimits.checkedJson(body)) as? JsonObject ?: throw IllegalStateException("Malformed OpenAI Responses response")
        return normalized(response, json)
    }

    /** Public assistant-item boundaries only, after native response validation. */

    fun publicTextSpans(message: JsonObject, publicText: String): List<AgentPublicTextSpan> {
        if (message["role"] != JsonPrimitive("assistant")) return emptyList()
        val content = message["content"] as? JsonPrimitive ?: return emptyList()
        if (!content.isString || content.content != publicText) return emptyList()
        val output = message[PRIVATE_CONTENT_FIELD] as? JsonArray ?: return emptyList()
        val spans = mutableListOf<AgentPublicTextSpan>()
        var offset = 0
        for (raw in output) {
            val item = raw as? JsonObject ?: return emptyList()
            when (item["type"]) {
                JsonPrimitive("reasoning"), JsonPrimitive("function_call") -> Unit
                JsonPrimitive("message") -> {
                    if (item["role"] != JsonPrimitive("assistant")) return emptyList()
                    val parts = item["content"] as? JsonArray ?: return emptyList()
                    val start = offset
                    for (rawPart in parts) {
                        val part = rawPart as? JsonObject ?: return emptyList()
                        val value = when (part["type"]) {
                            JsonPrimitive("output_text") -> part["text"]
                            JsonPrimitive("refusal") -> part["refusal"]
                            else -> return emptyList()
                        } as? JsonPrimitive ?: return emptyList()
                        if (!value.isString || value.content.length > publicText.length - offset ||
                            !publicText.regionMatches(offset, value.content, 0, value.content.length)) return emptyList()
                        offset += value.content.length
                    }
                    if (offset > start) {
                        if (spans.size == AgentPublicTextPresentation.MAX_SPANS) return emptyList()
                        spans += AgentPublicTextSpan(start, offset, publicPhase(item))
                    }
                }
                else -> return emptyList()
            }
        }
        return spans.takeIf { offset == publicText.length && AgentPublicTextPresentation.valid(publicText, it) }.orEmpty()
    }

    private fun publicPhase(item: JsonObject): ProviderPublicTextPhase = when {
        item["role"] != JsonPrimitive("assistant") -> ProviderPublicTextPhase.UNSPECIFIED
        item["phase"] == JsonPrimitive("commentary") -> ProviderPublicTextPhase.COMMENTARY
        item["phase"] == JsonPrimitive("final_answer") -> ProviderPublicTextPhase.FINAL_ANSWER
        else -> ProviderPublicTextPhase.UNSPECIFIED
    }

    private data class Projection(val text: String, val refusals: String, val calls: JsonArray)

    private fun project(output: JsonArray, json: Json, completeCalls: Boolean): Projection {
        val text = StringBuilder()
        val refusals = StringBuilder()
        val calls = mutableListOf<JsonObject>()
        val callIds = mutableSetOf<String>()
        output.forEach { raw ->
            val item = raw as? JsonObject ?: bad("output item must be an object")
            if (completeCalls) item["status"]?.takeUnless { it == JsonNull }?.let { status ->
                require(status == JsonPrimitive("completed")) {
                    "Responses completed envelope contains an unfinished output item"
                }
            }
            when (string(item, "type")) {
                "reasoning" -> {
                    require(item.keys.all { it in setOf("type", "id", "summary", "content", "encrypted_content", "status") }) { "Unsupported Responses reasoning item" }
                    item["encrypted_content"]?.let { require(it is JsonPrimitive && (it.isString || it == JsonNull)) { "Invalid encrypted reasoning" } }
                }
                "message" -> {
                    require(item["role"] == JsonPrimitive("assistant")) { "Responses output message must be assistant" }
                    val parts = item["content"] as? JsonArray ?: bad("output message content must be an array")
                    parts.forEach { rawPart ->
                        val part = rawPart as? JsonObject ?: bad("output content must be an object")
                        when (string(part, "type")) {
                            "output_text" -> text.append(string(part, "text", allowEmpty = true))
                            "refusal" -> { val refusal = string(part, "refusal", allowEmpty = true); text.append(refusal); refusals.append(refusal) }
                            else -> bad("unsupported Responses output content")
                        }
                    }
                }
                "function_call" -> {
                    val id = string(item, "call_id")
                    require(callIds.add(id)) { "Duplicate Responses output call_id" }
                    if (completeCalls) {
                        val arguments = string(item, "arguments")
                        require(json.parseToJsonElement(ProviderPayloadLimits.checkedJson(arguments)) is JsonObject) { "Responses tool arguments must be a JSON object" }
                        calls += buildJsonObject {
                            put("index", calls.size); put("id", id); put("type", "function")
                            put("function", buildJsonObject { put("name", string(item, "name")); put("arguments", arguments) })
                        }
                    }
                }
                else -> bad("unsupported Responses output item")
            }
        }
        return Projection(text.toString(), refusals.toString(), JsonArray(calls))
    }

    internal fun validatedNativeContent(message: JsonObject, json: Json): JsonArray? {
        val raw = message[PRIVATE_CONTENT_FIELD] ?: return null
        val native = raw as? JsonArray ?: bad("native continuation must be an array")
        validateContinuation(native, message, json)
        return native
    }

    private fun validateContinuation(items: JsonArray, message: JsonObject, json: Json) {
        val actual = project(items, json, completeCalls = true)
        require(actual.text == messageText(message["content"])) { "Responses private continuation text mismatch" }
        val expected = (message["tool_calls"] as? JsonArray).orEmpty().map { nativeCall(it, json) }
        val native = items.filter { (it as? JsonObject)?.get("type") == JsonPrimitive("function_call") }
        require(native.size == expected.size) { "Responses private continuation tool set mismatch" }
        native.zip(expected).forEach { (raw, expectedCall) ->
            val item = raw.jsonObject
            require(item["call_id"] == expectedCall["call_id"] && item["name"] == expectedCall["name"] &&
                json.parseToJsonElement(ProviderPayloadLimits.checkedJson(string(item, "arguments"))) == json.parseToJsonElement(ProviderPayloadLimits.checkedJson(string(expectedCall, "arguments")))) { "Responses private continuation tool payload mismatch" }
        }
    }

    private fun normalized(response: JsonObject, json: Json): String {
        response["error"]?.takeUnless { it == JsonNull }?.let { throw ProviderErrorPayloadException(it) }
        val status = string(response, "status")
        val incomplete = (response["incomplete_details"] as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull
        when (status) {
            "completed" -> Unit
            "incomplete" -> if (incomplete !in setOf("max_output_tokens", "content_filter")) throw IllegalStateException("OpenAI Responses incomplete: ${incomplete.orEmpty()}")
            else -> throw IllegalStateException("OpenAI Responses did not complete: $status")
        }
        val output = response["output"] as? JsonArray ?: throw IllegalStateException("OpenAI Responses is missing output")
        val projected = project(output, json, completeCalls = status == "completed")
        return buildJsonObject {
            response["id"]?.let { put("id", it) }; response["model"]?.let { put("model", it) }
            put("choices", buildJsonArray { add(buildJsonObject {
                put("index", 0)
                put("message", buildJsonObject {
                    put("role", "assistant"); put("content", projected.text)
                    if (projected.refusals.isNotEmpty()) put("refusal", projected.refusals)
                    if (projected.calls.isNotEmpty()) put("tool_calls", projected.calls)
                    // An incomplete call cannot be replayed as a completed tool request.
                    if (status == "completed" || output.none { (it as? JsonObject)?.get("type") == JsonPrimitive("function_call") }) put(PRIVATE_CONTENT_FIELD, output)
                })
                put("finish_reason", when {
                    incomplete == "max_output_tokens" -> "length"
                    incomplete == "content_filter" || projected.refusals.isNotEmpty() -> "content_filter"
                    projected.calls.isNotEmpty() -> "tool_calls"
                    else -> "stop"
                })
            }) })
            (response["usage"] as? JsonObject)?.let { usage -> put("usage", buildJsonObject {
                usage["input_tokens"]?.let { put("prompt_tokens", it) }
                usage["output_tokens"]?.let { put("completion_tokens", it) }
                usage["total_tokens"]?.let { put("total_tokens", it) }
                usage["input_tokens_details"]?.let { put("prompt_tokens_details", it) }
                usage["output_tokens_details"]?.let { put("completion_tokens_details", it) }
            }) }
        }.toString()
    }

    class StreamAccumulator(private val json: Json) {
        private val capacity = ProviderPayloadLimits.RetainedBudget()
        val retainedRevision: Long get() = capacity.revision
        private data class Tool(val ordinal: Int, var id: String? = null, var name: String? = null, val arguments: StringBuilder = StringBuilder())
        private val tools = linkedMapOf<Int, Tool>()
        private val text = mutableMapOf<Pair<Int, Int>, StringBuilder>()
        private val publicPhases = mutableMapOf<Int, ProviderPublicTextPhase>()
        private var terminal: JsonObject? = null
        private val reasoningItems = mutableSetOf<Int>()

        fun accept(payload: String): List<LlmStreamEvent> {
            capacity.validateEvent(payload)
            if (payload == "[DONE]") return emptyList()
            val event = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
                ?: throw IllegalStateException("Invalid OpenAI Responses SSE JSON")
            val type = string(event, "type")
            if (type == "error" || type == "response.failed") {
                val response = event["response"] as? JsonObject
                throw ProviderErrorPayloadException(response?.get("error")?.takeUnless { it == JsonNull }
                    ?: event["error"]?.takeUnless { it == JsonNull } ?: event)
            }
            // Gateways may send typed heartbeats while the model is reasoning. They carry no answer, usage
            // or completion evidence and must not enter replay.
            if (type in HEARTBEAT_TYPES) {
                event["error"]?.takeUnless { it == JsonNull }?.let { throw ProviderErrorPayloadException(it) }
                require(event.keys.none { it in RESPONSE_PAYLOAD_FIELDS }) {
                    "OpenAI Responses heartbeat contains response data"
                }
                return emptyList()
            }
            require(terminal == null || type in setOf("response.completed", "response.incomplete")) { "Responses stream continued after terminal event" }
            val events = mutableListOf<LlmStreamEvent>()
            when (type) {
                "response.output_text.delta", "response.refusal.delta" -> {
                    val key = index(event) to contentIndex(event)
                    val delta = string(event, "delta", allowEmpty = true)
                    capacity.append(text.getOrPut(key) { capacity.reserve(256); StringBuilder() }, delta)
                    if (delta.isNotEmpty()) events += LlmStreamEvent.TextDelta(delta, publicPhases[key.first] ?: ProviderPublicTextPhase.UNSPECIFIED)
                }
                "response.output_text.done", "response.refusal.done" -> {
                    val full = string(event, if (type == "response.refusal.done") "refusal" else "text", allowEmpty = true)
                    textSuffix(index(event), contentIndex(event), full, events)
                }
                "response.output_item.added", "response.output_item.done" -> {
                    val item = event["item"] as? JsonObject ?: bad("SSE output item is missing")
                    val itemIndex = index(event)
                    if (item["type"] == JsonPrimitive("reasoning")) {
                        val first = if (itemIndex in reasoningItems) false else {
                            capacity.reserve(128)
                            reasoningItems.add(itemIndex)
                        }
                        if (first && type == "response.output_item.added") events += LlmStreamEvent.Thinking
                    }
                    captureItem(itemIndex, item, events)
                }
                "response.function_call_arguments.delta" -> {
                    val tool = tool(index(event))
                    val delta = string(event, "delta", allowEmpty = true)
                    capacity.append(tool.arguments, delta)
                    if (delta.isNotEmpty()) events += LlmStreamEvent.ToolCallDelta(tool.ordinal, null, null, delta)
                }
                "response.function_call_arguments.done" -> {
                    val tool = tool(index(event))
                    argumentSuffix(tool, string(event, "arguments", allowEmpty = true), events)
                }
                "response.completed", "response.incomplete" -> {
                    val response = event["response"] as? JsonObject ?: bad("SSE terminal response is missing")
                    require(response["status"] == JsonPrimitive(type.removePrefix("response."))) {
                        "Responses terminal event and response status disagree"
                    }
                    normalized(response, json)
                    val output = response["output"] as? JsonArray ?: bad("SSE terminal output is missing")
                    output.forEachIndexed { index, raw -> captureItem(index, raw as? JsonObject ?: bad("SSE terminal output item is malformed"), events) }
                    require(terminal == null || terminal == response) { "Conflicting Responses terminal events" }
                    capacity.replace("terminal", ProviderPayloadLimits.retainedJsonUnits(response))
                    terminal = response
                }
                "response.created", "response.in_progress", "response.content_part.added", "response.content_part.done",
                "response.output_text.annotation.added", "response.reasoning_summary_part.added", "response.reasoning_summary_part.done",
                "response.reasoning_summary_text.delta", "response.reasoning_summary_text.done", "response.reasoning_text.delta", "response.reasoning_text.done" -> Unit
                else -> throw IllegalStateException("Unsupported OpenAI Responses SSE event: $type")
            }
            return events
        }

        private fun captureItem(index: Int, item: JsonObject, events: MutableList<LlmStreamEvent>) {
            val type = string(item, "type")
            // Malformed/custom streams may reuse an index. A prior message's phase cannot classify text
            // after that index has become a non-message item.
            if (type != "message") publicPhases.remove(index)
            when (type) {
                "function_call" -> {
                    val tool = tool(index)
                    val id = string(item, "call_id")
                    val name = string(item, "name")
                    require(tool.id == null || tool.id == id) { "Responses streamed call_id changed" }
                    require(tool.name == null || tool.name == name) { "Responses streamed tool name changed" }
                    if (tool.id == null || tool.name == null) events += LlmStreamEvent.ToolCallDelta(tool.ordinal, id, name, null)
                    capacity.replace("tool:$index:identity", id.length.toLong() + name.length)
                    tool.id = id; tool.name = name
                    item["arguments"]?.let { argumentSuffix(tool, string(item, "arguments", allowEmpty = true), events) }
                }
                "message" -> {
                    capacity.replace("phase:$index", 128)
                    publicPhases[index] = publicPhase(item)
                    (item["content"] as? JsonArray).orEmpty().forEachIndexed { contentIndex, raw ->
                        val part = raw as? JsonObject ?: bad("SSE message part is malformed")
                        when (string(part, "type")) {
                            "output_text" -> textSuffix(index, contentIndex, string(part, "text", allowEmpty = true), events)
                            "refusal" -> textSuffix(index, contentIndex, string(part, "refusal", allowEmpty = true), events)
                            else -> bad("unsupported SSE message content")
                        }
                    }
                }
                "reasoning" -> Unit // Only completed terminal output is used for encrypted replay.
                else -> bad("unsupported SSE output item")
            }
        }

        private fun tool(index: Int): Tool = tools.getOrPut(index) {
            capacity.reserve(256)
            Tool(tools.size)
        }

        private fun argumentSuffix(tool: Tool, full: String, events: MutableList<LlmStreamEvent>) {
            require(full.startsWith(tool.arguments.toString())) { "Responses streamed tool arguments differ from completed output" }
            val suffix = full.substring(tool.arguments.length)
            capacity.append(tool.arguments, suffix)
            if (suffix.isNotEmpty()) events += LlmStreamEvent.ToolCallDelta(tool.ordinal, null, null, suffix)
        }

        private fun textSuffix(index: Int, contentIndex: Int, full: String, events: MutableList<LlmStreamEvent>) {
            val previous = text.getOrPut(index to contentIndex) { capacity.reserve(256); StringBuilder() }
            require(full.startsWith(previous.toString())) { "Responses streamed text differs from completed output" }
            val suffix = full.substring(previous.length)
            capacity.append(previous, suffix)
            if (suffix.isNotEmpty()) events += LlmStreamEvent.TextDelta(suffix, publicPhases[index] ?: ProviderPublicTextPhase.UNSPECIFIED)
        }

        private fun index(event: JsonObject): Int = event["output_index"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }
            ?: bad("SSE output_index is missing")
        private fun contentIndex(event: JsonObject): Int = event["content_index"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }
            ?: bad("SSE content_index is missing")

        fun completedResponse(): String = normalized(terminal ?: throw IllegalStateException("OpenAI Responses stream ended before terminal response"), json)
    }

    private fun string(value: JsonObject, key: String, allowEmpty: Boolean = false): String {
        val raw = value[key] as? JsonPrimitive ?: bad("$key must be a string")
        require(raw.isString && (allowEmpty || raw.content.isNotBlank())) { "OpenAI Responses $key must be a ${if (allowEmpty) "string" else "nonempty string"}" }
        return raw.content
    }

    private fun bad(reason: String): Nothing = throw IllegalArgumentException("OpenAI Responses $reason")
    private val HEARTBEAT_TYPES = setOf("keepalive", "ping", "heartbeat")
    private val RESPONSE_PAYLOAD_FIELDS = setOf(
        "response", "output", "item", "delta", "text", "content", "arguments", "tool_calls",
        "choices", "candidates", "usage", "finish_reason", "incomplete_details",
    )
    private val DATA_IMAGE = Regex("^data:image/(?:png|jpeg|gif|webp);base64,[A-Za-z0-9+/=]+$")
}
