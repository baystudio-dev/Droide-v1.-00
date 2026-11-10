package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LspCSharpProfileTest {
 @Test fun sectionIsScopeLessArrayAndPushMatches() {
  val section=LspCSharpProfile.section("/work/a.slnx")
  val pulled=LspCSharpProfile.configuration(Json.parseToJsonElement("""{"items":[{"section":"csharp"},{"section":"other"},{"section":"csharp","scopeUri":"file:///else"}]}"""),"file:/work",section)
  assertEquals(section,pulled[0]);assertEquals(JsonNull,pulled[1]);assertEquals(JsonNull,pulled[2])
  assertEquals(section,LspCSharpProfile.settings(section)["settings"]!!.jsonObject["csharp"])
  assertFalse(LspCSharpProfile.section(null).containsKey("solutionPathOverride"))
  assertTrue(LspCSharpProfile.APPROVAL_EXPLANATION.contains("source generators"))
 }
 @Test fun exactNativeIncrementalAndUtf16() {
  val native=LspCSharpProfile.capabilities(Json.parseToJsonElement("""{"capabilities":{"textDocumentSync":2,"typeDefinitionProvider":true}}"""))
  assertEquals(LspTextSyncPolicy.ChangeKind.INCREMENTAL,native.sync.change);assertTrue(native.typeDefinition)
  assertFalse(LspNegotiatedCapabilities.fromInitialize(Json.parseToJsonElement("""{"capabilities":{"typeDefinitionProvider":true}}""")).typeDefinition)
  assertTrue(runCatching{LspCSharpProfile.capabilities(Json.parseToJsonElement("""{"capabilities":{"textDocumentSync":2,"positionEncoding":"utf-8"}}"""))}.isFailure)
 }
 @Test fun exactProfileAndCustomConsent() {
  val native=LanguageServerRegistry.builtIns.single{it.id==LspCSharpProfile.ID};assertTrue(LspServerProfiles.isCSharp(native))
  val custom=native.copy(source="custom",commands=listOf(listOf("other")));assertFalse(LspServerProfiles.isCSharp(custom));assertTrue(LspServerProfiles.requiresProjectAnalysis(custom,"a.cs"))
 }
}
