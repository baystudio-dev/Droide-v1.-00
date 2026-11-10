package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
class ExtensionCatalogUiTest {
    @get:Rule val compose = createComposeRule()

    private fun item(version: String, state: ExtensionState, detail: String = ""): ExtensionVersionState {
        val entry = ExtensionVersion(version, recommended = state == ExtensionState.AVAILABLE,
            installKind = if (state == ExtensionState.EXTERNAL || state == ExtensionState.UNAVAILABLE)
                ExtensionInstallKind.CATALOG_ONLY else ExtensionInstallKind.REVIEWED_RECIPE)
        val family = ExtensionFamily("lsp.python-pyright", "Python Pyright", ExtensionCategory.LANGUAGE_SERVERS,
            "Python language server", listOf(entry), "language:python", "Microsoft")
        return ExtensionVersionState(family, entry, state, detail)
    }

    @Test fun screenshotFamilyAppearsOnceAndOpensActualInstallableChoice() {
        val ready = item("Latest npm", ExtensionState.AVAILABLE)
        val cards = extensionCatalogCards(listOf(item("Local", ExtensionState.UNAVAILABLE),
            item("Workstation", ExtensionState.UNAVAILABLE), ready))
        var selected: ExtensionVersionState? = null
        compose.setContent { DroideTheme { Column(Modifier.width(360.dp)) {
            cards.forEach { card -> ExtensionRow(card) { selected = card.primary } }
        } } }
        compose.onAllNodesWithText("Python Pyright").assertCountEquals(1)
        compose.onNodeWithText("Not installed").assertIsDisplayed()
        compose.onNodeWithContentDescription("AVAILABLE", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Unavailable").assertDoesNotExist()
        compose.onNodeWithText("Python Pyright").performClick()
        assertEquals(ready, selected)
    }

    @Test fun externallyInstalledChoiceUsesPlainInstalledStatusWithoutInfoBadge() {
        val detected = item("External", ExtensionState.EXTERNAL)
        compose.setContent { DroideTheme { Column(Modifier.width(360.dp)) {
            ExtensionRow(extensionCatalogCards(listOf(detected)).single()) {}
        } } }
        compose.onNodeWithText("Installed").assertIsDisplayed()
        compose.onNodeWithContentDescription("EXTERNAL", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun installedVersionsAndDetectedEnvironmentStayVisibleInOneCard() {
        val cards = extensionCatalogCards(listOf(item("1.9.2", ExtensionState.INSTALLED, "Installed · workspace default"),
            item("2.1.0", ExtensionState.INSTALLED), item("External", ExtensionState.EXTERNAL)))
        compose.setContent { DroideTheme { Column(Modifier.width(360.dp)) {
            ExtensionRow(cards.single()) {}
        } } }
        compose.onAllNodesWithText("Python Pyright").assertCountEquals(1)
        compose.onNodeWithText("Versions and environments available").assertIsDisplayed()
        val summary = requireNotNull(extensionInstalledSummary(cards.single()))
        compose.onNodeWithText(summary).assertIsDisplayed()
        assertTrue(summary.contains("1.9.2 installed"))
        assertTrue(summary.contains("2.1.0 installed"))
        assertTrue(summary.contains("detected outside Droide"))
    }

    @Test fun detailSelectsManagedVersionAndDisablesVersionChangesDuringInstallation() {
        val detected = item("External", ExtensionState.EXTERNAL)
        val ready = item("Latest npm", ExtensionState.AVAILABLE)
        var selected by mutableStateOf(detected)
        var enabled by mutableStateOf(true)
        val selections = mutableListOf<ExtensionVersionState>()
        compose.setContent { DroideTheme { Column(Modifier.width(360.dp)) {
            ExtensionVersionChoices(listOf(detected, ready), selected, enabled) { selected = it; selections += it }
        } } }
        compose.onNodeWithText(extensionChoiceLabel(ready)).assertDoesNotExist()
        compose.onNodeWithText("Versions and environments").performClick()
        compose.onNodeWithText(extensionChoiceLabel(detected)).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(extensionChoiceLabel(ready)).assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf(ready), selections)
        compose.onNodeWithText("Versions and environments").performClick()
        compose.onNodeWithText(extensionChoiceLabel(ready)).assertIsNotEnabled()
        compose.runOnIdle { enabled = false }
        compose.onNodeWithText("Versions and environments").assertIsNotEnabled()
        compose.onNodeWithText(extensionChoiceLabel(detected)).assertIsNotEnabled()
    }

    @Test fun initialFamilyRequestWaitsForDiscoveryThenOpensManagedRecipeOnlyOnce() {
        val placeholder = item("External", ExtensionState.UNAVAILABLE)
        val recipe = item("ubuntu-24.04", ExtensionState.AVAILABLE)
        var items by mutableStateOf(listOf(placeholder))
        var pending by mutableStateOf(true)
        var initialFamily by mutableStateOf<String?>(placeholder.family.id)
        var selection by mutableStateOf<ExtensionSelection?>(null)
        var handled = 0
        compose.setContent { DroideTheme {
            ExtensionNavigationEffects(items, initialFamily, pending, selection, { selection = it },
                { handled++; initialFamily = null })
            Text(selection?.version ?: "Discovering extensions")
        } }
        compose.onNodeWithText("Discovering extensions").assertIsDisplayed()
        compose.runOnIdle { assertNull(selection); assertEquals(0, handled) }
        compose.runOnIdle { items = listOf(recipe); pending = false }
        compose.onNodeWithText("ubuntu-24.04").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, handled); assertNull(initialFamily) }
        compose.runOnIdle { items = listOf(recipe.copy(detail = "Refreshed")) }
        compose.runOnIdle { assertEquals(1, handled) }
    }

    @Test fun discoveryRemovalOfOldExternalPlaceholderKeepsFamilyDetailOnValidManagedChoice() {
        val placeholder = item("External", ExtensionState.UNAVAILABLE)
        val recipe = item("ubuntu-24.04", ExtensionState.AVAILABLE)
        var items by mutableStateOf(listOf(placeholder))
        var selection by mutableStateOf<ExtensionSelection?>(ExtensionSelection(placeholder.family.id, placeholder.version.version))
        compose.setContent { DroideTheme {
            ExtensionNavigationEffects(items, null, false, selection, { selection = it }, {})
            Text(selection?.version ?: "Extension list")
        } }
        compose.onNodeWithText("External").assertIsDisplayed()
        compose.runOnIdle { items = listOf(recipe) }
        compose.onNodeWithText("ubuntu-24.04").assertIsDisplayed()
        compose.runOnIdle { assertEquals(placeholder.family.id, selection?.familyId) }
        compose.runOnIdle { items = emptyList() }
        compose.onNodeWithText("Extension list").assertIsDisplayed()
    }
}
