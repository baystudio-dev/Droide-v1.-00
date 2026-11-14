package com.baystudio.droide.core

import java.util.ArrayDeque

/** Termux reports isRunning at pid 0, before JNI creates a child; only positive pids accept input. */
internal class TerminalStartupCommandQueue {
    private val commands = ArrayDeque<String>()
    private var chars = 0
    private var closed = false

    @Synchronized
    fun send(command: String, pid: Int, write: (String) -> Unit) {
        require(command.length <= 64_000 && '\u0000' !in command) { "Terminal command is too large or invalid" }
        sendRaw(command + "\n", pid, write)
    }

    @Synchronized
    fun sendRaw(text: String, pid: Int, write: (String) -> Unit) {
        require(text.length <= 65_536) { "Terminal input is too large" }
        check(!closed && pid >= 0) { "Terminal is closed or its process exited" }
        if (text.isEmpty()) return
        if (pid > 0) {
            flush(pid, write)
            write(text)
        } else {
            check(commands.size < 32 && chars + text.length <= 256_000) {
                "Terminal startup command queue is full"
            }
            commands.addLast(text)
            chars += text.length
        }
    }

    @Synchronized
    fun flush(pid: Int, write: (String) -> Unit) {
        if (pid < 0) close()
        if (closed || pid <= 0) return
        while (commands.isNotEmpty()) {
            val command = commands.removeFirst()
            chars -= command.length
            try {
                write(command)
            } catch (failure: Throwable) {
                // A PTY write can fail after partially accepting bytes. Never replay
                // this command or later queued text into a restarted/unknown shell.
                close()
                throw failure
            }
        }
    }

    @Synchronized
    fun close() {
        closed = true
        commands.clear()
        chars = 0
    }
}
