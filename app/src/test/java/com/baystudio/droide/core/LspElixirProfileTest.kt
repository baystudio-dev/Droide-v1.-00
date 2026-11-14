package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspElixirProfileTest {
    private val spec = LspElixirProfile.spec

    @Test fun routeOnlyElixirSourceAndScripts() {
        for (path in listOf("lib/app.ex", "test/test.exs", "mix.exs", "文档/a.ex", "dir\\a.exs")) {
            assertEquals(path, listOf(spec), LanguageServerRegistry.forPath(path))
            assertEquals("elixir", LanguageServerRegistry.protocolLanguageId(path))
        }
        for (path in listOf("a.EX", "template.eex", "page.heex", "mix.lock", ".tool-versions", "module.erl"))
            assertFalse(path, LanguageServerRegistry.forPath(path).contains(spec))
        assertEquals(46, LanguageServerRegistry.builtIns.size)
        assertEquals(55, LanguageRegistry.all.size)
    }

    @Test fun upstreamLauncherAndExistingRunAreNotReplacedByAnInstaller() {
        assertEquals(listOf("language_server.sh"), spec.commands.single())
        assertEquals(listOf("elixir", "path with 'quote'.exs"), LanguageRegistry.runPlanFor("path with 'quote'.exs")!!.steps.single())
        assertTrue(LspElixirProfile.INSTALL_NOTE.contains("cache"))
        assertTrue(LspElixirProfile.INSTALL_NOTE.contains("does not install"))
        assertTrue(LspElixirProfile.APPROVAL_EXPLANATION.contains("launcher"))
    }

    @Test fun customLauncherKeepsSeparateConsentAndSettings() {
        val custom = spec.copy(source = "custom", commands = listOf(listOf("elixir-ls")))
        assertEquals(custom, LanguageServerRegistry.forPath("app.ex", listOf(custom)).single())
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom, "app.ex"))
        assertFalse(LspServerProfiles.isElixir(custom))
        assertNull(LspBackgroundPreparationPolicy.forSpec(custom))
        assertNull(LspServerProfiles.configurationAfterInitialize(custom, null))
        assertNotEquals(LspServerProfiles.analysisConfiguration(spec), LspServerProfiles.analysisConfiguration(custom))
    }

    @Test fun openedWorkspaceBoundaryDoesNotSearchForOrExecuteMixProjects() {
        val parent = Files.createTempDirectory("elixir-profile-").toFile().canonicalFile
        try {
            val root = File(parent, "project").apply { mkdir() }
            val doc = File(root, "nested/a.ex").apply { requireNotNull(parentFile).mkdirs(); writeText("x = 1") }
            File(parent, "mix.exs").writeText("raise :never_execute")
            File(root, "nested/mix.exs").writeText("raise :never_execute")
            assertEquals(root, LspServerProfiles.projectRoot(spec, root, doc))
            val outside = File(parent, "outside.ex").apply { writeText("x = 2") }
            Files.createSymbolicLink(File(root, "escape.ex").toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { LspServerProfiles.projectRoot(spec, root, File(root, "escape.ex")) }
            assertTrue(LspServerProfiles.projectAvailable(spec, root))
            assertFalse(LspServerProfiles.projectAvailable(spec, doc))
        } finally { PathSecurity.deleteTreeNoFollow(parent) }
    }

    @Test fun compilerDiagnosticsRemainEnabledWhileExpensiveOptionalWorkIsOff() {
        val settings = Json.parseToJsonElement("""{"autoBuild":true,"fetchDeps":false,"dialyzerEnabled":false,"suggestSpecs":false,"enableTestLenses":false,"mcpEnabled":false}""")
        assertEquals(settings, LspServerProfiles.configurationAfterInitialize(spec, null)!!["settings"]!!.jsonObject["elixirLS"])
        assertTrue(LspServerProfiles.initializationOptions(spec, File("/inert")).isEmpty()) // settings use upstream push/pull contract
        val reply = LspElixirProfile.configurationReply(Json.parseToJsonElement("""{"items":[{"section":"elixirLS"},{"section":"elixirLS.dialyzerEnabled"},{"section":"unknown"},{}]}"""))
        assertEquals(settings, reply[0]); assertEquals(JsonPrimitive(false), reply[1])
        assertEquals(JsonNull, reply[2]); assertEquals(settings, reply[3].jsonObject["elixirLS"])
        val workspace = LspServerProfiles.clientCapabilities(spec)["workspace"]!!.jsonObject
        assertEquals(JsonPrimitive(true), workspace["configuration"])
        assertEquals(JsonPrimitive(false), workspace["didChangeWatchedFiles"]!!.jsonObject["dynamicRegistration"])
        assertEquals(JsonPrimitive(false), workspace["applyEdit"])
        assertTrue(LspServerProfiles.analysisConfiguration(spec).contains("dialyzerEnabled"))
    }

    @Test fun configurationIsBoundedAndDoesNotInterpretServerUrisOrCommands() {
        assertThrows(IllegalStateException::class.java) { LspElixirProfile.configurationReply(null) }
        assertThrows(IllegalStateException::class.java) { LspElixirProfile.configurationReply(JsonNull) }
        assertThrows(IllegalArgumentException::class.java) {
            LspElixirProfile.configurationReply(buildJsonObject { put("items", JsonArray(List(65) { buildJsonObject {} })) })
        }
        val reply = LspElixirProfile.configurationReply(Json.parseToJsonElement("""{"items":[{"section":"elixirLS","scopeUri":"file:///outside/secret"},{"section":"elixirLS.command"},42]}"""))
        assertEquals(LspElixirProfile.settings(), reply[0]); assertEquals(JsonNull, reply[1]); assertEquals(JsonNull, reply[2])
    }

    @Test fun launcherDiscoveryDoesNotCertifyErlangElixirCompatibility() {
        val external = DevelopmentExtensionCatalog.families.single { it.id == "lsp.${spec.id}" }.versions.single()
        assertTrue(external.detectsExternalCommands(setOf(LspElixirProfile.COMMAND)))
        assertFalse(external.detectsExternalCommands(setOf("elixir", "mix", "erl")))
        assertTrue(external.note.contains("does not establish runtime compatibility"))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(spec, "app.ex"))
        assertFalse(LspServerProfiles.refreshAfterPersistedMutation(spec))
        assertEquals(LspBackgroundPreparationPolicy("Elixir", 60_000, 180_000, 15_000), LspBackgroundPreparationPolicy.forSpec(spec))
    }
}
