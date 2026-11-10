package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Tests raw evidence preservation only; never claims SQLite recovery or writer exclusion. */
class MirrorSqliteRollbackRecoveryTest {
    private class Interrupted : RuntimeException()
    private fun fixture(action: (File) -> Unit) {
        val root = Files.createTempDirectory("sqlite-evidence-test-").toFile()
        try {
            File(root, "state.sqlite").writeBytes(ByteArray(8192) { (it % 251).toByte() })
            File(root, "state.sqlite-journal").writeBytes(ByteArray(4096) { (it % 239).toByte() })
            action(root)
        } finally { root.deleteRecursively() }
    }
    private fun snapshot(root: File) = listOf("state.sqlite", "state.sqlite-journal").associateWith { File(root, it).readBytes().toList() }
    private fun capture(root: File, checkpoint: (String) -> Unit = {}) =
        MirrorSqliteRollbackRecovery.capture(root, "binding-a", {}, checkpoint)

    @Test fun completedCaptureHasExactEvidenceAndPreservesOriginals() = fixture { root ->
        val before = snapshot(root)
        val review = capture(root)
        assertEquals(before, snapshot(root)); assertEquals(before, snapshot(review.evidenceDirectory))
        assertTrue(File(review.evidenceDirectory, "receipt").readText().contains("REPLAY_AND_ACTIVATION_DENIED"))
        assertEquals(64, review.databaseSha256.length); assertEquals(64, review.journalSha256.length)
    }

    @Test fun everyInterruptedBoundaryKeepsOriginalsAndRetryKeepsPriorEvidence() {
        for (boundary in listOf("sqlite-review-after-database-copy", "sqlite-review-after-journal-copy",
            "sqlite-review-before-receipt", "sqlite-review-before-receipt-publication", "sqlite-review-after-receipt")) fixture { root ->
            val before = snapshot(root)
            assertTrue(runCatching { capture(root) { if (it == boundary) throw Interrupted() } }.exceptionOrNull() is Interrupted)
            assertEquals(before, snapshot(root))
            val old = root.listFiles()!!.single { it.isDirectory }
            val oldFiles = old.listFiles()!!.associate { it.name to it.readBytes().toList() }
            val retry = capture(root)
            assertNotEquals(old, retry.evidenceDirectory)
            assertEquals(oldFiles, old.listFiles()!!.associate { it.name to it.readBytes().toList() })
            assertEquals(before, snapshot(root)); assertEquals(before, snapshot(retry.evidenceDirectory))
            assertEquals(boundary == "sqlite-review-after-receipt", File(old, "receipt").exists())
        }
    }

    @Test fun changedJournalCannotPublishReceipt() = fixture { root ->
        val failure = runCatching { capture(root) {
            if (it == "sqlite-review-after-database-copy") File(root, "state.sqlite-journal").appendText("changed")
        } }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse(root.walkTopDown().any { it.name == "receipt" })
    }

