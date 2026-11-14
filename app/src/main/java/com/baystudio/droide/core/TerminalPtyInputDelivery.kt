package com.baystudio.droide.core

/**
 * PTY output callbacks run on the terminal's main looper. A queued PTY write
 * can fail after partially accepting input: fail closed without crashing UI.
 */
internal object TerminalPtyInputDelivery {
    fun flush(
        queue: TerminalStartupCommandQueue,
        pid: Int,
        write: (String) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        try {
            queue.flush(pid, write)
        } catch (failure: Exception) {
            // Queue.flush() already clears ambiguous pending input, and close()
            // ensures a later onTextChanged cannot replay this shell input.
            queue.close()
            onFailure(failure)
        }
    }

    fun mayInterrupt(destroyed: Boolean, pid: Int, isRunning: Boolean): Boolean =
        !destroyed && pid > 0 && isRunning
}
