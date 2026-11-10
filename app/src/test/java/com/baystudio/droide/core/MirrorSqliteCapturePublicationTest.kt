package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class MirrorSqliteCapturePublicationTest {
    private val pair = listOf("state.sqlite", "state.sqlite-journal")
    private fun fixture(action: (File) -> Unit) {
        val root = Files.createTempDirectory("sqlite-capture-publication-").toFile()
        try {
            pair.forEachIndexed { i, name -> root.resolve(name).writeBytes(ByteArray(8192) { (it % (239 + i)).toByte() }) }
            action(root)
        } finally { root.deleteRecursively() }
    }
    private fun bytes(directory: File) = directory.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(directory).path to it.readBytes().toList() }
    private fun review(root: File) = root.listFiles()!!.single { it.name.startsWith("sqlite-review-") }
    private fun mutate(file: File) = file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
    private fun capture(root: File, checkpoint: (String) -> Unit = {}) =
        MirrorSqliteRollbackRecovery.capture(root, "binding-a", {}, checkpoint)
    private fun rejected(root: File, boundary: String, receiptExists: Boolean, mutation: (File) -> Unit) {
        var hit = false
        val failure = runCatching { capture(root) {
            if (it == boundary) { hit = true; mutation(review(root)) }
        } }.exceptionOrNull()
        assertTrue("Boundary must execute", hit)
        assertNotNull("Changed capture must not return a successful Review", failure)
        assertTrue("Expected capture integrity rejection, got $failure", failure is IllegalStateException)
        assertEquals(receiptExists, review(root).resolve("receipt").exists())
    }

    @Test fun changedOriginalBeforePublicationCannotPublishReceipt() {
        for (name in pair) fixture { root ->
            rejected(root, "sqlite-review-before-receipt-publication", false) { mutate(root.resolve(name)) }
        }
    }
    @Test fun changedOriginalAfterPublicationCannotReturnReview() {
        for (name in pair) fixture { root ->
            rejected(root, "sqlite-review-after-receipt", true) { mutate(root.resolve(name)) }
        }
    }
    @Test fun lateOriginalSidecarsBeforePublicationCannotPublishReceipt() {
        for (suffix in listOf("-wal", "-shm")) fixture { root ->
            rejected(root, "sqlite-review-before-receipt-publication", false) { root.resolve("state.sqlite$suffix").writeText("retained") }
            assertEquals("retained", root.resolve("state.sqlite$suffix").readText())
        }
    }
    @Test fun lateOriginalSidecarsAfterPublicationCannotReturnReview() {
        for (suffix in listOf("-wal", "-shm")) fixture { root ->
            rejected(root, "sqlite-review-after-receipt", true) { root.resolve("state.sqlite$suffix").writeText("retained") }
            assertEquals("retained", root.resolve("state.sqlite$suffix").readText())
        }
    }
    @Test fun changedCopiedBytesBeforePublicationCannotPublishReceipt() {
        for (name in pair) fixture { root ->
            val original = pair.associateWith { root.resolve(it).readBytes().toList() }
            rejected(root, "sqlite-review-before-receipt-publication", false) { mutate(it.resolve(name)) }
            assertEquals(original, pair.associateWith { root.resolve(it).readBytes().toList() })
        }
    }
    @Test fun changedCopiedBytesAfterPublicationCannotReturnReview() {
        for (name in pair) fixture { root ->
            rejected(root, "sqlite-review-after-receipt", true) { mutate(it.resolve(name)) }
        }
    }
    @Test fun sameByteCopiedInodeReplacementCannotPublishReceipt() = fixture { root ->
        rejected(root, "sqlite-review-before-receipt-publication", false) { evidence ->
            val file = evidence.resolve("state.sqlite")
            Files.move(file.toPath(), evidence.resolve("preserved-original").toPath())
            Files.copy(evidence.resolve("preserved-original").toPath(), file.toPath())
        }
        assertTrue(review(root).resolve("preserved-original").exists())
    }
    @Test fun lateEvidenceSidecarCannotReturnReview() = fixture { root ->
        rejected(root, "sqlite-review-after-receipt", true) { it.resolve("state.sqlite-wal").writeText("retained") }
        assertEquals("retained", review(root).resolve("state.sqlite-wal").readText())
    }
    @Test fun receiptChangedAfterPublicationCannotReturnReview() = fixture { root ->
        rejected(root, "sqlite-review-after-receipt", true) { mutate(it.resolve("receipt")) }
    }
    @Test fun receiptSameByteReplacementCannotReportForcedPublication() {
        for (boundary in listOf("sqlite-review-before-receipt-publication", "sqlite-review-after-receipt")) fixture { root ->
            val published = boundary == "sqlite-review-after-receipt"
            rejected(root, boundary, published) { evidence ->
                val file = evidence.resolve(if (published) "receipt" else ".receipt-pending")
                val retained = evidence.resolve("retained-forced-receipt")
                Files.move(file.toPath(), retained.toPath())
                Files.copy(retained.toPath(), file.toPath(), java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
            }
            assertTrue(review(root).resolve("retained-forced-receipt").isFile)
        }
    }
    @Test fun receiptPermissionChangeCannotReturnNonPrivateReview() {
        for (boundary in listOf("sqlite-review-before-receipt-publication", "sqlite-review-after-receipt")) fixture { root ->
            val published = boundary == "sqlite-review-after-receipt"
            rejected(root, boundary, published) { evidence ->
                Files.setPosixFilePermissions(evidence.resolve(if (published) "receipt" else ".receipt-pending").toPath(),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
            }
        }
    }
    @Test fun receiptAliasCannotReturnReview() {
        for (boundary in listOf("sqlite-review-before-receipt-publication", "sqlite-review-after-receipt")) fixture { root ->
            val published = boundary == "sqlite-review-after-receipt"
            rejected(root, boundary, published) { evidence ->
                Files.createLink(evidence.resolve("retained-receipt-alias").toPath(),
                    evidence.resolve(if (published) "receipt" else ".receipt-pending").toPath())
            }
            assertTrue(review(root).resolve("retained-receipt-alias").isFile)
        }
    }
    @Test fun failedPrivateReceiptRetainsEvidenceAndAllowsFreshRetry() = fixture { root ->
        val originals = pair.associateWith { root.resolve(it).readBytes().toList() }
        rejected(root, "sqlite-review-after-receipt", true) { evidence ->
            Files.setPosixFilePermissions(evidence.resolve("receipt").toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
        }
        val failed = review(root)
        val retained = bytes(failed)
        val retry = capture(root)
        assertNotEquals(failed, retry.evidenceDirectory)
        assertEquals(retained, bytes(failed))
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"),
            Files.getPosixFilePermissions(failed.resolve("receipt").toPath()))
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(retry.evidenceDirectory.resolve("receipt").toPath()))
        assertEquals(originals, pair.associateWith { root.resolve(it).readBytes().toList() })
        assertEquals(originals, pair.associateWith { retry.evidenceDirectory.resolve(it).readBytes().toList() })
        assertEquals(2, root.listFiles()!!.count { it.name.startsWith("sqlite-review-") })
    }
    @Test fun receiptBoundaryFailureRetainsEvidenceAndRetryUsesCurrentPair() = fixture { root ->
        rejected(root, "sqlite-review-after-receipt", true) { mutate(root.resolve("state.sqlite")) }
        val old = review(root)
        val preserved = bytes(old)
        val original = pair.associateWith { root.resolve(it).readBytes().toList() }
        val retried = capture(root)
        assertNotEquals(old, retried.evidenceDirectory)
        assertEquals(preserved, bytes(old))
        assertEquals(original, pair.associateWith { retried.evidenceDirectory.resolve(it).readBytes().toList() })
        assertEquals(original, pair.associateWith { root.resolve(it).readBytes().toList() })
        assertEquals(2, root.listFiles()!!.count { it.name.startsWith("sqlite-review-") })
    }
    @Test fun stableCaptureStillReturnsExactPair() = fixture { root ->
        val before = bytes(root)
        val result = capture(root)
        before.forEach { (name, value) ->
            assertEquals(value, root.resolve(name).readBytes().toList())
            assertEquals(value, result.evidenceDirectory.resolve(name).readBytes().toList())
        }
        assertTrue(result.evidenceDirectory.resolve("receipt").readText().contains("REPLAY_AND_ACTIVATION_DENIED"))
    }
}
