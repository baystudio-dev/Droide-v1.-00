package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GoogleToolContinuationTest {
    private val parts = Json.parseToJsonElement("""[
      {"text":"private summary","thought":true,"thoughtSignature":"summary-signature"},
      {"text":"Reading file"},
      {"functionCall":{"id":"call_1","name":"read_file","args":{"path":"README.md"}},"thoughtSignature":"opaque-signature"}
    ]""").jsonArray

    private fun response(parts: JsonArray = this.parts) = buildJsonObject {
        put("candidates", buildJsonArray { add(buildJsonObject {
            put("content", buildJsonObject { put("role", "model"); put("parts", parts) })
            put("finishReason", "STOP")
        }) })
    }.toString()

    private fun message(normalized: String) = Json.parseToJsonElement(normalized).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
    private fun replay(message: JsonObject): JsonObject {
        val body = buildJsonObject {
            put("model", "gemini-3.5-flash")
            put("messages", buildJsonArray {
                add(JsonObject(message + ("role" to JsonPrimitive("assistant"))))
                add(buildJsonObject { put("role", "tool"); put("tool_call_id", "call_1"); put("content", "file text") })
            })
        }.toString()
        return Json.parseToJsonElement(GoogleGenerateContentAdapter.encodeRequest(body, Json)).jsonObject
    }

    @Test fun bufferedToolTurnReplaysSignaturesOnTheirOriginalParts() {
        val msg = message(GoogleGenerateContentAdapter.normalizeBuffered(response(), Json))
        assertEquals(JsonPrimitive("Reading file"), msg["content"])
        assertEquals(parts, msg[GoogleGenerateContentAdapter.PRIVATE_CONTENT_FIELD])
        val contents = replay(msg)["contents"]!!.jsonArray
        assertEquals(parts, contents[0].jsonObject["parts"])
        assertFalse(contents[0].toString().contains("_droide_"))
        assertEquals(JsonPrimitive("read_file"), contents[1].jsonObject["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject["name"])
    }

    @Test fun streamingKeepsThoughtMetadataPrivateAndToolSignatureIntact() {
        val acc = GoogleGenerateContentAdapter.StreamAccumulator(Json)
        val events = acc.accept(response())
        assertEquals(listOf("Reading file"), events.filterIsInstance<LlmStreamEvent.TextDelta>().map { it.text })
        assertEquals(1, events.filterIsInstance<LlmStreamEvent.ToolCallDelta>().size)
        assertEquals(parts, message(acc.completedResponse())[GoogleGenerateContentAdapter.PRIVATE_CONTENT_FIELD])
    }

    @Test fun textChunksStayOrderedBeforeSignedFunctionCall() {
        val acc = GoogleGenerateContentAdapter.StreamAccumulator(Json)
        acc.accept(response(Json.parseToJsonElement("""[{"text":"Reading "}]""").jsonArray))
        acc.accept(response(Json.parseToJsonElement("""[{"text":"file"}]""").jsonArray))
        acc.accept(response(JsonArray(listOf(parts[2]))))
        val msg = message(acc.completedResponse())
        assertEquals(JsonPrimitive("Reading file"), msg["content"])
        assertEquals("opaque-signature", replay(msg)["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[2].jsonObject["thoughtSignature"]!!.jsonPrimitive.content)
    }

    @Test fun toolArgumentTamperingFailsBeforeSendingContinuation() {
        val msg = message(GoogleGenerateContentAdapter.normalizeBuffered(response(), Json))
        val private = JsonArray(listOf(Json.parseToJsonElement("""{"functionCall":{"id":"call_1","name":"read_file","args":{"path":"CHANGED.md"}},"thoughtSignature":"opaque-signature"}""")))
        assertThrows(IllegalArgumentException::class.java) { replay(JsonObject(msg + (GoogleGenerateContentAdapter.PRIVATE_CONTENT_FIELD to private))) }
    }

    @Test fun toolSetOrIdentifierTamperingFailsBeforeSendingContinuation() {
        val msg = message(GoogleGenerateContentAdapter.normalizeBuffered(response(), Json))
        val private = JsonArray(listOf(Json.parseToJsonElement("""{"functionCall":{"id":"wrong","name":"read_file","args":{"path":"README.md"}}}""")))
        assertThrows(IllegalArgumentException::class.java) { replay(JsonObject(msg + (GoogleGenerateContentAdapter.PRIVATE_CONTENT_FIELD to private))) }
        assertThrows(IllegalArgumentException::class.java) { replay(JsonObject(msg + (GoogleGenerateContentAdapter.PRIVATE_CONTENT_FIELD to JsonArray(emptyList())))) }
    }

    @Test fun legacyModelWithoutFunctionIdentifiersUsesStableToolOrdinal() {
        val anonymous = Json.parseToJsonElement("""[{"text":"plan"},{"functionCall":{"name":"read_file","args":{"path":"README.md"}}}]""").jsonArray
        val msg = message(GoogleGenerateContentAdapter.normalizeBuffered(response(anonymous), Json))
        assertEquals(JsonPrimitive("gemini_tool_0"), msg["tool_calls"]!!.jsonArray[0].jsonObject["id"])
        val body = buildJsonObject { put("model", "gemini-2.5-flash"); put("messages", JsonArray(listOf(msg))) }.toString()
        assertEquals(anonymous, Json.parseToJsonElement(GoogleGenerateContentAdapter.encodeRequest(body, Json)).jsonObject["contents"]!!.jsonArray[0].jsonObject["parts"])
    }

    @Test fun anonymousNativeCallDoesNotGainAnInventedIdentifierInItsResponse() {
        val anonymous = Json.parseToJsonElement("""[{"functionCall":{"name":"read_file","args":{"path":"README.md"}}}]""").jsonArray
        val msg = message(GoogleGenerateContentAdapter.normalizeBuffered(response(anonymous), Json))
        val result = Json.parseToJsonElement("""{"role":"tool","tool_call_id":"gemini_tool_0","content":"ok"}""")
        val body = buildJsonObject { put("model", "gemini-2.5-flash"); put("messages", JsonArray(listOf(msg, result))) }.toString()
        val contents = Json.parseToJsonElement(GoogleGenerateContentAdapter.encodeRequest(body, Json)).jsonObject["contents"]!!.jsonArray
        assertFalse("id" in contents[1].jsonObject["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject)
    }

    @Test fun parallelToolResponsesShareOneUserContentAndKeepTheirOrder() {
        val parallel = Json.parseToJsonElement("""[
          {"functionCall":{"id":"a","name":"read_file","args":{"path":"a"}},"thoughtSignature":"sig"},
          {"functionCall":{"id":"b","name":"read_file","args":{"path":"b"}}}
        ]""").jsonArray
        val msg = message(GoogleGenerateContentAdapter.normalizeBuffered(response(parallel), Json))
        val body = buildJsonObject {
            put("model", "gemini-3.5-flash")
            put("messages", JsonArray(listOf(msg,
                Json.parseToJsonElement("""{"role":"tool","tool_call_id":"a","content":"first"}"""),
                Json.parseToJsonElement("""{"role":"tool","tool_call_id":"b","content":"second"}"""))))
        }.toString()
        val contents = Json.parseToJsonElement(GoogleGenerateContentAdapter.encodeRequest(body, Json)).jsonObject["contents"]!!.jsonArray
        assertEquals(2, contents.size)
        val results = contents[1].jsonObject["parts"]!!.jsonArray
        assertEquals(listOf("a", "b"), results.map { it.jsonObject["functionResponse"]!!.jsonObject["id"]!!.jsonPrimitive.content })
    }
}
