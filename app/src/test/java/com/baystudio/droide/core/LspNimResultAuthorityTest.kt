package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspNimResultAuthorityTest {
    private fun range(sl: Int, sc: Int, el: Int, ec: Int): JsonObject = buildJsonObject {
        put("start", buildJsonObject { put("line", sl); put("character", sc) })
        put("end", buildJsonObject { put("line", el); put("character", ec) })
    }
    @Test fun unicodeUsesCapturedUtf16NotHybridNativeByteCoordinates() {
        val text = "é😀 值"
        assertTrue(LspNimResultAuthority.validRange(text, range(0, 4, 0, 5)))
        assertFalse(LspNimResultAuthority.validRange(text, range(0, 7, 0, 8)))
        assertFalse(LspNimResultAuthority.validPosition(text, 0, 2))
        assertTrue(LspNimResultAuthority.validPosition(text, 0, 3))
    }
    @Test fun crlfTrailingLinesAndNoFinalNewlineValidateExactly() {
        val text = "a\r\n😀\n"
        assertTrue(LspNimResultAuthority.validRange(text, range(0, 1, 1, 2)))
        assertFalse(LspNimResultAuthority.validPosition(text, 0, 2))
        assertTrue(LspNimResultAuthority.validPosition(text, 2, 0))
        assertFalse(LspNimResultAuthority.validPosition(text, 3, 0))
        assertTrue(LspNimResultAuthority.validPosition("last", 0, 4))
        assertFalse(LspNimResultAuthority.validPosition("last", 1, 0))
    }
    @Test fun malformedReversedNegativeAndOverflowRangesFail() {
        listOf(JsonNull, JsonPrimitive("range"), range(0, 2, 0, 1), range(-1, 0, 0, 1),
            range(0, Int.MAX_VALUE, 0, Int.MAX_VALUE),
            Json.parseToJsonElement("""{"start":{"line":"0","character":0},"end":{"line":0,"character":1}}"""),
            Json.parseToJsonElement("""{"start":{"line":0.5,"character":0},"end":{"line":0,"character":1}}"""))
            .forEach { assertFalse(LspNimResultAuthority.validRange("abc", it)) }
    }
    @Test fun editsRejectWholeOverlappingMalformedOrSplitSurrogateSet() {
        LspNimResultAuthority.requireEdits("abc", listOf(TextRangeEdit(1, 1, 1, 2, "a"), TextRangeEdit(1, 3, 1, 4, "c")))
        val invalid = listOf(
            listOf(TextRangeEdit(1, 1, 1, 3, "x"), TextRangeEdit(1, 2, 1, 4, "y")),
            listOf(TextRangeEdit(1, 1, 1, 2, "x"), TextRangeEdit(2, 1, 2, 1, "y")),
            listOf(TextRangeEdit(1, 1, 1, 2, "\uD800")),
        )
        invalid.forEach { assertTrue(runCatching { LspNimResultAuthority.requireEdits("abc", it) }.isFailure) }
        assertTrue(runCatching { LspNimResultAuthority.requireEdits("😀", listOf(TextRangeEdit(1, 2, 1, 3, "x"))) }.isFailure)
        assertTrue(runCatching { LspNimResultAuthority.requireEdits("", List(4097) { TextRangeEdit(1, 1, 1, 1, "") }) }.isFailure)
    }
    @Test fun completionIsNativePlainLabelEvenForSnippetKind() {
        val native = Json.parseToJsonElement("""[{"label":"foo","kind":15,"detail":"proc foo()","documentation":"native"}]""")
        val result = LspNimResultAuthority.plainCompletion(native)
        assertEquals(1, result.size)
        assertEquals(JsonPrimitive("foo"), result[0].jsonObject["label"])
        assertEquals(JsonPrimitive(1), result[0].jsonObject["insertTextFormat"])
        assertFalse(result[0].jsonObject.containsKey("textEdit"))
    }
    @Test fun completionNeverPromotesResolveSnippetsCommandsOrEdits() {
        val native = Json.parseToJsonElement("""[
            {"label":"good","kind":3},
            {"label":"bad","insertTextFormat":2},
            {"label":"bad","textEdit":{"newText":"x"}},
            {"label":"bad","additionalTextEdits":[]},
            {"label":"bad","command":{}},
            {"label":"bad","data":{}},
            {"label":"bad","kind":"3"},
            {"label":"bad","documentation":{}},
            {"label":123},
            {"label":"line\nbreak"}
        ]""")
        assertEquals(listOf("good"), LspNimResultAuthority.plainCompletion(native).map { it.jsonObject["label"]!!.jsonPrimitive.content })
        assertTrue(LspNimResultAuthority.plainCompletion(buildJsonObject { put("items", native) }).isEmpty())
    }
    @Test fun completionBoundsAndUnpairedSurrogatesAreRejected() {
        val bad = buildJsonArray {
            add(buildJsonObject { put("label", "x".repeat(2001)) })
            add(buildJsonObject { put("label", "\uD800") })
        }
        assertTrue(LspNimResultAuthority.plainCompletion(bad).isEmpty())
        assertEquals(512, LspNimResultAuthority.plainCompletion(JsonArray(List(600) { buildJsonObject { put("label", "x$it") } })).size)
    }
    @Test fun signaturesPreserveLabelsButNeverClaimActiveParameterOrOffsets() {
        val native = Json.parseToJsonElement("""{"signatures":[{"label":"foo(a: int)","documentation":"native", "parameters":[{"label":[4,10]}],"activeParameter":0}],"activeParameter":0,"activeSignature":0}""")
        val value = LspNimResultAuthority.signatureLabels(native).jsonObject
        assertFalse(value.containsKey("activeParameter"))
        assertFalse(value.containsKey("activeSignature"))
        val signature = value["signatures"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("foo(a: int)"), signature["label"])
        assertTrue(signature["parameters"]!!.jsonArray.isEmpty())
        assertFalse(signature.containsKey("activeParameter"))
    }
    @Test fun signatureMalformedEmptyOrOversizedValuesAreUnavailable() {
        assertEquals(JsonNull, LspNimResultAuthority.signatureLabels(JsonNull))
        assertEquals(JsonNull, LspNimResultAuthority.signatureLabels(Json.parseToJsonElement("""{"signatures":[{"label":12}]}""")))
        assertEquals(JsonNull, LspNimResultAuthority.signatureLabels(buildJsonObject {
            put("signatures", buildJsonArray { add(buildJsonObject { put("label", "x".repeat(2001)) }) })
        }))
    }
}
