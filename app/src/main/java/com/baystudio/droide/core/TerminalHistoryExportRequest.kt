package com.baystudio.droide.core

/** One picker/write at a time. Text is memory-only; saved state carries only an outstanding marker. */
internal class TerminalHistoryExportRequest {
    class Ticket internal constructor(val requestId: String, val history: TerminalRetainedHistory, val epoch: String)
    data class Result(val ticket: Ticket? = null, val message: String? = null)
    private var pending: Ticket? = null
    private var writing: Ticket? = null
    private var outstanding: String? = null
    val busy: Boolean get() = outstanding != null || writing != null
    val pickerOutstanding: String? get() = outstanding
    val writeActive: Boolean get() = writing != null

    fun restoreOutstanding(value: String?) {
        check(!busy) { "Export already active" }
        require(value == null || value.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        outstanding = value
    }

    fun begin(history: TerminalRetainedHistory, epoch: String, requestId: String = java.util.UUID.randomUUID().toString()): Ticket {
        check(!busy) { "A terminal export is already pending" }
        require(epoch.isNotBlank())
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        return Ticket(requestId, history, epoch).also { pending = it; outstanding = requestId }
    }

    fun launchFailed(ticket: Ticket) {
        if (pending === ticket) { pending = null; outstanding = null }
    }

    fun pickerResult(requestId: String, hasDestination: Boolean, currentOpenEpoch: String?): Result {
        // An old registry key must never consume a newer export's snapshot.
        if (outstanding != requestId) return Result()
        val ticket = pending
        pending = null
        outstanding = null
        if (!hasDestination) return Result()
        if (ticket == null) return Result(message = "Terminal snapshot was lost during recreation. Export again; the chosen file may be empty.")
        if (ticket.epoch != currentOpenEpoch) return Result(message = "Terminal export was cancelled because Droide stopped. The chosen file may be empty.")
        writing = ticket
        return Result(ticket = ticket)
    }

    /** Explicit retry may retire a marker whose in-memory snapshot was lost. Old callbacks stay fenced. */
    fun retireUnavailableSnapshot(): Boolean {
        if (outstanding == null || pending != null || writing != null) return false
        outstanding = null
        return true
    }

    fun finish(ticket: Ticket): Boolean {
        if (writing !== ticket) return false
        writing = null
        return true
    }
}
