package com.baystudio.droide.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowProcess

/** Uses production Settings with native rendering; external launches are captured. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w900dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsOfficialLinksWorkflowR74Test {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("settings-links-").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = RuntimeEnvironment.getApplication()
    private val terminals by lazy { TerminalManager(scope, root) }
    private val bridge by lazy { DeviceBridgeManager(app, scope) }
    private val development by lazy { AndroidDevelopmentManager(app, root, bridge) }
    private val size = mutableStateOf(900.dp to 640.dp)
    private val mounted = mutableStateOf(true)
    private val launched = mutableListOf<Intent>()
    private var closes = 0
    private var failLaunch: (Intent) -> Boolean = { false }
    private val launchContext by lazy { object : ContextWrapper(app) {
        override fun startActivity(intent: Intent) {
            launched += intent
            if (failLaunch(intent)) throw ActivityNotFoundException("controlled no-handler case")
        }
    } }
    private val clipboard get() = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Before fun prepareProcessIdentity() { ShadowProcess.setPid(4242) }
    @After fun cleanup() { terminals.dispose(false); bridge.close(); scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }

    private fun mount(category: String? = "ABOUT", restoration: StateRestorationTester? = null, fontScale: Float = 1f) {
        val themes = ThemeManager(root)
        val formatters = FormatterManager(root, terminals)
        val github = GitHubAccountManager(app)
        val content: @Composable () -> Unit = {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides launchContext, LocalDensity provides Density(density.density, fontScale)) {
                DroideTheme { Box(Modifier.size(size.value.first, size.value.second)) {
                    if (mounted.value) SettingsScreen(themes, formatters, false, false, true, CodingKeyboardMode.COMPACT,
                        CodeStyleDefaults(), development, bridge, github, "Official links project", PermissionPolicyDocument.EMPTY,
                        onWordwrap = {}, onHardwareShortcuts = {}, onAccessoryKeysComfortable = {},
                        onCodingKeyboardMode = {}, onDetectIndentation = {}, onIndentStyle = {}, onTabWidth = {},
                        onIndentSize = {}, onContinuationIndent = {}, onTheme = {},
                        onPermissionRuleChange = { _, _ -> PermissionPolicyDocument.EMPTY },
                        onOpenProviders = {}, onOpenModels = {}, onOpenExtensions = {}, onOpenFiles = {},
                        onOpenTerminal = {}, onOpenGit = {}, onOpenRunDebug = {}, onOpenAgent = {},
                        onOpenAgentSessions = {}, onOpenCapabilityHealth = {}, mcpHealth = emptyList(),
                        onRetryMcp = { "Unused" }, onOpenMcpConfig = {}, initialCategoryName = category,
                        onDismiss = { closes++; mounted.value = false })
                } }
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
    }

    @Test fun aboutAndLegalNavigationNeverAutomaticallyLaunchExternalApps() {
        mount()
        compose.onNodeWithText(SettingsOfficialLinks.DEVELOPER_DESCRIPTION).assertExists()
        compose.onNodeWithText("Privacy").performScrollTo().performClick()
        compose.onNodeWithText(SettingsOfficialLinks.PRIVACY_NOTE).assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Terms of Service").performScrollTo().performClick()
        compose.onNodeWithText(SettingsOfficialLinks.TERMS_NOTE).assertExists()
        compose.runOnIdle { assertTrue(launched.isEmpty()); assertEquals(0, closes) }
    }

    @Test fun externalAboutActionsHaveButtonSemanticsAndKeepTheirExactDestination() {
        mount()
        (SettingsOfficialLinks.about + SettingsOfficialLinks.support.filter { it != SettingsOfficialLink.REPORT_BUG }).forEachIndexed { index, link ->
            compose.onNodeWithText(link.title).performScrollTo().assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
            compose.runOnIdle { assertEquals(index + 1, launched.size); assertEquals(link.url, launched.last().dataString) }
        }
        compose.runOnIdle { assertEquals(0, closes) }
    }

    @Test fun reportBugOpensNativeFormWithoutAnExternalIntent() {
        mount()
        compose.onNodeWithText("Report a bug").performScrollTo().performClick()
        compose.onNodeWithText("To BayStudio support").assertExists()
        compose.onNodeWithText("This native form currently supports text only.", substring = true).assertExists()
        // No LocalExitUiWorkOwner in this fixture, so no network request can be admitted.
        compose.onNodeWithText("This Droide session is stopped. Your draft is still here.").assertExists()
        compose.runOnIdle { assertTrue(launched.isEmpty()); assertEquals(0, closes) }
        compose.onNodeWithContentDescription("Close bug report").performClick()
        compose.onNodeWithText("To BayStudio support").assertDoesNotExist()
    }

    @Test fun compactPrivacyRouteOpensOnlyOnTapAndBackReturnsToAbout() {
        size.value = 360.dp to 640.dp
        mount()
        compose.onNodeWithText("Privacy").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(launched.isEmpty()) }
        compose.onNodeWithText(SettingsOfficialLink.PRIVACY.title).performScrollTo().performClick()
        compose.onNodeWithText(SettingsOfficialLinks.PRIVACY_NOTE).assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Droide License").assertExists()
        compose.runOnIdle { assertEquals(listOf(SettingsOfficialLink.PRIVACY.url), launched.map { it.dataString }); assertEquals(0, closes) }
    }

    @Test fun wideTermsRouteHasNestedBackAndNoAgreementAction() {
        mount()
        compose.onNodeWithText("Terms of Service").performScrollTo().performClick()
        compose.onNodeWithText("I agree").assertDoesNotExist()
        compose.onNodeWithText("Accept").assertDoesNotExist()
        compose.onNodeWithText(SettingsOfficialLink.TERMS.title).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Official website").assertExists()
        compose.runOnIdle { assertEquals(SettingsOfficialLink.TERMS.url, launched.single().dataString); assertEquals(0, closes) }
    }

    @Test fun unavailableEmailHandlerShowsFailureAndCopiesOnlyTheSupportAddress() {
        failLaunch = { true }
        mount()
        compose.onNodeWithText(SettingsOfficialLink.SUPPORT.title).performScrollTo().performClick()
        compose.onNodeWithText(SettingsOfficialLink.SUPPORT.unavailableMessage).assertIsDisplayed()
        compose.onNodeWithText("Copy address").performClick()
        compose.onNodeWithText("Address copied").assertIsDisplayed()
        compose.runOnIdle { assertEquals("supportbaystudio@gmail.com", clipboard.primaryClip!!.getItemAt(0).text.toString()); assertEquals(0, closes) }
    }

    @Test fun unavailableWebHandlerCopiesTheExactInstagramQuery() {
        failLaunch = { true }
        mount()
        compose.onNodeWithText(SettingsOfficialLink.INSTAGRAM.title).performScrollTo().performClick()
        compose.onNodeWithText("Copy address").performClick()
        compose.runOnIdle { assertEquals(SettingsOfficialLink.INSTAGRAM.url, clipboard.primaryClip!!.getItemAt(0).text.toString()) }
    }

    private fun clickLinkAboveSnackbar(link: SettingsOfficialLink) {
        // The snackbar overlaps the row center; use its exposed top edge for a real touch.
        compose.onNodeWithText(link.title).performScrollTo().assertIsDisplayed()
            .performTouchInput { click(Offset(center.x, 8f)) }
    }

    @Test fun newerFailureReplacesOldCopyTarget() {
        failLaunch = { true }
        mount()
        compose.onNodeWithText(SettingsOfficialLink.WEBSITE.title).performScrollTo().performClick()
        compose.onNodeWithText("Copy address").assertIsDisplayed()
        clickLinkAboveSnackbar(SettingsOfficialLink.SUPPORT)
        compose.onNodeWithText("Copy address").performClick()
        compose.runOnIdle { assertEquals("supportbaystudio@gmail.com", clipboard.primaryClip!!.getItemAt(0).text.toString()); assertEquals(2, launched.size) }
    }

    @Test fun newerSuccessfulLaunchRemovesStaleCopyPrompt() {
        failLaunch = { it.dataString == SettingsOfficialLink.WEBSITE.url }
        mount()
        compose.onNodeWithText(SettingsOfficialLink.WEBSITE.title).performScrollTo().performClick()
        compose.onNodeWithText("Copy address").assertExists()
        clickLinkAboveSnackbar(SettingsOfficialLink.SOURCE)
        compose.onNodeWithText("Copy address").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, launched.size); assertEquals(0, closes) }
    }

    @Test fun restoringNestedPolicyRouteDoesNotReplayTheExternalLaunch() {
        size.value = 360.dp to 640.dp
        val restoration = StateRestorationTester(compose)
        mount(restoration = restoration)
        compose.onNodeWithText("Privacy").performScrollTo().performClick()
        compose.onNodeWithText(SettingsOfficialLink.PRIVACY.title).performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(SettingsOfficialLinks.PRIVACY_NOTE).assertExists()
        compose.runOnIdle { assertEquals(1, launched.size) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Terms of Service").performScrollTo().performClick()
        compose.onNodeWithText(SettingsOfficialLinks.TERMS_NOTE).assertExists()
        compose.runOnIdle { assertEquals(1, launched.size) }
    }

    @Test fun closingWhileFailurePromptIsOpenCancelsItWithoutCopyOrRetry() {
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("sentinel", "unchanged"))
        failLaunch = { true }
        mount()
        compose.onNodeWithText(SettingsOfficialLink.WEBSITE.title).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Close settings").performClick()
        compose.onNodeWithText("Copy address").assertDoesNotExist()
        compose.runOnIdle { assertEquals("unchanged", clipboard.primaryClip!!.getItemAt(0).text.toString()); assertEquals(1, closes); assertEquals(1, launched.size) }
    }

    @Test fun longTargetsRemainScrollableAndClickableAtLargeFontScale() {
        size.value = 360.dp to 640.dp
        mount(fontScale = 2f)
        listOf(SettingsOfficialLink.SOURCE, SettingsOfficialLink.INSTAGRAM, SettingsOfficialLink.SUPPORT).forEach { link ->
            compose.onNodeWithText(link.targetLabel).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(link.title).performScrollTo().assertHasClickAction().performClick()
        }
        compose.onNodeWithText("Privacy").performScrollTo().performClick()
        compose.onNodeWithText(SettingsOfficialLink.PRIVACY.title).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(4, launched.size); assertEquals(0, closes) }
    }

    @Test fun searchingForAnOfficialChannelFindsAboutWithoutLaunchingIt() {
        size.value = 360.dp to 640.dp
        mount(category = null)
        compose.onNodeWithText("Search settings").performTextInput("Bluesky")
        compose.onNodeWithText("About & Legal").performClick()
        compose.onNodeWithText(SettingsOfficialLink.BLUESKY.title).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(launched.isEmpty()) }
    }
}
