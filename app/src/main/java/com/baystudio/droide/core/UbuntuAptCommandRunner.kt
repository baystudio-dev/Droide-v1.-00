package com.baystudio.droide.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal typealias UbuntuGuestCommand = suspend (List<String>, Int, Long, ((String) -> Unit)?) -> ExecResult

internal class UbuntuPackageManagerBusyException(message: String) : IllegalStateException(message)

/** A busy native lock is contention, not a reason to run repair/update commands. */
internal class UbuntuAptCommandRunner(
    private val execute: UbuntuGuestCommand,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val lockWaitMs: Long = 180_000L,
) {
    suspend fun run(argv: List<String>, maxOutputBytes: Int = 256_000, timeoutMs: Long = 900_000,
                    progress: ((String) -> Unit)? = null): ExecResult {
        val started = nowMs()
        var announced = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val result = execute(argv, maxOutputBytes, timeoutMs, progress)
            if (!FoundryUbuntuAptPolicy.lockBusy(result)) return result
            if (!announced) {
                progress?.invoke("Waiting for another Ubuntu package operation to finish…\n")
                announced = true
            }
            val remaining = lockWaitMs - (nowMs() - started)
            if (remaining <= 0) throw UbuntuPackageManagerBusyException(
                "Ubuntu package manager is busy. Finish the other terminal/Agent installation, then retry.\n${result.output.takeLast(1_500)}"
            )
            pause(minOf(1_000L, remaining))
        }
    }
}
