package com.baystudio.droide.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.baystudio.droide.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Activity-owned form. Only bounded user text is saved; cookies/tokens and requests never are. */
internal class SettingsBugReportViewModel(private val saved: SavedStateHandle) : ViewModel() {
    private val ownerJob = SupervisorJob()
    private val owner = CoroutineScope(ownerJob + Dispatchers.Main.immediate)
    private var boundEpoch: String? = null
    private var boundExitOwner: ExitUiWorkOwner? = null
    private var operation: Job? = null
    private var coordinator: BugReportCoordinator? = null
    private var uncertain = saved.get<Boolean>("unknown") == true || saved.get<Boolean>("sending") == true
    var visible by mutableStateOf(saved.get<Boolean>("visible") ?: false)
        private set
    var draft by mutableStateOf(readDraft())
        private set
    var preparing by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    var accepted by mutableStateOf(false)
        private set
    var errors by mutableStateOf(emptyMap<BugReportField, String>())
        private set
    var notice by mutableStateOf(if (uncertain) UNKNOWN_MESSAGE else "")
        private set
    var confirmRetry by mutableStateOf(false)
        private set
    var confirmClose by mutableStateOf(false)
        private set

    init {
        saved["sending"] = false
        saved["unknown"] = uncertain
    }

    /** Register the retained root once; activity recreation keeps the same epoch and Exit owner. */
    fun bindWorkOwner(exitOwner: ExitUiWorkOwner?, epoch: String?) {
        if (exitOwner == null || epoch == null || !ownerJob.isActive) return
        if (boundEpoch != null) {
            if (boundEpoch != epoch || boundExitOwner !== exitOwner) release()
            return
        }
        boundEpoch = epoch
        boundExitOwner = exitOwner
        exitOwner.register(ownerJob)
    }

    private fun admitted(): Boolean = ownerJob.isActive && boundEpoch?.let(ApplicationWorkAdmission::isCurrentOpen) == true
    private fun newCoordinator(): BugReportCoordinator {
        val epoch = checkNotNull(boundEpoch)
        return BugReportCoordinator(OfficialBugReportTransport(dispatchGuard = { block ->
            ApplicationWorkAdmission.withOpen(epoch, block)
        }), initiallyUnknown = uncertain)
    }

    fun open() {
        visible = true
        saved["visible"] = true
        prepare()
    }

    fun prepare() {
        if (!visible || sending || preparing || ready || accepted) return
        if (!admitted()) { notice = "This Droide session is stopped. Your draft is still here."; return }
        val client = coordinator ?: newCoordinator().also { coordinator = it }
        preparing = true
        operation = owner.launch {
            try {
                val result = client.prepare()
                if (!admitted()) { ready = false; return@launch }
                ready = result == BugReportPreparation.READY
                if (!ready && !uncertain) notice = "Could not prepare the secure form. Your draft is still here. Check your connection and try again."
                else if (ready && !uncertain) notice = ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ready = false
                notice = "Could not prepare the secure form. Your draft is still here."
            } finally { preparing = false }
        }
    }

    fun edit(value: BugReportDraft) {
        if (sending || accepted) return
        draft = value
        saveDraft()
        if (errors.isNotEmpty()) errors = BugReportPolicy.validate(value)
    }

