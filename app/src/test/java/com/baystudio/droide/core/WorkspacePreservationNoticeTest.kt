package com.baystudio.droide.core

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Notice/flow/presentation seams only; no Activity, host, native editor, IO, or runtime startup. */
class WorkspacePreservationNoticeTest {
    @Test(timeout = 15_000)
    fun ordinaryFailureKeepsImmediateFirstNoticeAndExactTenSecondThrottle() {
        var nanos = 1L
        val notice = WorkspaceFailureNotice { nanos }
        val first = checkNotNull(notice.next())
        assertEquals(1L, first.id)
        assertEquals(WorkspaceFailureReason.GENERIC, first.reason)
        nanos = 10_000_000_000L
        assertNull(notice.next())
        nanos = 10_000_000_001L
        assertEquals(2L, notice.next()?.id)
    }

    @Test(timeout = 15_000)
    fun preservationReasonsBypassGenericThrottleWithoutResettingItsWindow() {
        var nanos = 1L
        val notice = WorkspaceFailureNotice { nanos }
        val generic = checkNotNull(notice.next())
        val retry = checkNotNull(notice.next(WorkspaceFailureReason.EDITOR_PRESERVATION_RETRY))
        val blocked = checkNotNull(notice.next(WorkspaceFailureReason.EDITOR_CAPTURE_BLOCKED))
        val anotherExplicitAttempt = checkNotNull(notice.next(WorkspaceFailureReason.EDITOR_PRESERVATION_RETRY))
        assertEquals(listOf(1L, 2L, 3L, 4L), listOf(generic.id, retry.id, blocked.id, anotherExplicitAttempt.id))
        assertNull(notice.next())
        nanos = 10_000_000_001L
        assertEquals(5L, notice.next()?.id)
    }

    @Test(timeout = 15_000)
    fun allReasonsHaveDistinctStaticMessagesWithTheApprovedAction() {
        val notice = WorkspaceFailureNotice { 1L }
        val generic = checkNotNull(notice.next())
        val retry = checkNotNull(notice.next(WorkspaceFailureReason.EDITOR_PRESERVATION_RETRY))
        val blocked = checkNotNull(notice.next(WorkspaceFailureReason.EDITOR_CAPTURE_BLOCKED))
        assertEquals("A background operation failed. Retry the operation if needed.", generic.message)
        assertEquals("Unsaved edits from the previous project remain in memory and have not been saved to recovery. Switch projects again to retry preserving them.", retry.message)
        assertEquals("The previous editor could not be fully captured. Pending unsaved edits remain retained, and project switching is blocked. Keep the app open.", blocked.message)
        assertEquals(3, setOf(generic.message, retry.message, blocked.message).size)
    }

    @Test(timeout = 15_000)
    fun stalePreservationCallbackCannotPublishOrReplaceTheCurrentNotice() {
        val flow = MutableStateFlow<WorkspaceFailureEvent?>(null)
        val current = WorkspaceFailureEvent(1, WorkspaceFailureReason.GENERIC)
        flow.publishWorkspaceFailure(current)
        val stale = WorkspaceFailureEvent(2, WorkspaceFailureReason.EDITOR_CAPTURE_BLOCKED) { false }
        flow.publishWorkspaceFailure(stale)
        assertSame(current, flow.value)
        flow.consumeWorkspaceFailure(stale)
        assertSame(current, flow.value)
    }

    @Test(timeout = 15_000)
    fun validPendingPreservationNoticeTakesPrecedenceOverGenericFailure() {
        val flow = MutableStateFlow<WorkspaceFailureEvent?>(null)
        val generic = WorkspaceFailureEvent(1, WorkspaceFailureReason.GENERIC)
        var preservationCurrent = true
        val retry = WorkspaceFailureEvent(2, WorkspaceFailureReason.EDITOR_PRESERVATION_RETRY) { preservationCurrent }
        flow.publishWorkspaceFailure(generic)
        flow.publishWorkspaceFailure(retry)
        assertSame(retry, flow.value)
        val laterGeneric = WorkspaceFailureEvent(3, WorkspaceFailureReason.GENERIC)
        flow.publishWorkspaceFailure(laterGeneric)
        assertSame(retry, flow.value)
        preservationCurrent = false
        flow.publishWorkspaceFailure(laterGeneric)
        assertSame(laterGeneric, flow.value)
    }

    @Test(timeout = 15_000)
    fun captureBlockedNoticeAlsoCannotBeReplacedByUnrelatedGenericFailure() {
        val flow = MutableStateFlow<WorkspaceFailureEvent?>(null)
        val blocked = WorkspaceFailureEvent(1, WorkspaceFailureReason.EDITOR_CAPTURE_BLOCKED)
        flow.publishWorkspaceFailure(blocked)
        flow.publishWorkspaceFailure(WorkspaceFailureEvent(2, WorkspaceFailureReason.GENERIC))
        assertSame(blocked, flow.value)
    }

    @Test(timeout = 15_000)
    fun resolvedNoticeIsRecheckedBeforeDisplayAndStillConsumedExactly() {
        val flow = MutableStateFlow<WorkspaceFailureEvent?>(null)
        var current = true
        val event = WorkspaceFailureEvent(1, WorkspaceFailureReason.EDITOR_PRESERVATION_RETRY) { current }
        flow.publishWorkspaceFailure(event)
        current = false
        val shown = mutableListOf<String>()
        var consumed: WorkspaceFailureEvent? = null
        event.deliverWorkspaceFailure(shown::add) {
            consumed = it
            flow.consumeWorkspaceFailure(it)
        }
        assertTrue(shown.isEmpty())
        assertSame(event, consumed)
        assertNull(flow.value)
    }

    @Test(timeout = 15_000)
    fun oldOrEqualIdEventsCannotConsumeANewerEvent() {
        val flow = MutableStateFlow<WorkspaceFailureEvent?>(null)
        val ownerCheck: () -> Boolean = { true }
        val newer = WorkspaceFailureEvent(7, WorkspaceFailureReason.EDITOR_CAPTURE_BLOCKED, ownerCheck)
        val equalFields = WorkspaceFailureEvent(7, WorkspaceFailureReason.EDITOR_CAPTURE_BLOCKED, ownerCheck)
        val stale = WorkspaceFailureEvent(6, WorkspaceFailureReason.EDITOR_PRESERVATION_RETRY) { false }
        flow.publishWorkspaceFailure(newer)
        flow.consumeWorkspaceFailure(equalFields)
        assertSame(newer, flow.value)
        stale.deliverWorkspaceFailure({ fail("Stale event must not be displayed") }, flow::consumeWorkspaceFailure)
        assertSame(newer, flow.value)
        flow.consumeWorkspaceFailure(newer)
        assertNull(flow.value)
    }

    @Test(timeout = 15_000)
    fun currentDeliveryUsesOnlyStaticMessageAndConsumesEvenIfPresentationThrows() {
        val flow = MutableStateFlow<WorkspaceFailureEvent?>(null)
        val event = WorkspaceFailureEvent(1, WorkspaceFailureReason.EDITOR_CAPTURE_BLOCKED)
        flow.publishWorkspaceFailure(event)
        val presentationFailure = IllegalStateException("fixture presentation failure")
        var shown: String? = null
        val failure = runCatching {
            event.deliverWorkspaceFailure({
                shown = it
                throw presentationFailure
            }, flow::consumeWorkspaceFailure)
        }.exceptionOrNull()
        assertSame(presentationFailure, failure)
        assertEquals(WorkspaceFailureReason.EDITOR_CAPTURE_BLOCKED.message, shown)
        assertNull(flow.value)
    }
}
