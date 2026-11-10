package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class InstallerProgressOutcome { RUNNING, SUCCEEDED, FAILED, CANCELED }

internal data class InstallerProgressDetails(
    val familyId: String,
    val familyName: String,
    val version: String,
    val components: List<String>,
)

internal data class InstallerProgressEntry(
    val id: Long,
    val details: InstallerProgressDetails,
    val outcome: InstallerProgressOutcome = InstallerProgressOutcome.RUNNING,
    val stage: String = "queued",
    val message: String = "Waiting to start installation…",
    val log: String = "",
    val fraction: Float? = null,
    val cancelRequested: Boolean = false,
) {
    val running: Boolean get() = outcome == InstallerProgressOutcome.RUNNING
}

internal data class InstallerProgressPresentationState(
    val entries: List<InstallerProgressEntry> = emptyList(),
    val expandedId: Long? = null,
) {
    val expanded: InstallerProgressEntry? get() = entries.firstOrNull { it.id == expandedId }
}

/** Presentation for UI-requested installs; the supplied manager scope remains the job owner. */

internal class InstallerProgressPresentation {
    private val lock = Any()
    private val mutableState = MutableStateFlow(InstallerProgressPresentationState())
    val state: StateFlow<InstallerProgressPresentationState> = mutableState.asStateFlow()
    private val jobs = mutableMapOf<Long, Job>()
    private var nextId = 0L

    fun launch(
        scope: CoroutineScope,
        details: InstallerProgressDetails,
        operation: suspend (report: (PackageProgressSnapshot, Float?) -> Unit) -> UnifiedPackageTransactionResult,
    ): Deferred<UnifiedPackageTransactionResult> {
        val job = synchronized(lock) {
            val id = ++nextId
            val result = AtomicReference<UnifiedPackageTransactionResult?>(null)
            // Bind before publishing or starting: cancellation before first progress is exact.
            val deferred = scope.async(start = CoroutineStart.LAZY) {
                operation { progress, fraction -> publish(id, progress, fraction) }.also(result::set)
            }
            jobs[id] = deferred
            val current = mutableState.value
            mutableState.value = current.copy(
                entries = trimCompleted(current.entries) + InstallerProgressEntry(id, details.copy(components = details.components.toList())),
                expandedId = id,
            )
            deferred.invokeOnCompletion { failure -> finish(id, result.get(), failure) }
            deferred
        }
        job.start()
        return job
    }

    fun minimize(id: Long) = synchronized(lock) {
        val current = mutableState.value
        if (current.expandedId == id) mutableState.value = current.copy(expandedId = null)
    }

    fun open(id: Long) = synchronized(lock) {
        val current = mutableState.value
        if (current.entries.any { it.id == id }) mutableState.value = current.copy(expandedId = id)
    }

    fun dismiss(id: Long) = synchronized(lock) {
        val current = mutableState.value
        if (current.entries.any { it.id == id && !it.running }) {
            mutableState.value = current.copy(entries = current.entries.filterNot { it.id == id },
                expandedId = current.expandedId.takeUnless { it == id })
        }
    }

    fun cancel(id: Long) {
        val job = synchronized(lock) {
            val current = mutableState.value
            val entry = current.entries.firstOrNull { it.id == id && it.running && !it.cancelRequested }
                ?: return
            mutableState.value = current.copy(entries = current.entries.map {
                if (it.id == id) entry.copy(cancelRequested = true) else it
            })
            jobs[id]
        }
        job?.cancel(CancellationException("Canceled by user"))
    }

    private fun publish(id: Long, progress: PackageProgressSnapshot, fraction: Float?) = synchronized(lock) {
        val current = mutableState.value
        mutableState.value = current.copy(entries = current.entries.map {
            if (it.id == id && it.running) it.copy(stage = progress.stage, message = progress.message.progressPrefix(1_024),
                log = progress.log.progressTail(8_192), fraction = fraction?.takeIf(Float::isFinite)?.coerceIn(0f, 1f)) else it
        })
    }

    private fun finish(id: Long, result: UnifiedPackageTransactionResult?, failure: Throwable?) = synchronized(lock) {
        jobs.remove(id)
        val outcome = when {
            failure is CancellationException -> InstallerProgressOutcome.CANCELED
            failure != null || result?.success != true -> InstallerProgressOutcome.FAILED
            else -> InstallerProgressOutcome.SUCCEEDED
        }
        val current = mutableState.value
        mutableState.value = current.copy(entries = trimCompleted(current.entries.map {
            if (it.id != id) it else it.copy(outcome = outcome, fraction = null, message = when (outcome) {
                InstallerProgressOutcome.CANCELED -> "Installation canceled."
                InstallerProgressOutcome.FAILED -> result?.message?.takeIf(String::isNotBlank)
                    ?: "Installation failed: ${failure?.javaClass?.simpleName ?: "unknown error"}"
                else -> result?.message?.takeIf(String::isNotBlank) ?: "Installation completed."
            }.progressPrefix(1_024))
        }, keepId = current.expandedId))
        // Keep expandedId unchanged: a hidden completion never steals workspace focus.
    }

    private fun trimCompleted(entries: List<InstallerProgressEntry>, keepId: Long? = null): List<InstallerProgressEntry> {
        // Never evict running work or the result currently being read. Retain eight recent results.
        val retained = entries.filterNot { it.running || it.id == keepId }.takeLast(8).mapTo(mutableSetOf()) { it.id }
        return entries.filter { it.running || it.id == keepId || it.id in retained }
    }
}

/** UTF-16 caps must not split a valid surrogate pair; these are not grapheme limits. */
private fun String.progressPrefix(limit: Int): String {
    var end = minOf(length, limit)
    if (end > 0 && end < length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) end--
    return substring(0, end)
}

private fun String.progressTail(limit: Int): String {
    var start = (length - limit).coerceAtLeast(0)
    if (start > 0 && start < length && this[start - 1].isHighSurrogate() && this[start].isLowSurrogate()) start++
    return substring(start)
}
