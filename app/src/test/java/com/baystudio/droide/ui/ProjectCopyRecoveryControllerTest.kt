package com.baystudio.droide.ui

import com.baystudio.droide.core.ProjectCopyEvidence
import com.baystudio.droide.core.ProjectCopyJournal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Authored regression sources only. No runtime, compiler, test or device execution is claimed. */
class ProjectCopyRecoveryControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var available = true
    private var opens = 0
    private val owner = ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, "session-a")
    private val record = record()
    private val access = FakeAccess(record)
    private val controller = controller { access }

    @After fun cleanup() { controller.dispose(); scope.cancel() }

    private fun controller(open: suspend () -> ProjectCopyRecoveryAccess) = ProjectCopyRecoveryController(
        owner, scope, { available }, { ProjectCopyRecoveryActions { opens++; open() } })

    @Test fun constructionAndRenderingStateDoNotOpenOrScan() {
        assertFalse(controller.state.open)
        assertEquals(0, opens)
        assertEquals(0, access.lists)
        controller.close()
        assertEquals(0, opens)
    }

    @Test fun explicitOpenOnlyListsMetadataAndFiltersHistoricalNonUnknownRows() {
        access.recovery = ProjectCopyJournal.Recovery(listOf(record,
            record.copy(status = ProjectCopyJournal.Status.COMMITTED),
            record.copy(status = ProjectCopyJournal.Status.PREPARED)), null)
        controller.open()
        assertEquals(1, opens)
        assertEquals(1, access.lists)
        assertEquals(listOf(record), controller.state.records)
        assertEquals(emptyList<String>(), access.inspected)
        assertEquals(emptyList<ProjectCopyJournal.Inspection>(), access.acknowledged)
        controller.confirm()
        assertTrue(access.acknowledged.isEmpty())
    }

    @Test fun inspectSelectsOneExactRowAndDoesNotConfirmIt() {
        controller.open()
        controller.inspect("not-listed")
        assertTrue(access.inspected.isEmpty())
        controller.inspect(record.intent.id)
        assertEquals(listOf(record.intent.id), access.inspected)
        assertSame(access.inspection, controller.state.inspection)
        assertTrue(access.acknowledged.isEmpty())
    }

    @Test fun retainedPreparedAndCommittedRowsRemainVisibleWithoutChangingTheirStatus() {
        val prepared = retainedRecord(ProjectCopyJournal.Status.PREPARED)
        val committed = retainedRecord(ProjectCopyJournal.Status.COMMITTED)
        val absent = committed.copy(intent = committed.intent.copy(id = "00000000-0000-0000-0000-000000000004",
            plannedStagePath = "assets/.droide-tmp-00000000-0000-0000-0000-000000000004"),
            stagePresence = ProjectCopyEvidence.StagePresence.ABSENT)
        val unverified = committed.copy(intent = committed.intent.copy(id = "00000000-0000-0000-0000-000000000005",
            plannedStagePath = "assets/.droide-tmp-00000000-0000-0000-0000-000000000005"),
            stagePresence = ProjectCopyEvidence.StagePresence.UNVERIFIABLE)
        access.recovery = ProjectCopyJournal.Recovery(listOf(record, prepared, committed, absent, unverified), null)
        controller.open()
        assertEquals(listOf(record, prepared, committed, unverified), controller.state.records)
        assertTrue(access.inspected.isEmpty())
        assertTrue(access.acknowledged.isEmpty())
    }

    @Test fun retainedPreparedAndCommittedInspectionsCannotGrantANewCopy() {
        for (status in listOf(ProjectCopyJournal.Status.PREPARED, ProjectCopyJournal.Status.COMMITTED)) {
            val retained = retainedRecord(status)
            val retainedAccess = FakeAccess(retained)
            val inspecting = controller { retainedAccess }
            try {
                inspecting.open(); inspecting.inspect(retained.intent.id)
                assertSame(retainedAccess.inspection, inspecting.state.inspection)
                assertEquals(status, inspecting.state.inspection!!.record.status)
                inspecting.confirm(); inspecting.confirm()
                assertTrue(retainedAccess.acknowledged.isEmpty())
                assertNull(inspecting.state.grantedTransaction)
                assertNull(inspecting.state.notice)
            } finally { inspecting.dispose() }
        }
    }

    @Test fun unsafeRetainedLocationCannotGrantANewCopyEvenWhenOutcomeIsUnknown() {
        val unsafeRecord = retainedRecord(ProjectCopyJournal.Status.UNKNOWN)
        val unsafeAccess = FakeAccess(unsafeRecord)
        unsafeAccess.inspection = unsafeAccess.inspection.copy(stage = ProjectCopyEvidence.StageInspection(
            relativePath = unsafeRecord.intent.plannedStagePath, status = ProjectCopyEvidence.StageStatus.UNSAFE,
            bytes = null, identity = null, sha256 = null, detail = "Recovery parent changed"))
        val inspecting = controller { unsafeAccess }
        try {
            inspecting.open(); inspecting.inspect(unsafeRecord.intent.id)
            assertSame(unsafeAccess.inspection, inspecting.state.inspection)
            inspecting.confirm(); inspecting.confirm()
            assertSame(unsafeAccess.inspection, inspecting.state.inspection)
            assertTrue(unsafeAccess.acknowledged.isEmpty())
            assertNull(inspecting.state.grantedTransaction)
            assertNull(inspecting.state.notice)
        } finally { inspecting.dispose() }
    }

    @Test fun statusChangeDuringRetainedInspectionRequiresRefresh() {
        val retained = retainedRecord(ProjectCopyJournal.Status.PREPARED)
        val retainedAccess = FakeAccess(retained)
        retainedAccess.inspection = retainedAccess.inspection.copy(
            record = retained.copy(status = ProjectCopyJournal.Status.COMMITTED))
        val inspecting = controller { retainedAccess }
        try {
            inspecting.open(); inspecting.inspect(retained.intent.id); inspecting.confirm()
            assertNull(inspecting.state.inspection)
            assertTrue(inspecting.state.error!!.contains("Refresh"))
            assertTrue(retainedAccess.acknowledged.isEmpty())
        } finally { inspecting.dispose() }
    }

    @Test fun confirmationPassesOriginalInspectionOnceAndRetainsUnknownHistory() = runBlocking {
        val release = CompletableDeferred<Unit>()
        access.onAcknowledge = { release.await() }
        controller.open(); controller.inspect(record.intent.id)
        val original = controller.state.inspection
        controller.confirm(); controller.confirm()
        assertEquals(1, access.acknowledged.size)
        assertSame(original, access.acknowledged.single())
        assertTrue(controller.state.working)
        release.complete(Unit)
        assertFalse(controller.state.working)
        assertNull(controller.state.inspection)
        assertEquals(ProjectCopyJournal.Status.UNKNOWN, controller.state.records.single().status)
        assertEquals(record.intent.id, controller.state.grantedTransaction)
        assertTrue(controller.state.notice!!.contains("NEW manual request"))
        controller.confirm()
        assertEquals(1, access.acknowledged.size)
    }

    @Test fun changedEvidenceRejectsAndRequiresAnotherInspection() {
        access.onAcknowledge = { error("Recovery evidence changed; inspect again") }
        controller.open(); controller.inspect(record.intent.id); controller.confirm()
        assertNull(controller.state.inspection)
        assertNull(controller.state.grantedTransaction)
        assertNull(controller.state.notice)
        assertTrue(controller.state.error!!.contains("evidence changed"))
        controller.confirm()
        assertEquals(1, access.acknowledged.size)
    }

    @Test fun expiredConfirmationNeverShowsSuccess() {
        access.onAcknowledge = { error("Recovery inspection expired; inspect again") }
        controller.open(); controller.inspect(record.intent.id); controller.confirm()
        assertNull(controller.state.inspection)
        assertNull(controller.state.notice)
        assertTrue(controller.state.error!!.contains("expired"))
    }

    @Test fun foreignSessionListCannotAppearInTheOriginalConversation() {
        access.recovery = ProjectCopyJournal.Recovery(listOf(record("session-b")), null)
        controller.open()
        assertTrue(controller.state.records.isEmpty())
        assertTrue(controller.state.error!!.contains("different project or conversation"))
    }

    @Test fun changedTransactionOrProjectInspectionCannotEnableConfirmation() {
        val changed = record.copy(intent = record.intent.copy(canonicalRoot = "/project-b"))
        access.inspection = access.inspection.copy(record = changed)
        controller.open(); controller.inspect(record.intent.id); controller.confirm()
        assertNull(controller.state.inspection)
        assertTrue(controller.state.error!!.contains("changed"))
        assertTrue(access.acknowledged.isEmpty())
    }

    @Test fun listEvidenceChangeRequiresRefreshRatherThanAdoptingNewEvidenceSilently() {
        access.inspection = access.inspection.copy(record = record.copy(evidenceHash = "different-chain"))
        controller.open(); controller.inspect(record.intent.id)
        assertNull(controller.state.inspection)
        assertTrue(controller.state.error!!.contains("Refresh"))
    }

    @Test fun journalProblemAndActiveOrDamagedRecordsAreNeverInspectable() {
        access.recovery = ProjectCopyJournal.Recovery(listOf(record), "Journal could not be verified")
        controller.open(); controller.inspect(record.intent.id)
        assertEquals("Journal could not be verified", controller.state.blockingProblem)
        assertTrue(access.inspected.isEmpty())
        controller.close()
        access.recovery = ProjectCopyJournal.Recovery(listOf(record.copy(active = true)), null)
        controller.open(); controller.inspect(record.intent.id)
        assertTrue(access.inspected.isEmpty())
        controller.close()
        access.recovery = ProjectCopyJournal.Recovery(listOf(record.copy(problem = "Broken receipt chain")), null)
        controller.open(); controller.inspect(record.intent.id)
        assertTrue(access.inspected.isEmpty())
    }

    @Test fun losingIdleAdmissionDisablesOldConfirmationImmediately() {
        controller.open(); controller.inspect(record.intent.id)
        available = false
        controller.confirm()
        assertTrue(access.acknowledged.isEmpty())
        controller.close()
        assertTrue(access.closed)
        assertNull(controller.state.inspection)
        controller.open()
        assertFalse(controller.state.open)
    }

    @Test fun closeDiscardsLateInspectionEvenWhenUnderlyingReadIgnoresCancellation() = runBlocking {
        val release = CompletableDeferred<Unit>()
        access.onInspect = { withContext(NonCancellable) { release.await() }; access.inspection }
        controller.open(); controller.inspect(record.intent.id)
        controller.close()
        release.complete(Unit)
        assertFalse(controller.state.open)
        assertNull(controller.state.inspection)
        assertNull(controller.state.error)
        assertTrue(access.closed)
        assertTrue(access.acknowledged.isEmpty())
    }

    @Test fun disposeDuringOpeningClosesTheLateCapabilityAndDoesNotScan() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val delayed = controller { withContext(NonCancellable) { release.await() }; access }
        delayed.open(); delayed.dispose()
        release.complete(Unit)
        assertTrue(access.closed)
        assertEquals(0, access.lists)
        assertFalse(delayed.state.open)
    }

    @Test fun refreshClosesOldAccessAndDropsOldEvidence() {
        val second = FakeAccess(record)
        var next: ProjectCopyRecoveryAccess = access
        val refreshing = controller { next }
        refreshing.open(); refreshing.inspect(record.intent.id)
        next = second
        refreshing.open()
        assertTrue(access.closed)
        assertEquals(1, second.lists)
        assertNull(refreshing.state.inspection)
        assertTrue(second.acknowledged.isEmpty())
        refreshing.dispose()
    }

    @Test fun closeAfterConfirmationDoesNotPretendTheGrantWasUndoneOrRetryAnything() {
        controller.open(); controller.inspect(record.intent.id); controller.confirm()
        controller.close()
        assertEquals(1, access.acknowledged.size)
        assertFalse(controller.state.open)
        assertNull(controller.state.notice)
        assertNull(controller.state.inspection)
    }

    @Test fun absentEvidenceAndExistingBytesAreDescribedAsCurrentObservations() {
        val absent = projectCopyEvidenceText(ProjectCopyEvidence.Current(listOf("" to "root-a"), null, null))
        assertTrue(absent.contains("absent at inspection"))
        val present = projectCopyEvidenceText(ProjectCopyEvidence.Current(listOf("" to "root-a"),
            ProjectCopyEvidence.Identity("file-a", 12, "2026-10-15T00:00:00Z", "file"), "ab".repeat(32)))
        assertTrue(present.contains("Bytes: 12"))
        assertTrue(present.contains("SHA-256:"))
        assertFalse(present.contains("COMMITTED"))
        assertFalse(present.contains("succeeded"))
    }

    @Test fun retainedEvidenceLabelsNeverInferPublicationOrHideVerificationLimits() {
        val labels = mapOf(
            ProjectCopyEvidence.StageStatus.LEGACY_UNAVAILABLE to "Location was not recorded",
            ProjectCopyEvidence.StageStatus.ABSENT to "No file found at inspection",
            ProjectCopyEvidence.StageStatus.UNVERIFIED to "contents are not verified",
            ProjectCopyEvidence.StageStatus.VERIFIED to "Matches the recorded copy at inspection",
            ProjectCopyEvidence.StageStatus.MISMATCH to "differs from the recorded copy",
            ProjectCopyEvidence.StageStatus.TOO_LARGE to "exceeds the content-check limit",
            ProjectCopyEvidence.StageStatus.UNSAFE to "could not be safely checked",
        )
        for ((status, label) in labels) {
            val unavailable = status == ProjectCopyEvidence.StageStatus.LEGACY_UNAVAILABLE || status == ProjectCopyEvidence.StageStatus.ABSENT
            val stage = ProjectCopyEvidence.StageInspection(
                relativePath = if (status == ProjectCopyEvidence.StageStatus.LEGACY_UNAVAILABLE) null
                    else "assets/.droide-tmp-00000000-0000-0000-0000-000000000002",
                status = status, bytes = if (unavailable) null else 17L,
                identity = if (unavailable) null else "retained-inode", sha256 = null, detail = "Inspection snapshot")
            val text = projectCopyStageEvidenceText(stage)
            stage.relativePath?.let { assertTrue(text.contains(it)) }
            assertTrue(text.contains(label))
            if (!unavailable) assertTrue(text.contains("Bytes: 17"))
            assertTrue(text.contains("Inspection snapshot"))
            assertFalse(text.contains("COMMITTED"))
            assertFalse(text.contains("succeeded"))
        }
    }

    private fun retainedRecord(status: ProjectCopyJournal.Status): ProjectCopyJournal.Record {
        val id = if (status == ProjectCopyJournal.Status.PREPARED) "00000000-0000-0000-0000-000000000002"
            else "00000000-0000-0000-0000-000000000003"
        return record.copy(intent = record.intent.copy(version = 2, id = id,
            plannedStagePath = "assets/.droide-tmp-$id"), status = status,
            stagePresence = ProjectCopyEvidence.StagePresence.PRESENT)
    }

    private fun record(sessionId: String = "session-a") = ProjectCopyJournal.Record(
        ProjectCopyJournal.Intent(id = "00000000-0000-0000-0000-000000000001", canonicalRoot = "/project-a", rootIdentity = "root-a",
            owner = ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.AGENT_MEDIA, sessionId, "run-a", "source-a"),
            destination = "assets/image.png", createdAt = 1L),
        ProjectCopyJournal.Status.UNKNOWN, null, null, "chain-a", false)

    private class FakeAccess(record: ProjectCopyJournal.Record) : ProjectCopyRecoveryAccess {
        var lists = 0
        var closed = false
        val inspected = mutableListOf<String>()
        val acknowledged = mutableListOf<ProjectCopyJournal.Inspection>()
        var recovery = ProjectCopyJournal.Recovery(listOf(record), null)
        var inspection = ProjectCopyJournal.Inspection("opaque-evidence-token", record,
            ProjectCopyEvidence.Current(listOf("" to "root-a"), null, null), 300_000L)
        var onInspect: suspend () -> ProjectCopyJournal.Inspection = { inspection }
        var onAcknowledge: suspend () -> Unit = {}
        override suspend fun list(): ProjectCopyJournal.Recovery { lists++; return recovery }
        override suspend fun inspect(transactionId: String): ProjectCopyJournal.Inspection {
            inspected += transactionId
            return onInspect()
        }
        override suspend fun acknowledge(inspection: ProjectCopyJournal.Inspection) {
            acknowledged += inspection
            onAcknowledge()
        }
        override fun close() { closed = true }
    }
}
