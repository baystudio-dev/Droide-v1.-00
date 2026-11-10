package com.baystudio.droide

import android.app.Application
import android.os.Process
import org.robolectric.shadows.ShadowProcess
import org.junit.Before
import org.junit.After
import android.os.Looper
import com.baystudio.droide.core.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Concrete process owner and concrete Coordinator, no native/CLI/transport/network startup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = WorkspaceRuntimeHostRecoveryTest.FixtureApplication::class)
class WorkspaceRuntimeHostRecoveryTest {
    private var previousPid = 0
    @Before fun prepareAndroidProcessIdentity() {
        previousPid = Process.myPid()
        ShadowProcess.setPid(4242)
        // This fixture uses its own Application, so it does not run DroideApplication's admission
        // initialization. Explicitly model a user-opened session for the host.
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }
    @After fun restoreAndroidProcessIdentity() { ShadowProcess.setPid(previousPid) }

    class FixtureApplication : Application() {
        override fun getFilesDir(): File = root
    }
    companion object {
        private val root = Files.createTempDirectory("recovery-host-app-").toFile().canonicalFile
        @JvmStatic @AfterClass fun cleanFixtureRoot() { PathSecurity.deleteTreeNoFollow(root) }
    }
    private class Store(val project: DroideProject) : ProjectStore {
        var saves = 0
        override suspend fun load() = listOf(project)
        override suspend fun loadActiveId() = project.id
        override suspend fun save(all: List<DroideProject>) { saves++ }
        override suspend fun saveActiveId(id: String) { saves++ }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("Fixture did not settle", condition())
    }

    @Test fun processOwnerKeepsSameDirtyEditorAndModeAcrossEitherRetryRequest() {
        val application = RuntimeEnvironment.getApplication() as FixtureApplication
        val projectRoot = File(root, "projects/existing").apply { mkdirs() }
        val source = File(projectRoot, "keep.kt").apply { writeText("saved source") }
        val store = Store(DroideProject("existing", "Existing", projectRoot.path))
        var recentSaves = 0
        val recents = object : ProjectRecentsStore {
            override suspend fun load(): List<String>? = listOf("existing")
            override suspend fun save(ids: List<String>) { recentSaves++ }
        }
        // ModelsDevCatalog.initialize has an existing fresh-cache early return. Validate the tiny
        // fixture with its actual parser before starting Host; never seed an empty root.
        val cache = File(root, "models-dev/api.json").apply {
            parentFile!!.mkdirs(); writeText("""{"fixture":{"id":"fixture","models":{}}}""")
        }
        ModelsDevCatalog.parse(cache.readText())
        val meta = File(cache.parentFile, "cache.meta").apply { writeText("fetchedAtMs=${System.currentTimeMillis()}") }
        val cacheBefore = cache.readText(); val metaBefore = meta.readText()
        val revisionBefore = ModelsDevCatalog.revision.value
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var constructions = 0; var processes = 0; var ubuntu = 0
        var ownedHost: WorkspaceRuntimeHost? = null
        try {
            val host = WorkspaceRuntimeHost(application) { context, scope, timers ->
                constructions++
                val bridge = DeviceBridgeManager(context, scope, DeviceBridgeDependencies(
                    configureIdentity = {}, create = { error("No transport") }, pair = { _, _, _ -> error("No pairing") },
                    discovery = { error("No discovery") },
                ))
                WorkspaceCoordinator(context, scope, ProjectManager(root, store, recents), browserApplicationTimers = timers,
                    deviceBridge = bridge,
                    processReconciliation = {
                        processes++; entered.complete(Unit); release.await()
                        error("Stop normal startup before installed components")
                    },
                    initialUbuntuPreparation = { ubuntu++; error("No automatic Ubuntu setup") },
                )
            }
            ownedHost = host
            await { entered.isCompleted }
            val starting = host.coordinator.startup.value
            host.initializeWorkspace(WorkspaceStartupMode.TEMPORARY_BUILTINS)
            assertEquals(starting, host.coordinator.startup.value)
            assertNull(host.coordinator.runtime.value)
            release.complete(Unit)
            await { host.initError.value != null }
            assertEquals(WorkspaceStartupPhase.FAILED, host.coordinator.startup.value.phase)
            assertEquals(1, processes)
            host.initializeWorkspace(WorkspaceStartupMode.TEMPORARY_BUILTINS)
            await { host.coordinator.runtime.value != null && host.coordinator.startup.value.phase == WorkspaceStartupPhase.READY }
            val runtime = checkNotNull(host.coordinator.runtime.value)
            val editor = host.editorStateFor(runtime)
            val explorer = host.explorerStateFor(runtime)
            val document = editor.document("keep.kt")
            runBlocking { document.ensureLoaded(runtime.files) }
            document.capture("unsaved host sentinel")
            val dirty = runBlocking { runtime.documentAuthority.dirtySnapshots() }
            assertEquals("unsaved host sentinel", dirty.single().content)
            val ready = host.coordinator.startup.value
            host.initializeWorkspace()
            host.initializeWorkspace(WorkspaceStartupMode.TEMPORARY_BUILTINS)
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(runtime, host.coordinator.runtime.value)
            assertSame(editor, host.editorStateFor(runtime))
            assertSame(explorer, host.explorerStateFor(runtime))
            assertEquals(ready, host.coordinator.startup.value)
            assertEquals(dirty, runBlocking { runtime.documentAuthority.dirtySnapshots() })
            assertEquals(WorkspaceStartupMode.TEMPORARY_BUILTINS, runtime.startupMode)
            assertNull(host.initError.value)
            assertEquals("saved source", source.readText())
            assertEquals(1, constructions); assertEquals(1, processes); assertEquals(0, ubuntu)
            assertEquals(0, store.saves); assertEquals(0, recentSaves)
            await { ModelsDevCatalog.revision.value > revisionBefore }
            assertEquals(cacheBefore, cache.readText()); assertEquals(metaBefore, meta.readText())
        } finally { release.complete(Unit); ownedHost?.close() }
    }
}
