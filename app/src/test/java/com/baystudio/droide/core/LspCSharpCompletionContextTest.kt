package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LspCSharpCompletionContextTest {
 @Test fun unicodeVerbatimEscapesMemberAndCrLf() {
  for(text in listOf("@class","αβ","\\u0061bc","obj.","𐐀abc")) {
   val c=LspCSharpCompletionContext.capture(text,1,text.length+1);assertNotNull(text,c)
  }
  assertEquals(1,LspCSharpCompletionContext.capture("\r\n@ab",2,4)!!.startColumn)
  assertNull(LspCSharpCompletionContext.capture("𐐀x",1,2))
  assertNull(LspCSharpCompletionContext.capture("// comment",1,11))
  assertNull(LspCSharpCompletionContext.capture("\"string",1,8))
  assertNull(LspCSharpCompletionContext.capture("abc",1,2))
 }
 @Test fun nativeInsertionFilterOrderAndRangePreserved() {
  val c=LspCSharpCompletionContext.capture("obj.na",1,7)!!;val g=LspCompletionApplicationGuard(c.text,c.caret){true}
  val result=LspCSharpCompletionContext.parse(Json.parseToJsonElement("""{"isIncomplete":true,"items":[{"label":"native B","insertText":"NameB","filterText":"na","sortText":"2"},{"label":"native A","insertText":"NameA","sortText":"1"}]}"""),c,g,1)
  assertTrue(result.incomplete);assertEquals(listOf("NameB","NameA"),result.items.map{it.insertText});assertEquals("na",result.items[0].filterText)
  assertEquals(5,result.items[0].editStartColumn);assertEquals(7,result.items[0].editEndColumn)
  assertFalse(g.accepts("changed",c.caret,false));assertFalse(g.accepts(c.text,c.caret,true))
 }
 @Test fun commandOrOversizedListFailsWholeResult() {
  val c=LspCSharpCompletionContext.capture("x",1,2)!!;val g=LspCompletionApplicationGuard(c.text,c.caret){true}
  assertTrue(runCatching { LspCSharpCompletionContext.parse(Json.parseToJsonElement("""[{"label":"x","command":{"command":"evil"}}]"""),c,g,1) }.isFailure)
  assertTrue(runCatching { LspCSharpCompletionContext.parse(JsonArray(List(241){buildJsonObject{put("label","x")}}),c,g,1) }.isFailure)
 }
}
