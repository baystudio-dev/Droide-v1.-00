package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class DevelopmentExtensionProjectionTest {
    private val git = requireNotNull(DevelopmentExtensionCatalog.byId("cli.git"))

    private fun state(family: ExtensionFamily, version: ExtensionVersion, state: ExtensionState) =
        ExtensionVersionState(family, version, state, state.name)

    @Test fun gitHasARealLocalInstallChoiceBeforeTheFirstRuntimeRefresh() {
        val recipe = requireNotNull(UbuntuAptCatalog.find(git.id))
        val versions = DevelopmentExtensionProjection.localVersions(git)
        val selected = versions.single()
        assertEquals(recipe.version, selected.version)
        assertEquals(ExtensionInstallKind.REVIEWED_RECIPE, selected.installKind)
        assertEquals(ExecutionScope.LOCAL_LINUX_ARM64, selected.scope)
        assertEquals(setOf("git"), selected.provides)
        assertTrue(selected.recommended)
        assertEquals(listOf("git"), recipe.packages)
        assertEquals(mapOf("git" to "/usr/bin/git"), recipe.commands)
    }

    @Test fun everyRegisteredLanguageServerHasAnExplicitInstallationRoute() {
        val specs = LanguageServerRegistry.builtIns
        assertEquals(specs.size, specs.map { it.id }.distinct().size)
        assertEquals(19, LegacyLspCatalogFixture.workspaceProfiles().size)
        val externallyManaged = mapOf(
            "nomicfoundation-solidity-language-server" to "nomicfoundation-solidity-language-server",
            "metals" to "metals",
            "asm-lsp" to "asm-lsp",
            "elixir-ls" to "language_server.sh",
            "haskell-hls" to "haskell-language-server-wrapper",
            "r-languageserver" to "R",
        )
        assertTrue(specs.map { it.id }.containsAll(externallyManaged.keys))
        specs.forEach { spec ->
            val familyId = when (spec.id) {
                "graphql-local-schema" -> "lsp.graphql-library"
                "taplo-toml-wasm" -> "lsp.taplo-wasm"
                "vue-language-server" -> "lsp.vue"
                else -> "lsp.${spec.id}"
            }
            val family = requireNotNull(DevelopmentExtensionCatalog.byId(familyId)) {
                "Missing catalog family $familyId for ${spec.id}"
            }
            val choices = DevelopmentExtensionProjection.localVersions(family)
            val command = spec.commands.first().first()
            val external = family.versions.single()
            assertEquals("External", external.version)
            assertEquals(ExecutionScope.LOCAL_LINUX_ARM64, external.scope)
            if (spec.id in externallyManaged) {
                assertEquals(externallyManaged.getValue(spec.id), command)
                assertTrue("External-only server advertises an installer: ${spec.id}", choices.isEmpty())
                assertEquals(ExtensionInstallKind.CATALOG_ONLY, external.installKind)
                assertEquals(setOf(command), external.provides)
                assertTrue(DevelopmentExtensionProjection.canDetectExternal(external))
                assertTrue(external.detectsExternalCommands(setOf(command)))
                assertFalse(external.detectsExternalCommands(emptySet()))
            } else {
                assertTrue("Missing installer for ${spec.id}", choices.isNotEmpty())
                assertTrue("Installer does not supply $command", choices.any { command in it.provides })
                assertTrue(choices.all { it.installKind == ExtensionInstallKind.REVIEWED_RECIPE })
            }
        }
    }

    @Test fun libraryLanguageServerPackageIdsResolveToOneCatalogFamilyAndInstaller() {
        for (id in listOf("graphql-local-schema", "taplo-toml-wasm", "vue-language-server")) {
            val plan = requireNotNull(ManagedLanguageServerPlan.forServer(id))
            val family = DevelopmentExtensionCatalog.families.singleOrNull { it.id == plan.packageFamilyId }
            assertNotNull("Missing catalog family ${plan.packageFamilyId}", family)
            assertTrue("Missing installer for ${plan.packageFamilyId}",
                DevelopmentExtensionProjection.localVersions(requireNotNull(family)).any {
                plan.command in it.provides && it.installKind == ExtensionInstallKind.REVIEWED_RECIPE
            })
        }
    }

    @Test fun taploInstallerAndLanguageSupportShareTheCanonicalPackageIdentity() {
        val family = requireNotNull(DevelopmentExtensionCatalog.byId("lsp.taplo-wasm"))
        assertEquals(ExtensionCategory.LANGUAGE_SERVERS, family.category)
        val choice = DevelopmentExtensionProjection.localVersions(family).single()
        assertEquals("0.8.0", choice.version)
        assertEquals(setOf("droide-taplo-lsp"), choice.provides)
        assertEquals(ExtensionInstallKind.REVIEWED_RECIPE, choice.installKind)
        assertEquals(ExecutionScope.LOCAL_LINUX_ARM64, choice.scope)
        assertNull(DevelopmentExtensionCatalog.byId("lsp.taplo-toml-wasm"))
    }

    @Test fun vueInstallerAndLanguageSupportShareTheCanonicalPackageIdentity() {
        val family = requireNotNull(DevelopmentExtensionCatalog.byId("lsp.vue"))
        assertEquals(ExtensionCategory.LANGUAGE_SERVERS, family.category)
        val choice = DevelopmentExtensionProjection.localVersions(family).single()
        assertEquals("3.3.13", choice.version)
        assertEquals(setOf("vue-language-server"), choice.provides)
        assertEquals(ExtensionInstallKind.REVIEWED_RECIPE, choice.installKind)
        assertEquals(ExecutionScope.LOCAL_LINUX_ARM64, choice.scope)
        assertNull(DevelopmentExtensionCatalog.byId("lsp.vue-language-server"))
    }

    @Test fun svelteOffersOneExactManagedRecipeAndHonestExternalDetection() {
        val family = requireNotNull(DevelopmentExtensionCatalog.byId("lsp.svelte-language-server"))
        assertEquals("Svelte", family.name)
        val choice = DevelopmentExtensionProjection.localVersions(family).single()
        assertEquals("0.18.5", choice.version)
        assertEquals(setOf("svelteserver"), choice.provides)
        assertEquals(setOf("node", "npm"), choice.requires)
        assertEquals(ExtensionInstallKind.REVIEWED_RECIPE, choice.installKind)
        assertTrue(choice.note.orEmpty().contains("Typed managed identity"))
        assertTrue(choice.note.orEmpty().contains("transport-only"))
        assertTrue(choice.note.orEmpty().contains("native/device semantics remain unverified"))
        val external = family.versions.single()
        assertTrue(DevelopmentExtensionProjection.canDetectExternal(external))
        assertTrue(external.note.orEmpty().contains("does not establish"))
        assertTrue(external.note.orEmpty().contains("Generic custom declarations"))
        val framework = requireNotNull(DevelopmentExtensionCatalog.byId("framework.svelte"))
        assertEquals(ExecutionScope.EDITOR, framework.versions.single().scope)
        assertTrue(DevelopmentExtensionProjection.localVersions(framework).isEmpty())
    }

    @Test fun absentExternalPlaceholderCannotDisplaceTheGitInstaller() {
        val install = state(git, DevelopmentExtensionProjection.localVersions(git).single(), ExtensionState.AVAILABLE)
        val missing = state(git, git.versions.single(), ExtensionState.UNAVAILABLE)
        assertEquals(listOf(install), DevelopmentExtensionProjection.visibleStates(listOf(install, missing)))
    }

    @Test fun realUserManagedCommandRemainsVisibleAlongsideManagedInstallChoice() {
        val install = state(git, DevelopmentExtensionProjection.localVersions(git).single(), ExtensionState.AVAILABLE)
        val external = state(git, git.versions.single(), ExtensionState.EXTERNAL)
        assertEquals(listOf(install, external), DevelopmentExtensionProjection.visibleStates(listOf(install, external)))
    }

    @Test fun installedReceiptsSurvivePlaceholderFilteringEvenForAnotherBackend() {
        val install = state(git, DevelopmentExtensionProjection.localVersions(git).single(), ExtensionState.AVAILABLE)
        val receipt = state(git, git.versions.single().copy(version = "External", channel = "installed-device"), ExtensionState.INSTALLED)
        assertEquals(listOf(install, receipt), DevelopmentExtensionProjection.visibleStates(listOf(install, receipt)))
    }

    @Test fun unsupportedFamiliesAndProjectDependenciesAreNotHiddenAsIfFixed() {
        val flutter = requireNotNull(DevelopmentExtensionCatalog.byId("framework.flutter"))
        val project = requireNotNull(DevelopmentExtensionCatalog.byId("framework.react"))
        val unavailable = state(flutter, flutter.versions.single(), ExtensionState.UNAVAILABLE)
        val dependency = state(project, project.versions.single(), ExtensionState.UNAVAILABLE)
        assertEquals(listOf(unavailable, dependency), DevelopmentExtensionProjection.visibleStates(listOf(unavailable, dependency)))
        assertTrue(DevelopmentExtensionProjection.localVersions(flutter).isEmpty())
        assertEquals(ExecutionScope.EDITOR, dependency.version.scope)
    }

    @Test fun commandNameDetectionCannotClaimAnExactManagedAgentVersion() {
        val opencode = requireNotNull(DevelopmentExtensionCatalog.byId("plugin.opencode"))
        assertFalse(DevelopmentExtensionProjection.canDetectExternal(opencode.versions.single()))
        assertTrue(DevelopmentExtensionProjection.canDetectExternal(git.versions.single()))
        assertFalse(DevelopmentExtensionProjection.canDetectExternal(git.versions.single().copy(scope = ExecutionScope.LOCAL)))
        assertFalse(DevelopmentExtensionProjection.canDetectExternal(git.versions.single().copy(installKind = ExtensionInstallKind.ANDROID_LOCAL_COMPONENT)))
    }

    @Test fun terminalInstalledPluginsHaveAnUnversionedChoiceSeparateFromHistoricalPins() {
        val plugin = requireNotNull(DevelopmentExtensionCatalog.byId("plugin.opencode"))
        val user = requireNotNull(DevelopmentExtensionProjection.userInstalledChoice(plugin))
        assertEquals("User-installed", user.version)
        assertEquals(setOf("opencode"), user.provides)
        assertTrue(DevelopmentExtensionProjection.canDetectExternal(user))
        assertFalse(DevelopmentExtensionProjection.canDetectExternal(plugin.versions.single()))
    }

    @Test fun builtinAndExistingExternalChoicesDoNotAcquireDuplicateUserInstallRows() {
        assertNull(DevelopmentExtensionProjection.userInstalledChoice(git))
        val builtIn = requireNotNull(DevelopmentExtensionCatalog.byId("plugin.ai-agent"))
        assertNull(DevelopmentExtensionProjection.userInstalledChoice(builtIn))
    }

    @Test fun installerAvailabilityMatchesPackagedEngineArchitectureAdmission() {
        assertNull(DevelopmentExtensionProjection.platformProblem(listOf("arm64-v8a", "armeabi-v7a"), true))
        assertNotNull(DevelopmentExtensionProjection.platformProblem(listOf("arm64-v8a", "armeabi-v7a"), false))
        assertNotNull(DevelopmentExtensionProjection.platformProblem(listOf("armeabi-v7a"), false))
        assertNotNull(DevelopmentExtensionProjection.platformProblem(listOf("x86_64", "x86"), true))
        assertNotNull(DevelopmentExtensionProjection.platformProblem(listOf("x86_64", "arm64-v8a"), true))
        assertNotNull(DevelopmentExtensionProjection.platformProblem(emptyList(), true))
    }

    @Test fun providerChoicesPreservePrerequisitesAndBundleCommands() {
        val npm = requireNotNull(DevelopmentExtensionCatalog.byId("package.npm"))
        val choice = DevelopmentExtensionProjection.localVersions(npm).single()
        assertEquals(LocalUbuntuBundleAliasPolicy.VERSION_CHOICE, choice.version)
        assertEquals(setOf("npm", "npx"), choice.provides)
        assertEquals(setOf("node"), choice.requires)
        val node = requireNotNull(DevelopmentExtensionCatalog.byId("runtime.node"))
        assertEquals(setOf("node", "npm", "npx"), DevelopmentExtensionProjection.localVersions(node).single().provides)
    }

    @Test fun ubuntuSnapshotNeverAdvertisesAnotherGuestsReceiptOrCommands() {
        val root = "/app"
        val ubuntu = ManagedPackageRecord("cli.git", "1", ExecutionScope.LOCAL_LINUX_ARM64.name,
            "$root/packages/cli.git/1/arm64-v8a", 1, true,
            commands = mapOf("git" to "/usr/bin/git"),
            metadata = mapOf(LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_APT))
        val otherGuest = ubuntu.copy(familyId = "legacy.guest", metadata = mapOf(LocalManagedPackageMetadata.KIND_KEY to "guest-alpine"))
        val device = ubuntu.copy(familyId = "legacy.device", installRoot = "/data/local/tmp/droide/packages/git")
        val records = listOf(ubuntu, otherGuest, device)
        val views = DevelopmentExtensionProjection.recordsForUbuntuSnapshot(records, null, root, { true })
        assertEquals(listOf(ubuntu), views.catalogRecords)
        assertEquals(listOf(ubuntu), views.localCapabilityRecords)
        assertEquals(3, records.size) // Projection must not delete a historical receipt.
    }

    @Test fun ubuntuSnapshotNormalizesLegacyAddressesAndRetainsRecoveryRepairState() {
        val root = "/canonical"
        val canonical = ManagedPackageRecord("cli.git", "1", ExecutionScope.LOCAL_LINUX_ARM64.name,
            "$root/packages/cli.git/1/arm64-v8a", 1, true,
            commands = mapOf("git" to "/usr/bin/git"),
            metadata = mapOf(LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_APT))
        val legacy = canonical.copy(installRoot = "/old-address/packages/cli.git/1/arm64-v8a")
        val views = DevelopmentExtensionProjection.recordsForUbuntuSnapshot(listOf(legacy), "Recovery failed", root,
            { true }, { it.copy(installRoot = canonical.installRoot) })
        assertEquals(listOf(canonical), views.catalogRecords)
        assertTrue(views.localCapabilityRecords.isEmpty())
    }

    @Test fun failedRecoveryPreservesRepairReceiptsWithoutPublishingTheirCommands() {
        fun receipt(family: String, scope: ExecutionScope, backend: PackageBackendId) = ManagedPackageRecord(
            familyId = family, version = "1", scope = scope.name,
            installRoot = if (backend == PackageBackendId.LOCAL_APP) "/app/files/packages/$family/1" else "/remote/packages/$family/1",
            installedAtEpochMs = 1, commands = mapOf("git" to "/tool/git"),
            metadata = mapOf(PackageBackendContract.METADATA_KEY to backend.name),
        )
        val local = receipt("cli.git", ExecutionScope.LOCAL_LINUX_ARM64, PackageBackendId.LOCAL_APP)
        val remote = receipt("cli.gh", ExecutionScope.LOCAL_LINUX_ARM64, PackageBackendId.DEVICE_ADB)
        val editor = receipt("theme.custom", ExecutionScope.EDITOR, PackageBackendId.DEVICE_ADB)
        val records = listOf(local, remote, editor)
        val owns: (ManagedPackageRecord) -> Boolean = { it.metadata[PackageBackendContract.METADATA_KEY] == PackageBackendId.LOCAL_APP.name }
        val healthy = DevelopmentExtensionProjection.recordsForSnapshot(records, null, owns)
        assertEquals(records, healthy.catalogRecords)
        assertEquals(listOf(local, editor), healthy.localCapabilityRecords)
        val failed = DevelopmentExtensionProjection.recordsForSnapshot(records, "Recovery failed", owns)
        assertEquals(records, failed.catalogRecords)
        assertEquals(listOf(editor), failed.localCapabilityRecords)
        assertTrue(failed.catalogRecords.any { it.familyId == "cli.git" })
        assertFalse(failed.localCapabilityRecords.any { "git" in it.commands && it.familyId == "cli.git" })
    }

    private fun deviceWorkstation() = AndroidDevelopmentManager.WorkstationInfo(
        version = "1", abi = "arm64-v8a", javaVersion = 17, compileSdk = 36,
        sdkPlatforms = setOf(35, 36), buildToolsVersions = setOf("36.0.0"),
        ndkVersions = emptySet(), cmakeVersions = emptySet(), javaHome = "/remote/jdk",
        sdkRoot = "/remote/sdk", aapt2Path = "/remote/sdk/build-tools/36.0.0/aapt2",
        executionMode = AndroidToolchainExecutionMode.LINUX_ARM64_PROOT,
    )

    @Test fun bridgeSdk36CannotMarkAppLocalSdk36AsInstalled() {
        val family = requireNotNull(DevelopmentExtensionCatalog.byId("sdk.android"))
        val local = ExtensionVersion("36", installKind = ExtensionInstallKind.ANDROID_LOCAL_COMPONENT)
        assertNull(DevelopmentExtensionProjection.detectedDeviceComponent(family, local, deviceWorkstation()))
        val device = local.copy(installKind = ExtensionInstallKind.CATALOG_ONLY)
        val detected = requireNotNull(DevelopmentExtensionProjection.detectedDeviceComponent(family, device, deviceWorkstation()))
        assertEquals(ExtensionState.INSTALLED, detected.state)
        assertTrue(detected.detail.contains("Device Workstation"))
    }

    @Test fun bridgeJdk17CannotSatisfyReviewedLocalJdk17Recipe() {
        val family = requireNotNull(DevelopmentExtensionCatalog.byId("toolchain.jdk"))
        val local = ExtensionVersion("17", installKind = ExtensionInstallKind.REVIEWED_RECIPE)
        assertNull(DevelopmentExtensionProjection.detectedDeviceComponent(family, local, deviceWorkstation()))
        val device = local.copy(installKind = ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN)
        val detected = requireNotNull(DevelopmentExtensionProjection.detectedDeviceComponent(family, device, deviceWorkstation()))
        assertEquals(ExtensionState.INSTALLED, detected.state)
        assertTrue(detected.detail.contains("Device Workstation"))
        assertNull(DevelopmentExtensionProjection.detectedDeviceComponent(family, device.copy(version = "21"), deviceWorkstation()))
    }

    @Test fun bridgeBuildToolsDoNotSatisfyTheLocalAndroidComponentInstaller() {
        val family = requireNotNull(DevelopmentExtensionCatalog.byId("build.android-tools"))
        val local = ExtensionVersion("36.0.0", installKind = ExtensionInstallKind.ANDROID_LOCAL_COMPONENT)
        assertNull(DevelopmentExtensionProjection.detectedDeviceComponent(family, local, deviceWorkstation()))
        val device = local.copy(installKind = ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN)
        val detected = requireNotNull(DevelopmentExtensionProjection.detectedDeviceComponent(family, device, deviceWorkstation()))
        assertEquals(ExtensionState.INSTALLED, detected.state)
        assertTrue(detected.detail.contains("Device Workstation"))
        assertNull(DevelopmentExtensionProjection.detectedDeviceComponent(family, device, null))
    }
}
