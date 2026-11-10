package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspScalaProfileTest {
    private val spec = LspScalaProfile.spec

    @Test fun routeOnlyScalaSourceAndScripts() {
        for (path in listOf("lib/app.scala", "project/build.sbt", "build.sbt", "文档/a.scala", "dir\\a.sc")) {
            assertEquals(path, listOf(spec), LanguageServerRegistry.forPath(path))
            assertEquals("scala", LanguageServerRegistry.protocolLanguageId(path))
        }
        for (path in listOf("a.SCALA", "a.java", "README.md", "build.sbt.bak", "pom.xml"))
            assertFalse(path, LanguageServerRegistry.forPath(path).contains(spec))
        assertEquals(46, LanguageServerRegistry.builtIns.size)
        assertEquals(55, LanguageRegistry.all.size)
    }

    @Test fun upstreamLauncherAndExistingRunAreNotReplacedByAnInstaller() {
        assertEquals(listOf("metals"), spec.commands.single())
        assertEquals(listOf("scala", "path with 'quote'.scala"), LanguageRegistry.runPlanFor("path with 'quote'.scala")!!.steps.single())
        assertNull(LanguageRegistry.runPlanFor("build.sbt"))
        assertEquals("", LanguageRegistry.templateFor("build.sbt"))
        assertTrue(LspScalaProfile.INSTALL_NOTE.contains("does not install"))
        assertTrue(LspScalaProfile.APPROVAL_EXPLANATION.contains("BSP/Bloop/Scala CLI"))
    }

    @Test fun customLauncherKeepsSeparateConsentAndSettings() {
        val custom = spec.copy(source = "custom", commands = listOf(listOf("custom-metals")))
        assertEquals(custom, LanguageServerRegistry.forPath("app.scala", listOf(custom)).single())
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom, "app.scala"))
        assertFalse(LspServerProfiles.isScala(custom))
        assertNull(LspBackgroundPreparationPolicy.forSpec(custom))
        assertNull(LspServerProfiles.configurationAfterInitialize(custom, null))
        assertNotEquals(LspServerProfiles.analysisConfiguration(spec), LspServerProfiles.analysisConfiguration(custom))
    }

    @Test fun openedWorkspaceBoundaryDoesNotSearchForOrExecuteMixProjects() {
        val parent = Files.createTempDirectory("scala-profile-").toFile().canonicalFile
        try {
            val root = File(parent, "project").apply { mkdir() }
            val doc = File(root, "nested/a.scala").apply { requireNotNull(parentFile).mkdirs(); writeText("x = 1") }
            File(parent, "build.sbt").writeText("raise :never_execute")
            File(root, "nested/build.sbt").writeText("raise :never_execute")
            assertEquals(root, LspServerProfiles.projectRoot(spec, root, doc))
            val outside = File(parent, "outside.scala").apply { writeText("x = 2") }
            Files.createSymbolicLink(File(root, "escape.scala").toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { LspServerProfiles.projectRoot(spec, root, File(root, "escape.scala")) }
            assertTrue(LspServerProfiles.projectAvailable(spec, root))
            assertFalse(LspServerProfiles.projectAvailable(spec, doc))
        } finally { PathSecurity.deleteTreeNoFollow(parent) }
    }

    @Test fun initialImportIsConfiguredBeforeBuildAndPrivateClientUiIsNotAdvertised() {
        val settings = Json.parseToJsonElement("""{"autoImportBuilds":"initial","startMcpServer":false,"superMethodLensesEnabled":false,"scalafixLintEnabled":false,"enableSemanticHighlighting":false}""")
        assertEquals(settings, LspServerProfiles.configurationAfterInitialize(spec, null)!!["settings"]!!.jsonObject["metals"])
        val options = LspServerProfiles.initializationOptions(spec, File("/inert"))
        assertEquals(JsonPrimitive(false), options["isHttpEnabled"])
        for (flag in listOf("executeClientCommandProvider", "inputBoxProvider", "quickPickProvider", "debuggingProvider", "isVirtualDocumentSupported"))
            assertEquals(JsonPrimitive(false), options[flag])
        val reply = LspScalaProfile.configurationReply(Json.parseToJsonElement("""{"items":[{"section":"metals"},{"section":"metals.startMcpServer"},{"section":"unknown"},{}]}"""))
        assertEquals(settings, reply[0]); assertEquals(JsonPrimitive(false), reply[1])
        assertEquals(JsonNull, reply[2]); assertEquals(settings, reply[3].jsonObject["metals"])
        val workspace = LspServerProfiles.clientCapabilities(spec)["workspace"]!!.jsonObject
        assertEquals(JsonPrimitive(true), workspace["configuration"])
        assertEquals(JsonPrimitive(false), workspace["didChangeWatchedFiles"]!!.jsonObject["dynamicRegistration"])
        assertEquals(JsonPrimitive(false), workspace["applyEdit"])
        assertTrue(LspServerProfiles.analysisConfiguration(spec).contains("autoImportBuilds"))
    }

    @Test fun configurationIsBoundedAndDoesNotInterpretServerUrisOrCommands() {
        assertThrows(IllegalStateException::class.java) { LspScalaProfile.configurationReply(null) }
        assertThrows(IllegalStateException::class.java) { LspScalaProfile.configurationReply(JsonNull) }
        assertThrows(IllegalArgumentException::class.java) {
            LspScalaProfile.configurationReply(buildJsonObject { put("items", JsonArray(List(65) { buildJsonObject {} })) })
        }
        val reply = LspScalaProfile.configurationReply(Json.parseToJsonElement("""{"items":[{"section":"metals","scopeUri":"file:///outside/secret"},{"section":"metals.command"},42]}"""))
        assertEquals(LspScalaProfile.settings(), reply[0]); assertEquals(JsonNull, reply[1]); assertEquals(JsonNull, reply[2])
    }

    @Test fun launcherDiscoveryDoesNotCertifyJdkBuildToolCompatibility() {
        val external = DevelopmentExtensionCatalog.families.single { it.id == "lsp.${spec.id}" }.versions.single()
        assertTrue(external.detectsExternalCommands(setOf(LspScalaProfile.COMMAND)))
        assertFalse(external.detectsExternalCommands(setOf("java", "scala", "sbt")))
        assertTrue(external.note.contains("does not establish runtime compatibility"))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(spec, "app.scala"))
        assertFalse(LspServerProfiles.refreshAfterPersistedMutation(spec))
        assertEquals(LspBackgroundPreparationPolicy("Scala", 60_000, 180_000, 15_000), LspBackgroundPreparationPolicy.forSpec(spec))
    }
}
