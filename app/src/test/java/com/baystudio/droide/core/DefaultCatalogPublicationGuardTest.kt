package com.baystudio.droide.core

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultCatalogPublicationGuardTest {
    @get:Rule val temp = TemporaryFolder()
    private fun evidence(app: File) = app.resolve("catalog-startup").apply { mkdirs() }
        .resolve("catalog-updates").apply { mkdir() }
    private fun project(app: File, name: String) = DroideProject("default", name,
        app.resolve("projects/default").apply { mkdirs() }.path)
    private fun store(app: File, guard: DefaultCatalogModeGuard,
                      durability: StreamPublicationDurability = StreamPublicationDurability.platform,
                      afterReplace: () -> Unit = {}) = FileProjectStore(app, durability, { from, to, options ->
        Files.move(from, to, *options)
        afterReplace()
    }, guard::requireDefaultMode)

    @Test fun initialAndNoopCatalogAndActiveWritesRejectExistingEvidence() = runBlocking {
        val app = temp.newFolder()
        val record = project(app, "Original")
        FileProjectStore(app) { from, to, options -> Files.move(from, to, *options); Unit }.save(listOf(record))
        val before = app.resolve(".droide/projects.json").readBytes()
        evidence(app)
        val guarded = store(app, DefaultCatalogModeGuard(app))
        assertTrue(runCatching { guarded.save(listOf(record)) }.exceptionOrNull() is DefaultCatalogModeGuard.Blocked)
        assertTrue(runCatching { guarded.saveActiveId("default") }.exceptionOrNull() is DefaultCatalogModeGuard.Blocked)
        assertArrayEquals(before, app.resolve(".droide/projects.json").readBytes())
        assertFalse(app.resolve(".droide/active_project.txt").exists())
        assertEquals(listOf(record), guarded.load())
    }

    @Test fun evidenceDuringStagingRejectsBeforeCatalogAndActiveReplacement() = runBlocking {
        for (active in listOf(false, true)) {
            val app = temp.newFolder()
            val old = project(app, "Original")
            val plain = FileProjectStore(app) { from, to, options -> Files.move(from, to, *options); Unit }
            plain.save(listOf(old)); plain.saveActiveId("default")
            val target = app.resolve(if (active) ".droide/active_project.txt" else ".droide/projects.json")
            val before = target.readBytes()
            var calls = 0
            val durability = object : StreamPublicationDurability {
                override fun forceFile(channel: FileChannel) = channel.force(true)
                override fun forceDirectory(directory: File) {
                    StreamPublicationDurability.platform.forceDirectory(directory)
                    if (++calls == 2) evidence(app)
                }
            }
            val guarded = store(app, DefaultCatalogModeGuard(app), durability)
            val failure = runCatching { if (active) guarded.saveActiveId("changed") else guarded.save(listOf(old.copy(name="Changed"))) }.exceptionOrNull()
            assertTrue(failure is DefaultCatalogModeGuard.Blocked)
            assertFalse(ProjectMetadataPublication.isUnconfirmed(failure!!))
            assertArrayEquals(before, target.readBytes())
            assertTrue(target.parentFile.list()!!.any { it.startsWith(".${target.name}.tmp-") })
        }
    }

    @Test fun evidenceAfterReplaceProducesUnconfirmedOutcomeForCatalogAndActiveId() = runBlocking {
        for (active in listOf(false, true)) {
            val app = temp.newFolder()
            val old = project(app, "Original")
            val guarded = store(app, DefaultCatalogModeGuard(app), afterReplace = { evidence(app) })
            val failure = runCatching { if (active) guarded.saveActiveId("changed") else guarded.save(listOf(old)) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(ProjectMetadataPublication.isUnconfirmed(failure!!))
            assertTrue(app.resolve(if(active) ".droide/active_project.txt" else ".droide/projects.json").isFile)
            assertTrue(runCatching { guarded.saveActiveId("later") }.exceptionOrNull() is DefaultCatalogModeGuard.Blocked)
        }
    }

    @Test fun lostReplaceReturnCannotHideNewEvidenceOrBecomeSuccess() = runBlocking {
        for (active in listOf(false, true)) {
            val app = temp.newFolder()
            val old = project(app, "Original")
            val guarded = store(app, DefaultCatalogModeGuard(app), afterReplace = {
                evidence(app)
                throw kotlinx.coroutines.CancellationException("lost native return")
            })
            val failure = runCatching { if (active) guarded.saveActiveId("changed") else guarded.save(listOf(old)) }.exceptionOrNull()
            assertTrue(failure is kotlinx.coroutines.CancellationException)
            assertTrue(ProjectMetadataPublication.isUnconfirmed(failure!!))
            assertTrue(failure.suppressed.any { it is DefaultCatalogModeGuard.Blocked })
            assertTrue(app.resolve(if(active) ".droide/active_project.txt" else ".droide/projects.json").isFile)
        }
    }

    @Test fun managerDoesNotPromoteEqualPublishedBytesAfterModeGuardFailure() = runBlocking {
        val app = temp.newFolder()
        var inject = false
        val guarded = store(app, DefaultCatalogModeGuard(app), afterReplace = { if (inject) evidence(app) })
        val manager = ProjectManager(app, guarded).apply { init() }
        val before = manager.projects.value
        inject = true
        assertNotNull(runCatching { manager.add("Published but blocked") }.exceptionOrNull())
        assertTrue(manager.selectionIsUncertain)
        assertEquals(before, manager.projects.value)
        assertTrue(app.resolve(".droide/projects.json").readText().contains("Published but blocked"))
    }
    @Test fun managerRejectsPrepublicationEvidenceEvenWhenExternalBytesEqualProposal() = runBlocking {
        val app = temp.newFolder()
        var inject = false
        var flushes = 0
        val durability = object : StreamPublicationDurability {
            override fun forceFile(channel: FileChannel) = channel.force(true)
            override fun forceDirectory(directory: File) {
                StreamPublicationDurability.platform.forceDirectory(directory)
                if (inject && ++flushes == 2) {
                    val metadata = app.resolve(".droide")
                    val staged = metadata.listFiles()!!.single { it.name.startsWith(".projects.json.tmp-") }
                    metadata.resolve("projects.json").writeBytes(staged.readBytes())
                    evidence(app)
                }
            }
        }
        val manager = ProjectManager(app, store(app, DefaultCatalogModeGuard(app), durability)).apply { init() }
        val before = manager.projects.value
        inject = true
        assertNotNull(runCatching { manager.add("External equal proposal") }.exceptionOrNull())
        assertTrue(manager.selectionIsUncertain)
        assertEquals(before, manager.projects.value)
        assertTrue(app.resolve(".droide/projects.json").readText().contains("External equal proposal"))
    }

    @Test fun managerDoesNotPublishActiveSelectionAfterPostpublicationEvidence() = runBlocking {
        val app = temp.newFolder()
        var inject = false
        val manager = ProjectManager(app, store(app, DefaultCatalogModeGuard(app), afterReplace = {
            if (inject) evidence(app)
        })).apply { init() }
        val second = manager.add("Second")
        val before = manager.activeId.value
        inject = true
        assertNotNull(runCatching { manager.selectAndPublish(second.id) }.exceptionOrNull())
        assertTrue(manager.selectionIsUncertain)
        assertEquals(before, manager.activeId.value)
        assertEquals(second.id, app.resolve(".droide/active_project.txt").readText().trim())
    }

}
