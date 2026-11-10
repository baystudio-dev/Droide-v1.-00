package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspAsmProfileTest {
    private val spec = LspAsmProfile.spec
    @Test fun routesAssemblyAndPreprocessedAssemblyOnly() {
        for (path in listOf("a.s", "a.S", "a.asm", "space and 文/a.s")) {
            assertEquals(listOf(spec), LanguageServerRegistry.forPath(path))
            assertEquals("asm", LanguageServerRegistry.protocolLanguageId(path))
        }
        for (path in listOf("a.ASM", "file.rs", "x.s/readme", "a.inc")) assertFalse(LanguageServerRegistry.forPath(path).contains(spec))
        assertEquals(46, LanguageServerRegistry.builtIns.size)
        assertEquals(55, LanguageRegistry.all.size)
    }
    @Test fun userControlsToolsAndRunKeepsTheExistingNativeCompilerRoute() {
        assertEquals(listOf("asm-lsp"), spec.commands.single())
        assertNotNull(LanguageRegistry.runPlanFor("main.s"))
        val version = DevelopmentExtensionCatalog.families.single { it.id == "lsp.asm-lsp" }.versions.single()
        assertEquals(setOf("asm-lsp"), version.provides)
        assertTrue(version.detectsExternalCommands(setOf("asm-lsp")))
        assertFalse(version.detectsExternalCommands(setOf("clang")))
        assertTrue(version.note.contains("does not prove"))
    }
    @Test fun customServerRetainsItsOwnGrantAndCapabilities() {
        val custom = spec.copy(source = "custom", commands = listOf(listOf("another-server")))
        assertFalse(LspServerProfiles.isAsm(custom))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom, "main.s"))
        assertNotEquals(LspServerProfiles.analysisConfiguration(spec), LspServerProfiles.analysisConfiguration(custom))
        assertNull(LspBackgroundPreparationPolicy.forSpec(custom))
    }
    @Test fun rootNeverSearchesAncestorConfigurationOrEscapesTheWorkspace() {
        val base = Files.createTempDirectory("asm-root").toFile().canonicalFile
        try {
            val root = base.resolve("workspace").apply { mkdir() }
            val file = root.resolve("nested/main.s").apply { parentFile.mkdirs(); writeText("ret") }
            base.resolve(".asm-lsp.toml").writeText("not read by client")
            assertEquals(root, LspServerProfiles.projectRoot(spec, root, file))
            assertThrows(IllegalArgumentException::class.java) { LspServerProfiles.projectRoot(spec, root, base.resolve("outside.s")) }
            assertFalse(LspServerProfiles.projectAvailable(spec, root.resolve("missing")))
        } finally { PathSecurity.deleteTreeNoFollow(base) }
    }
    @Test fun unsafeOrUnimplementedFeaturesAreNotAdvertisedAsAvailable() {
        val native = LspNegotiatedCapabilities.fromInitialize(Json.parseToJsonElement("""{"capabilities":{
            "textDocumentSync":{"openClose":true,"change":2,"save":true},"hoverProvider":true,
            "completionProvider":{},"documentSymbolProvider":true,"diagnosticProvider":{},
            "definitionProvider":true,"documentFormattingProvider":true,"renameProvider":true
        }}"""))
        val actual = LspAsmProfile.supportedCapabilities(native)
        assertTrue(actual.hover && actual.completion && actual.documentSymbol)
        assertFalse(actual.pullDiagnostics || actual.sync.save || actual.formatting || actual.rename || actual.definition)
        assertFalse(actual.workspaceSymbol || actual.codeAction || actual.semanticTokens || actual.inlayHint)
    }
    @Test fun positionsAreExplicitlyLimitedUntilUpstreamUnicodeIsCorrect() {
        assertTrue(LspAsmProfile.acceptsPositionFeatures("main:\r\n\tret\n"))
        assertTrue(LspAsmProfile.acceptsPositionFeatures(""))
        for (text in listOf("//é", "//😀", "\u0000")) assertFalse(LspAsmProfile.acceptsPositionFeatures(text))
        assertTrue(LspAsmProfile.FEATURE_NOTE.contains("ASCII"))
    }
    @Test fun passiveRoutingHasNoSetupAndBackgroundStartupHasFiniteBudgets() {
        val policy = LspBackgroundPreparationPolicy.forSpec(spec)!!
        assertEquals(30_000L, policy.initializeMs)
        assertEquals(90_000L, policy.attemptMs)
        assertEquals(15_000L, policy.replayMs)
        assertFalse(LspServerProfiles.refreshAfterPersistedMutation(spec))
        assertTrue(LspAsmProfile.INSTALL_NOTE.contains("does not install or pin"))
        assertEquals(JsonPrimitive(false), LspServerProfiles.clientCapabilities(spec)["workspace"]!!.jsonObject["configuration"])
    }
}
