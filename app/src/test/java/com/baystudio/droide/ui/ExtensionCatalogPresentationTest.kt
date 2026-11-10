package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import org.junit.Assert.*
import org.junit.Test

class ExtensionCatalogPresentationTest {
    private fun item(
        version: String,
        state: ExtensionState,
        recommended: Boolean = false,
        kind: ExtensionInstallKind = ExtensionInstallKind.CATALOG_ONLY,
        scope: ExecutionScope = ExecutionScope.LOCAL_LINUX_ARM64,
        detail: String = "",
        familyId: String = "lsp.python-pyright",
    ): ExtensionVersionState {
        val entry = ExtensionVersion(version, recommended = recommended, installKind = kind, scope = scope,
            provides = setOf("pyright-langserver"))
        val family = ExtensionFamily(familyId, "Python Pyright", ExtensionCategory.LANGUAGE_SERVERS,
            "Python language server", listOf(entry), "language:python", "Microsoft")
        return ExtensionVersionState(family, entry, state, detail)
    }

    @Test fun screenshotDetectionPlaceholdersBecomeOneInstallableFamilyCard() {
        val available = item("Latest npm", ExtensionState.AVAILABLE, true, ExtensionInstallKind.REVIEWED_RECIPE)
        val cards = extensionCatalogCards(listOf(item("Local", ExtensionState.UNAVAILABLE, scope = ExecutionScope.LOCAL),
            item("Workstation", ExtensionState.UNAVAILABLE), available))
        assertEquals(1, cards.size)
        assertEquals(available, cards.single().primary)
        assertEquals(listOf(available), extensionSelectableChoices(cards.single().choices))
        assertEquals("Latest npm · Local Ubuntu · ready to install", extensionChoiceLabel(available))
    }

    @Test fun unavailableExternalGitEntryNeverWinsOverItsAvailableManagedRecipe() {
        val external = item("External", ExtensionState.UNAVAILABLE, familyId = "cli.git")
        val apt = item("ubuntu-24.04", ExtensionState.AVAILABLE, true, ExtensionInstallKind.REVIEWED_RECIPE,
            familyId = "cli.git")
        assertEquals(apt, extensionDefaultChoice(listOf(external, apt)))
    }

    @Test fun workspaceDefaultHasPriorityAndOtherInstalledVersionsArePreserved() {
        val global = item("1.2.0", ExtensionState.INSTALLED, detail = "Installed · global default")
        val workspace = item("1.1.0", ExtensionState.INSTALLED, detail = "Installed · workspace default")
        val update = item("1.3.0", ExtensionState.AVAILABLE, true, ExtensionInstallKind.REVIEWED_RECIPE)
        val card = extensionCatalogCards(listOf(update, global, workspace)).single()
        assertEquals(workspace, card.primary)
        assertEquals(listOf(global, workspace), card.installed)
        assertEquals(3, extensionSelectableChoices(card.choices).size)
        assertTrue(extensionInstalledSummary(card)!!.contains("1.2.0 installed"))
        assertTrue(extensionInstalledSummary(card)!!.contains("1.1.0 installed"))
    }

    @Test fun detectedCommandDoesNotClaimItsUnprobedVersionAndKeepsManagedInstallChoice() {
        val detected = item("External", ExtensionState.EXTERNAL)
        val available = item("Latest npm", ExtensionState.AVAILABLE, true, ExtensionInstallKind.REVIEWED_RECIPE)
        val card = extensionCatalogCards(listOf(detected, available)).single()
        assertEquals(detected, card.primary)
        assertEquals("Local Ubuntu: detected outside Droide", extensionInstalledSummary(card))
        assertFalse(extensionInstalledSummary(card)!!.contains("Latest npm"))
        assertEquals(listOf(detected, available), extensionSelectableChoices(card.choices))
    }

    @Test fun updatedExternalExecutableIsPresentedAheadOfAnUnselectedManagedSnapshot() {
        val managed = item("1.18.34", ExtensionState.INSTALLED, detail = "Installed · global default")
        val updated = item("External", ExtensionState.EXTERNAL)
        assertEquals(updated, extensionDefaultChoice(listOf(managed, updated)))
        val pinned = managed.copy(detail = "Installed · workspace default")
        assertEquals(pinned, extensionDefaultChoice(listOf(updated, pinned)))
    }

