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

/** Exercises the actual staged Coordinator without a transport, provider request, or Ubuntu. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkspaceProviderConfigurationOwnershipTest {
    companion object {
        private val appRoot = Files.createTempDirectory("provider-project-handoff-").toFile().canonicalFile
        @JvmStatic @AfterClass fun removeFixture() { PathSecurity.deleteTreeNoFollow(appRoot) }
    }

    private var previousPid = 0
    @Before fun prepareFixture() {
        previousPid = Process.myPid()
        ShadowProcess.setPid(4242)
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
        ProviderRegistry.installConfigured(emptyList())
    }
    @After fun restoreFixture() {
        ProviderRegistry.installConfigured(emptyList())
        ShadowProcess.setPid(previousPid)
    }

    private class GateStore(private val records: List<DroideProject>, val fail: Boolean) : ProjectStore {
        var selected = "default"
        val prepared = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun load(): List<DroideProject> = records
        override suspend fun loadActiveId(): String = selected
        override suspend fun save(all: List<DroideProject>) = Unit
        override suspend fun saveActiveId(id: String) {
            if (id == "second") {
                prepared.complete(Unit)
                release.await()
                if (fail) throw IOException("fixture active-ID persistence failed")
            }
            selected = id
        }
    }

    private class Recents : ProjectRecentsStore {
        override suspend fun load(): List<String> = listOf("default", "second")
        override suspend fun save(ids: List<String>) = Unit
    }

    @Test(timeout = 15_000)
    fun failedSelectionPersistenceKeepsActiveProjectProvider() = scenario(failPersistence = true)

    @Test(timeout = 15_000)
    fun lateDirtyEditorKeepsActiveProjectProvider() = scenario(lateEdit = true)

    @Test(timeout = 15_000)
    fun candidateProviderPublishesOnlyWithSuccessfulRuntimeHandoff() = scenario()

    private fun scenario(failPersistence: Boolean = false, lateEdit: Boolean = false) {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = appRoot
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var failure: Throwable? = null
        val test = scope.launch {
            try {
                withTimeout(10_000) {
                    val records = listOf("default", "second").map { id ->
                        val root = File(appRoot, "projects/$id").apply { mkdirs() }
                        File(root, "keep.kt").writeText("saved $id")
                        File(root, ".droide/providers.json").apply {
                            parentFile!!.mkdirs()
                            writeText("""{"providers":[{"id":"custom.project","name":"Project $id","baseUrl":"https://$id.example/v1","auth":"none"}]}""")
                        }
                        DroideProject(id, id, root.path)
                    }
                    val store = GateStore(records, failPersistence)
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
                        val original = checkNotNull(coordinator.runtime.value)
                        assertEquals("https://default.example/v1", activeEndpoint())
                        val editor = EditorWorkspaceState()
                        val document = editor.document("keep.kt")
                        document.ensureLoaded(original.files)
                        binding = original.documentAuthority.bind(EditorWorkspaceDocumentAuthority(
                            editor, original.files, original.lsp, Dispatchers.Main.immediate,
                        ))

                        switching = async { runCatching { coordinator.switchProject("second") } }
                        store.prepared.await()
                        // Candidate B and every service have been built; A still owns the UI.
                        assertSame(original, coordinator.runtime.value)
                        assertEquals("https://default.example/v1", activeEndpoint())
                        if (lateEdit) document.capture("late edit while B was preparing")
                        store.release.complete(Unit)
                        val outcome = switching.await()

                        if (failPersistence || lateEdit) {
                            assertTrue(outcome.isFailure)
                            assertSame(original, coordinator.runtime.value)
                            assertEquals("default", manager.active()?.id)
                            assertEquals("default", store.selected)
                            assertEquals("https://default.example/v1", activeEndpoint())
                            if (lateEdit) {
                                assertTrue(document.dirty)
                                assertEquals("late edit while B was preparing", document.content)
                            }
                        } else {
                            assertTrue(outcome.exceptionOrNull()?.toString(), outcome.isSuccess)
                            assertEquals("second", coordinator.runtime.value?.project?.id)
                            assertEquals("second", manager.active()?.id)
                            assertEquals("https://second.example/v1", activeEndpoint())
                        }
                        assertEquals("saved default", File(original.files.root, "keep.kt").readText())
                    } finally {
                        store.release.complete(Unit)
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
        } finally {
            test.cancel()
            scope.cancel()
        }
    }

    private fun activeEndpoint(): String? = ProviderRegistry.findById("custom.project")?.baseUrl
}
