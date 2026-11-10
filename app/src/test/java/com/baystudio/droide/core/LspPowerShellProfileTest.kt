package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspPowerShellProfileTest {
    private val spec = LanguageServerRegistry.builtIns.single { it.id == LspPowerShellProfile.ID }
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected bounded rejection") } catch (e: IllegalArgumentException) {} catch (e: IllegalStateException) {} }
    private fun native(sync: JsonElement = Json.parseToJsonElement("""{"openClose":true,"change":2,"save":{"includeText":true}}"""), legend: JsonElement? = null) = buildJsonObject {
        put("capabilities", buildJsonObject { put("textDocumentSync", sync); if (legend != null) put("semanticTokensProvider", buildJsonObject { put("legend", legend) }) })
    }
    @Test fun oneConfigurationRoutesOriginalLowercaseScriptModuleAndData() {
        for (path in listOf("a.ps1", "nested/module.psm1", "module.psd1")) {
            assertTrue(path, LanguageServerRegistry.forPath(path).any { it.id == spec.id })
            assertEquals("powershell", LanguageServerRegistry.protocolLanguageId(path))
            assertEquals("powershell", LanguageRegistry.forFile(path)?.id)
        }
        for (path in listOf("a.PS1", "a.PSM1", "a.PSD1", "a.ps1xml", "a.ps1/"))
            assertFalse(path, LanguageServerRegistry.forPath(path).any { it.id == spec.id })
        assertNotNull(LanguageRegistry.runPlanFor("a.ps1")); assertNull(LanguageRegistry.runPlanFor("a.psm1")); assertNull(LanguageRegistry.runPlanFor("a.psd1"))
        assertEquals(listOf(listOf(spec.id)), spec.commands); assertEquals(46, LanguageServerRegistry.builtIns.size); assertEquals(55, LanguageRegistry.all.size)
    }
    @Test fun exactProfileIsNotInheritedByCustomCommandOrSource() {
        for (custom in listOf(spec.copy(source="extension"), spec.copy(commands=listOf(listOf("different"))), spec.copy(languageIds=setOf("powershell","other")))) {
            assertFalse(LspServerProfiles.isPowerShell(custom)); assertNull(LspBackgroundPreparationPolicy.forSpec(custom))
            assertTrue(LspServerProfiles.requiresProjectAnalysis(custom,"a.ps1"))
            assertNotEquals(LspServerProfiles.analysisConfiguration(spec), LspServerProfiles.analysisConfiguration(custom))
        }
    }
    @Test fun typedRootsAndMissingParentKeepExactUnavailableIdentity() {
        val root=Files.createTempDirectory("pses-profile-").toFile().canonicalFile
        try {
            val doc=File(root,"nested/a.ps1");doc.parentFile.mkdirs();doc.writeText("")
            assertEquals(root,LspServerProfiles.projectRoot(spec,root,doc))
            File(doc.parentFile,".git").writeText("not a directory")
            assertEquals(root,LspServerProfiles.projectRoot(spec,root,doc));File(doc.parentFile,".git").delete()
            Files.createSymbolicLink(File(doc.parentFile,".hg").toPath(),root.toPath())
            assertEquals(root,LspServerProfiles.projectRoot(spec,root,doc));File(doc.parentFile,".hg").delete()
            File(doc.parentFile,".jj").mkdir(); assertEquals(doc.parentFile,LspServerProfiles.projectRoot(spec,root,doc))
            PathSecurity.deleteTreeNoFollow(doc.parentFile)
            val unavailable=LspServerProfiles.projectRoot(spec,root,doc)
            assertEquals(doc.parentFile,unavailable);assertFalse(LspServerProfiles.projectAvailable(spec,unavailable))
            assertTrue(LspServerProfiles.unavailableProjectMessage(spec).contains("PowerShell"))
        } finally {PathSecurity.deleteTreeNoFollow(root)}
    }
    @Test fun exactFiniteReadinessAndInformedProfileConfiguration() {
        val policy=LspBackgroundPreparationPolicy.forSpec(spec)!!
        assertEquals(60_000L,policy.initializeMs);assertEquals(90_000L,policy.attemptMs);assertEquals(15_000L,policy.replayMs)
        val clojure=LspBackgroundPreparationPolicy.forSpec(LanguageServerRegistry.builtIns.single{it.id=="clojure-lsp"})!!
        assertEquals(300_000L,clojure.initializeMs);assertEquals(330_000L,clojure.attemptMs);assertEquals(15_000L,clojure.replayMs)
        val config=LspPowerShellProfile.initializationOptions("/workspace/project")
        assertEquals(JsonPrimitive(true),config["enableProfileLoading"]);assertEquals(JsonPrimitive("/workspace/project"),config["initialWorkingDirectory"])
        assertTrue(LspServerProfiles.refreshAfterPersistedMutation(spec));assertNull(LspServerProfiles.configurationAfterInitialize(spec,null))
        val identity=LspServerProfiles.analysisConfiguration(spec)
        assertTrue(identity.contains(LspPowerShellProfile.IDENTITY));assertTrue(identity.contains(LspPowerShellProfile.ROOT_POLICY))
        assertTrue(identity.contains(LspPowerShellProfile.READINESS_POLICY));assertTrue(LspServerProfiles.approvalExplanation(spec).contains("profiles",ignoreCase=true))
    }
    @Test fun capabilitiesEnableOnlyReviewedCallbackAndNativeMultilineContract() {
        val caps=LspServerProfiles.clientCapabilities(spec)
        val workspace=caps["workspace"]!!.jsonObject; assertEquals(JsonPrimitive(true),workspace["configuration"])
        assertFalse(workspace.containsKey("didChangeWatchedFiles"))
        assertFalse(caps["textDocument"]!!.jsonObject["codeAction"]!!.jsonObject.containsKey("resolveSupport"))
        assertEquals(JsonPrimitive(true),caps["textDocument"]!!.jsonObject["semanticTokens"]!!.jsonObject["multilineTokenSupport"])
        assertEquals(JsonArray(listOf(JsonPrimitive("utf-16"))),caps["general"]!!.jsonObject["positionEncodings"])
        LspPowerShellProfile.requireInitializeContract(native())
        rejects { LspPowerShellProfile.requireInitializeContract(native(JsonPrimitive(2))) }
        rejects { LspPowerShellProfile.requireInitializeContract(native(Json.parseToJsonElement("""{"openClose":true,"change":2,"save":true}"""))) }
    }
    @Test fun malformedLegendCannotShiftNativeIndicesThroughNormalization() {
        val valid=buildJsonObject { put("tokenTypes",JsonArray(listOf(JsonPrimitive("variable"))));put("tokenModifiers",JsonArray(emptyList())) }
        LspPowerShellProfile.requireInitializeContract(native(legend=valid))
        for (types in listOf(JsonArray(listOf(JsonPrimitive("variable"),JsonPrimitive("variable"))),JsonArray(listOf(JsonPrimitive(1))),JsonArray(listOf(JsonPrimitive("")))))
            rejects { LspPowerShellProfile.requireInitializeContract(native(legend=JsonObject(valid+("tokenTypes" to types)))) }
    }
    @Test fun finiteConfigurationReturnsOrderedScalarsOnlyForOwnedRenameScope() {
        val items=Json.parseToJsonElement("""{"items":[{"section":"powershell.rename","scopeUri":"file:///owned.ps1"},{"section":"powershell.rename","scopeUri":"file:///foreign.ps1"},{"section":"unknown"},{"section":"powershell.rename","scopeUri":"file:///owned.ps1"}]}""")
        val reply=LspPowerShellProfile.configurationReply(items){it=="file:///owned.ps1"}
        assertEquals(4,reply.size);assertEquals(reply[0],reply[3]);assertEquals(JsonNull,reply[1]);assertEquals(JsonNull,reply[2])
        assertEquals(JsonPrimitive(true),reply[0].jsonObject["acceptDisclaimer"]);assertEquals(JsonPrimitive(false),reply[0].jsonObject["createParameterAlias"])
        rejects { LspPowerShellProfile.configurationReply(buildJsonObject { put("items",JsonArray(List(65){buildJsonObject{}})) }){true} }
        rejects { LspPowerShellProfile.configurationReply(Json.parseToJsonElement("""{"items":[{"section":{}}]}""")){true} }
    }
    @Test fun validPromptAlwaysCancelsAndMalformedPromptsFailPromptly() {
        val cancelled=LspPowerShellProfile.cancelPrompt("powerShell/showInputPrompt",Json.parseToJsonElement("""{"name":"secret","label":"Enter"}"""))
        assertEquals(JsonPrimitive(true),cancelled["promptCancelled"])
        rejects {LspPowerShellProfile.cancelPrompt("powerShell/showInputPrompt",Json.parseToJsonElement("""{"name":[],"label":"Enter"}"""))}
        rejects {LspPowerShellProfile.cancelPrompt("unknown",buildJsonObject{})}
    }
    @Test fun serverPlanCountsRuntimeOnlyAsPrerequisite() {
        val plan=ManagedLanguageServerPlan.forServer(spec.id)!!
        assertEquals("4.7.0",plan.pinnedVersion);assertEquals("lsp.powershell-editor-services",plan.packageFamilyId)
        assertEquals(setOf("node"),plan.requiredCommands);assertEquals(LanguageServerRuntimeKind.LINUX_ARM64_PROOT,plan.runtime)
    }
    @Test fun customConsentNeverPromisesBuiltInProfileOrPromptSemantics() {
        val custom = spec.copy(source = "custom", commands = listOf(listOf("custom-pwsh")))
        val explanation = LspServerProfiles.approvalExplanation(custom, path = "a.ps1")
        assertTrue(explanation.contains("unverified"))
        assertTrue(explanation.contains("network"))
        assertTrue(explanation.contains("credential"))
        assertFalse(explanation.contains("loads the four system/user"))
        assertFalse(explanation.contains("Native standard prompts are denied"))
        assertTrue(LspServerProfiles.approvalExplanation(spec).contains("Native standard prompts are denied"))
    }
    @Test fun rejectedNativeEnvelopesRemainDistinctFromValidEmptyResults() {
        for (value in listOf("null", "[]", "{\"items\":[]}", "{\"items\":[],\"isIncomplete\":false}"))
            assertTrue(value, LspPowerShellProfile.acceptsCompletionEnvelope(Json.parseToJsonElement(value)))
        for (value in listOf("123", "true", "{}", "{\"items\":null}", "{\"items\":[],\"isIncomplete\":\"false\"}"))
            assertFalse(value, LspPowerShellProfile.acceptsCompletionEnvelope(Json.parseToJsonElement(value)))
        val legend = LspSemanticTokenLegend(listOf("variable"), emptyList())
        assertEquals(emptyList<LspSemanticToken>(), LspPowerShellSemanticTokens.parseFullChecked(JsonNull, legend, "x"))
        assertEquals(emptyList<LspSemanticToken>(), LspPowerShellSemanticTokens.parseFullChecked(Json.parseToJsonElement("{\"data\":[]}"), legend, "x"))
        assertNull(LspPowerShellSemanticTokens.parseFullChecked(Json.parseToJsonElement("{\"data\":[0,0,-1,0,0]}"), legend, "x"))
        assertNull(LspPowerShellSemanticTokens.parseFullChecked(JsonPrimitive(123), legend, "x"))
        assertEquals(1, LspPowerShellSemanticTokens.parseFullChecked(Json.parseToJsonElement("{\"data\":[0,0,1,0,0]}"), legend, "x")!!.size)
    }

}
