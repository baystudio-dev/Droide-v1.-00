package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderToolSchemaTest {
    private fun schema(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun normalize(text: String) = ProviderToolSchema.parameters(schema(text), "fixture")

    @Test fun webBatchesAdvertiseTypedPerCallLimitsAndRemainOptionalForSingleOpens() {
        val function = AgentToolCatalog.definitions.map { it.jsonObject.getValue("function").jsonObject }
            .single { it["name"] == JsonPrimitive("web") }
        val parameters = ProviderToolSchema.parameters(function.getValue("parameters").jsonObject, "web")
        val properties = parameters.getValue("properties").jsonObject
        for (name in listOf("urls", "ref_ids")) {
            val array = properties.getValue(name).jsonObject
            assertEquals(JsonPrimitive("array"), array["type"])
            assertEquals(JsonPrimitive(1), array["minItems"])
            assertEquals(JsonPrimitive(8), array["maxItems"])
            assertEquals(JsonPrimitive("string"), array.getValue("items").jsonObject["type"])
        }
        assertEquals(JsonPrimitive(3), properties.getValue("queries").jsonObject["maxItems"])
        assertEquals(JsonArray(listOf(JsonPrimitive("operation"))), parameters["required"])
        AgentPluginJsonSchema.requireValid(parameters, schema("""{"operation":"open","urls":["https://example.com/a","https://example.com/b"]}"""))
        AgentPluginJsonSchema.requireValid(parameters, schema("""{"operation":"open","url":"https://example.com/a"}"""))
        assertThrows(IllegalArgumentException::class.java) {
            AgentPluginJsonSchema.requireValid(parameters, schema("""{"operation":"open","urls":[]}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentPluginJsonSchema.requireValid(parameters, buildJsonObject {
                put("operation", "open"); put("urls", JsonArray((0..8).map { JsonPrimitive("https://example.com/$it") }))
            })
        }
    }

    @Test fun todosDeclareTypedItemsAndKeepReadOperationOptional() {
        val fn = AgentToolCatalog.definitions.map { it.jsonObject.getValue("function").jsonObject }.single { it["name"] == JsonPrimitive("todowrite") }
        val parameters = fn.getValue("parameters").jsonObject
        val array = parameters.getValue("properties").jsonObject.getValue("todos").jsonObject
        val item = array.getValue("items").jsonObject
        assertEquals(JsonPrimitive("object"), item["type"])
        assertEquals(setOf("content", "status", "priority"), item.getValue("properties").jsonObject.keys)
        assertEquals(schema("""{"required":["content"]}""")["required"], item["required"])
        assertTrue(parameters.getValue("required").jsonArray.isEmpty())
        AgentPluginJsonSchema.requireValid(parameters, schema("""{"todos":[{"content":"fix","status":"in_progress","priority":"high"}]}"""))
        AgentPluginJsonSchema.requireValid(parameters, schema("{}"))
        assertThrows(IllegalArgumentException::class.java) { AgentPluginJsonSchema.requireValid(parameters, schema("""{"todos":["bad"]}""")) }
    }

    @Test fun nestedObjectsReceiveExplicitEmptyRequiredWithoutMakingOptionalFieldsRequired() {
        val result = normalize("""{"type":"object","properties":{"options":{"type":"object","properties":{"limit":{"type":"integer"}}}}}""")
        assertEquals(JsonArray(emptyList()), result["required"])
        assertEquals(JsonArray(emptyList()), result["properties"]!!.jsonObject["options"]!!.jsonObject["required"])
    }

    @Test fun missingArrayItemsDoNotInventStringArguments() {
        val result = normalize("""{"type":"object","properties":{"values":{"type":"array"}}}""")
        assertEquals(schema("{}"), result["properties"]!!.jsonObject["values"]!!.jsonObject["items"])
    }

    @Test fun referencesAreExpandedThroughArraysAndEscapedPropertyNames() {
        val result = normalize("""{"type":"object","properties":{"entries":{"type":"array","items":{"${'$'}ref":"#/${'$'}defs/a~1b~0c"}}},"${'$'}defs":{"a/b~c":{"type":"object","properties":{"count":{"type":"integer","minimum":0}},"required":["count"]}}}""")
        val item = result["properties"]!!.jsonObject["entries"]!!.jsonObject["items"]!!.jsonObject
        assertEquals(JsonPrimitive("integer"), item["properties"]!!.jsonObject["count"]!!.jsonObject["type"])
        assertEquals(JsonPrimitive(0), item["properties"]!!.jsonObject["count"]!!.jsonObject["minimum"])
        assertFalse("${'$'}defs" in result)
        assertFalse("${'$'}ref" in item)
    }

    @Test fun referenceSiblingsRetainBothConflictingAssertions() {
        val result = normalize("""{"properties":{"n":{"${'$'}ref":"#/${'$'}defs/n","minimum":3}},"${'$'}defs":{"n":{"type":"integer","minimum":0}}}""")
        val all = result["properties"]!!.jsonObject["n"]!!.jsonObject.getValue("allOf").jsonArray
        assertEquals(setOf(JsonPrimitive(0), JsonPrimitive(3)), all.map { it.jsonObject["minimum"] }.toSet())
    }

    @Test fun referencesRejectExternalTargetsCyclesAndMissingTargetsWithToolName() {
        val inputs = listOf(
            """{"properties":{"p":{"${'$'}ref":"https://example.invalid/schema"}}}""",
            """{"properties":{"p":{"${'$'}ref":"#/${'$'}defs/a"}},"${'$'}defs":{"a":{"${'$'}ref":"#/${'$'}defs/a"}}}""",
            """{"properties":{"p":{"${'$'}ref":"#/missing"}}}""",
        )
        inputs.forEach { input ->
            val error = assertThrows(IllegalArgumentException::class.java) { normalize(input) }
            assertTrue(error.message.orEmpty().contains("fixture"))
            assertTrue(error.message.orEmpty().contains("parameters.properties.p"))
        }
    }

    @Test fun defaultsEnumsAndExamplesRemainLiteralData() {
        val source = schema("""{"type":"object","properties":{"payload":{"type":"object","default":{"type":"array","${'$'}ref":"literal","required":null},"examples":[{"properties":"literal"}]}}}""")
        val result = ProviderToolSchema.parameters(source, "literal")
        val original = source["properties"]!!.jsonObject["payload"]!!.jsonObject
        val actual = result["properties"]!!.jsonObject["payload"]!!.jsonObject
        assertEquals(original["default"], actual["default"])
        assertEquals(original["examples"], actual["examples"])
        assertEquals(JsonNull, original["default"]!!.jsonObject["required"])
    }

    @Test fun numericEnumsConstUnionsAndAdditionalPropertiesPreserveTheirTypes() {
        val result = normalize("""{"type":"object","properties":{"n":{"type":["integer","null"],"enum":[1,2,null]},"mode":{"const":"json"},"map":{"type":"object","additionalProperties":{"type":"integer"}}},"additionalProperties":false,"required":["n"]}""")
        val properties = result.getValue("properties").jsonObject
        assertEquals(JsonPrimitive(1), properties.getValue("n").jsonObject.getValue("enum").jsonArray[0])
        assertEquals(JsonPrimitive("json"), properties.getValue("mode").jsonObject["const"])
        assertEquals(JsonPrimitive("integer"), properties.getValue("map").jsonObject["additionalProperties"]!!.jsonObject["type"])
        assertEquals(JsonPrimitive(false), result["additionalProperties"])
        assertEquals(JsonArray(listOf(JsonPrimitive("n"))), result["required"])
    }

    @Test fun impossibleRequiredPropertyIsReportedInsteadOfBeingDropped() {
        assertThrows(IllegalArgumentException::class.java) { normalize("""{"type":"object","properties":{},"required":["missing"],"additionalProperties":false}""") }
    }

    @Test fun missingRootParametersAreValidNoArgumentObject() {
        val result = ProviderToolSchema.parameters(null, "empty")
        assertEquals(JsonPrimitive("object"), result["type"])
        assertEquals(schema("{}"), result["properties"])
        assertEquals(JsonArray(emptyList()), result["required"])
    }

    @Test fun malformedAndDeepSchemasFailBeforeProviderRequest() {
        listOf("""{"type":"array"}""", """{"type":"object","required":"bad"}""", """{"properties":{"p":{"type":"invalid"}}}""").forEach {
            assertThrows(IllegalArgumentException::class.java) { normalize(it) }
        }
        var deep: JsonObject = schema("""{"type":"string"}""")
        repeat(40) { deep = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(mapOf("p" to deep)))) }
        assertThrows(IllegalArgumentException::class.java) { ProviderToolSchema.parameters(deep, "deep") }
    }

    @Test fun normalizationIsIdempotentAndDoesNotMutateCatalog() {
        val source = schema("""{"type":"object","properties":{"x":{"type":"object"}},"required":null}""")
        val original = source.toString()
        val first = ProviderToolSchema.parameters(source, "stable")
        assertEquals(first, ProviderToolSchema.parameters(first, "stable"))
        assertEquals(original, source.toString())
    }

    @Test fun malformedExclusiveBoundsFailWithTheirExactToolAndPropertyPath() {
        listOf("\"zero\"", "null", "[]", "{}", "true").forEach { value ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                normalize("""{"properties":{"n":{"type":"number","exclusiveMinimum":$value}}}""")
            }
            assertTrue(failure.message.orEmpty().contains("fixture"))
            assertTrue(failure.message.orEmpty().contains("parameters.properties.n.exclusiveMinimum"))
        }
    }

    @Test fun modernNumericBoundsAndExplicitLegacyBoundsKeepTheirMeaning() {
        val modern = normalize("""{"properties":{"n":{"type":"number","exclusiveMinimum":-0.5,"exclusiveMaximum":2}}}""")
        assertEquals(Json.parseToJsonElement("-0.5"), modern["properties"]!!.jsonObject["n"]!!.jsonObject["exclusiveMinimum"])
        val legacy = normalize("""{"${'$'}schema":"http://json-schema.org/draft-04/schema#","properties":{"n":{"type":"number","minimum":-0.5,"exclusiveMinimum":true,"maximum":2,"exclusiveMaximum":false}}}""")
        val n = legacy["properties"]!!.jsonObject["n"]!!.jsonObject
        assertEquals(Json.parseToJsonElement("-0.5"), n["exclusiveMinimum"])
        assertFalse("minimum" in n)
        assertFalse("exclusiveMaximum" in n)
        assertEquals(JsonPrimitive(2), n["maximum"])
        assertEquals(legacy, ProviderToolSchema.parameters(legacy, "stable"))
    }

    @Test fun legacyBooleanBoundsCannotUseMissingOrNonNumericInclusiveBounds() {
        listOf("", "\"minimum\":\"zero\",").forEach { bound ->
            assertThrows(IllegalArgumentException::class.java) {
                normalize("""{"${'$'}schema":"https://json-schema.org/draft-04/schema","properties":{"n":{${bound}"exclusiveMinimum":true}}}""")
            }
        }
    }
}
