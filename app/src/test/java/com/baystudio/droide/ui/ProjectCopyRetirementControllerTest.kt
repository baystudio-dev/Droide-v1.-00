package com.baystudio.droide.ui

import com.baystudio.droide.core.ProjectCopyJournal
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProjectCopyRetirementControllerTest {
    @Test fun openingThePanelDoesNotInspectOrCompleteHistory() = fixture { f ->
        f.controller.open()
        assertTrue(f.controller.state.supportsHistoryRecovery)
        assertEquals(1, f.access.lists); assertEquals(0, f.access.inspects); assertEquals(0, f.access.finishes)
        f.controller.finishHistory()
        assertEquals(0, f.access.finishes)
    }

    @Test fun inspectAndConfirmAreSeparateAndRefreshOnlyAfterSuccess() = fixture { f ->
        f.controller.open(); f.controller.inspectHistory()
        assertSame(f.access.inspection, f.controller.state.historyInspection)
        assertEquals(0, f.access.finishes)
        f.controller.finishHistory()
        assertEquals(1, f.access.finishes); assertEquals(2, f.access.lists)
        assertNull(f.controller.state.historyInspection)
        assertNull(f.controller.state.blockingProblem)
        assertNotNull(f.controller.state.notice)
    }

    @Test fun repeatedConfirmationCannotStartParallelRecovery() = fixture { f ->
        val release = CompletableDeferred<Unit>()
        f.access.onFinish = { release.await() }
        f.controller.open(); f.controller.inspectHistory(); f.controller.finishHistory(); f.controller.finishHistory()
        assertEquals(1, f.access.finishes); assertTrue(f.controller.state.working)
        release.complete(Unit)
        assertFalse(f.controller.state.working)
    }

    @Test fun closingTheViewPreventsLateCompletionFromChangingItsState() = fixture { f ->
        val release = CompletableDeferred<Unit>()
        f.access.onFinish = { withContext(NonCancellable) { release.await() } }
        f.controller.open(); f.controller.inspectHistory(); f.controller.finishHistory(); f.controller.close()
        release.complete(Unit)
        assertFalse(f.controller.state.open); assertNull(f.controller.state.notice)
        assertTrue(f.access.closed)
    }

    @Test fun failureRequiresNewInspectionAndNeverDisplaysSuccess() = fixture { f ->
        f.access.onFinish = { error("History evidence changed") }
        f.controller.open(); f.controller.inspectHistory(); f.controller.finishHistory()
        assertNull(f.controller.state.historyInspection)
        assertNull(f.controller.state.notice)
        assertEquals("History evidence changed", f.controller.state.error)
        f.controller.finishHistory()
        assertEquals(1, f.access.finishes)
    }

    @Test fun backDiscardsThePresentedConfirmation() = fixture { f ->
        f.controller.open(); f.controller.inspectHistory(); f.controller.back(); f.controller.finishHistory()
        assertNull(f.controller.state.historyInspection); assertEquals(0, f.access.finishes)
    }

    @Test fun agentConversationCannotExposeProjectHistoryRecovery() = fixture(agent = true) { f ->
        f.controller.open(); f.controller.inspectHistory(); f.controller.finishHistory()
        assertFalse(f.controller.state.supportsHistoryRecovery)
        assertEquals(0, f.access.inspects); assertEquals(0, f.access.finishes)
    }

    private class Access : ProjectCopyRecoveryAccess {
        var lists = 0; var inspects = 0; var finishes = 0; var closed = false; var completed = false
        var onFinish: suspend () -> Unit = {}
        val inspection = ProjectCopyJournal.RetirementInspection("token", "00000000-0000-0000-0000-000000000001",
            ProjectCopyJournal.RetirementLocation.ARCHIVE, 5000L)
        override val supportsHistoryRecovery = true
        override suspend fun list(): ProjectCopyJournal.Recovery { lists++; return ProjectCopyJournal.Recovery(emptyList(), if (completed) null else "History retirement pending") }
        override suspend fun inspect(transactionId: String): ProjectCopyJournal.Inspection = error("Not a file-copy inspection")
        override suspend fun acknowledge(inspection: ProjectCopyJournal.Inspection) = error("No file-copy authorization")
        override suspend fun inspectHistory(): ProjectCopyJournal.RetirementInspection { inspects++; return inspection }
        override suspend fun finishHistory(inspection: ProjectCopyJournal.RetirementInspection) {
            assertSame(this.inspection, inspection); finishes++; onFinish(); completed = true
        }
        override fun close() { closed = true }
    }
    private class Fixture(agent: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val access = Access()
        val controller = ProjectCopyRecoveryController(if (agent)
            ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, "session")
        else ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.EXPLORER_IMPORT), scope, { true }, { ProjectCopyRecoveryActions { access } })
    }
    private fun fixture(agent: Boolean = false, block: (Fixture) -> Unit) {
        val f = Fixture(agent)
        try { block(f) } finally { f.controller.dispose(); f.scope.cancel() }
    }
}
