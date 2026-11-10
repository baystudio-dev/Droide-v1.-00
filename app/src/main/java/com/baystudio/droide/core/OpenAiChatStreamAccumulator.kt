package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Pure compatible Chat SSE accumulation, shared by production transport and offline fixtures. */
internal class OpenAiChatStreamAccumulator(private val json: Json) {
    private val capacity = ProviderPayloadLimits.RetainedBudget()
    val retainedRevision: Long get() = capacity.revision
    private data class ToolParts(
        var id: String = "",
        var type: String = "function",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    private val content = StringBuilder()
    private val refusal = StringBuilder()
    private val reasoningText = linkedMapOf<String, StringBuilder>()
    private val reasoningDetails = mutableListOf<JsonObject>()
    private val tools = sortedMapOf<Int, ToolParts>()
    private var finishReason: JsonElement = JsonNull
    private var nativeFinishReason: JsonPrimitive? = null
    private var usage: JsonObject? = null
    private var responseId: String? = null
    private var model: String? = null

    fun accept(payload: String): List<LlmStreamEvent> {
        capacity.validateEvent(payload)
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }
            .getOrElse { throw IllegalStateException("Invalid SSE JSON from provider") }
        root["error"]?.takeUnless { it == JsonNull }?.let { throw ProviderErrorPayloadException(it) }
        root["id"]?.jsonPrimitive?.contentOrNull?.let { capacity.replace("id", it.length.toLong()); responseId = it }
        root["model"]?.jsonPrimitive?.contentOrNull?.let { capacity.replace("model", it.length.toLong()); model = it }
        val canonicalUsage = root["usage"]?.takeUnless { it == JsonNull }
        val reportedUsage = canonicalUsage
            ?: (root["x_groq"] as? JsonObject)?.get("usage")?.takeUnless { it == JsonNull }
        // Prefer canonical usage; duplicate wrappers/snapshots are never additive.

        if (reportedUsage != null) {
            capacity.replace("usage", ProviderPayloadLimits.retainedJsonUnits(reportedUsage))
            usage = reportedUsage as? JsonObject
        }

        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return emptyList()
        choice["error"]?.takeUnless { it == JsonNull }?.let { throw ProviderErrorPayloadException(it) }
        choice["finish_reason"]?.takeUnless { it == JsonNull }?.let {
            check(it is JsonPrimitive && it.isString) { "Provider returned malformed finish reason" }
            check(finishReason == JsonNull || finishReason == it) { "Provider stream changed its terminal finish reason" }
            capacity.replace("finish", it.content.length.toLong())
            finishReason = it
        }
        // Compatible providers may expose their original reason publicly. It remains ordinary metadata,
        // never an adapter-authored private admission signal.
        (choice["native_finish_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.let { capacity.replace("nativeFinish", it.content.length.toLong()); nativeFinishReason = it }
        val rawDelta = choice["delta"]
        check(rawDelta == null || rawDelta == JsonNull || rawDelta is JsonObject) { "Provider returned malformed delta" }
        val delta = rawDelta as? JsonObject ?: return emptyList()
        val events = mutableListOf<LlmStreamEvent>()
        for (key in listOf("reasoning_content", "reasoning")) {
            val value = stringDelta(delta[key], key)
            if (value.isNotEmpty()) capacity.append(reasoningText.getOrPut(key) { capacity.reserve(256); StringBuilder() }, value)
        }
        delta["reasoning_details"]?.takeUnless { it == JsonNull }?.let { details ->
            check(details is JsonArray && details.all { it is JsonObject }) { "Provider returned malformed reasoning details" }
            capacity.reserve(details.sumOf { ProviderPayloadLimits.retainedJsonUnits(it) + 256 })
            reasoningDetails.addAll(details.map { it as JsonObject })
        }
        if (finishReason == JsonNull && listOf("reasoning_content", "reasoning").any { key ->
                (delta[key] as? JsonPrimitive)?.let { it.isString && it.content.isNotEmpty() } == true
            }) events += LlmStreamEvent.Thinking
        val refusalDelta = when (val value = delta["refusal"]) {
            null, JsonNull -> ""
            is JsonPrimitive -> {
                check(value.isString) { "Provider returned malformed refusal text" }
                value.content
            }
            else -> throw IllegalStateException("Provider returned malformed refusal text")
        }
        val textDelta = stringDelta(delta["content"], "content")
        val rawTools = delta["tool_calls"]
        check(rawTools == null || rawTools == JsonNull || rawTools is JsonArray) { "Provider returned malformed tool deltas" }
        if (textDelta.isNotEmpty()) {
            capacity.append(content, textDelta)
            events += LlmStreamEvent.TextDelta(textDelta)
        }
        if (refusalDelta.isNotEmpty()) {
            // Refusal is public output too. Match emitted text order exactly so a durable partial cannot
            // lose it when ordinary content is also present.
            capacity.append(content, refusalDelta)
            capacity.append(refusal, refusalDelta)
            events += LlmStreamEvent.TextDelta(refusalDelta)
        }

        val toolDeltas = rawTools as? JsonArray
        toolDeltas?.forEach { raw ->
            val item = raw as? JsonObject ?: throw IllegalStateException("Provider returned malformed tool delta")
            val index = (item["index"] as? JsonPrimitive)?.intOrNull ?: tools.size
            val part = tools.getOrPut(index) { capacity.reserve(256); ToolParts() }
            val idFragment = stringDelta(item["id"], "tool id").takeIf { it.isNotBlank() }
            if (idFragment != null) { capacity.replace("tool:$index:id", idFragment.length.toLong()); part.id = idFragment }
            stringDelta(item["type"], "tool type").takeIf { it.isNotBlank() }?.let { capacity.replace("tool:$index:type", it.length.toLong()); part.type = it }
            val rawFunction = item["function"]
            check(rawFunction == null || rawFunction == JsonNull || rawFunction is JsonObject) { "Provider returned malformed tool function" }
            val fn = rawFunction as? JsonObject
            val nameFragment = stringDelta(fn?.get("name"), "tool name").takeIf { it.isNotEmpty() }
            if (nameFragment != null) {
                val nextName = when {
                    part.name.isBlank() -> nameFragment
                    nameFragment == part.name -> part.name
                    else -> {

                        capacity.replace("tool:$index:name", part.name.length.toLong() + nameFragment.length)
                        part.name + nameFragment
                    }
                }
                capacity.replace("tool:$index:name", nextName.length.toLong())
                part.name = nextName
            }
            val argsFragment = stringDelta(fn?.get("arguments"), "tool arguments").takeIf { it.isNotEmpty() }
            if (argsFragment != null) capacity.append(part.arguments, argsFragment)
            if (idFragment != null || nameFragment != null || argsFragment != null) {
                events += LlmStreamEvent.ToolCallDelta(index, idFragment, nameFragment, argsFragment)
            }
        }
        return events
    }

    private fun stringDelta(value: JsonElement?, field: String): String = when (value) {
        null, JsonNull -> ""
        is JsonPrimitive -> { check(value.isString) { "Provider returned malformed $field" }; value.content }
        else -> throw IllegalStateException("Provider returned malformed $field")
    }

    fun completedResponse(): String = buildJsonObject {
        responseId?.let { put("id", it) }
        model?.let { put("model", it) }
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("index", 0)
                put("message", buildJsonObject {
                    put("role", "assistant")
                    put("content", content.toString())
                    if (refusal.isNotEmpty()) put("refusal", refusal.toString())
                    if (refusal.isEmpty() && finishReason in setOf(JsonPrimitive("stop"), JsonPrimitive("tool_calls"))) {
                        reasoningText.forEach { (key, value) -> put(key, value.toString()) }
                        if (reasoningDetails.isNotEmpty()) put("reasoning_details", JsonArray(reasoningDetails))
                    }
                    if (tools.isNotEmpty()) {
                        put("tool_calls", buildJsonArray {
                            tools.forEach { (index, part) ->
                                add(buildJsonObject {
                                    put("index", index)
                                    put("id", part.id.ifBlank { "stream_tool_$index" })
                                    put("type", part.type)
                                    put("function", buildJsonObject {
                                        put("name", part.name)
                                        put("arguments", part.arguments.toString())
                                    })
                                })
                            }
                        })
                    }
                })
                put("finish_reason", finishReason)
                nativeFinishReason?.let { put("native_finish_reason", it) }
            })
        })
        usage?.let { put("usage", it) }
    }.toString()
}
