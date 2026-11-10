package com.baystudio.droide.ui

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real JVM coroutine/lifetime tests for production action ownership. Android dispatch and UI are injected. */
class SettingsOfficialLinkActionsTest {
    @Test fun constructionDoesNotDispatchOrReportAnything() = runBlocking {
        var dispatches = 0
        var reports = 0
        SettingsOfficialLinkActions(this, { dispatches++; null }, { _, _ -> reports++ })
        assertEquals(0, dispatches)
        assertEquals(0, reports)
    }

    @Test fun explicitSuccessDispatchesExactTargetOnlyOnceWithoutFeedback() = runBlocking {
        val dispatched = mutableListOf<SettingsOfficialLink>()
        var reports = 0
        val actions = SettingsOfficialLinkActions(this, { dispatched += it; null }, { _, _ -> reports++ })
        actions.open(SettingsOfficialLink.INSTAGRAM)
        assertEquals(listOf(SettingsOfficialLink.INSTAGRAM), dispatched)
        assertEquals(0, reports)
    }

    @Test fun failureCarriesExactCapturedTargetAndMessage() = runBlocking {
        var reported: Pair<SettingsOfficialLink, String>? = null
        val actions = SettingsOfficialLinkActions(this, { it.unavailableMessage }, { link, message -> reported = link to message })
        actions.open(SettingsOfficialLink.SUPPORT)
        assertEquals(SettingsOfficialLink.SUPPORT to SettingsOfficialLink.SUPPORT.unavailableMessage, reported)
    }

    @Test fun aNewerFailedTapCancelsOldFeedbackAndCannotCopyTheOldAddress() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pending = mutableListOf<CompletableDeferred<Unit>>()
        val copied = mutableListOf<String>()
        var cancelled = 0
        try {
            val actions = SettingsOfficialLinkActions(owner, { it.unavailableMessage }, { link, _ ->
                val choice = CompletableDeferred<Unit>(); pending += choice
                try { choice.await(); copied += link.targetLabel } finally { if (!currentCoroutineContext().isActive) cancelled++ }
            })
            actions.open(SettingsOfficialLink.WEBSITE)
            actions.open(SettingsOfficialLink.SUPPORT)
            assertEquals(1, cancelled)
            assertEquals(2, pending.size)
            pending[0].complete(Unit)
            assertTrue(copied.isEmpty())
            pending[1].complete(Unit)
            assertEquals(listOf("supportbaystudio@gmail.com"), copied)
        } finally { owner.cancel() }
    }

    @Test fun successfulNewerTapRemovesPriorFailureWithoutAutomaticRetry() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var cancelled = false
        var dispatches = 0
        try {
            val actions = SettingsOfficialLinkActions(owner, { dispatches++; if (dispatches == 1) it.unavailableMessage else null }, { _, _ ->
                try { awaitCancellation() } finally { cancelled = true }
            })
            actions.open(SettingsOfficialLink.PRIVACY)
            actions.open(SettingsOfficialLink.TERMS)
            assertTrue(cancelled)
            assertEquals(2, dispatches)
        } finally { owner.cancel() }
    }

    @Test fun repeatedSameLinkTapsDoNotQueueCopyPrompts() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var active = 0; var highWater = 0; var dispatches = 0
        try {
            val actions = SettingsOfficialLinkActions(owner, { dispatches++; it.unavailableMessage }, { _, _ ->
                active++; highWater = maxOf(highWater, active)
                try { awaitCancellation() } finally { active-- }
            })
            repeat(20) { actions.open(SettingsOfficialLink.DISCORD) }
            assertEquals(20, dispatches)
            assertEquals(1, active)
            assertEquals(1, highWater)
        } finally { owner.cancel() }
        assertEquals(0, active)
    }

    @Test fun disposingSettingsCancelsPendingPromptAndPreventsLaterCopy() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val copyChoice = CompletableDeferred<Unit>()
        var copied = false
        val actions = SettingsOfficialLinkActions(owner, { it.unavailableMessage }, { _, _ -> copyChoice.await(); copied = true })
        actions.open(SettingsOfficialLink.SOURCE)
        owner.cancel()
        copyChoice.complete(Unit)
        assertFalse(copied)
    }

    @Test fun clicksFromDisposedOwnerCannotDispatch() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var dispatches = 0
        val actions = SettingsOfficialLinkActions(owner, { dispatches++; null }, { _, _ -> fail("Unexpected feedback") })
        owner.cancel()
        actions.open(SettingsOfficialLink.WEBSITE)
        assertEquals(0, dispatches)
    }

    @Test fun cancellationDuringDispatchDoesNotStartADeadOwnersPrompt() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var reports = 0
        val actions = SettingsOfficialLinkActions(owner, { owner.cancel(); it.unavailableMessage }, { _, _ -> reports++ })
        actions.open(SettingsOfficialLink.PRIVACY)
        assertEquals(0, reports)
    }

    @Test fun promptCancellationIsPropagatedAndDoesNotBecomeFailureOrRetry() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var reports = 0; var dispatches = 0
        try {
            val actions = SettingsOfficialLinkActions(owner, { dispatches++; it.unavailableMessage }, { _, _ ->
                reports++
                throw CancellationException("dismissed")
            })
            actions.open(SettingsOfficialLink.TERMS)
            assertEquals(1, reports)
            assertEquals(1, dispatches)
            assertTrue(owner.isActive)
        } finally { owner.cancel() }
    }
    @Test fun replacingActionOwnerCancelsOnlyItsPromptAndPreventsStaleDispatch() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var dispatches = 0; var cancelled = false
        try {
            val actions = SettingsOfficialLinkActions(owner, { dispatches++; it.unavailableMessage }, { _, _ ->
                try { awaitCancellation() } finally { cancelled = true }
            })
            actions.open(SettingsOfficialLink.WEBSITE)
            actions.close()
            actions.close()
            actions.open(SettingsOfficialLink.SUPPORT)
            assertTrue(cancelled)
            assertEquals(1, dispatches)
            assertTrue(owner.isActive)
        } finally { owner.cancel() }
    }

}
