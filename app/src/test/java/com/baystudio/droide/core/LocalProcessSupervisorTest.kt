package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LocalProcessSupervisorTest {
    private val cwd = File(requireNotNull(System.getProperty("java.io.tmpdir")))
    @Test fun tailCaptureRetainsFinalBuildFailure() = runBlocking {
        val result = LocalProcessSupervisor.capture(
            listOf("/bin/sh", "-c", "head -c 100000 /dev/zero; printf 'FINAL: compilation failed'; exit 7"), cwd,
            maxOutputBytes = 1024, keepTail = true,
        )
        assertEquals(7, result.exitCode)
        assertTrue(result.output.contains("FINAL: compilation failed"))
        assertTrue(result.output.endsWith("[output truncated]\n"))
        assertTrue(result.output.length < 1100)
    }

    @Test fun streamedUnicodeMatchesCapturedOutput() = runBlocking {
        val chunks = StringBuilder()
        val result = LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "printf '日本語 😀 selesai'"), cwd, onOutput = chunks::append)
        assertEquals("日本語 😀 selesai", result.output)
        assertEquals(result.output, chunks.toString())
    }
    @Test fun capturesExitStatusAndStderr() = runBlocking {
        val r = LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "printf out; printf err >&2; exit 7"), cwd)
        assertEquals(7, r.exitCode); assertEquals("outerr", r.output); assertFalse(r.timedOut)
    }
    @Test fun drainsBeyondOutputLimit() = runBlocking {
        val r = LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "head -c 2000000 /dev/zero"), cwd, maxOutputBytes = 1024, timeoutMs = 5_000)
        assertEquals(0, r.exitCode); assertTrue(r.output.endsWith("[output truncated]\n")); assertTrue(r.output.length < 1100)
    }
    @Test fun timesOutWithoutWaitingForOutput() = runBlocking {
        // On loaded CI, JVM scheduling and creating a child can take >2.5 seconds. Deadline behavior is
        // verified with a monotonic synthetic-clock test.
        val r = LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "exec sleep 20"), cwd, timeoutMs = 150)
        assertEquals(124, r.exitCode)
        assertTrue(r.timedOut)
    }
    @Test fun cancellationRunsCleanup() = runBlocking {
        val cleaned = AtomicBoolean(false)
        val task = launch { LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "exec sleep 20"), cwd, cleanup = { cleaned.set(true) }) }
        delay(150)
        withTimeout(2500) { task.cancelAndJoin() }
        assertTrue(cleaned.get())
    }
    @Test fun streamsStdinAndClosesIt() = runBlocking {
        val r = LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "wc -c"), cwd,
            input = { out -> repeat(300) { out.write(ByteArray(1000) { 42 }) } })
        assertEquals(0, r.exitCode); assertEquals("300000", r.output.trim())
    }

    @Test fun zeroDeadlineSupportsBackgroundJobsAndCapturesTheirExit() = runBlocking {
        val result = withTimeout(3_000) {
            LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "sleep 0.2; printf background; exit 7"), cwd, timeoutMs = 0)
        }
        assertEquals(7, result.exitCode)
        assertEquals("background", result.output)
        assertFalse(result.timedOut)
    }

    @Test fun unlimitedDeadlineStillCancelsAndReapsProcess() = runBlocking {
        val cleaned = AtomicBoolean(false)
        val pid = File.createTempFile("droide-unlimited-", ".pid").apply { delete() }
        try {
            val quoted = "'" + pid.absolutePath.replace("'", "'\\''") + "'"
            val task = launch {
                LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "echo \$\$ > $quoted; exec sleep 30"), cwd,
                    timeoutMs = 0, cleanup = { cleaned.set(true) })
            }
            withTimeout(3_000) { while (!pid.isFile) delay(10) }
            val child = pid.readText().trim().toLong()
            withTimeout(2_500) { task.cancelAndJoin() }
            assertTrue(cleaned.get())
            withTimeout(2_500) {
                while (File("/proc/$child").exists()) delay(10)
            }
        } finally { pid.delete() }
    }
}
