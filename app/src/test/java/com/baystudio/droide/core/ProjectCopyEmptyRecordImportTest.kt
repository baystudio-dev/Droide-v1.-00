package com.baystudio.droide.core

import android.app.Application
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProjectCopyEmptyRecordImportTest {
    @Test fun importsReportRecoveryRequiredBeforeOpeningSourceOrPublishing() = fixture { root, empty ->
        val metadata = AtomicInteger()
        val opens = AtomicInteger()
        val mutations = AtomicInteger()
        val publications = AtomicInteger()
        val files = FileRepository(root, beforeMutation = { mutations.incrementAndGet() },
            newFilePublisher = NewProjectFilePublisher { _, _ -> publications.incrementAndGet(); error("Unexpected publication") })
        val source = object : WorkspaceImportSource {
            override suspend fun metadata(uri: Uri): WorkspaceImportSourceMetadata {
                metadata.incrementAndGet()
                return WorkspaceImportSourceMetadata("incoming.bin", 4096)
            }
            override suspend fun open(uri: Uri): InputStream {
                opens.incrementAndGet()
                error("Recovery must block before the provider is opened")
            }
        }
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = WorkspaceImportController(files, parent, source)
        val entriesBefore = paths(root)
        try {
            var lastId = 0L
            repeat(2) {
                assertEquals(WorkspaceImportAdmission.ACCEPTED,
                    controller.submit(controller.capturePicker(""), Uri.parse("content://test/blocked")))
                val result = withTimeout(5000) {
                    controller.state.first { !it.busy && it.result?.id?.let { id -> id > lastId } == true }.result!!
                }
                lastId = result.id
                assertEquals(WorkspaceImportResultStatus.RECOVERY_REQUIRED, result.status)
                assertTrue(result.message.contains(empty.name))
                assertFalse(result.localCommitRecorded)
                assertNull(result.sha256)
                assertFalse(controller.state.value.busy)
                assertEquals(entriesBefore, paths(root))
                assertEquals("original project work", root.resolve("original.txt").readText())
                assertTrue(empty.isDirectory && empty.listFiles()!!.isEmpty())
                assertFalse(root.resolve("incoming.bin").exists())
            }
            assertEquals(2, metadata.get())
            assertEquals(0, opens.get())
            assertEquals(0, mutations.get())
            assertEquals(0, publications.get())
            assertTrue(files.retainedWork.value.notices.isEmpty())
        } finally {
            controller.closeAndJoin()
            assertTrue(parent.isActive)
            parent.cancel()
        }
    }

    @Test fun ordinaryRepositorySaveContinuesWithoutClearingUnknownCopyHistory() = fixture { root, empty ->
        val journal = ProjectCopyJournal(root)
        assertTrue(journal.recovery(ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.EXPLORER_IMPORT))
            .blockingProblem.orEmpty().contains(empty.name))
        val metadataBefore = paths(root.resolve(".droide"))
        val files = FileRepository(root, newFilePublisher = NewProjectFilePublisher.native())
        files.writeTextIfUnchanged("source.kt", "fun main() = Unit\n", DiskFileVersion.fromText(null))
        assertEquals("fun main() = Unit\n", files.readText("source.kt"))
        assertEquals("original project work", root.resolve("original.txt").readText())
        assertEquals(metadataBefore, paths(root.resolve(".droide")))
        assertTrue(empty.listFiles()!!.isEmpty())
        assertTrue(files.retainedWork.value.notices.isEmpty())
        assertTrue(journal.recovery(ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.EXPLORER_IMPORT))
            .blockingProblem.orEmpty().contains(empty.name))
    }

    private fun paths(root: File): List<String> = Files.walk(root.toPath()).use { stream ->
        stream.iterator().asSequence().map { root.toPath().relativize(it).toString() }.sorted().toList()
    }

    private fun fixture(block: suspend CoroutineScope.(File, File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("empty-record-import-").toFile()
        try {
            root.resolve("original.txt").writeText("original project work")
            val empty = root.resolve(".droide/copy-recovery/${UUID.randomUUID()}")
            Files.createDirectories(empty.toPath())
            block(root, empty)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
