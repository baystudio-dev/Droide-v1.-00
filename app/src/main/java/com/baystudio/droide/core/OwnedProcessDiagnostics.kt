package com.baystudio.droide.core

import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Process management uses admitted job IDs, never an unverified host PID or port owner. */
class OwnedProcessDiagnostics(private val commands: BackgroundCommandManager) {
    fun list(): String = buildString {
        appendLine("scope=workspace_primary_agent_jobs; system_process_table=not_used")
        val jobs = commands.list()
        if (jobs.isEmpty()) append("No Droide-owned Agent command sessions.")
        else jobs.forEach { appendLine(render(it)) }
        append("\nOnly this workspace's primary Agent command sessions are registered here. Shell-started processes and other process managers are not included. A session ID does not prove ownership of a port.")
    }.trimEnd()

    fun status(id: String): String? {
        requireJobId(id)
        return commands.snapshot(id)?.let(::render)
    }

    suspend fun stop(id: String): String? {
        requireJobId(id)
        return commands.kill(id)?.let {
            if (it.state == BackgroundCommandManager.State.KILLED) "Stopped owned session; ${render(it)}"
            else "Owned session already finished; no process was stopped; ${render(it)}"
        }
    }

    // Commands and output can contain credentials. These diagnostics deliberately expose neither.
    private fun render(value: BackgroundCommandManager.Snapshot): String = buildString {
        append("id=${value.id} state=${value.state.name.lowercase()}")
        value.exitCode?.let { append(" exit=$it") }
        append(" started_ms=${value.createdAtMs}")
        value.finishedAtMs?.let { append(" finished_ms=$it") }
    }

    private fun requireJobId(id: String) {
        require(id.matches(Regex("bg-[A-Za-z0-9-]{1,80}"))) { "Expected a Droide-owned command session ID, not a PID or port" }
    }
}

internal enum class LoopbackPortState { BIND_SUCCEEDED, IN_USE, UNAVAILABLE }

internal data class LoopbackPortDiagnostic(val port: Int, val state: LoopbackPortState) {
    fun render(): String = buildString {
        appendLine("scope=Android_host_ipv4_loopback address=127.0.0.1 port=$port state=${state.name.lowercase()}")
        appendLine("This is a momentary bind probe, not a reservation or a port-owner lookup. VM and remote workstation ports are separate.")
        when (state) {
            LoopbackPortState.IN_USE -> append("The port is occupied; inspect 'droide process list' and stop only a known owned session ID. No process was stopped.")
            LoopbackPortState.BIND_SUCCEEDED -> append("Binding succeeded at probe time; another process may claim the port afterward.")
            LoopbackPortState.UNAVAILABLE -> append("The app could not establish availability. Android permissions or socket restrictions may prevent this probe.")
        }
    }
}

internal object LoopbackPortProbe {
    fun inspect(port: Int): LoopbackPortDiagnostic {
        require(port in 1..65_535) { "Port must be between 1 and 65535" }
        val state = try {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port))
            }
            LoopbackPortState.BIND_SUCCEEDED
        } catch (failure: BindException) {
            // BindException can also represent EACCES. Do not report a permission failure as an owner.
            if (failure.message.orEmpty().contains("EADDRINUSE", ignoreCase = true) ||
                failure.message.orEmpty().contains("already in use", ignoreCase = true)) LoopbackPortState.IN_USE
            else LoopbackPortState.UNAVAILABLE
        } catch (_: java.io.IOException) {
            LoopbackPortState.UNAVAILABLE
        } catch (_: SecurityException) {
            LoopbackPortState.UNAVAILABLE
        }
        return LoopbackPortDiagnostic(port, state)
    }
}
