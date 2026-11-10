package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CSharpCompletionWorkflowTest {
    @Test fun nativeVerbatimUnicodeInsertionKeepsCapturedRangeAndAdditionalEdit() {
        val text = "// 😀\r\nobj.@na"
        val context = requireNotNull(LspCSharpCompletionContext.capture(text, 2, 8))
        var owner = true
        val guard = LspCompletionApplicationGuard(text, context.caret) { owner }
        val raw = Json.parseToJsonElement("""{"items":[{"label":"Native","insertText":"@native","filterText":"@na","sortText":"007","additionalTextEdits":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":0}},"newText":"// import\r\n"}]}],"isIncomplete":false}""")
        val item = LspCSharpCompletionContext.parse(raw, context, guard, 1).items.single()
        val primary = TextRangeEdit(item.editStartLine!!, item.editStartColumn!!, item.editEndLine!!, item.editEndColumn!!, item.insertText)
        assertEquals("// import\r\n// 😀\r\nobj.@native", TextEditApplier.apply(text, item.additionalTextEdits + primary))
        assertTrue(guard.accepts(text, context.caret, false))
        owner = false
        assertFalse(guard.accepts(text, context.caret, false))
        assertEquals("007", item.sortText)
    }
    @Test fun caretOrSelectionChangeRejectsWithoutFallback() {
        val text = "αβ"; val caret = LspCompletionCaret(1, 3)
        val guard = LspCompletionApplicationGuard(text, caret) { true }
        assertFalse(guard.accepts(text, LspCompletionCaret(1, 2), false))
        assertFalse(guard.accepts(text, caret, true))
        assertFalse(guard.accepts("changed", caret, false))
        assertNull(LspCSharpCompletionContext.capture("\\u0", 1, 4))
    }
}
