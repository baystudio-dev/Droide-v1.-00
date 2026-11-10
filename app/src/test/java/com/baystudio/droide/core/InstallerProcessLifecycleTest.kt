package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class InstallerProcessLifecycleTest {
    @Test fun actualProcessDeadlineReturnsInsteadOfWaitingForPipeEof() = runBlocking {
        val cwd = Files.createTempDirectory("droide-deadline").toFile()
        try {
            val start = System.nanoTime()
            val result = LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "printf ready; exec sleep 30"), cwd,
                timeoutMs = 150, maxOutputBytes = 256, keepTail = true)
            assertTrue(result.timedOut); assertEquals(124, result.exitCode)
            assertTrue(result.output.contains("ready"))
            assertTrue((System.nanoTime() - start) / 1_000_000 < 2500)
        } finally { cwd.delete() }
    }
    @Test fun outputStreamsBeforeCompletionAndCancellationReturnsPromptly() = runBlocking {
        val cwd = Files.createTempDirectory("droide-cancel").toFile()
        try {
            val ready = CompletableDeferred<String>()
            val task = async { LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "printf streamed; exec sleep 30"), cwd,
                timeoutMs = 60000, onOutput = { ready.complete(it) }) }
            assertTrue(withTimeout(2000) { ready.await() }.contains("streamed"))
            assertFalse(task.isCompleted)
            withTimeout(2000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
        } finally { cwd.delete() }
    }
    @Test fun boundedTailKeepsActualErrorAndExitCode() = runBlocking {
        val cwd = Files.createTempDirectory("droide-tail").toFile()
        try {
            val result = LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "i=0; while [ \"\$i\" -lt 500 ]; do printf x; i=\$((i+1)); done; printf failure-tail; exit 9"), cwd,
                maxOutputBytes = 128, keepTail = true)
            assertEquals(9, result.exitCode)
            assertTrue(result.output.contains("failure-tail")); assertTrue(result.output.contains("truncated"))
            assertFalse(result.timedOut)
        } finally { cwd.delete() }
    }
}
