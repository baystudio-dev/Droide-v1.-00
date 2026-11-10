package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LspElpProfileTest {
 @Test fun exactRouteAndNonRunnableHeaderPreserveProtocol() {
  val spec=LanguageServerRegistry.forPath("m.erl").first();assertTrue(LspServerProfiles.isElp(spec));assertEquals(setOf("erlang"),spec.languageIds);assertEquals(listOf(listOf("elp")),spec.commands)
  assertTrue(LspServerProfiles.isElp(LanguageServerRegistry.forPath("m.hrl").first()))
  for(path in listOf("m.ERL","m.HRL","m.xrl","m.yrl","m.beam","m.config","m.escript"))assertFalse(LanguageServerRegistry.forPath(path).any(LspServerProfiles::isElp))
  assertEquals("erlang",LanguageRegistry.forFile("m.hrl")!!.id);assertNull(LanguageRegistry.runPlanFor("m.hrl"));assertNotNull(LanguageRegistry.runPlanFor("m.erl"))
  assertTrue(LspServerProfiles.requiresProjectAnalysis(spec,"m.erl"))
 }
 @Test fun metadataPromptDoesNotReuseSessionGrantText() {
  val default=LspProjectAnalysisRequest("old","/w","/w",listOf(listOf("old")),"explain")
  assertEquals("Enable old project analysis for this workspace session?",default.summary);assertTrue(default.detail.contains("grant stays in memory"))
  val metadata=default.copy(purpose=LspProjectAnalysisPurpose.ELP_METADATA_INSPECTION,metadataDisclosure=ElpMetadataInspectionDisclosure("/python","hash","/w/.elp.toml","digest"))
  assertEquals("Inspect ELP configuration with selected Python?",metadata.summary);assertFalse(metadata.detail.contains("grant stays in memory"));assertTrue(metadata.detail.contains("does not enable ELP"));assertTrue(metadata.detail.contains("20 seconds"))
 }
 @Test fun nativeSettingsAndEncodingAreNarrow() {
  assertEquals(JsonPrimitive(false),LspElpProfile.settings["diagnostics"]!!.jsonObject["onSave"]!!.jsonObject["enable"])
  val caps=LspElpProfile.clientCapabilities();assertEquals(JsonPrimitive(false),caps["workspace"]!!.jsonObject["didChangeWatchedFiles"]!!.jsonObject["dynamicRegistration"])
  fun init(encoding:String?)=buildJsonObject { put("capabilities",buildJsonObject { encoding?.let{put("positionEncoding",it)};put("textDocumentSync",buildJsonObject { put("openClose",true);put("change",2);put("save",true) });put("typeDefinitionProvider",true) }) }
  assertTrue(LspElpProfile.capabilities(init(null)).typeDefinition);assertTrue(runCatching{LspElpProfile.capabilities(init("utf-32"))}.isFailure)
 }
}