    @Test fun searchIncludesEveryVersionAndExecutionEnvironmentWithoutDuplicateFamilies() {
        val installed = item("1.9.2", ExtensionState.INSTALLED, detail = "Installed · global default")
        val available = item("2.1.0", ExtensionState.AVAILABLE, true, ExtensionInstallKind.REVIEWED_RECIPE)
        val detectedDevice = item("Workstation", ExtensionState.EXTERNAL)
        val all = listOf(installed, available, detectedDevice)
        assertEquals(1, extensionCatalogCards(all, "2.1.0").size)
        assertEquals(1, extensionCatalogCards(all, "Device Workstation").size)
        assertEquals(1, extensionCatalogCards(all, "pyright-langserver").size)
        assertTrue(extensionCatalogCards(all, category = ExtensionCategory.CLI_TOOLS).isEmpty())
        assertEquals("Device Workstation · detected outside Droide", extensionChoiceLabel(detectedDevice))
    }

    @Test fun unavailableOnlyFamilyStaysVisibleWithItsRealFailureState() {
        val unavailable = item("Reviewed Ubuntu release", ExtensionState.UNAVAILABLE,
            kind = ExtensionInstallKind.REVIEWED_RECIPE, detail = "Package recovery failed")
        val card = extensionCatalogCards(listOf(unavailable)).single()
        assertEquals(unavailable, card.primary)
        assertTrue(extensionSelectableChoices(card.choices).isEmpty())
        assertNull(extensionInstalledSummary(card))
    }
    @Test fun contributedLanguageChoiceDoesNotClaimBundledOrigin() {
        LanguageRegistry.registerDeclarative("presentation-test", listOf(DeclarativeLanguageSpec("external-language", "External", listOf("extlang"), emptyList())))
        try {
            val family = DevelopmentExtensionCatalog.byId("language.external-language")!!
            val state = ExtensionVersionState(family, family.versions.single(), ExtensionState.BUILT_IN, "Editor syntax available")
            assertEquals("Extension-provided · Editor", extensionChoiceLabel(state))
            val builtin = DevelopmentExtensionCatalog.byId("language.kotlin")!!
            assertEquals("Built in · Editor", extensionChoiceLabel(ExtensionVersionState(builtin, builtin.versions.single(), ExtensionState.BUILT_IN, "")))
        } finally { LanguageRegistry.unregisterDeclarative("presentation-test") }
    }

    @Test fun freshAndroidSdkUsesExactReviewedInstallerInsteadOfUnbootstrappedLatest() {
        val reviewed = item("36", ExtensionState.AVAILABLE, true, ExtensionInstallKind.ANDROID_LOCAL_COMPONENT,
            familyId = "sdk.android")
        val external = item("User-installed", ExtensionState.UNAVAILABLE, familyId = "sdk.android")
        val choice = extensionReviewedAndroidChoice(listOf(external, reviewed), "sdk.android")!!
        assertEquals(reviewed, choice)
        val requested = ToolVersionRequests.select(listOf(reviewed, external), choice.family.id, choice.version.version)!!
        assertEquals(ExtensionInstallKind.ANDROID_LOCAL_COMPONENT, requested.version.installKind)
        assertEquals("36", requested.version.version)
        assertNotEquals("latest", requested.version.version)
    }

    @Test fun reviewedAndroidChoicePreservesUnavailableAndInstalledStates() {
        for (state in listOf(ExtensionState.AVAILABLE, ExtensionState.INSTALLED, ExtensionState.UNAVAILABLE)) {
            val reviewed = item("36.0.0", state, true, ExtensionInstallKind.ANDROID_LOCAL_COMPONENT,
                familyId = "build.android-tools")
            assertEquals(reviewed, extensionReviewedAndroidChoice(listOf(reviewed), "build.android-tools"))
        }
    }

    @Test fun reviewedAndroidChoiceCannotSelectOtherFamiliesOrDevicePacks() {
        val sdk = item("36", ExtensionState.AVAILABLE, true, ExtensionInstallKind.ANDROID_LOCAL_COMPONENT,
            familyId = "sdk.android")
        val pack = sdk.copy(version = sdk.version.copy(installKind = ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN))
        assertNull(extensionReviewedAndroidChoice(listOf(sdk), "build.android-tools"))
        assertNull(extensionReviewedAndroidChoice(listOf(pack), "sdk.android"))
        assertNull(extensionReviewedAndroidChoice(listOf(sdk), "runtime.node"))
    }

    @Test fun explicitUpstreamLatestIsNotSilentlyReplacedWithReviewedSdk() {
        val sdk = item("36", ExtensionState.AVAILABLE, true, ExtensionInstallKind.ANDROID_LOCAL_COMPONENT,
            familyId = "sdk.android")
        val requested = ToolVersionRequests.select(listOf(sdk), "sdk.android", "latest")!!
        assertEquals("latest", requested.version.version)
        assertEquals(ExtensionInstallKind.USER_MANAGED, requested.version.installKind)
    }

}
