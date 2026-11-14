package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual ACP drain, byte cap and completion ownership with independent fixtures. */
class AcpTerminalUnicodeIndependentF152Test {
    private class ChunkedInput(private val data: ByteArray, private val chunk: Int, private val zeroReads: Int = 0) : InputStream() {
        private var at = 0
        private var zeros = zeroReads
        override fun read(): Int = error("Bulk reads only")
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (zeros-- > 0) return 0
            if (at == data.size) return -1
            val size = minOf(length, chunk, data.size - at)
            data.copyInto(target, offset, at, at + size); at += size
            return size
        }
    }
    private class GatedInput(private val first: ByteArray, private val last: ByteArray, private val failure: RuntimeException? = null) : InputStream() {
        private var at = 0
        private var second = false
        val reached = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun read(): Int = error("Bulk reads only")
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (!second && at == first.size) {
                reached.countDown()
                check(resume.await(12, TimeUnit.SECONDS)) { "Independent fixture gate expired" }
                failure?.let { throw it }
                second = true; at = 0
            }
            val bytes = if (second) last else first
            if (at == bytes.size) return -1
            val size = minOf(length, bytes.size - at)
            bytes.copyInto(target, offset, at, at + size); at += size
            return size
        }
        override fun close() { closed.countDown() }
    }
    private class ErrorInput(private val first: ByteArray) : InputStream() {
        private var readFirst = false
        override fun read(): Int = error("Bulk reads only")
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (readFirst) throw IOException("independent read failure")
            readFirst = true
            first.copyInto(target, offset)
            return first.size
        }
    }
    private class Child(override val stdout: InputStream, override val stderr: InputStream = ByteArrayInputStream(byteArrayOf())) : HostedStdioProcess {
        override val stdin = ByteArrayOutputStream()
        private val result = CompletableDeferred<Int>()
        @Volatile private var code: Int? = null
        val closes = AtomicInteger()
        override val isAlive get() = code == null
        override val exitCode get() = code
        override suspend fun awaitExit() = result.await()
        fun finish(value: Int = 0) { code = value; result.complete(value) }
        override fun close() { closes.incrementAndGet(); finish(code ?: 143) }
    }
    private class Fixture {
        val root = Files.createTempDirectory("f152-independent-unicode-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gates = mutableListOf<GatedInput>()
        val children = mutableListOf<HostedStdioProcess>()
        private var next = 0
        private val mapper = WorkspacePathMapper(root, root.canonicalPath)
        private val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = "/bin/$command"
            override suspend fun pathMapper() = mapper
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?) = children[next++]
        }
        val controller = AcpTerminalController(host, mapper, ApprovalManager(),
            PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), AcpPermissionLedger(),
            WorkspaceAgentAccessPolicy().apply { setMode(WorkspaceAgentMode.EDIT) }, scope).apply { bindApprovalSession("independent-owner") }
        suspend fun create(child: HostedStdioProcess, cap: Int? = 4096): JsonObject {
            children += child
            return withTimeout(3_000) { controller.create(buildJsonObject {
                put("command", "cat"); put("sessionId", "independent-session"); if (cap != null) put("outputByteLimit", cap)
            }) }
        }
        suspend fun completed(id: JsonObject): JsonObject {
            withTimeout(6_000) { controller.waitForExit(id) }
            return controller.output(id)
        }
        suspend fun capture(data: ByteArray, cap: Int? = 4096, chunk: Int = 4096, zeroReads: Int = 0): JsonObject {
            val child = Child(ChunkedInput(data, chunk, zeroReads)).apply { finish() }
            val id = create(child, cap)
            return completed(id).also { controller.release(id) }
        }
        fun gated(first: ByteArray, last: ByteArray, failure: RuntimeException? = null) =
            GatedInput(first, last, failure).also { gates += it }
        suspend fun close() {
            gates.forEach { it.resume.countDown() }
            runCatching { controller.close() }
            children.forEach { runCatching { it.close() } }
            scope.cancel(); withTimeout(3_000) { scope.coroutineContext.job.join() }
            // Keep isolated fixture files for evidence. No user or repository files are removed.
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try { withTimeout(30_000) { fixture.block() } } finally { fixture.close() }
    }
    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        assertTrue("Expected actual InputStream boundary", latch.await(3, TimeUnit.SECONDS))
    }
    private fun JsonObject.text() = getValue("output").jsonPrimitive.content
    private fun JsonObject.truncated() = getValue("truncated").jsonPrimitive.boolean
    private fun JsonObject.code() = getValue("exitStatus").jsonObject.getValue("exitCode").jsonPrimitive.int
    private fun suffixByCodePoint(text: String, budget: Int): String {
        var start = text.length
        var bytes = 0
        while (start > 0) {
            val scalar = text.codePointBefore(start)
            val width = when { scalar < 0x80 -> 1; scalar < 0x800 -> 2; scalar < 0x10000 -> 3; else -> 4 }
            if (bytes + width > budget) break
            bytes += width; start -= Character.charCount(scalar)
        }
        return text.substring(start)
    }
    private fun wellFormed(text: String) {
        var at = 0
        while (at < text.length) {
            val char = text[at++]
            if (Character.isHighSurrogate(char)) {
                assertTrue("Complete surrogate pair required", at < text.length && Character.isLowSurrogate(text[at])); at++
            } else assertFalse("Isolated low surrogate", Character.isLowSurrogate(char))
        }
    }

    @Test(timeout = 35_000) fun minimumCapPreservesExactSupplementaryTailAcrossOldCharBoundaries() = fixture {
        println("controllerOrigin=" + AcpTerminalController::class.java.protectionDomain.codeSource.location)
        println("decoderOrigin=" + Utf8StreamDecoder::class.java.protectionDomain.codeSource.location)
        for (prefix in listOf(4095, 8191, 12287, 16383, 20479, 28671)) {
            for (scalar in listOf("😀", "𐀀", "\uDBFF\uDFFF")) {
                val payload = "a".repeat(prefix) + scalar
                val result = capture(payload.toByteArray())
                assertEquals("prefix=$prefix scalar=$scalar", suffixByCodePoint(payload, 4096), result.text())
                assertTrue(result.truncated()); wellFormed(result.text())
            }
        }
    }

    @Test(timeout = 35_000) fun realCatPreservesExactMinimumAndDefaultByteTails() = fixture {
        for ((prefix, limit) in listOf(12287 to 4096, 266239 to null)) {
            val payload = "b".repeat(prefix) + "😀"
            val file = root.resolve("cat-$prefix.txt").apply { writeText(payload) }
            val process = ProcessBuilder("/bin/cat", file.canonicalPath).directory(root).start()
            val child = object : HostedStdioProcess {
                override val stdin get() = process.outputStream
                override val stdout get() = process.inputStream
                override val stderr get() = process.errorStream
                override val isAlive get() = process.isAlive
                override val exitCode get() = if (process.isAlive) null else process.exitValue()
                override suspend fun awaitExit() = withContext(Dispatchers.IO) { runInterruptible { process.waitFor() } }
                override fun close() { if (process.isAlive) process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS) }
            }
            val id = create(child, limit)
            val result = completed(id)
            assertEquals(suffixByCodePoint(payload, limit ?: 262144), result.text())
            assertEquals(limit ?: 262144, result.text().toByteArray().size)
            assertEquals(0, result.code()); assertTrue(result.truncated()); wellFormed(result.text())
            controller.release(id)
        }
    }

    @Test(timeout = 35_000) fun everyUtf8WidthSurvivesOneByteAndIrregularReadsWithoutNormalization() = fixture {
        val payload = "\uFEFFascii¢€😀𐀀\uDBFF\uDFFF\u0000\r\ne\u0301👩‍💻"
        for (chunk in listOf(1, 2, 3, 5, 7, 4095, 4096, 8192)) {
            val result = capture(payload.toByteArray(), chunk = chunk)
            assertEquals("chunk=$chunk", payload, result.text())
            assertFalse(result.truncated()); wellFormed(result.text())
        }
    }

    @Test(timeout = 35_000) fun leftCapDropsOnlyThePartialLeadingCodePoint() = fixture {
        for (scalar in listOf("¢", "€", "😀")) for (tail in 4091..4096) {
            val payload = "discard".repeat(700) + scalar + "x".repeat(tail)
            val result = capture(payload.toByteArray(), chunk = 127)
            assertEquals("scalar=$scalar tail=$tail", suffixByCodePoint(payload, 4096), result.text())
            assertTrue(result.text().toByteArray().size <= 4096)
            assertTrue(result.truncated()); wellFormed(result.text())
        }
    }

    @Test(timeout = 35_000) fun zeroLengthReadsAreNotEofAndCannotFlushPendingBytes() = fixture {
        val payload = "retained:€😀"
        val result = capture(payload.toByteArray(), chunk = 1, zeroReads = 4)
        assertEquals(payload, result.text()); assertFalse(result.truncated())
        val reads = AtomicInteger()
        val stream = object : InputStream() {
            override fun read(): Int = error("Bulk only")
            override fun read(target: ByteArray, offset: Int, length: Int): Int = when (reads.getAndIncrement()) {
                0 -> { target[offset] = 0xf0.toByte(); target[offset + 1] = 0x9f.toByte(); 2 }
                1, 2 -> 0
                3 -> { target[offset] = 0x98.toByte(); target[offset + 1] = 0x80.toByte(); 2 }
                else -> -1
            }
        }
        val id = create(Child(stream).apply { finish() })
        assertEquals("😀", completed(id).text())
    }

    @Test(timeout = 35_000) fun malformedAndIncompleteEofUseReplacementNotQuestionMarks() = fixture {
        val cases = listOf(
            byteArrayOf(0xc0.toByte(), 0xaf.toByte()) to "��",
            byteArrayOf(0xe2.toByte(), 0x28, 0xa1.toByte()) to "�(�",
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()) to "�",
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()) to "����",
            byteArrayOf(0xf0.toByte(), 0x9f.toByte(), 0x98.toByte()) to "�",
            byteArrayOf(0xe2.toByte(), 0x82.toByte()) to "�",
            byteArrayOf(0xc2.toByte()) to "�",
            byteArrayOf(0x80.toByte(), 0x80.toByte()) to "��",
        )
        for ((bytes, expected) in cases) for (chunk in 1..4) {
            val result = capture("first:".toByteArray() + bytes, chunk = chunk)
            assertEquals("chunk=$chunk bytes=${bytes.toList()}", "first:$expected", result.text())
            assertFalse(result.truncated()); wellFormed(result.text())
        }
    }

    @Test(timeout = 35_000) fun stdoutAndStderrCannotCompleteEachOthersPartialSequence() = fixture {
        val out = gated("OUT:".toByteArray() + byteArrayOf(0xf0.toByte(), 0x9f.toByte()), byteArrayOf(0x98.toByte(), 0x80.toByte()))
        val err = gated("ERR:".toByteArray() + byteArrayOf(0xe2.toByte(), 0x82.toByte()), byteArrayOf(0xac.toByte()))
        val child = Child(out, err)
        val id = create(child)
        await(out.reached); await(err.reached)
        val before = controller.output(id)
        assertFalse(before.text().contains('�')); assertNull(before["exitStatus"])
        out.resume.countDown(); err.resume.countDown(); child.finish(19)
        val after = completed(id)
        // Stream scheduling is intentionally not constrained to a particular interleaving.
        assertEquals(1, Regex("OUT:").findAll(after.text()).count())
        assertEquals(1, Regex("ERR:").findAll(after.text()).count())
        assertEquals(1, Regex("😀").findAll(after.text()).count())
        assertEquals(1, Regex("€").findAll(after.text()).count())
        assertEquals("OUT:ERR:😀€".length, after.text().length)
        assertEquals(19, after.code()); assertFalse(after.truncated()); wellFormed(after.text())
    }

    @Test(timeout = 35_000) fun incompleteEofFlushIsIncludedBeforeWaitReturns() = fixture {
        val out = gated("prefix:".toByteArray() + byteArrayOf(0xf0.toByte(), 0x9f.toByte()), byteArrayOf())
        val child = Child(out).apply { finish(17) }
        val id = create(child)
        await(out.reached)
        assertNull(controller.output(id)["exitStatus"])
        out.resume.countDown()
        val final = completed(id)
        assertEquals("prefix:�", final.text()); assertFalse(final.truncated()); assertEquals(17, final.code())
        await(out.closed); assertEquals(final, controller.output(id))
    }

    @Test(timeout = 35_000) fun readFailureDoesNotMasqueradeAsEofFlush() = fixture {
        val id = create(Child(ErrorInput("prefix:".toByteArray() + byteArrayOf(0xf0.toByte(), 0x9f.toByte()))).apply { finish(7) })
        val final = completed(id)
        assertEquals("prefix:\n[terminal output incomplete: independent read failure]\n", final.text())
        assertFalse(final.text().contains('�')); assertTrue(final.truncated()); assertEquals(7, final.code())
    }

    @Test(timeout = 35_000) fun cancelledReaderDoesNotFlushOrInventIncompleteOutputDiagnostic() = fixture {
        val out = gated("prefix:".toByteArray() + byteArrayOf(0xe2.toByte(), 0x82.toByte()), byteArrayOf(), CancellationException("fixture read cancelled"))
        val child = Child(out).apply { finish() }
        val id = create(child)
        await(out.reached); out.resume.countDown()
        val final = completed(id)
        assertEquals("prefix:", final.text()); assertFalse(final.truncated()); assertEquals(0, final.code())
    }

    @Test(timeout = 35_000) fun postExitDelayedFinalScalarIsJoinedBeforeCompletion() = fixture {
        val out = gated("late:".toByteArray() + byteArrayOf(0xf0.toByte(), 0x9f.toByte()), byteArrayOf(0x98.toByte(), 0x80.toByte()))
        val child = Child(out).apply { finish(31) }
        val id = create(child)
        await(out.reached)
        val waiter = scope.async(start = CoroutineStart.UNDISPATCHED) { controller.waitForExit(id) }
        assertFalse(waiter.isCompleted); assertNull(controller.output(id)["exitStatus"])
        out.resume.countDown()
        withTimeout(2_000) { waiter.await() }
        val final = controller.output(id)
        assertEquals("late:😀", final.text()); assertEquals(31, final.code()); assertFalse(final.truncated())
    }

    @Test(timeout = 35_000) fun drainTimeoutFreezesSnapshotAgainstLateDecodedBytes() = fixture {
        val out = gated("prefix:".toByteArray() + byteArrayOf(0xf0.toByte(), 0x9f.toByte()), byteArrayOf(0x98.toByte(), 0x80.toByte()))
        val child = Child(out).apply { finish(29) }
        val id = create(child)
        await(out.reached)
        val frozen = completed(id)
        assertTrue(frozen.truncated()); assertTrue(frozen.text().contains("a pipe remained open after process exit"))
        assertFalse(frozen.text().contains("😀")); assertFalse(frozen.text().contains('�'))
        assertEquals(29, frozen.code()); assertEquals(1, child.closes.get())
        out.resume.countDown(); await(out.closed)
        assertEquals(frozen, controller.output(id))
    }

    @Test(timeout = 35_000) fun releaseKeepsPreviewImmutableAndCancelsAnExistingWaiter() = fixture {
        val out = gated("preview:".toByteArray() + byteArrayOf(0xf0.toByte(), 0x9f.toByte()), byteArrayOf(0x98.toByte(), 0x80.toByte()))
        val child = Child(out)
        val id = create(child)
        await(out.reached)
        val key = id.getValue("terminalId").jsonPrimitive.content
        val before = controller.preview(key)
        val waiter = scope.async(start = CoroutineStart.UNDISPATCHED) { controller.waitForExit(id) }
        controller.release(id)
        assertTrue(runCatching { waiter.await() }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { controller.output(id) }.isFailure)
        assertEquals(before, controller.preview(key))
        out.resume.countDown(); await(out.closed)
        assertEquals(before, controller.preview(key))
        controller.bindApprovalSession("replacement-owner"); assertNull(controller.preview(key))
    }

    @Test(timeout = 35_000) fun cancelAndRebindRetireDecoderStateWithoutContaminatingNextSession() = fixture {
        val out = gated("old:".toByteArray() + byteArrayOf(0xf0.toByte(), 0x9f.toByte()), byteArrayOf(0x98.toByte(), 0x80.toByte()))
        val child = Child(out)
        val id = create(child)
        await(out.reached)
        val waiter = scope.async(start = CoroutineStart.UNDISPATCHED) { controller.waitForExit(id) }
        controller.cancelActive().getOrThrow()
        assertTrue(runCatching { waiter.await() }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { controller.output(id) }.isFailure)
        controller.bindApprovalSession("replacement-owner")
        val nextId = create(Child(ChunkedInput("new:€😀".toByteArray(), 1)).apply { finish(11) })
        val final = completed(nextId)
        assertEquals("new:€😀", final.text()); assertEquals(11, final.code()); assertFalse(final.truncated())
        out.resume.countDown(); await(out.closed)
        assertEquals(final, controller.output(nextId))
    }
}
