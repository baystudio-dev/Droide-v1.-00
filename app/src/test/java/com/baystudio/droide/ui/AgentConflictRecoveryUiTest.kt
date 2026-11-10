package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.*
import java.nio.file.Files
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp")
class AgentConflictRecoveryUiTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("conflict-ui-").toFile()
    private val source = root.resolve("source").apply { mkdir() }
    private val working = root.resolve("working").apply { mkdir() }
    private val owner = MirrorConflictRecovery(source, working)
    private val rescue = MirrorRetainedRecovery(source, working)
    private val node = MirrorMetadataAuthority.Node.file(WorkspaceSyncManifest.Entry("ab".repeat(32), 420))
    private val operation = MirrorMetadataAuthority.Intent("00000000-0000-0000-0000-000000000001", "file.kt", "replace",
        "00000000-0000-0000-0000-000000000001", MirrorMetadataAuthority.Phase.APPLIED, node, node)
    private val item = MirrorRetainedRecovery.Item(operation, MirrorRetainedRecovery.Role.ORIGINAL, AnchoredWorkspaceFiles.Kind.FILE, 8)
    private val observed = MirrorRecoverySnapshot.Observation(node, "identity", "parent", 8)
    private val versionReview = MirrorConflictRecovery.VersionReview(owner, 0, item, observed)
    private var acknowledgements = 0
    private var resolutions = 0
    private var failReload = false
    private var resolutionInterruption: String? = null
    private val resolutionGate = kotlinx.coroutines.CompletableDeferred<Unit>()
    private lateinit var controller: AgentRetainedRecoveryController
    private fun show() {
        val actions = object : AgentRetainedRecoveryActions {
            override val supportsConflictReview = true
            override val supportsRetentionReview = true
            override suspend fun reviewRetention(item: MirrorRetainedRecovery.Item) = MirrorRetainedRecovery.RetentionReview(
                item, 4, observed, mapOf("file.kt" to WorkspaceDocumentVersion(2, 3)), listOf("file.kt"), 1, 1, 0, true)

            override suspend fun list(cursor: MirrorRetainedRecovery.Cursor?): MirrorRetainedRecovery.Page {
                if (failReload && acknowledgements > 0) error("reload unavailable")
                return MirrorRetainedRecovery.Page(listOf(item), null, conflicts = if (resolutions == 0) listOf("file.kt") else emptyList())
            }
            override suspend fun inspect(item: MirrorRetainedRecovery.Item) = MirrorRetainedRecovery.Inspection(rescue, item,
                CanonicalFileSnapshot.Version("identity", 8, node.sha256, 420), "parent")
            override suspend fun copy(inspection: MirrorRetainedRecovery.Inspection, destination: String): Long = error("No copy authorized")
            override suspend fun inspectVersion(item: MirrorRetainedRecovery.Item) = versionReview
            override suspend fun acknowledge(review: MirrorConflictRecovery.VersionReview) { acknowledgements++ }
            override suspend fun inspectConflict(path: String) = MirrorConflictRecovery.Review(owner, 0, path, observed,
                observed.copy(identity = "other"), MirrorConflictRecovery.History("hash", 1, if (acknowledgements > 0) 0 else 1))
            override suspend fun resolveConflict(review: MirrorConflictRecovery.Review) {
                resolutions++
                if (resolutionInterruption != null) {
                    val attempt = MirrorConflictRecovery.ResolutionAttempt().also { review.lastResolutionAttempt = it }
                    attempt.attempted()
                    if (resolutionInterruption == "delayed-confirmed") {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            resolutionGate.await(); attempt.confirmed(5)
                        }
                        throw kotlinx.coroutines.CancellationException("closed after commit")
                    }
                    if (resolutionInterruption == "confirmed") {
                        attempt.confirmed(5)
                        throw kotlinx.coroutines.CancellationException("interrupted after commit")
                    }
                    error("commit outcome unknown")
                }
            }
        }
        compose.setContent { DroideTheme {
            val scope = rememberCoroutineScope()
            controller = remember { AgentRetainedRecoveryController(scope, actions) { true } }
            AgentRetainedRecoveryDialog(controller)
        } }
        compose.runOnIdle { controller.open() }
    }
    private fun reviewVersion() {
        compose.onNodeWithText("CLI version · 8 bytes").performScrollTo().performClick()
        compose.onNodeWithText("Review this version").performScrollTo().performClick()
    }
    @After fun cleanup() {
        resolutionGate.complete(Unit)
        if (::controller.isInitialized) compose.runOnIdle { controller.close() }
        assertTrue(PathSecurity.deleteTreeNoFollow(root))
    }
    @Test fun versionAcknowledgementAndResolutionRequireSeparateExplicitActions() {
        show()
        compose.onNodeWithText("Review conflict · file.kt").performClick()
        compose.onNodeWithText("Resolve matching saved files").assertIsNotEnabled()
        compose.onNodeWithText("Back").performClick()
        reviewVersion()
        compose.runOnIdle { assertEquals(0, acknowledgements); assertEquals(0, resolutions) }
        compose.onNodeWithText("Acknowledge reviewed version").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, acknowledgements); assertEquals(0, resolutions) }
        compose.onNodeWithText("Review conflict · file.kt").performScrollTo().performClick()
        compose.onNodeWithText("Resolve matching saved files").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, resolutions) }
    }
    @Test fun savedResolutionExplainsThatUnsavedChangesRemainSeparate() {
        acknowledgements = 1
        show()
        compose.onNodeWithText("Review conflict · file.kt").performClick()
        compose.onNodeWithText("Use matching saved files as the sync baseline. Unsaved editor changes are neither saved nor discarded. All retained versions are kept.").assertExists()
        compose.onNodeWithText("Resolve matching saved files").assertIsEnabled().performClick()
        compose.onNodeWithText("Matching saved files were resolved. Unsaved editor changes remain separate; all retained versions were kept.").assertExists()
        compose.runOnIdle { assertEquals(1, resolutions) }
    }

    @Test fun confirmedCommitCancellationReportsSavedStatusAndClearsStaleReview() {
        acknowledgements = 1; resolutionInterruption = "confirmed"
        show()
        compose.onNodeWithText("Review conflict · file.kt").performClick()
        compose.onNodeWithText("Resolve matching saved files").performScrollTo().performClick()
        compose.onNodeWithText("Saved-file status was committed before interruption. Unsaved edits and retained versions were kept. Refresh recovery.").assertExists()
        compose.runOnIdle {
            assertNull(controller.state.conflictReview)
            controller.resolveConflict()
            assertEquals(1, resolutions)
        }
    }

    @Test fun unknownCommitOutcomeRequiresRefreshWithoutAutomaticRetry() {
        acknowledgements = 1; resolutionInterruption = "unknown"
        show()
        compose.onNodeWithText("Review conflict · file.kt").performClick()
        compose.onNodeWithText("Resolve matching saved files").performScrollTo().performClick()
        compose.onNodeWithText("Saved-file status may have changed. Unsaved edits and retained versions were kept. Refresh before retrying.").assertExists()
        compose.runOnIdle {
            assertNull(controller.state.conflictReview)
            controller.resolveConflict()
            assertEquals(1, resolutions)
        }
    }

    @Test fun closingAndReopeningDoesNotReceiveAnOldResolutionNotice() {
        acknowledgements = 1; resolutionInterruption = "delayed-confirmed"
        show()
        compose.onNodeWithText("Review conflict · file.kt").performClick()
        compose.onNodeWithText("Resolve matching saved files").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, resolutions)
            controller.close()
            controller.open()
            resolutionGate.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(controller.state.open)
            assertNull(controller.state.notice)
            assertNull(controller.state.conflictReview)
            assertFalse(controller.state.busy)
        }
    }

    @Test fun retentionCheckIsVisibleReadOnlyAndBackClearsItsSnapshot() {
        show()
        compose.onNodeWithText("CLI version · 8 bytes").performScrollTo().performClick()
        compose.onNodeWithText("Check why this version is kept").performScrollTo().performClick()
        compose.onNodeWithText("Why this version is kept").assertExists()
        compose.onNodeWithText("• Pending editor writes are not covered by a held release barrier.").assertExists()
        compose.onNodeWithText("Acknowledge reviewed version").assertDoesNotExist()
        compose.onNodeWithText("Save copy").assertDoesNotExist()
        compose.runOnIdle {
            assertNotNull(controller.state.retentionReview)
            assertEquals(0, acknowledgements); assertEquals(0, resolutions)
        }
        compose.onNodeWithText("Back").performClick()
        compose.runOnIdle { assertNull(controller.state.retentionReview) }
    }

    @Test fun reloadFailureDoesNotMisreportAnAlreadySavedReview() {
        failReload = true
        show(); reviewVersion()
        compose.onNodeWithText("Acknowledge reviewed version").performScrollTo().performClick()
        compose.onNodeWithText("Recovery status was saved. Refresh to reload the list.").assertExists()
        compose.onNodeWithText("Version review saved. Retained bytes were kept; later changes will need review again.").assertExists()
        compose.runOnIdle { assertEquals(1, acknowledgements); assertNull(controller.state.versionReview) }
    }
}
