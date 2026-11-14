package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class TerminalOutputBufferTest {
    @Test fun smallChunksWrapWithoutLosingOrDuplicatingTheRetainedTail() {
        val buffer = TerminalOutputBuffer(73)
        var expected = ""
        repeat(2_000) { index ->
            val chunk = "$index:日本語 😀\n"
            buffer.append(chunk)
            expected = codePointTail(expected + chunk, 73)
            assertEquals(expected, buffer.snapshot())
        }
    }

    @Test fun oversizedChunksAndTinyCapsKeepOnlyCompleteBoundaryCodePoints() {
        for (capacity in 1..8) {
            val buffer = TerminalOutputBuffer(capacity)
            for (text in listOf("prefix😀", "prefix😀a", "prefix😀ab", "日本語", "e\u0301")) {
                buffer.clear()
                buffer.append(text)
                assertEquals("capacity $capacity for $text", codePointTail(text, capacity), buffer.snapshot())
                assertTrue(buffer.snapshot().length <= capacity)
            }
        }
    }

    @Test fun pairCrossingThePhysicalRingEndIsEvictedTogether() {
        val buffer = TerminalOutputBuffer(5)
        buffer.append("abcd")
        buffer.append("😀") // The high surrogate is at index 4, the low surrogate at index 0.
        assertEquals("bcd😀", buffer.snapshot())
        buffer.append("xy")
        assertEquals("d😀xy", buffer.snapshot())
        buffer.append("z")
        assertEquals("😀xyz", buffer.snapshot())
        buffer.append("q")
        assertEquals("xyzq", buffer.snapshot())
        buffer.append("s")
        assertEquals("xyzqs", buffer.snapshot())
    }

    @Test fun splitSurrogateAppendsMatchTheWholeStreamAcrossTinyCaps() {
        val text = "A😀日本語e\u0301👩\u200D💻\n".repeat(12)
        for (capacity in 1..11) {
            val buffer = TerminalOutputBuffer(capacity)
            val all = StringBuilder()
            // Include a split pair at every possible chunk boundary. An incomplete
            // input chunk is not rewritten; truncation must not create a new split.
            for (unit in text) {
                all.append(unit)
                buffer.append(unit.toString())
                assertEquals("capacity $capacity after ${all.length} units",
                    codePointTail(all.toString(), capacity), buffer.snapshot())
                assertTrue(buffer.snapshot().length <= capacity)
            }
        }
    }

    @Test fun fullCapacityChunkRecognizesThePairWithThePreviousAppend() {
        val buffer = TerminalOutputBuffer(3)
        buffer.append("x\uD83D")
        buffer.append("\uDE00ab")
        assertEquals("ab", buffer.snapshot())
        buffer.clear()
        buffer.append("\uDE00ab") // No preceding high surrogate: preserve the input as supplied.
        assertEquals("\uDE00ab", buffer.snapshot())
    }

    @Test fun bmpWhitespaceCombiningAndJoinerUnitsAreNotRewritten() {
        val buffer = TerminalOutputBuffer(6)
        buffer.append("日本 e\u0301\u200D ")
        assertEquals("本 e\u0301\u200D ", buffer.snapshot())
        buffer.clear()
        buffer.append("\uDC00x\uD800")
        assertEquals("\uDC00x\uD800", buffer.snapshot())
    }

    @Test fun oversizedChunkAndClearKeepOnlyTheNewBoundedTail() {
        val buffer = TerminalOutputBuffer(16)
        buffer.append("previous")
        buffer.append("0123456789".repeat(10_000))
        assertEquals("4567890123456789", buffer.snapshot())
        buffer.clear()
        assertEquals("", buffer.snapshot())
        buffer.append("new")
        assertEquals("new", buffer.snapshot())
    }

    @Test fun noisyBurstCreatesOneSnapshotAndFinalCloseFlushesTheLatestTail() {
        val buffer = TerminalOutputBuffer(200_000)
        val scheduled = mutableListOf<() -> Unit>()
        var captures = 0
        var output = ""
        val publisher = TerminalTranscriptPublisher(
            schedule = { _, action -> scheduled += action; {} },
            capture = { captures++; buffer.snapshot() },
            publish = { output = it },
        )
        val chunk = "build output\n".repeat(300)
        repeat(10_000) { buffer.append(chunk); publisher.request() }
        assertEquals(1, scheduled.size)
        assertEquals(0, captures)
        buffer.append("FINAL")
        publisher.close()
        assertEquals(1, captures)
        assertEquals(200_000, output.length)
        assertTrue(output.endsWith("FINAL"))
        scheduled.single()()
        assertEquals(1, captures)
    }

    @Test fun realPipeFloodKeepsTerminalUsableAndClearCannotRepublishOldOutput() = runBlocking {
        val directory = Files.createTempDirectory("terminal-output-stress-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val buffer = TerminalOutputBuffer(200_000)
        val output = MutableStateFlow("")
        val publisher = TerminalTranscriptPublisher(
            schedule = { wait, action ->
                val job = scope.launch { delay(wait); action() }
                val cancel = { job.cancel() }
                cancel
            },
            capture = buffer::snapshot,
            publish = { output.value = it },
        )
        // Exercise the actual pipe reader and transcript components with a host shell.
        // TerminalSession's production lease launcher requires Android's /system/bin.
        val terminal = InteractiveTerminalProcess(scope,
            launchProcess = { ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-s")
                .directory(directory).redirectErrorStream(true).start() },
            cleanup = {},
            output = { text -> buffer.append(text); publisher.request() },
        )
        try {
            terminal.start()
            terminal.send("head -c 1000000 /dev/zero | tr '\\0' x; printf '\\nDROIDE_OUTPUT_TAIL\\n'")
            withTimeout(10_000) { while (!output.value.endsWith("DROIDE_OUTPUT_TAIL\n")) delay(20) }
            assertTrue(output.value.length <= 200_000)
            assertTrue(output.value.contains("xxxx"))
            buffer.clear()
            publisher.clear()
            delay(150)
            assertEquals("", output.value)
            terminal.send("printf '\\nSTILL_READY\\n'")
            withTimeout(5_000) { while (!output.value.endsWith("STILL_READY\n")) delay(20) }
            assertFalse(output.value.contains("DROIDE_OUTPUT_TAIL"))
        } finally {
            terminal.destroy()
            publisher.close()
            scope.coroutineContext.job.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    /** Independent oracle: walk whole code points backwards instead of slicing UTF-16 units. */
    private fun codePointTail(text: String, limit: Int): String {
        var start = text.length
        var remaining = limit
        while (start > 0) {
            val units = Character.charCount(text.codePointBefore(start))
            if (units > remaining) break
            start -= units
            remaining -= units
        }
        return text.substring(start)
    }
}
