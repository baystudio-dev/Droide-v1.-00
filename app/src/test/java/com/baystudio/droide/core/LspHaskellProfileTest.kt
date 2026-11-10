package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspHaskellProfileTest {
    private val spec = LspHaskellProfile.spec

    @Test fun sourceAndLiterateRoutingDoNotClaimBuildOrGeneratedInputs() {
        for (path in listOf("Main.hs", "文档/test.lhs", "space and λ/a.hs", "dir\\Main.hs"))
            assertEquals(path, listOf(spec), LanguageServerRegistry.forPath(path))
        for (path in listOf("Main.HS", "project.cabal", "stack.yaml", "hie.yaml", "generated.hsc", "a.hs-boot"))
            assertFalse(path, LanguageServerRegistry.forPath(path).contains(spec))
        assertEquals("haskell", LanguageServerRegistry.protocolLanguageId("Main.hs"))
        assertEquals("literate haskell", LanguageServerRegistry.protocolLanguageId("Tutorial.lhs"))
        assertEquals(46, LanguageServerRegistry.builtIns.size)
        assertEquals(55, LanguageRegistry.all.size)
    }

    @Test fun userWrapperSelectsGhcAndRunKeepsItsOwnArgv() {
        assertEquals(listOf("haskell-language-server-wrapper", "--lsp", "-j", "2"), spec.commands.single())
        assertEquals(listOf("runhaskell", "name with 'quote'.lhs"), LanguageRegistry.runPlanFor("name with 'quote'.lhs")!!.steps.single())
        assertEquals("> main = putStrLn \"Hello\"\n", LanguageRegistry.templateFor("new.lhs"))
        assertEquals("main = putStrLn \"Hello\"\n", LanguageRegistry.templateFor("new.hs"))
        assertEquals(LanguageRegistry.forFile("new.py")!!.template, LanguageRegistry.templateFor("new.py"))
        assertEquals("", LanguageRegistry.templateFor("unknown.extension"))
    }

    @Test fun customCommandsDoNotInheritProfileAuthorityOrConfiguration() {
        val custom = spec.copy(source = "custom", commands = listOf(listOf("my-hls")))
        assertEquals(custom, LanguageServerRegistry.forPath("Main.hs", listOf(custom)).single())
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom, "Main.hs"))
        assertFalse(LspServerProfiles.isHaskell(custom))
        assertNull(LspBackgroundPreparationPolicy.forSpec(custom))
        assertNull(LspServerProfiles.configurationAfterInitialize(custom, null))
        assertNotEquals(LspServerProfiles.analysisConfiguration(spec), LspServerProfiles.analysisConfiguration(custom))
    }

    @Test fun rootIsOpenedWorkspaceWithoutAncestorScanning() {
        val parent = Files.createTempDirectory("hls-profile-").toFile().canonicalFile
        try {
            val root = File(parent, "project").apply { mkdir() }
            val doc = File(root, "nested/Main.hs").apply { requireNotNull(parentFile).mkdirs(); writeText("main = pure ()") }
            File(parent, "hie.yaml").writeText("# outside project")
            File(root, "nested/stack.yaml").writeText("# nested marker")
            assertEquals(root, LspServerProfiles.projectRoot(spec, root, doc))
            val outside = File(parent, "outside.hs").apply { writeText("main = pure ()") }
            Files.createSymbolicLink(File(root, "escape.hs").toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { LspServerProfiles.projectRoot(spec, root, File(root, "escape.hs")) }
            assertTrue(LspServerProfiles.projectAvailable(spec, root))
            assertFalse(LspServerProfiles.projectAvailable(spec, doc))
        } finally { PathSecurity.deleteTreeNoFollow(parent) }
    }

    @Test fun settingsAreConsistentAcrossInitializationPushAndPull() {
        val settings = Json.parseToJsonElement("""{"checkProject":false,"checkParents":"CheckOnSave","maxCompletions":40}""")
        assertEquals(settings, LspServerProfiles.initializationOptions(spec, File("/inert")))
        assertEquals(settings, LspServerProfiles.configurationAfterInitialize(spec, null)!!["settings"]!!.jsonObject["haskell"])
        val reply = LspHaskellProfile.configurationReply(Json.parseToJsonElement("""{"items":[{"section":"haskell"},{"section":"haskell.checkProject"},{"section":"unknown"},{}]}"""))
        assertEquals(settings, reply[0]); assertEquals(JsonPrimitive(false), reply[1])
        assertEquals(JsonNull, reply[2]); assertEquals(settings, reply[3].jsonObject["haskell"])
        val config = Json.parseToJsonElement(LspServerProfiles.analysisConfiguration(spec)).jsonObject
        assertEquals(settings, config["startup"])
        val workspace = LspServerProfiles.clientCapabilities(spec)["workspace"]!!.jsonObject
        assertEquals(JsonPrimitive(true), workspace["configuration"])
        assertEquals(JsonPrimitive(false), workspace["didChangeWatchedFiles"]!!.jsonObject["dynamicRegistration"])
        assertEquals(JsonPrimitive(false), workspace["applyEdit"])
    }

    @Test fun configurationQueriesAreBoundedAndCannotSupplyCommands() {
        assertThrows(IllegalStateException::class.java) { LspHaskellProfile.configurationReply(null) }
        assertThrows(IllegalStateException::class.java) { LspHaskellProfile.configurationReply(JsonNull) }
        assertThrows(IllegalArgumentException::class.java) {
            LspHaskellProfile.configurationReply(buildJsonObject { put("items", JsonArray(List(65) { buildJsonObject {} })) })
        }
        val reply = LspHaskellProfile.configurationReply(Json.parseToJsonElement("""{"items":[{"section":"haskell","scopeUri":"file:///outside/secret"},{"section":"haskell.command"},42]}"""))
        assertEquals(LspHaskellProfile.settings(), reply[0]) // fixed public settings; URI is never opened
        assertEquals(JsonNull, reply[1]); assertEquals(JsonNull, reply[2])
    }

    @Test fun installationDetectionIsNotToolchainCompatibilityAndStartupIsBounded() {
        val external = DevelopmentExtensionCatalog.families.single { it.id == "lsp.${spec.id}" }.versions.single()
        assertTrue(external.detectsExternalCommands(setOf(LspHaskellProfile.WRAPPER)))
        assertFalse(external.detectsExternalCommands(setOf("ghc", "runhaskell")))
        assertTrue(external.note.contains("does not establish GHC compatibility"))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(spec, "Main.hs"))
        assertFalse(LspServerProfiles.refreshAfterPersistedMutation(spec))
        assertEquals(LspBackgroundPreparationPolicy("Haskell", 60_000, 120_000, 15_000), LspBackgroundPreparationPolicy.forSpec(spec))
    }
}
