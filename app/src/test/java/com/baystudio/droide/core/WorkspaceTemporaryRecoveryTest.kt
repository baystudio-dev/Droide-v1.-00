package com.baystudio.droide.core

import android.app.Application
import android.os.Process
import org.robolectric.shadows.ShadowProcess
import org.junit.Before
import org.junit.After
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import com.baystudio.droide.ui.EditorWorkspaceState
import com.baystudio.droide.ui.EditorWorkspaceDocumentAuthority
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.AfterClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual Coordinator/runtime assembly; native, CLI and transport startup are never invoked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkspaceTemporaryRecoveryTest {
    private var previousPid = 0
    @Before fun prepareAndroidProcessIdentity() {
        previousPid = Process.myPid()
        ShadowProcess.setPid(4242)
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }
    @After fun restoreAndroidProcessIdentity() { ShadowProcess.setPid(previousPid) }

    companion object {
        // LocalExecutionSubstrate intentionally permits only one application root per process.
        private val appRoot = Files.createTempDirectory("temporary-startup-app-").toFile().canonicalFile
        @JvmStatic @AfterClass fun cleanFixtureRoot() { PathSecurity.deleteTreeNoFollow(appRoot) }
    }
    private class Store(val records: List<DroideProject>, var selected: String?) : ProjectStore {
        var reads = 0; var saves = 0
        override suspend fun load(): List<DroideProject> { reads++; return records }
        override suspend fun loadActiveId(): String? { reads++; return selected }
        override suspend fun save(all: List<DroideProject>) { saves++ }
        override suspend fun saveActiveId(id: String) { saves++; selected = id }
    }
    private class Recents : ProjectRecentsStore {
        var saves = 0
        override suspend fun load(): List<String>? = listOf("default", "second")
        override suspend fun save(ids: List<String>) { saves++ }
    }
    private fun context() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = appRoot
    }
    private fun file(path: String, text: String): File = File(appRoot, path).apply { parentFile!!.mkdirs(); writeText(text) }
    private fun snapshot(root: File): Map<String, String> = if (!root.exists()) emptyMap() else root.walkTopDown().associate {
        it.relativeTo(root).path to if (it.isDirectory) "directory" else it.readBytes().joinToString("") { b -> "%02x".format(b) }
    }

    @Test(timeout = 15_000)
    fun explicitRecoveryPreservesInstalledStateAndLiveDirtyRuntimeThenCarriesAcrossProjectSwitch() = runOnAndroidMain {
        val context = context()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var fixtureBridge: DeviceBridgeManager? = null
        try {
            val bridge = DeviceBridgeManager(context, scope, DeviceBridgeDependencies(
                configureIdentity = {}, create = { error("No transport") }, pair = { _, _, _ -> error("No pairing") },
                discovery = { error("No discovery") },
            ))
            fixtureBridge = bridge
            val source = file("projects/default/keep.kt", "saved source")
            file("projects/second/keep.kt", "second source")
            val projects = listOf("default", "second").map { DroideProject(it, it, File(appRoot, "projects/$it").absolutePath) }
            val store = Store(projects, "default"); val recents = Recents()
            val manager = ProjectManager(appRoot, store, recents)
            file("declarative-extensions/index.json", "corrupt declarative sentinel")
            file("declarative-extensions/packages/keep/resource", "installed declarative data")
            file("agent-plugins/index.json", "corrupt agent sentinel")
            file("agent-plugins/packages/keep/resource", "installed agent data")
            val projection = file("projects/default/.droide/.runtime-agent-plugins/keep", "existing projection")
            val untouched = listOf(File(appRoot, "declarative-extensions"), File(appRoot, "agent-plugins"))
            val before = untouched.map(::snapshot)
            var processes = 0; var ubuntu = 0
            val events = mutableListOf<WorkspaceStartupMeasurement>()
            val coordinator = WorkspaceCoordinator(context, scope, manager, deviceBridge = bridge,
                processReconciliation = { processes++; error("Stop normal startup before installed components") },
                initialUbuntuPreparation = { ubuntu++; error("No automatic Ubuntu setup") },
                startupMeasurementObserver = { events += it },
            )
            try {
                assertNotNull(runCatching { coordinator.init(WorkspaceStartupMode.TEMPORARY_BUILTINS) }.exceptionOrNull())
                assertEquals(WorkspaceStartupPhase.IDLE, coordinator.startup.value.phase)
                assertEquals(0, processes); assertEquals(0, store.reads)
                assertNotNull(runCatching { coordinator.init() }.exceptionOrNull())
                assertEquals(WorkspaceStartupPhase.FAILED, coordinator.startup.value.phase)
                assertEquals(1, processes)
                events.clear()
                coordinator.init(WorkspaceStartupMode.TEMPORARY_BUILTINS)
                val runtime = checkNotNull(coordinator.runtime.value)
                assertEquals(WorkspaceStartupPhase.READY, coordinator.startup.value.phase)
                assertEquals(WorkspaceStartupMode.TEMPORARY_BUILTINS, runtime.startupMode)
                assertTrue(runtime.lsp is TemporaryRecoveryLspManager)
                assertNull(runtime.managedLanguageServers)
                assertSame(AgentPluginSource.EMPTY, runtime.agent.mcpPluginSource())
                assertFalse(runtime.extensions.installedComponentsEnabled)
                assertEquals(TEMPORARY_COMPONENTS_UNAVAILABLE, runtime.extensions.refresh().message)
                assertEquals(1, processes); assertEquals(0, ubuntu)
                assertTrue(runtime.terminals.tabs.value.isEmpty())
                assertTrue(events.none { it.phase == WorkspaceStartupPhase.RUNTIME_REGISTRIES || it.phase == WorkspaceStartupPhase.FALLBACK_RUNTIME })
                assertEquals(1, events.count { it.step == WorkspaceStartupStep.PUBLISH && it.outcome == WorkspaceStartupOutcome.SUCCEEDED })
                assertEquals(0, store.saves); assertEquals(0, recents.saves)
                assertFalse(File(appRoot, "projects/default/README.md").exists())
                assertEquals(before, untouched.map(::snapshot))
                assertEquals("existing projection", projection.readText())
                assertEquals(TEMPORARY_COMPONENTS_UNAVAILABLE, runCatching { coordinator.installedAgentPlugins() }.exceptionOrNull()?.message)

                val editor = EditorWorkspaceState()
                val document = editor.document("keep.kt")
                document.ensureLoaded(runtime.files)
                document.capture("unsaved editor sentinel")
                val binding = runtime.documentAuthority.bind(EditorWorkspaceDocumentAuthority(editor, runtime.files, runtime.lsp, Dispatchers.Unconfined))
                try {
                    val dirty = runtime.documentAuthority.dirtySnapshots()
                    assertEquals("unsaved editor sentinel", dirty.single().content)
                    val status = coordinator.startup.value
                    coordinator.init()
                    coordinator.init(WorkspaceStartupMode.TEMPORARY_BUILTINS)
                    assertSame(runtime, coordinator.runtime.value)
                    assertEquals(status, coordinator.startup.value)
                    assertEquals(dirty, runtime.documentAuthority.dirtySnapshots())
                    assertTrue(document.dirty)
                    assertEquals("saved source", source.readText())
                    assertEquals(1, processes); assertEquals(0, ubuntu)
                } finally { binding.close() }

                coordinator.switchProject("second")
                val switched = checkNotNull(coordinator.runtime.value)
                assertEquals("second", switched.project.id)
                assertEquals(WorkspaceStartupMode.TEMPORARY_BUILTINS, switched.startupMode)
                assertTrue(switched.lsp is TemporaryRecoveryLspManager)
                assertNull(switched.managedLanguageServers)
                assertSame(AgentPluginSource.EMPTY, switched.agent.mcpPluginSource())
                assertFalse(switched.extensions.installedComponentsEnabled)
                assertEquals("existing projection", projection.readText())
                assertEquals(before, untouched.map(::snapshot))
                assertEquals(0, ubuntu)
            } finally { coordinator.close(); scope.cancel() }
            assertEquals("existing projection", projection.readText())
            assertEquals(before, untouched.map(::snapshot))
        } finally { fixtureBridge?.close(); scope.cancel() }

    }

    @Test(timeout = 15_000)
    fun missingSavedActiveRemainsVisibleFailureWithoutFallbackOrCatalogWrites() = runOnAndroidMain {
        val context = context()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var fixtureBridge: DeviceBridgeManager? = null
        try {
            val bridge = DeviceBridgeManager(context, scope, DeviceBridgeDependencies(
                configureIdentity = {}, create = { error("No transport") }, pair = { _, _, _ -> error("No pairing") },
                discovery = { error("No discovery") },
            ))
            fixtureBridge = bridge
            val root = File(appRoot, "projects/existing").apply { mkdirs() }
            val store = Store(listOf(DroideProject("existing", "existing", root.path)), "missing")
            val recents = Recents()
            var processes = 0; var ubuntu = 0
            val coordinator = WorkspaceCoordinator(context, scope, ProjectManager(appRoot, store, recents), deviceBridge = bridge,
                processReconciliation = { processes++; error("Stop normal startup before installed components") },
                initialUbuntuPreparation = { ubuntu++; error("No automatic Ubuntu setup") },
            )
            try {
                assertNotNull(runCatching { coordinator.init() }.exceptionOrNull())
                assertNotNull(runCatching { coordinator.init(WorkspaceStartupMode.TEMPORARY_BUILTINS) }.exceptionOrNull())
                assertNull(coordinator.runtime.value)
                assertEquals(WorkspaceStartupPhase.FAILED, coordinator.startup.value.phase)
                assertTrue(coordinator.startup.value.detail.orEmpty().contains("active project", ignoreCase = true))
                assertEquals(0, store.saves); assertEquals(0, recents.saves)
                assertEquals(1, processes); assertEquals(0, ubuntu)
                // An explicit normal retry is still available after a failed temporary attempt.
                assertNotNull(runCatching { coordinator.init() }.exceptionOrNull())
                assertEquals(2, processes)
            } finally { coordinator.close(); scope.cancel() }
        } finally { fixtureBridge?.close(); scope.cancel() }

    }
    @Test(timeout = 30_000) fun cancellationAfterRealRuntimeConstructionPreservesUnpublishedCandidateData() {
        val context = context()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var ownedBridge: DeviceBridgeManager? = null
        var ownedCoordinator: WorkspaceCoordinator? = null
        var recovery: Job? = null
        var waiter: Job? = null
        val constructed = CompletableDeferred<Unit>()
        try {
            val bridge = DeviceBridgeManager(context, scope, DeviceBridgeDependencies(
                configureIdentity = {}, create = { error("No transport") }, pair = { _, _, _ -> error("No pairing") },
                discovery = { error("No discovery") },
            ))
            ownedBridge = bridge
            val source = file("projects/cancelled/keep.kt", "unpublished source sentinel")
            val projection = file("projects/cancelled/.droide/.runtime-agent-plugins/keep", "unpublished projection sentinel")
            file("declarative-extensions/index.json", "cancelled declarative index sentinel")
            file("agent-plugins/index.json", "cancelled agent index sentinel")
            val untouched = listOf(File(appRoot, "declarative-extensions"), File(appRoot, "agent-plugins"), projection.parentFile!!)
            val before = untouched.map(::snapshot)
            val project = DroideProject("cancelled", "cancelled", source.parentFile!!.path)
            val store = Store(listOf(project), project.id); val recents = Recents()
            val events = java.util.concurrent.CopyOnWriteArrayList<WorkspaceStartupMeasurement>()
            var ubuntu = 0
            val coordinator = WorkspaceCoordinator(context, scope, ProjectManager(appRoot, store, recents), deviceBridge = bridge,
                processReconciliation = { error("Stop normal startup before installed components") },
                initialUbuntuPreparation = { ubuntu++; error("No automatic Ubuntu setup") },
                startupMeasurementObserver = { measurement ->
                    events += measurement
                    if (measurement.step == WorkspaceStartupStep.RUNTIME_BINDINGS && measurement.outcome == WorkspaceStartupOutcome.SUCCEEDED) {
                        constructed.complete(Unit) // Observation only; the waiting caller owns cancellation.
                    }
                },
            )
            ownedCoordinator = coordinator
            var normalFailure: Throwable? = null
            val normal = scope.launch { normalFailure = runCatching { coordinator.init() }.exceptionOrNull() }
            awaitMain { normal.isCompleted }
            assertNotNull(normalFailure)
            assertEquals(WorkspaceStartupPhase.FAILED, coordinator.startup.value.phase)
            events.clear()
            val attempt = scope.launch(start = CoroutineStart.LAZY) { coordinator.init(WorkspaceStartupMode.TEMPORARY_BUILTINS) }
            recovery = attempt
            val cancellationWaiter = scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                constructed.await()
                attempt.cancel(CancellationException("Caller cancelled the constructed startup candidate"))
            }
            waiter = cancellationWaiter
            attempt.start()
            var joinFailure: Throwable? = null
            val joined = scope.launch {
                joinFailure = runCatching { withTimeout(10_000) { attempt.join(); cancellationWaiter.join() } }.exceptionOrNull()
            }
            awaitMain { joined.isCompleted }
            assertNull(joinFailure)
            assertTrue(attempt.isCancelled)
            assertEquals(1, events.count { it.step == WorkspaceStartupStep.RUNTIME_BINDINGS && it.outcome == WorkspaceStartupOutcome.SUCCEEDED })
            assertTrue(events.none { it.step == WorkspaceStartupStep.PUBLISH && it.outcome == WorkspaceStartupOutcome.SUCCEEDED })
            assertTrue(events.none { it.phase == WorkspaceStartupPhase.FALLBACK_RUNTIME })
            assertNull(coordinator.runtime.value)
            assertEquals(0, store.saves); assertEquals(0, recents.saves); assertEquals(0, ubuntu)
            assertEquals("unpublished source sentinel", source.readText())
            assertEquals(before, untouched.map(::snapshot))
        } finally {
            constructed.complete(Unit)
            recovery?.cancel(); waiter?.cancel()
            try { ownedCoordinator?.close(); ownedBridge?.close() } finally { scope.cancel() }
        }
    }

    private fun runOnAndroidMain(block: suspend () -> Unit) {
        // Coordinator publishes on Main; runBlocking on Robolectric's paused Main
        // prevents that publication from ever resuming after preparation on IO.
        val fixtureScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var failure: Throwable? = null
        val fixtureJob = fixtureScope.launch {
            try { withTimeout(10_000) { block() } }
            catch (caught: Throwable) { failure = caught }
        }
        try {
            awaitMain { fixtureJob.isCompleted }
            failure?.let { throw it }
        } finally {
            fixtureJob.cancel()
            fixtureScope.cancel()
        }
    }

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(12)
        while (!condition() && System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("Fixture did not settle", condition())
    }

}
