package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class AgentPluginSchemaStringTypeTest {
    private fun schema(text:String)=Json.parseToJsonElement(text).jsonObject
    @Test fun requiredNumericEntryRejected() { assertFalse(AgentPluginJsonSchema.schemaSupported(schema("""{"required":[7]}"""))) }
    @Test fun requiredBooleanEntryRejected() { assertFalse(AgentPluginJsonSchema.schemaSupported(schema("""{"required":[true]}"""))) }
    @Test fun mixedRequiredEntryRejected() { assertFalse(AgentPluginJsonSchema.schemaSupported(schema("""{"required":["valid",7]}"""))) }
    @Test fun numericPatternRejected() { assertFalse(AgentPluginJsonSchema.schemaSupported(schema("""{"pattern":7}"""))) }
    @Test fun booleanPatternRejected() { assertFalse(AgentPluginJsonSchema.schemaSupported(schema("""{"pattern":false}"""))) }
    @Test fun nullAndStructuredValuesRemainRejected() {
        for (text in listOf("""{"pattern":null}""","""{"pattern":{}}""","""{"required":[null]}""","""{"required":[{}]}"""))
            assertFalse(AgentPluginJsonSchema.schemaSupported(schema(text)))
    }
    @Test fun numericLookingStringNamesRemainValid() {
        val s=schema("""{"required":["7","true"]}""")
        assertTrue(AgentPluginJsonSchema.schemaSupported(s))
        AgentPluginJsonSchema.requireValid(s,schema("""{"7":1,"true":2}"""))
    }
    @Test fun stringPatternStillValidates() {
        val s=schema("""{"properties":{"value":{"pattern":"^7+$"}}}""")
        assertTrue(AgentPluginJsonSchema.schemaSupported(s))
        AgentPluginJsonSchema.requireValid(s,schema("""{"value":"77"}"""))
        assertThrows(IllegalArgumentException::class.java) { AgentPluginJsonSchema.requireValid(s,schema("""{"value":"88"}""")) }
    }
    @Test fun emptyRequiredAndEmptyPatternRemainSupported() {
        assertTrue(AgentPluginJsonSchema.schemaSupported(schema("""{"required":[],"pattern":""}""")))
    }
    @Test fun malformedStringAssertionsAreNotAdvertisedByPluginDiscovery() {
        val root=java.nio.file.Files.createTempDirectory("schema-string-type").toFile()
        try {
            val plugin=java.io.File(root,"plugin").apply { mkdirs() }
            val contribution=AgentPluginContributionRoot("fixture","Fixture","1","",AgentPluginFormat.DROIDE,plugin,"fixture-sha")
            val manager=AgentPluginToolManager(root,AgentPluginSource { listOf(contribution) })
            for (input in listOf("""{"required":[7]}""","""{"pattern":7}""")) {
                java.io.File(plugin,"tools.json").writeText(buildJsonObject {
                    putJsonArray("tools") { add(buildJsonObject { put("name","inspect");putJsonArray("command") { add("inspect") };put("inputSchema",schema(input)) }) }
                }.toString())
                assertTrue("Malformed contract advertised: $input",manager.discover().isEmpty())
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
