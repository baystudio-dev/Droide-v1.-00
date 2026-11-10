package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Pure serialization and wire adapters only; no provider/network/process work. */
class SubagentReceiptMetadataTest {
    private val receipt = SubagentDeliveryReceipt("parent", "task-one", "child", 2)
    private val user = SubagentDeliveryHistory.entry("Completed child result", receipt)
    private val calls = Json.parseToJsonElement("""[{"id":"call-one","type":"function","function":{"name":"read_file","arguments":"{}"}}]""").jsonArray
    private val tool = buildJsonObject { put("role", "tool"); put("tool_call_id", "call-one"); put("content", "Result") }

    @Test fun pendingInputRoundTripPreservesExactReceiptAndLegacyDefaultsToNull() {
        val input = AgentPendingInput("input", "Completion", AgentPromptDelivery.STEER, "provider", "model",
            origin = AgentInputOrigin.SUBAGENT, sourceId = receipt.taskId, createdAt = 42L, sourceDelivery = receipt)
        val encoded = Json.encodeToString(AgentPendingInput.serializer(), input)
        assertEquals(input, Json.decodeFromString(AgentPendingInput.serializer(), encoded))
        val legacy = JsonObject(Json.parseToJsonElement(encoded).jsonObject - "sourceDelivery")
        assertNull(Json.decodeFromString(AgentPendingInput.serializer(), legacy.toString()).sourceDelivery)
        assertEquals(receipt, SubagentDeliveryHistory.read(user))
    }

    @Test fun receiptParsingRejectsMalformedOrUnboundedMetadata() {
        val raw = user.getValue(SubagentDeliveryHistory.PRIVATE_FIELD).jsonObject
        val bad = listOf(
            JsonObject(raw + ("version" to JsonPrimitive(2))),
            JsonObject(raw + ("attempt" to JsonPrimitive("2"))),
            JsonObject(raw + ("attempt" to JsonPrimitive(0))),
            JsonObject(raw + ("attempt" to JsonPrimitive(2_147_483_648L))),
            JsonObject(raw + ("task_id" to JsonPrimitive("x".repeat(129)))),
            JsonObject(raw + ("task_id" to JsonPrimitive("../task"))),
            JsonObject(raw + ("child_session_id" to JsonPrimitive(12))),
            JsonObject(raw + ("extra" to JsonPrimitive(true))),
            JsonObject(raw - "parent_session_id"),
        )
        bad.forEach { metadata -> assertNull(SubagentDeliveryHistory.read(JsonObject(user + (SubagentDeliveryHistory.PRIVATE_FIELD to metadata)))) }
        assertNull(SubagentDeliveryHistory.read(JsonObject(user + ("role" to JsonPrimitive("assistant")))))
        assertNull(SubagentDeliveryHistory.read(buildJsonObject { put("role", "user"); put("content", user.toString()) }))
    }

    @Test fun userOriginAndMismatchedSourceOrParentCannotMintDeliveryAuthority() {
        assertEquals(receipt, SubagentDeliveryHistory.boundReceipt(AgentInputOrigin.SUBAGENT, receipt.taskId, receipt.parentSessionId, receipt))
        assertThrows(IllegalArgumentException::class.java) { SubagentDeliveryHistory.boundReceipt(AgentInputOrigin.USER, receipt.taskId, receipt.parentSessionId, receipt) }
        assertThrows(IllegalArgumentException::class.java) { SubagentDeliveryHistory.boundReceipt(AgentInputOrigin.SUBAGENT, "other-task", receipt.parentSessionId, receipt) }
        assertThrows(IllegalArgumentException::class.java) { SubagentDeliveryHistory.boundReceipt(AgentInputOrigin.SUBAGENT, receipt.taskId, "other-parent", receipt) }
        assertNull(SubagentDeliveryHistory.boundReceipt(AgentInputOrigin.SUBAGENT, receipt.taskId, receipt.parentSessionId, null))
    }

    @Test fun openAiChatDropsPrivateReceiptButKeepsToolHistory() {
        val wire = Json.parseToJsonElement(ProviderToolSchema.openAiRequest(request("fixture", assistant()), Json)).jsonObject
        assertNoReceipt(wire)
        assertEquals(JsonPrimitive("Completed child result"), wire.getValue("messages").jsonArray.first().jsonObject["content"])
        assertEquals(calls, wire.getValue("messages").jsonArray[1].jsonObject["tool_calls"])
    }

    @Test fun responsesDropsReceiptButPreservesNativeContinuation() {
        val native = Json.parseToJsonElement("""[
            {"type":"reasoning","id":"rs-one","encrypted_content":"opaque-fixture"},
            {"type":"function_call","id":"fc-one","call_id":"call-one","name":"read_file","arguments":"{}"}
        ]""").jsonArray
        val wire = Json.parseToJsonElement(OpenAiResponsesAdapter.encodeRequest(
            request("gpt-5", assistant(OpenAiResponsesAdapter.PRIVATE_CONTENT_FIELD, native)), false, Json)).jsonObject
        assertNoReceipt(wire)
        native.forEach { assertTrue(wire.getValue("input").jsonArray.contains(it)) }
    }

    @Test fun anthropicDropsReceiptButPreservesSignedContinuation() {
        val native = Json.parseToJsonElement("""[
            {"type":"thinking","thinking":"fixture thought","signature":"fixture-signature"},
            {"type":"tool_use","id":"call-one","name":"read_file","input":{}}
        ]""").jsonArray
        val wire = Json.parseToJsonElement(AnthropicMessagesAdapter.encodeRequest(
            request("claude-sonnet-4", assistant(AnthropicMessagesAdapter.PRIVATE_CONTENT_FIELD, native)), false, Json)).jsonObject
        assertNoReceipt(wire)
        assertEquals(native, wire.getValue("messages").jsonArray[1].jsonObject["content"])
    }

    @Test fun googleDropsReceiptButPreservesSignedContinuation() {
        val native = Json.parseToJsonElement("""[
            {"thoughtSignature":"fixture-signature","functionCall":{"id":"call-one","name":"read_file","args":{}}}
        ]""").jsonArray
        val wire = Json.parseToJsonElement(GoogleGenerateContentAdapter.encodeRequest(
            request("gemini-2.5-flash", assistant(GoogleGenerateContentAdapter.PRIVATE_CONTENT_FIELD, native)), Json)).jsonObject
        assertNoReceipt(wire)
        assertEquals(native, wire.getValue("contents").jsonArray[1].jsonObject["parts"])
    }

    private fun assistant(privateField: String? = null, native: JsonArray? = null) = buildJsonObject {
        put("role", "assistant"); put("content", ""); put("tool_calls", calls)
        if (privateField != null) put(privateField, requireNotNull(native))
    }

    private fun request(model: String, assistant: JsonObject) = buildJsonObject {
        put("model", model); put("messages", JsonArray(listOf(user, assistant, tool))); put("max_tokens", 1_024)
    }.toString()

    private fun assertNoReceipt(wire: JsonObject) {
        assertFalse(wire.toString().contains(SubagentDeliveryHistory.PRIVATE_FIELD))
        assertFalse(wire.toString().contains("parent_session_id"))
        assertFalse(wire.toString().contains("child_session_id"))
    }
}
