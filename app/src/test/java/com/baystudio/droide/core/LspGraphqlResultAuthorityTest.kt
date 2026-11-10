package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LspGraphqlResultAuthorityTest {
 @Test fun offsetsRejectOutsideAndSurrogateSplit(){
 fun range(end:Int)=Json.parseToJsonElement("""{"start":{"line":0,"character":0},"end":{"line":0,"character":$end}}""")
 assertEquals(4,LspGraphqlResultAuthority.range(range(3),"a😀").endColumn);assertTrue(runCatching{LspGraphqlResultAuthority.range(range(2),"a😀")}.isFailure);assertTrue(runCatching{LspGraphqlResultAuthority.range(range(4),"a😀")}.isFailure)}
 @Test fun heldEnvelopeNeverBindsToNewSerial(){var serial=1;var writes=0;val old=GraphqlDiagnosticPublication(Any(),1){publish->if(serial==1){publish(emptyMap());true}else false};serial=2;assertFalse(old.publishIfCurrent{writes++});assertEquals(0,writes)}
 @Test fun hoverRetainsPlainTextAndRejectsGuessedRange(){val text="<script>native schema description</script>";assertEquals(text,LspGraphqlResultAuthority.hover(buildJsonObject{put("contents",buildJsonObject{put("kind","plaintext");put("value",text)})}));assertTrue(runCatching{LspGraphqlResultAuthority.hover(buildJsonObject{put("contents","x");put("range",JsonNull)})}.isFailure)}
 @Test fun symbolsUseOwnedUriOnly(){val value=Json.parseToJsonElement("""[{"name":"Q","kind":12,"location":{"uri":"file:///wrong.gql","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}}]""");assertTrue(runCatching{LspGraphqlResultAuthority.symbols(value,"Q","file:///q.gql","q.gql",LspHeldResultGuard{true})}.isFailure)}
}
