package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderSchemaCompatibilityTest {
    private fun schema(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun normalize(text: String) = ProviderToolSchema.parameters(schema(text), "mcp_fixture")
    private fun parametersOnEveryWire(input: JsonObject): List<JsonObject> {
        val body = buildJsonObject {
            put("model", "fixture")
            put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "hi") }) })
            put("tools", buildJsonArray { add(buildJsonObject {
                put("type", "function"); put("function", buildJsonObject { put("name", "mcp_fixture"); put("parameters", input) })
            }) })
        }.toString()
        val openai = Json.parseToJsonElement(ProviderToolSchema.openAiRequest(body, Json)).jsonObject
        val anthropic = Json.parseToJsonElement(AnthropicMessagesAdapter.encodeRequest(body, false, Json)).jsonObject
        val google = Json.parseToJsonElement(GoogleGenerateContentAdapter.encodeRequest(body, Json)).jsonObject
        val responses = Json.parseToJsonElement(OpenAiResponsesAdapter.encodeRequest(body, false, Json)).jsonObject
        return listOf(
            openai["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject,
            anthropic["tools"]!!.jsonArray[0].jsonObject["input_schema"]!!.jsonObject,
            google["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject["parametersJsonSchema"]!!.jsonObject,
            responses["tools"]!!.jsonArray[0].jsonObject["parameters"]!!.jsonObject,
        )
    }

    @Test fun screenshotTodosAndNestedArraysAlwaysHaveItemsOnEveryWire() {
        val input = schema("""{"type":"object","properties":{"todos":{"type":"array"},"nested":{"type":"array","items":{"type":"object","properties":{"values":{"type":["array","null"]}}}}}}""")
        parametersOnEveryWire(input).forEach { parameters ->
            val props = parameters["properties"]!!.jsonObject
            assertEquals(JsonObject(emptyMap()), props["todos"]!!.jsonObject["items"])
            assertEquals(JsonObject(emptyMap()), props["nested"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject["values"]!!.jsonObject["items"])
            assertTrue(parameters["required"]!!.jsonArray.isEmpty())
        }
    }

    @Test fun untypedNestedAssertionsDoNotInventObjectOrArrayTypes() {
        val result = normalize("""{"properties":{"objectHint":{"properties":{"x":{"type":"integer"}}},"arrayHint":{"items":{"type":"integer"}}}}""")
        assertEquals(JsonPrimitive("object"), result["type"])
        val props = result["properties"]!!.jsonObject
        assertFalse("type" in props["objectHint"]!!.jsonObject)
        assertFalse("type" in props["arrayHint"]!!.jsonObject)
    }

    @Test fun requiredMapKeysKeepTheirAdditionalPropertyConstraints() {
        val input = schema("""{"type":"object","required":["x"],"additionalProperties":{"type":"integer"}}""")
        parametersOnEveryWire(input).forEach {
            assertEquals(JsonPrimitive("integer"), it["additionalProperties"]!!.jsonObject["type"])
            assertFalse("x" in it["properties"]!!.jsonObject)
            assertEquals(JsonArray(listOf(JsonPrimitive("x"))), it["required"])
        }
    }

    @Test fun requiredPatternKeysRemainValidInAClosedMap() {
        val result = normalize("""{"type":"object","patternProperties":{"^x$":{"type":"integer"}},"required":["x"],"additionalProperties":false}""")
        assertFalse("x" in result["properties"]!!.jsonObject)
        assertEquals(JsonPrimitive("integer"), result["patternProperties"]!!.jsonObject["^x$"]!!.jsonObject["type"])
    }

    @Test fun legacyDependenciesAdditionalItemsAndContentSchemasExpandLocalReferences() {
        val input = schema("""{"type":"object","properties":{"tuple":{"type":"array","items":[{"type":"string"}],"additionalItems":{"${'$'}ref":"#/definitions/list"}},"content":{"contentSchema":{"${'$'}ref":"#/definitions/list"}}},"dependencies":{"trigger":{"properties":{"rows":{"${'$'}ref":"#/definitions/list"}}},"named":["other"]},"definitions":{"list":{"type":"array"}}}""")
        parametersOnEveryWire(input).forEach {
            assertFalse(it.toString().contains("\"${'$'}ref\""))
            assertFalse("definitions" in it)
            val props = it["properties"]!!.jsonObject
            assertEquals(JsonObject(emptyMap()), props["tuple"]!!.jsonObject["additionalItems"]!!.jsonObject["items"])
            assertEquals(JsonObject(emptyMap()), props["content"]!!.jsonObject["contentSchema"]!!.jsonObject["items"])
            assertEquals(JsonObject(emptyMap()), it["dependencies"]!!.jsonObject["trigger"]!!.jsonObject["properties"]!!.jsonObject["rows"]!!.jsonObject["items"])
            assertEquals(JsonArray(listOf(JsonPrimitive("other"))), it["dependencies"]!!.jsonObject["named"])
        }
    }

    @Test fun referencesToBooleanRootFailWithToolAndPathInsteadOfClassCast() {
        for (value in listOf(true, false)) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                normalize("""{"${'$'}ref":"#/definitions/root","definitions":{"root":$value}}""")
            }
            assertTrue(failure.message.orEmpty().contains("mcp_fixture"))
            assertTrue(failure.message.orEmpty().contains("parameters"))
        }
    }

    @Test fun dynamicReferencesAreRejectedBeforeDefinitionsCanBeRemoved() {
        listOf("${'$'}dynamicRef", "${'$'}recursiveRef").forEach { keyword ->
            assertThrows(IllegalArgumentException::class.java) { normalize("""{"properties":{"x":{"$keyword":"#node"}}}""") }
        }
    }

    @Test fun invalidRecognizedKeywordsFailWithTheSchemaPath() {
        listOf(
            """{"enum":[]}""", """{"enum":["x","x"]}""", """{"enum":"x"}""",
            """{"minItems":-1}""", """{"maxItems":1.5}""", """{"maxLength":"4"}""",
            """{"uniqueItems":"true"}""", """{"description":7}""",
            """{"multipleOf":0}""", """{"anyOf":[]}""",
            """{"dependentRequired":{"x":"y"}}""", """{"dependencies":{"x":["y","y"]}}"""
        ).forEach { bad ->
            val failure = assertThrows(IllegalArgumentException::class.java) { normalize("""{"properties":{"x":$bad}}""") }
            assertTrue(failure.message.orEmpty().contains("parameters.properties.x"))
        }
    }

    @Test fun unconstrainedItemsCannotEraseUnevaluatedItemRestrictions() {
        assertThrows(IllegalArgumentException::class.java) { normalize("""{"properties":{"x":{"type":"array","prefixItems":[{"type":"integer"}],"unevaluatedItems":false}}}""") }
        val result = normalize("""{"properties":{"x":{"type":"array","items":false,"prefixItems":[{"type":"integer"}],"unevaluatedItems":false}}}""")
        assertEquals(JsonPrimitive(false), result["properties"]!!.jsonObject["x"]!!.jsonObject["items"])
    }

    @Test fun nonObjectTypesDoNotAcquireObjectRequiredSemantics() {
        val result = normalize("""{"properties":{"x":{"type":"array","required":["ignoredOnArray"],"additionalProperties":false}}}""")
        assertEquals(JsonPrimitive("array"), result["properties"]!!.jsonObject["x"]!!.jsonObject["type"])
    }

    @Test fun invalidWrappersNamesAndDuplicatesFailInEveryNativeEncoder() {
        val tools = listOf("{}", "[null]", """[{"type":"custom","function":{"name":"x"}}]""",
            """[{"type":"function","function":{"name":7}}]""",
            """[{"type":"function","function":{"name":"not.a.name"}}]""",
            """[{"type":"function","function":{"name":"x","description":7}}]""",
            """[{"type":"function","function":{"name":"x","strict":"true"}}]""",
            """[{"type":"function","function":{"name":"x"}},{"type":"function","function":{"name":"x"}}]""")
        tools.forEach { raw ->
            val body = """{"model":"fixture","messages":[],"tools":$raw}"""
            listOf<() -> String>(
                { ProviderToolSchema.openAiRequest(body, Json) },
                { AnthropicMessagesAdapter.encodeRequest(body, false, Json) },
                { GoogleGenerateContentAdapter.encodeRequest(body, Json) },
                { OpenAiResponsesAdapter.encodeRequest(body, false, Json) },
            ).forEach { encoder -> assertThrows(IllegalArgumentException::class.java) { encoder() } }
        }
    }
}
