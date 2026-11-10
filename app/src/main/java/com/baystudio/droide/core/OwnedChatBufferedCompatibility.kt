package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Bounded SAME-response compatibility, not a second generation request. */
internal object OwnedChatBufferedCompatibility {
    fun event(body: String, json: Json): String {
        ProviderPayloadLimits.checkedJson(body)
        OwnedChatEventKeys.rejectDuplicates(body,json)
        val root=json.parseToJsonElement(body) as? JsonObject ?: error("Buffered Chat response must be an object")
        require(root["error"] == null || root["error"] == JsonNull) { "Buffered Chat error response" }
        val choices=root["choices"] as? JsonArray ?: error("Missing buffered Chat choices")
        require(choices.size==1) { "Typed Chat requires a single buffered choice" }
        val choice=choices.single() as? JsonObject ?: error("Malformed buffered Chat choice")
        val message=choice["message"] as? JsonObject ?: error("Missing buffered Chat message")
        require(message["function_call"] == null || message["function_call"] == JsonNull) { "Deprecated function_call requires explicit migration" }
        val tools=message["tool_calls"]
        require(tools==null || tools==JsonNull || tools is JsonArray) { "Malformed buffered tool calls" }
        val delta=if(tools is JsonArray) JsonObject(message + ("tool_calls" to JsonArray(tools.mapIndexed { i,raw ->
            val call=raw as? JsonObject ?: error("Malformed buffered tool call")
            JsonObject(call + ("index" to JsonPrimitive(i)))
        }))) else message
        val normalizedChoice=JsonObject(choice.filterKeys { it!="message" } + ("delta" to delta))
        return JsonObject(root + ("choices" to JsonArray(listOf(normalizedChoice)))).toString()
    }
}
