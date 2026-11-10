package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectRegisteredFailureRetentionTest {
    private class RegistrationStore(val delegate: FileProjectStore) : ProjectStore by delegate {
        var writes = 0
        var afterRegistration: (suspend (DroideProject) -> Unit)? = null
        override suspend fun save(all: List<DroideProject>) {
            writes++
            delegate.save(all)
            val project = all.singleOrNull { it.id != "default" }
            if (project != null) {
                val callback = afterRegistration.also { afterRegistration = null }
                callback?.invoke(project)
            }
        }
    }

    private class Fixture(val app: File, val store: RegistrationStore, val manager: ProjectManager) {
        var registered: DroideProject? = null
        var rootKey: Any? = null
        var userFileKey: Any? = null
        val defaultFile = File(app, "projects/default/keep.txt").apply { writeText("existing Default work") }
        val defaultKey = key(defaultFile)
        val originalWrites = store.writes

        fun retainUserFile(project: DroideProject) {
            registered = project
            val root = File(project.rootPath)
            rootKey = key(root)
            val source = File(root, "main.py").apply { writeText("pending user and CLI work\n") }
            userFileKey = key(source)
        }

        suspend fun assertRegistrationAndSourceRetained(): DroideProject {
            val project = checkNotNull(registered)
            val root = File(project.rootPath)
            assertEquals(project, manager.projects.value.single { it.id == project.id })
            assertEquals(project, store.load().single { it.id == project.id })
            assertFalse(project.workspaceRemoved)
            assertEquals("default", manager.activeId.value)
            assertEquals("default", store.loadActiveId())
            assertEquals(rootKey, key(root))
            assertEquals(userFileKey, key(File(root, "main.py")))
            assertEquals("pending user and CLI work\n", File(root, "main.py").readText())
            assertFalse(Files.exists(File(root.parentFile, ".delete-${project.id}").toPath(), NOFOLLOW_LINKS))
            assertEquals(defaultKey, key(defaultFile))
            assertEquals("existing Default work", defaultFile.readText())
            assertEquals(originalWrites + 1, store.writes)
            return project
        }
    }

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val app = Files.createTempDirectory("droide-registered-template-failure-").toFile()
        try {
            val store = RegistrationStore(FileProjectStore(app))
            val manager = ProjectManager(app, store).apply { init() }
            block(Fixture(app, store, manager))
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    private suspend fun failActualPythonTemplate(f: Fixture): IllegalArgumentException {
        f.store.afterRegistration = { f.retainUserFile(it) }
        val failure = try {
            f.manager.createFromTemplate("Partial Python project", "python")
            throw AssertionError("The actual template writer must reject the existing main.py")
        } catch (failure: IllegalArgumentException) { failure }
        assertEquals("Template target already exists: main.py", failure.message)
        return failure
    }

    @Test fun writerFailureRetainsTheRegisteredProjectUserFileAndPartialTemplateOutput() = fixture { f ->
        val failure = failActualPythonTemplate(f)

        val project = f.assertRegistrationAndSourceRetained()

        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertEquals(ProjectTemplateRegistry.byId("python")!!.files.getValue("pyproject.toml"),
            File(project.rootPath, "pyproject.toml").readText())
    }

    @Test fun failedTemplateProjectCanBeSelectedAfterRestartWithItsOriginalIdentity() = fixture { f ->
        failActualPythonTemplate(f)
        val project = f.assertRegistrationAndSourceRetained()
        val root = File(project.rootPath)
        val partialKey = key(File(root, "pyproject.toml"))

        val restarted = ProjectManager(f.app).apply { init() }
        assertTrue(restarted.collectStartupGarbage().isEmpty())
        assertEquals(project, restarted.projects.value.single { it.id == project.id })
        restarted.switch(project.id)

        assertEquals(project.id, restarted.activeId.value)
        assertEquals(project, restarted.active())
        assertEquals(project.rootPath, restarted.activeRoot().path)
        assertEquals(f.rootKey, key(root))
        assertEquals(f.userFileKey, key(File(root, "main.py")))
        assertEquals(partialKey, key(File(root, "pyproject.toml")))
        assertEquals("pending user and CLI work\n", File(root, "main.py").readText())
        assertEquals("existing Default work", f.defaultFile.readText())
    }

    @Test fun cancellationAfterRegistrationCommitKeepsSourceAndRethrowsTheOriginalCancellation() = fixture { f ->
        val cancellation = CancellationException("registration return interrupted after commit")
        f.store.afterRegistration = {
            f.retainUserFile(it)
            throw cancellation
        }
        val failure = try {
            f.manager.createFromTemplate("Cancelled Python project", "python")
            throw AssertionError("The committed registration cancellation must propagate")
        } catch (failure: CancellationException) { failure }

        assertSame(cancellation, failure)
        val project = f.assertRegistrationAndSourceRetained()
        assertFalse(f.manager.selectionIsUncertain)
        assertFalse(File(project.rootPath, "pyproject.toml").exists())
    }

    companion object {
        private fun key(file: File) =
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
    }
}
