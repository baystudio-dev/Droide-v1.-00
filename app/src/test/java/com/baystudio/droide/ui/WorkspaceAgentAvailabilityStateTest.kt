package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.baystudio.droide.core.WorkspaceAgentAvailability
import com.baystudio.droide.core.WorkspaceAgentPluginCatalog
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkspaceAgentAvailabilityStateTest {
    @get:Rule val compose = createComposeRule()
    private var expanded by mutableStateOf(false)
    private var hostIdentity by mutableStateOf<Any>(Any())
    private val installed = mutableSetOf<String>()
    private val calls = AtomicInteger()
    private var pending: CompletableDeferred<Unit>? = null

    private fun mount() {
        compose.setContent {
            DroideTheme {
                val availability = rememberWorkspaceAgentAvailability(hostIdentity, expanded) {
                    calls.incrementAndGet()
                    pending?.await()
                    WorkspaceAgentPluginCatalog.entries.map { spec ->
                        WorkspaceAgentAvailability(spec, if (spec.id in installed) "/usr/local/bin/${spec.command}" else null)
                    }
                }
                Column {
                    Button(onClick = { expanded = !expanded }) { Text(if (expanded) "Close agents" else "Open agents") }
                    availability.forEach { Text(it.spec.id + if (it.available) ": available" else ": unavailable") }
                }
            }
        }
    }

    @Test fun allFiveAgentsInstalledInTerminalAppearOnNextPickerOpen() {
        mount()
        WorkspaceAgentPluginCatalog.entries.forEach { compose.onNodeWithText(it.id + ": unavailable").assertExists() }
        compose.runOnIdle { installed += WorkspaceAgentPluginCatalog.entries.map { it.id } }
        compose.onNodeWithText("Open agents").performClick()
        WorkspaceAgentPluginCatalog.entries.forEach { compose.onNodeWithText(it.id + ": available").assertExists() }
        assertEquals(2, calls.get())
    }

    @Test fun removedAgentIsNoLongerOfferedOnReopen() {
        installed += "gemini"
        mount()
        compose.onNodeWithText("gemini: available").assertExists()
        compose.onNodeWithText("Open agents").performClick()
        compose.onNodeWithText("Close agents").performClick()
        compose.runOnIdle { installed.clear() }
        compose.onNodeWithText("Open agents").performClick()
        compose.onNodeWithText("gemini: unavailable").assertExists()
    }

    @Test fun closingPickerDoesNotRepeatChecksAndCancelledRefreshCannotEraseDetection() {
        installed += "codex"
        mount()
        compose.onNodeWithText("codex: available").assertExists()
        compose.runOnIdle { pending = CompletableDeferred() }
        compose.onNodeWithText("Open agents").performClick()
        compose.waitUntil { calls.get() == 2 }
        compose.onNodeWithText("Close agents").performClick()
        compose.onNodeWithText("codex: available").assertExists()
        compose.runOnIdle { pending?.complete(Unit); pending = null }
        assertEquals(2, calls.get())
        compose.onNodeWithText("Open agents").performClick()
        compose.onNodeWithText("codex: available").assertExists()
        assertEquals(3, calls.get())
    }

    @Test fun changingWorkspaceHostDoesNotReuseOldExecutableDetection() {
        installed += "opencode"
        mount()
        compose.onNodeWithText("opencode: available").assertExists()
        compose.runOnIdle { installed.clear(); hostIdentity = Any() }
        compose.onNodeWithText("opencode: unavailable").assertExists()
        assertEquals(2, calls.get())
    }
}
