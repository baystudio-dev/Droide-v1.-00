package com.baystudio.droide.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Confined to the Settings UI owner. It owns feedback, never the persistence transaction. */
internal class SettingsActionRunner(
    private val scope: CoroutineScope,
    private val pendingChanged: (Set<String>) -> Unit,
    private val reportFailure: suspend (String, Throwable) -> Unit,
    private val reportResult: suspend (String) -> Unit,
) {
    private val pending = mutableSetOf<String>()

    fun launch(key: String, failureLabel: String, action: suspend () -> String?): Job? {
        if (!scope.isActive || !pending.add(key)) return null
        pendingChanged(pending.toSet())
        // Begin finally ownership before returning to the caller; a rapid dismissal cannot strand a
        // queued action's pending flag. DataStore remains responsible for atomicity.
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var failure: Throwable? = null
            var result: String? = null
            try {
                currentCoroutineContext().ensureActive()
                result = action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (recoverable: Exception) {
                failure = recoverable
            } catch (unavailable: LinkageError) {
                failure = unavailable
            } finally {
                pending.remove(key)
                pendingChanged(pending.toSet())
            }
            currentCoroutineContext().ensureActive()
            if (failure != null) reportFailure(failureLabel, failure)
            else if (result != null) reportResult(result)
        }
    }
}
