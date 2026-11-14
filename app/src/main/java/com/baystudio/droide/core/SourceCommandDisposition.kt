package com.baystudio.droide.core

/** Classifies an already-authenticated manager observation, never authenticates a public snapshot.
 * A nonnegative backend exit is a known terminal outcome, not evidence that effects succeeded.
 * Output capture completeness is independent from process status in ExecResult's contract.
 */
internal object SourceCommandDisposition {
    fun knownTerminal(snapshot:BackgroundCommandManager.Snapshot):Boolean =
        snapshot.state in setOf(BackgroundCommandManager.State.EXITED,BackgroundCommandManager.State.FAILED,
            BackgroundCommandManager.State.TIMED_OUT) && (snapshot.exitCode ?: -1)>=0
}
