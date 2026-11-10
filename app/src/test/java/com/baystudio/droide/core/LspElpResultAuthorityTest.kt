package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LspElpResultAuthorityTest {
 private fun range(sl:Int,sc:Int,el:Int,ec:Int)=buildJsonObject{put("start",buildJsonObject{put("line",sl);put("character",sc)});put("end",buildJsonObject{put("line",el);put("character",ec)})}
 @Test fun utf16LfCrLfAndBareCrAreNeverClampedOrNormalized() {
  val t="a😀\r\nb\rc\n";assertEquals(3,LspElpResultAuthority.offset(t,0,3));assertNull(LspElpResultAuthority.offset(t,0,2));assertNull(LspElpResultAuthority.offset(t,0,4));assertEquals(8,LspElpResultAuthority.offset(t,1,3));assertNull(LspElpResultAuthority.offset(t,1,4));assertNull(LspElpResultAuthority.offset("\uD800",0,0))
  assertTrue(runCatching{LspElpResultAuthority.range(range(1,2,0,1),t)}.isFailure)
 }
 @Test fun completeWorkspaceProposalRejectsUnopenedOverlappingAndMalformedEdit() {
  val root="/workspace";val uri=LspElpProfile.uri("$root/a.erl");val sources=mapOf("$root/a.erl" to "abc\r\n");val versions=mapOf("$root/a.erl" to 1);val guard=LspHeldResultGuard{true}
  fun edit(target:String,items:JsonArray)=buildJsonObject{put("changes",buildJsonObject{put(target,items)})}
  val one=buildJsonObject{put("range",range(0,0,0,1));put("newText","x")}
  val good=LspElpResultAuthority.workspaceEdit(edit(uri,JsonArray(listOf(one))),root,sources,versions,guard);assertEquals(sources.values.single(),good.expectedContent["a.erl"])
  for(value in listOf(edit(LspElpProfile.uri("$root/unopened.erl"),JsonArray(listOf(one))),edit(uri,JsonArray(listOf(one,one))),edit(uri,JsonArray(listOf(one,buildJsonObject{put("newText","x")}))))) assertTrue(runCatching{LspElpResultAuthority.workspaceEdit(value,root,sources,versions,guard)}.isFailure)
 }
 @Test fun nativeCodeAndOriginUncertaintyAreNeverTruncated() {
  val list=buildJsonArray{add(buildJsonObject{put("range",range(0,0,0,1));put("message","native");put("source","eqWAlizer");put("code","E042")})}
  val d=LspElpResultAuthority.diagnostics(list,"abc",LspHeldResultGuard{true}).single();assertEquals(JsonPrimitive("E042"),d.code);assertEquals("eqWAlizer",d.source);assertTrue(d.originNote!!.contains("unverified"));assertEquals(JsonPrimitive("E042"),LspElpResultAuthority.diagnosticJson(d)["code"])
 }
 @Test fun inlayPerLineAndSemanticExtentsAreStrict() {
  val hint=buildJsonArray{add(buildJsonObject{put("position",buildJsonObject{put("line",0);put("character",4)});put("label","arg:")})}
  assertTrue(runCatching{LspElpResultAuthority.inlay(hint,"x\nlong final",LspHeldResultGuard{true})}.isFailure)
  val token=buildJsonObject{put("data",JsonArray(listOf(0,0,2,0,0).map(::JsonPrimitive)))}
  assertTrue(runCatching{LspElpResultAuthority.semantic(token,"x",LspSemanticTokenLegend(listOf("function")))}.isFailure)
 }
}
