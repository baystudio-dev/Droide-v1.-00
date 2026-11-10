package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectManagerCreationRootSafetyTest {
    @Test fun blankTemplateNameStillCreatesDedicatedWorkspace() = runBlocking {
        val appDir = Files.createTempDirectory("droide-project-create-blank-").toFile()
        try {
            val manager = ProjectManager(appDir, MemoryProjectStore(emptyList()))
            manager.init()

            val (project, _) = manager.createFromTemplate("   ", "empty")
            val projectsRoot = appDir.resolve("projects").canonicalFile
            val root = java.io.File(project.rootPath).canonicalFile

            assertEquals("Empty Project", project.name)
            assertFalse(root == projectsRoot)
            assertEquals(projectsRoot, root.parentFile?.canonicalFile)
            assertTrue(root.name.startsWith("p-"))
        } finally {
            PathSecurity.deleteTreeNoFollow(appDir)
        }
    }

    @Test fun projectsContainerAndNestedRootsAreRejected() = runBlocking {
        val appDir = Files.createTempDirectory("droide-project-create-root-").toFile()
        try {
            val manager = ProjectManager(appDir, MemoryProjectStore(emptyList()))
            manager.init()
            val projectsRoot = appDir.resolve("projects").canonicalFile
            val nested = projectsRoot.resolve("p-parent/nested")

            assertTrue(runCatching { manager.add("Bad root", projectsRoot.absolutePath) }.isFailure)
            assertTrue(runCatching { manager.add("Nested root", nested.absolutePath) }.isFailure)
        } finally {
            PathSecurity.deleteTreeNoFollow(appDir)
        }
    }

    @Test fun corruptStoredContainerRootCannotInventAnEmptyIdDerivedChild() = runBlocking {
        val appDir = Files.createTempDirectory("droide-project-create-repair-").toFile()
        try {
            val projectsRoot = appDir.resolve("projects").apply { mkdirs() }.canonicalFile
            projectsRoot.resolve("sibling-project.txt").writeText("must stay outside repaired project")
            val original = listOf(DroideProject("p-corrupt", "Corrupt", projectsRoot.absolutePath))
            val store = MemoryProjectStore(original)
            val manager = ProjectManager(appDir, store)

            val failure = runCatching { manager.init() }.exceptionOrNull()

            assertTrue(failure != null)
            assertTrue(failure!!.message.orEmpty().contains(projectsRoot.resolve("p-corrupt").path))
            assertTrue(manager.projects.value.isEmpty())
            assertEquals(original, store.load())
            assertFalse(projectsRoot.resolve("p-corrupt").exists())
            assertFalse(projectsRoot.resolve("default").exists())
            assertEquals("must stay outside repaired project", projectsRoot.resolve("sibling-project.txt").readText())
        } finally {
            PathSecurity.deleteTreeNoFollow(appDir)
        }
    }

    private class MemoryProjectStore(initial: List<DroideProject>) : ProjectStore {
        private var projects = initial
        private var active: String? = null
        override suspend fun load(): List<DroideProject> = projects
        override suspend fun save(all: List<DroideProject>) { projects = all }
        override suspend fun loadActiveId(): String? = active
        override suspend fun saveActiveId(id: String) { active = id }
    }
}
