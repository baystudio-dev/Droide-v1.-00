package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OpenAiResponsesAdapterTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(text: String) = json.parseToJsonElement(text).jsonObject
    private fun array(text: String) = json.parseToJsonElement(text).jsonArray

    private fun request(messages: JsonArray, vararg extra: Pair<String, JsonElement>) = JsonObject(
        mapOf("model" to JsonPrimitive("gpt-5"), "messages" to messages) + extra.toMap(),
    ).toString()

    private fun encode(messages: JsonArray, vararg extra: Pair<String, JsonElement>, streaming: Boolean = false) =
        obj(OpenAiResponsesAdapter.encodeRequest(request(messages, *extra), streaming, json))

    private val user = array("""[{"role":"user","content":"Check the workspace"}]""")
    private val todoSchema = obj("""{
        "type":"object",
        "properties":{
            "todos":{"type":"array","items":{
                "type":"object","properties":{"content":{"type":"string"},"priority":{"type":"integer"}},
                "required":["content"],"additionalProperties":false
            }},
            "note":{"type":"string"}
        },
        "required":["todos"],"additionalProperties":false
    }""")

    private fun tool(schema: JsonElement = todoSchema, strict: Boolean? = null) = buildJsonArray {
        add(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "todos")
                put("description", "Update tasks")
                put("parameters", schema)
                strict?.let { put("strict", it) }
            })
        })
    }

    private fun functionCall(callId: String = "call_1", arguments: String = "{\"path\":\"README.md\"}") =
        buildJsonObject {
            put("id", callId)
            put("type", "function")
            put("function", buildJsonObject { put("name", "read_file"); put("arguments", arguments) })
        }

    private fun assistantCall(callId: String = "call_1", arguments: String = "{\"path\":\"README.md\"}") =
        buildJsonObject {
            put("role", "assistant")
            put("content", "Reading file")
            put("tool_calls", JsonArray(listOf(functionCall(callId, arguments))))
        }

    private fun toolResult(callId: String = "call_1") = buildJsonObject {
        put("role", "tool"); put("tool_call_id", callId); put("content", "file content")
    }

    private fun response(
        output: JsonArray,
        status: String = "completed",
        extra: Map<String, JsonElement> = emptyMap(),
    ) = JsonObject(mapOf(
        "id" to JsonPrimitive("resp_1"),
        "model" to JsonPrimitive("gpt-5"),
        "status" to JsonPrimitive(status),
        "output" to output,
        "usage" to obj("""{"input_tokens":12,"output_tokens":7,"total_tokens":19}"""),
    ) + extra)

    private fun message(response: JsonObject) = response["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
    private fun finishReason(response: JsonObject) = response["choices"]!!.jsonArray[0].jsonObject["finish_reason"]
    private fun normalized(response: JsonObject) = obj(OpenAiResponsesAdapter.normalizeBuffered(response.toString(), json))

    private val reasoningOutput = array("""[
        {"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":"opaque-encrypted-reasoning"},
        {"id":"msg_1","type":"message","role":"assistant","phase":"commentary","status":"completed",
         "content":[{"type":"output_text","text":"Reading file","annotations":[]}]},
        {"id":"fc_1","type":"function_call","status":"completed","call_id":"call_1",
         "name":"read_file","arguments":"{\"path\":\"README.md\"}"}
    ]""")

    @Test fun functionToolsAreFlatAndKeepRequiredAndOptionalTodoFields() {
        val wire = encode(user, "tools" to tool())
        val declaration = wire["tools"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("function"), declaration["type"])
        assertEquals(JsonPrimitive("todos"), declaration["name"])
        assertEquals(JsonPrimitive("Update tasks"), declaration["description"])
        assertEquals(JsonPrimitive(false), declaration["strict"])
        assertFalse("function" in declaration)
        assertEquals(todoSchema, declaration["parameters"])
        val params = declaration["parameters"]!!.jsonObject
        assertEquals(array("""["todos"]"""), params["required"])
        assertTrue("note" in params["properties"]!!.jsonObject)
        val item = params["properties"]!!.jsonObject["todos"]!!.jsonObject["items"]!!.jsonObject
        assertEquals(array("""["content"]"""), item["required"])
        assertEquals(JsonPrimitive("integer"), item["properties"]!!.jsonObject["priority"]!!.jsonObject["type"])
    }

    @Test fun explicitStrictToolsAndBufferedStreamingToolSchemasUseTheSameContract() {
        val source = request(user, "tools" to tool(strict = true))
        val buffered = obj(OpenAiResponsesAdapter.encodeRequest(source, false, json))
        val stream = obj(OpenAiResponsesAdapter.encodeRequest(source, true, json))
        assertEquals(JsonPrimitive(true), buffered["tools"]!!.jsonArray.single().jsonObject["strict"])
        assertEquals(buffered["tools"], stream["tools"])
        assertEquals(JsonPrimitive(false), buffered["stream"])
        assertEquals(JsonPrimitive(true), stream["stream"])
    }

    @Test fun reasoningUsesEncryptedContinuationAndStatelessResponsesRequest() {
        val wire = encode(user, "reasoning_effort" to JsonPrimitive("high"), "max_completion_tokens" to JsonPrimitive(1024))
        assertEquals(obj("""{"effort":"high"}"""), wire["reasoning"])
        assertEquals(array("""["reasoning.encrypted_content"]"""), wire["include"])
        assertEquals(JsonPrimitive(false), wire["store"])
        assertEquals(JsonPrimitive(1024), wire["max_output_tokens"])
        assertFalse("messages" in wire)
        assertFalse("reasoning_effort" in wire)
        assertFalse("max_completion_tokens" in wire)
        assertFalse("previous_response_id" in wire)
    }

    @Test fun inputPreservesSystemDeveloperAndUserRolesAndStagedImages() {
        val source = array("""[
            {"role":"system","content":"You are helpful"},
            {"role":"developer","content":"Use tools carefully"},
            {"role":"user","content":[
                {"type":"text","text":"Inspect this image"},
                {"type":"image_url","image_url":{"url":"data:image/png;base64,aGVsbG8=","detail":"low"}}
            ]}
        ]""")
        val input = encode(source)["input"]!!.jsonArray
        assertEquals(listOf("system", "developer", "user"), input.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals(JsonPrimitive("input_text"), input[0].jsonObject["content"]!!.jsonArray[0].jsonObject["type"])
        val content = input[2].jsonObject["content"]!!.jsonArray
        assertEquals(obj("""{"type":"input_text","text":"Inspect this image"}"""), content[0])
        assertEquals(JsonPrimitive("input_image"), content[1].jsonObject["type"])
        assertEquals(JsonPrimitive("data:image/png;base64,aGVsbG8="), content[1].jsonObject["image_url"])
        assertEquals(JsonPrimitive("low"), content[1].jsonObject["detail"])
        assertFalse(encode(source).toString().contains("_droide_"))
    }

    @Test fun assistantCallsAndToolResultsUseCallIdentityInsteadOfItemIdentity() {
        val wire = encode(JsonArray(listOf(assistantCall(), toolResult())))
        val input = wire["input"]!!.jsonArray.map { it.jsonObject }
        val call = input.single { it["type"] == JsonPrimitive("function_call") }
        val result = input.single { it["type"] == JsonPrimitive("function_call_output") }
        assertEquals(JsonPrimitive("call_1"), call["call_id"])
        assertEquals(JsonPrimitive("read_file"), call["name"])
        assertEquals(JsonPrimitive("{\"path\":\"README.md\"}"), call["arguments"])
        assertEquals(JsonPrimitive("call_1"), result["call_id"])
        assertEquals(JsonPrimitive("file content"), result["output"])
        assertFalse(wire.toString().contains("tool_call_id"))
    }

    @Test fun bufferedReasoningAndCallsNormalizeUsageAndRetainExactPrivateOutput() {
        val result = normalized(response(reasoningOutput))
        val assistant = message(result)
        assertEquals(JsonPrimitive("Reading file"), assistant["content"])
        assertEquals(reasoningOutput, assistant[OpenAiResponsesAdapter.PRIVATE_CONTENT_FIELD])
        assertEquals(JsonPrimitive("tool_calls"), finishReason(result))
        val call = assistant["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("call_1"), call["id"])
        assertEquals(JsonPrimitive("read_file"), call["function"]!!.jsonObject["name"])
        assertEquals(JsonPrimitive("{\"path\":\"README.md\"}"), call["function"]!!.jsonObject["arguments"])
        val usage = result["usage"]!!.jsonObject
        assertEquals(JsonPrimitive(12), usage["prompt_tokens"])
        assertEquals(JsonPrimitive(7), usage["completion_tokens"])
        assertEquals(JsonPrimitive(19), usage["total_tokens"])
        assertFalse(assistant["content"].toString().contains("opaque-encrypted"))
    }

    @Test fun encryptedReasoningAndToolCallReplayExactlyWithTheFollowingResult() {
        val assistant = message(normalized(response(reasoningOutput)))
        val wire = encode(JsonArray(listOf(assistant, toolResult())))
        val input = wire["input"]!!.jsonArray
        assertEquals(reasoningOutput, JsonArray(input.dropLast(1)))
        assertEquals(obj("""{"type":"function_call_output","call_id":"call_1","output":"file content"}"""), input.last())
        assertFalse(wire.toString().contains(OpenAiResponsesAdapter.PRIVATE_CONTENT_FIELD))
    }

    @Test fun tamperedCanonicalTextCannotReplayPrivateOutput() {
        val original = message(normalized(response(reasoningOutput)))
        val tampered = JsonObject(original + ("content" to JsonPrimitive("Changed assistant text")))
        assertThrows(IllegalArgumentException::class.java) { encode(JsonArray(listOf(tampered, toolResult()))) }
    }

    @Test fun tamperedCallIdentityNameAndArgumentsCannotReplayPrivateOutput() {
        val original = message(normalized(response(reasoningOutput)))
        val originalCall = original["tool_calls"]!!.jsonArray.single().jsonObject
        val originalFn = originalCall["function"]!!.jsonObject
        val changedCalls = listOf(
            JsonObject(originalCall + ("id" to JsonPrimitive("changed_call"))),
            JsonObject(originalCall + ("function" to JsonObject(originalFn + ("name" to JsonPrimitive("write_file"))))),
            JsonObject(originalCall + ("function" to JsonObject(originalFn + ("arguments" to JsonPrimitive("{\"path\":\"CHANGED.md\"}"))))),
        )
        changedCalls.forEach { changed ->
            val tampered = JsonObject(original + ("tool_calls" to JsonArray(listOf(changed))))
            assertThrows(IllegalArgumentException::class.java) { encode(JsonArray(listOf(tampered, toolResult()))) }
        }
    }

    @Test fun privateToolArgumentsCannotDifferFromCanonicalArguments() {
        val original = message(normalized(response(reasoningOutput)))
        val changedOutput = JsonArray(reasoningOutput.map { item ->
            val part = item.jsonObject
            if (part["type"] == JsonPrimitive("function_call"))
                JsonObject(part + ("arguments" to JsonPrimitive("{\"path\":\"CHANGED.md\"}"))) else part
        })
        val tampered = JsonObject(original + (OpenAiResponsesAdapter.PRIVATE_CONTENT_FIELD to changedOutput))
        assertThrows(IllegalArgumentException::class.java) { encode(JsonArray(listOf(tampered, toolResult()))) }
    }

    @Test fun invalidRolesMissingIdsAndUnmatchedToolResultsFailBeforeRequest() {
        listOf(
            array("""[{"role":"invalid","content":"hello"}]"""),
            array("""[{"role":"tool","content":"missing id"}]"""),
            JsonArray(listOf(assistantCall(), toolResult("unknown_call"))),
            JsonArray(listOf(assistantCall(""), toolResult())),
            JsonArray(listOf(toolResult())),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { encode(invalid) }
        }
    }

    @Test fun malformedToolsAndSchemasFailInsteadOfBeingDroppedOrStrengthened() {
        val invalidTools = listOf(
            obj("""{}"""),
            array("""[null]"""),
            array("""[{"type":"function"}]"""),
            tool(obj("""{"type":"object","properties":{"todos":{"type":"not-a-type"}}}""")),
            tool(obj("""{"type":"object","properties":{},"required":["missing"],"additionalProperties":false}""")),
            tool(obj("""{"type":"object","properties":{"todos":{"type":"array","items":[]}},"required":"todos"}""")),
        )
        invalidTools.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { encode(user, "tools" to invalid) }
        }
    }

    @Test fun toolCallArgumentsMustBeJsonObjects() {
        listOf("[]", "null", "not-json").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                encode(JsonArray(listOf(assistantCall(arguments = invalid), toolResult())))
            }
        }
    }

    @Test fun streamTextDeltaDoneAndCompletedDoNotDuplicateVisibleText() {
        val acc = OpenAiResponsesAdapter.StreamAccumulator(json)
        val events = mutableListOf<LlmStreamEvent>()
        events += acc.accept("""{"type":"response.output_item.added","output_index":0,"item":{"id":"msg_1","type":"message","role":"assistant","status":"in_progress","content":[]}}""")
        events += acc.accept("""{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Hello "}""")
        events += acc.accept("""{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"world"}""")
        events += acc.accept("""{"type":"response.output_text.done","item_id":"msg_1","output_index":0,"content_index":0,"text":"Hello world"}""")
        val output = array("""[{"id":"msg_1","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Hello world","annotations":[]}]}]""")
        events += acc.accept(buildJsonObject { put("type", "response.completed"); put("response", response(output)) }.toString())
        events += acc.accept("[DONE]")
        assertEquals("Hello world", events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text })
        val result = obj(acc.completedResponse())
        assertEquals(JsonPrimitive("Hello world"), message(result)["content"])
        assertEquals(output, message(result)[OpenAiResponsesAdapter.PRIVATE_CONTENT_FIELD])
        assertEquals(JsonPrimitive("stop"), finishReason(result))
    }

    @Test fun streamedRefusalIsVisibleExactlyOnceAndRetainedAsRefusal() {
        val acc = OpenAiResponsesAdapter.StreamAccumulator(json)
        val events = mutableListOf<LlmStreamEvent>()
        events += acc.accept("""{"type":"response.output_item.added","output_index":0,"item":{"id":"msg_1","type":"message","role":"assistant","status":"in_progress","content":[]}}""")
        events += acc.accept("""{"type":"response.refusal.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"I cannot "}""")
        events += acc.accept("""{"type":"response.refusal.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"assist"}""")
        events += acc.accept("""{"type":"response.refusal.done","item_id":"msg_1","output_index":0,"content_index":0,"refusal":"I cannot assist"}""")
        val output = array("""[{"id":"msg_1","type":"message","role":"assistant","status":"completed","content":[{"type":"refusal","refusal":"I cannot assist"}]}]""")
        events += acc.accept(buildJsonObject { put("type", "response.completed"); put("response", response(output)) }.toString())
        assertEquals("I cannot assist", events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text })
        val result = obj(acc.completedResponse())
        assertEquals(JsonPrimitive("I cannot assist"), message(result)["content"])
        assertEquals(JsonPrimitive("I cannot assist"), message(result)["refusal"])
        assertEquals(JsonPrimitive("content_filter"), finishReason(result))
    }

    @Test fun streamCallArgumentsDeltaDoneAndCompletedDoNotDuplicateFunctionData() {
        val acc = OpenAiResponsesAdapter.StreamAccumulator(json)
        val events = mutableListOf<LlmStreamEvent>()
        events += acc.accept("""{"type":"response.output_item.added","output_index":0,"item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"read_file","arguments":"","status":"in_progress"}}""")
        events += acc.accept("""{"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":"{\"path\":"}""")
        events += acc.accept("""{"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":"\"README.md\"}"}""")
        events += acc.accept("""{"type":"response.function_call_arguments.done","item_id":"fc_1","output_index":0,"arguments":"{\"path\":\"README.md\"}"}""")
        val output = JsonArray(listOf(reasoningOutput.last()))
        events += acc.accept(buildJsonObject { put("type", "response.completed"); put("response", response(output)) }.toString())
        val calls = events.filterIsInstance<LlmStreamEvent.ToolCallDelta>()
        assertEquals("read_file", calls.mapNotNull { it.nameFragment }.joinToString(""))
        assertEquals("{\"path\":\"README.md\"}", calls.mapNotNull { it.argumentsFragment }.joinToString(""))
        assertEquals("call_1", calls.mapNotNull { it.callId }.first())
        val result = obj(acc.completedResponse())
        assertEquals(JsonPrimitive("{\"path\":\"README.md\"}"), message(result)["tool_calls"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["arguments"])
        assertEquals(JsonPrimitive("tool_calls"), finishReason(result))
    }

    @Test fun streamRetainsEncryptedReasoningWithOnlyAContentFreeActivitySignal() {
        val acc = OpenAiResponsesAdapter.StreamAccumulator(json)
        assertEquals(listOf(LlmStreamEvent.Thinking), acc.accept("""{"type":"response.output_item.added","output_index":0,"item":{"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":""}}"""))
        assertTrue(acc.accept("""{"type":"response.reasoning_summary_text.delta","item_id":"rs_1","output_index":0,"summary_index":0,"delta":"private summary"}""").isEmpty())
        assertTrue(acc.accept(buildJsonObject {
            put("type", "response.output_item.done"); put("output_index", 0); put("item", reasoningOutput.first())
        }.toString()).isEmpty())
        val events = acc.accept(buildJsonObject { put("type", "response.completed"); put("response", response(reasoningOutput)) }.toString())
        assertFalse(events.filterIsInstance<LlmStreamEvent.TextDelta>().any { it.text.contains("private") || it.text.contains("opaque") })
        assertEquals(reasoningOutput, message(obj(acc.completedResponse()))[OpenAiResponsesAdapter.PRIVATE_CONTENT_FIELD])
    }

    @Test fun failedAndUnsupportedIncompleteResponsesThrowForBufferedAndStreamedPaths() {
        val failed = response(JsonArray(emptyList()), "failed", mapOf("error" to obj("""{"code":"server_error","message":"Provider failed"}""")))
        val incomplete = response(JsonArray(emptyList()), "incomplete", mapOf("incomplete_details" to obj("""{"reason":"unexpected_reason"}""")))
        listOf(failed, incomplete).forEach { bad ->
            assertThrows(IllegalStateException::class.java) { normalized(bad) }
            val acc = OpenAiResponsesAdapter.StreamAccumulator(json)
            val event = buildJsonObject { put("type", "response.${bad["status"]!!.jsonPrimitive.content}"); put("response", bad) }.toString()
            assertThrows(IllegalStateException::class.java) { acc.accept(event); acc.completedResponse() }
        }
        assertThrows(IllegalStateException::class.java) {
            OpenAiResponsesAdapter.StreamAccumulator(json).accept("""{"type":"error","code":"server_error","message":"Provider failed"}""")
        }
    }

    @Test fun streamEndWithoutTerminalResponseDoesNotReturnSuccessfulPartialData() {
        val acc = OpenAiResponsesAdapter.StreamAccumulator(json)
        acc.accept("""{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"partial"}""")
        assertTrue(acc.accept("[DONE]").isEmpty())
        assertThrows(IllegalStateException::class.java) { acc.completedResponse() }
    }

    @Test fun incompleteTokenLimitMapsToLengthAndNeverExposesPartialExecutableCalls() {
        val output = array("""[
            {"id":"msg_1","type":"message","role":"assistant","status":"incomplete","content":[{"type":"output_text","text":"Partial answer","annotations":[]}]},
            {"id":"fc_1","type":"function_call","status":"incomplete","call_id":"call_1","name":"read_file","arguments":"{\"path\":"}
        ]""")
        val incomplete = response(output, "incomplete", mapOf("incomplete_details" to obj("""{"reason":"max_output_tokens"}""")))
        val buffered = normalized(incomplete)
        assertEquals(JsonPrimitive("length"), finishReason(buffered))
        assertEquals(JsonPrimitive("Partial answer"), message(buffered)["content"])
        assertTrue((message(buffered)["tool_calls"] as? JsonArray).isNullOrEmpty())
        val acc = OpenAiResponsesAdapter.StreamAccumulator(json)
        acc.accept(buildJsonObject { put("type", "response.incomplete"); put("response", incomplete) }.toString())
        assertEquals(JsonPrimitive("length"), finishReason(obj(acc.completedResponse())))
        assertTrue((message(obj(acc.completedResponse()))["tool_calls"] as? JsonArray).isNullOrEmpty())
    }

    @Test fun refusalsAndContentFilterMapToContentFilterWithoutToolExecution() {
        val output = array("""[{"id":"msg_1","type":"message","role":"assistant","status":"completed","content":[{"type":"refusal","refusal":"I cannot assist with that"}]}]""")
        val refused = normalized(response(output))
        assertEquals(JsonPrimitive("I cannot assist with that"), message(refused)["content"])
        assertEquals(JsonPrimitive("I cannot assist with that"), message(refused)["refusal"])
        assertEquals(JsonPrimitive("content_filter"), finishReason(refused))
        val filtered = normalized(response(reasoningOutput, "incomplete", mapOf("incomplete_details" to obj("""{"reason":"content_filter"}"""))))
        assertEquals(JsonPrimitive("content_filter"), finishReason(filtered))
        assertTrue((message(filtered)["tool_calls"] as? JsonArray).isNullOrEmpty())
    }
}
