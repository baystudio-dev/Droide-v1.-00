package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Existing participating ledgers only. It neither starts/stops nor probes any process. */
internal class CatalogProcessObservation private constructor(
    private val writers: ExportWriterAdmission.Observation,
    private val tickets: WorkspaceProcessWriteAdmission.Observation,
    private val exit: ExitHostedProcessOwners.Observation,
) {
    private fun requireCurrent() {
        writers.validate()
        tickets.validate()
        exit.validate()
    }
    suspend fun validate() {
        currentCoroutineContext().ensureActive()
        requireCurrent()
        currentCoroutineContext().ensureActive()
    }
    fun evidence(terminals: CatalogReleasePreflight.ProcessEvidence? = null, children: CatalogReleasePreflight.ProcessEvidence? = null): CatalogReleasePreflight.ProcessEvidence = CatalogReleasePreflight.ProcessEvidence(
        liveOwners = emptyList(), // No OS liveness property is probed by this collector.
        participatingWriters = buildList {
            writers.writers.forEach { add("Participating writer: ${it.label} (${it.count})") }
            writers.managed.forEach { add("Managed writer: ${it.label} (${it.count})") }
            writers.captures.forEach { add("Active capture: ${it.label}") }
        },
        retainedOwners = buildList {
            if (tickets.registeredHandles > 0) add("Workspace process tickets: ${tickets.registeredHandles}")
            if (exit.retainedHandles > 0) add("Application-wide retained process receipts: ${exit.retainedHandles}")
            if (exit.pendingCleanup > 0) add("Pending cleanup receipts: ${exit.pendingCleanup}")
            if (exit.failedCleanup > 0) add("Failed cleanup receipts: ${exit.failedCleanup}")
            addAll(terminals?.retainedOwners.orEmpty())
            addAll(children?.retainedOwners.orEmpty())
        },
        unknownDescendants = tickets.unknownFactoryRoots.map { "Unconfirmed factory: $it" } +
            "Arbitrary descendant ownership is not established" + terminals?.unknownDescendants.orEmpty() + children?.unknownDescendants.orEmpty(),
        unknownDescriptors = listOf("Inherited or external descriptor closure is not established") + terminals?.unknownDescriptors.orEmpty() + children?.unknownDescriptors.orEmpty(),
        unknownCoverage = listOf("Launch coverage is incomplete; application exit receipts have no workspace root mapping") +
            (terminals?.unknownCoverage ?: listOf("Native terminal observation producer is unavailable")) +
            (children?.unknownCoverage ?: listOf("Child resource observation producer is unavailable")),
    )
    companion object {
        fun capture(root: File): CatalogProcessObservation {
            val writers = ExportWriterAdmission.observe(root)
            val tickets = WorkspaceProcessWriteAdmission.observe(root)
            val exit = ExitHostedProcessRegistry.observe()
            // No locks span another ledger, IO or callback. Revalidate after ALL captures.
            return CatalogProcessObservation(writers, tickets, exit).also { it.requireCurrent() }
        }
    }
}
