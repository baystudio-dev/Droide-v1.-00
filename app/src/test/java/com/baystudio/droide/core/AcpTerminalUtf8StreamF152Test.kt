package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises stream decoding through the production ACP create/output/wait/release routes. */
class AcpTerminalUtf8StreamF152Test {
    private class Chunked(bytes: ByteArray, private val maxRead: Int) : ByteArrayInputStream(bytes) {
        override fun read(bytes: ByteArray, off: Int, len: Int) = super.read(bytes, off, minOf(len, maxRead))
    }

    private class Fixture(
        out: InputStream,
        err: InputStream = ByteArrayInputStream(byteArrayOf()),
        completed: Boolean = true,
        private val code: Int = 0,
    ) {
        private val root = Files.createTempDirectory("f152-acp-stream-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val exit = CompletableDeferred<Int>().apply { if (completed) complete(code) }
        private val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = "/fixture/$command"
            override suspend fun pathMapper() = WorkspacePathMapper(root, root.canonicalPath)
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?) = object : HostedStdioProcess {
                override val stdin = ByteArrayOutputStream()
                override val stdout = out
                override val stderr = err
                override val isAlive get() = !exit.isCompleted
                override val exitCode get() = if (exit.isCompleted) code else null
                override suspend fun awaitExit() = exit.await()
                override fun close() { out.close(); err.close(); exit.complete(code) }
            }
        }
        val controller = AcpTerminalController(host, WorkspacePathMapper(root, root.canonicalPath), ApprovalManager(),
            PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), AcpPermissionLedger(),
            WorkspaceAgentAccessPolicy().apply { setMode(WorkspaceAgentMode.EDIT) }, scope).apply { bindApprovalSession("fixture") }

        suspend fun create(limit: Int = 4096) = controller.create(buildJsonObject {
            put("command", "fixture"); put("sessionId", "fixture"); put("outputByteLimit", limit)
        })
        suspend fun finish(id: JsonObject): JsonObject {
            assertEquals(code, withTimeout(4_000) { controller.waitForExit(id) }.getValue("exitCode").jsonPrimitive.int)
            return controller.output(id)
        }
        suspend fun close() {
            controller.close(); scope.cancel()
            withTimeout(2_000) { scope.coroutineContext.job.join() }
            // Keep fixture directories for evidence; no user/evidence deletion.
        }
    }

    private suspend fun capture(out: InputStream, err: InputStream = ByteArrayInputStream(byteArrayOf()), limit: Int = 4096): JsonObject {
        val fixture = Fixture(out, err)
        try { return fixture.finish(fixture.create(limit)) }
        finally { fixture.close() }
    }
    private fun text(result: JsonObject) = result.getValue("output").jsonPrimitive.content

    @Test(timeout = 20_000) fun arbitraryByteBoundariesPreserveExactUnicodeTail() = runBlocking {
        val suffix = "😀中é𐐷"
        val payload = "a".repeat(8191) + suffix
        for (chunk in listOf(1, 2, 3, 7, 4095, 4096, 8192)) {
            val result = capture(Chunked(payload.toByteArray(), chunk))
            assertEquals("chunk=$chunk", "a".repeat(4096 - suffix.toByteArray().size) + suffix, text(result))
            assertTrue(result.getValue("truncated").jsonPrimitive.boolean)
        }
    }

    @Test(timeout = 20_000) fun malformedAndIncompleteFinalSequencesRetainDecoderReplacementPolicy() = runBlocking {
        val cases = listOf(
            byteArrayOf(0x61, 0x80.toByte(), 0x62),
            byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf0.toByte(), 0x9f.toByte(), 0x98.toByte()),
            byteArrayOf(0xf0.toByte(), 0x9f.toByte(), 0x98.toByte(), 0x80.toByte(), 0xe4.toByte(), 0xb8.toByte()),
        )
        for (bytes in cases) for (chunk in 1..4) {
            val result = capture(Chunked(bytes, chunk))
            assertEquals("chunk=$chunk bytes=${bytes.toList()}", bytes.toString(Charsets.UTF_8), text(result))
            assertFalse(result.getValue("truncated").jsonPrimitive.boolean)
        }
    }

    @Test(timeout = 10_000) fun stdoutAndStderrMaintainIndependentPendingUtf8Sequences() = runBlocking {
        val out = "😀".repeat(50).toByteArray()
        val err = "𐐷".repeat(50).toByteArray()
        val result = capture(Chunked(out, 1), Chunked(err, 3))
        val codePoints = text(result).codePoints().toArray().toList()
        assertEquals(100, codePoints.size)
        assertEquals(50, codePoints.count { it == 0x1f600 })
        assertEquals(50, codePoints.count { it == 0x10437 })
        assertFalse(result.getValue("truncated").jsonPrimitive.boolean)
    }

    @Test(timeout = 10_000) fun byteCapDropsIncompleteLeadingCharactersOnly() = runBlocking {
        for (prefix in 4088..4100) {
            val original = "中😀" + "a".repeat(prefix) + "é"
            val bytes = original.toByteArray()
            var start = (bytes.size - 4096).coerceAtLeast(0)
            while (start < bytes.size && (bytes[start].toInt() and 0xc0) == 0x80) start++
            val result = capture(Chunked(bytes, 7))
            assertEquals(bytes.copyOfRange(start, bytes.size).toString(Charsets.UTF_8), text(result))
            assertTrue(text(result).toByteArray().size <= 4096)
        }
    }

    @Test(timeout = 5_000) fun zeroByteReadDoesNotFinishPendingUtf8BeforeActualEof() = runBlocking {
        val bytes = "😀中é".toByteArray()
        val input = object : InputStream() {
            private var offset = 0
            private var zero = false
            override fun read(): Int = error("Use the byte-array read")
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                zero = !zero
                if (zero) return 0
                if (offset == bytes.size) return -1
                buffer[off] = bytes[offset++]
                return 1
            }
        }
        val result = capture(input)
        assertEquals("😀中é", text(result))
        assertFalse(result.getValue("truncated").jsonPrimitive.boolean)
    }

    @Test(timeout = 5_000) fun readFailureDoesNotFlushPendingBytesAsInventedEof() = runBlocking {
        val input = object : InputStream() {
            private var first = true
            override fun read(): Int = error("Use the byte-array read")
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                if (!first) throw IOException("broken UTF-8 output pipe")
                first = false
                val bytes = "before ".toByteArray() + byteArrayOf(0xf0.toByte(), 0x9f.toByte())
                bytes.copyInto(buffer, off)
                return bytes.size
            }
        }
        val result = capture(input)
        assertEquals("before \n[terminal output incomplete: broken UTF-8 output pipe]\n", text(result))
        assertTrue(result.getValue("truncated").jsonPrimitive.boolean)
        assertEquals(0, result.getValue("exitStatus").jsonObject.getValue("exitCode").jsonPrimitive.int)
    }

    @Test(timeout = 8_000) fun cancelAndReleaseDoNotPublishLatePendingOutputOrInventExit() = runBlocking {
        for (release in listOf(false, true)) {
            val reading = CountDownLatch(1)
            val gate = CountDownLatch(1)
            val drained = CountDownLatch(1)
            val input = object : InputStream() {
                private var stage = 0
                override fun read(): Int = error("Use the byte-array read")
                override fun read(buffer: ByteArray, off: Int, len: Int): Int = when (stage++) {
                    0 -> {
                        val bytes = "before ".toByteArray() + byteArrayOf(0xf0.toByte(), 0x9f.toByte())
                        bytes.copyInto(buffer, off); bytes.size
                    }
                    1 -> {
                        reading.countDown(); check(gate.await(5, TimeUnit.SECONDS))
                        buffer[off] = 0x98.toByte(); buffer[off + 1] = 0x80.toByte()
                        drained.countDown(); 2
                    }
                    else -> -1
                }
                override fun close() {} // Model a blocking read that ignores a close/cancel until its gate opens.
            }
            val fixture = Fixture(input, completed = false)
            try {
                val id = fixture.create()
                assertTrue(reading.await(1, TimeUnit.SECONDS))
                val terminalId = id.getValue("terminalId").jsonPrimitive.content
                val before = fixture.controller.preview(terminalId)
                assertEquals("before ", before)
                assertFalse(fixture.controller.output(id).containsKey("exitStatus"))
                val waiter = async(start = CoroutineStart.UNDISPATCHED) { fixture.controller.waitForExit(id) }
                if (release) fixture.controller.release(id) else fixture.controller.cancelActive().getOrThrow()
                try { withTimeout(1_000) { waiter.await() }; fail("Retired terminal must cancel its wait") }
                catch (_: CancellationException) { assertTrue(waiter.isCancelled) }
                gate.countDown()
                assertTrue(drained.await(1, TimeUnit.SECONDS))
                fixture.scope.coroutineContext.job.children.toList().joinAll()
                assertEquals(if (release) before else null, fixture.controller.preview(terminalId))
                try { fixture.controller.output(id); fail("Retired terminal must no longer be addressable") }
                catch (_: IllegalStateException) {}
            } finally { gate.countDown(); fixture.close() }
        }
    }

    @Test(timeout = 5_000) fun repeatedZeroReadsRemainCancellableWithoutFabricatingCompletion() = runBlocking {
        val reading = CountDownLatch(1)
        val stop = AtomicBoolean(false)
        val input = object : InputStream() {
            override fun read(): Int = error("Use the byte-array read")
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                reading.countDown()
                return if (stop.get()) -1 else 0
            }
            override fun close() {}
        }
        val fixture = Fixture(input, completed = false)
        try {
            val id = fixture.create()
            assertTrue(reading.await(1, TimeUnit.SECONDS))
            assertFalse(fixture.controller.output(id).containsKey("exitStatus"))
            fixture.controller.cancelActive().getOrThrow()
            withTimeout(1_000) { fixture.scope.coroutineContext.job.children.toList().joinAll() }
            assertNull(fixture.controller.preview(id.getValue("terminalId").jsonPrimitive.content))
        } finally { stop.set(true); fixture.close() }
    }
}
