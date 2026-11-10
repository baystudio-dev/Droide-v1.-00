package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class OwnedResponseContentStoreTest {
    private val owner = OwnedResponseContentStore.Owner("session", "run", "call")
    private val unlimited = OwnedResponseContentStore.HeadroomAdmission({ 1L shl 40 }, 8192)
    private fun drain(input: InputStream) { val bytes = ByteArray(1024); while (input.read(bytes) != -1) {} }
    private fun root() = Files.createTempDirectory("owned-response-test-")
    private fun clean(root: Path) { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    private inline fun fixture(block: (Path) -> Unit) { val p = root(); try { block(p) } finally { clean(p) } }
    private fun read(session: OwnedResponseContentStore.Session, handle: OwnedResponseContentStore.Handle): String =
        session.openPages(handle).use { pages -> buildString { while (true) append(pages.readPage(17) ?: break) } }

    @Test fun smallAndSpilledContentSurviveAuthorizedRestartWithExactDigest() = fixture { root ->
        val store = OwnedResponseContentStore(root, unlimited, inlineThreshold = 16)
        val session = store.authorize(owner)
        for (expected in listOf("hello", "abc🌏".repeat(10_000))) {
            val content = session.begin(OwnedResponseContentStore.Kind.ANSWER).use { writer ->
                expected.chunked(3).forEach(writer::append)
                writer.sealCompleted(::drain).content
            }
            assertEquals(expected.toByteArray().size.toLong(), content.byteLength)
            assertEquals(expected.length.toLong(), content.utf16Length)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(expected.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }, content.sha256)
            assertEquals(expected.length > 16, content is OwnedResponseContentStore.Content.FileBacked)
            val restarted = OwnedResponseContentStore(root, unlimited, 16).authorize(owner)
            val restored = OwnedResponseContentStore.Handle.parse(content.handle.serialized)
            assertEquals(expected, read(restarted, restored))
            assertEquals(OwnedResponseContentStore.State.COMPLETE, restarted.openCompleted(restored).content.state)
        }
    }

    @Test fun utf8EveryByteBoundaryAndUtf16EveryCodeUnitAreLossless() = fixture { root ->
        val session = OwnedResponseContentStore(root, unlimited, 3).authorize(owner)
        val expected = "A🌏é漢字\u0000\nZ"
        val fromBytes = session.begin(OwnedResponseContentStore.Kind.ANSWER).use { writer ->
            expected.toByteArray().forEach { writer.appendUtf8(byteArrayOf(it)) }; writer.sealCompleted(::drain).content
        }
        val fromChars = session.begin(OwnedResponseContentStore.Kind.ANSWER).use { writer ->
            expected.forEach { writer.append(it.toString()) }; writer.sealCompleted(::drain).content
        }
        assertEquals(expected, read(session, fromBytes.handle)); assertEquals(expected, read(session, fromChars.handle))
        assertEquals(fromBytes.sha256, fromChars.sha256)
        session.openPages(fromChars.handle).use { pages ->
            while (true) { val page = pages.readPage(2) ?: break; assertFalse(Character.isHighSurrogate(page.last())); assertFalse(Character.isLowSurrogate(page.first())) }
        }
    }

    @Test fun malformedAndIncompleteUnicodeNeverBecomeCompleted() = fixture { root ->
        val session = OwnedResponseContentStore(root, unlimited, 1).authorize(owner)
        for (bytes in listOf(byteArrayOf(0xc0.toByte(), 0x80.toByte()), byteArrayOf(0xf0.toByte(), 0x9f.toByte()))) {
            session.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use { writer ->
                assertThrows(Exception::class.java) { writer.appendUtf8(bytes); writer.sealCompleted(::drain) }
                assertThrows(IllegalStateException::class.java) { writer.sealCompleted(::drain) }
            }
        }
        session.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use { writer ->
            writer.append("\ud83c")
            assertThrows(IllegalStateException::class.java) { writer.sealCompleted(::drain) }
        }
    }

    @Test fun incompleteAndUnvalidatedContentCannotBeOpenedCompleted() = fixture { root ->
        val session = OwnedResponseContentStore(root, unlimited, 1).authorize(owner)
        val incomplete = session.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use { writer ->
            writer.append("{\"path\":"); writer.sealIncomplete()
        }
        assertEquals("{\"path\":", read(session, incomplete.handle))
        assertThrows(IllegalStateException::class.java) { session.openCompleted(incomplete.handle) }
        session.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use { writer ->
            writer.append("{}")
            assertThrows(IllegalStateException::class.java) { writer.sealCompleted { it.read() } }
            assertThrows(Exception::class.java) { session.openCompleted(writer.recoveryHandle) }
        }
    }

    @Test fun validatorFailurePublishesNothingAndRecoveryIsOnlyIncomplete() = fixture { root ->
        val session = OwnedResponseContentStore(root, unlimited, 1).authorize(owner)
        val handle = session.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use { writer ->
            writer.append("not-json")
            assertThrows(IOException::class.java) { writer.sealCompleted { drain(it); throw IOException("Invalid arguments") } }
            writer.recoveryHandle
        }
        assertTrue(session.hasRecoverableStage(handle))
        val partial = session.recoverIncomplete(handle)
        assertEquals(OwnedResponseContentStore.State.INCOMPLETE, partial.state)
        assertEquals("not-json", read(session, handle))
        assertThrows(IllegalStateException::class.java) { session.openCompleted(handle) }
    }

    @Test fun incompleteUtf8TailExportsExactlyAndUtf16SurrogateCannotSilentlyDisappear() = fixture { root ->
        val session = OwnedResponseContentStore(root, unlimited, 1).authorize(owner)
        val raw = byteArrayOf(65, 0xf0.toByte(), 0x9f.toByte())
        val partial = session.begin(OwnedResponseContentStore.Kind.ANSWER).use { writer ->
            raw.forEach { writer.appendUtf8(byteArrayOf(it)) }
            writer.sealIncomplete()
        }
        session.openBytes(partial.handle).use { assertArrayEquals(raw, it.readBytes()) }
        assertEquals(-1L, partial.utf16Length)
        assertThrows(java.nio.charset.MalformedInputException::class.java) { read(session, partial.handle) }
        assertThrows(IllegalStateException::class.java) { session.openCompleted(partial.handle) }
        session.begin(OwnedResponseContentStore.Kind.ANSWER).use { writer ->
            writer.append("A\ud83c")
            assertThrows(IllegalStateException::class.java) { writer.sealIncomplete() }
            assertThrows(Exception::class.java) { session.open(writer.recoveryHandle) }
        }
    }

    @Test fun recoveryChecksExpectedKindBeforePublishingAnyManifest() = fixture { root ->
        val session = OwnedResponseContentStore(root, unlimited, 1).authorize(owner)
        val handle = session.begin(OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION).use { writer ->
            writer.append("private continuation"); writer.recoveryHandle
        }
        assertThrows(IllegalStateException::class.java) {
            session.recoverIncomplete(handle, OwnedResponseContentStore.Kind.ANSWER)
        }
        assertTrue(session.hasRecoverableStage(handle))
        assertFalse(Files.exists(root.resolve(handle.id + ".meta")))
        assertFalse(Files.exists(root.resolve(handle.id + ".blob")))
        assertTrue(Files.exists(root.resolve(handle.id + ".part")))
        assertEquals(OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION,
            session.recoverIncomplete(handle, OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION).kind)
    }

    @Test fun authorizationSeparatesSessionRunAndCallAndRejectsForgedToken() = fixture { root ->
        val store = OwnedResponseContentStore(root, unlimited)
        val session = store.authorize(owner)
        val content = session.begin(OwnedResponseContentStore.Kind.ANSWER).use { it.append("secret"); it.sealCompleted(::drain).content }
        listOf(owner.copy(sessionId = "other"), owner.copy(runId = "other"), owner.copy(callId = "other")).forEach {
            assertThrows(IllegalStateException::class.java) { store.authorize(it).open(content.handle) }
        }
        val pieces = content.handle.serialized.split('.')
        val forged = OwnedResponseContentStore.Handle.parse(pieces.take(2).joinToString(".") + "." + "A".repeat(43))
        assertThrows(IllegalStateException::class.java) { session.open(forged) }
        assertThrows(IllegalArgumentException::class.java) { OwnedResponseContentStore.Handle.parse("../../project/file") }
    }

    @Test fun corruptionDetectedBeforeAnyReadOrValidatorSideEffect() = fixture { root ->
        val session = OwnedResponseContentStore(root, unlimited, 1).authorize(owner)
        val content = session.begin(OwnedResponseContentStore.Kind.ANSWER).use { it.append("original"); it.sealCompleted(::drain).content }
        val file = root.resolve(content.handle.id + ".blob")
        Files.write(file, "modified".toByteArray(), StandardOpenOption.TRUNCATE_EXISTING)
        assertThrows(IllegalStateException::class.java) { session.openBytes(content.handle) }
        assertThrows(IllegalStateException::class.java) { session.openCompleted(content.handle) }
    }

    @Test fun shortWritesPreserveContentAndZeroWritesFailClosed() = fixture { root ->
        val session = OwnedResponseContentStore(root, unlimited, 1, channelWrite = { channel, buffer ->
            val limit = buffer.limit(); buffer.limit(minOf(limit, buffer.position() + 3))
            try { channel.write(buffer) } finally { buffer.limit(limit) }
        }).authorize(owner)
        val content = session.begin(OwnedResponseContentStore.Kind.ANSWER).use { it.append("short".repeat(1000)); it.sealCompleted(::drain).content }
        assertEquals("short".repeat(1000), read(session, content.handle))
        val zero = OwnedResponseContentStore(root, unlimited, channelWrite = { _, _ -> 0 }).authorize(owner)
        assertThrows(IOException::class.java) { zero.begin(OwnedResponseContentStore.Kind.ANSWER) }
    }

    @Test fun diskAdmissionPrecedesWriteAndStorageFailureCannotPublishCompletion() = fixture { root ->
        var denied = false; var writes = 0
        val admission = object : OwnedResponseContentStore.DiskAdmission {
            override fun reserve(bytes: Long): AutoCloseable { if (denied) throw IOException("ENOSPC"); return AutoCloseable {} }
        }
        val session = OwnedResponseContentStore(root, admission, 1, channelWrite = { c, b -> writes++; c.write(b) }).authorize(owner)
        session.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use { writer ->
            val previous = writes; denied = true
            assertThrows(IOException::class.java) { writer.append("more than memory") }
            assertEquals(previous, writes)
            assertThrows(Exception::class.java) { session.openCompleted(writer.recoveryHandle) }
        }
    }

    @Test fun injectedPartialEnospcIsRecoverableButNeverExecutable() = fixture { root ->
        var fail = false
        val session = OwnedResponseContentStore(root, unlimited, 0, channelWrite = { c, b ->
            if (fail) { val limit = b.limit(); b.limit(minOf(limit, b.position() + 2)); c.write(b); b.limit(limit); throw IOException("ENOSPC") }
            c.write(b)
        }).authorize(owner)
        val handle = session.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use { writer ->
            fail = true
            assertThrows(IOException::class.java) { writer.append("abcdef") }
            writer.recoveryHandle
        }
        fail = false
        val result = session.recoverIncomplete(handle)
        assertEquals(2L, result.byteLength); assertEquals("ab", read(session, handle))
        assertThrows(IllegalStateException::class.java) { session.openCompleted(handle) }
    }

    @Test fun cancellationStopsWritesAndKeepsPublishedContent() = fixture { root ->
        var cancelled = false
        val store = OwnedResponseContentStore(root, unlimited, 1, cancelled = { cancelled })
        val session = store.authorize(owner)
        val content = session.begin(OwnedResponseContentStore.Kind.ANSWER).use { it.append("keep"); it.sealCompleted(::drain).content }
        session.begin(OwnedResponseContentStore.Kind.ANSWER).use { writer ->
            writer.append("partial"); cancelled = true
            assertThrows(CancellationException::class.java) { writer.append("never") }
        }
        cancelled = false
        assertEquals("keep", read(session, content.handle))
        assertThrows(IllegalStateException::class.java) { session.discardStage(content.handle) }
        assertEquals("keep", read(session, content.handle))
    }

    @Test fun activeWriterCannotBeRecoveredOrDiscardedByAnotherStore() = fixture { root ->
        val session = OwnedResponseContentStore(root, unlimited, 1).authorize(owner)
        val other = OwnedResponseContentStore(root, unlimited, 1).authorize(owner)
        session.begin(OwnedResponseContentStore.Kind.ANSWER).use { writer ->
            writer.append("active")
            assertThrows(IllegalStateException::class.java) { other.recoverIncomplete(writer.recoveryHandle) }
            assertThrows(IllegalStateException::class.java) { other.discardStage(writer.recoveryHandle) }
        }
    }

    @Test fun recoveryAfterClosedWriterRequiresExplicitDiscardAndDoesNotTouchProjectFiles() = fixture { root ->
        val project = root.resolve("project.txt"); Files.write(project, "user".toByteArray())
        val session = OwnedResponseContentStore(root, unlimited, 1).authorize(owner)
        val handle = session.begin(OwnedResponseContentStore.Kind.ANSWER).use { it.append("partial"); it.recoveryHandle }
        assertTrue(session.hasRecoverableStage(handle))
        session.discardStage(handle)
        assertFalse(session.hasRecoverableStage(handle))
        assertEquals("user", String(Files.readAllBytes(project)))
    }

    @Test fun symlinkBlobAndRootAreRejected() = fixture { root ->
        val session = OwnedResponseContentStore(root, unlimited, 1).authorize(owner)
        val content = session.begin(OwnedResponseContentStore.Kind.ANSWER).use { it.append("safe"); it.sealCompleted(::drain).content }
        val blob = root.resolve(content.handle.id + ".blob"); Files.delete(blob)
        val outside = Files.createTempFile("outside-owned-content", ".txt")
        try {
            Files.write(outside, "safe".toByteArray()); Files.createSymbolicLink(blob, outside)
            assertThrows(IOException::class.java) { session.open(content.handle) }
            val link = root.resolve("root-link"); Files.createSymbolicLink(link, root)
            assertThrows(IllegalArgumentException::class.java) { OwnedResponseContentStore(link, unlimited) }
        } finally { Files.delete(outside) }
    }

    @Test fun outputBeyondFormerEightMillionLimitIsStreamedWithoutWholeHeapReload() = fixture { root ->
        val session = OwnedResponseContentStore(root, unlimited, 64 * 1024).authorize(owner)
        val chunk = "0123456789abcdef".repeat(512)
        val expectedHash = MessageDigest.getInstance("SHA-256")
        val content = session.begin(OwnedResponseContentStore.Kind.ANSWER).use { writer ->
            repeat(1200) { writer.append(chunk); expectedHash.update(chunk.toByteArray()) }
            writer.sealCompleted(::drain).content
        }
        assertEquals(9_830_400L, content.byteLength)
        assertTrue(content is OwnedResponseContentStore.Content.FileBacked)
        assertEquals(expectedHash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, content.sha256)
        var total = 0L
        session.openBytes(content.handle).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                for (i in 0 until n) assertEquals("0123456789abcdef"[((total + i) % 16).toInt()].code.toByte(), buffer[i])
                total += n
            }
        }
        assertEquals(content.byteLength, total)
    }

    @Test fun crashBetweenBlobAndManifestPublishRecoversAsIncomplete() = fixture { root ->
        val session = OwnedResponseContentStore(root, unlimited, 1).authorize(owner)
        val handle = session.begin(OwnedResponseContentStore.Kind.ANSWER).use { writer -> writer.append("committing"); writer.recoveryHandle }
        Files.move(root.resolve(handle.id + ".part"), root.resolve(handle.id + ".blob"))
        val restored = OwnedResponseContentStore(root, unlimited, 1).authorize(owner)
        assertTrue(restored.hasRecoverableStage(handle))
        assertEquals(OwnedResponseContentStore.State.INCOMPLETE, restored.recoverIncomplete(handle).state)
        assertEquals("committing", read(restored, handle))
        assertThrows(IllegalStateException::class.java) { restored.openCompleted(handle) }
    }

    @Test fun cancelledInlineAndSpilledWritersCheckpointWithoutResettingCancellation() = fixture { root ->
        for ((index, threshold) in listOf(65536, 1).withIndex()) {
            var cancelled = false
            val session = OwnedResponseContentStore(root, unlimited, threshold, cancelled = { cancelled }).authorize(owner)
            val other = session.begin(OwnedResponseContentStore.Kind.ANSWER)
            val checkpoint = session.begin(OwnedResponseContentStore.Kind.ANSWER).use { writer ->
                writer.append("already received $index 🌏")
                cancelled = true
                val result = writer.abortToIncomplete()
                assertEquals(OwnedResponseContentStore.AbortTailStatus.PRESERVED_AVAILABLE_BYTES, result.tailStatus)
                assertThrows(IllegalStateException::class.java) { writer.append("new input") }
                assertThrows(IllegalStateException::class.java) { writer.sealCompleted(::drain) }
                assertThrows(IllegalStateException::class.java) { writer.abortToIncomplete() }
                result.content
            }
            other.use { assertThrows(CancellationException::class.java) { it.append("still cancelled") } }
            assertThrows(CancellationException::class.java) { session.begin(OwnedResponseContentStore.Kind.ANSWER) }
            assertThrows(CancellationException::class.java) { session.openBytes(checkpoint.handle) }
            val reader = OwnedResponseContentStore(root, unlimited, threshold).authorize(owner)
            assertEquals("already received $index 🌏", read(reader, checkpoint.handle))
            assertThrows(IllegalStateException::class.java) { reader.openCompleted(checkpoint.handle) }
        }
    }

    @Test fun cancelledUtf8TailIsCheckpointedExactlyAndUnpairedUtf16IsReported() = fixture { root ->
        var cancelled = false
        val session = OwnedResponseContentStore(root, unlimited, cancelled = { cancelled }).authorize(owner)
        val raw = byteArrayOf(65, 0xf0.toByte(), 0x9f.toByte())
        val checkpoint = session.begin(OwnedResponseContentStore.Kind.ANSWER).use { writer ->
            writer.appendUtf8(raw); cancelled = true; writer.abortToIncomplete()
        }
        val reader = OwnedResponseContentStore(root, unlimited).authorize(owner)
        reader.openBytes(checkpoint.content.handle).use { assertArrayEquals(raw, it.readBytes()) }
        assertThrows(java.nio.charset.MalformedInputException::class.java) { read(reader, checkpoint.content.handle) }
        cancelled = false
        val unpaired = session.begin(OwnedResponseContentStore.Kind.ANSWER).use { writer ->
            writer.append("prefix\ud83c"); cancelled = true; writer.abortToIncomplete()
        }
        assertEquals(OwnedResponseContentStore.AbortTailStatus.UNPAIRED_UTF16_NOT_REPRESENTABLE, unpaired.tailStatus)
        assertEquals("prefix", read(reader, unpaired.content.handle))
    }

    @Test fun failedCancelledAppendDoesNotDiscardPreviouslyBufferedUtf8Tail() = fixture { root ->
        var cancelled = false
        val session = OwnedResponseContentStore(root, unlimited, cancelled = { cancelled }).authorize(owner)
        val raw = byteArrayOf(65, 0xf0.toByte(), 0x9f.toByte())
        val checkpoint = session.begin(OwnedResponseContentStore.Kind.ANSWER).use { writer ->
            writer.appendUtf8(raw); cancelled = true
            assertThrows(CancellationException::class.java) { writer.appendUtf8(byteArrayOf(0x8c.toByte(), 0x8f.toByte())) }
            writer.abortToIncomplete()
        }
        assertTrue(checkpoint.inputHadFailed)
        val reader = OwnedResponseContentStore(root, unlimited).authorize(owner)
        reader.openBytes(checkpoint.content.handle).use { assertArrayEquals(raw, it.readBytes()) }
    }

    @Test fun abortDiskFailureRemainsTerminalAndNeverPublishesCompletedContent() = fixture { root ->
        var denied = false
        val disk = object : OwnedResponseContentStore.DiskAdmission {
            override fun reserve(bytes: Long): AutoCloseable { if (denied) throw IOException("ENOSPC"); return AutoCloseable {} }
        }
        val session = OwnedResponseContentStore(root, disk).authorize(owner)
        session.begin(OwnedResponseContentStore.Kind.ANSWER).use { writer ->
            writer.append("inline before disk exhausted"); denied = true
            assertThrows(IOException::class.java) { writer.abortToIncomplete() }
            denied = false
            assertThrows(IllegalStateException::class.java) { writer.append("cannot resume") }
            assertThrows(IllegalStateException::class.java) { writer.sealCompleted(::drain) }
            assertFalse(Files.exists(root.resolve(writer.recoveryHandle.id + ".meta")))
        }
    }

    @Test fun abortAfterPartialWriteExportsActualPrefixNotOptimisticCounters() = fixture { root ->
        var fail = false
        val session = OwnedResponseContentStore(root, unlimited, 0, channelWrite = { c, b ->
            if (fail) {
                val limit = b.limit(); b.limit(minOf(limit, b.position() + 2)); c.write(b); b.limit(limit)
                throw IOException("ENOSPC")
            }
            c.write(b)
        }).authorize(owner)
        val checkpoint = session.begin(OwnedResponseContentStore.Kind.ANSWER).use { writer ->
            fail = true
            assertThrows(IOException::class.java) { writer.append("abcdef") }
            fail = false
            writer.abortToIncomplete()
        }
        assertEquals(OwnedResponseContentStore.AbortTailStatus.FAILED_INPUT_PREFIX_ONLY, checkpoint.tailStatus)
        assertEquals(-1L, checkpoint.content.utf16Length)
        assertEquals("ab", read(session, checkpoint.content.handle))
    }

    @Test fun abortCompletesVerifiedPartialSpillWithoutDuplicatingInlinePrefix() = fixture { root ->
        var fail = false
        val session = OwnedResponseContentStore(root, unlimited, 8, channelWrite = { c, b ->
            if (fail) {
                val limit = b.limit(); b.limit(minOf(limit, b.position() + 2)); c.write(b); b.limit(limit)
                throw IOException("spill failed")
            }
            c.write(b)
        }).authorize(owner)
        val checkpoint = session.begin(OwnedResponseContentStore.Kind.ANSWER).use { writer ->
            writer.append("abcdef"); fail = true
            assertThrows(IOException::class.java) { writer.append("not persisted") }
            fail = false; writer.abortToIncomplete()
        }
        assertEquals(OwnedResponseContentStore.AbortTailStatus.FAILED_INPUT_PREFIX_ONLY, checkpoint.tailStatus)
        assertEquals("abcdef", read(session, checkpoint.content.handle))
    }

    @Test fun headroomLedgerIncludesConcurrentOutstandingReservations() {
        var free = 100L
        val budget = OwnedResponseContentStore.HeadroomAdmission({ free }, 20)
        val a = budget.reserve(50)
        assertThrows(IOException::class.java) { budget.reserve(31) }
        budget.reserve(30).use { assertThrows(IOException::class.java) { budget.reserve(1) } }
        a.close(); a.close()
        budget.reserve(80).close()
        free = 19
        assertThrows(IOException::class.java) { budget.reserve(1) }
    }
}
