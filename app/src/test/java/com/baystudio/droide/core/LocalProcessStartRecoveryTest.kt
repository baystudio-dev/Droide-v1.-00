package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LocalProcessStartRecoveryTest {
    private fun fixture(block: suspend CoroutineScope.(File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("local-start-recovery-").toFile()
        try { withTimeout(10_000) { block(root) } } finally { root.deleteRecursively() }
    }
    private fun available(root: File) {
        assertTrue("A proven no-start must not poison retry", WorkspaceProcessWriteAdmission.observe(root).unknownFactoryRoots.isEmpty())
        ExportWriterAdmission.beginCapture(root).close()
    }
    @Test fun executableMissingDoesNotPoisonCaptureRetry() = fixture { root ->
        val failure = runCatching { LocalProcessSupervisor.capture(listOf(root.resolve("not-installed").path), root) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is IOException); available(root)
        assertEquals(0, LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "exit 0"), root).exitCode)
        available(root)
    }
    @Test fun executableNotExecutableDoesNotPoisonCaptureRetry() = fixture { root ->
        val path = root.resolve("script").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(false) }
        assertFalse(path.canExecute())
        val failure = runCatching { LocalProcessSupervisor.capture(listOf(path.path), root) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is IOException); available(root)
    }
    @Test fun cwdMissingDoesNotPoisonCaptureRetry() = fixture { root ->
        val missing = root.resolve("missing-directory")
        val failure = runCatching { LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "exit 0"), missing) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is IOException); available(missing)
    }
    @Test fun executableDirectoryDoesNotPoisonCaptureRetry() = fixture { root ->
        val failure = runCatching { LocalProcessSupervisor.capture(listOf(root.path), root) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is IOException); available(root)
    }
    @Test fun platformFailureAfterPreflightStillKeepsUnknownCustody() = fixture { root ->
        // Preflight passes. The platform throws only after native creation entry; this layer must not
        // infer cleanup from a generic IOException.
        val path = root.resolve("bad-interpreter").apply {
            writeText("#!/definitely/no/such/interpreter\n")
            setExecutable(true)
        }
        val failure = runCatching { LocalProcessSupervisor.capture(listOf(path.path), root) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is IOException)
        assertFalse(WorkspaceProcessWriteAdmission.observe(root).unknownFactoryRoots.isEmpty())
    }
    @Test fun shellExit127IsARealProcessAndReleasesOnlyAfterCleanup() = fixture { root ->
        val result = LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "a_missing_command_for_droide_test"), root)
        assertEquals(127, result.exitCode); available(root)
        assertEquals(0, LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "exit 0"), root).exitCode)
    }

}
