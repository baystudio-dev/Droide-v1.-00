package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w420dp-h860dp")
class PrimaryRunHeaderTest {
    @get:Rule val compose = createComposeRule()
    private var runs = 0
    private var devices = 0
    private var path by mutableStateOf("untitled.cs")
    private var busy by mutableStateOf(false)

    private fun mount(compact: Boolean = true, android: Boolean = true, connected: Boolean = false) {
        compose.setContent {
            DroideTheme {
                DroideIdeHeader(
                    DroideIdeHeaderState("Project", path, compact, false, false, false, false,
                        busy, android, connected, false, false, false, emptyList()),
                    DroideIdeHeaderActions(
                        onProjects = {}, onRun = { runs++ }, onBuildDebug = {}, onTest = {},
                        onTestExplorer = {}, onLint = {}, onReleaseApk = {}, onReleaseBundle = {},
                        onBuildOutput = {}, onApkAnalyzer = {}, onCancel = {}, onDebugAndroid = {},
                        onInstallRun = {}, onLogcat = {}, onLocalLinux = {},
                        onAndroidDevelopment = { devices++ }, onQuickOpen = {}, onSearchFiles = {},
                        onWorkspaceSymbols = {}, onProblems = {}, onTerminal = {}, onGit = {},
                        onReviewChanges = {}, onIsolatedWorkspaces = {}, onCapabilityHealth = {},
                        onTasks = {}, onAgent = {}, onFocusMode = {}, onCommandPalette = {},
                        onRunDebug = {}, onSessions = {}, onExtensions = {}, onConnectProvider = {},
                        onModels = {}, onBilling = {}, onSettings = {}, onMcpRetry = {}, onManageMcp = {},
                    ),
                )
            }
        }
    }

    @Test fun csharpInAndroidLookingWorkspaceStillOffersRunCurrentFile() {
        mount()
        compose.onNodeWithContentDescription("Run current file").performClick()
        assertEquals(1, runs)
        assertEquals(0, devices)
    }

    @Test fun switchingFromAndroidSourceToPythonChangesTheRunAction() {
        path = "app/src/main/MainActivity.kt"
        mount(compact = false)
        compose.onNodeWithText("Run App").assertExists()
        compose.runOnIdle { path = "tools/check.py" }
        compose.onNodeWithText("Run", substring = false).performClick()
        assertEquals(1, runs)
        assertEquals(0, devices)
    }

    @Test fun htmlStillOffersPreviewInAnAndroidProject() {
        path = "app/src/main/assets/index.html"
        mount()
        compose.onNodeWithContentDescription("Preview current file").performClick()
        assertEquals(1, runs)
        assertEquals(0, devices)
    }

    @Test fun androidSourceRetainsTheAppRunAction() {
        path = "app/src/main/MainActivity.kt"
        mount(connected = true)
        compose.onNodeWithContentDescription("Build, install and run Android app").performClick()
        assertEquals(1, runs)
        assertEquals(0, devices)
    }

    @Test fun runningWorkDisablesRunWithoutChangingItsDestination() {
        mount(android = false)
        compose.runOnIdle { busy = true }
        compose.onNodeWithContentDescription("Run current file").assertIsNotEnabled()
        assertEquals(0, runs)
        assertEquals(0, devices)
    }
}
