package com.baystudio.droide.core

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal object OwnedDevelopmentOperation {
    private object Key : CoroutineContext.Key<Owner>
    private class Owner(val handle: DevelopmentForegroundService.Handle) : AbstractCoroutineContextElement(Key)
    suspend fun <T> run(context: Context, label: String, block: suspend () -> T): T =
        runWithCpuPolicy(context, label, true, block)

    suspend fun <T> runWithoutCpu(context: Context, label: String, block: suspend () -> T): T =
        runWithCpuPolicy(context, label, false, block)

    private suspend fun <T> runWithCpuPolicy(context: Context, label: String, cpuWork: Boolean, block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        // Nested package steps remain one owned operation and one wake-lock policy.
        if (currentCoroutineContext()[Key] != null) return block()
        val owner = currentCoroutineContext().job
        val handle = if (cpuWork) DevelopmentForegroundService.startRequired(context, label) { owner.cancel() }
        else checkNotNull(DevelopmentForegroundService.startWithoutCpu(context, label) { owner.cancel() }) {
            "Android did not allow the required foreground developer session to start"
        }
        return try { withContext(Owner(handle)) { currentCoroutineContext().ensureActive(); block() } }
        finally { DevelopmentForegroundService.finish(context, handle) }
    }
    /** UIDT/JobScheduler already supplies its own transfer wake lock; the caller can idle while waiting. */
    suspend fun <T> withCpuIdle(block: suspend () -> T): T {
        val owner = currentCoroutineContext()[Key] ?: return block()
        val previous = DevelopmentForegroundSessionRegistry.cpuWork(owner.handle.token)
        DevelopmentForegroundService.setCpuWork(owner.handle, false)
        return try { block() } finally { DevelopmentForegroundService.setCpuWork(owner.handle, previous) }
    }
}

/** A backend may protect an owned command, without interrupting or taking ownership of a human shell. */
interface DevelopmentOperationSession {
    suspend fun <T> protectCommand(block: suspend () -> T): T
}
