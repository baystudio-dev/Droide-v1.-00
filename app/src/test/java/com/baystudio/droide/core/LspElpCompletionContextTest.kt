package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LspElpCompletionContextTest {
 @Test fun clientPrefixNeverConsumesQualifierPunctuationOrTokenSuffix() {
  mapOf("lists:ma" to 7,"?MA" to 2,"#rec.fi" to 6,"-mo" to 2,"Fun@na" to 1,"name" to 1,"$"+"val" to 2).forEach { (text,start)-> assertEquals(start,LspElpCompletionContext.capture(text,1,text.length+1)!!.startColumn) }
  listOf("'quoted", "\"string", "%comment").forEach { assertNull(LspElpCompletionContext.capture(it,1,it.length+1)) }
  assertNull(LspElpCompletionContext.capture("maps",1,3)); assertNull(LspElpCompletionContext.capture("😀",1,2))
 }
 @Test fun exactNativeInsertionOrderAndHarmlessCommandArePreserved() {
  val context=LspElpCompletionContext.capture("lists:ma",1,9)!!;val guard=LspCompletionApplicationGuard(context.text,context.caret){true}
  val values=buildJsonArray { for(name in listOf("z/1","a/2"))add(buildJsonObject { put("label",name);put("insertText","map(\${1:RELATIVE_FILEPATH})");put("insertTextFormat",2);put("command",buildJsonObject { put("title","");put("command","editor.action.triggerParameterHints") }) }) }
  val items=LspElpCompletionContext.parse(values,context,guard,1);assertEquals(listOf("z/1","a/2"),items.map{it.label});assertEquals("map(\${1:RELATIVE_FILEPATH})",items[0].insertText);assertEquals(LspNativeSnippetPolicy.ELP_NUMERIC_RAW,items[0].nativeSnippetPolicy)
  val unsafe=JsonObject(values[0].jsonObject+("command" to buildJsonObject { put("command","other") }));assertTrue(LspElpCompletionContext.parse(JsonArray(listOf(unsafe)),context,guard,2).isEmpty())
 }
 @Test fun numericSnippetGrammarAndLiteralEscapesAreExact() {
  listOf("f(\${1:A}, \${2:B})", "\$1\$0", "\${1}", "f(\${1:naïve})", "a\\\$b\\}c\\\\d").forEach { assertTrue(it,LspElpCompletionContext.validSnippet(it)) }
  listOf("\$NAME", "\${NAME}", "\${1/regex/x/}", "\${1|a,b|}", "\${1:\$2}", "\${1:unterminated", "\\q", "\$9999999999").forEach { assertFalse(it,LspElpCompletionContext.validSnippet(it)) }
 }
 @Test fun primaryPlusIncludeEditIsCompleteAndRejectsDuplicateInsertion() {
  val text="lists:ma";val context=LspElpCompletionContext.capture(text,1,9)!!;val guard=LspCompletionApplicationGuard(text){true}
  fun item(at:Int)=buildJsonArray { add(buildJsonObject { put("label","map");put("additionalTextEdits",buildJsonArray { add(buildJsonObject { put("range",buildJsonObject { put("start",buildJsonObject { put("line",0);put("character",at) });put("end",buildJsonObject { put("line",0);put("character",at) }) });put("newText","-include(\"dir/file.hrl\").\n") }) }) }) }
  assertEquals("-include(\"dir/file.hrl\").\n",LspElpCompletionContext.parse(item(0),context,guard,1).single().additionalTextEdits.single().newText)
  assertTrue(LspElpCompletionContext.parse(item(6),context,guard,2).isEmpty())
 }
}
