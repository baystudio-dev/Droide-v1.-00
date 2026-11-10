package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class AgentPluginSchemaAssertionTypeTest {
    private fun rejected(keyword:String,value:JsonElement) {
        val schema=buildJsonObject { put(keyword,value) }
        assertFalse("Malformed schema accepted: $schema",AgentPluginJsonSchema.schemaSupported(schema))
        assertThrows(IllegalArgumentException::class.java) { AgentPluginJsonSchema.requireValid(schema,buildJsonObject {}) }
    }
    @Test fun quotedBooleanAdditionalPropertiesRejected() {
        for (value in listOf("true","false")) rejected("additionalProperties",JsonPrimitive(value))
    }
    @Test fun quotedSizeBoundsRejected() {
        for (key in listOf("minLength","maxLength","minItems","maxItems","minProperties","maxProperties")) rejected(key,JsonPrimitive("2"))
    }
    @Test fun quotedNumericBoundsRejected() {
        for (key in listOf("minimum","maximum","exclusiveMinimum","exclusiveMaximum")) rejected(key,JsonPrimitive("1.5"))
    }
    @Test fun actualBooleanSchemasRemainSupported() {
        for (v in listOf(true,false)) assertTrue(AgentPluginJsonSchema.schemaSupported(buildJsonObject { put("additionalProperties",v) }))
    }
    @Test fun actualSizeBoundsRemainSupported() {
        for (key in listOf("minLength","maxLength","minItems","maxItems","minProperties","maxProperties")) assertTrue(AgentPluginJsonSchema.schemaSupported(buildJsonObject { put(key,2) }))
    }
    @Test fun actualNumericBoundsRemainSupported() {
        for (key in listOf("minimum","maximum","exclusiveMinimum","exclusiveMaximum")) assertTrue(AgentPluginJsonSchema.schemaSupported(buildJsonObject { put(key,1.5) }))
    }
    @Test fun additionalPropertySchemaStillValidatesChildren() {
        val schema=Json.parseToJsonElement("""{"additionalProperties":{"type":"integer"}}""").jsonObject
        assertTrue(AgentPluginJsonSchema.schemaSupported(schema))
        AgentPluginJsonSchema.requireValid(schema,buildJsonObject { put("x",2) })
        assertThrows(IllegalArgumentException::class.java) { AgentPluginJsonSchema.requireValid(schema,buildJsonObject { put("x","two") }) }
    }
    @Test fun malformedNestedAssertionRejected() {
        val schema=Json.parseToJsonElement("""{"properties":{"title":{"maxLength":"2"}}}""").jsonObject
        assertFalse(AgentPluginJsonSchema.schemaSupported(schema))
    }
    @Test fun nonPrimitiveBoundsRemainRejected() {
        rejected("maxLength",buildJsonObject {});rejected("minimum",buildJsonArray {});rejected("additionalProperties",JsonNull)
    }
    @Test fun pluginDiscoveryDoesNotAdvertiseMalformedAssertionTypes() {
        val root=java.nio.file.Files.createTempDirectory("schema-assertion-test").toFile()
        try {
            val plugin=java.io.File(root,"plugin").apply { mkdirs() }
            val contribution=AgentPluginContributionRoot("fixture","Fixture","1","",AgentPluginFormat.DROIDE,plugin,"fixture-sha")
            val manager=AgentPluginToolManager(root,AgentPluginSource { listOf(contribution) })
            for ((key,value) in listOf("additionalProperties" to JsonPrimitive("false"),"maxLength" to JsonPrimitive("2"),"minimum" to JsonPrimitive("2"))) {
                val schema=buildJsonObject { put(key,value) }
                java.io.File(plugin,"tools.json").writeText(buildJsonObject {
                    putJsonArray("tools") { add(buildJsonObject { put("name","inspect");putJsonArray("command") { add("inspect") };put("inputSchema",schema) }) }
                }.toString())
                assertTrue("Malformed contract advertised: $schema",manager.discover().isEmpty())
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
