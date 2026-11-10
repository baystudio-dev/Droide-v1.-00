package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspCompletionResultTruthTest {
    @Test fun explicitNewTextWinsEvenWhenEmpty() {
        assertEquals("RIGHT", parse(item("RIGHT")).single().insertText)
        val deletion = parse(item("")).single()
        assertEquals("", deletion.insertText)
        assertEquals(1, deletion.editStartColumn)
        assertEquals(3, deletion.editEndColumn)
    }

    @Test fun snippetFormatAppliesToWinningTextEditAndKeepsLiteralAdditionalEdits() {
        val snippet = "hello(${'$'}{1:name})${'$'}0"
        val value = item(snippet) + mapOf("insertTextFormat" to JsonPrimitive(2), "additionalTextEdits" to buildJsonArray {
            add(buildJsonObject { put("range", range(1, 0, 1, 0)); put("newText", "literal ${'$'}1") })
        })
        val result = parse(JsonObject(value), "ab\n").single()
        assertEquals(snippet, result.insertText)
        assertTrue(result.isSnippet)
        assertEquals("literal ${'$'}1", result.additionalTextEdits.single().newText)
    }

    @Test fun insertionReplacementPairUsesValidatedReplaceRangeForCurrentEditor() {
        val edit = buildJsonObject {
            put("newText", "RIGHT")
            put("insert", range(0, 0, 0, 2))
            put("replace", range(0, 0, 0, 4))
        }
        val parsed = parse(JsonObject(item("unused") + ("textEdit" to edit)), "abcd").single()
        assertEquals("RIGHT", parsed.insertText)
        assertEquals(5, parsed.editEndColumn)
    }

    @Test fun malformedExplicitEditsNeverFallBackToConflictingInsertText() {
        val malformed = listOf(
            JsonNull,
            buildJsonObject { put("range", range(0, 0, 0, 2)) },
            buildJsonObject { put("newText", "safe"); put("range", range(0, -1, 0, 2)) },
            buildJsonObject { put("newText", "safe"); put("range", range(0, 0, 1, 2)) },
            buildJsonObject { put("newText", "safe"); put("range", range(0, 2, 0, 0)) },
            buildJsonObject { put("newText", "safe"); put("insert", range(0, 1, 0, 2)); put("replace", range(0, 0, 0, 4)) },
            buildJsonObject { put("newText", "safe"); put("insert", range(0, 0, 0, 4)); put("replace", range(0, 0, 0, 2)) },
        )
        malformed.forEach { edit -> assertTrue(edit.toString(), parse(JsonObject(item("unused") + ("textEdit" to edit))).isEmpty()) }
    }

    @Test fun actualCursorAndDocumentBoundsRejectUnusableRanges() {
        assertTrue(LspCompletionProtocol.parse(JsonArray(listOf(item("RIGHT"))), 2, 2, "ab").isEmpty())
        assertTrue(LspCompletionProtocol.parse(JsonArray(listOf(item("RIGHT"))), 1, 4, "abcd").isEmpty())
        assertTrue(parse(item("RIGHT"), "a").isEmpty())
        val surrogate = JsonObject(item("RIGHT") + ("textEdit" to buildJsonObject {
            put("newText", "RIGHT"); put("range", range(0, 1, 0, 2))
        }))
        assertTrue(parse(surrogate, "\uD83D\uDE00").isEmpty())
    }

    @Test fun snippetSafetyRunsOnNewTextRatherThanIgnoredInsertText() {
        val value = JsonObject(item("`command`${'$'}1") + ("insertTextFormat" to JsonPrimitive(2)))
        val result = parse(value).single()
        assertEquals("\\`command\\`${'$'}1", result.insertText)
        assertTrue(result.isSnippet)
    }

    @Test fun excessiveSnippetMarkersUsePlainFallbackOnWinningText() {
        val value = JsonObject(item("x" + "${'$'}1".repeat(97)) + ("insertTextFormat" to JsonPrimitive(2)))
        val result = parse(value).single()
        assertEquals("x", result.insertText)
        assertFalse(result.isSnippet)
    }

    @Test fun simpleInsertTextAndLabelFallbackRemainSupported() {
        val values = Json.parseToJsonElement("""[{"label":"insert","insertText":"VALUE"},{"label":"fallback"}]""")
        assertEquals(listOf("VALUE", "fallback"), LspCompletionProtocol.parse(values).map { it.insertText })
    }

    @Test fun additionalEditConflictOrMalformedPayloadCannotPartiallyApplyCompletion() {
        val overlap = buildJsonObject { put("range", range(0, 1, 0, 2)); put("newText", "overlap") }
        val malformed = buildJsonObject { put("range", range(1, 0, 1, 0)) }
        listOf(overlap, malformed).forEach { extra ->
            assertTrue(parse(JsonObject(item("RIGHT") + ("additionalTextEdits" to JsonArray(listOf(extra)))), "ab\n").isEmpty())
        }
    }

    @Test fun differentReplacementEndsAreNotDeduplicated() {
        val shorter = item("RIGHT")
        val longer = JsonObject(shorter + ("textEdit" to buildJsonObject { put("newText", "RIGHT"); put("range", range(0, 0, 0, 3)) }))
        assertEquals(2, LspCompletionProtocol.parse(JsonArray(listOf(shorter, longer)), 1, 2, "abc").size)
    }

    @Test fun bulkCompletionValidatesAgainstOneLargeRequestSnapshot() {
        val text = "x\n".repeat(250_000) + "abcd"
        val values = JsonArray((0 until 240).map { index -> buildJsonObject {
            put("label", "candidate$index")
            put("textEdit", buildJsonObject { put("newText", "value$index"); put("range", range(250_000, 0, 250_000, 4)) })
        } })
        val result = LspCompletionProtocol.parse(values, 250_001, 3, text)
        assertEquals(240, result.size)
        assertEquals(5, result.last().editEndColumn)
    }

    @Test fun requestBoundsRespectCrlfAndRejectIncompleteReplacementCharacters() {
        assertEquals(1, parse(item("RIGHT"), "ab\r\n").size)
        val beyondCr = JsonObject(item("RIGHT") + ("textEdit" to buildJsonObject {
            put("newText", "RIGHT"); put("range", range(0, 0, 0, 3))
        }))
        assertTrue(parse(beyondCr, "ab\r\n").isEmpty())
        assertTrue(parse(item("\uD83D")).isEmpty())
    }

    private fun parse(value: JsonObject, text: String = "ab") = LspCompletionProtocol.parse(JsonArray(listOf(value)), 1, 2, text)
    private fun item(newText: String) = buildJsonObject {
        put("label", "example"); put("insertText", "WRONG")
        put("textEdit", buildJsonObject { put("newText", newText); put("range", range(0, 0, 0, 2)) })
    }
    private fun range(sl: Int, sc: Int, el: Int, ec: Int) = buildJsonObject {
        put("start", buildJsonObject { put("line", sl); put("character", sc) })
        put("end", buildJsonObject { put("line", el); put("character", ec) })
    }
}
