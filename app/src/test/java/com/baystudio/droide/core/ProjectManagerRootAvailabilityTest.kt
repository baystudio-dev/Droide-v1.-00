package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProjectManagerRootAvailabilityTest {
    @Test fun missingSavedRootCannotBecomeAnEmptyProjectAtNormalStartup() = fixture { f ->
        val project = f.project("p-owned")
        f.catalog(listOf(project), project.id)
        f.retain(File(project.rootPath))
        f.assertNormalRejected(project)
    }

    @Test fun missingRegisteredDefaultIsNotTreatedAsANewDefault() = fixture { f ->
        val project = f.project("default")
        f.catalog(listOf(project), project.id)
        f.retain(File(project.rootPath))
        f.assertNormalRejected(project)
        assertFalse(f.projects.resolve("default").exists())
    }

    @Test fun missingProjectsContainerIsNotRecreatedBeforeSavedRootsAreValidated() = fixture { f ->
        val project = f.project("p-owned")
        f.catalog(listOf(project), project.id)
        f.retain(f.projects)
        f.assertNormalRejected(project)
        assertFalse(f.projects.exists())
    }

    @Test fun regularFileAtSavedRootIsPreservedAndCannotBePublishedAsAWorkspace() = fixture { f ->
        val project = f.project("p-owned")
        f.catalog(listOf(project), project.id)
        val root = File(project.rootPath)
        f.retain(root)
        root.writeText("foreign regular file")
        f.assertNormalRejected(project)
        assertEquals("foreign regular file", root.readText())
    }

    @Test fun exactRecoveryIdRestoresTheOriginalRootEvenWhenItsLeafDiffersFromTheId() = fixture { f ->
        val project = f.project("p-owned", "custom-workspace")
        val root = File(project.rootPath)
        val rootKey = key(root)
        val sourceKey = key(root.resolve("source.kt"))
        f.catalog(listOf(project), project.id)
        val backup = f.stageTracked(project)
        val beforeLoad = snapshot(f.base)
        assertEquals(listOf(project), FileProjectStore(f.app).load())
        assertEquals(beforeLoad, snapshot(f.base))

        val manager = ProjectManager(f.app)
        manager.init()

        assertFalse(backup.exists())
        assertEquals(rootKey, key(root))
        assertEquals(sourceKey, key(root.resolve("source.kt")))
        assertEquals("source for ${project.id}", root.resolve("source.kt").readText())
        assertEquals(project.id, manager.activeId.value)
        assertEquals(project.rootPath, manager.active()?.rootPath)
    }

    @Test fun recoveryDirectoryNamedAfterRootLeafDoesNotAuthorizeAnotherProjectId() = fixture { f ->
        val project = f.project("p-owned", "custom-workspace")
        f.catalog(listOf(project), project.id)
        val root = File(project.rootPath)
        Files.move(root.toPath(), f.projects.resolve(".delete-${root.name}").toPath())
        f.assertNormalRejected(project)
    }

    @Test fun symbolicRecoveryDirectoryDoesNotAuthorizeFollowingItsTarget() = fixture { f ->
        val project = f.project("p-owned")
        f.catalog(listOf(project), project.id)
        val retained = f.retain(File(project.rootPath))
        val backup = f.projects.resolve(".delete-${project.id}")
        Files.createSymbolicLink(backup.toPath(), retained.toPath())
        f.assertNormalRejected(project)
        assertTrue(Files.isSymbolicLink(backup.toPath()))
        assertEquals(retained.toPath(), Files.readSymbolicLink(backup.toPath()))
    }

    @Test fun everySavedRootIsCheckedBeforeAnyOtherRecoveryMoveOrPublication() = fixture { f ->
        val recoverable = f.project("p-a")
        val missing = f.project("p-b")
        f.catalog(listOf(recoverable, missing), recoverable.id)
        val recovery = f.projects.resolve(".delete-${recoverable.id}")
        Files.move(File(recoverable.rootPath).toPath(), recovery.toPath())
        f.retain(File(missing.rootPath))
        f.assertNormalRejected(missing)
        assertTrue(recovery.isDirectory)
        assertFalse(File(recoverable.rootPath).exists())
        assertEquals("source for ${recoverable.id}", recovery.resolve("source.kt").readText())
    }

    @Test fun existingRootAndDuplicateRecoveryTreeBothRemainAvailable() = fixture { f ->
        val project = f.project("p-owned")
        val root = File(project.rootPath)
        val backup = f.projects.resolve(".delete-${project.id}").apply { check(mkdir()) }
        backup.resolve("source.kt").writeText("separate retained copy")
        f.catalog(listOf(project), project.id)
        val beforeRoot = snapshot(root)
        val beforeBackup = snapshot(backup)

        val manager = ProjectManager(f.app)
        val failure = runCatching { manager.init() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("no recorded identity"))
        assertNull(manager.activeId.value)
        assertTrue(manager.projects.value.isEmpty())
        assertEquals(beforeRoot, snapshot(root))
        assertEquals(beforeBackup, snapshot(backup))
    }

    @Test fun genuinelyNewInstallationStillCreatesItsFirstDefaultWorkspace() = fixture { f ->
        check(f.projects.delete())
        val manager = ProjectManager(f.app)

        manager.init()

        val only = manager.projects.value.single()
        assertEquals("default", only.id)
        assertEquals(f.projects.resolve("default").canonicalPath, only.rootPath)
        assertTrue(File(only.rootPath).isDirectory)
        assertEquals("default", manager.activeId.value)
        assertEquals(listOf(only), FileProjectStore(f.app).load())
    }

    @Test fun readOnlyRecoveryCanOpenHealthyActiveWhileAnInactiveRootIsMissing() = fixture { f ->
        val active = f.project("default")
        val inactive = f.project("p-other")
        f.catalog(listOf(active, inactive), active.id)
        f.retain(File(inactive.rootPath))
        val before = snapshot(f.base)
        val manager = ProjectManager(f.app)

        manager.init(readOnlyExisting = true)

        assertEquals(active.id, manager.activeId.value)
        assertEquals(listOf(active, inactive), manager.projects.value)
        assertEquals(before, snapshot(f.base))
        assertFalse(File(inactive.rootPath).exists())
    }

    @Test fun metadataOnlyCatalogReadDoesNotRequireEveryRootToBeAvailable() = fixture { f ->
        val project = f.project("p-owned")
        f.catalog(listOf(project), project.id)
        f.retain(File(project.rootPath))
        val before = snapshot(f.base)

        assertEquals(listOf(project), FileProjectStore(f.app).load())

        assertEquals(before, snapshot(f.base))
        assertFalse(File(project.rootPath).exists())
    }

    @Test fun missingLegacyIdDerivedRootIsNotInventedFromAPersistedEntry() = fixture { f ->
        val project = DroideProject("p-legacy", "Legacy", f.projects.resolve("p-legacy").canonicalPath)
        f.rawCatalog("""[{"id":"p-legacy","name":"Legacy"}]""", project.id)
        f.assertNormalRejected(project)
    }

    @Test fun regularFileAtRecoveryNameIsPreservedAndDoesNotAuthorizeRootCreation() = fixture { f ->
        val project = f.project("p-owned")
        f.catalog(listOf(project), project.id)
        f.retain(File(project.rootPath))
        val backup = f.projects.resolve(".delete-${project.id}").apply { writeText("recovery-name file") }
        f.assertNormalRejected(project)
        assertEquals("recovery-name file", backup.readText())
    }

    @Test fun legacyContainerPathCanStillUseAnExistingIdDerivedWorkspace() = fixture { f ->
        val project = f.project("p-legacy")
        f.rawCatalog("""[{"id":"p-legacy","name":"Legacy","rootPath":"${f.projects.path}"}]""", project.id)
        val before = snapshot(File(project.rootPath))
        val manager = ProjectManager(f.app)

        manager.init()

        assertEquals(project.id, manager.activeId.value)
        assertEquals(project.rootPath, manager.active()?.rootPath)
        assertEquals(before, snapshot(File(project.rootPath)))
    }

    private class Fixture(val base: File, val app: File, val control: File, val projects: File) {
        fun project(id: String, leaf: String = id): DroideProject {
            val root = projects.resolve(leaf).apply { check(mkdir()) }
            root.resolve("source.kt").writeText("source for $id")
            return DroideProject(id, id, root.canonicalPath)
        }

        fun catalog(projects: List<DroideProject>, active: String) = rawCatalog(JsonArray(projects.map { project ->
            buildJsonObject {
                put("id", project.id)
                put("name", project.name)
                put("rootPath", project.rootPath)
            }
        }).toString(), active)

        fun rawCatalog(text: String, active: String) {
            val metadata = app.resolve(".droide").apply { check(isDirectory || mkdir()) }
            metadata.resolve("projects.json").writeText(text + "\n")
            metadata.resolve("active_project.txt").writeText(active + "\n")
            metadata.resolve("recent_projects.json").writeText(JsonArray(listOf(JsonPrimitive(active))).toString() + "\n")
        }

        suspend fun stageTracked(project: DroideProject): File {
            val store = FileProjectStore(app)
            val before = store.load()
            val coordinator = ProjectWorkspaceRelocation(app)
            coordinator.withLock {
                val intent = coordinator.prepare(project, before, before.filterNot { it.id == project.id }, store)
                coordinator.move(intent)
                coordinator.abandon()
            }
            return projects.resolve(".delete-${project.id}")
        }

        fun retain(root: File): File {
            val target = control.resolve(root.name)
            Files.move(root.toPath(), target.toPath())
            return target
        }

        suspend fun assertNormalRejected(project: DroideProject) {
            val manager = ProjectManager(app)
            val before = snapshot(base)
            val failure = runCatching { manager.init() }.exceptionOrNull()
            assertNotNull("Unavailable saved root must not be replaced by an empty workspace", failure)
            assertTrue("Failure must identify the unavailable saved path: $failure",
                generateSequence(failure) { it.cause }.take(16).any { it.message.orEmpty().contains(project.rootPath) })
            assertTrue(manager.projects.value.isEmpty())
            assertNull(manager.activeId.value)
            assertTrue(manager.recentProjects.value.isEmpty())
            assertTrue(manager.otherProjects.value.isEmpty())
            assertEquals(before, snapshot(base))
        }
    }

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val base = Files.createTempDirectory("project-manager-root-availability-").toFile().canonicalFile
        try {
            val app = base.resolve("app").apply { check(mkdir()) }
            val control = base.resolve("control").apply { check(mkdir()) }
            val projects = app.resolve("projects").apply { check(mkdir()) }
            block(Fixture(base, app, control, projects))
        } finally { PathSecurity.deleteTreeNoFollow(base) }
    }

    private companion object {
        data class Node(val key: String, val kind: String, val size: Long, val modified: String,
            val sha256: String?, val linkTarget: String?)

        fun key(file: File): String = requireNotNull(Files.readAttributes(file.toPath(),
            BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()).toString()

        fun snapshot(root: File): Map<String, Node> = Files.walk(root.toPath()).use { stream ->
            stream.iterator().asSequence().associate { path ->
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                val kind = when {
                    attributes.isDirectory -> "directory"
                    attributes.isRegularFile -> "file"
                    attributes.isSymbolicLink -> "link"
                    else -> error("Unexpected fixture node")
                }
                root.toPath().relativize(path).joinToString("/") to Node(
                    requireNotNull(attributes.fileKey()).toString(), kind, attributes.size(), attributes.lastModifiedTime().toString(),
                    if (attributes.isRegularFile) ProjectCopyEvidence.sha256(Files.readAllBytes(path)) else null,
                    if (attributes.isSymbolicLink) Files.readSymbolicLink(path).toString() else null)
            }
        }
    }
}
