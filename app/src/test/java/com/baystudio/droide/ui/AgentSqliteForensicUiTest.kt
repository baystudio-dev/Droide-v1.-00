package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp")
class AgentSqliteForensicUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var controller: AgentRetainedRecoveryController
    private var candidateRequests = 0
    private var captureRequests = 0
    private var pending: CompletableDeferred<Unit>? = null
    private fun show(supported: Boolean = true) {
        val actions = object : AgentRetainedRecoveryActions {
            override val supportsSqliteEvidence = supported
            override suspend fun list(cursor: MirrorRetainedRecovery.Cursor?): MirrorRetainedRecovery.Page = error("Unresolved rollback journal")
            override suspend fun inspect(item: MirrorRetainedRecovery.Item): MirrorRetainedRecovery.Inspection = error("No selection")
            override suspend fun copy(inspection: MirrorRetainedRecovery.Inspection, destination: String): Long = error("No copy")
            override suspend fun captureSqliteEvidence(): String { captureRequests++; return "Raw evidence preserved" }
            override suspend fun buildSqliteCandidate(): String {
                candidateRequests++; pending?.await()
                return "Unverified forensic bytes only. Activation remains blocked."
            }
        }
        compose.setContent { DroideTheme {
            val scope=rememberCoroutineScope()
            controller=remember { AgentRetainedRecoveryController(scope,actions) { true } }
            AgentRetainedRecoveryDialog(controller)
        } }
        compose.runOnIdle { controller.open() }
    }
    @After fun close() { if(::controller.isInitialized) compose.runOnIdle { controller.close() } }

    @Test fun explicitCandidateButtonDoesNotRunOnOpenOrSilentlyResumeWorkspace() {
        show()
        compose.runOnIdle { assertEquals(0,candidateRequests); assertEquals(0,captureRequests) }
        compose.onNodeWithText("Build unverified forensic candidate").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1,candidateRequests); assertEquals(0,captureRequests) }
        compose.onNodeWithText("Unverified forensic bytes only. Activation remains blocked.").assertExists()
    }

    @Test fun unsupportedRouteCannotInvokeForensicAction() {
        show(false)
        compose.onNodeWithText("Build unverified forensic candidate").assertDoesNotExist()
        compose.runOnIdle { controller.buildSqliteCandidate(); assertEquals(0,candidateRequests) }
    }

    @Test fun closingDialogDiscardsLateCandidateNotice() {
        pending=CompletableDeferred()
        show()
        compose.onNodeWithText("Build unverified forensic candidate").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1,candidateRequests); controller.close(); pending!!.complete(Unit) }
        compose.runOnIdle { assertFalse(controller.state.open); assertNull(controller.state.notice) }
    }
}
