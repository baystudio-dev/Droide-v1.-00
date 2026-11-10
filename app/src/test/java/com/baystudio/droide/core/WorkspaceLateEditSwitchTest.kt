package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.os.Process
import com.baystudio.droide.ui.EditorWorkspaceDocumentAuthority
import com.baystudio.droide.ui.EditorWorkspaceState
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

/** Run this class in its own Robolectric sandbox, as with WorkspaceTemporaryRecoveryTest. */

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkspaceLateEditSwitchTest {
    companion object {
        // LocalExecutionSubstrate admits only one application root per sandbox/process.
        private val appRoot = Files.createTempDirectory("r29-late-switch-").toFile().canonicalFile
        @JvmStatic @AfterClass fun removeFixture() { PathSecurity.deleteTreeNoFollow(appRoot) }
    }

    private var previousPid = 0
    @Before fun setFixturePid() {
        previousPid = Process.myPid(); ShadowProcess.setPid(4242)
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }
    @After fun restorePid() { ShadowProcess.setPid(previousPid) }

    private class GateStore(private val records: List<DroideProject>) : ProjectStore {
        var selected = "default"
        val beforeTargetCommit = CompletableDeferred<Unit>()
        val allowTargetCommit = CompletableDeferred<Unit>()
        var failTargetCommit = false
        var cancelAfterTargetCommit = false
        var targetCaller: Job? = null
        override suspend fun load(): List<DroideProject> = records
        override suspend fun loadActiveId(): String = selected
        override suspend fun save(all: List<DroideProject>) = Unit
        override suspend fun saveActiveId(id: String) {
            if (id == "second") {
                beforeTargetCommit.complete(Unit)
                allowTargetCommit.await()
                if (failTargetCommit) throw IOException("fixture active-ID persistence failed before commit")
            }
            selected = id
            if (id == "second" && cancelAfterTargetCommit) {
                checkNotNull(targetCaller).cancel(CancellationException("fixture: selected ID committed"))
                currentCoroutineContext().ensureActive()
            }
        }
    }

    private class Recents : ProjectRecentsStore {
        override suspend fun load(): List<String> = listOf("default", "second")
        override suspend fun save(ids: List<String>) = Unit
    }

    @Test(timeout = 15_000)
    fun lateTypingDuringActiveIdPreparationRefusesHandoffAndKeepsOldWorkspace() =
        scenario(editAfterPreparation = true, failPersistence = false)

    @Test(timeout = 15_000)
    fun noLateEditStillAllowsThePreparedProjectToCommit() =
        scenario(editAfterPreparation = false, failPersistence = false)

    @Test(timeout = 15_000)
    fun failedActiveIdPersistenceKeepsLateTypingAndOldWorkspace() =
        scenario(editAfterPreparation = true, failPersistence = true)

    @Test(timeout = 15_000)
    fun cancellationAfterActiveIdCommitKeepsThePublishedRuntimeAlive() =
        scenario(editAfterPreparation = false, failPersistence = false, cancelAfterCommit = true)

    @Test(timeout = 15_000)
    fun cancellationAfterActiveIdCommitStillRefusesLateDirtyEditor() =
        scenario(editAfterPreparation = true, failPersistence = false, cancelAfterCommit = true)

    private fun scenario(editAfterPreparation: Boolean, failPersistence: Boolean, cancelAfterCommit: Boolean = false) {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = appRoot
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var failure: Throwable? = null
        val test = scope.launch {
            try {
                withTimeout(10_000) {
                    val source = File(appRoot, "projects/default/keep.kt").apply {
                        parentFile!!.mkdirs(); writeText("saved source")
                    }
                    File(appRoot, "projects/second/keep.kt").apply {
                        parentFile!!.mkdirs(); writeText("target source")
                    }
                    val projects = listOf("default", "second").map {
                        DroideProject(it, it, File(appRoot, "projects/$it").path)
                    }
                    val store = GateStore(projects).apply {
                        failTargetCommit = failPersistence
                        cancelAfterTargetCommit = cancelAfterCommit
                    }
                    val manager = ProjectManager(appRoot, store, Recents())
                    val bridge = DeviceBridgeManager(context, scope, DeviceBridgeDependencies(
                        configureIdentity = {}, create = { error("No transport") },
                        pair = { _, _, _ -> error("No pairing") }, discovery = { error("No discovery") },
                    ))
                    val coordinator = WorkspaceCoordinator(context, scope, manager, deviceBridge = bridge,
                        processReconciliation = { error("Fixture stops normal startup before installed components") },
                        initialUbuntuPreparation = { error("No Ubuntu preparation") },
                    )
                    var binding: WorkspaceDocumentAuthorityBridge.Binding? = null
                    var switching: Deferred<Result<Unit>>? = null
                    try {
                        assertNotNull(runCatching { coordinator.init() }.exceptionOrNull())
                        coordinator.init(WorkspaceStartupMode.TEMPORARY_BUILTINS)
                        val oldRuntime = checkNotNull(coordinator.runtime.value)
                        assertEquals(WorkspaceStartupMode.TEMPORARY_BUILTINS, oldRuntime.startupMode)
                        val state = EditorWorkspaceState()
                        val document = state.document("keep.kt")
                        document.ensureLoaded(oldRuntime.files)
                        binding = oldRuntime.documentAuthority.bind(
                            EditorWorkspaceDocumentAuthority(state, oldRuntime.files, oldRuntime.lsp, Dispatchers.Main.immediate),
                        )

                        assertTrue(oldRuntime.documentAuthority.dirtySnapshots().isEmpty())
                        var switchOutcome: Result<Unit>? = null
                        val switch = async {
                            store.targetCaller = currentCoroutineContext()[Job]
                            runCatching { coordinator.switchProject("second") }.also { switchOutcome = it }
                        }
                        switching = switch
                        store.beforeTargetCommit.await()
                        assertSame(oldRuntime, coordinator.runtime.value)
                        if (editAfterPreparation) document.capture("typed while project switch was preparing")
                        store.allowTargetCommit.complete(Unit)
                        switch.join()
                        val outcome = checkNotNull(switchOutcome)
                        if (editAfterPreparation || failPersistence) {
                            assertTrue("A late edit must refuse handoff instead of abandoning the old editor", outcome.isFailure)
                            assertSame(oldRuntime, coordinator.runtime.value)
                            assertEquals("default", manager.active()?.id)
                            assertEquals("default", store.selected)
                            assertEquals("typed while project switch was preparing", document.content)
                            assertTrue(document.dirty)
                            assertEquals(document.content, oldRuntime.documentAuthority.dirtySnapshots().single().content)
                        } else {
                            if (cancelAfterCommit) assertTrue(outcome.exceptionOrNull() is CancellationException)
                            else assertTrue(outcome.isSuccess)
                            assertEquals("second", coordinator.runtime.value?.project?.id)
                            assertEquals("second", manager.active()?.id)
                            assertEquals("second", store.selected)
                            assertFalse(document.dirty)
                            // TEMPORARY_BUILTINS activation cannot start a CLI. A mistakenly discarded committed candidate
                            // would reject this as already closed.
                            checkNotNull(coordinator.runtime.value).extensions.activateCli()
                            assertTrue(runCatching { oldRuntime.extensions.activateCli() }.isFailure)
                        }
                        assertEquals("saved source", source.readText())
                    } finally {
                        store.allowTargetCommit.complete(Unit)
                        switching?.cancelAndJoin()
                        binding?.close()
                        coordinator.close()
                        bridge.close()
                    }
                }
            } catch (caught: Throwable) { failure = caught }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (!test.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertTrue("Bounded fixture did not settle", test.isCompleted)
            failure?.let { throw it }
        } finally { test.cancel(); scope.cancel() }
    }
}
