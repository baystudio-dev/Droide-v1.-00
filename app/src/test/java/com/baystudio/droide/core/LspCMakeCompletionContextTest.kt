package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class LspCMakeCompletionContextTest {
    private fun context(text:String)=requireNotNull(LspCMakeCompletionContext.capture(text,1,text.length+1))
    private fun response(vararg fields:Pair<String,JsonElement>) = buildJsonArray { add(buildJsonObject {
        put("label","project"); put("kind",3); put("documentation","native documentation");fields.forEach { (k,v)->put(k,v) }
    }) }
    private fun parse(raw:JsonElement, text:String="pro"):List<LspCompletionItem> {
        val c=context(text)
        return LspCMakeCompletionContext.parse(raw,c,LspCompletionApplicationGuard(text,c.requestCaret){true})
    }
    @Test fun sealedWordTableHasExactCardinalityAndDistinctUnicodeRules() {
        assertEquals(137_936,(0..0x10ffff).count(LspCMakeWordTable::contains))
        for (cp in listOf(0x5f,0xb2,0x2167,0x10400,0x31350)) assertTrue(LspCMakeWordTable.contains(cp))
        for (cp in listOf(-1,0x110000,0x0301,0x200c,0x1f600)) assertFalse(LspCMakeWordTable.contains(cp))
    }
    @Test fun capturesPythonWordPrefixInUtf16() {
        val c=context("😀 𐐀²_x")
        assertEquals("𐐀²_x",c.prefix); assertEquals(TextRangeEdit(1,4,1,9,""),c.prefixRange)
        assertEquals("{",context("\u0024{").triggerCharacter)
        assertEquals("(",context("include(").triggerCharacter)
        assertNull(context("include(foo").triggerCharacter)
        assertEquals("",context("abc\u0301").prefix)
    }
    @Test fun rejectsSuffixAndCompoundAmbiguity() {
        assertNull(LspCMakeCompletionContext.capture("project",1,4))
        for (text in listOf("foo-bar","foo.bar","foo:bar","foo/bar","foo\\bar","-",".")) assertNull(LspCMakeCompletionContext.capture(text,1,text.length+1))
        assertNull(LspCMakeCompletionContext.capture("abc-def",1,4))
        assertNull(LspCMakeCompletionContext.capture("😀x",1,2))
    }
    @Test fun insertionIsPlainExactAndCapturedRangeOnly() {
        val i=parse(response("insertText" to JsonPrimitive("project"))).single()
        assertEquals("project",i.insertText);assertEquals(1,i.editStartColumn);assertEquals(4,i.editEndColumn)
        assertEquals("native documentation",i.documentation);assertFalse(i.isSnippet)
        assertNotNull(i.applicationGuard)
        assertTrue(parse(response("insertText" to JsonPrimitive("x".repeat(50_001)))).isEmpty())
        assertTrue(parse(response("label" to JsonPrimitive("x".repeat(501)))).isEmpty())
    }
    @Test fun malformedOrExecutableEnvelopeNeverFallsBack() {
        for ((key,value) in listOf("command" to JsonNull,"textEdit" to JsonNull,"additionalTextEdits" to JsonArray(emptyList()),"insertTextFormat" to JsonPrimitive(2),"insertTextMode" to JsonPrimitive(2),"insertText" to JsonPrimitive(4),"kind" to JsonPrimitive(1)))
            assertTrue("$key",parse(response(key to value)).isEmpty())
        for (value in listOf("\u0000","\ud800","x\ny","x\ty")) assertTrue(parse(response("insertText" to JsonPrimitive(value))).isEmpty())
    }
    @Test fun finiteResponseDoesNotDeduplicateNativeDocumentation() {
        val c=context("pro");val guard=LspCompletionApplicationGuard(c.snapshot.text,c.requestCaret){true}
        val items=JsonArray(List(300){ response()[0] })
        assertEquals(120,LspCMakeCompletionContext.parse(items,c,guard).size)
        assertTrue(LspCMakeCompletionContext.parse(items,c,LspCompletionApplicationGuard("other",c.requestCaret){true}).isEmpty())
    }
    @Test fun heldAcceptanceNeedsExactTextCaretNoSelectionAndCurrentOwner() {
        var current=true
        val guard=LspCompletionApplicationGuard("pro",LspCompletionCaret(1,4)){current}
        assertTrue(guard.accepts("pro",LspCompletionCaret(1,4),false))
        assertFalse(guard.accepts("pro",LspCompletionCaret(1,3),false)); assertFalse(guard.accepts("pro",LspCompletionCaret(1,4),true))
        assertFalse(guard.accepts("pRo",LspCompletionCaret(1,4),false));current=false
        assertFalse(guard.accepts("pro",LspCompletionCaret(1,4),false))
    }
}
