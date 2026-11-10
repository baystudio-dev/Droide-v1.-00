package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class AgentPluginSchemaCountNumberTest {
    private val keys=listOf("minLength","maxLength","minItems","maxItems","minProperties","maxProperties")
    private fun obj(s:String)=Json.parseToJsonElement(s).jsonObject
    private fun schema(key:String,n:String)=obj("""{"properties":{"value":{"$key":$n}}}""")
    @Test fun mathematicalIntegerSpellingsAreSupportedForEveryCountKeyword() {
        for(key in keys) for(n in listOf("2.0","2e0","20e-1","0.000","-0.0","2147483647.0")) assertTrue("$key=$n",AgentPluginJsonSchema.schemaSupported(schema(key,n)))
    }
    @Test(timeout=5000) fun fractionalNegativeAndOutOfRangeCountsRemainUnsupported() {
        for(key in keys) for(n in listOf("2.1","-1.0","2147483648","2147483647.1","1e1000000","1e-1000000")) assertFalse("$key=$n",AgentPluginJsonSchema.schemaSupported(schema(key,n)))
    }
    @Test fun quotedBooleanAndNullValuesRemainUnsupported() {
        for(key in keys) for(n in listOf(""""2.0"""","true","false","null")) assertFalse("$key=$n",AgentPluginJsonSchema.schemaSupported(schema(key,n)))
    }
    @Test fun equivalentMinimaAreActuallyEnforced() {
        for((key,tooSmall,enough) in listOf(Triple("minLength",""""x"""",""""xy""""),Triple("minItems","[1]","[1,2]"),Triple("minProperties","""{"x":1}""","""{"x":1,"y":2}"""))) {
            val s=schema(key,"2.0")
            AgentPluginJsonSchema.requireValid(s,obj("""{"value":$enough}"""))
            assertThrows(IllegalArgumentException::class.java) { AgentPluginJsonSchema.requireValid(s,obj("""{"value":$tooSmall}""")) }
        }
    }
    @Test fun equivalentMaximaAreActuallyEnforced() {
        for((key,tooLarge,enough) in listOf(Triple("maxLength",""""xyz"""",""""xy""""),Triple("maxItems","[1,2,3]","[1,2]"),Triple("maxProperties","""{"x":1,"y":2,"z":3}""","""{"x":1,"y":2}"""))) {
            val s=schema(key,"20e-1")
            AgentPluginJsonSchema.requireValid(s,obj("""{"value":$enough}"""))
            assertThrows(IllegalArgumentException::class.java) { AgentPluginJsonSchema.requireValid(s,obj("""{"value":$tooLarge}""")) }
        }
    }
    @Test fun signedZeroMaximumAllowsEmptyButRejectsNonempty() {
        val s=schema("maxItems","-0.0")
        AgentPluginJsonSchema.requireValid(s,obj("""{"value":[]}"""))
        assertThrows(IllegalArgumentException::class.java) { AgentPluginJsonSchema.requireValid(s,obj("""{"value":[1]}""")) }
    }
    @Test fun normalIntegerBoundaryKeepsExistingBehavior() {
        val s=schema("maxLength","2147483647")
        AgentPluginJsonSchema.requireValid(s,obj("""{"value":"normal"}"""))
        assertTrue(AgentPluginJsonSchema.schemaSupported(schema("minItems","0")))
    }
    @Test fun actualDiscoveryKeepsValidDecimalCountSchemaAndPermissionEnforcesIt() {
        val root=java.nio.file.Files.createTempDirectory("schema-count").toFile()
        try {
            val plugin=java.io.File(root,"plugin").apply { mkdirs() }
            java.io.File(plugin,"tools.json").writeText(buildJsonObject { putJsonArray("tools") { add(buildJsonObject {
                put("name","inspect");putJsonArray("command") { add("inspect") };put("inputSchema",schema("maxItems","2.0"))
            }) } }.toString())
            val contribution=AgentPluginContributionRoot("fixture","Fixture","1","",AgentPluginFormat.DROIDE,plugin,"fixture-sha")
            val m=AgentPluginToolManager(root,AgentPluginSource { listOf(contribution) })
            assertEquals(1,m.discover().size);val tool=m.discover().single()
            m.permissionResource(tool,obj("""{"value":[1,2]}"""))
            assertThrows(IllegalArgumentException::class.java) { m.permissionResource(tool,obj("""{"value":[1,2,3]}""")) }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
