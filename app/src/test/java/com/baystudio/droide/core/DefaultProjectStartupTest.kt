package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DefaultProjectStartupTest {
    @get:org.junit.Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        val app = Files.createTempDirectory("default-startup-").toFile()
        try { withTimeout(60_000) { block(app) } }
        finally { assertTrue(PathSecurity.deleteTreeNoFollow(app)) }
    }
    private fun snapshot(root: File): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.iterator().asSequence().associate { path ->
            root.toPath().relativize(path).toString() to when {
                Files.isSymbolicLink(path) -> "link:${Files.readSymbolicLink(path)}"
                Files.isDirectory(path) -> "dir:${Files.getAttribute(path, "unix:ino") }"
                else -> MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
            }
        }
    }

    @Test fun applicationStartupRegistersBeforePublishingDefaultAndReopensAfterMoreProjects() = fixture { app ->
        val manager = ProjectManager.forApplication(app)
        manager.init()
        assertEquals("default", manager.activeId.value)
        assertTrue(app.resolve("catalog-startup/catalog-membership.json").isFile)
        app.resolve("projects/default/keep.bin").writeBytes(byteArrayOf(0, -1, 3))
        val created = manager.createFromTemplate("Second", "empty").first
        val reopened = ProjectManager.forApplication(app)
        reopened.init()
        assertTrue(reopened.projects.value.any { it.id == created.id })
        assertArrayEquals(byteArrayOf(0, -1, 3), app.resolve("projects/default/keep.bin").readBytes())
    }

    @Test fun updateEvidenceBeforeFirstRegistrationIsPreservedWithoutStartupMutation() = fixture { app ->
        app.resolve("catalog-startup").mkdir()
        app.resolve("catalog-startup/catalog-updates").mkdir()
        app.resolve("catalog-startup/catalog-updates/retained").writeText("keep")
        val before = snapshot(app)
        assertNotNull(runCatching { ProjectManager.forApplication(app).init() }.exceptionOrNull())
        assertEquals(before, snapshot(app))
        assertFalse(app.resolve(".droide").exists())
    }

    @Test fun laterUpdateEvidenceBlocksDefaultProjectMutationBeforeCreatingDirectory() = fixture { app ->
        val manager = ProjectManager.forApplication(app).apply { init() }
        app.resolve("catalog-startup/startup-admission.json").writeText("unreviewed choice")
        val before = snapshot(app)
        val projects = manager.projects.value
        assertNotNull(runCatching { manager.add("Must not create") }.exceptionOrNull())
        assertEquals(before, snapshot(app))
        assertEquals(projects, manager.projects.value)
        assertNotNull(runCatching { ProjectManager.forApplication(app).init(readOnlyExisting = true) }.exceptionOrNull())
        assertEquals(before, snapshot(app))
    }

    @Test fun modeGuardDuringEnrollmentLeavesEvidenceAndNeverActivatesSession() = fixture { app ->
        var injected = false
        assertNotNull(runCatching { DefaultProjectStartup(app) { point ->
            if (!injected && point.startsWith("enrollment/")) {
                injected = true
                app.resolve("catalog-startup/.startup-admission.json.pending").writeText("interrupted choice")
            }
        }.prepare() }.exceptionOrNull())
        assertTrue(injected)
        assertEquals("interrupted choice", app.resolve("catalog-startup/.startup-admission.json.pending").readText())
        val before = snapshot(app)
        assertNotNull(runCatching { ProjectManager.forApplication(app).init() }.exceptionOrNull())
        assertEquals(before, snapshot(app))
    }

    @Test fun publishedDirectoryWithoutCatalogCanFinishOnNextApplicationStartup() = runBlocking {
        for (boundary in listOf("directory/after-directory-move", "directory-ready", "registered")) fixture { app ->
            var hit = false
            assertNotNull(runCatching { DefaultProjectStartup(app) { point ->
                if (point == boundary) { hit = true; throw CancellationException(boundary) }
            }.prepare() }.exceptionOrNull())
            assertTrue(boundary, hit)
            val root = app.resolve("projects/default")
            val identity = Files.getAttribute(root.toPath(), "unix:ino")
            root.resolve("retained.txt").writeText("work after interruption")
            val reopened = ProjectManager.forApplication(app)
            reopened.init()
            assertEquals(identity, Files.getAttribute(root.toPath(), "unix:ino"))
            assertEquals("work after interruption", root.resolve("retained.txt").readText())
            assertEquals(root.path, reopened.active()!!.rootPath)
        }
    }

    @Test fun publicationIntentWithoutMovedDirectoryIsNeverReplayed() = fixture { app ->
        assertNotNull(runCatching { DefaultProjectStartup(app) {
            if (it == "directory/before-directory-move") throw CancellationException("stop")
        }.prepare() }.exceptionOrNull())
        val before = snapshot(app)
        assertNotNull(runCatching { ProjectManager.forApplication(app).init() }.exceptionOrNull())
        assertEquals(before, snapshot(app)); assertFalse(app.resolve("projects/default").exists())
    }

    @Test fun changedPublishedDirectoryIsNotAdoptedAfterInterruption() = fixture { app ->
        assertNotNull(runCatching { DefaultProjectStartup(app) {
            if (it == "directory-ready") throw CancellationException("stop")
        }.prepare() }.exceptionOrNull())
        val root = app.resolve("projects/default")
        Files.move(root.toPath(), app.resolve("preserved-root").toPath())
        assertTrue(root.mkdir()); root.resolve("foreign").writeText("never adopt")
        val before = snapshot(app)
        assertNotNull(runCatching { ProjectManager.forApplication(app).init() }.exceptionOrNull())
        assertEquals(before, snapshot(app))
    }

    @Test fun arbitraryExistingProjectWithoutCatalogIsPreservedAndRefused() = fixture { app ->
        app.resolve("projects/default").mkdirs(); app.resolve("projects/default/keep").writeText("user data")
        assertNotNull(runCatching { ProjectManager.forApplication(app).init() }.exceptionOrNull())
        assertEquals("user data", app.resolve("projects/default/keep").readText())
        assertFalse(app.resolve("catalog-startup").exists()); assertFalse(app.resolve(".droide/projects.json").exists())
    }

    @Test fun legacyCatalogIsNotEnrolledOrReformattedByStartupPreparation() = fixture { app ->
        val root = app.resolve("projects/default").apply { mkdirs() }
        FileProjectStore(app).save(listOf(DroideProject("default", "Default", root.path)))
        val before = snapshot(app)
        DefaultProjectStartup(app).prepare()
        assertEquals(before, snapshot(app)); assertFalse(app.resolve("catalog-startup").exists())
    }

    @Test fun readOnlyStartupNeverCreatesOrRecoversRegistration() = fixture { app ->
        val before = snapshot(app)
        assertNotNull(runCatching { ProjectManager.forApplication(app).init(readOnlyExisting = true) }.exceptionOrNull())
        assertEquals(before, snapshot(app))
    }

    @Test fun emptySetupStorageCanBeEnrolledOnlyWhenNoCatalogOrProjectsExist() = fixture { app ->
        app.resolve("catalog-startup").mkdir()
        ProjectManager.forApplication(app).init()
        assertTrue(app.resolve(".droide/projects.json").isFile)
    }

    @Test fun concurrentApplicationManagersShareOneRegistration() = fixture { app ->
        coroutineScope { List(3) { async(Dispatchers.IO) { ProjectManager.forApplication(app).init() } }.awaitAll() }
        assertEquals(1, FileProjectStore(app).load().size)
        assertEquals(setOf("default"), app.resolve("projects").list()!!.toSet())
    }

    @Test fun startupStorageSymlinkIsRefusedWithoutChangingTarget() = fixture { app ->
        val target = app.resolve("untouched").apply { mkdir(); resolve("keep").writeText("keep") }
        Files.createSymbolicLink(app.resolve("catalog-startup").toPath(), target.toPath())
        val before = snapshot(target)
        assertNotNull(runCatching { ProjectManager.forApplication(app).init() }.exceptionOrNull())
        assertEquals(before, snapshot(target))
    }
}
