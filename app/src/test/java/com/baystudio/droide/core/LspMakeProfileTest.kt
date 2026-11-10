package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspMakeProfileTest {
    private val spec=LanguageServerRegistry.builtIns.single{it.id=="make-ls"}
    @Test fun exactRoutesNamesAndProtocolUseOneNonRunnableFamily() {
        for(path in listOf("Makefile","makefile","GNUmakefile","src/test.mk","dir/data.mak")) {
            assertTrue(LanguageServerRegistry.forPath(path).any{it.id==spec.id})
            assertEquals("makefile",LanguageServerRegistry.protocolLanguageId(path));assertNull(LanguageRegistry.runPlanFor(path))
        }
        for(path in listOf("MAKEFILE","GNUMakefile","test.MK","data.MAK","foo.makefile","Makefile.am","Makefile.in","CMakeLists.txt","Makefile/"))
            assertFalse(path,LanguageServerRegistry.forPath(path).any{it.id==spec.id})
        assertEquals(listOf(listOf("make-ls")),spec.commands);assertEquals(LanguageRegistry.all.size, LanguageRegistry.all.map { it.id }.distinct().size)
        assertEquals(LanguageServerRegistry.builtIns.size, LanguageServerRegistry.builtIns.map { it.id }.distinct().size)
    }
    @Test fun builtinProfileMaskHasNoEffectOnNativeSyncOrUsefulProviders() {
        val native=LspNegotiatedCapabilities.fromInitialize(Json.parseToJsonElement("""{"capabilities":{"positionEncoding":"utf-16","textDocumentSync":{"openClose":true,"change":1,"save":{"includeText":false}},"hoverProvider":true,"documentSymbolProvider":{},"completionProvider":{"triggerCharacters":["$","("]},"definitionProvider":true,"referencesProvider":true,"codeActionProvider":{"codeActionKinds":["quickfix"]},"documentFormattingProvider":true},"serverInfo":{"name":"make-ls","version":"0.1.0"}}"""))
        val supported=LspMakeProfile.supportedCapabilities(native)
        assertTrue(native.hover);assertTrue(native.documentSymbol);assertTrue(native.formatting)
        assertFalse(supported.hover);assertFalse(supported.documentSymbol);assertFalse(supported.formatting)
        assertTrue(supported.completion);assertTrue(supported.definition);assertTrue(supported.references);assertTrue(supported.codeAction)
        assertEquals(native.sync,supported.sync);assertEquals(LspTextSyncPolicy.ChangeKind.FULL,supported.sync.change)
        assertTrue(supported.sync.openClose);assertTrue(supported.sync.save);assertFalse(supported.sync.saveIncludeText)
        assertFalse(supported.codeActionResolve);assertFalse(supported.workspaceSymbol);assertFalse(supported.rename)
    }
    @Test fun rootFallbackAndTypedMarkersRespectWorkspaceAndMissingParentBarrier() {
        val root=Files.createTempDirectory("make-roots-").toFile().canonicalFile
        try {
            val doc=File(root,"nested/a.mk");doc.parentFile.mkdirs();doc.writeText("")
            assertEquals(root,LspServerProfiles.projectRoot(spec,root,doc))
            File(doc.parentFile,".git").writeText("wrong type")
            assertEquals(root,LspServerProfiles.projectRoot(spec,root,doc));File(doc.parentFile,".git").delete()
            Files.createSymbolicLink(File(doc.parentFile,".hg").toPath(),root.toPath())
            assertEquals(root,LspServerProfiles.projectRoot(spec,root,doc));File(doc.parentFile,".hg").delete()
            File(doc.parentFile,".jj").mkdir();assertEquals(doc.parentFile,LspServerProfiles.projectRoot(spec,root,doc))
            assertTrue(PathSecurity.deleteTreeNoFollow(doc.parentFile))
            val unavailable=LspServerProfiles.projectRoot(spec,root,doc);assertEquals(doc.parentFile,unavailable);assertFalse(LspServerProfiles.projectAvailable(spec,unavailable))
            assertTrue(LspServerProfiles.unavailableProjectMessage(spec).contains("Make"))
        } finally {PathSecurity.deleteTreeNoFollow(root)}
    }
    @Test fun customRoutesRequireTheirOwnGrantWithoutBuiltinProfile() {
        val custom=spec.copy(source="extension",commands=listOf(listOf("my-make-server")))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom,"a.mk"));assertFalse(LspServerProfiles.isMake(custom))
        assertFalse(LspServerProfiles.hasProjectRoots(custom));assertFalse(LspServerProfiles.refreshAfterPersistedMutation(custom))
        assertNotEquals(LspServerProfiles.analysisConfiguration(spec),LspServerProfiles.analysisConfiguration(custom))
        assertNull(LspServerProfiles.optionalToolNote(custom));assertTrue(LspServerProfiles.approvalExplanation(custom).contains("execute project code"))
    }
    @Test fun configurationBindsMasksAndRootsWithoutInventedNativeSettings() {
        val identity=LspServerProfiles.analysisConfiguration(spec)
        assertTrue(identity.contains(LspMakeProfile.IDENTITY));assertTrue(identity.contains("absent-parent-barrier"));assertTrue(identity.contains("edit-only"))
        assertTrue(LspServerProfiles.refreshAfterPersistedMutation(spec));assertNull(LspServerProfiles.configurationAfterInitialize(spec,null))
        val caps=LspServerProfiles.clientCapabilities(spec)
        assertEquals(JsonPrimitive(false),caps["workspace"]!!.jsonObject["configuration"])
        assertEquals(JsonPrimitive(false),caps["workspace"]!!.jsonObject["didChangeWatchedFiles"]!!.jsonObject["dynamicRegistration"])
        assertEquals(JsonArray(listOf(JsonPrimitive("utf-16"))),caps["general"]!!.jsonObject["positionEncodings"])
        assertTrue(LspServerProfiles.approvalExplanation(spec).contains("no aggregate/depth limit"))
        assertTrue(LspServerProfiles.optionalToolNote(spec)!!.contains("formatting are unavailable"))
    }
    @Test fun managedPlanSelectsNativeServerWithoutCompilerOrEditorNode() {
        val plan=ManagedLanguageServerPlan.forServer("make-ls")!!
        assertEquals("0.1.25",plan.pinnedVersion);assertEquals("lsp.make-ls",plan.packageFamilyId)
        assertTrue(plan.requiredCommands.isEmpty());assertEquals(LanguageServerRuntimeKind.LINUX_ARM64_PROOT,plan.runtime)
    }
}
