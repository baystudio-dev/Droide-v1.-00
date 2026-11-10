package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LspSemanticTokensMalformedTest {
    private fun legend(types: String, modifiers: String = "[]") = LspSemanticTokensProtocol.legendFromProvider(
        Json.parseToJsonElement("""{"legend":{"tokenTypes":$types,"tokenModifiers":$modifiers}}""").jsonObject)
    @Test fun objectLegendDoesNotThrow() { assertTrue(legend("""[{},"class"]""").tokenTypes.isEmpty()) }
    @Test fun emptyTypeDoesNotReindexFollowingType() { assertTrue(legend("""["","class"]""").tokenTypes.isEmpty()) }
    @Test fun numericLegendIsNotAName() { assertTrue(legend("""[42,"class"]""").tokenTypes.isEmpty()) }
    @Test fun invalidModifierDoesNotShiftBitPositions() { assertTrue(legend("""["class"]""","""["","deprecated"]""").tokenTypes.isEmpty()) }
    @Test fun nullLegendEntryDoesNotBecomeLiteralNull() { assertTrue(legend("""[null,"class"]""").tokenTypes.isEmpty()) }
    @Test fun objectTokenDoesNotThrow() {
        assertTrue(LspSemanticTokensProtocol.parseFull(Json.parseToJsonElement("""{"data":[0,0,1,{},0]}"""),LspSemanticTokenLegend(listOf("class"))).isEmpty())
    }
    @Test fun quotedTokenNumberIsRejected() {
        assertTrue(LspSemanticTokensProtocol.parseFull(Json.parseToJsonElement("""{"data":[0,0,1,"0",0]}"""),LspSemanticTokenLegend(listOf("class"))).isEmpty())
    }
    @Test fun validLegendIndicesAndBitsUnchanged() {
        val result=LspSemanticTokensProtocol.parseFull(Json.parseToJsonElement("""{"data":[0,0,2,1,2]}"""),legend("""["class","parameter"]""","""["definition","deprecated"]"""))
        assertEquals("parameter",result.single().type);assertEquals(setOf("deprecated"),result.single().modifiers)
    }
}
