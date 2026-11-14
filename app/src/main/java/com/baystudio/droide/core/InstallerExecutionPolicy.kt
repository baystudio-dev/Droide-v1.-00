package com.baystudio.droide.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/** A heartbeat describes activity, not a fabricated percentage or successful download. */
internal object InstallerExecutionPolicy {
    // Finite installer work retains owned cancellation, not an implicit runtime cutoff.
    const val INSTALL_OPERATION_DEADLINE_MS = 0L
    const val NPM_STEP_DEADLINE_MS = 0L
    const val HEARTBEAT_INTERVAL_MS = 20_000L

    /** The authority's outer transaction must not shorten a nested install's budget. */
    fun operationDeadlineMs(phase: ManagedExtensionInstaller.Phase): Long =
        if (phase == ManagedExtensionInstaller.Phase.INSTALLING || phase == ManagedExtensionInstaller.Phase.REPAIRING) INSTALL_OPERATION_DEADLINE_MS
        else 30L * 60_000L

    /** Preserve a provider-specific success message already verified by the installer. */
    fun finalMessage(alreadyReady: Boolean, verifiedMessage: String, fallback: String): String =
        if (alreadyReady && verifiedMessage.isNotBlank()) verifiedMessage else fallback

    fun successMessage(familyId: String): String = when (familyId) {
        "plugin.opencode" ->
            "OpenCode CLI installed and ready. Sign-in is optional for opening the CLI; connect only if your selected provider requires credentials."
        else -> "CLI installed and ready. Configure credentials only if required by the selected provider."
    }
}

internal class InstallerOperationDeadlineExceeded(val timeoutMs: Long) :
    IllegalStateException("Installer operation deadline reached after ${timeoutMs}ms")

private data class InstallerDeadlineResult<T>(val value: T)

/** Only this helper's own expiry is a deadline failure; caller cancellation stays cancellation. */
internal suspend fun <T> withInstallerOperationDeadline(timeoutMs: Long, block: suspend () -> T): T {
    require(timeoutMs >= 0L) { "Invalid installer deadline" }
    currentCoroutineContext().ensureActive()
    if (timeoutMs == 0L) return block()
    val result = withTimeoutOrNull(timeoutMs) { InstallerDeadlineResult(block()) }
    if (result != null) return result.value
    currentCoroutineContext().ensureActive()
    throw InstallerOperationDeadlineExceeded(timeoutMs)
}

/** Activity is a separate channel: never splice it into partially received credentials. */
internal suspend fun <T> withInstallerOutputHeartbeat(
    output: (String) -> Unit,
    activity: (String) -> Unit,
    heartbeat: InstallerOutputHeartbeat = InstallerOutputHeartbeat(),
    block: suspend ((String) -> Unit) -> T,
): T = coroutineScope {
    val acceptingOutput = AtomicBoolean(true)
    val heartbeatJob = heartbeat.start(this, activity)
    try {
        block { chunk ->
            if (acceptingOutput.get()) {
                heartbeat.observedOutput()
                output(chunk)
            }
        }
    } finally {
        acceptingOutput.set(false)
        withContext(NonCancellable) { heartbeatJob.cancelAndJoin() }
    }
}

internal class InstallerOutputHeartbeat(
    private val intervalMs: Long = InstallerExecutionPolicy.HEARTBEAT_INTERVAL_MS,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    init { require(intervalMs in 1..300_000L) }
    private val lastOutput = AtomicLong(clockNanos())

    fun observedOutput() {
        lastOutput.set(clockNanos())
    }

    internal fun isQuiet(): Boolean =
        ((clockNanos() - lastOutput.get()).coerceAtLeast(0L)) / 1_000_000L >= intervalMs

    /** Runs as a child of the installing command's coroutine, never detached. */
    fun start(scope: CoroutineScope, report: (String) -> Unit): Job = scope.launch {
        while (true) {
            delay(intervalMs)
            if (isQuiet()) report("Still working; waiting for installer output…\n")
        }
    }
}
