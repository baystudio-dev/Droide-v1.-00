package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w420dp-h860dp")
class IdeHeaderDispatchTest {
    @get:Rule val compose = createComposeRule()
    private val events = mutableListOf<String>()
    private var path by mutableStateOf("main.rs")
    private var android by mutableStateOf(true)
    private var busy by mutableStateOf(false)
    private fun record(name: String): () -> Unit = { events += name }
    private fun mount(compact: Boolean = true, wide: Boolean = false) {
        compose.setContent { DroideTheme {
            DroideIdeHeader(
                DroideIdeHeaderState("Project", path, compact, wide, false, wide, false, busy,
                    android, true, true, true, true, emptyList()),
                DroideIdeHeaderActions(
                    onProjects = record("Projects"), onRun = record("Run"), onBuildDebug = record("Build"),
                    onTest = record("Test"), onTestExplorer = record("Test Explorer"), onLint = record("Lint"),
                    onReleaseApk = record("APK"), onReleaseBundle = record("Bundle"), onBuildOutput = record("Output"),
                    onApkAnalyzer = record("Analyzer"), onCancel = record("Cancel"), onDebugAndroid = record("Debug Android"),
                    onInstallRun = record("Install"), onLogcat = record("Logcat"), onLocalLinux = record("Ubuntu"),
                    onAndroidDevelopment = record("Device"), onQuickOpen = record("Quick Open"), onSearchFiles = record("Search"),
                    onWorkspaceSymbols = record("Symbols"), onProblems = record("Problems"), onTerminal = record("Terminal"),
                    onGit = record("Git"), onReviewChanges = record("Review"), onIsolatedWorkspaces = record("Workspaces"),
                    onCapabilityHealth = record("Health"), onTasks = record("Tasks"), onAgent = record("Agent"),
                    onFocusMode = record("Focus"), onCommandPalette = record("Palette"), onRunDebug = record("Debug"),
                    onSessions = record("Sessions"), onExtensions = record("Extensions"), onConnectProvider = record("Provider"),
                    onModels = record("Models"), onBilling = record("Billing"), onSettings = record("Settings"),
                    onMcpRetry = { events += "MCP:$it" }, onManageMcp = record("MCP"),
                    onNotificationSettings = record("Notifications"),
                ),
            )
        } }
    }

    @Test fun everyLanguageRunTouchCallsOnlyRunInCompactHeader() {
        mount()
        for (sample in catalogRunPaths()) for (androidProject in listOf(false, true)) {
            compose.runOnIdle { path = sample; android = androidProject; events.clear() }
            val description = when (expectedCatalogTarget(sample, androidProject)) {
                PrimaryRunTarget.FILE -> "Run current file"
                PrimaryRunTarget.PREVIEW -> "Preview current file"
                PrimaryRunTarget.ANDROID_APP -> "Build, install and run Android app"
            }
            compose.onNodeWithContentDescription(description).performTouchInput { click(center) }
            compose.runOnIdle { assertEquals("$sample android=$androidProject", listOf("Run"), events.toList()) }
        }
    }

    @Test @Config(qualifiers = "w960dp-h420dp-land")
    fun landscapeRunTouchesNeverHitTheAdjacentDeviceOrToolsButtons() {
        mount(compact = false, wide = true)
        for (sample in catalogRunPaths()) {
            compose.runOnIdle { path = sample; events.clear() }
            val label = when (expectedCatalogTarget(sample, true)) {
                PrimaryRunTarget.FILE -> "Run"
                PrimaryRunTarget.PREVIEW -> "Preview"
                PrimaryRunTarget.ANDROID_APP -> "Run App"
            }
            compose.onNodeWithText(label).performTouchInput { click(center) }
            compose.runOnIdle { assertEquals(sample, listOf("Run"), events.toList()) }
        }
    }

    @Test fun allOverflowActionsReachTheirNamedCallback() {
        mount()
        val actions = listOf("Projects" to "Projects", "Open AI" to "Agent", "Run current file or app" to "Run", "Manage MCP" to "MCP", "Project Tasks" to "Tasks", "Build Debug APK" to "Build", "Test Tasks" to "Test Explorer",
            "Android Test" to "Test", "Lint" to "Lint", "Release APK" to "APK", "Release Bundle" to "Bundle",
            "Build / Run Output" to "Output", "APK Analyzer" to "Analyzer", "Cancel active operation" to "Cancel",
            "Run & Debug" to "Debug", "Debug Android App" to "Debug Android", "Install & Run" to "Install",
            "Logcat" to "Logcat", "Problems" to "Problems", "Quick Open" to "Quick Open", "Search in Files" to "Search",
            "Workspace Symbols" to "Symbols", "Terminal" to "Terminal", "Source Control" to "Git",
            "Review Changes" to "Review", "Isolated Workspaces" to "Workspaces", "Capability Health" to "Health",
            "Extensions" to "Extensions", "Agent Sessions" to "Sessions", "Ubuntu Terminal" to "Ubuntu",
            "Android SDK / Toolchain" to "Device", "Command Palette" to "Palette", "Connect Provider" to "Provider",
            "Models" to "Models", "Billing / Usage" to "Billing", "Notification settings" to "Notifications", "Settings" to "Settings")
        for ((label, callback) in actions) {
            compose.runOnIdle { events.clear() }
            compose.onNodeWithContentDescription("More").performTouchInput { click(center) }
            compose.onNodeWithText(label).performScrollTo().performTouchInput { click(center) }
            compose.runOnIdle { assertEquals(label, listOf(callback), events.toList()) }
        }
    }

    @Test fun busyRunAndNonAndroidBuildButtonsStayDisabled() {
        mount()
        compose.runOnIdle { busy = true; android = false }
        compose.onNodeWithContentDescription("Run current file").assertIsNotEnabled()
        compose.onNodeWithContentDescription("More").performClick()
        for (label in listOf("Build Debug APK", "Android Test", "Lint", "Release APK", "Release Bundle", "Debug Android App", "Install & Run"))
            compose.onNodeWithText(label).assertIsNotEnabled()
        assertTrue(events.isEmpty())
    }
}
