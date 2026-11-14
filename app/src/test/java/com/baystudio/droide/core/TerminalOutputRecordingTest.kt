package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TerminalOutputRecordingTest {
    @Test fun recordsBeyondScrollbackWithoutChangingBytesAndCopiesReusedBuffers() = runBlocking {
        val root = Files.createTempDirectory("terminal-recording-").toFile()
        try {
            val log = TerminalOutputRecording(root)
            log.start()
            val expected = ("\u001b[31mcafé 世界\u001b[0m\r\n".repeat(400)).toByteArray()
            val chunk = expected.copyOf()
            repeat(32) { log.append(chunk) }
            chunk.fill(0)
            withTimeout(5000) { log.awaitClosed() }
            val result = root.resolve(log.state.value.path!!).readBytes()
            assertEquals(expected.size * 32, result.size)
            assertArrayEquals(expected, result.copyOfRange(0, expected.size))
            assertArrayEquals(expected, result.copyOfRange(result.size - expected.size, result.size))
            assertNull(log.state.value.problem)
            assertFalse(log.state.value.active)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun stopsAtLimitWithoutBlockingOrClaimingCompleteRecording() = runBlocking {
        val root = Files.createTempDirectory("terminal-recording-").toFile()
        try {
            val log = TerminalOutputRecording(root, limitBytes = 6)
            log.start(); log.append("abcdef".toByteArray()); log.append("not recorded".toByteArray())
            withTimeout(5000) { log.awaitClosed() }
            assertEquals("abcdef", root.resolve(log.state.value.path!!).readText())
            assertTrue(log.state.value.problem!!.contains("limit"))
            assertEquals(6, log.state.value.bytes)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun refusesSymlinkLogDirectoryWithoutTouchingItsTarget() = runBlocking {
        val root = Files.createTempDirectory("terminal-recording-").toFile()
        val outside = Files.createTempDirectory("terminal-outside-").toFile()
        try {
            Files.createSymbolicLink(root.resolve("terminal-logs").toPath(), outside.toPath())
            val log = TerminalOutputRecording(root)
            assertTrue(runCatching { log.start() }.isFailure)
            assertTrue(outside.listFiles()!!.isEmpty())
            assertFalse(log.state.value.active)
        } finally { PathSecurity.deleteTreeNoFollow(root); PathSecurity.deleteTreeNoFollow(outside) }
    }
}
