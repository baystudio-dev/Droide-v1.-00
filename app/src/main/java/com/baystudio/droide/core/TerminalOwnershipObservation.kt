package com.baystudio.droide.core

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Observation only. Implementations must never start, stop, probe or wait for a process. */
interface TerminalOwnershipObservationSource {
    suspend fun observeTerminalOwnership(): TerminalOwnershipObservation
}

enum class TerminalReceiptStatus { PENDING, RECORDED, UNAVAILABLE }
data class TerminalReceiptObservation(val role: String, val status: TerminalReceiptStatus,
                                      val processFailure: Int?, val inputFailure: Int?, val exitStatus: Int?)
class TerminalOwnershipObservation internal constructor(
    receipts: List<TerminalReceiptObservation>, val disposed: Boolean,
    retainedOwners: List<String> = emptyList(),
    private val validateOwner: () -> Unit,
) {
    val retainedOwners: List<String> = java.util.Collections.unmodifiableList(retainedOwners.toList())
    val receipts: List<TerminalReceiptObservation> = java.util.Collections.unmodifiableList(receipts.toList())
    /** Non-waiting owner metadata only; safe after dispatcher-owned validation. */
    internal fun validateMetadata() = validateOwner()
    suspend fun validate() {
        currentCoroutineContext().ensureActive(); validateOwner(); currentCoroutineContext().ensureActive()
    }
}

internal object NativePtyOwnershipObservation {
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun stamp(role: String, receipt: Deferred<NativePtyCleanupReceipt>): TerminalReceiptObservation {
        if (!receipt.isCompleted) return TerminalReceiptObservation(role, TerminalReceiptStatus.PENDING, null, null, null)
        val value = runCatching { receipt.getCompleted() }.getOrNull()
            ?: return TerminalReceiptObservation(role, TerminalReceiptStatus.UNAVAILABLE, null, null, null)
        return TerminalReceiptObservation(role, TerminalReceiptStatus.RECORDED,
            value.processFailureReason, value.inputFailureReason, value.observedExitStatus)
    }
    fun capture(human: Deferred<NativePtyCleanupReceipt>, pending: List<Deferred<NativePtyCleanupReceipt>>,
                frozenExit: List<Deferred<NativePtyCleanupReceipt>>, disposed: Boolean,
                validateOwner: () -> Unit): TerminalOwnershipObservation {
        val sources = mutableListOf<Pair<String, Deferred<NativePtyCleanupReceipt>>>()
        fun add(role: String, receipt: Deferred<NativePtyCleanupReceipt>) {
            // Deferred identity, never process equality, PID or a native getter.
            val index = sources.indexOfFirst { it.second === receipt }
            if (index < 0) sources += role to receipt
            else sources[index] = (sources[index].first + "+" + role) to receipt
        }
        add("human", human)
        pending.forEach { add("retained-agent", it) }
        frozenExit.forEach { add("exit-agent", it) }
        val observed = sources.map { stamp(it.first, it.second) }
        return TerminalOwnershipObservation(observed, disposed) {
            validateOwner()
            check(sources.map { stamp(it.first, it.second) } == observed) { "Native terminal receipts changed during review" }
            validateOwner()
        }
    }
}

/** Exact retained references are copied under their existing owner monitor. */
internal class TerminalRegistryObservation(
    sessions: List<ITerminalSession>, val disposed: Boolean, val pendingCleanup: Int,
    val failedCleanup: Int, val preparing: Int, private val validateOwner: () -> Unit,
) {
    val sessions: List<ITerminalSession> = java.util.Collections.unmodifiableList(sessions.toList())
    fun validate() = validateOwner()
}
