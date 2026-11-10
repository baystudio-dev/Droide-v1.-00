package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectManagerCloneRecoveryTest {
    @Test fun initKeepsUnregisteredWorkspaceAndRegisteredProject() = runBlocking {
        val appDir = Files.createTempDirectory("droide-project-clone-recovery-").toFile()
        try {
            val projectsRoot = appDir.resolve("projects").apply { mkdirs() }
            val orphan = projectsRoot.resolve("p-orphan").apply { mkdirs(); resolve("partial.txt").writeText("partial") }
            val kept = projectsRoot.resolve("p-kept").apply { mkdirs(); resolve("README.md").writeText("ok") }
            val store = MemoryProjectStore(
                listOf(DroideProject("p-kept", "Kept", kept.absolutePath)),
            )
            val manager = ProjectManager(appDir, store)

            manager.init()

            manager.collectStartupGarbage()
            assertTrue(orphan.exists())
            assertTrue(orphan.resolve("partial.txt").readText() == "partial")
            assertTrue(kept.isDirectory)
            assertTrue(manager.projects.value.any { it.id == "p-kept" })
        } finally {
            PathSecurity.deleteTreeNoFollow(appDir)
        }
    }

    @Test fun corruptFileCatalogAbortsInitWithoutDeletingOrReplacingAnything() = runBlocking {
        val appDir = Files.createTempDirectory("droide-project-catalog-corrupt-").toFile()
        try {
            val root = appDir.resolve("projects/p-owned").apply { mkdirs(); resolve("source.kt").writeText("keep") }
            val catalog = appDir.resolve(".droide/projects.json").apply { parentFile.mkdirs(); writeText("[{truncated") }
            val manager = ProjectManager(appDir)
            assertTrue(runCatching { manager.init() }.exceptionOrNull() is java.io.IOException)
            manager.collectStartupGarbage()
            assertTrue(catalog.readText() == "[{truncated")
            assertTrue(root.resolve("source.kt").readText() == "keep")
            assertFalse(appDir.resolve("projects/.delete-p-owned").exists())
        } finally { PathSecurity.deleteTreeNoFollow(appDir) }
    }

    @Test fun missingCatalogAbortsInitWithProjectAndBackupPreserved() = runBlocking {
        val appDir = Files.createTempDirectory("droide-project-catalog-missing-").toFile()
        try {
            val root = appDir.resolve("projects/p-owned").apply { mkdirs(); resolve("source.kt").writeText("keep") }
            val backup = appDir.resolve("projects/.delete-p-old").apply { mkdirs(); resolve("source.kt").writeText("backup") }
            val manager = ProjectManager(appDir)
            assertTrue(runCatching { manager.init() }.exceptionOrNull() is java.io.IOException)
            manager.collectStartupGarbage()
            assertTrue(root.resolve("source.kt").readText() == "keep")
            assertTrue(backup.resolve("source.kt").readText() == "backup")
            assertFalse(appDir.resolve(".droide/projects.json").exists())
        } finally { PathSecurity.deleteTreeNoFollow(appDir) }
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