    @Test fun replacedDatabaseCannotPublishReceipt() = fixture { root ->
        assertNotNull(runCatching { capture(root) {
            if (it == "sqlite-review-after-journal-copy") {
                val db = File(root, "state.sqlite")
                val replacement = File(root, "replacement").apply { writeBytes(db.readBytes()) }
                Files.move(replacement.toPath(), db.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } }.exceptionOrNull())
        assertFalse(root.walkTopDown().any { it.name == "receipt" })
    }

    @Test fun unsupportedWalFailsClosed() = fixture { root ->
        val before = snapshot(root)
        File(root, "state.sqlite-wal").writeText("evidence")
        assertNotNull(runCatching { capture(root) }.exceptionOrNull())
        assertEquals(before, snapshot(root)); assertEquals("evidence", File(root, "state.sqlite-wal").readText())
        assertFalse(root.listFiles()!!.any { it.isDirectory })
    }

    @Test fun symlinkJournalCannotReadOtherWorkspace() = fixture { root -> fixture { other ->
        val before = snapshot(other)
        Files.delete(File(root, "state.sqlite-journal").toPath())
        Files.createSymbolicLink(File(root, "state.sqlite-journal").toPath(), File(other, "state.sqlite-journal").toPath())
        assertNotNull(runCatching { capture(root) }.exceptionOrNull())
        assertEquals(before, snapshot(other)); assertFalse(root.listFiles()!!.any { it.isDirectory })
    } }

    @Test fun workspaceIsolationAndBoundedRetry() = fixture { root -> fixture { other ->
        val before = snapshot(other)
        repeat(8) { capture(root) }
        assertNotNull(runCatching { capture(root) }.exceptionOrNull())
        assertEquals(before, snapshot(other)); assertFalse(other.listFiles()!!.any { it.isDirectory })
        assertEquals(8, root.listFiles()!!.count { it.isDirectory })
    } }

    @Test fun cancellationDoesNotPublishReceipt() = fixture { root ->
        val before = snapshot(root)
        assertNotNull(runCatching { MirrorSqliteRollbackRecovery.capture(root, "a", { throw Interrupted() }) }.exceptionOrNull())
        assertEquals(before, snapshot(root)); assertFalse(root.listFiles()!!.any { it.isDirectory })
    }
    @Test fun unsupportedShmFailsClosed() = fixture { root ->
        val before = snapshot(root)
        File(root, "state.sqlite-shm").writeText("evidence")
        assertNotNull(runCatching { capture(root) }.exceptionOrNull())
        assertEquals(before, snapshot(root)); assertEquals("evidence", File(root, "state.sqlite-shm").readText())
        assertFalse(root.listFiles()!!.any { it.isDirectory })
    }

    @Test fun cancellationAfterCopyPreservesPartialEvidenceWithoutReceipt() = fixture { root ->
        val before = snapshot(root)
        var cancelled = false
        assertTrue(runCatching {
            MirrorSqliteRollbackRecovery.capture(root, "a", { if (cancelled) throw Interrupted() }) {
                if (it == "sqlite-review-after-database-copy") cancelled = true
            }
        }.exceptionOrNull() is Interrupted)
        assertEquals(before, snapshot(root))
        val partial = root.listFiles()!!.single { it.isDirectory }
        assertTrue(File(partial, "state.sqlite").isFile)
        assertFalse(File(partial, "receipt").exists())
        val retry = capture(root)
        assertEquals(before, snapshot(retry.evidenceDirectory))
        assertTrue(partial.isDirectory)
    }

    @Test fun cancellationInsideDatabaseCopyKeepsPartialBytesWithoutReceipt() = fixture { root ->
        File(root, "state.sqlite").writeBytes(ByteArray(256 * 1024) { (it % 251).toByte() })
        val before = snapshot(root)
        var checks = 0
        assertTrue(runCatching {
            MirrorSqliteRollbackRecovery.capture(root, "a", { if (++checks == 4) throw Interrupted() })
        }.exceptionOrNull() is Interrupted)
        assertEquals(before, snapshot(root))
        val partial = root.listFiles()!!.single { it.isDirectory }
        assertTrue(File(partial, "state.sqlite").length() in 1 until File(root, "state.sqlite").length())
        assertFalse(File(partial, "receipt").exists())
        assertEquals(before, snapshot(capture(root).evidenceDirectory))
    }

    private fun receiptIo(
        onWrite: (AnchoredWorkspaceFiles.OpenedFile, ByteArray) -> Unit = { file, bytes -> file.write(bytes) },
        onForce: (AnchoredWorkspaceFiles.OpenedFile) -> Unit = { it.force(384) },
        onPublish: (AnchoredWorkspaceFiles, String) -> AnchoredWorkspaceFiles.MoveReceipt =
            { owner, name -> owner.moveNoReplaceDurable(name, owner, "receipt") },
    ) = object : MirrorSqliteRollbackRecovery.ReceiptIo {
        override fun write(file: AnchoredWorkspaceFiles.OpenedFile, bytes: ByteArray) = onWrite(file, bytes)
        override fun force(file: AnchoredWorkspaceFiles.OpenedFile) = onForce(file)
        override fun publish(owner: AnchoredWorkspaceFiles, staged: String) = onPublish(owner, staged)
    }

    private fun failedReceipt(root: File, io: MirrorSqliteRollbackRecovery.ReceiptIo,
                              finalReceiptMayExist: Boolean = false) {
        val before = snapshot(root)
        var completed: MirrorSqliteRollbackRecovery.Review? = null
        assertNotNull(runCatching {
            completed = MirrorSqliteRollbackRecovery.captureWithReceiptIo(root, "binding-a", {}, io)
        }.exceptionOrNull())
        assertNull(completed)
        assertEquals(before, snapshot(root))
        val failed = root.listFiles()!!.single { it.isDirectory }
        assertEquals(before, snapshot(failed))
        assertEquals(finalReceiptMayExist, File(failed, "receipt").exists())
        val preserved = failed.listFiles()!!.associate { it.name to it.readBytes().toList() }
        val retry = capture(root)
        assertNotEquals(failed, retry.evidenceDirectory)
        assertEquals(preserved, failed.listFiles()!!.associate { it.name to it.readBytes().toList() })
        assertEquals(before, snapshot(retry.evidenceDirectory))
    }

    @Test fun partialReceiptWriteFailureKeepsStageAndCannotReturnReview() = fixture { root ->
        failedReceipt(root, receiptIo(onWrite = { file, bytes ->
            file.write(bytes, 0, bytes.size / 2)
            throw java.io.IOException("Injected ENOSPC during receipt write")
        }))
        val failed = root.listFiles()!!.single { it.isDirectory && File(it, ".receipt-pending").exists() }
        assertTrue(File(failed, ".receipt-pending").length() > 0)
    }

    @Test fun silentShortReceiptWriteIsRejectedBeforePublication() = fixture { root ->
        var published = false
        failedReceipt(root, receiptIo(onWrite = { file, bytes -> file.write(bytes, 0, bytes.size / 2) },
            onPublish = { owner, name -> published = true; owner.moveNoReplaceDurable(name, owner, "receipt") }))
        assertFalse(published)
    }

    @Test fun receiptFileForceFailureCannotPublishOrReturnReview() = fixture { root ->
        var published = false
        failedReceipt(root, receiptIo(onForce = { throw java.io.IOException("Injected fsync EIO") },
            onPublish = { owner, name -> published = true; owner.moveNoReplaceDurable(name, owner, "receipt") }))
        assertFalse(published)
    }

    @Test fun renameWithoutConfirmedDirectoryForceCannotReturnReviewEvenWhenReceiptExists() = fixture { root ->
        failedReceipt(root, receiptIo(onPublish = { owner, name ->
            // The real rename succeeds. Return the same observable failure contract as namespace publication
            // followed by a directory-fsync failure.
            check(owner.moveNoReplaceDurable(name, owner, "receipt").durable)
            AnchoredWorkspaceFiles.MoveReceipt(published = true, error = 5)
        }), finalReceiptMayExist = true)
    }

    @Test fun publicationFailurePreservesStagedReceiptAndRetryUsesNewAttempt() = fixture { root ->
        failedReceipt(root, receiptIo(onPublish = { _, _ ->
            AnchoredWorkspaceFiles.MoveReceipt(published = false, error = 28)
        }))
    }

    @Test fun cancellationAfterReceiptForceNeverPublishesCompletion() = fixture { root ->
        val before = snapshot(root)
        var cancelled = false
        assertTrue(runCatching {
            MirrorSqliteRollbackRecovery.captureWithReceiptIo(root, "binding-a",
                { if (cancelled) throw Interrupted() }, receiptIo()) {
                if (it == "sqlite-review-before-receipt-publication") cancelled = true
            }
        }.exceptionOrNull() is Interrupted)
        val failed = root.listFiles()!!.single { it.isDirectory }
        assertFalse(File(failed, "receipt").exists())
        assertTrue(File(failed, ".receipt-pending").isFile)
        assertEquals(before, snapshot(root))
        assertEquals(before, snapshot(capture(root).evidenceDirectory))
    }

    @Test fun existingReceiptCannotBeReplacedByPublication() = fixture { root ->
        val before = snapshot(root)
        assertNotNull(runCatching {
            MirrorSqliteRollbackRecovery.capture(root, "binding-a", {}) {
                if (it == "sqlite-review-before-receipt-publication") {
                    root.listFiles()!!.single { entry -> entry.isDirectory }.resolve("receipt").writeText("existing evidence")
                }
            }
        }.exceptionOrNull())
        val failed = root.listFiles()!!.single { it.isDirectory }
        assertEquals("existing evidence", File(failed, "receipt").readText())
        assertTrue(File(failed, ".receipt-pending").isFile)
        assertEquals(before, snapshot(root))
        assertEquals(before, snapshot(capture(root).evidenceDirectory))
        assertEquals("existing evidence", File(failed, "receipt").readText())
    }

    private fun interruptedSqlite(root: File, commit: Boolean) {
        // Test-only subprocess. SQLite is never invoked on captured evidence by production code.
        Files.delete(File(root, "state.sqlite").toPath())
        Files.delete(File(root, "state.sqlite-journal").toPath())
        val script = """
import sqlite3, os, sys
c = sqlite3.connect(sys.argv[1])
c.execute('pragma journal_mode=delete')
c.execute('pragma synchronous=extra')
c.execute('pragma cache_size=5')
c.execute('create table sample(id integer primary key, value text)')
c.execute("insert into sample values(1, 'committed')")
c.commit()
c.execute('begin immediate')
c.execute("update sample set value='uncommitted' where id=1")
for i in range(2, 200):
    c.execute('insert into sample values(?, ?)', (i, 'x' * 4096))
if sys.argv[2] == 'commit':
    c.commit()
os._exit(73)
""".trimIndent()
        val process = ProcessBuilder("python3", "-c", script, File(root, "state.sqlite").path,
            if (commit) "commit" else "interrupt").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 73, process.waitFor())
    }

    @Test fun actualSqliteInterruptedBeforeCommitPreservesHotJournalAcrossReviewRetry() = fixture { root ->
        interruptedSqlite(root, commit = false)
        assertTrue(File(root, "state.sqlite-journal").length() > 0)
        val before = snapshot(root)
        val first = capture(root); val second = capture(root)
        assertEquals(before, snapshot(root))
        assertEquals(before, snapshot(first.evidenceDirectory)); assertEquals(before, snapshot(second.evidenceDirectory))
    }

    @Test fun actualSqliteExitAfterCommitDoesNotInventRollbackEvidence() = fixture { root ->
        interruptedSqlite(root, commit = true)
        assertFalse(File(root, "state.sqlite-journal").exists())
        val before = File(root, "state.sqlite").readBytes()
        assertNotNull(runCatching { capture(root) }.exceptionOrNull())
        assertArrayEquals(before, File(root, "state.sqlite").readBytes())
        assertFalse(root.listFiles()!!.any { it.isDirectory })
    }

}
