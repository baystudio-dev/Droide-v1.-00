package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OwnedInstallerCommandTest {
    private val cwd = File("/inert/workspace")
    private val token = "fixture-token"

    private fun quoted(value: String): String = when {
        value.isEmpty() -> "''"
        value.all { it.isLetterOrDigit() || it in "-_.,+/" } -> value
        else -> "'" + value.replace("'", "'\\''") + "'"
    }
    private fun lease(cleanup: () -> Unit = {}) = OwnedInstallerCommand.Lease(
        mapOf("DROIDE_PROCESS_LEASE" to token), { "FAKE-LEASE[$it]" }, cleanup,
    )

    @Test fun hostAndEnvResetGuestKeepArgumentsLiteral() = runBlocking {
        val args = listOf("npm", "path with spaces", "a'b", "${'$'}(inert)", "a;b")
        for (prefix in listOf(emptyList(), listOf("/inert/guest", "/usr/bin/env", "-i"))) {
            val launch = TerminalLaunchSpec(prefix = prefix)
            var executions = 0
            OwnedInstallerCommand.capture(launch, cwd, args, leaseFactory = { lease() }, execute = { request ->
                executions++
                val guestToken = if (prefix.isEmpty()) emptyList() else listOf("DROIDE_PROCESS_LEASE=$token")
                val target = (prefix + guestToken + args).joinToString(" ", transform = ::quoted)
                assertEquals(listOf("/system/bin/sh", "-c", "FAKE-LEASE[$target]"), request.argv)
                assertEquals(cwd, request.cwd)
                ExecResult(0, "fixture", false)
            })
            assertEquals(1, executions)
        }
    }

    @Test fun leaseTokenWinsAndCollectionsAreCopiedBeforeCallbacks(): Unit = runBlocking {
        val args = mutableListOf("bundle", "install")
        val prefix = mutableListOf("/inert/guest", "/usr/bin/env", "-i")
        val host = mutableMapOf("DROIDE_PROCESS_LEASE" to "wrong-token", "KEEP" to "original")
        val ownership = mutableMapOf("DROIDE_PROCESS_LEASE" to token)
        val launch = TerminalLaunchSpec(prefix = prefix, environment = host)
        val expectedTarget = (prefix.toList() + "DROIDE_PROCESS_LEASE=$token" + args.toList())
            .joinToString(" ", transform = ::quoted)
        OwnedInstallerCommand.capture(launch, cwd, args, leaseFactory = {
            args[0] = "mutated"; prefix[0] = "/mutated"; host["KEEP"] = "mutated"
            OwnedInstallerCommand.Lease(ownership, { target ->
                assertEquals(expectedTarget, target)
                ownership["DROIDE_PROCESS_LEASE"] = "mutated-after-copy"
                "FAKE-LEASE[$target]"
            }, {})
        }, execute = { request ->
            assertEquals(mapOf("DROIDE_PROCESS_LEASE" to token, "KEEP" to "original"), request.environment)
            assertTrue(request.argv.last().contains(quoted("DROIDE_PROCESS_LEASE=$token")))
            assertFalse(request.argv.last().contains("mutated"))
            ExecResult(0, "", false)
        })
    }

    @Test fun approvedUpdateShellTextStaysOneQuotedArgumentInsideTheGuestLease() = runBlocking {
        val launch = TerminalLaunchSpec(prefix = listOf("/inert/guest", "/usr/bin/env", "-i"), shell = "/bin/bash")
        val command = "npm install --global 'fixture@1' && printf '%s' \"${'$'}HOME\""
        val args = listOf(launch.shell, "-c", command)
        val output: (String) -> Unit = { }
        val result = ExecResult(0, "fake checked update", false)
        val actual = OwnedInstallerCommand.capture(launch, cwd, args, timeoutMs = 0L,
            maxOutputBytes = 24_000, onOutput = output, leaseFactory = { lease() }, execute = { request ->
                val target = (launch.prefix + "DROIDE_PROCESS_LEASE=$token" + args)
                    .joinToString(" ", transform = ::quoted)
                assertEquals(listOf("/system/bin/sh", "-c", "FAKE-LEASE[$target]"), request.argv)
                assertEquals(token, request.environment["DROIDE_PROCESS_LEASE"])
                assertEquals(0L, request.timeoutMs); assertEquals(24_000, request.maxOutputBytes)
                assertTrue(request.keepTail); assertSame(output, request.onOutput)
                result
            })
        assertSame(result, actual)
    }

    @Test fun deadlinesCapsOutputAndResultMetadataAreForwardedUnchanged() = runBlocking {
        val output = mutableListOf<String>()
        val callback: (String) -> Unit = output::add
        val result = ExecResult(7, "bounded fixture tail", true)
        for (deadline in listOf(0L, 1_250L, 86_400_000L)) {
            val actual = OwnedInstallerCommand.capture(TerminalLaunchSpec(), cwd, listOf("inert"),
                timeoutMs = deadline, maxOutputBytes = 128_000, onOutput = callback,
                leaseFactory = { lease() }, execute = { request ->
                    assertEquals(deadline, request.timeoutMs)
                    assertEquals(128_000, request.maxOutputBytes)
                    assertTrue(request.keepTail)
                    assertSame(callback, request.onOutput)
                    request.onOutput?.invoke("chunk")
                    result
                })
            assertSame(result, actual)
        }
        assertEquals(listOf("chunk", "chunk", "chunk"), output)
    }

    @Test(timeout = 10_000) fun invalidAndCancelledAdmissionsNeverCreateLeaseOrExecute() = runBlocking {
        var factories = 0
        var executions = 0
        val factory = { factories++; lease() }
        val execute: suspend (OwnedInstallerCommand.Request) -> ExecResult = { executions++; ExecResult(0, "", false) }
        val invalid = listOf(
            Triple(emptyList(), 30_000L, 128_000), Triple(listOf(""), 30_000L, 128_000),
            Triple(listOf("bad\narg"), 30_000L, 128_000), Triple(listOf("bad\u0000arg"), 30_000L, 128_000),
            Triple(listOf("inert"), -1L, 128_000), Triple(listOf("inert"), 86_400_001L, 128_000),
            Triple(listOf("inert"), 30_000L, 0), Triple(listOf("inert"), 30_000L, 4_000_001),
        )
        for ((args, timeout, limit) in invalid) {
            try {
                OwnedInstallerCommand.capture(TerminalLaunchSpec(), cwd, args, timeout, limit,
                    leaseFactory = factory, execute = execute)
                fail("Invalid admission accepted")
            } catch (_: IllegalArgumentException) { }
        }
        var attempted = false
        val job = launch {
            currentCoroutineContext().job.cancel()
            attempted = true
            try {
                OwnedInstallerCommand.capture(TerminalLaunchSpec(), cwd, listOf("inert"), leaseFactory = factory, execute = execute)
                fail("Cancelled admission accepted")
            } catch (_: CancellationException) { }
        }
        withTimeout(2_000) { job.join() }
        assertTrue(attempted); assertEquals(0, factories); assertEquals(0, executions)
    }

    @Test(timeout = 10_000) fun cancellationReachesFakeExecutorAndDoesNotDuplicateItsCleanup() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var factories = 0
        var executions = 0
        var cleanups = 0
        val job = launch {
            OwnedInstallerCommand.capture(TerminalLaunchSpec(), cwd, listOf("inert"), timeoutMs = 0,
                leaseFactory = { factories++; lease { cleanups++ } }, execute = { request ->
                    executions++; entered.complete(Unit)
                    try { awaitCancellation() } finally { request.cleanup() }
                })
        }
        try { withTimeout(2_000) { entered.await(); job.cancelAndJoin() } }
        finally { withContext(NonCancellable) { withTimeout(2_000) { job.cancelAndJoin() } } }
        assertTrue(job.isCancelled)
        assertEquals(1, factories); assertEquals(1, executions); assertEquals(1, cleanups)
    }

    @Test fun fakeCleanupFailureRemainsObservableWithoutRetry() = runBlocking {
        var executions = 0
        var cleanups = 0
        try {
            OwnedInstallerCommand.capture(TerminalLaunchSpec(), cwd, listOf("inert"),
                leaseFactory = { lease { cleanups++; throw IllegalStateException("fake cleanup failure") } },
                execute = { request ->
                    executions++
                    try { ExecResult(0, "completed", false) } finally { request.cleanup() }
                })
            fail("Cleanup failure swallowed")
        } catch (failure: IllegalStateException) { assertEquals("fake cleanup failure", failure.message) }
        assertEquals(1, executions); assertEquals(1, cleanups)
    }
}
