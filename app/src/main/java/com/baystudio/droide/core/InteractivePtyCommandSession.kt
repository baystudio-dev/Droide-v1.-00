package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** An explicitly approved Agent process, displayed in the selected existing terminal tab. */
interface InteractivePtyCommandSession {
    val supportsInteractivePty: Boolean
    suspend fun execInteractivePty(
        command: String,
        timeoutMs: Long,
        input: ProcessInputBuffer?,
        onOutput: (String) -> Unit,
    ): ExecResult
}

/** Ownership is separate from the user's shell and rejects stale completion callbacks. */
internal class InteractivePtyOwnership {
    private var owner: Any? = null
    @Synchronized fun acquire(humanPhase: TerminalCommandPhase): Any {
        check(owner == null) { "An Agent interactive command already occupies this terminal tab" }
        check(humanPhase == TerminalCommandPhase.READY) {
            "User shell is not ready; no interactive command was launched. Wait for the visible shell prompt."
        }
        return Any().also { owner = it }
    }
    @Synchronized fun isOwner(token: Any): Boolean = owner === token
    @Synchronized fun release(token: Any): Boolean {
        if (owner !== token) return false
        owner = null
        return true
    }
    companion object {
        fun mayStopHumanCommand(activity: TerminalCommandActivity, ownerGeneration: Long, agentDisplayed: Boolean, closed: Boolean): Boolean =
            activity.generation == ownerGeneration && mayStopHumanCommand(activity.phase, agentDisplayed, closed)
        fun mayStopHumanCommand(phase: TerminalCommandPhase, agentDisplayed: Boolean, closed: Boolean): Boolean =
            !closed && !agentDisplayed && phase in setOf(TerminalCommandPhase.AWAITING_RESPONSE, TerminalCommandPhase.RUNNING)
    }
}

/** TUI redraws are screen evidence, not a lossless stdout stream or shell prompt evidence. */
internal class InteractivePtyScreenOutput(private val limit: Int = 12_000) {
    private var previous: String? = null
    private var total = ""
    val output: String get() = total
    fun capture(screen: String): String? {
        val bounded = screen.takeLast(limit)
        if (bounded == previous || (bounded.isEmpty() && previous == null)) return null
        previous = bounded
        val frame = "\n[PTY screen snapshot; redraws may replace previous text]\n$bounded\n"
        total = (total + frame).takeLast(20_000)
        return frame
    }
}

/** A failed/partial write is a command failure; it must not impersonate the user's Stop. */
internal object InteractivePtyInputDelivery {
    fun drain(
        input: ProcessInputBuffer,
        completion: CompletableDeferred<Int>,
        isAlive: () -> Boolean,
        writeBytes: (ByteArray, Int, Int) -> Unit,
        onEof: () -> Unit,
    ) {
        val sink = object : java.io.OutputStream() {
            override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { writeBytes(bytes, offset, length) }
        }
        try {
            input.drain(sink, isAlive)
            if (input.isClosed && !input.isAborted && isAlive()) onEof()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            input.abort()
            completion.completeExceptionally(IllegalStateException("Interactive input delivery could not be confirmed; check before retrying", failure))
        }
    }
}
