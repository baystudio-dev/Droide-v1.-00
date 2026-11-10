package com.baystudio.droide.core
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LspElpProjectObservationTest {
 private suspend fun resolve(host:ElpAuthoredHost,file:File,toml:JsonObject?=null):ElpProjectObservation {
  val deadline=ElpDeadline.after(5000);val raw=host.inspectElpSelection(host.root.path,file.path,deadline)
  return LspElpProjectObservation.resolve(raw,toml){host.observeElpPaths(raw,it,deadline)}
 }
 @Test fun outermostRebarScriptWinsOverNearestStatic()=runBlocking {
  val root=elpRoot();val nested=File(root,"nested").apply{mkdir()};val source=File(nested,"m.erl").apply{writeText("m.")}
  File(root,"rebar.config").writeText("[]");File(root,"rebar.config.script").writeText("CONFIG.");File(nested,"rebar.config").writeText("[]");File(nested,"build_info.json").writeText("{}")
  val observed=resolve(ElpAuthoredHost(root),source);assertEquals(File(root,"rebar.config.script").path,observed.descriptor);assertEquals("test",observed.rebarProfile)
 }
 @Test fun tomlInPlaceQuirkDoesNotDiscoverAdjacentStatic()=runBlocking {
  val root=elpRoot();File(root,".elp.toml").writeText("");File(root,"build_info.json").writeText("{}")
  val observed=resolve(ElpAuthoredHost(root),File(root,"q.erl"),buildJsonObject{});assertEquals(ElpProjectObservation.Kind.NO_MANIFEST,observed.kind);assertEquals(File(root,".static").path,observed.descriptor)
 }
 @Test fun staticDefaultsAndLiteralTomlAppsAreNative()=runBlocking {
  val root=elpRoot();File(root,".elp.toml").writeText("");File(root,"app").mkdir();val host=ElpAuthoredHost(root)
  val toml=buildJsonObject{put("build_info",buildJsonObject{put("apps","app")})};val observed=resolve(host,File(root,"q.erl"),toml)
  val app=observed.staticData!!["apps"]!!.jsonArray.single().jsonObject;assertEquals(JsonArray(listOf(JsonPrimitive("src"))),app["src_dirs"]);assertEquals(JsonArray(listOf(JsonPrimitive("test"))),app["extra_src_dirs"]);assertFalse(observed.needsRebar)
 }
 @Test fun unsupportedBuckGlobsAndOutsideRootFailVisibly()=runBlocking {
  val root=elpRoot();File(root,".elp.toml").writeText("");val host=ElpAuthoredHost(root)
  for(toml in listOf(buildJsonObject{put("buck",buildJsonObject{put("enabled",true)})},buildJsonObject{put("build_info",buildJsonObject{put("apps","apps/*")})},buildJsonObject{put("build_info",buildJsonObject{put("file","/outside/build_info.json")})}))
   assertTrue(runCatching{resolve(host,File(root,"q.erl"),toml)}.isFailure)
 }
 @Test fun noManifestUsesNearestSrcAndDoesNotCreateSyntheticFile()=runBlocking {
  val root=elpRoot();File(root,"src").mkdir();val source=File(root,"src/m.erl").apply{writeText("m.")};val observed=resolve(ElpAuthoredHost(root),source)
  assertEquals(root.path,observed.root);assertFalse(File(root,".static").exists())
 }
}
