package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Existing local lease ownership for finite npm/Ruby work and their bounded probes. */
internal object OwnedInstallerCommand {
    internal data class Lease(
        val environment: Map<String, String>,
        val wrap: (String) -> String,
        val cleanup: () -> Unit,
    )

    internal data class Request(
        val argv: List<String>,
        val cwd: File,
        val environment: Map<String, String>,
        val timeoutMs: Long,
        val maxOutputBytes: Int,
        val onOutput: ((String) -> Unit)?,
        val cleanup: () -> Unit,
        val keepTail: Boolean = true,
    )

    suspend fun capture(
        launch: TerminalLaunchSpec,
        cwd: File,
        argv: List<String>,
        timeoutMs: Long = 30_000L,
        maxOutputBytes: Int = 128_000,
        onOutput: ((String) -> Unit)? = null,
        leaseFactory: () -> Lease = ::localLease,
        execute: suspend (Request) -> ExecResult = ::captureRequest,
    ): ExecResult {
        currentCoroutineContext().ensureActive()
        require(timeoutMs in 0L..86_400_000L) { "Invalid installer process timeout" }
        require(maxOutputBytes in 1..4_000_000) { "Invalid installer output limit" }
        val arguments = argv.toList()
        val commandLaunch = launch.copy(prefix = launch.prefix.toList(), environment = launch.environment.toMap(),
            interactiveArgs = launch.interactiveArgs.toList(), interactiveEnvironment = launch.interactiveEnvironment.toMap())
        ProcessSecurityPolicy.validateArgv(arguments)
        require(arguments.first().isNotBlank()) { "Installer executable is missing" }
        ProcessSecurityPolicy.validateArgv(commandLaunch.command(arguments))
        val lease = leaseFactory()
        val leaseEnvironment = lease.environment.toMap()
        val target = commandLaunch.command(arguments, leaseEnvironment)
            .joinToString(" ", transform = LocalExecutionSubstrate::shellQuote)
        val request = Request(
            argv = listOf("/system/bin/sh", "-c", lease.wrap(target)), cwd = cwd,
            environment = (commandLaunch.environment + leaseEnvironment).toMap(),
            timeoutMs = timeoutMs, maxOutputBytes = maxOutputBytes,
            onOutput = onOutput, cleanup = lease.cleanup,
        )
        // The supervisor owns cleanup once a process starts; never run a second cleanup here.
        return execute(request)
    }

    private fun localLease(): Lease {
        val lease = RemoteProcessLease.createLocal("managed-install")
        return Lease(lease.environment, lease::wrap) { LocalExecutionSubstrate.terminateLease(lease) }
    }

    private suspend fun captureRequest(request: Request): ExecResult = LocalProcessSupervisor.capture(
        argv = request.argv, cwd = request.cwd, environment = request.environment,
        timeoutMs = request.timeoutMs, maxOutputBytes = request.maxOutputBytes,
        onOutput = request.onOutput, cleanup = request.cleanup, keepTail = request.keepTail,
    )
}
