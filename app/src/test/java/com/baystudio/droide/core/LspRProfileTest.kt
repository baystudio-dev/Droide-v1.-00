package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspRProfileTest {
    private val spec = LspRProfile.spec

    @Test fun sourceRoutingDoesNotClaimMarkdownOrSavedSessions() {
        for (path in listOf("main.R", "analysis/test.r", "space and λ/a.R", "windows\\main.R")) {
            assertEquals(listOf(spec), LanguageServerRegistry.forPath(path))
            assertEquals("r", LanguageServerRegistry.protocolLanguageId(path))
        }
        for (path in listOf("README.Rmd", "a.RData", "a.Rhistory", "a.Rnw", "a.R/README", "a.rs")) {
            assertFalse(path, LanguageServerRegistry.forPath(path).contains(spec))
        }
        assertEquals(46, LanguageServerRegistry.builtIns.size)
        assertEquals(55, LanguageRegistry.all.size)
    }

    @Test fun launchUsesSelectedRWithoutInstallOrUserTextInterpolation() {
        assertEquals(listOf("R", "--vanilla", "--no-echo", "-e", LspRProfile.PROGRAM), spec.commands.single())
        assertFalse(LspRProfile.PROGRAM.contains("install.packages"))
        assertTrue(LspRProfile.PROGRAM.endsWith("languageserver::run()"))
        assertTrue(LspRProfile.PROGRAM.contains("index_mode='off'"))
        assertTrue(LspRProfile.PROGRAM.contains("parse_cache_max_mb=32L"))
        assertFalse(LspRProfile.PROGRAM.contains("{FILE}"))
        assertEquals(listOf("Rscript", "name with 'quotes'.R"), LanguageRegistry.runPlanFor("name with 'quotes'.R")!!.steps.single())
    }

    @Test fun customCommandsKeepTheirOwnConfigurationAndTrust() {
        val custom = spec.copy(source = "custom", commands = listOf(listOf("my-R-server")))
        assertFalse(LspServerProfiles.isR(custom))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom, "main.R"))
        assertNotEquals(LspServerProfiles.analysisConfiguration(spec), LspServerProfiles.analysisConfiguration(custom))
        assertNull(LspBackgroundPreparationPolicy.forSpec(custom))
        assertEquals(custom, LanguageServerRegistry.forPath("main.R", listOf(custom)).single())
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom.copy(id = "third-party", languageIds = emptySet()), "README"))
    }

    @Test fun openedWorkspaceIsCanonicalAndRejectsEscapedDocuments() {
        val parent = Files.createTempDirectory("r-profile-").toFile().canonicalFile
        try {
            val root = File(parent, "project").apply { mkdir() }
            val doc = File(root, "nested/main.R").apply { requireNotNull(parentFile).mkdirs(); writeText("x <- 1") }
            File(root, "nested/DESCRIPTION").writeText("Package: nested")
            assertEquals(root, LspServerProfiles.projectRoot(spec, root, doc))
            assertTrue(LspServerProfiles.projectAvailable(spec, root))
            assertFalse(LspServerProfiles.projectAvailable(spec, File(root, "missing")))
            val outside = File(parent, "outside.R").apply { writeText("x <- 2") }
            Files.createSymbolicLink(File(root, "escape.R").toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { LspServerProfiles.projectRoot(spec, root, outside) }
            assertThrows(IllegalArgumentException::class.java) { LspServerProfiles.projectRoot(spec, root, File(root, "escape.R")) }
        } finally { PathSecurity.deleteTreeNoFollow(parent) }
    }

    @Test fun clientAdvertisesOnlyImplementedCallbacksAndReusesSessionAcrossSaves() {
        val caps = LspServerProfiles.clientCapabilities(spec)
        val workspace = caps.getValue("workspace").jsonObject
        assertEquals(JsonPrimitive(false), workspace["configuration"])
        assertEquals(JsonPrimitive(false), workspace["didChangeWatchedFiles"]!!.jsonObject["dynamicRegistration"])
        assertEquals(JsonPrimitive(false), workspace["applyEdit"])
        assertNull(LspServerProfiles.configurationAfterInitialize(spec, null))
        assertFalse(LspServerProfiles.refreshAfterPersistedMutation(spec))
        assertEquals(JsonArray(listOf(JsonPrimitive("utf-16"))), caps["general"]!!.jsonObject["positionEncodings"])
    }

    @Test fun startupHasFiniteBackgroundBudgetsAndNoVersionWhitelist() {
        assertTrue(LspServerProfiles.requiresProjectAnalysis(spec, "main.R"))
        val policy = LspBackgroundPreparationPolicy.forSpec(spec)!!
        assertEquals(40_000L, policy.initializeMs)
        assertEquals(90_000L, policy.attemptMs)
        assertEquals(15_000L, policy.replayMs)
        assertTrue(LspServerProfiles.optionalToolNote(spec)!!.contains("unverified"))
        assertTrue(LspRProfile.INSTALL_NOTE.contains("Updates"))
        assertTrue(LspRProfile.APPROVAL_EXPLANATION.contains("not a sandbox"))
    }

    @Test fun extensionSeparatesExecutableDiscoveryFromRPackageReadiness() {
        val family = DevelopmentExtensionCatalog.families.single { it.id == "lsp.${spec.id}" }
        val external = family.versions.single()
        assertEquals(setOf("R"), external.provides)
        assertTrue(external.note.contains("does not prove"))
        assertFalse(external.detectsExternalCommands(setOf("Rscript")))
        assertTrue(external.detectsExternalCommands(setOf("R")))
    }
}
