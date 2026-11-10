package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DocumentPreviewPolicyTest {
    @Test fun documentTypesNeverUseExtensionTextFallback() {
        assertEquals(DocumentPreviewPolicy.Kind.PDF, DocumentPreviewPolicy.kind("Folder/Report.PDF"))
        assertEquals(DocumentPreviewPolicy.Kind.DOCX, DocumentPreviewPolicy.kind("document.docx"))
        listOf("doc", "docm", "dot", "dotx", "dotm").forEach {
            assertEquals(DocumentPreviewPolicy.Kind.LEGACY_WORD, DocumentPreviewPolicy.kind("x.$it"))
        }
        assertNull(DocumentPreviewPolicy.kind("x.txt"))
        assertNull(DocumentPreviewPolicy.kind("pdf"))
    }
    @Test fun dimensionsAreBoundedForNormalAndExtremePages() {
        for ((w, h) in listOf(612 to 792, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE, Int.MAX_VALUE to Int.MAX_VALUE)) {
            for (z in listOf(-1, 1, 2, Int.MAX_VALUE)) {
                val (x, y) = DocumentPreviewPolicy.size(w, h, z)
                assertTrue(x in 1..3200 && y in 1..3200)
                assertTrue(x.toLong() * y <= DocumentPreviewPolicy.MAX_PIXELS)
            }
        }
    }
    @Test fun nonpositiveDimensionsAreRejected() {
        listOf(0 to 10, 10 to 0, -1 to 3).forEach { (w, h) ->
            assertTrue(runCatching { DocumentPreviewPolicy.size(w, h) }.isFailure)
        }
    }
    private fun directory(test: (File) -> Unit) {
        val root = Files.createTempDirectory("document-preview-test").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
    @Test fun immutableCopyPreservesEveryByteAndNeverWritesOriginal() = directory { root ->
        val source = File(root, "source.pdf"); val bytes = ByteArray(50_000) { (it % 251).toByte() }
        source.writeBytes(bytes)
        val copied = DocumentPreviewPolicy.snapshot(source, File(root, "cache")) {}
        assertArrayEquals(bytes, source.readBytes()); assertArrayEquals(bytes, copied.readBytes())
        source.writeText("changed later")
        assertArrayEquals(bytes, copied.readBytes())
    }
    @Test fun cancellationCleansTemporaryWithoutChangingOriginal() = directory { root ->
        val source = File(root, "source"); val bytes = ByteArray(200_000) { 42 }; source.writeBytes(bytes)
        val cache = File(root, "cache"); var calls = 0
        val cancelled = CancellationException("stop")
        assertSame(cancelled, runCatching {
            DocumentPreviewPolicy.snapshot(source, cache) { if (++calls == 4) throw cancelled }
        }.exceptionOrNull())
        assertArrayEquals(bytes, source.readBytes()); assertTrue(cache.listFiles().orEmpty().isEmpty())
    }
    @Test fun oversizedAndEmptyInputsAreRejectedWithoutCacheFiles() = directory { root ->
        val source = File(root, "source"); source.writeBytes(byteArrayOf())
        assertTrue(runCatching { DocumentPreviewPolicy.snapshot(source, File(root, "cache")) {} }.isFailure)
        java.io.RandomAccessFile(source, "rw").use { it.setLength(DocumentPreviewPolicy.MAX_FILE_BYTES + 1) }
        assertTrue(runCatching { DocumentPreviewPolicy.snapshot(source, File(root, "cache")) {} }.isFailure)
        assertFalse(File(root, "cache").exists())
    }
    @Test fun symlinkInputIsRejected() = directory { root ->
        val source = File(root, "source"); source.writeText("original")
        val link = File(root, "link"); Files.createSymbolicLink(link.toPath(), source.toPath())
        assertTrue(runCatching { DocumentPreviewPolicy.snapshot(link, File(root, "cache")) {} }.isFailure)
        assertEquals("original", source.readText())
    }
    @Test fun growthDuringCopyFailsAndCleansCache() = directory { root ->
        val source = File(root, "source"); source.writeBytes(ByteArray(90_000) { 33 })
        val cache = File(root, "cache"); var calls = 0
        assertTrue(runCatching {
            DocumentPreviewPolicy.snapshot(source, cache) { if (++calls == 3) source.appendText("more") }
        }.isFailure)
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }
    @Test fun sameSizeReplacementDuringCopyIsRejected() = directory { root ->
        val source = File(root, "source"); source.writeBytes(ByteArray(90_000) { 33 })
        val cache = File(root, "cache"); var calls = 0
        assertTrue(runCatching {
            DocumentPreviewPolicy.snapshot(source, cache) {
                if (++calls == 3) {
                    val next = File(root, "next"); next.writeBytes(ByteArray(90_000) { 44 })
                    Files.move(next.toPath(), source.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }.isFailure)
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }
}
