package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class AgentPluginSchemaValueEqualityTest {
    private fun obj(s:String)=Json.parseToJsonElement(s).jsonObject
    private fun schema(keyword:String,value:String)=obj("""{"properties":{"value":{"$keyword":$value}}}""")
    private fun accepts(keyword:String,expected:String,actual:String) {
        val s=schema(keyword,expected);assertTrue(AgentPluginJsonSchema.schemaSupported(s))
        AgentPluginJsonSchema.requireValid(s,obj("""{"value":$actual}"""))
    }
    private fun rejects(keyword:String,expected:String,actual:String) {
        val s=schema(keyword,expected);assertTrue(AgentPluginJsonSchema.schemaSupported(s))
        assertThrows(IllegalArgumentException::class.java) { AgentPluginJsonSchema.requireValid(s,obj("""{"value":$actual}""")) }
    }
    @Test fun enumAcceptsEquivalentNumericSpellings() { accepts("enum","[1]","1.0");accepts("enum","[1000]","1e3") }
    @Test fun constAcceptsEquivalentNumericSpellings() { accepts("const","1.00","100e-2") }
    @Test fun signedZeroAndExtremeExponentsCompareExactly() {
        accepts("const","-0e999999999999","0.0")
        accepts("const","1e2147483648","10e2147483647")
        rejects("const","0","1e-2147483648")
    }
    @Test fun nestedObjectsAndArraysCompareValues() {
        accepts("const","""{"a":[1,{"b":2}],"c":true}""","""{"c":true,"a":[1.0,{"b":20e-1}]}""")
        accepts("enum","""[{"a":[1]}]""","""{"a":[1.0]}""")
    }
    @Test fun structuralDifferencesRemainDistinct() {
        rejects("const","[1,2]","[2,1]");rejects("const","[1]","[1,2]")
        rejects("const","""{"a":1}""","""{"b":1}""")
        rejects("const","""{"a":1}""","""{"a":1,"b":2}""")
        rejects("const","[]","{}")
    }
    @Test fun stringsBooleansAndNullNeverCoerceToNumbers() {
        rejects("enum","[1]",""" "1" """);rejects("const","1","true");rejects("const","0","false")
        rejects("const","0","null");rejects("const","null",""" "null" """)
        accepts("const","null","null");accepts("const","true","true");accepts("const",""" "1" """,""" "1" """)
    }
    @Test fun adjacentLargeAndTinyNumbersStayDistinct() {
        rejects("enum","[9007199254740992]","9007199254740993")
        rejects("const","1.00000000000000000001","1.00000000000000000002")
        rejects("const","1e-400","2e-400")
    }
    @Test fun combinatorsUseValueEquality() {
        AgentPluginJsonSchema.requireValid(obj("""{"properties":{"value":{"oneOf":[{"const":1},{"const":2}]}}}"""),obj("""{"value":1.0}"""))
        assertThrows(IllegalArgumentException::class.java) { AgentPluginJsonSchema.requireValid(obj("""{"properties":{"value":{"not":{"const":1}}}}"""),obj("""{"value":1.0}""")) }
    }
    @Test fun deeplyNestedLiteralsDoNotRequireRecursiveEquality() {
        var a:JsonElement=Json.parseToJsonElement("1")
        var b:JsonElement=Json.parseToJsonElement("1.0")
        repeat(2000) { a=JsonArray(listOf(a)); b=JsonArray(listOf(b)) }
        val schema=buildJsonObject { putJsonObject("properties") { putJsonObject("value") { put("const",a) } } }
        AgentPluginJsonSchema.requireValid(schema,buildJsonObject { put("value",b) })
    }
    @Test fun boundedNumericOracleAgreesWithBigDecimal() {
        val random=java.util.Random(907)
        repeat(200) {
            val n=random.nextInt(200000)-100000
            val scale=random.nextInt(41)-20
            val a=n.toString()+"e"+scale
            val b=n.toString()+".00e"+scale
            assertEquals(0,java.math.BigDecimal(a).compareTo(java.math.BigDecimal(b)))
            accepts("const",a,b)
            rejects("const",a,(n+1).toString()+"e"+scale)
        }
    }
    @Test fun invocationHashRemainsSpellingSensitive() {
        assertNotEquals(AgentPluginJsonSchema.invocationHash(obj("""{"value":1}""")),AgentPluginJsonSchema.invocationHash(obj("""{"value":1.0}""")))
    }
    @Test fun publicDiscoveryPermissionResourceAcceptsEquivalentValue() {
        val root=java.nio.file.Files.createTempDirectory("schema-equality").toFile()
        try {
            val plugin=java.io.File(root,"plugin").apply { mkdirs() }
            java.io.File(plugin,"tools.json").writeText(buildJsonObject {
                putJsonArray("tools") { add(buildJsonObject {
                    put("name","inspect");putJsonArray("command") { add("inspect") }
                    put("inputSchema",schema("enum","[1]"))
                }) }
            }.toString())
            val contribution=AgentPluginContributionRoot("fixture","Fixture","1","",AgentPluginFormat.DROIDE,plugin,"fixture-sha")
            val manager=AgentPluginToolManager(root,AgentPluginSource { listOf(contribution) })
            manager.permissionResource(manager.discover().single(),obj("""{"value":1.0}"""))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
