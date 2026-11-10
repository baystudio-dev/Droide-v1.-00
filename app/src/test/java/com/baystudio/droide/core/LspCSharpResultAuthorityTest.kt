package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LspCSharpResultAuthorityTest {
 @Test fun uncapturedAndResourceTargetsFailWholeProposal() {
  val texts=mapOf("/work/a.cs" to "abc")
  assertTrue(runCatching{LspCSharpResultAuthority.uriPath("file:/work/other.cs","/work",texts)}.isFailure)
  assertTrue(runCatching{LspCSharpResultAuthority.uriPath("file:/outside/a.cs","/work",texts)}.isFailure)
  assertTrue(runCatching{LspCSharpResultAuthority.edits(Json.parseToJsonElement("""{"documentChanges":[{"kind":"create","uri":"file:/work/a.cs"}]}"""),null,"/work",texts,emptyMap(),{it}){true}}.isFailure)
 }
 @Test fun scalarBoundaries() { assertFalse(LspCSharpResultAuthority.validPosition("😀x",0,1));assertTrue(LspCSharpResultAuthority.validPosition("😀x",0,2));assertFalse(LspCSharpResultAuthority.validText("x\ry")) }
}
