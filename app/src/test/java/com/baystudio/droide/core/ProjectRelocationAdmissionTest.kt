package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectRelocationAdmissionTest {
    private fun fixture(block: suspend (File, ProjectStore, ProjectManager, DroideProject) -> Unit) = runBlocking {
        val app = Files.createTempDirectory("relocation-admission-").toFile()
        try {
            val store = FileProjectStore(app)
            val manager = ProjectManager(app, store).apply { init() }
            val project = manager.add("Original")
            File(project.rootPath, "payload").writeText("preserved")
            block(app, store, manager, project)
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }
    private fun busy(retained: Boolean, capture: Boolean) = fixture { app, store, manager, project ->
        val root = File(project.rootPath)
        val target = File(root.parentFile, ".delete-${project.id}")
        val before = store.load()
        val heldRoot = if (retained) target else root
        val held = if (capture) ExportWriterAdmission.beginCapture(heldRoot)
            else ExportWriterAdmission.beginWriter(heldRoot, "fixture writer")
        try {
            assertTrue("Busy namespace must refuse relocation", runCatching { manager.deleteWorkspace(project.id) }.isFailure)
            assertEquals(before, store.load())
            assertTrue(root.isDirectory)
            assertFalse(target.exists())
            assertFalse(File(app, ".droide/workspace-relocation-${project.id}.json").exists())
        } finally { held.close() }
    }
    @Test fun originalWriterRefusesBeforePrepared() = busy(false, false)
    @Test fun retainedWriterRefusesBeforePrepared() = busy(true, false)
    @Test fun originalCaptureRefusesBeforePrepared() = busy(false, true)
    @Test fun retainedCaptureRefusesBeforePrepared() = busy(true, true)

    private fun assertAvailable(root: File) { ExportWriterAdmission.beginWriter(root, "after scope").close() }

    @Test fun secondAcquisitionRefusalRollsBackFirst() = fixture { _, _, manager, project ->
        val root = File(project.rootPath)
        val retained = File(root.parentFile, ".delete-${project.id}")
        val paths = listOf(root, retained).sortedBy { it.canonicalPath }
        ExportWriterAdmission.beginWriter(paths[1], "second busy").use {
            assertTrue(runCatching { manager.deleteWorkspace(project.id) }.isFailure)
            assertAvailable(paths[0])
        }
        assertAvailable(paths[1])
    }

    @Test fun callbacksSeeBothReservationsAndUnrelatedRootRemainsUsable() = fixture { app, store, _, project ->
        val root = File(project.rootPath)
        val retained = File(root.parentFile, ".delete-${project.id}")
        val calls = mutableListOf<String>()
        val authority = ProjectWorkspaceRelocation(app, checkpoint = { checkpoint ->
            if (checkpoint == "before-relocation" || checkpoint == "before-restoration") {
                calls += checkpoint
                for (path in listOf(root, retained)) {
                    assertTrue(runCatching { ExportWriterAdmission.beginWriter(path, "late writer").close() }.isFailure)
                }
                assertAvailable(File(root.parentFile, "unrelated"))
            }
        })
        authority.withLock {
            val before = store.load()
            val intent = authority.prepare(project, before, before.filterNot { it.id == project.id }, store)
            authority.move(intent)
            authority.finish(store)
        }
        assertEquals(listOf("before-relocation", "before-restoration"), calls)
        assertEquals("preserved", File(root, "payload").readText())
        assertAvailable(root); assertAvailable(retained)
    }

    @Test fun retainedWriterBlocksRestartRestorationWithoutMutation() = fixture { app, store, _, project ->
        val root = File(project.rootPath)
        val retained = File(root.parentFile, ".delete-${project.id}")
        val authority = ProjectWorkspaceRelocation(app)
        authority.withLock {
            val before = store.load()
            authority.move(authority.prepare(project, before, before.filterNot { it.id == project.id }, store))
        }
        val record = File(app, ".droide/workspace-relocation-${project.id}.json")
        val beforeRecord = record.readText()
        ExportWriterAdmission.beginWriter(retained, "retained writer").use {
            val restart = ProjectWorkspaceRelocation(app)
            assertTrue(runCatching { restart.withLock { restart.recover(store) } }.isFailure)
            assertFalse(root.exists()); assertTrue(retained.isDirectory)
            assertEquals(beforeRecord, record.readText())
        }
        val restart = ProjectWorkspaceRelocation(app)
        restart.withLock { restart.recover(store) }
        assertEquals("preserved", File(root, "payload").readText())
    }

    private fun interrupted(failure: Throwable) = fixture { app, store, _, project ->
        val root = File(project.rootPath)
        val retained = File(root.parentFile, ".delete-${project.id}")
        val authority = ProjectWorkspaceRelocation(app, checkpoint = { if (it == "before-relocation") throw failure })
        val caught = runCatching {
            authority.withLock {
                val before = store.load()
                authority.move(authority.prepare(project, before, before.filterNot { it.id == project.id }, store))
            }
        }.exceptionOrNull()
        assertSame(failure, caught)
        assertAvailable(root); assertAvailable(retained)
        val restart = ProjectWorkspaceRelocation(app)
        restart.withLock { restart.recover(store) }
        assertEquals("preserved", File(root, "payload").readText())
    }
    @Test fun cancellationReleasesBothReservations() = interrupted(kotlinx.coroutines.CancellationException("fixture"))
    @Test fun errorReleasesBothReservations() = interrupted(java.io.IOException("fixture"))

    @Test fun substitutedIntentCannotUseHeldReservation() = fixture { app, store, _, project ->
        val authority = ProjectWorkspaceRelocation(app)
        authority.withLock {
            val before = store.load()
            val intent = authority.prepare(project, before, before.filterNot { it.id == project.id }, store)
            assertTrue(runCatching { authority.move(intent.copy(id = "substituted")) }.isFailure)
            authority.finish(store)
        }
    }

    @Test fun prepareReceiptPublicationFailureReleasesBothPaths() = fixture { app, store, _, project ->
        val failure = java.io.IOException("receipt publication fixture")
        val authority = ProjectWorkspaceRelocation(app, checkpoint = { if (it == "receipt-published") throw failure })
        val before = store.load()
        assertSame(failure, runCatching {
            authority.withLock { authority.prepare(project, before, before.filterNot { it.id == project.id }, store) }
        }.exceptionOrNull())
        val root = File(project.rootPath)
        assertAvailable(root); assertAvailable(File(root.parentFile, ".delete-${project.id}"))
        val restart = ProjectWorkspaceRelocation(app)
        restart.withLock { restart.recover(store) }
    }

    @Test fun restartedCatalogAfterResolutionRequiresAdmissionThenCommits() = fixture { app, store, _, project ->
        val authority = ProjectWorkspaceRelocation(app)
        authority.withLock {
            val before = store.load()
            val after = before.filterNot { it.id == project.id }
            authority.move(authority.prepare(project, before, after, store))
            store.save(after)
        }
        val root = File(project.rootPath)
        val retained = File(root.parentFile, ".delete-${project.id}")
        val record = File(app, ".droide/workspace-relocation-${project.id}.json")
        val beforeRecord = record.readText()
        ExportWriterAdmission.beginWriter(retained, "receipt busy").use {
            val restart = ProjectWorkspaceRelocation(app)
            assertTrue(runCatching { restart.withLock { restart.recover(store) } }.isFailure)
            assertEquals(beforeRecord, record.readText())
        }
        val restart = ProjectWorkspaceRelocation(app)
        restart.withLock { restart.recover(store) }
        assertTrue(record.readText().contains("COMMITTED"))
        assertFalse(root.exists()); assertEquals("preserved", File(retained, "payload").readText())
        assertAvailable(root); assertAvailable(retained)
    }

    @Test fun readOnlyInspectionDoesNotReserveOrPublishRestoredReceipt() = fixture { app, store, _, project ->
        val authority = ProjectWorkspaceRelocation(app)
        authority.withLock {
            val before = store.load()
            authority.prepare(project, before, before.filterNot { it.id == project.id }, store)
        }
        val record = File(app, ".droide/workspace-relocation-${project.id}.json")
        val beforeRecord = record.readText()
        ExportWriterAdmission.beginWriter(File(project.rootPath), "read-only fixture").use {
            val restart = ProjectWorkspaceRelocation(app)
            restart.withLock(readOnly = true) { restart.recover(store, readOnly = true) }
        }
        assertEquals(beforeRecord, record.readText())
        assertEquals("preserved", File(project.rootPath, "payload").readText())
    }
}
