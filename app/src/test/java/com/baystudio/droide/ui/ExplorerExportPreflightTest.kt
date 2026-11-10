package com.baystudio.droide.ui

import android.app.Application
import com.baystudio.droide.core.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Exercises the production pre-picker boundary; no DocumentsUI launch or provider write is faked
 * as success. Real SQLite lifetime admission is included without closing it during inspection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ExplorerExportPreflightTest {
    private val root = Files.createTempDirectory("export-preflight-").toFile()
    private val histories = mutableListOf<SqlitePluginConversationHistory>()
    private val documents = object : WorkspaceDocumentAuthority {
        override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
        override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = emptyList()
    }

    @After fun cleanup() = runBlocking {
        histories.forEach { it.close() }
        assertTrue(PathSecurity.deleteTreeNoFollow(root))
    }

    private fun admission(
        directory: File = root,
        blockers: () -> List<String> = { emptyList() },
        safe: (ExportScope) -> Unit = {},
    ) = ExportSourceAdmission("project", directory, documents, {}, requireSourceSafe = safe,
        knownWriterBlockers = blockers, requireEpoch = { "epoch" }, epochOpen = { it == "epoch" })

    private fun files(): Map<String, Triple<Boolean, Long, String>> =
        root.walkTopDown().associate {
            it.relativeTo(root).invariantSeparatorsPath to
                Triple(it.isDirectory, it.lastModified(), if (it.isDirectory) "" else
                    MessageDigest.getInstance("SHA-256").digest(it.readBytes())
                        .joinToString("") { byte -> "%02x".format(byte) })
        }

    @Test fun knownBuildAndAgentBlockBeforePickerAndRetryUsesFreshObservation() = runBlocking {
        var busy = true
        val owner = admission(blockers = {
            if (busy) listOf("Build/Run/Debug task is active", "Agent task is active") else emptyList()
        })
        val ticket = owner.ticket(ExportScope(""))
        var launches = 0
        val blocked = chooseExportDestinationIfReady(owner, ticket) { launches++ }
        assertFalse(blocked.canChooseDestination)
        assertEquals(listOf("Agent task is active", "Build/Run/Debug task is active"), blocked.blockers)
        assertEquals(0, launches)
        busy = false
        assertTrue(chooseExportDestinationIfReady(owner, ticket) { launches++ }.canChooseDestination)
        assertEquals(1, launches)
    }

    @Test fun mappedWritersIncludeOverlappingRootsButNotUnrelatedWorkspaces() = runBlocking {
        val project = File(root, "project").apply { mkdir() }
        val child = File(project, "nested").apply { mkdir() }
        val unrelated = File(root, "other").apply { mkdir() }
        val owner = admission(project)
        val ticket = owner.ticket(ExportScope(""))
        var launches = 0
        ExportWriterAdmission.beginWriter(unrelated, "Unrelated writer").use {
            assertTrue(owner.preflight(ticket).canChooseDestination)
            ExportWriterAdmission.beginWriter(child, "Nested writer").use {
                val blocked = chooseExportDestinationIfReady(owner, ticket) { launches++ }
                assertEquals(listOf("Nested writer"), blocked.blockers)
                assertEquals(0, launches)
            }
        }
        assertTrue(chooseExportDestinationIfReady(owner, ticket) { launches++ }.canChooseDestination)
        assertEquals(1, launches)
    }

    @Test fun clearPreflightIsNotAReservationAndLateWriterStillBlocksCapture() = runBlocking {
        File(root, "source.txt").writeText("unchanged")
        val before = files()
        val owner = admission()
        val ticket = owner.ticket(ExportScope(""))
        repeat(3) { assertTrue(owner.preflight(ticket).canChooseDestination) }
        var captured = false
        ExportWriterAdmission.beginWriter(root, "Late writer").use {
            val failure = assertThrows(IllegalStateException::class.java) {
                runBlocking { owner.withCapture(ticket, true) { captured = true } }
            }
            assertTrue(failure.message.orEmpty().contains("Late writer"))
        }
        assertFalse(captured)
        assertEquals(before, files())
        owner.withCapture(ticket, true) { captured = true }
        assertTrue(captured)
    }

    @Test fun activeCaptureIsVisibleWithoutTryingToAcquireAnotherLease() = runBlocking {
        val owner = admission()
        val ticket = owner.ticket(ExportScope(""))
        var launches = 0
        owner.withCapture(ticket, true) {
            val blocked = chooseExportDestinationIfReady(owner, ticket) { launches++ }
            assertEquals(listOf("Another export is capturing this workspace"), blocked.blockers)
        }
        assertEquals(0, launches)
        assertTrue(owner.preflight(ticket).canChooseDestination)
    }

    @Test fun idleManagedDatabaseAllowsPickerWithoutCheckpointCloseOrByteChanges() = runBlocking {
        val history = SqlitePluginConversationHistory(root).also { histories += it }
        history.listPage()
        val owner = admission()
        val ticket = owner.ticket(ExportScope(""))
        val before = files()
        var launches = 0
        val ready = chooseExportDestinationIfReady(owner, ticket) { launches++ }
        assertTrue(ready.canChooseDestination)
        assertTrue(ready.blockers.isEmpty())
        assertEquals(1, launches)
        assertEquals(before, files())
        // The same open history owner remains usable. Preflight did not dispose it.
        history.listPage()
        assertTrue(owner.preflight(ticket).canChooseDestination)
        // Explicit fixture-owner disposal represents the existing project lifecycle, not preflight.
        history.close()
        assertTrue(chooseExportDestinationIfReady(owner, ticket) { launches++ }.canChooseDestination)
        assertEquals(2, launches)
        assertTrue(File(root, ".droide/sessions/plugins.sqlite").isFile)
    }

    @Test fun sourceSafetyFailureAndRetiredPickerNeverLaunchDestination() = runBlocking {
        var safe = false
        val owner = admission(safe = { check(safe) { "Pending linked-source recovery" } })
        val ticket = owner.ticket(ExportScope(""))
        var launches = 0
        assertThrows(IllegalStateException::class.java) {
            runBlocking { chooseExportDestinationIfReady(owner, ticket) { launches++ } }
        }
        assertEquals(0, launches)
        assertTrue(root.listFiles().orEmpty().isEmpty())
        safe = true
        owner.retire()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { chooseExportDestinationIfReady(owner, ticket) { launches++ } }
        }
        assertEquals(0, launches)
    }
}
