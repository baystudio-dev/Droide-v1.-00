package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspTerraformProfileTest {
    private val spec=LanguageServerRegistry.builtIns.single {it.id=="terraform-ls"}
    @Test fun exactCaseSensitiveSourceAndVariablesRoutesExcludeOtherHclAndJson() {
        for(p in listOf("main.tf","vars.tfvars","prod.auto.tfvars")) {
            assertEquals(spec,LanguageServerRegistry.forPath(p).single())
            assertEquals(if(p.endsWith(".tfvars"))"terraform-vars" else "terraform",LanguageServerRegistry.protocolLanguageId(p))
        }
        for(p in listOf("main.TF","vars.TFVARS","main.tf.json","vars.tfvars.json","a.hcl",".terraform.lock.hcl","stack.tfcomponent.hcl"))
            assertFalse(LanguageServerRegistry.forPath(p).contains(spec))
        assertEquals(listOf("terraform-ls","serve"),spec.commands.single())
        assertEquals(55,LanguageRegistry.all.size)
    }
    @Test fun canonicalDocumentDirectoryIsBoundedWithoutAncestorMarkerPromotion() {
        val root=Files.createTempDirectory("terraform-root-").toFile().canonicalFile
        try {
            File(root,".git").mkdir()
            val file=File(root,"module/main.tf").apply {parentFile.mkdirs();writeText("inert")}
            assertEquals(file.parentFile,LspServerProfiles.projectRoot(spec,root,file))
            assertEquals(file.parentFile,LspServerProfiles.projectRoot(spec,root,File(root,"module/new.tf")))
            val nested=File(root,"module/env/dev.tfvars").apply {parentFile.mkdirs();writeText("inert")}
            assertEquals(nested.parentFile,LspServerProfiles.projectRoot(spec,root,nested))
            assertTrue(LspServerProfiles.publishesWorkspaceRoot(spec,nested.parentFile))
            val absent=File(root,"missing/a.tf")
            assertEquals(absent.parentFile,LspServerProfiles.projectRoot(spec,root,absent))
            assertFalse(LspServerProfiles.projectAvailable(spec,absent.parentFile))
            assertTrue(LspServerProfiles.projectAvailable(spec,file.parentFile))
            assertTrue(runCatching {LspServerProfiles.projectRoot(spec,root,File(root.parentFile,"outside.tf"))}.isFailure)
        } finally {PathSecurity.deleteTreeNoFollow(root)}
    }
    @Test fun staticOptionsRetainNativeIntelligenceAndTruthfulCallbackCapabilities() {
        val options=LspServerProfiles.initializationOptions(spec,File("."))
        assertEquals(JsonPrimitive("30s"),options["terraform"]!!.jsonObject["timeout"])
        assertNull(options["terraform"]!!.jsonObject["path"])
        assertEquals(JsonPrimitive(false),options["experimentalFeatures"]!!.jsonObject["validateOnSave"])
        assertEquals(JsonPrimitive(true),options["validation"]!!.jsonObject["enableEnhancedValidation"])
        val caps=LspServerProfiles.clientCapabilities(spec)
        assertEquals(JsonPrimitive(false),caps["workspace"]!!.jsonObject["configuration"])
        assertEquals(JsonPrimitive(false),caps["workspace"]!!.jsonObject["didChangeWatchedFiles"]!!.jsonObject["dynamicRegistration"])
        assertNull(caps["experimental"]);assertNull(LspServerProfiles.configurationAfterInitialize(spec,null))
    }
    @Test fun consentIdentityContainsStableOptionsButNoFreshnessEpoch() {
        val id=LspServerProfiles.analysisConfiguration(spec)
        assertTrue(id.contains("terraform-ls-0.39.0"));assertTrue(id.contains("30s"));assertFalse(id.contains("Epoch"))
        assertTrue(LspServerProfiles.refreshAfterPersistedMutation(spec))
        val explanation=LspServerProfiles.approvalExplanation(spec)
        for(word in listOf("initialization","provider","credentials","Registry","Algolia","private identifiers"))assertTrue(explanation.contains(word))
        assertTrue(LspServerProfiles.optionalToolNote(spec)!!.contains("do not prove CLI validation"))
    }
    @Test fun customCommandsRequireDistinctGrantWithoutBuiltinRootOrRefreshProfile() {
        val custom=spec.copy(source="custom",commands=listOf(listOf("custom-terraform")))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom,"main.tf"))
        assertFalse(LspServerProfiles.isTerraform(custom));assertFalse(LspServerProfiles.hasProjectRoots(custom))
        assertFalse(LspServerProfiles.refreshAfterPersistedMutation(custom))
        assertTrue(LspServerProfiles.initializationOptions(custom,File(".")).isEmpty())
        assertNotEquals(LspServerProfiles.analysisConfiguration(spec),LspServerProfiles.analysisConfiguration(custom))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom.copy(id="other",languageIds=setOf("terraform-vars"),extensions=emptySet()),"a.unknown"))
    }
}
