package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LspTomlCompletionContextTest {
    @Test fun gapInsertionKeepsNativeOrderAndIdentity() {
        val context = requireNotNull(LspTomlCompletionContext.capture("key = ", 1, 7))
        val guard = LspCompletionApplicationGuard(context.text, context.caret) { true }
        val result = LspTomlCompletionContext.parse(Json.parseToJsonElement("""[{"label":"z","insertText":"false"},{"label":"a","insertText":"true"},{"label":"z","insertText":"false"}]"""), context, guard, 7)
        assertEquals(listOf("z", "a", "z"), result.items.map { it.label })
        assertEquals(listOf("7:0", "7:1", "7:2"), result.items.map { it.nativeItemIdentity })
        assertTrue(result.items.all { it.editStartColumn == 7 && it.editEndColumn == 7 && it.applicationGuard === guard })
        assertFalse(guard.accepts(context.text, LspCompletionCaret(1,6), false))
        assertFalse(guard.accepts(context.text, context.caret, true))
    }
    @Test fun nonemptyTokensRequireNativeExplicitRange() {
        val c = requireNotNull(LspTomlCompletionContext.capture("key",1,4)); assertFalse(c.gap)
        val g = LspCompletionApplicationGuard(c.text, c.caret) { true }
        assertTrue(runCatching { LspTomlCompletionContext.parse(Json.parseToJsonElement("""[{"label":"key"}]"""),c,g,1) }.isFailure)
        val explicit = Json.parseToJsonElement("""[{"label":"key","textEdit":{"newText":"other","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":3}}}}]""")
        assertEquals("other",LspTomlCompletionContext.parse(explicit,c,g,1).items.single().insertText)
    }
    @Test fun commandsOversizeListsAndUnrepresentableSnippetsFailVisibly() {
        val c=requireNotNull(LspTomlCompletionContext.capture("",1,1));val g=LspCompletionApplicationGuard(c.text,c.caret){true}
        for (value in listOf("""[{"label":"x","command":{"command":"run"}}]""", """{"itemDefaults":{},"items":[]}""", """[{"label":"x","insertText":"${'$'}{1/a/b/}","insertTextFormat":2}]"""))
            assertTrue(value,runCatching { LspTomlCompletionContext.parse(Json.parseToJsonElement(value),c,g,1) }.isFailure)
        assertTrue(runCatching { LspTomlCompletionContext.parse(JsonArray(List(241){buildJsonObject{put("label","x")}}),c,g,1) }.isFailure)
    }
    @Test fun gapsInsideCommentsAndStringsAreNotInferred() {
        for (text in listOf("# comment = ", "key = \"string = ", "key = \"\"\"\n  ")) {
            val line=text.count{it=='\n'}+1;val column=text.substringAfterLast('\n').length+1
            assertFalse(text,requireNotNull(LspTomlCompletionContext.capture(text,line,column)).gap)
        }
        val c=requireNotNull(LspTomlCompletionContext.capture("",1,1));val g=LspCompletionApplicationGuard(c.text,c.caret){true}
        val parsed=LspTomlCompletionContext.parse(Json.parseToJsonElement("""[{"label":"name","insertText":"name = \"${'$'}{0:value}\"","insertTextFormat":2}]"""),c,g,1)
        assertTrue(parsed.items.single().isSnippet)
    }

}
