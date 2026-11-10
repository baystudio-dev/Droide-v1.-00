package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspVueCompletionTest {
    private val text = "name\nimport x\n"
    private val guard = LspCompletionApplicationGuard(text, LspCompletionCaret(1, 1)) { true }
    private fun item(origin: String = "vue", id: String = "vue:0"): JsonObject = buildJsonObject {
        put("label", "same"); put("droideVueOrigin", origin); put("droideVueBatch", 1); put("droideVueItem", id)
        put("textEdit", buildJsonObject { put("range", range(0, 4)); put("newText", "native") })
        put("additionalTextEdits", buildJsonArray { add(buildJsonObject {
            put("range", buildJsonObject {
                put("start", buildJsonObject { put("line", 1); put("character", 0) })
                put("end", buildJsonObject { put("line", 1); put("character", 0) })
            }); put("newText", "import genuine\n")
        }) })
    }
    private fun parse(vararg items: JsonElement) = LspVueResultAuthority.completion(buildJsonObject { put("items", JsonArray(items.toList())) }, 1, 1, text, 1, guard)
    @Test fun preservesNativeDuplicatesOriginOrderAndAllEdits() {
        val native = parse(item(), item("private-ts-lsp", "private-ts-lsp:0"), item(id = "vue:1"))
        assertEquals(listOf("vue", "private-ts-lsp", "vue"), native.map { it.nativeOrigin })
        assertEquals(listOf("same", "same", "same"), native.map { it.label })
        assertTrue(native.all { it.additionalTextEdits.size == 1 && it.applicationGuard === guard })
    }
    @Test fun unsupportedOrMalformedWholeItemNeverSalvagesPrimary() {
        val valid = item()
        for (invalid in listOf(JsonObject(valid - "textEdit"), JsonObject(valid + ("command" to JsonPrimitive("run"))),
            JsonObject(valid + ("droideVueBatch" to JsonPrimitive(2))), JsonObject(valid + ("droideVueOrigin" to JsonPrimitive("generic"))),
            JsonObject(valid + ("additionalTextEdits" to JsonPrimitive("broken"))),
            JsonObject(valid + ("insertTextFormat" to JsonPrimitive(3))),
            JsonObject(valid + ("insertTextMode" to JsonPrimitive(2))))) assertTrue(parse(invalid).isEmpty())
    }
    @Test fun invalidAdditionalOverlapAndSnippetRejectEntireItem() {
        val overlap = JsonObject(item() + ("additionalTextEdits" to buildJsonArray { add(buildJsonObject {
            put("range", range(0, 1)); put("newText", "bad")
        }) }))
        assertTrue(parse(overlap).isEmpty())
        val malformed = JsonObject(item() + mapOf("insertTextFormat" to JsonPrimitive(2), "textEdit" to buildJsonObject {
            put("range", range(0, 4)); put("newText", "${'$'}{99999999999999999999999999999:bad}")
        }))
        assertTrue(parse(malformed).isEmpty())
    }
    @Test fun exactPureGuardRejectsTextCaretSelectionAndOwnerChanges() {
        var current = true
        val g = LspCompletionApplicationGuard(text, LspCompletionCaret(1, 1)) { current }
        assertTrue(g.accepts(text, LspCompletionCaret(1, 1), false))
        assertFalse(g.accepts(text + "x", LspCompletionCaret(1, 1), false))
        assertFalse(g.accepts(text, LspCompletionCaret(1, 2), false))
        assertFalse(g.accepts(text, LspCompletionCaret(1, 1), true))
        current = false; assertFalse(g.accepts(text, LspCompletionCaret(1, 1), false))
    }
    private fun range(start: Int, end: Int) = buildJsonObject {
        put("start", buildJsonObject { put("line", 0); put("character", start) })
        put("end", buildJsonObject { put("line", 0); put("character", end) })
    }
}
