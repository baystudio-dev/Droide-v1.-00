package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real session/supervisor processes on a substituted host layout, not Android/PRoot proof. */
class WorkspaceToolchainExecutionTest {
    @get:org.junit.Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private fun fixture(block: suspend CoroutineScope.(File, TerminalHostLayoutTestAuthority) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("toolchain-workspace-session-").toFile()
        val field = LocalExecutionSubstrate::class.java.getDeclaredField("filesDir").apply { isAccessible = true }
        val previous = field.get(null); field.set(null, root)
        val host = TerminalHostLayoutTestAuthority { null }
        try { withTimeout(20_000) { block(root, host) } }
        finally { field.set(null, previous); root.deleteRecursively() }
    }
    @Test fun workspaceRunnerAndAgentCommandsUseTheSameFreshToolchain() = fixture { root, host ->
        val bin = File(root, "user-bin").apply { mkdir() }
        File(bin, "droide-fixture-compiler").apply { writeText("#!/bin/sh\nprintf 'compiled:%s' \"\$PWD\""); setExecutable(true) }
        var preparations = 0
        val old = TerminalLaunchSpec(shell = "/bin/sh", environment = mapOf("PATH" to "/usr/bin:/bin"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = TerminalSession(root, scope, old, host) { current, directory, workspace ->
            preparations++; assertEquals(old, current); assertEquals(root, workspace); assertEquals(root, directory)
            current.copy(environment = mapOf("PATH" to "${bin.path}:/usr/bin:/bin"))
        }
        try {
            val result = Runner(session, root).runPlanResult(RunPlan(listOf(listOf("droide-fixture-compiler")), "Compile"))
            assertTrue(result.output, result.success); assertTrue(result.output.contains("compiled:${root.path}"))
            assertEquals(1, preparations)
            val ordinary = session.execArgv(listOf("/bin/sh", "-c", "command -v droide-fixture-compiler"), 5_000)
            assertEquals(ordinary.output, 0, ordinary.exitCode); assertEquals(2, preparations)
            assertEquals(File(bin, "droide-fixture-compiler").path, ordinary.output.trim())
        } finally { session.destroy(); scope.cancel() }
    }
    @Test fun unsupportedBackendKeepsItsOriginalEnvironment() = fixture { root, host ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val old = TerminalLaunchSpec(shell = "/bin/sh", environment = mapOf("DROIDE_FIXTURE" to "old"))
        val session = TerminalSession(root, scope, old, host) { _, _, _ -> null }
        try {
            val result = session.execWorkspaceArgv(root, root, listOf("/bin/sh", "-c", "printf '%s' \"\$DROIDE_FIXTURE\""), 5_000)
            assertEquals(result.output, 0, result.exitCode); assertEquals("old", result.output.trim())
        } finally { session.destroy(); scope.cancel() }
    }
    @Test fun failedPreparationCannotLaunchStaleCommand() = fixture { root, host ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = TerminalSession(root, scope, TerminalLaunchSpec(shell = "/bin/sh"), host) { _, _, _ ->
            error("Workspace toolchain changed")
        }
        try {
            val result = session.execWorkspaceArgv(root, root, listOf("touch", "must-not-exist"), 5_000)
            assertNotEquals(0, result.exitCode); assertFalse(File(root, "must-not-exist").exists())
            assertEquals(0, host.nativeCreationEntries)
        } finally { session.destroy(); scope.cancel() }
    }
    @Test fun cancellationDuringPreparationCannotCreateProcess() = fixture { root, host ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val entered = CompletableDeferred<Unit>()
        val session = TerminalSession(root, scope, TerminalLaunchSpec(shell = "/bin/sh"), host) { _, _, _ ->
            entered.complete(Unit); awaitCancellation()
        }
        try {
            val running = launch { session.execWorkspaceArgv(root, root, listOf("touch", "must-not-exist"), 5_000) }
            entered.await(); running.cancelAndJoin()
            assertEquals(0, host.nativeCreationEntries); assertFalse(File(root, "must-not-exist").exists())
        } finally { session.destroy(); scope.cancel() }
    }

    @Test fun environmentCleanupFailureIsRetainedAfterFailedCommandLeavesActiveSet() = fixture { root, host ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = TerminalSession(root, scope, TerminalLaunchSpec(shell = "/bin/sh"), host) { _, _, _ ->
            throw UserEnvironmentCleanupUnconfirmed(IllegalStateException("cleanup"))
        }
        try {
            val result = session.execWorkspaceArgv(root, root, listOf("touch", "must-not-exist"), 5_000)
            assertNotEquals(0, result.exitCode); assertEquals(0, host.nativeCreationEntries)
            val owners = session.observeTerminalOwnership().retainedOwners
            assertTrue(owners.toString(), owners.any { it.contains("execution cleanup failure retained") })
            try { session.stopAndAwaitForExit(); fail("Exit must not certify failed cleanup") }
            catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("cleanup")) }
        } finally { session.destroy(); scope.cancel() }
    }
    @Test fun cancelledEnvironmentCleanupFailureIsRetainedAfterCancelledCommandLeavesActiveSet() = fixture { root, host ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val cache = UserToolchainEnvironmentCache()
        val session = TerminalSession(root, scope, TerminalLaunchSpec(shell = "/bin/sh"), host) { _, _, _ ->
            cache.resolve("cancel", root.path) {
                UserToolchainEnvironmentCleanup.capture { completed ->
                    entered.complete(Unit)
                    try { awaitCancellation() }
                    finally { completed(IllegalStateException("cleanup")) }
                }
            }
            error("Cancelled resolver must not return")
        }
        try {
            val running = launch { session.execWorkspaceArgv(root, root, listOf("touch", "must-not-exist"), 5_000) }
            entered.await(); running.cancelAndJoin()
            assertEquals(0, host.nativeCreationEntries)
            val owners = session.observeTerminalOwnership().retainedOwners
            assertTrue(owners.toString(), owners.any { it.contains("execution cleanup failure retained") })
            try { session.stopAndAwaitForExit(); fail("Exit must not certify failed cleanup") }
            catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("cleanup")) }
        } finally { session.destroy(); scope.cancel() }
    }
}
