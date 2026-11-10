package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DismissibleNoticeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dismissalSurvivesStateRestoreButChangedMessageAndOwnerReappear() {
        var message by mutableStateOf("First failure")
        var owner by mutableStateOf("project-one")
        val restoration = StateRestorationTester(compose)
        restoration.setContent { DroideTheme { DismissibleNotice(message, ownerKey = owner) } }
        compose.onNodeWithContentDescription("Dismiss message").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performTouchInput { click(center) }
        compose.onNodeWithText("First failure").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("First failure").assertDoesNotExist()
        compose.runOnIdle { message = "Second failure" }
        compose.onNodeWithText("Second failure").assertExists()
        compose.onNodeWithContentDescription("Dismiss message").performClick()
        compose.runOnIdle { owner = "project-two" }
        compose.onNodeWithText("Second failure").assertExists()
    }

    @Test fun separateNoticesDoNotDismissEachOtherOrInvokeActions() {
        var retries = 0
        compose.setContent { DroideTheme { Column {
            DismissibleNotice("First failure", actions = { TextButton(onClick = { retries++ }) { Text("Retry") } })
            DismissibleNotice("Second failure")
        } } }
        compose.onAllNodesWithContentDescription("Dismiss message")[0].performClick()
        compose.onNodeWithText("First failure").assertDoesNotExist()
        compose.onNodeWithText("Second failure").assertExists()
        assertEquals(0, retries)
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    @Test fun snackbarDismissIsNotTheConnectActionAndNextMessageCanAppear() {
        val host = SnackbarHostState()
        val results = mutableListOf<SnackbarResult>()
        compose.setContent { DroideTheme {
            LaunchedEffect(Unit) {
                results += host.showSnackbar("Connect a device", "Connect", duration = SnackbarDuration.Indefinite)
                results += host.showSnackbar("Second message", duration = SnackbarDuration.Indefinite)
            }
            DismissibleSnackbarHost(host)
        } }
        compose.onNodeWithContentDescription("Dismiss message").performClick()
        compose.onNodeWithText("Second message").assertIsDisplayed()
        assertEquals(listOf(SnackbarResult.Dismissed), results)
        compose.onNodeWithContentDescription("Dismiss message").performClick()
        compose.runOnIdle { assertEquals(listOf(SnackbarResult.Dismissed, SnackbarResult.Dismissed), results) }
    }

    @Test fun snackbarConnectActionKeepsItsOwnResult() {
        val host = SnackbarHostState()
        var result: SnackbarResult? = null
        compose.setContent { DroideTheme {
            LaunchedEffect(Unit) { result = host.showSnackbar("Connect a device", "Connect", duration = SnackbarDuration.Indefinite) }
            DismissibleSnackbarHost(host)
        } }
        compose.onNodeWithText("Connect").performClick()
        compose.runOnIdle { assertEquals(SnackbarResult.ActionPerformed, result) }
    }
}