    fun send(confirmUnknownRetry: Boolean = false) {
        if (sending || preparing || !ready || accepted) return
        if (!admitted()) { ready = false; notice = "This Droide session is stopped. Your draft is still here."; return }
        errors = BugReportPolicy.validate(draft)
        if (errors.isNotEmpty()) {
            notice = "Check the highlighted fields. Nothing was sent."
            return
        }
        if (uncertain && !confirmUnknownRetry) { confirmRetry = true; return }
        confirmRetry = false
        val client = coordinator ?: return
        val snapshot = draft.copy()
        sending = true
        saved["sending"] = true
        notice = "Sending to BayStudio…"
        operation = owner.launch {
            var resultMayHaveBeenAccepted = false
            try {
                val result = client.submit(snapshot, confirmUnknownRetry)
                resultMayHaveBeenAccepted = result is BugReportOutcome.Accepted || result == BugReportOutcome.UnknownSubmission
                if (!admitted()) {
                    uncertain = uncertain || client.requiresUnknownRetry || resultMayHaveBeenAccepted
                    ready = false
                    notice = if (uncertain) UNKNOWN_MESSAGE else "This Droide session stopped. Your draft is still here."
                    return@launch
                }
                // Main-thread publication is fenced against explicit Exit and replacement runtime.
                ApplicationWorkAdmission.withOpen(checkNotNull(boundEpoch)) {
                when (result) {
                    is BugReportOutcome.Accepted -> {
                        accepted = true
                        confirmClose = false
                        uncertain = false
                        draft = BugReportDraft()
                        saveDraft()
                        notice = "BayStudio's server accepted the report for its email service. Mailbox delivery has not been confirmed." +
                            (result.reportId?.let { " Report ID: $it" } ?: "")
                        ready = false
                        client.close()
                        coordinator = null
                    }
                    is BugReportOutcome.Invalid -> { errors = result.fields; notice = "Check the highlighted fields. Nothing was sent." }
                    is BugReportOutcome.Rejected -> {
                        notice = result.error.message + " Your draft is still here."
                        if (result.error != BugReportError.TOO_FAST) ready = false
                    }
                    BugReportOutcome.PreparationFailed -> { ready = false; notice = "Could not prepare the secure form. Your draft is still here." }
                    BugReportOutcome.UnknownSubmission -> { uncertain = true; ready = false; notice = UNKNOWN_MESSAGE }
                    BugReportOutcome.RetryConfirmationRequired -> { uncertain = true; confirmRetry = true; notice = UNKNOWN_MESSAGE }
                    BugReportOutcome.Busy -> notice = "A report operation is already running."
                    BugReportOutcome.Closed -> { ready = false; notice = "The secure form is closed. Close and reopen it to try again. Your draft is still here." }
                }
                uncertain = uncertain || client.requiresUnknownRetry
                }
            } catch (cancelled: CancellationException) {
                uncertain = uncertain || client.requiresUnknownRetry
                if (uncertain) notice = UNKNOWN_MESSAGE
                else if (!admitted()) notice = "This Droide session stopped. Your draft is still here."
                ready = false
                throw cancelled
            } catch (_: Exception) {
                uncertain = uncertain || client.requiresUnknownRetry || resultMayHaveBeenAccepted
                ready = false
                notice = if (uncertain) UNKNOWN_MESSAGE else "The request stopped without a confirmed result. Your draft is still here."
            } finally {
                sending = false
                saved["sending"] = false
                saved["unknown"] = uncertain
            }
        }
    }

    fun cancelSending() {
        if (!sending && !preparing) return
        val client = coordinator
        client?.cancel()
        uncertain = uncertain || client?.requiresUnknownRetry == true
        operation?.cancel()
        ready = false
        notice = if (uncertain) UNKNOWN_MESSAGE else "Stopped before submission was confirmed. Your draft is still here."
        saved["unknown"] = uncertain
    }

    fun requestClose() {
        if (sending || draft != BugReportDraft()) confirmClose = true else closeKeepingDraft()
    }
    fun dismissClosePrompt() { confirmClose = false }
    fun dismissRetryPrompt() { confirmRetry = false }

    fun closeKeepingDraft() {
        cancelSending()
        coordinator?.close()
        coordinator = null
        ready = false
        confirmClose = false
        confirmRetry = false
        visible = false
        saved["visible"] = false
        if (accepted) { accepted = false; notice = "" }
    }

    fun discardAndClose() {
        // Unknown request state is retained: discarding a local draft cannot recall a sent report.
        closeKeepingDraft()
        draft = BugReportDraft()
        errors = emptyMap()
        saveDraft()
    }

    internal fun release() {
        coordinator?.close()
        coordinator = null
        owner.cancel()
    }
    override fun onCleared() { release() }

    private fun readDraft(): BugReportDraft = BugReportDraft(
        title = saved.get<String>("title").orEmpty().take(120),
        category = BugReportCategory.entries.firstOrNull { it.name == saved.get<String>("category") },
        priority = BugReportPriority.entries.firstOrNull { it.name == saved.get<String>("priority") } ?: BugReportPriority.NORMAL,
        description = saved.get<String>("description").orEmpty().take(4000),
        steps = saved.get<String>("steps").orEmpty().take(2500),
        expected = saved.get<String>("expected").orEmpty().take(1200),
        actual = saved.get<String>("actual").orEmpty().take(1200),
        version = saved.get<String>("version").orEmpty().take(50),
        android = saved.get<String>("android").orEmpty().take(60),
        device = saved.get<String>("device").orEmpty().take(100),
        email = saved.get<String>("email").orEmpty().take(254),
        privacyAcknowledged = saved.get<Boolean>("privacy") ?: false,
    )
    private fun saveDraft() {
        saved["title"] = draft.title
        saved["category"] = draft.category?.name
        saved["priority"] = draft.priority.name
        saved["description"] = draft.description
        saved["steps"] = draft.steps
        saved["expected"] = draft.expected
        saved["actual"] = draft.actual
        saved["version"] = draft.version
        saved["android"] = draft.android
        saved["device"] = draft.device
        saved["email"] = draft.email
        saved["privacy"] = draft.privacyAcknowledged
    }
    companion object {
        const val UNKNOWN_MESSAGE = "The result is uncertain. BayStudio may already have received this report. Your draft is still here; sending it again could create a duplicate."
    }
}
