package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.*
import org.junit.Test

class ExportMetadataWriterAdmissionTest {
    private fun <T> withRoot(block: (File) -> T): T {
        val root = Files.createTempDirectory("export-metadata-writer").toFile()
        return try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun captureRefusesGitReadThatWouldRepairExclude() = withRoot { root ->
        Git.init().setDirectory(root).call().close()
        val exclude = File(root, ".git/info/exclude")
        exclude.delete()
        ExportWriterAdmission.beginCapture(root).use {
            assertThrows(ExportCaptureBusyException::class.java) {
                runBlocking { GitManager(root).isRepository() }
            }
            assertFalse(exclude.exists())
        }
        runBlocking { assertTrue(GitManager(root).isRepository()) }
        assertTrue(exclude.readText().contains("/.droide/"))
    }

    @Test fun captureRefusesGitIndexWriteWithoutChangingIndex() = withRoot { root ->
        runBlocking {
            val manager = GitManager(root)
            manager.init()
            File(root, "source.txt").writeText("source")
            ExportWriterAdmission.beginCapture(root).use {
                assertThrows(ExportCaptureBusyException::class.java) { runBlocking { manager.stageAll() } }
                assertFalse(File(root, ".git/index").exists())
            }
            assertTrue(manager.stageAll().contains("Staged"))
        }
    }

    @Test fun gitPublicationKeepsWriterAdmissionUntilReconciliationSettles() = withRoot { root ->
        runBlocking {
            val publicationEntered = CompletableDeferred<Unit>()
            val finishPublication = CompletableDeferred<Unit>()
            val manager = GitManager(root, syncLinkedFolder = {
                publicationEntered.complete(Unit)
                finishPublication.await()
                null
            })
            manager.init()
            val operation = async { manager.stash() }
            publicationEntered.await()
            try {
                assertThrows(IllegalStateException::class.java) { ExportWriterAdmission.beginCapture(root) }
            } finally { finishPublication.complete(Unit) }
            operation.await()
            ExportWriterAdmission.beginCapture(root).close()
        }
    }

    @Test fun captureRefusesSessionPrimaryAndSummaryRepair() = withRoot { root ->
        runBlocking {
            val sessions = SessionManager(root)
            val original = sessions.create("Original")
            val primary = File(root, ".droide/sessions/${original.id}.json")
            val before = primary.readBytes()
            val summary = File(root, ".droide/sessions/${original.id}.meta")
            summary.delete()
            ExportWriterAdmission.beginCapture(root).use {
                assertThrows(ExportCaptureBusyException::class.java) { runBlocking { sessions.save(original.copy(title = "Changed")) } }
                assertEquals("Original", sessions.getSummary(original.id)?.title)
                assertThrows(ExportCaptureBusyException::class.java) { runBlocking { sessions.delete(original.id) } }
                assertArrayEquals(before, primary.readBytes())
                assertFalse(summary.exists())
            }
            assertEquals("Original", sessions.getSummary(original.id)?.title)
            assertTrue(summary.isFile)
        }
    }
    @Test fun captureRefusesHistoryAndBackupsButPreservesDeferredCleanup() = withRoot { root ->
        runBlocking {
            val history = EditHistory(FileRepository(root))
            val pending = history.prepareLive("source.txt", "original")
            ExportWriterAdmission.beginCapture(root).use {
                assertThrows(ExportCaptureBusyException::class.java) {
                    runBlocking { history.prepareLive("source.txt", "replacement") }
                }
                assertThrows(ExportCaptureBusyException::class.java) {
                    SessionHistoryLedger(root).writePage(listOf(kotlinx.serialization.json.buildJsonObject {
                        put("role", kotlinx.serialization.json.JsonPrimitive("user"))
                    }))
                }
                history.discardLive(pending)
                assertTrue(pending.backup.isFile)
            }
            history.discardLive(pending)
            assertFalse(pending.backup.exists())
        }
    }

}
