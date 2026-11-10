package com.baystudio.droide.core

import android.app.Application
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ExportScopedHistoryTest {
    private val home = Files.createTempDirectory("export-scoped-history-").toFile()
    private val root = File(home, "project").apply { mkdir() }
    private val history = SqlitePluginConversationHistory(root)
    private val documents = object : WorkspaceDocumentAuthority {
        override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
        override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = emptyList()
    }
    private fun owner() = ExportSourceAdmission("project", root, documents, {},
        requireEpoch = { "epoch" }, epochOpen = { it == "epoch" })
    @After fun cleanup() = runBlocking { history.close(); PathSecurity.deleteTreeNoFollow(home); Unit }

    @Test fun idleChatDatabaseDoesNotBlockSelectedApkOrBuildFolder() = runBlocking {
        history.listPage()
        val bytes = byteArrayOf(0, -1, 13, 10, 42)
        File(root, "build/app.apk").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        File(root, "build/empty").mkdir()
        val owner = owner()
        for (path in listOf("build/app.apk", "build")) {
            val ticket = owner.ticket(ExportScope(path))
            assertTrue(owner.preflight(ticket).blockers.toString(), owner.preflight(ticket).canChooseDestination)
            owner.withCapture(ticket, true) { permit ->
                ExportCaptureStore(File(home, "staging")).capture(permit).use { capture ->
                    val payloads = mutableListOf<ByteArray>()
                    ExportManifest.each(capture.manifest, capture.manifestDigest) {
                        if (it.type == ExportEntryType.FILE) payloads += capture.data(it.dataId).readBytes()
                    }
                    assertEquals(1, payloads.size)
                    assertArrayEquals(bytes, payloads.single())
                }
            }
            history.listPage() // Export did not dispose or checkpoint the history owner.
        }
    }

    @Test fun coordinatedCaptureCanPrepareDatabaseAndItsParentsWithoutClosingHistory() = runBlocking {
        history.listPage()
        val owner = owner()
        for (path in listOf("", ".droide", ".droide/sessions", ".droide/sessions/plugins.sqlite")) {
            val ticket = owner.ticket(ExportScope(path))
            assertTrue(owner.preflight(ticket).canChooseDestination)
            owner.withCapture(ticket, true) { it.validate() }
            history.listPage()
            // The low-level path still refuses to bypass owner preparation.
            assertThrows(IllegalStateException::class.java) { ExportWriterAdmission.beginCapture(root) }
        }
        history.close()
        val ticket = owner.ticket(ExportScope(""))
        assertTrue(owner.preflight(ticket).canChooseDestination)
        owner.withCapture(ticket, true) { it.validate() }
    }

    @Test fun selectedSubtreeFenceAllowsSiblingsButRejectsParentAndSelectedWriters() = runBlocking {
        File(root, "build").mkdir()
        File(root, "build-other").mkdir()
        val owner = owner()
        val ticket = owner.ticket(ExportScope("build"))
        ExportWriterAdmission.beginWriter(File(root, "build-other"), "Sibling download").use {
            assertTrue(owner.preflight(ticket).canChooseDestination)
            owner.withCapture(ticket, true) {
                ExportWriterAdmission.beginWriter(File(root, "build-other"), "Sibling write").close()
                assertThrows(ExportCaptureBusyException::class.java) { ExportWriterAdmission.beginWriter(root, "Workspace write") }
                assertThrows(ExportCaptureBusyException::class.java) { ExportWriterAdmission.beginWriter(File(root, "build/app.apk"), "Selected write") }
            }
        }
        // A writer starting after a clear preflight must still block capture.
        ExportWriterAdmission.beginWriter(File(root, "build/app.apk"), "Late build output").use {
            assertFalse(owner.preflight(ticket).canChooseDestination)
            assertThrows(IllegalStateException::class.java) {
                runBlocking { owner.withCapture(ticket, true) { fail("Late writer was ignored") } }
            }
        }
        Unit
    }
}
