package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class AgentPluginSchemaDiagnosticsTest {
    private fun failure(schema:String,input:String):String = assertThrows(IllegalArgumentException::class.java) {
        AgentPluginJsonSchema.requireValid(Json.parseToJsonElement(schema).jsonObject,Json.parseToJsonElement(input).jsonObject)
    }.message.orEmpty()
    @Test fun nestedPropertyNamesLocateWrongType() {
        val message=failure("""{"properties":{"options":{"properties":{"count":{"type":"integer"}}}}}""","""{"options":{"count":"oops"}}""")
        assertTrue(message,message.contains("$.options.count has the wrong type"))
    }
    @Test fun arrayItemIndexLocatesWrongType() {
        val message=failure("""{"properties":{"items":{"items":{"type":"integer"}}}}""","""{"items":[1,"oops"]}""")
        assertTrue(message,message.contains("$.items[1] has the wrong type"))
    }
    @Test fun missingRequiredPropertyIsNamed() {
        val message=failure("""{"required":["target"]}""","""{}""")
        assertTrue(message,message.contains("$ is missing required property 'target'"))
    }
    @Test fun unexpectedPropertyIsNamed() {
        val message=failure("""{"additionalProperties":false}""","""{"extra":1}""")
        assertTrue(message,message.contains("$ contains unexpected property 'extra'"))
    }
    @Test fun stringBoundAndPropertyAreActual() {
        val message=failure("""{"properties":{"title":{"maxLength":2}}}""","""{"title":"long"}""")
        assertTrue(message,message.contains("$.title is longer than 2 characters"))
    }
    @Test fun arrayBoundAndPropertyAreActual() {
        val message=failure("""{"properties":{"items":{"minItems":2}}}""","""{"items":[]}""")
        assertTrue(message,message.contains("$.items has fewer than 2 items"))
    }
    @Test fun schemaKeywordIsNamed() {
        val message=failure("""{"unknownKeyword":true}""","""{}""")
        assertEquals("Unsupported Agent plugin schema keyword: unknownKeyword",message)
    }
    @Test fun invalidCompositionKeywordIsNamed() {
        val message=failure("""{"anyOf":[]}""","""{}""")
        assertEquals("Invalid anyOf schema",message)
    }
    @Test fun dynamicPropertyNamesAreData() {
        val key="literal"+"$"+"name"
        val message=failure("""{"additionalProperties":false}""",buildJsonObject { put(key,1) }.toString())
        assertTrue(message,message.contains("unexpected property '"+key+"'"))
    }
    @Test fun validInputStillAcceptedAndErrorRemainsBounded() {
        AgentPluginJsonSchema.requireValid(Json.parseToJsonElement("""{"properties":{"count":{"type":"integer"}}}""").jsonObject,Json.parseToJsonElement("""{"count":2}""").jsonObject)
        val input=buildJsonObject { put("x".repeat(1000),1) }.toString()
        val message=failure("""{"additionalProperties":false}""",input)
        assertTrue(message.length <= "Agent plugin input failed schema validation: ".length+600)
    }
}
