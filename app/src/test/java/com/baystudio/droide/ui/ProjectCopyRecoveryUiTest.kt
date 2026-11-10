package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.size
import com.baystudio.droide.core.ProjectCopyEvidence
import com.baystudio.droide.core.ProjectCopyJournal
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Source-only Compose regressions, not a rendered/physical-device validation claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
class ProjectCopyRecoveryUiTest {
    @get:Rule val compose = createComposeRule()
    private val record = ProjectCopyJournal.Record(ProjectCopyJournal.Intent(
        id = "00000000-0000-0000-0000-000000000001", canonicalRoot = "/project-a", rootIdentity = "root-a",
        owner = ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.AGENT_MEDIA, "session-a", "run-a", "source-a"),
        destination = "assets/image.png", createdAt = 1L), ProjectCopyJournal.Status.UNKNOWN, null, null, "chain-a", false)
    private val inspection = ProjectCopyJournal.Inspection("original-token", record,
        ProjectCopyEvidence.Current((0..30).map { "parent-$it" to "identity-$it" },
            ProjectCopyEvidence.Identity("file-a", 12, "2026-10-15T00:00:00Z", "file"), "ab".repeat(32)), 300_000L)

    @Test fun recoveryEntryLivesOnlyInTheOpenedMenuAndHasOneAccessibleTarget() {
        var expanded by mutableStateOf(false)
        var opened = 0
        compose.setContent { DroideTheme { Box(Modifier.size(360.dp, 600.dp)) {
            TextButton(onClick = { expanded = true }) { Text("Existing options") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ProjectCopyRecoveryMenuItem(imports = true, enabled = true) { opened++; expanded = false }
            }
        } } }
        compose.onNodeWithText("Import recovery").assertDoesNotExist()
        compose.onNodeWithText("Existing options").performClick()
        compose.onAllNodesWithText("Import recovery").assertCountEquals(1)
        val entry = compose.onNodeWithText("Import recovery")
        assertTrue(entry.getUnclippedBoundsInRoot().size.height.value >= 48f)
        entry.performClick()
        assertEquals(1, opened)
        compose.onNodeWithText("Import recovery").assertDoesNotExist()
    }

    @Test fun metadataListOffersInspectionWithoutAnyConfirmation() {
        val selected = mutableListOf<String>()
        var confirmed = 0
        mount(ProjectCopyRecoveryUiState(open = true, records = listOf(record)), onInspect = selected::add,
            onConfirm = { confirmed++ })
        compose.onNodeWithText("Allow a new copy").assertDoesNotExist()
        compose.onNodeWithTag("inspect-copy:${record.intent.id}").performScrollTo().performClick()
        assertEquals(listOf(record.intent.id), selected)
        assertEquals(0, confirmed)
    }

    @Test fun retainedCommittedListShowsItsRecoveryLocationWithoutOfferingANewCopy() {
        val retained = retainedRecord(ProjectCopyJournal.Status.COMMITTED)
        val selected = mutableListOf<String>()
        var confirmed = 0
        mount(ProjectCopyRecoveryUiState(open = true, records = listOf(retained)),
            onInspect = selected::add, onConfirm = { confirmed++ })
        compose.onNodeWithText("Recovery location: ${retained.intent.plannedStagePath}").assertExists()
        compose.onNodeWithText("Previous outcome: COMMITTED", substring = true).assertExists()
        compose.onNodeWithText("Allow a new copy").assertDoesNotExist()
        compose.onNodeWithTag("inspect-copy:${retained.intent.id}").performScrollTo().performClick()
        assertEquals(listOf(retained.intent.id), selected)
        assertEquals(0, confirmed)
    }

    @Test fun retainedCommittedInspectionKeepsTheOutcomeAndOffersReadOnlyEvidence() {
        val retained = retainedRecord(ProjectCopyJournal.Status.COMMITTED)
        val stage = ProjectCopyEvidence.StageInspection(relativePath = retained.intent.plannedStagePath,
            status = ProjectCopyEvidence.StageStatus.VERIFIED, bytes = 12L, identity = "retained-inode",
            sha256 = "ab".repeat(32), detail = null)
        val retainedInspection = inspection.copy(record = retained, stage = stage)
        var confirmed = 0
        mount(ProjectCopyRecoveryUiState(open = true, records = listOf(retained), inspection = retainedInspection),
            height = 260, onConfirm = { confirmed++ })
        compose.onNodeWithText("Previous outcome: COMMITTED", substring = true).assertExists()
        compose.onNodeWithText("Retained copy").assertExists()
        compose.onNodeWithText("Matches the recorded copy at inspection", substring = true).assertExists()
        compose.onNodeWithText("Read-only inspection.", substring = true).assertExists()
        compose.onNodeWithText("Allow a new copy").assertDoesNotExist()
        listOf("Back to transactions", "Close").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        assertEquals(0, confirmed)
    }

    @Test fun preparedCandidateRemainsUnverifiedAndCannotEnableConfirmation() {
        val retained = retainedRecord(ProjectCopyJournal.Status.PREPARED)
        val stage = ProjectCopyEvidence.StageInspection(relativePath = retained.intent.plannedStagePath,
            status = ProjectCopyEvidence.StageStatus.UNVERIFIED, bytes = 12L, identity = "candidate-inode",
            sha256 = null, detail = null)
        mount(ProjectCopyRecoveryUiState(open = true, records = listOf(retained),
            inspection = inspection.copy(record = retained, stage = stage)))
        compose.onNodeWithText("Previous outcome: PREPARED", substring = true).assertExists()
        compose.onNodeWithText("File found; contents are not verified", substring = true).assertExists()
        compose.onNodeWithText("Recovery location: ${retained.intent.plannedStagePath}", substring = true).assertExists()
        compose.onNodeWithText("Allow a new copy").assertDoesNotExist()
        compose.onNodeWithText("Allow a new import").assertDoesNotExist()
    }

    @Test fun unsafeUnknownInspectionRequiresLocationResolutionAndOffersNoConfirmation() {
        val retained = retainedRecord(ProjectCopyJournal.Status.UNKNOWN)
        val stage = ProjectCopyEvidence.StageInspection(relativePath = retained.intent.plannedStagePath,
            status = ProjectCopyEvidence.StageStatus.UNSAFE, bytes = null, identity = null,
            sha256 = null, detail = "Recovery parent changed")
        var confirmed = 0
        mount(ProjectCopyRecoveryUiState(open = true, records = listOf(retained),
            inspection = inspection.copy(record = retained, stage = stage)), onConfirm = { confirmed++ })
        compose.onNodeWithText("Previous outcome: UNKNOWN", substring = true).assertExists()
        compose.onNodeWithText("Location could not be safely checked", substring = true).assertExists()
        compose.onNodeWithText("Resolve the recovery location before allowing a new copy.").assertExists()
        compose.onNodeWithText("Allow a new copy").assertDoesNotExist()
        compose.onNodeWithText("Allow a new import").assertDoesNotExist()
        compose.onNodeWithText("Close").assertIsEnabled()
        assertEquals(0, confirmed)
    }

    @Test fun evidenceAndPriorUnknownAreSeparateAndFooterStaysVisibleInShortViewport() {
        var confirmed = 0
        mount(ProjectCopyRecoveryUiState(open = true, records = listOf(record), inspection = inspection),
            height = 260, onConfirm = { confirmed++ })
        compose.onNodeWithText("Previous outcome: UNKNOWN", substring = true).assertExists()
        compose.onNodeWithText("Current destination evidence").assertExists()
        assertEquals(0, confirmed)
        listOf("Allow a new copy", "Back to transactions", "Close").forEach { label ->
            val target = compose.onNodeWithText(label)
            target.assertIsDisplayed()
            val bounds = target.getUnclippedBoundsInRoot()
            assertTrue("$label must remain inside short viewport", bounds.top.value >= 0f && bounds.bottom.value <= 260f)
            assertTrue("$label needs a 48dp target", bounds.size.height.value >= 48f)
        }
        compose.onNodeWithText("Allow a new copy").performClick()
        assertEquals(1, confirmed)
    }

    @Test fun inFlightConfirmationDisablesRepeatAndKeepsCloseAvailable() {
        mount(ProjectCopyRecoveryUiState(open = true, working = true, records = listOf(record), inspection = inspection))
        compose.onNodeWithText("Allow a new copy").assertIsNotEnabled()
        compose.onNodeWithText("Back to transactions").assertIsNotEnabled()
        compose.onNodeWithText("Close").assertIsEnabled()
    }

    @Test fun journalBlockIsVisibleAndDisablesInspection() {
        mount(ProjectCopyRecoveryUiState(open = true, records = listOf(record), blockingProblem = "Unreadable recovery journal"))
        compose.onNodeWithText("Recovery is blocked: Unreadable recovery journal").assertExists()
        compose.onNodeWithTag("inspect-copy:${record.intent.id}").assertIsNotEnabled()
        compose.onNodeWithText("No uncertain copies in this conversation.").assertDoesNotExist()
    }

    @Test fun importConfirmationUsesItsOwnActionLabelAndNeverClaimsSuccessOfTheOldCopy() {
        mount(ProjectCopyRecoveryUiState(open = true, records = listOf(record), inspection = inspection), imports = true)
        compose.onNodeWithText("Allow a new import").assertIsDisplayed()
        compose.onNodeWithText("Allow a new copy").assertDoesNotExist()
        compose.onNodeWithText("It does not copy, retry, overwrite, delete, or mark the old transaction successful.", substring = true).assertExists()
    }

    @Test fun changingConversationClosesOldCapabilityAndNeverScansTheReplacementAutomatically() {
        var session by mutableStateOf("session-a")
        var controller: ProjectCopyRecoveryController? = null
        var opens = 0
        var closes = 0
        var confirmations = 0
        val instance = Any()
        compose.setContent { DroideTheme {
            val owned = rememberProjectCopyRecoveryController(instance, "/project-a",
                ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, session), enabled = true,
                actions = ProjectCopyRecoveryActions {
                    opens++
                    object : ProjectCopyRecoveryAccess {
                        override suspend fun list() = ProjectCopyJournal.Recovery(listOf(record), null)
                        override suspend fun inspect(transactionId: String) = inspection
                        override suspend fun acknowledge(inspection: ProjectCopyJournal.Inspection) { confirmations++ }
                        override fun close() { closes++ }
                    }
                })
            SideEffect { controller = owned }
            Text("Conversation $session")
        } }
        lateinit var old: ProjectCopyRecoveryController
        compose.runOnIdle { old = controller!!; old.open() }
        compose.waitForIdle()
        compose.runOnIdle { old.inspect(record.intent.id) }
        compose.waitForIdle()
        compose.runOnIdle { assertSame(inspection, old.state.inspection) }
        compose.runOnIdle { session = "session-b" }
        compose.waitForIdle()
        compose.runOnIdle {
            old.confirm()
            assertFalse(old.state.open)
            assertNull(old.state.inspection)
            assertFalse(controller!!.state.open)
        }
        assertEquals(1, opens)
        assertEquals(1, closes)
        assertEquals(0, confirmations)
    }

    private fun retainedRecord(status: ProjectCopyJournal.Status): ProjectCopyJournal.Record {
        val id = if (status == ProjectCopyJournal.Status.PREPARED) "00000000-0000-0000-0000-000000000002"
            else "00000000-0000-0000-0000-000000000003"
        return record.copy(intent = record.intent.copy(version = 2, id = id,
            plannedStagePath = "assets/.droide-tmp-$id"), status = status,
            stagePresence = ProjectCopyEvidence.StagePresence.PRESENT)
    }

    private fun mount(state: ProjectCopyRecoveryUiState, imports: Boolean = false, height: Int = 580,
        onInspect: (String) -> Unit = {}, onConfirm: () -> Unit = {}) {
        compose.setContent { DroideTheme { Box(Modifier.size(360.dp, height.dp)) {
            ProjectCopyRecoveryContent(state, imports, "/project-a", if (imports) null else "session-a", height.dp,
                onInspect, onConfirm, {}, {}, {})
        } } }
    }
}
