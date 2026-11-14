package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.os.Process
import java.io.File
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

/** Actual temporary-builtins coordinator; recording observer only, no native editor/CLI/process. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkspaceStartupCommittedStatusR29Test {
    companion object {
        private val appRoot = Files.createTempDirectory("r29-committed-startup-").toFile().canonicalFile
        @JvmStatic @AfterClass fun cleanup() { PathSecurity.deleteTreeNoFollow(appRoot) }
    }
    private var previousPid = 0
    @Before fun setPid() {
        previousPid = Process.myPid(); ShadowProcess.setPid(4242)
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }
    @After fun restorePid() { ShadowProcess.setPid(previousPid) }

    @Test(timeout = 15_000) fun cancelledPublishedStartupKeepsReadyOwnerAndRetryDoesNotRebuild() = scenario(true)
    @Test(timeout = 15_000) fun successfulStartupPublishesReadyTogetherWithRuntime() = scenario(false)

    private fun scenario(cancelAfterPublication: Boolean) {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = appRoot
        }
        val source = File(appRoot, "projects/default/keep.txt").apply { parentFile!!.mkdirs(); writeText("saved source") }
        val project = DroideProject("default", "Default", source.parentFile!!.path)
        var reads = 0
        var writes = 0
        val store = object : ProjectStore {
            override suspend fun load(): List<DroideProject> { reads++; return listOf(project) }
            override suspend fun loadActiveId(): String { reads++; return project.id }
            override suspend fun save(all: List<DroideProject>) { writes++ }
            override suspend fun saveActiveId(id: String) { writes++ }
        }
        val recents = object : ProjectRecentsStore {
            override suspend fun load(): List<String>? = null
            override suspend fun save(ids: List<String>) { writes++ }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val bridge = DeviceBridgeManager(context, scope, DeviceBridgeDependencies(
            configureIdentity = {}, create = { error("No transport") },
            pair = { _, _, _ -> error("No pairing") }, discovery = { error("No discovery") },
        ))
        var attempt: Job? = null
        var coordinator: WorkspaceCoordinator? = null
        var published: WorkspaceRuntime? = null
        var statusAtPublication: WorkspaceStartupStatus? = null
        var publications = 0
        val owned = WorkspaceCoordinator(context, scope, ProjectManager(appRoot, store, recents), deviceBridge = bridge,
            processReconciliation = { error("Stop normal startup before installed components") },
            initialUbuntuPreparation = { error("No Ubuntu") },
            startupMeasurementObserver = { event ->
                if (event.step == WorkspaceStartupStep.PUBLISH && event.outcome == WorkspaceStartupOutcome.SUCCEEDED) {
                    publications++
                    published = coordinator?.runtime?.value
                    statusAtPublication = coordinator?.startup?.value
                    if (cancelAfterPublication) checkNotNull(attempt).cancel(CancellationException("fixture: after publication"))
                }
            },
        )
        coordinator = owned
        var failure: Throwable? = null
        val test = scope.launch {
            try {
                withTimeout(10_000) {
                    assertNotNull(runCatching { owned.init() }.exceptionOrNull())
                    assertEquals(WorkspaceStartupPhase.FAILED, owned.startup.value.phase)
                    var outcome: Throwable? = null
                    val startup = launch(start = CoroutineStart.LAZY) {
                        outcome = runCatching { owned.init(WorkspaceStartupMode.TEMPORARY_BUILTINS) }.exceptionOrNull()
                    }
                    attempt = startup
                    startup.start()
                    startup.join()
                    if (cancelAfterPublication) {
                        assertTrue(startup.isCancelled)
                        assertTrue(outcome is CancellationException)
                    } else assertNull(outcome)
                    val accepted = checkNotNull(published)
                    assertSame(accepted, owned.runtime.value)
                    assertEquals(WorkspaceStartupPhase.READY, statusAtPublication?.phase)
                    assertEquals(WorkspaceStartupPhase.READY, owned.startup.value.phase)
                    val beforeReads = reads
                    val beforeStatus = owned.startup.value
                    owned.init()
                    owned.init(WorkspaceStartupMode.TEMPORARY_BUILTINS)
                    assertSame(accepted, owned.runtime.value)
                    assertEquals(beforeStatus, owned.startup.value)
                    assertEquals(beforeReads, reads)
                    assertEquals(1, publications)
                    assertEquals(0, writes)
                    accepted.extensions.activateCli() // Built-ins no-op; fails if wrongly discarded/closed.
                    assertEquals("saved source", source.readText())
                }
            } catch (caught: Throwable) { failure = caught }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (!test.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertTrue("Bounded startup fixture did not settle", test.isCompleted)
            failure?.let { throw it }
        } finally { test.cancel(); attempt?.cancel(); owned.close(); bridge.close(); scope.cancel() }
    }
}
