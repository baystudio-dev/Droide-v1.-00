package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspBufProfileTest {
    private val spec=LanguageServerRegistry.builtIns.single {it.id=="buf"}
    @Test fun exactCaseProtoRouteDoesNotTakeOverConfigurationFiles() {
        for(p in listOf("a.proto","nested/schema.proto")) {
            assertEquals(spec,LanguageServerRegistry.forPath(p).single())
            assertEquals("proto",LanguageServerRegistry.protocolLanguageId(p))
        }
        for(p in listOf("a.PROTO","a.Proto","buf.yaml","buf.work.yaml","a.proto.json"))
            assertFalse(LanguageServerRegistry.forPath(p).contains(spec))
        assertEquals(listOf("buf","lsp","serve"),spec.commands.single())
        assertEquals(55,LanguageRegistry.all.size)
    }
    @Test fun nearestTypedMarkerIsOnlyTheClientGrantRoot() {
        val root=Files.createTempDirectory("buf-root-").toFile().canonicalFile
        try {
            File(root,"buf.work.yaml").writeText("inert")
            val file=File(root,"module/nested/a.proto").apply {parentFile.mkdirs();writeText("inert")}
            File(root,"module/buf.yaml").writeText("inert")
            assertEquals(File(root,"module"),LspServerProfiles.projectRoot(spec,root,file))
            File(file.parentFile,"buf.yaml").mkdir()
            File(file.parentFile,".git").writeText("not a directory")
            assertEquals(File(root,"module"),LspServerProfiles.projectRoot(spec,root,file))
            File(file.parentFile,".hg").mkdir()
            assertEquals(file.parentFile,LspServerProfiles.projectRoot(spec,root,file))
            assertTrue(LspServerProfiles.publishesWorkspaceRoot(spec,file.parentFile))
        } finally {PathSecurity.deleteTreeNoFollow(root)}
    }
    @Test fun markerlessAndMissingDirectoryKeepExactIdentityWithoutBroadening() {
        val root=Files.createTempDirectory("buf-single-").toFile().canonicalFile
        try {
            val file=File(root,"module/a.proto").apply {parentFile.mkdirs();writeText("inert")}
            assertEquals(file.parentFile,LspServerProfiles.projectRoot(spec,root,file))
            val absent=File(root,"missing/a.proto")
            assertEquals(absent.parentFile,LspServerProfiles.projectRoot(spec,root,absent))
            assertFalse(LspServerProfiles.projectAvailable(spec,absent.parentFile))
            assertTrue(LspServerProfiles.projectAvailable(spec,file.parentFile))
            assertTrue(runCatching {LspServerProfiles.projectRoot(spec,root,File(root.parentFile,"outside.proto"))}.isFailure)
        } finally {PathSecurity.deleteTreeNoFollow(root)}
    }
    @Test fun staticOptionsDoNotInventConfigurationOrWatchCallbacks() {
        assertTrue(LspServerProfiles.initializationOptions(spec,File(".")).isEmpty())
        val caps=LspServerProfiles.clientCapabilities(spec)
        assertEquals(JsonPrimitive(false),caps["workspace"]!!.jsonObject["configuration"])
        assertEquals(JsonPrimitive(false),caps["workspace"]!!.jsonObject["didChangeWatchedFiles"]!!.jsonObject["dynamicRegistration"])
        assertNull(LspServerProfiles.configurationAfterInitialize(spec,null))
    }
    @Test fun identityAndDisclosureCoverAncestorPluginsAndNetworkWithoutRefreshingGrant() {
        val id=LspServerProfiles.analysisConfiguration(spec)
        assertTrue(id.contains("buf-1.74.0"));assertTrue(id.contains("rootPolicy"));assertFalse(id.contains("Epoch"))
        assertTrue(LspServerProfiles.refreshAfterPersistedMutation(spec))
        for(word in listOf("ancestor","native plugins","Wasm","network","credentials","not confined"))
            assertTrue(word,LspServerProfiles.approvalExplanation(spec).contains(word))
        for(word in listOf("log-only","empty diagnostics","unopened targets"))
            assertTrue(word,LspServerProfiles.optionalToolNote(spec)!!.contains(word))
    }
    @Test fun customProtoRequiresFreshConsentWithoutBuiltinProfile() {
        val custom=spec.copy(source="custom",commands=listOf(listOf("custom-buf")))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom,"a.proto"))
        assertFalse(LspServerProfiles.isBuf(custom));assertFalse(LspServerProfiles.hasProjectRoots(custom))
        assertFalse(LspServerProfiles.refreshAfterPersistedMutation(custom))
        assertNotEquals(LspServerProfiles.analysisConfiguration(spec),LspServerProfiles.analysisConfiguration(custom))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom.copy(id="other",extensions=emptySet()),"a.unknown"))
    }
}
