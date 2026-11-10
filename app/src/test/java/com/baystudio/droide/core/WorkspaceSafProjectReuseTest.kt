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

/** Actual Coordinator: selecting the active SAF tree must not touch its provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkspaceSafProjectReuseTest {
    @Test(timeout = 15_000) fun reopeningActiveExternalFolderRetainsRuntimeDirtyEditorAndProjectIdentityWithoutProviderQuery() = runOnAndroidMain {
        val appRoot = Files.createTempDirectory("saf-project-reuse-").toFile().canonicalFile
        val previousPid = Process.myPid()
        ShadowProcess.setPid(4242)
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = appRoot
        }
        ApplicationWorkAdmission.initialize(context)
        ApplicationWorkAdmission.reopenFromUser()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val uri = Uri.parse("content://unregistered.fixture/tree/primary%3AProject")
        val root = File(appRoot, "projects/linked").apply { mkdirs() }
        File(appRoot, "projects/default").mkdirs()
        val source = File(root, "main.kt").apply { writeText("saved source") }
        val records = listOf(
            DroideProject("default", "Default", File(appRoot, "projects/default").path),
            DroideProject("linked", "Linked", root.path, uri.toString()),
        )
        var writes = 0
        val store = object : ProjectStore {
            override suspend fun load() = records
            override suspend fun loadActiveId() = "linked"
            override suspend fun save(all: List<DroideProject>) { writes++ }
            override suspend fun saveActiveId(id: String) { writes++ }
        }
        val recents = object : ProjectRecentsStore {
            override suspend fun load() = listOf("linked")
            override suspend fun save(ids: List<String>) { writes++ }
        }
        val manager = ProjectManager(appRoot, store, recents)
        val bridge = DeviceBridgeManager(context, scope, DeviceBridgeDependencies(
            configureIdentity = {}, create = { error("No transport") }, pair = { _, _, _ -> error("No pairing") },
            discovery = { error("No discovery") },
        ))
        val coordinator = WorkspaceCoordinator(context, scope, manager, deviceBridge = bridge,
            processReconciliation = { error("Enter builtin recovery fixture") },
            initialUbuntuPreparation = { error("Repeated setup is forbidden") })
        var binding: WorkspaceDocumentAuthorityBridge.Binding? = null
        try {
            assertTrue(runCatching { coordinator.init() }.isFailure)
            coordinator.init(WorkspaceStartupMode.TEMPORARY_BUILTINS)
            val active = checkNotNull(coordinator.runtime.value)
            val editor = EditorWorkspaceState()
            val document = editor.document("main.kt")
            document.ensureLoaded(active.files)
            binding = active.documentAuthority.bind(EditorWorkspaceDocumentAuthority(
                editor, active.files, active.lsp, Dispatchers.Unconfined))
            document.capture("unsaved user work")
            val selected = coordinator.addSafProject(
                Uri.parse("content://unregistered.fixture/tree/primary%3aProject"), "New name should not duplicate")
            assertEquals("linked", selected.id)
            assertEquals("Linked", selected.name)
            assertEquals(uri.toString(), selected.treeUri)
            assertSame(active, coordinator.runtime.value)
            assertEquals(2, manager.projects.value.size)
            assertEquals(0, writes)
            assertTrue(document.dirty)
            assertEquals("unsaved user work", document.content)
            assertEquals("saved source", source.readText())

            // A previous owner's retained recovery is unrelated to reselecting the current project. retried
            // it before noticing the same ID, which could fail an otherwise harmless drawer selection.
            val handoff = WorkspaceCoordinator::class.java.getDeclaredField("editorHandoff").let {
                it.isAccessible = true
                it.get(coordinator) as WorkspaceHandoffGate
            }
            var preservationRetries = 0
            val retained = object : WorkspaceHandoffRetention {
                override suspend fun retryPreservation() {
                    preservationRetries++
                    error("previous owner preservation still pending")
                }
            }
            handoff.reserve(retained)
            handoff.publishOwner(active)
            coordinator.switchProject("linked")
            assertEquals(0, preservationRetries)
            assertTrue("Reselection must not discard retained recovery", runCatching { handoff.requireClear() }.isFailure)
            handoff.clear(retained)
            assertSame(active, coordinator.runtime.value)
            assertEquals("linked", manager.activeId.value)
            assertEquals(0, writes)
            assertTrue(document.dirty)
            assertEquals("unsaved user work", document.content)
            assertEquals("saved source", source.readText())
        } finally {
            binding?.close(); coordinator.close(); bridge.close(); scope.cancel()
            ShadowProcess.setPid(previousPid)
            PathSecurity.deleteTreeNoFollow(appRoot)
        }
    }

    private fun runOnAndroidMain(block: suspend () -> Unit) {
        val fixtureScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var failure: Throwable? = null
        val fixtureJob = fixtureScope.launch {
            try { withTimeout(10_000) { block() } }
            catch (caught: Throwable) { failure = caught }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (!fixtureJob.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertTrue("Bounded SAF fixture did not settle", fixtureJob.isCompleted)
            failure?.let { throw it }
        } finally { fixtureJob.cancel(); fixtureScope.cancel() }
    }
}
