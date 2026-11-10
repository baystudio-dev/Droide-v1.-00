package com.baystudio.droide.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Current and retained terminal owner diagnostics. No native/API process probe or cleanup. */
internal class CatalogTerminalObservation private constructor(
    private val manager: TerminalRegistryObservation,
    private val host: TerminalRegistryObservation?,
    private val sources: List<TerminalOwnershipObservation>,
    private val unsupported: Int,
) {
    /** No dispatch, native calls or mutable Main-owned collection reads. */
    fun validateMetadata() {
        manager.validate(); host?.validate()
        sources.forEach { it.validateMetadata() }
        manager.validate(); host?.validate()
    }
    suspend fun validate() {
        currentCoroutineContext().ensureActive()
        manager.validate(); host?.validate()
        sources.forEach { it.validate() }
        manager.validate(); host?.validate()
        currentCoroutineContext().ensureActive()
    }
    fun evidence(): CatalogReleasePreflight.ProcessEvidence {
        val receipts = sources.flatMap { it.receipts }
        return CatalogReleasePreflight.ProcessEvidence(emptyList(),
            unknownDescendants = listOf("Native PTY receipts do not establish detached process-group closure"),
            unknownDescriptors = listOf("Native PTY receipts do not establish inherited/external descriptor closure"),
            unknownCoverage = buildList {
                if (host == null) add("Retained native terminal host is unavailable or uninitialized")
                if (unsupported > 0) add("Terminal observation unsupported for $unsupported retained session(s)")
                if (receipts.any { it.status == TerminalReceiptStatus.UNAVAILABLE }) add("Native terminal receipt unavailable")
                add("Native terminal history/launch coverage is not complete")
            },
            retainedOwners = buildList {
                sources.forEach { addAll(it.retainedOwners) }
                if (manager.sessions.isNotEmpty()) add("Manager-retained terminal owners: ${manager.sessions.size}")
                if (host?.sessions?.isNotEmpty() == true) add("Application-retained terminal owners: ${host.sessions.size}")
                if (manager.preparing > 0) add("Terminal launch preparations: ${manager.preparing}")
                if (manager.pendingCleanup > 0) add("Pending terminal cleanup receipts: ${manager.pendingCleanup}")
                if (manager.failedCleanup > 0) add("Failed terminal cleanup receipts: ${manager.failedCleanup}")
                val pending = receipts.count { it.status == TerminalReceiptStatus.PENDING }
                if (pending > 0) add("Pending native PTY receipts: $pending")
                receipts.filter { it.processFailure != null && it.processFailure !in 0..1 }.forEach {
                    add("Native PTY custody failure: ${it.processFailure}")
                }
                receipts.filter { it.inputFailure != null && it.inputFailure != 0 }.forEach {
                    add("Native PTY input delivery failure: ${it.inputFailure}")
                }
            })
    }
    companion object {
        suspend fun capture(manager: TerminalManager, host: PersistentLocalTerminalHost?): CatalogTerminalObservation {
            val current = manager.observeOwnership()
            val retained = host?.observeOwnership()
            val sessions = mutableListOf<ITerminalSession>()
            (current.sessions + retained?.sessions.orEmpty()).forEach { session ->
                if (sessions.none { it === session }) sessions += session
            }
            var unsupported = 0
            val observations = sessions.mapNotNull { session ->
                val source = session as? TerminalOwnershipObservationSource
                if (source == null) { unsupported++; null } else source.observeTerminalOwnership()
            }
            return CatalogTerminalObservation(current, retained, observations, unsupported).also { it.validate() }
        }
    }
}
