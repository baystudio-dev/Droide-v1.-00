package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspSolidityProfileTest {
    private val spec = LspSolidityProfile.spec

    @Test fun routesOnlySoliditySourceAndPreservesCompilerTaskOwnership() {
        for (path in listOf("contracts/A.sol", "a space/文.sol", "dir\\A.sol")) {
            assertEquals(listOf(spec), LanguageServerRegistry.forPath(path))
            assertEquals("solidity", LanguageServerRegistry.protocolLanguageId(path))
        }
        for (path in listOf("A.SOL", "A.sol.json", "foundry.toml", "hardhat.config.ts", "A.vy"))
            assertFalse(path, LanguageServerRegistry.forPath(path).contains(spec))
        assertEquals(46, LanguageServerRegistry.builtIns.size)
        assertEquals(55, LanguageRegistry.all.size)
        assertNull(LanguageRegistry.runPlanFor("A.sol")) // an LSP is not an EVM/deployment runner
    }
    @Test fun launchesUserInstalledServerWithoutVersionPinOrPackageInstall() {
        assertEquals(listOf("nomicfoundation-solidity-language-server", "--stdio"), spec.commands.single())
        assertTrue(LspSolidityProfile.INSTALL_NOTE.contains("not a version whitelist"))
        assertTrue(LspSolidityProfile.APPROVAL_EXPLANATION.contains("fork workers"))
        assertTrue(LspSolidityProfile.APPROVAL_EXPLANATION.contains("not a sandbox"))
    }
    @Test fun customServersKeepTheirOwnIdentityTrustAndStartupOptions() {
        val custom = spec.copy(source = "custom", commands = listOf(listOf("my-solidity-lsp")))
        assertEquals(custom, LanguageServerRegistry.forPath("A.sol", listOf(custom)).single())
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom, "A.sol"))
        assertFalse(LspServerProfiles.isSolidity(custom))
        assertNull(LspBackgroundPreparationPolicy.forSpec(custom))
        assertTrue(LspServerProfiles.initializationOptions(custom, File("/unused")).isEmpty())
        assertNotEquals(LspServerProfiles.analysisConfiguration(spec), LspServerProfiles.analysisConfiguration(custom))
    }
    @Test fun rootBoundaryIsCanonicalWithoutExecutingFrameworkFiles() {
        val parent = Files.createTempDirectory("solidity-profile-").toFile().canonicalFile
        try {
            val root = File(parent,"project").apply { mkdir() }
            val doc = File(root,"contracts/A.sol").apply { requireNotNull(parentFile).mkdirs(); writeText("contract A {}") }
            File(root,"hardhat.config.ts").writeText("throw new Error('must not execute in client')")
            assertEquals(root, LspServerProfiles.projectRoot(spec, root, doc))
            val outside = File(parent,"secret.sol").apply { writeText("private") }
            Files.createSymbolicLink(File(root,"escape.sol").toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { LspServerProfiles.projectRoot(spec, root, File(root,"escape.sol")) }
            assertTrue(LspServerProfiles.projectAvailable(spec, root))
            assertFalse(LspServerProfiles.projectAvailable(spec, doc))
        } finally { PathSecurity.deleteTreeNoFollow(parent) }
    }
    @Test fun telemetryIsDisabledInInitializeBeforeNativeIndexing() {
        val options = LspServerProfiles.initializationOptions(spec, File("/inert"))
        assertEquals(Json.parseToJsonElement("""{"telemetryEnabled":false}"""), options)
        assertFalse(options.containsKey("machineId"))
        assertNull(LspServerProfiles.configurationAfterInitialize(spec, null))
        assertTrue(LspServerProfiles.analysisConfiguration(spec).contains("telemetryEnabled"))
        val workspace = LspServerProfiles.clientCapabilities(spec)["workspace"]!!.jsonObject
        assertEquals(JsonPrimitive(false), workspace["configuration"])
        assertEquals(JsonPrimitive(false), workspace["didChangeWatchedFiles"]!!.jsonObject["dynamicRegistration"])
        assertEquals(JsonPrimitive(false), workspace["applyEdit"])
    }
    @Test fun initialScanIsAsynchronousBoundedAndNotRepeatedOnSave() {
        assertTrue(LspServerProfiles.requiresProjectAnalysis(spec, "A.sol"))
        assertFalse(LspServerProfiles.refreshAfterPersistedMutation(spec))
        assertEquals(LspBackgroundPreparationPolicy("Solidity",90_000,180_000,15_000), LspBackgroundPreparationPolicy.forSpec(spec))
        assertTrue(LspSolidityProfile.FEATURE_NOTE.contains("250 ms"))
        assertTrue(LspSolidityProfile.FEATURE_NOTE.contains("Unversioned diagnostics are advisory"))
    }
    @Test fun catalogDistinguishesLauncherFromNodeAndNativeCompatibility() {
        val version = DevelopmentExtensionCatalog.families.single { it.id == "lsp.${spec.id}" }.versions.single()
        assertEquals(setOf(LspSolidityProfile.COMMAND), version.provides)
        assertTrue(version.detectsExternalCommands(setOf(LspSolidityProfile.COMMAND)))
        assertFalse(version.detectsExternalCommands(setOf("node","solc","forge")))
        assertTrue(version.note.contains("does not establish platform compatibility"))
    }
}
