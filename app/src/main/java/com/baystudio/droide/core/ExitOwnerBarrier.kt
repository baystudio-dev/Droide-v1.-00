package com.baystudio.droide.core

import kotlinx.coroutines.*

/** Parallel stop requests retain their exact owners and completion; a UI timeout never loses them. */
internal class ExitOwnerBarrier(private val scope: CoroutineScope, owners: List<ApplicationExitSequence.Step>) {
    private class Owner(val step: ApplicationExitSequence.Step, var pending: Deferred<Result<Unit>>? = null, var complete: Boolean = false)
    private val owners = owners.map(::Owner)
    fun requestStop() {
        owners.filterNot { it.complete }.forEach { owner ->
            if (owner.pending == null) owner.pending = scope.async { runCatching { owner.step.run() } }
        }
    }
    suspend fun stopAndAwait() {
        requestStop()
        val failures = mutableListOf<String>()
        owners.filterNot { it.complete }.forEach { owner ->
            val result = checkNotNull(owner.pending).await()
            if (result.isSuccess) owner.complete = true else {
                owner.pending = null
                failures += "${owner.step.label}: ${result.exceptionOrNull()?.message?.take(160) ?: "cleanup failed"}"
            }
        }
        check(failures.isEmpty()) { "Exit remains incomplete. " + failures.joinToString("; ").take(460) }
    }
}
