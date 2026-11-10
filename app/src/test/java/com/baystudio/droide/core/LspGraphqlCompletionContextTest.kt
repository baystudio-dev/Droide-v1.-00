package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LspGraphqlCompletionContextTest {
 private fun response(items:String,start:Int=2,end:Int=6,caret:Int=4)=Json.parseToJsonElement("""{"items":$items,"isIncomplete":false,"context":{"start":$start,"end":$end,"caret":$caret}}""")
 private fun parse(raw:JsonElement,text:String="{ name }",column:Int=5):LspGraphqlCompletionContext.Envelope {val c=LspGraphqlCompletionContext.capture(text,1,column)!!;return LspGraphqlCompletionContext.parse(raw,c,LspCompletionApplicationGuard(text,c.caret){true},7)}
 @Test fun fullCapturedNameSuffixAndOrder(){val r=parse(response("""[{"label":"z"},{"label":"a"},{"label":"z"}]"""));assertEquals(listOf("z","a","z"),r.items.map{it.label});assertEquals(3,r.items[0].editStartColumn);assertEquals(7,r.items[0].editEndColumn);assertEquals(3,r.items.map{it.nativeItemIdentity}.distinct().size)}
 @Test fun nativeVariableInsertionPreservesSigil(){val r=parse(response("""[{"label":"${'$'}name","insertText":"name"}]""",3,7,5),"{ ${'$'}name }",6);assertEquals("name",r.items.single().insertText);assertEquals(4,r.items.single().editStartColumn)}
 @Test fun rawTemplatePreservedAndCommandOnlyOmitted(){val r=parse(response("""[{"label":"RELATIVE_FILEPATH","insertText":"RELATIVE_FILEPATH: ${'$'}1","insertTextFormat":2,"command":{"command":"editor.action.triggerSuggest","title":"Suggestions"}}]"""));assertEquals("RELATIVE_FILEPATH: ${'$'}1",r.items.single().insertText);assertEquals(LspNativeSnippetPolicy.GRAPHQL_NUMERIC_RAW,r.items.single().nativeSnippetPolicy);assertTrue(r.followOnUnsupported)}
 @Test fun unexpectedEditsCommandsAndGapFail(){for(items in listOf("""[{"label":"a","textEdit":{}}]""","""[{"label":"a","command":{"command":"editor.action.triggerSuggest","title":"Suggestions","arguments":[]}}]""","""[{"label":"a","insertText":"${'$'}NAME","insertTextFormat":2}]"""))assertTrue(runCatching{parse(response(items))}.isFailure);assertTrue(runCatching{parse(response("[]",2,2,2),"{ name }",3)}.isFailure)}
 @Test fun completeLabelsAreNeverTruncated(){val label="x".repeat(1000);assertEquals(label,parse(response("""[{"label":"$label"}]""")).items.single().label)}
}
