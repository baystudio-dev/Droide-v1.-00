package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkspaceMissingRootAdmissionTest {
    @Test(timeout = 15_000) fun missingInactiveRootIsNotRecreatedDuringNormalPreparation() = run(Kind.MISSING_SECOND)
    @Test(timeout = 15_000) fun missingDefaultRootIsNotRecreatedOrSeededDuringNormalPreparation() = run(Kind.MISSING_DEFAULT)
    @Test(timeout = 15_000) fun regularFileAtRegisteredRootIsPreserved() = run(Kind.FILE_SECOND)
    @Test(timeout = 15_000) fun existingRootReachesTheBoundedPreparationControl() = run(Kind.EXISTING_SECOND)

    private enum class Kind { MISSING_SECOND, MISSING_DEFAULT, FILE_SECOND, EXISTING_SECOND }
    private class BoundaryStop : IllegalStateException("C14 bounded preparation control reached")

    private class BoundedContext(private val directory: File, private val cache: File) :
        ContextWrapper(RuntimeEnvironment.getApplication()) {
        @Volatile var armed = false
        val filesReads = AtomicInteger()
        override fun getApplicationContext(): Context = this
        override fun getCacheDir() = cache
        override fun getFilesDir(): File {
            if (armed && filesReads.incrementAndGet() > 1) throw BoundaryStop()
            return directory
        }
    }

    private fun run(kind: Kind) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var failure: Throwable? = null
        val job = scope.launch {
            try { withTimeout(10_000) { scenario(scope, kind) } }
            catch (caught: Throwable) { failure = caught }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (!job.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertTrue("Bounded workspace fixture did not settle", job.isCompleted)
            failure?.let { throw it }
        } finally { job.cancel(); scope.cancel() }
    }

    private suspend fun scenario(scope: CoroutineScope, kind: Kind) {
        val base = Files.createTempDirectory("workspace-missing-root-").toFile().canonicalFile
        val app = base.resolve("app").apply { check(mkdir()) }
        val control = base.resolve("control").apply { check(mkdir()) }
        val cache = base.resolve("cache").apply { check(mkdir()) }
        val first = app.resolve("projects/default").apply { check(mkdirs()) }
        val second = app.resolve("projects/second").apply { check(mkdir()) }
        first.resolve("README.md").writeText("existing user readme")
        first.resolve("main.kt").writeText("default source")
        second.resolve("main.kt").writeText("second source")
        val records = listOf(DroideProject("default", "Default", first.path), DroideProject("second", "Second", second.path))
        val writes = AtomicInteger()
        val store = object : ProjectStore {
            var active = "default"
            override suspend fun load() = records
            override suspend fun loadActiveId() = active
            override suspend fun save(all: List<DroideProject>) { writes.incrementAndGet() }
            override suspend fun saveActiveId(id: String) { writes.incrementAndGet(); active = id }
        }
        val recents = object : ProjectRecentsStore {
            override suspend fun load() = listOf("default")
            override suspend fun save(ids: List<String>) { writes.incrementAndGet() }
        }
        val context = BoundedContext(app, cache)
        ApplicationWorkAdmission.initialize(context)
        ApplicationWorkAdmission.reopenFromUser()
        val manager = ProjectManager(app, store, recents)
        manager.init()
        val bridge = DeviceBridgeManager(context, scope, DeviceBridgeDependencies(
            configureIdentity = {}, create = { error("No transport") }, pair = { _, _, _ -> error("No pairing") },
            discovery = { error("No discovery") },
        ))
        val ubuntu = AtomicInteger()
        val coordinator = WorkspaceCoordinator(context, scope, manager, deviceBridge = bridge,
            processReconciliation = { error("No process reconciliation") },
            initialUbuntuPreparation = { ubuntu.incrementAndGet(); error("No Ubuntu preparation") })
        try {
            val target = if (kind == Kind.MISSING_DEFAULT) first else second
            val targetId = if (kind == Kind.MISSING_DEFAULT) "default" else "second"
            if (kind != Kind.EXISTING_SECOND) Files.move(target.toPath(), control.resolve("retained-root").toPath())
            if (kind == Kind.FILE_SECOND) target.writeText("foreign regular file")
            val projectBefore = snapshot(app.resolve("projects"))
            val retainedBefore = snapshot(control)
            val savedWrites = writes.get()
            val savedRecords = manager.projects.value
            val savedActive = manager.activeId.value
            assertNull(coordinator.runtime.value)
            context.armed = true

            val failure = runCatching { coordinator.switchProject(targetId) }.exceptionOrNull()

            assertNotNull("The fixture must stop before starting services", failure)
            if (kind == Kind.MISSING_SECOND || kind == Kind.MISSING_DEFAULT) {
                assertFalse("Registered workspace must remain absent; retained source is not a new project", target.exists())
                assertFalse(target.resolve("README.md").exists())
                assertTrue(failure.toString(), generateSequence(failure) { it.cause }
                    .any { it.message.orEmpty().contains("unavailable") })
                assertEquals(1, context.filesReads.get())
            } else if (kind == Kind.FILE_SECOND) {
                assertTrue(target.isFile)
                assertEquals("foreign regular file", target.readText())
                assertEquals(1, context.filesReads.get())
            } else {
                assertTrue("Existing root should pass admission and reach the deliberate later boundary",
                    generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains("C14 bounded preparation control reached") })
                assertEquals(2, context.filesReads.get())
            }
            assertEquals(projectBefore, snapshot(app.resolve("projects")))
            assertEquals(retainedBefore, snapshot(control))
            assertEquals(savedWrites, writes.get())
            assertEquals(savedRecords, manager.projects.value)
            assertEquals(savedActive, manager.activeId.value)
            assertNull(coordinator.runtime.value)
            assertEquals(0, ubuntu.get())
        } finally {
            context.armed = false
            coordinator.close(); bridge.close()
            PathSecurity.deleteTreeNoFollow(base)
        }
    }

    private data class Node(val key: String, val directory: Boolean, val size: Long, val modified: String, val sha256: String?)
    private fun snapshot(root: File): Map<String, Node> = Files.walk(root.toPath()).use { stream ->
        stream.iterator().asSequence().associate { path ->
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attributes.isDirectory || attributes.isRegularFile)
            root.toPath().relativize(path).joinToString("/") to Node(requireNotNull(attributes.fileKey()).toString(),
                attributes.isDirectory, attributes.size(), attributes.lastModifiedTime().toString(),
                if (attributes.isRegularFile) ProjectCopyEvidence.sha256(Files.readAllBytes(path)) else null)
        }
    }
}
