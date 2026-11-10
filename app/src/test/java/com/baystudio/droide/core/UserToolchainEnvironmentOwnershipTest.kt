package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real supervisor and global owner ledger, independent of a TerminalSession caller. */
class UserToolchainEnvironmentOwnershipTest {
    @get:org.junit.Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test fun failedEnvironmentCleanupRemainsOwnedGloballyAfterResolverReturns() = runBlocking {
        val root = Files.createTempDirectory("droide-environment-custody-").toFile()
        val cache = UserToolchainEnvironmentCache()
        try {
            try {
                cache.resolve("environment", root.path) {
                    val result = UserToolchainEnvironmentCleanup.capture { completed ->
                        LocalProcessSupervisor.captureWithAdmission(
                            listOf("/bin/sh", "-c", "printf observation"), root, emptyMap(),
                            maxOutputBytes = 1024, timeoutMs = 1_000,
                            cleanup = { error("Injected unconfirmed lease cleanup") },
                            onCleanupCompleted = completed,
                            admitStart = { it() },
                        )
                    }
                    UserToolchainEnvironmentCache.Capture(result.output, result.exitCode)
                }
                fail("Failed cleanup must escape environment fallback")
            } catch (_: UserEnvironmentCleanupUnconfirmed) { }
            assertEquals(1, WorkspaceProcessWriteAdmission.observe(root).registeredHandles)
            assertTrue(ExportWriterAdmission.observe(root).writers.isNotEmpty())
            assertTrue(ExitHostedProcessRegistry.observe().retainedHandles >= 1)
            ApplicationWorkAdmission.closeForExit()
            try { ExitHostedProcessRegistry.stopAndAwaitAll(); fail("Global Exit must retain failed cleanup") }
            catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("cleanup")) }
            assertEquals(1, WorkspaceProcessWriteAdmission.observe(root).registeredHandles)
            assertTrue(ExitHostedProcessRegistry.observe().retainedHandles >= 1)
        } finally { root.deleteRecursively() }
    }
}
