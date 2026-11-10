package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class AgentPluginSchemaExactBoundsTest {
    private fun schema(keyword:String,bound:String)=Json.parseToJsonElement("{\"properties\":{\"count\":{\"type\":\"number\",\""+keyword+"\":"+bound+"}}}").jsonObject
    private fun input(number:String)=Json.parseToJsonElement("{\"count\":"+number+"}").jsonObject
    private fun accepts(keyword:String,bound:String,number:String) {
        val s=schema(keyword,bound); assertTrue(AgentPluginJsonSchema.schemaSupported(s));AgentPluginJsonSchema.requireValid(s,input(number))
    }
    private fun rejects(keyword:String,bound:String,number:String) {
        val s=schema(keyword,bound);assertTrue(AgentPluginJsonSchema.schemaSupported(s))
        assertThrows(IllegalArgumentException::class.java) { AgentPluginJsonSchema.requireValid(s,input(number)) }
    }
    @Test fun maximumDoesNotRoundBeyondDoubleIntegerPrecision() { rejects("maximum","9007199254740992","9007199254740993") }
    @Test fun minimumDoesNotRoundBeyondDoubleIntegerPrecision() { rejects("minimum","9007199254740993","9007199254740992") }
    @Test fun exclusiveMinimumDoesNotRejectDistinctLargerValue() { accepts("exclusiveMinimum","9007199254740992","9007199254740993") }
    @Test fun exclusiveMaximumDoesNotRejectDistinctSmallerValue() { accepts("exclusiveMaximum","9007199254740993","9007199254740992") }
    @Test fun fractionalPrecisionIsPreserved() {
        rejects("maximum","1.00000000000000000001","1.00000000000000000002")
        accepts("exclusiveMinimum","1.00000000000000000001","1.00000000000000000002")
    }
    @Test fun equivalentSpellingsAreEqual() {
        accepts("minimum","1e3","1000.00");accepts("maximum","10.0","1e1")
        rejects("exclusiveMaximum","1.00","100e-2");rejects("exclusiveMinimum","1e3","1000")
    }
    @Test fun negativeBoundsReverseMagnitudeCorrectly() {
        rejects("minimum","-9007199254740992","-9007199254740993")
        accepts("exclusiveMaximum","-9007199254740992","-9007199254740993")
        accepts("minimum","-2","-1.5");rejects("maximum","-2","-1.5")
    }
    @Test fun zeroAndSignedZeroAreEqual() {
        accepts("minimum","0","-0.0");accepts("maximum","-0e100","0")
        rejects("exclusiveMinimum","0","-0.000");rejects("exclusiveMaximum","-0","0")
    }
    @Test fun underflowValuesRemainDistinctFromZero() {
        accepts("exclusiveMinimum","0","1e-400")
        rejects("minimum","0","-1e-400")
        rejects("maximum","0","1e-400")
    }
    @Test fun exponentOutsideBigDecimalScaleRangeIsNotExpanded() {
        accepts("exclusiveMinimum","0","1e-2147483648")
        rejects("maximum","1","1e2147483648")
        accepts("minimum","1","1e2147483648")
    }
    @Test fun tinySchemaBoundKeepsItsExactValue() {
        rejects("minimum","2e-400","1e-400");accepts("exclusiveMinimum","1e-400","2e-400")
    }
    @Test(timeout=15000) fun largeExponentAndMantissaStayInputSized() {
        val start=System.nanoTime()
        rejects("maximum","1","1e"+"9".repeat(30000))
        accepts("exclusiveMinimum","0","1e-"+"9".repeat(30000))
        rejects("maximum","1","9".repeat(30000))
        println("Exact comparison 30k-digit cases ms="+(System.nanoTime()-start)/1000000)
    }
    @Test fun boundedOracleMatchesBigDecimal() {
        val random=java.util.Random(709)
        repeat(300) {
            val left=(random.nextInt(200000)-100000).toString()+"e"+(random.nextInt(41)-20)
            val right=(random.nextInt(200000)-100000).toString()+"e"+(random.nextInt(41)-20)
            val cmp=java.math.BigDecimal(left).compareTo(java.math.BigDecimal(right))
            for ((key,valid) in listOf("minimum" to (cmp>=0),"maximum" to (cmp<=0),"exclusiveMinimum" to (cmp>0),"exclusiveMaximum" to (cmp<0))) {
                if(valid) accepts(key,right,left) else rejects(key,right,left)
            }
        }
    }
    @Test fun actualDiscoveryPermissionValidationRejectsRoundedOverflow() {
        val root=java.nio.file.Files.createTempDirectory("schema-exact-bounds").toFile()
        try {
            val plugin=java.io.File(root,"plugin").apply { mkdirs() }
            java.io.File(plugin,"tools.json").writeText(buildJsonObject {
                putJsonArray("tools") { add(buildJsonObject {
                    put("name","inspect");putJsonArray("command") { add("inspect") }
                    put("inputSchema",schema("maximum","9007199254740992"))
                }) }
            }.toString())
            val contribution=AgentPluginContributionRoot("fixture","Fixture","1","",AgentPluginFormat.DROIDE,plugin,"fixture-sha")
            val manager=AgentPluginToolManager(root,AgentPluginSource { listOf(contribution) })
            val tool=manager.discover().single()
            manager.permissionResource(tool,input("9007199254740992"))
            assertThrows(IllegalArgumentException::class.java) { manager.permissionResource(tool,input("9007199254740993")) }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
