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
class WorkspaceProjectRootOwnershipTest {
    @Test(timeout = 15_000)
    fun inactiveRootReplacedByAliasCannotSwitchOwnershipOrTouchCurrentWorkspace() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var failure: Throwable? = null
        val test = scope.launch {
            try {
                withTimeout(10_000) { scenario(scope) }
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

    private suspend fun scenario(scope: CoroutineScope) {
        val appRoot = Files.createTempDirectory("workspace-root-ownership-").toFile().canonicalFile
        val previousPid = Process.myPid()
        ShadowProcess.setPid(4242)
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = appRoot
        }
        // A plain Robolectric Application does not run DroideApplication.onCreate. Model a user-opened
        // app session before exercising real workspace startup.
        ApplicationWorkAdmission.initialize(context)
        ApplicationWorkAdmission.reopenFromUser()
        val currentRoot = File(appRoot, "projects/default").apply { mkdirs() }
        val inactiveRoot = File(appRoot, "projects/second").apply { mkdirs() }
        val source = File(currentRoot, "main.kt").apply { writeText("keep current source") }
        val records = listOf(
            DroideProject("default", "Default", currentRoot.path),
            DroideProject("second", "Second", inactiveRoot.path),
        )
        var writes = 0
        val store = object : ProjectStore {
            override suspend fun load() = records
            override suspend fun loadActiveId() = "default"
            override suspend fun save(all: List<DroideProject>) { writes++ }
            override suspend fun saveActiveId(id: String) { writes++ }
        }
        val recents = object : ProjectRecentsStore {
            override suspend fun load() = listOf("default")
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
        try {
            val startupFailure = runCatching { coordinator.init() }.exceptionOrNull()
            assertNotNull(startupFailure)
            assertTrue(startupFailure.toString(), generateSequence(startupFailure) { it.cause }
                .any { it.message.orEmpty().contains("Enter builtin recovery fixture") })
            assertEquals(WorkspaceStartupPhase.FAILED, coordinator.startup.value.phase)
            coordinator.init(WorkspaceStartupMode.TEMPORARY_BUILTINS)
            val active = checkNotNull(coordinator.runtime.value)
            assertEquals("default", manager.activeId.value)
            assertTrue(inactiveRoot.delete())
            Files.createSymbolicLink(inactiveRoot.toPath(), currentRoot.toPath())

            val failure = runCatching { coordinator.switchProject("second") }.exceptionOrNull()

            assertNotNull("Switch must reject the changed path before runtime construction", failure)
            assertTrue(failure?.message.orEmpty().contains("symbolic link"))
            assertSame(active, coordinator.runtime.value)
            assertEquals("default", manager.activeId.value)
            assertEquals(2, manager.projects.value.size)
            assertEquals(0, writes)
            assertEquals("keep current source", source.readText())
            assertTrue(Files.isSymbolicLink(inactiveRoot.toPath()))
        } finally {
            coordinator.close(); bridge.close()
            ShadowProcess.setPid(previousPid)
            PathSecurity.deleteTreeNoFollow(appRoot)
        }
    }
}
