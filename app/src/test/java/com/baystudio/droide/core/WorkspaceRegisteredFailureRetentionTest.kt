package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.os.Process
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
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
class WorkspaceRegisteredFailureRetentionTest {
    companion object {
        private val applicationRoot = Files.createTempDirectory("registered-prepare-failure-").toFile().canonicalFile
    }

    @Test(timeout = 15_000) fun failedPreparationKeepsTheCommittedProjectAtItsOriginalPath() =
        runOnAndroidMain { scenario(Membership.KEEP) }

    @Test(timeout = 15_000) fun failedPreparationCannotEraseTheUsersCommittedRemoval() =
        runOnAndroidMain { scenario(Membership.REMOVE) }

    @Test(timeout = 15_000) fun failedPreparationCannotEraseTheUsersCommittedRestoration() =
        runOnAndroidMain { scenario(Membership.REMOVE_AND_RESTORE) }

    private enum class Membership { KEEP, REMOVE, REMOVE_AND_RESTORE }
    private class BoundaryStop : IllegalStateException("Registered project preparation boundary reached")

    private class BoundedContext(private val root: File) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        @Volatile var beforePreparedRootRead: (() -> Unit)? = null
        override fun getApplicationContext(): Context = this
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getFilesDir(): File {
            val action = beforePreparedRootRead
            if (action != null && Thread.currentThread().stackTrace.any {
                    it.className.startsWith("com.baystudio.droide.core.WorkspaceCoordinator\$buildRuntime")
                }) {
                beforePreparedRootRead = null
                action()
            }
            return root
        }
    }

    private suspend fun scenario(membership: Membership) {
        val app = applicationRoot.apply { check(isDirectory || mkdirs()) }
        val previousPid = Process.myPid()
        ShadowProcess.setPid(4242)
        val context = BoundedContext(app)
        ApplicationWorkAdmission.initialize(context)
        ApplicationWorkAdmission.reopenFromUser()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val defaultRoot = File(app, "projects/default").apply { mkdirs() }
        val defaultSource = File(defaultRoot, "main.kt").apply { writeText("existing active source") }
        val default = DroideProject("default", "Default", defaultRoot.path)
        val disk = FileProjectStore(app)
        disk.save(listOf(default))
        disk.saveActiveId(default.id)
        var catalogWrites = 0
        var selectionWrites = 0
        val store = object : ProjectStore by disk {
            override suspend fun save(all: List<DroideProject>) { catalogWrites++; disk.save(all) }
            override suspend fun saveActiveId(id: String) { selectionWrites++; disk.saveActiveId(id) }
        }
        val recents = object : ProjectRecentsStore {
            override suspend fun load() = listOf("default")
            override suspend fun save(ids: List<String>) = Unit
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
        try {
            assertTrue(runCatching { coordinator.init() }.isFailure)
            coordinator.init(WorkspaceStartupMode.TEMPORARY_BUILTINS)
            val active = checkNotNull(coordinator.runtime.value)
            val selection = File(app, ".droide/active_project.txt")
            val selectionBefore = selection.readBytes()
            val catalog = File(app, ".droide/projects.json")
            val stop = BoundaryStop()
            var created: DroideProject? = null
            var rootKey: Any? = null
            var sourceKey: Any? = null
            var committedCatalog: ByteArray? = null
            var boundaryCalls = 0
            context.beforePreparedRootRead = {
                boundaryCalls++
                val project = manager.projects.value.single { it.id != "default" }
                created = project
                val root = File(project.rootPath)
                assertEquals("print('Hello from Droide')\n", File(root, "main.py").readText())
                File(root, "late-user-work.txt").writeText("work accepted after registration")
                rootKey = key(root)
                sourceKey = key(File(root, "main.py"))
                runBlocking {
                    if (membership != Membership.KEEP) manager.removeWorkspace(project.id)
                    if (membership == Membership.REMOVE_AND_RESTORE) manager.restoreWorkspace(project.id)
                }
                committedCatalog = catalog.readBytes()
                throw stop
            }

            val failure = runCatching {
                coordinator.createProjectFromTemplateAndSwitch("Retained project", "python")
            }.exceptionOrNull()

            assertSame("Preparation must propagate its original failure", stop, failure)
            assertEquals(1, boundaryCalls)
            val original = checkNotNull(created)
            val expected = original.copy(workspaceRemoved = membership == Membership.REMOVE)
            assertSame(active, coordinator.runtime.value)
            assertEquals("default", manager.activeId.value)
            assertEquals("existing active source", defaultSource.readText())
            assertEquals(listOf(default, expected), manager.projects.value)
            assertEquals(listOf(default, expected), disk.load())
            assertArrayEquals(checkNotNull(committedCatalog), catalog.readBytes())
            assertArrayEquals(selectionBefore, selection.readBytes())
            val root = File(original.rootPath)
            assertTrue("Registered source must remain at its original path", root.isDirectory)
            assertEquals(rootKey, key(root))
            assertEquals(sourceKey, key(File(root, "main.py")))
            assertEquals("print('Hello from Droide')\n", File(root, "main.py").readText())
            assertEquals("work accepted after registration", File(root, "late-user-work.txt").readText())
            assertEquals(if (membership == Membership.REMOVE) listOf(expected) else emptyList<DroideProject>(),
                manager.storedProjects.value)
            assertFalse(File(root.parentFile, ".delete-${original.id}").exists())
            assertEquals(when (membership) {
                Membership.KEEP -> 1
                Membership.REMOVE -> 2
                Membership.REMOVE_AND_RESTORE -> 3
            }, catalogWrites)
            assertEquals(0, selectionWrites)
            assertEquals(0, ubuntuPreparations)
        } finally {
            context.beforePreparedRootRead = null
            coordinator.close(); bridge.close(); scope.cancel()
            ShadowProcess.setPid(previousPid)
            PathSecurity.deleteTreeNoFollow(app)
        }
    }

    private fun key(file: File) =
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()

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
            assertTrue("Bounded registered-project failure fixture did not settle", job.isCompleted)
            failure?.let { throw it }
        } finally { job.cancel(); scope.cancel() }
    }
}
