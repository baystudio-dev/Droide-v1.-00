package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Looper
import android.os.Process
import com.baystudio.droide.ui.EditorWorkspaceDocumentAuthority
import com.baystudio.droide.ui.EditorWorkspaceState
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkspaceStoredProjectAdmissionTest {
    companion object {
        private val applicationRoot = Files.createTempDirectory("stored-project-admission-").toFile().canonicalFile
    }
    @Test(timeout = 15_000) fun directStoredSelectionIsRejectedBeforeEditorHandoffOrRuntimePreparation() =
        runOnAndroidMain { scenario(openThroughSaf = false) }

    @Test(timeout = 15_000) fun storedSafTreeCannotBeRestoredBeforePendingEditorPreservationSucceeds() =
        runOnAndroidMain { scenario(openThroughSaf = true) }

    private suspend fun scenario(openThroughSaf: Boolean) {
        val app = applicationRoot.apply { check(isDirectory || mkdirs()) }
        val previousPid = Process.myPid()
        ShadowProcess.setPid(4242)
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = app
            override fun getCacheDir() = File(app, "cache").apply { mkdirs() }
        }
        ApplicationWorkAdmission.initialize(context)
        ApplicationWorkAdmission.reopenFromUser()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val uri = Uri.parse("content://unregistered.fixture/tree/primary%3AProject")
        val activeRoot = File(app, "projects/default").apply { mkdirs() }
        val storedRoot = File(app, "projects/linked").apply { mkdir() }
        val activeSource = File(activeRoot, "main.kt").apply { writeText("saved active source") }
        val storedSource = File(storedRoot, "source.kt").apply { writeText("stored unpublished source") }
        val records = listOf(
            DroideProject("default", "Default", activeRoot.path),
            DroideProject("linked", "Linked", storedRoot.path, uri.toString(), workspaceRemoved = true),
        )
        val disk = FileProjectStore(app)
        disk.save(records)
        disk.saveActiveId("default")
        var writes = 0
        val store = object : ProjectStore by disk {
            override suspend fun save(all: List<DroideProject>) { writes++; disk.save(all) }
            override suspend fun saveActiveId(id: String) { writes++; disk.saveActiveId(id) }
        }
        val recents = object : ProjectRecentsStore {
            override suspend fun load() = listOf("default")
            override suspend fun save(ids: List<String>) { writes++ }
        }
        val manager = ProjectManager(app, store, recents)
        val bridge = DeviceBridgeManager(context, scope, DeviceBridgeDependencies(
            configureIdentity = {}, create = { error("No transport") }, pair = { _, _, _ -> error("No pairing") },
            discovery = { error("No discovery") },
        ))
        var ubuntuPreparations = 0
        val coordinator = WorkspaceCoordinator(context, scope, manager, deviceBridge = bridge,
            processReconciliation = { error("Enter built-in recovery fixture") },
            initialUbuntuPreparation = { ubuntuPreparations++; error("No Ubuntu preparation") })
        var binding: WorkspaceDocumentAuthorityBridge.Binding? = null
        var gate: WorkspaceHandoffGate? = null
        var retained: WorkspaceHandoffRetention? = null
        try {
            assertTrue(runCatching { coordinator.init() }.isFailure)
            coordinator.init(WorkspaceStartupMode.TEMPORARY_BUILTINS)
            val active = checkNotNull(coordinator.runtime.value)
            val editor = EditorWorkspaceState()
            val document = editor.document("main.kt")
            document.ensureLoaded(active.files)
            binding = active.documentAuthority.bind(EditorWorkspaceDocumentAuthority(
                editor, active.files, active.lsp, Dispatchers.Unconfined))
            document.capture("unsaved active editor work")
            val catalog = File(app, ".droide/projects.json")
            val catalogBefore = catalog.readBytes()
            val selection = File(app, ".droide/active_project.txt")
            val selectionBefore = selection.readBytes()
            val preservationFailure = IllegalStateException("Editor preservation is still pending")
            var preservationRetries = 0
            val handoff = WorkspaceCoordinator::class.java.getDeclaredField("editorHandoff").let {
                it.isAccessible = true
                it.get(coordinator) as WorkspaceHandoffGate
            }
            gate = handoff
            val pending = object : WorkspaceHandoffRetention {
                override suspend fun retryPreservation() {
                    preservationRetries++
                    throw preservationFailure
                }
            }
            retained = pending
            handoff.reserve(pending)
            handoff.publishOwner(active)

            val failure = runCatching {
                if (openThroughSaf) coordinator.addSafProject(
                    Uri.parse("content://unregistered.fixture/tree/primary%3aProject"), "Do not duplicate")
                else coordinator.switchProject("linked")
            }.exceptionOrNull()

            assertNotNull("The stored project must not pass the guarded boundary", failure)
            if (openThroughSaf) {
                assertSame(preservationFailure, failure)
                assertEquals(1, preservationRetries)
            } else {
                assertTrue(failure.toString(), failure!!.message.orEmpty().contains("Restore the stored project"))
                assertEquals(0, preservationRetries)
            }
            assertSame(active, coordinator.runtime.value)
            assertEquals("default", manager.activeId.value)
            assertEquals(records, manager.projects.value)
            assertEquals(records, disk.load())
            assertEquals(listOf(records[1]), manager.storedProjects.value)
            assertEquals(0, writes)
            assertEquals(0, ubuntuPreparations)
            assertArrayEquals(catalogBefore, catalog.readBytes())
            assertArrayEquals(selectionBefore, selection.readBytes())
            assertTrue(document.dirty)
            assertEquals("unsaved active editor work", document.content)
            assertEquals("saved active source", activeSource.readText())
            assertEquals("stored unpublished source", storedSource.readText())
            assertEquals(uri.toString(), manager.storedProjects.value.single().treeUri)
        } finally {
            retained?.let { gate?.clear(it) }
            binding?.close()
            coordinator.close(); bridge.close(); scope.cancel()
            ShadowProcess.setPid(previousPid)
            PathSecurity.deleteTreeNoFollow(app)
        }
    }

    private fun runOnAndroidMain(block: suspend () -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var failure: Throwable? = null
        val job = scope.launch {
            try { withTimeout(10_000) { block() } }
            catch (caught: Throwable) { failure = caught }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (!job.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertTrue("Bounded stored-project admission fixture did not settle", job.isCompleted)
            failure?.let { throw it }
        } finally { job.cancel(); scope.cancel() }
    }
}
