package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WorkspaceReviewPreviewTest {
    @Test fun previewRequiresExactBytesAndDoesNotDecodeLargeOrBinaryInputs() = runBlocking {
        val root = Files.createTempDirectory("workspace-review-").toFile()
        try {
            val text = File(root, "file").apply { writeText("fun hello() = 42\n") }
            fun hash() = MessageDigest.getInstance("SHA-256").digest(text.readBytes()).joinToString("") { "%02x".format(it) }
            val approvedHash = hash()
            assertEquals(text.readText(), WorkspaceReviewPreview.text(root, "file", approvedHash))
            text.writeText("changed")
            try { WorkspaceReviewPreview.text(root, "file", approvedHash); fail("Unreviewed content was previewed") }
            catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("Review again")) }
            text.writeBytes(byteArrayOf(0, 1, 2))
            assertNull(WorkspaceReviewPreview.text(root, "file", hash()))
            text.writeBytes(byteArrayOf(-1, -2))
            assertNull(WorkspaceReviewPreview.text(root, "file", hash()))
            text.writeBytes(ByteArray(32_769) { 65 })
            assertNull(WorkspaceReviewPreview.text(root, "file", hash()))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
