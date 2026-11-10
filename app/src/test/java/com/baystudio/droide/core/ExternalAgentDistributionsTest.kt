package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExternalAgentDistributionsTest {
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Unreviewed substitution accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun nativeRecipesRejectPublisherVersionAndArtifactSubstitution() = runBlocking {
        val resolver = LocalUbuntuVendorResolver { error("Pinned recipes do not resolve moving metadata") }
        for (release in LocalUbuntuAgentDistributions.releases) {
            val recipe = resolver.resolve(release.definition, release.definition.pinnedVersion!!)
            recipe.validate()
            assertTrue(recipe.definition.supportsPublisherVersionSelection)
            rejects { recipe.copy(assetUrl = "https://example.org/binary").validate() }
            rejects { recipe.copy(assetSha256 = "0".repeat(64)).validate() }
            rejects { recipe.copy(assetSize = recipe.assetSize!! + 1).validate() }
            rejects { recipe.copy(version = "99.0.0").validate() }
        }
    }

    @Test fun extensionsExposeTerminalAndChatCommandsWithoutAddingABuiltinProvider() {
        val families = DevelopmentExtensionCatalog.families.associateBy { it.id }
        val specs = WorkspaceAgentPluginCatalog.entries.filter { it.id in setOf("antigravity", "muse", "kimi", "deepseek") }
        assertEquals(4, specs.size)
        for (spec in specs) {
            val family = families.getValue(spec.familyId)
            val version = family.versions.single()
            assertEquals(ExtensionInstallKind.REVIEWED_RECIPE, version.installKind)
            assertTrue(spec.command in version.provides)
            assertTrue(spec.cliCommand in version.provides)
            val projected = DevelopmentExtensionProjection.localVersions(family).single()
            assertTrue(spec.command in projected.provides)
            assertTrue(spec.cliCommand in projected.provides)
            assertFalse(spec.args.any { it.contains("skip-permissions") || it.contains("yolo") })
        }
        assertFalse(families.containsKey(LocalUbuntuAgentDistributions.MUSE_RUNTIME))
        assertEquals(listOf("acp", "--process-per-session"), specs.single { it.id == "antigravity" }.args)
        assertEquals(listOf("acp"), specs.single { it.id == "kimi" }.args)
    }

    @Test fun nativeManagedUpdatesStayUnderExtensionsControl() {
        val recipe = LocalUbuntuAgentDistributions.releases.first().recipe()
        val script = LocalUbuntuVendorPolicy.guestWrapper(recipe, "/opt/droide/packages/agent/payload/tree/antigravity", "/opt/droide/packages/agent")
        assertTrue(script.contains("export AGY_CLI_DISABLE_AUTO_UPDATE=true"))
        assertTrue(LocalUbuntuVendorPolicy.healthCommand(recipe, "/agent").contains("AGY_CLI_DISABLE_AUTO_UPDATE=true"))
        assertFalse(script.contains("skip-permissions"))
    }

    @Test fun pinnedNpmRecipesDoNotRunPublisherMigrationOrDependencyScripts() {
        for ((family, version) in listOf(LocalUbuntuMusePolicy.FAMILY to LocalUbuntuMusePolicy.VERSION, "plugin.kimi-code" to "2.5.2")) {
            val recipe = requireNotNull(WorkstationInstallRecipeCatalog.find(family, version))
            assertTrue(LocalUbuntuNpmPolicy.supports(recipe))
            assertFalse(recipe.allowLifecycleScripts)
            assertFalse(LocalUbuntuNpmPolicy.supports(recipe.copy(packageVersion = "99.0.0")))
            assertFalse(LocalUbuntuNpmPolicy.supports(recipe.copy(registryIntegrity = "changed")))
        }
        val muse = requireNotNull(WorkstationInstallRecipeCatalog.find(LocalUbuntuMusePolicy.FAMILY, LocalUbuntuMusePolicy.VERSION))
        assertEquals(listOf("muse-code-acp", "--cli"), LocalUbuntuNpmPolicy.commands(muse).getValue("muse"))
    }

    private val appRoot = "/data/user/0/com.baystudio.droide/files"
    private fun museRuntime(): ManagedPackageRecord {
        val recipe = LocalUbuntuAgentDistributions.releases.last().recipe()
        val path = LocalPackageInstallJournal.paths(appRoot, recipe.familyId, recipe.version, LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE).final.path
        return ManagedPackageRecord(recipe.familyId, recipe.version, ExecutionScope.LOCAL_LINUX_ARM64.name, path, 1,
            artifactSha256 = recipe.assetSha256, abi = "arm64-v8a")
    }

    @Test fun museAdapterRejectsMissingForeignOrReplacedRuntime() {
        val record = museRuntime()
        val executable = LocalUbuntuMusePolicy.requireRuntime(record, appRoot)
        assertTrue(executable.endsWith("/payload/tree/muse"))
        assertTrue(LocalUbuntuMusePolicy.environment(executable).contains("MUSE_CODE_ACP_BACKEND=sdk"))
        for (bad in listOf(record.copy(familyId = "runtime.node"), record.copy(version = "1.4.14-R7309.1"),
            record.copy(artifactSha256 = "0".repeat(64)), record.copy(installRoot = "/tmp/foreign"), record.copy(abi = "x86_64"))) {
            rejects { LocalUbuntuMusePolicy.requireRuntime(bad, appRoot) }
        }
    }

    @Test fun planCanReturnOnlyToAnAdvertisedSafeMuseOrAntigravityProfile() {
        for (id in listOf("muse", "antigravity", "kimi")) {
            val spec = WorkspaceAgentPluginCatalog.entries.single { it.id == id }
            val selection = WorkspaceAgentModeSelection(spec.planModeCandidates, spec.editModeCandidates)
            val config = WorkspaceAgentSessionConfiguration(modes = listOf("plan", "default", "yolo"), currentModeId = "plan")
            assertEquals("default", selection.resolve(WorkspaceAgentMode.EDIT, config).id)
            try {
                selection.resolve(WorkspaceAgentMode.EDIT, config.copy(modes = listOf("plan", "yolo")))
                fail("Missing native safe mode must not escalate to yolo")
            } catch (_: IllegalStateException) { }
        }
    }

    @Test fun museSdkCanUpgradeButCannotDisappearFromTheIntegrityLockedClosure() {
        val recipe = requireNotNull(WorkstationInstallRecipeCatalog.find(LocalUbuntuMusePolicy.FAMILY, LocalUbuntuMusePolicy.VERSION))
        fun lock(sdk: String) = buildJsonObject {
            put("lockfileVersion", 3)
            put("packages", buildJsonObject {
                put("", JsonObject(emptyMap()))
                put("node_modules/${recipe.packageName}", buildJsonObject {
                    put("version", recipe.version); put("integrity", recipe.registryIntegrity!!)
                    put("resolved", "https://registry.npmjs.org/@bex-co/muse-code-acp/-/muse-code-acp-${recipe.version}.tgz")
                })
                put("node_modules/@muse-code/sdk", buildJsonObject {
                    put("version", sdk); put("integrity", recipe.registryIntegrity!!)
                    put("resolved", "https://registry.npmjs.org/@muse-code/sdk/-/sdk-$sdk.tgz")
                })
            })
        }.toString()
        LocalUbuntuNpmPolicy.validateLock(lock("1.4.12"), recipe)
        LocalUbuntuNpmPolicy.validateLock(lock("1.4.99"), recipe)
        val invalid = Json.parseToJsonElement(lock("1.4.99")).jsonObject
        val missing = JsonObject(invalid + ("packages" to JsonObject(invalid.getValue("packages").jsonObject - "node_modules/@muse-code/sdk")))
        rejects { LocalUbuntuNpmPolicy.validateLock(missing.toString(), recipe) }
    }
}
