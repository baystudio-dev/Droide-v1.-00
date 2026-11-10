package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CMakeCompletionWorkflowTest {
    @Test fun plainNativePrefixInsertionUsesCapturedUtf16Coordinates() {
        val text="😀 𐐀pro"
        val context=requireNotNull(LspCMakeCompletionContext.capture(text,1,text.length+1))
        val guard=LspCompletionApplicationGuard(text,context.requestCaret){true}
        val raw=Json.parseToJsonElement("""[{"label":"project","kind":3,"documentation":"native"}]""")
        val item=LspCMakeCompletionContext.parse(raw,context,guard).single()
        assertTrue(guard.accepts(text,context.requestCaret,false))
        val result=TextEditApplier.apply(text,listOf(TextRangeEdit(item.editStartLine!!,item.editStartColumn!!,
            item.editEndLine!!,item.editEndColumn!!,item.insertText)))
        assertEquals("😀 project",result)
    }
    @Test fun rejectedNativeItemDoesNotCreateGenericFallbackEdit() {
        val text="foo.bar"
        assertNull(LspCMakeCompletionContext.capture(text,1,text.length+1))
        var owner=true
        val guard=LspCompletionApplicationGuard("pro",LspCompletionCaret(1,4)){owner}
        owner=false
        assertFalse(guard.accepts("pro",LspCompletionCaret(1,4),false))
        assertFalse(guard.accepts("pro",LspCompletionCaret(1,2),false))
    }
}
