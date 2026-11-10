package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LspInlayLabelUnicodeTest {
    private fun parse(label:JsonElement):LspInlayHint {
        val payload=buildJsonObject {
            put("position",buildJsonObject {put("line",0);put("character",0)})
            put("label",label)
        }
        val method=LspInlayHintProtocol::class.java.declaredMethods.single {it.name=="parse"}.also {it.isAccessible=true}
        return method.invoke(LspInlayHintProtocol,payload,0,100) as LspInlayHint
    }
    @Test fun scalarPreviewDoesNotSplitSurrogatePair() {
        assertEquals("a".repeat(79),parse(JsonPrimitive("a".repeat(79)+"🙂")).label)
    }
    @Test fun labelPartPreviewDoesNotSplitSurrogatePair() {
        assertEquals("a".repeat(79),parse(buildJsonArray {add(buildJsonObject {put("value","a".repeat(79)+"🙂")})}).label)
    }
    @Test fun combinedPartsPreviewDoesNotSplitSurrogatePair() {
        assertEquals("a".repeat(79),parse(buildJsonArray {
            add(buildJsonObject {put("value","a".repeat(79))});add(buildJsonObject {put("value","🙂")})
        }).label)
    }
    @Test fun completePairAtBoundaryPreserved() {
        val label="a".repeat(78)+"🙂"
        assertEquals(label,parse(JsonPrimitive(label)).label)
    }
    @Test fun asciiLimitUnchanged() {
        assertEquals("a".repeat(80),parse(JsonPrimitive("a".repeat(100))).label)
    }
    @Test fun truncatedPartCannotPullLaterTextAheadOfOmittedCharacter() {
        assertEquals("a".repeat(79),parse(buildJsonArray {
            add(buildJsonObject {put("value","a".repeat(79)+"🙂")});add(buildJsonObject {put("value","Z")})
        }).label)
    }

}
