package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w900dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsMotionWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("settings-motion-").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = RuntimeEnvironment.getApplication()
    private val terminals by lazy { TerminalManager(scope, root) }
    private val bridge by lazy { DeviceBridgeManager(app, scope) }
    private val development by lazy { AndroidDevelopmentManager(app, root, bridge) }
    private val size = mutableStateOf(900.dp to 640.dp)
    private val wrap = mutableStateOf(false)
    private var closes = 0
    private val mounted = mutableStateOf(true)

    // Android's journal requires a positive app PID; Robolectric Application defaults to zero. No
    // operation is started and no real process is signaled by these settings-only tests.
    @Before fun prepareProcessIdentity() { ShadowProcess.setPid(4242) }

    @After fun cleanup() { terminals.dispose(false); bridge.close(); scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }

    private fun mount(
        themeAction: suspend (String) -> Unit = {},
        permissionAction: suspend (String, PermEffect) -> PermissionPolicyDocument = { _, _ -> PermissionPolicyDocument.EMPTY },
        category: String? = null,
        launchContext: android.content.Context? = null,
        wordwrapAction: suspend (Boolean) -> Unit = { wrap.value = it },
        retryAction: suspend (String) -> String = { "Retry checked" },
        configAction: suspend () -> Unit = {},
        health: List<McpHealthSnapshot> = emptyList(),
    ) {
        val themes = ThemeManager(root)
        val formatter = FormatterManager(root, terminals)
        val github = GitHubAccountManager(app)
        compose.setContent { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides (launchContext ?: app)) { DroideTheme { Box(Modifier.size(size.value.first, size.value.second)) {
            if (mounted.value) SettingsScreen(themes, formatter, wrap.value, false, true, CodingKeyboardMode.COMPACT,
                CodeStyleDefaults(), development, bridge, github, "Motion project", PermissionPolicyDocument.EMPTY,
                onWordwrap = wordwrapAction, onHardwareShortcuts = {}, onAccessoryKeysComfortable = {},
                onCodingKeyboardMode = {}, onDetectIndentation = {}, onIndentStyle = {}, onTabWidth = {},
                onIndentSize = {}, onContinuationIndent = {}, onTheme = themeAction, onPermissionRuleChange = permissionAction,
                onOpenProviders = {}, onOpenModels = {}, onOpenExtensions = {}, onOpenFiles = {},
                onOpenTerminal = {}, onOpenGit = {}, onOpenRunDebug = {}, onOpenAgent = {},
                onOpenAgentSessions = {}, onOpenCapabilityHealth = {}, mcpHealth = health,
                onRetryMcp = retryAction, onOpenMcpConfig = configAction, initialCategoryName = category, onDismiss = { closes++; mounted.value = false })
        } } } }
    }

    @Test fun switchingCategoryStartsAtItsOwnTopAfterTheEditorPageWasScrolled() {
        mount()
        compose.onNodeWithText("Editor").performClick()
        compose.onNodeWithText("Authority").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Terminal").performClick()
        compose.onNodeWithText("Input").assertIsDisplayed()
        compose.onNodeWithText("Workspace", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Switch to User settings").assertIsDisplayed()
    }

    @Test fun compactSettingsBackAndCloseWorkWhileTheCategoryIsEntering() {
        size.value = 360.dp to 640.dp
        compose.mainClock.autoAdvance = false
        mount()
        compose.onNodeWithText("Editor").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Word wrap").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close settings").performClick()
        assertEquals(1, closes)
    }
    @Test fun failedThemeSelectionKeepsOldRadioAndShowsError() {
        val manager = ThemeManager(root); val old = manager.current().name; val next = manager.list().first { it.name != old }.name
        mount(themeAction = { error("disk unavailable") }, category = "APPEARANCE")
        compose.onNodeWithText("Workspace", useUnmergedTree = true).performClick()
        compose.onNodeWithText(next).performScrollTo().performClick()
        compose.onNodeWithText("Could not save theme: disk unavailable").assertIsDisplayed()
        compose.onNodeWithText(old).performScrollTo().assertIsSelected()
    }

    @Test fun permissionFailureKeepsOldChipAndShowsError() {
        mount(permissionAction = { _, _ -> error("policy conflict") }, category = "PERMISSIONS")
        compose.onNodeWithText("Workspace", useUnmergedTree = true).performClick()
        compose.onAllNodesWithText("Deny")[0].performClick()
        compose.onNodeWithText("Permission update failed: policy conflict").assertIsDisplayed()
        compose.onAllNodesWithText("Allow")[0].assertIsSelected()
    }

    @Test fun notificationLaunchFailureIsVisible() {
        val context = object : android.content.ContextWrapper(app) {
            override fun startActivity(intent: android.content.Intent) { throw android.content.ActivityNotFoundException("no handler") }
        }
        mount(category = "NOTIFICATIONS", launchContext = context)
        compose.onNodeWithText("Android notification settings").performClick()
        compose.onNodeWithText("Could not open Android notification settings: no handler").assertIsDisplayed()
    }

    @Test fun notificationIntentIsScopedToThisApp() {
        var launched: android.content.Intent? = null
        val context = object : android.content.ContextWrapper(app) {
            override fun startActivity(intent: android.content.Intent) { launched = intent }
        }
        mount(category = "NOTIFICATIONS", launchContext = context)
        compose.onNodeWithText("Android notification settings").performClick()
        compose.runOnIdle {
            assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, launched?.action)
            assertEquals(app.packageName, launched?.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
        }
    }

    @Test fun preferenceSaveFailureIsVisibleInsideSettings() {
        mount(category = "EDITOR", wordwrapAction = { error("preferences unavailable") })
        compose.onNodeWithText("Word wrap").performClick()
        compose.onNodeWithText("Could not save word wrap: preferences unavailable").assertIsDisplayed()
        compose.runOnIdle { assertEquals(false, wrap.value) }
    }

    @Test fun pendingPreferenceDisablesOnlyItsControlAndDismissCancels() {
        var started = 0; var cancelled = false
        mount(category = "EDITOR", wordwrapAction = {
            started++
            try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true }
        })
        compose.onNodeWithText("Word wrap").performClick()
        compose.onNodeWithText("Word wrap").assertIsNotEnabled()
        compose.onNodeWithText("Detect indentation").assertIsEnabled()
        compose.onNodeWithContentDescription("Close settings").performClick()
        compose.waitUntil { cancelled }
        compose.runOnIdle { assertEquals(1, started) }
    }

    @Test fun mcpRetryResultIsVisibleInSettingsWithoutLeavingPage() {
        val item = McpHealthSnapshot("fixture", McpHealthState.CONFIGURED, McpHealthPhase.CONFIGURATION, "Configured")
        mount(category = "TOOLS", health = listOf(item), retryAction = { "fixture: approval required" })
        compose.onNodeWithText("fixture").performClick()
        compose.onNodeWithText("Retry check").performScrollTo().performClick()
        compose.onNodeWithText("fixture: approval required").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, closes) }
    }

    @Test fun mcpConfigOpenFailureStaysVisibleInSettings() {
        mount(category = "TOOLS", configAction = { error("editor cannot open config") })
        compose.onNodeWithText("Open .droide/mcp.json").performScrollTo().performClick()
        compose.onNodeWithText("Could not open MCP configuration: editor cannot open config").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, closes) }
    }

    @Test fun searchFindsRealIndentationAndReleaseStatusDestination() {
        size.value = 360.dp to 640.dp
        mount()
        compose.onNodeWithText("Search settings").performTextInput("continuation indent")
        compose.onNodeWithText("Editor").assertIsDisplayed()
        compose.onNodeWithText("Editor").performClick()
        compose.onNodeWithText("Continuation indent").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Search settings").performTextInput("release status")
        compose.onNodeWithText("About & Legal").performClick()
        compose.onNodeWithText("Release status").performScrollTo().performClick()
        compose.onNodeWithText("Release & Distribution Status").assertIsDisplayed()
    }

}
