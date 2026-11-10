package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectDeletionRelocationIdentityTest {
    private class Store(private val delegate: ProjectStore) : ProjectStore by delegate {
        var failure: (() -> Unit)? = null
        override suspend fun save(all: List<DroideProject>) {
            failure?.also { failure = null; it(); throw IOException("catalog refused") }
            delegate.save(all)
        }
    }
    private fun fixture(block: suspend (File, ProjectManager, DroideProject) -> Unit) = runBlocking {
        val app = Files.createTempDirectory("droide-deletion-identity-").toFile()
        try {
            val store = Store(FileProjectStore(app))
            val manager = ProjectManager(app, store).apply { init() }
            val project = manager.add("Original")
            val root = File(project.rootPath)
            File(root, "original.txt").writeText("original retained work")
            store.failure = { check(root.mkdir()); File(root, "replacement.txt").writeText("unrelated replacement work") }
            assertTrue(runCatching { manager.deleteWorkspace(project.id) }.isFailure)
            assertEquals("original retained work", File(root.parentFile, ".delete-${project.id}/original.txt").readText())
            assertEquals("unrelated replacement work", File(root, "replacement.txt").readText())
            block(app, manager, project)
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }
    @Test fun failedRestorationCannotSelectReplacementTreeInSameManager() = fixture { _, manager, project ->
        val before = manager.activeId.value
        assertTrue("Unresolved relocation must not select replacement root", runCatching { manager.switch(project.id) }.isFailure)
        assertEquals(before, manager.activeId.value)
    }
    @Test fun restartCannotAdoptReplacementTreeAfterFailedRestoration() = fixture { app, _, project ->
        val reopened = ProjectManager(app)
        val open = runCatching { reopened.init(); reopened.switch(project.id) }
        assertTrue("Restart must not bind original ID to replacement tree", open.isFailure)
    }
}
