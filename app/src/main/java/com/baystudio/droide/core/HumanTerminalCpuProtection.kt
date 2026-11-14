package com.baystudio.droide.core

import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One CPU-protection attempt per human command. Shell epochs and command generations must increase
 * monotonically; output and elapsed time never start or renew an attempt. A protection failure is
 * advisory only. Only the gateway's explicit user Stop/Exit callback may request a command stop.
 *
 * The stop recipient must recheck the supplied owner against its live PTY immediately before
 * signaling it. All injected callbacks and StateFlow publications run outside [lock].
 */
class HumanTerminalCpuProtection(
    private val acquire: (
        onAcquired: () -> Unit,
        onLost: (String) -> Unit,
        onStop: () -> Unit,
    ) -> (() -> Unit)?,
    private val stop: (shellEpoch: Long, generation: Long) -> Unit,
    private val warn: (String) -> Unit,
) {
    enum class Phase { IDLE, PENDING, ACQUIRED, UNAVAILABLE }

    data class Status(
        val phase: Phase,
        val shellEpoch: Long? = null,
        val generation: Long? = null,
        // Orders publications without holding the owner lock while resuming flow collectors.
        internal val revision: Long = 0,
    )

    private data class Owner(val shellEpoch: Long, val generation: Long) {
        fun precedes(other: Owner): Boolean = shellEpoch < other.shellEpoch ||
            (shellEpoch == other.shellEpoch && generation < other.generation)
    }

    private class Attempt(val owner: Owner) {
        var release: (() -> Unit)? = null
    }

    private data class Observation(
        val attempt: Attempt?,
        val release: (() -> Unit)?,
        val status: Status,
    )

    private val lock = Any()
    private val mutableStatus = MutableStateFlow(Status(Phase.IDLE))
    val status: StateFlow<Status> = mutableStatus.asStateFlow()
    private var desiredStatus = mutableStatus.value
    private var closed = false
    private var observedOwner: Owner? = null
    private var observedActive = false
    private var latestAttempt: Attempt? = null
    private var current: Attempt? = null

    fun observe(activity: TerminalCommandActivity, shellEpoch: Long) {
        val owner = Owner(shellEpoch, activity.generation)
        val observation = synchronized(lock) {
            if (closed || observedOwner?.let(owner::precedes) == true) return
            observedOwner = owner
            observedActive = activity.phase == TerminalCommandPhase.AWAITING_RESPONSE ||
                activity.phase == TerminalCommandPhase.RUNNING
            val release = if (!observedActive || current?.owner != owner) retireLocked() else null
            if (!observedActive) {
                Observation(null, release, updateStatusLocked(Phase.IDLE, owner))
            } else if (latestAttempt?.owner == owner) {
                // Remember failures, pending requests and retired attempts through duplicate phases.
                Observation(null, release, desiredStatus)
            } else {
                val attempt = Attempt(owner)
                latestAttempt = attempt // Admission is recorded before invoking the gateway.
                current = attempt
                Observation(attempt, release, updateStatusLocked(Phase.PENDING, owner))
            }
        }
        publish(observation.status)
        safely(observation.release)
        val attempt = observation.attempt ?: return
        // Publication/release may have synchronously finished or replaced this owner.
        if (!synchronized(lock) { !closed && current === attempt }) return
        val release = try {
            acquire({ acquired(attempt) }, { _ -> unavailable(attempt) }, { requestedStop(attempt) })
        } catch (cancellation: CancellationException) {
            cancelled(attempt)
            throw cancellation
        } catch (_: RuntimeException) {
            unavailable(attempt)
            return
        }
        if (release == null) {
            unavailable(attempt)
        } else {
            val retained = synchronized(lock) {
                if (!closed && current === attempt) {
                    attempt.release = release
                    true
                } else false
            }
            // An acquire may return after close, completion, failure or a newer command.
            if (!retained) safely(release)
        }
    }

    fun close() {
        val observation = synchronized(lock) {
            if (closed) return
            closed = true
            observedActive = false
            Observation(null, retireLocked(), updateStatusLocked(Phase.IDLE, null))
        }
        publish(observation.status)
        safely(observation.release)
    }

    private fun acquired(attempt: Attempt) {
        val next = synchronized(lock) {
            if (closed || current !== attempt) return
            updateStatusLocked(Phase.ACQUIRED, attempt.owner)
        }
        publish(next)
    }

    private fun unavailable(attempt: Attempt) {
        val observation = synchronized(lock) {
            if (closed || current !== attempt) return
            Observation(null, retireLocked(), updateStatusLocked(Phase.UNAVAILABLE, attempt.owner))
        }
        publish(observation.status)
        safely(observation.release)
        val relevant = synchronized(lock) {
            !closed && latestAttempt === attempt && observedOwner == attempt.owner &&
                observedActive && desiredStatus.phase == Phase.UNAVAILABLE
        }
        // Never expose callback/exception text: it may contain command output or private input.
        if (relevant) safely { warn(UNAVAILABLE_WARNING) }
    }

    private fun requestedStop(attempt: Attempt) {
        val observation = synchronized(lock) {
            if (closed || current !== attempt) return
            Observation(null, retireLocked(), updateStatusLocked(Phase.IDLE, attempt.owner))
        }
        publish(observation.status)
        try {
            val relevant = synchronized(lock) {
                !closed && latestAttempt === attempt && observedOwner == attempt.owner && observedActive
            }
            if (relevant) stop(attempt.owner.shellEpoch, attempt.owner.generation)
        } finally {
            safely(observation.release)
        }
    }

    private fun cancelled(attempt: Attempt) {
        val observation = synchronized(lock) {
            if (closed || current !== attempt) return
            Observation(null, retireLocked(), updateStatusLocked(Phase.IDLE, attempt.owner))
        }
        publish(observation.status)
        safely(observation.release)
    }

    /** Detach before invoking release, so synchronous callback reentry cannot release twice. */
    private fun retireLocked(): (() -> Unit)? {
        val attempt = current ?: return null
        current = null
        return attempt.release.also { attempt.release = null }
    }

    private fun updateStatusLocked(phase: Phase, owner: Owner?): Status {
        if (desiredStatus.phase != phase || desiredStatus.shellEpoch != owner?.shellEpoch ||
            desiredStatus.generation != owner?.generation) {
            desiredStatus = Status(phase, owner?.shellEpoch, owner?.generation, desiredStatus.revision + 1)
        }
        return desiredStatus
    }

    private fun publish(next: Status) {
        while (true) {
            val previous = mutableStatus.value
            if (previous.revision >= next.revision) return
            if (mutableStatus.compareAndSet(previous, next)) return
        }
    }

    private fun safely(action: (() -> Unit)?) {
        // Cleanup and advisory UI failures cannot replay commands. Cancellation still propagates.
        try { action?.invoke() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RuntimeException) { /* Best effort; ownership was already retired. */ }
    }

    companion object {
        const val UNAVAILABLE_WARNING =
            "Background CPU protection is unavailable. Keep Droide open while this command runs."
    }
}
