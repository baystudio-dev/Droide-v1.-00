package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AgentPublicContentArchiveTest {
    private fun store(root: Path, cancelled: () -> Boolean = { false }) = OwnedResponseContentStore(
        root, OwnedResponseContentStore.HeadroomAdmission({ Long.MAX_VALUE }, 0), inlineThreshold = 32,
        cancelled = cancelled,
    )
    private fun fixture(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("public-archive-test-")
        try { block(root) } finally { root.toFile().deleteRecursively() }
    }
    private fun answer(archive: AgentPublicContentArchive, text: String, complete: Boolean = true): ArchivedPublicAnswer =
        archive.begin("run", "answer").use { capture ->
            // Deliberately split surrogate pairs across appends.
            text.forEach { capture.append(it.toString()) }
            if (complete) capture.finishAfterProviderCompletion() else capture.finishIncomplete()
        }
    private fun export(archive: AgentPublicContentArchive, ref: AgentPublicContentRef): ByteArray =
        ByteArrayOutputStream().also { archive.export(ref, it) }.toByteArray()

    @Test fun largePublicAnswerSurvivesDescriptorSaveReopenAndExactExport() = fixture { root ->
        val original = "abc🌏\n".repeat(30_000)
        val archive = AgentPublicContentArchive(store(root), "session", previewChars = 513)
        val value = answer(archive, original)
        assertTrue(original.length > 100_000)
        assertTrue(value.previewIsPartial)
        assertTrue(value.preview.length <= 513)
        assertFalse(Character.isHighSurrogate(value.preview.last()))
        val serialized = Json.encodeToString(value)
        assertTrue("Session manifest must stay small", serialized.length < 4096)
        val restored = Json.decodeFromString<ArchivedPublicAnswer>(serialized)
        val reopened = AgentPublicContentArchive(store(root), "session", previewChars = 513)
        assertEquals(value, reopened.reopen(restored.reference))
        assertArrayEquals(original.toByteArray(), export(reopened, restored.reference))
    }

    @Test fun explicitForkCopiesContentIntoDifferentAuthorizedOwner() = fixture { root ->
        val original = "text 🌏 ".repeat(20_000)
        val parent = AgentPublicContentArchive(store(root), "parent")
        val child = AgentPublicContentArchive(store(root), "child")
        val a = answer(parent, original)
        assertThrows(IllegalStateException::class.java) { child.reopen(a.reference) }
        val b = parent.copyForFork(a.reference, child, "fork-run", "fork-answer")
        assertNotEquals(a.reference.opaqueHandle, b.reference.opaqueHandle)
        assertArrayEquals(original.toByteArray(), export(child, b.reference))
        assertArrayEquals(original.toByteArray(), export(parent, a.reference))
        assertEquals(a.reference.sha256, b.reference.sha256)
    }

    @Test fun incompleteRemainsIncompleteAcrossReopenForkAndExport() = fixture { root ->
        val parent = AgentPublicContentArchive(store(root), "parent", 8)
        val child = AgentPublicContentArchive(store(root), "child", 8)
        val a = answer(parent, "partial 🌏 content", complete = false)
        assertFalse(a.reference.complete)
        assertFalse(parent.reopen(a.reference).reference.complete)
        assertFalse(parent.copyForFork(a.reference, child, "run", "child-item").reference.complete)
        assertArrayEquals("partial 🌏 content".toByteArray(), export(parent, a.reference))
        assertThrows(IllegalStateException::class.java) { parent.reopen(a.reference.copy(complete = true)) }
    }

    @Test fun wrongOwnerAndTamperedDescriptorExposeNoBytes() = fixture { root ->
        val good = AgentPublicContentArchive(store(root), "good")
        val a = answer(good, "sensitive public answer")
        val other = AgentPublicContentArchive(store(root), "other")
        val target = ByteArrayOutputStream()
        assertThrows(IllegalStateException::class.java) { other.export(a.reference, target) }
        assertEquals(0, target.size())
        assertThrows(IllegalStateException::class.java) { good.export(a.reference.copy(bytes = a.reference.bytes + 1), target) }
        assertEquals(0, target.size())
    }

    @Test fun privateContinuationCannotBeRelabeledAsPublicAnswer() = fixture { root ->
        val backing = store(root)
        val owner = backing.authorize(OwnedResponseContentStore.Owner("session", "run", "answer"))
        val private = owner.begin(OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION).use { writer ->
            writer.append("secret chain")
            writer.sealCompleted { input -> val b = ByteArray(8); while (input.read(b) != -1) {} }.content
        }
        val ref = AgentPublicContentRef(opaqueHandle = private.handle.serialized, runId = "run", itemId = "answer",
            bytes = private.byteLength, utf16Chars = private.utf16Length, sha256 = private.sha256, complete = true)
        val archive = AgentPublicContentArchive(backing, "session")
        val destination = ByteArrayOutputStream()
        assertThrows(IllegalStateException::class.java) { archive.export(ref, destination) }
        assertEquals(0, destination.size())
    }

    @Test fun interruptedExportDoesNotDestroyTheSource() = fixture { root ->
        val archive = AgentPublicContentArchive(store(root), "session")
        val value = answer(archive, "x".repeat(100_001))
        val failedDestination = object : OutputStream() { override fun write(b: Int) { throw IOException("disk full") } }
        assertThrows(IOException::class.java) { archive.export(value.reference, failedDestination) }
        assertEquals(100_001, export(archive, value.reference).size)
    }

    @Test fun cancellationNeverPublishesACompletedReference() = fixture { root ->
        var cancelled = false
        val archive = AgentPublicContentArchive(store(root) { cancelled }, "session")
        archive.begin("run", "answer").use { capture ->
            capture.append("partial")
            cancelled = true
            assertThrows(java.util.concurrent.CancellationException::class.java) { capture.finishAfterProviderCompletion() }
        }
    }

    @Test fun partialUtf8TailRemainsExactlyExportableAndForkable() = fixture { root ->
        val archive = AgentPublicContentArchive(store(root), "session")
        val child = AgentPublicContentArchive(store(root), "child")
        val raw = "partial ".toByteArray() + byteArrayOf(0xf0.toByte(), 0x9f.toByte())
        val a = archive.begin("run", "answer").use { capture ->
            capture.appendUtf8(raw)
            capture.finishIncomplete()
        }
        assertFalse(a.reference.complete)
        assertEquals(-1L, a.reference.utf16Chars)
        assertFalse(a.textPreviewAvailable)
        assertArrayEquals(raw, export(archive, a.reference))
        val copy = archive.copyForFork(a.reference, child, "child-run", "answer")
        assertFalse(copy.reference.complete)
        assertArrayEquals(raw, export(child, copy.reference))
    }

    @Test fun explicitStageRecoveryPublishesOnlyIncompletePublicContent() = fixture { root ->
        val archive = AgentPublicContentArchive(store(root), "session")
        val raw = "x".repeat(1000)
        val handle = archive.begin("run", "answer").use { capture ->
            capture.append(raw)
            capture.recoveryHandle
        }
        val recovered = archive.recoverIncomplete("run", "answer", handle)
        assertFalse(recovered.reference.complete)
        assertEquals(-1L, recovered.reference.utf16Chars)
        assertArrayEquals(raw.toByteArray(), export(archive, recovered.reference))
        assertTrue(recovered.textPreviewAvailable)
    }

    @Test fun publicRecoveryCannotPublishAPrivateContinuationStage() = fixture { root ->
        val backing = store(root)
        val session = backing.authorize(OwnedResponseContentStore.Owner("session", "run", "answer"))
        val handle = session.begin(OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION).use { writer ->
            writer.append("private ".repeat(50))
            writer.recoveryHandle
        }
        val archive = AgentPublicContentArchive(backing, "session")
        assertThrows(IllegalStateException::class.java) { archive.recoverIncomplete("run", "answer", handle) }
        assertTrue(session.hasRecoverableStage(handle))
        assertFalse(Files.exists(root.resolve(handle.id + ".meta")))
        assertFalse(Files.exists(root.resolve(handle.id + ".blob")))
    }

    @Test fun cancelledInlineAndSpilledAnswersCheckpointWithoutReopeningCancelledStore() = fixture { root ->
        for ((index, text) in listOf("inline 🌏", "spilled 🌏 ".repeat(15_000)).withIndex()) {
            var cancelled = false
            val sessionId = "session$index"
            val archive = AgentPublicContentArchive(store(root) { cancelled }, sessionId)
            val checkpoint = archive.begin("run", "answer").use { capture ->
                capture.append(text)
                cancelled = true
                val result = capture.abortToIncomplete()
                assertThrows(IllegalStateException::class.java) { capture.append("new input") }
                assertThrows(IllegalStateException::class.java) { capture.finishAfterProviderCompletion() }
                assertThrows(IllegalStateException::class.java) { capture.abortToIncomplete() }
                result
            }
            assertFalse(checkpoint.reference.complete)
            assertFalse(checkpoint.inputHadFailed)
            assertEquals(PublicAnswerAbortTailStatus.PRESERVED_AVAILABLE_BYTES, checkpoint.tailStatus)
            assertThrows(java.util.concurrent.CancellationException::class.java) { archive.reopen(checkpoint.reference) }
            assertThrows(java.util.concurrent.CancellationException::class.java) { archive.begin("another", "answer") }
            val serialized = Json.encodeToString(checkpoint)
            val restored = Json.decodeFromString<PublicAnswerAbortCheckpoint>(serialized)
            assertEquals(checkpoint, restored)
            val reader = AgentPublicContentArchive(store(root), sessionId)
            assertArrayEquals(text.toByteArray(), export(reader, restored.reference))
            assertFalse(reader.reopen(restored.reference).reference.complete)
        }
    }

    @Test fun abortReportsFailedInputPrefixRatherThanClaimingAllAttemptedText() = fixture { root ->
        var cancelled = false
        val archive = AgentPublicContentArchive(store(root) { cancelled }, "session")
        val checkpoint = archive.begin("run", "answer").use { capture ->
            capture.append("accepted prefix")
            cancelled = true
            assertThrows(java.util.concurrent.CancellationException::class.java) { capture.append("not accepted") }
            capture.abortToIncomplete()
        }
        assertTrue(checkpoint.inputHadFailed)
        assertEquals(PublicAnswerAbortTailStatus.FAILED_INPUT_PREFIX_ONLY, checkpoint.tailStatus)
        assertArrayEquals("accepted prefix".toByteArray(), export(AgentPublicContentArchive(store(root), "session"), checkpoint.reference))
    }

    @Test fun abortReportsUnpairedUtf16TailAndKeepsPrefixIncomplete() = fixture { root ->
        var cancelled = false
        val archive = AgentPublicContentArchive(store(root) { cancelled }, "session")
        val checkpoint = archive.begin("run", "answer").use { capture ->
            capture.append("prefix\ud83c")
            cancelled = true
            capture.abortToIncomplete()
        }
        assertFalse(checkpoint.inputHadFailed)
        assertEquals(PublicAnswerAbortTailStatus.UNPAIRED_UTF16_NOT_REPRESENTABLE, checkpoint.tailStatus)
        assertFalse(checkpoint.reference.complete)
        assertArrayEquals("prefix".toByteArray(), export(AgentPublicContentArchive(store(root), "session"), checkpoint.reference))
    }

    @Test fun abortPreservesRawUtf8TailWithoutRequiringPreviewDecode() = fixture { root ->
        var cancelled = false
        val raw = "prefix".toByteArray() + byteArrayOf(0xf0.toByte(), 0x9f.toByte())
        val archive = AgentPublicContentArchive(store(root) { cancelled }, "session")
        val checkpoint = archive.begin("run", "answer").use { capture ->
            capture.appendUtf8(raw)
            cancelled = true
            capture.abortToIncomplete()
        }
        assertEquals(PublicAnswerAbortTailStatus.PRESERVED_AVAILABLE_BYTES, checkpoint.tailStatus)
        assertFalse(checkpoint.inputHadFailed)
        val reader = AgentPublicContentArchive(store(root), "session")
        assertArrayEquals(raw, export(reader, checkpoint.reference))
        assertFalse(reader.reopen(checkpoint.reference).textPreviewAvailable)
    }

    @Test fun abortDiskFailureReturnsNoSuccessAndCannotResumeComplete() = fixture { root ->
        var denied = false
        val disk = object : OwnedResponseContentStore.DiskAdmission {
            override fun reserve(bytes: Long): AutoCloseable {
                if (denied) throw IOException("ENOSPC")
                return AutoCloseable {}
            }
        }
        val backing = OwnedResponseContentStore(root, disk, inlineThreshold = 1024)
        val archive = AgentPublicContentArchive(backing, "session")
        archive.begin("run", "answer").use { capture ->
            capture.append("already received")
            denied = true
            assertThrows(IOException::class.java) { capture.abortToIncomplete() }
            denied = false
            assertThrows(IllegalStateException::class.java) { capture.finishAfterProviderCompletion() }
            assertFalse(Files.exists(root.resolve(capture.recoveryHandle.id + ".meta")))
        }
    }

    @Test fun referenceLogsRedactOpaqueCapability() = fixture { root ->
        val archive = AgentPublicContentArchive(store(root), "session")
        val ref = answer(archive, "ok").reference
        assertFalse(ref.toString().contains(ref.opaqueHandle))
        assertThrows(IllegalArgumentException::class.java) { ref.copy(version = 2) }
    }
}
