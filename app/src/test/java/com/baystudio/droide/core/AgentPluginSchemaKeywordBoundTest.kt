package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class AgentPluginSchemaKeywordBoundTest {
    private val prefix="Unsupported Agent plugin schema keyword: "
    private fun error(key:String):String {
        val schema=buildJsonObject { put(key,true) }
        assertFalse(AgentPluginJsonSchema.schemaSupported(schema))
        return assertThrows(IllegalArgumentException::class.java) { AgentPluginJsonSchema.requireValid(schema, buildJsonObject {}) }.message.orEmpty()
    }
    @Test fun ordinaryKeywordStillIdentified() { assertEquals(prefix+"unknown",error("unknown")) }
    @Test fun exactBoundaryIsUnchanged() { val key="x".repeat(600-prefix.length); assertEquals(prefix+key,error(key)) }
    @Test fun longKeywordMessageIsBounded() { val message=error("x".repeat(24000)); assertTrue(message.length<=600);assertTrue(message.endsWith("…")) }
    @Test fun oneOverBoundaryShowsExplicitTruncation() { val message=error("x".repeat(601-prefix.length));assertEquals(600,message.length);assertTrue(message.endsWith("…")) }
    @Test fun unicodeBoundaryDoesNotSplitValidPair() {
        for (lead in listOf("","x")) {
            val message=error(lead+"🙂".repeat(600))
            assertTrue(message.length<=600);assertTrue(message.endsWith("…"))
            assertFalse(message.dropLast(1).last().isHighSurrogate())
        }
    }
}
