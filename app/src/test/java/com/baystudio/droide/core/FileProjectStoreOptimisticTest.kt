package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Optimistic observed-version coverage; deliberately not external-writer filesystem CAS. */
class FileProjectStoreOptimisticTest {
    @get:Rule val temp = TemporaryFolder()
    private val catalog get() = File(temp.root, ".droide/projects.json")
    private fun project(id: String = "p-one", name: String = "Before") =
        DroideProject(id, name, File(temp.root, "projects/$id").apply { mkdirs() }.canonicalPath)
    private fun stages() = File(temp.root, ".droide").listFiles().orEmpty()
        .filter { it.name.startsWith(".projects.json.tmp-") }
    private suspend fun seed(): DroideProject = project().also { FileProjectStore(temp.root).save(listOf(it)) }
    private suspend fun conflict(action: suspend () -> Unit): ProjectCatalogConflictException {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected typed prepublication conflict, got $failure", failure is ProjectCatalogConflictException)
        assertFalse(ProjectMetadataPublication.isUnconfirmed(checkNotNull(failure)))
        return failure as ProjectCatalogConflictException
    }

    @Test fun staleSnapshotCannotRemoveExternalMembership() = runBlocking {
        val first = seed()
        val writer = FileProjectStore(temp.root)
        val snapshot = writer.loadCatalog()
        val external = project("p-two", "External")
        FileProjectStore(temp.root).save(listOf(first, external))
        val preserved = catalog.readBytes()
        conflict { writer.saveCatalog(snapshot.projects, snapshot.revision) }
        assertArrayEquals(preserved, catalog.readBytes())
        assertEquals(listOf(first, external), writer.load())
        assertEquals(1, stages().size)
        assertTrue(File(external.rootPath).isDirectory)
    }

    @Test fun revisionsBelongToCallersNotSharedStoreLastRead() = runBlocking {
        val first = seed()
        val store = FileProjectStore(temp.root)
        val old = store.loadCatalog()
        store.save(listOf(first.copy(name = "Later!")))
        val newer = store.loadCatalog()
        assertNotEquals(old.revision, newer.revision)
        conflict { store.saveCatalog(old.projects, old.revision) }
        assertEquals(newer, store.loadCatalog())
    }

    @Test fun sameSizeAndTimestampExternalEditStillConflicts() = runBlocking {
        seed()
        val store = FileProjectStore(temp.root)
        val old = store.loadCatalog()
        val time = Files.getLastModifiedTime(catalog.toPath())
        val external = catalog.readText().replace("Before", "After!")
        assertEquals(catalog.length(), external.toByteArray().size.toLong())
        catalog.writeText(external)
        Files.setLastModifiedTime(catalog.toPath(), time)
        conflict { store.saveCatalog(old.projects, old.revision) }
        assertEquals(external, catalog.readText())
    }

    @Test fun missingBaselineCanPublishAfterOwnWorkspaceDirectoryCreation() = runBlocking {
        val store = FileProjectStore(temp.root)
        val missing = store.loadCatalog()
        val created = project("default", "Default")
        val revision = store.saveCatalog(listOf(created), missing.revision)
        assertEquals(revision, store.loadCatalog().revision)
        assertEquals(listOf(created), store.load())
    }

    @Test fun initialMissingCatalogWithExistingWorkspaceIsStillRejected() = runBlocking {
        project()
        assertTrue(runCatching { FileProjectStore(temp.root).loadCatalog() }.exceptionOrNull() is IOException)
        assertFalse(catalog.exists())
    }

    @Test fun missingBaselineCannotOverwriteNewCatalog() = runBlocking {
        val store = FileProjectStore(temp.root)
        val missing = store.loadCatalog()
        val created = project()
        store.save(listOf(created))
        val bytes = catalog.readBytes()
        conflict { store.saveCatalog(emptyList(), missing.revision) }
        assertArrayEquals(bytes, catalog.readBytes())
    }

    @Test fun removedTargetDoesNotGetRecreatedByStaleSave() = runBlocking {
        seed()
        val store = FileProjectStore(temp.root)
        val old = store.loadCatalog()
        Files.delete(catalog.toPath())
        conflict { store.saveCatalog(old.projects, old.revision) }
        assertFalse(catalog.exists())
        assertEquals(1, stages().size)
    }

    @Test fun successfulSequentialSaveReturnsExactSerializedRevision() = runBlocking {
        seed()
        val store = FileProjectStore(temp.root)
        val old = store.loadCatalog()
        val next = old.projects.map { it.copy(name = "Next") }
        val revision = store.saveCatalog(next, old.revision)
        assertEquals(DiskFileVersion.fromText(catalog.readText()), revision)
        assertEquals(store.catalogRevision(next), revision)
        val last = next.map { it.copy(name = "Last") }
        store.saveCatalog(last, revision)
        assertEquals(last, store.load())
    }

    @Test fun unsupportedExternalCatalogIsPreserved() = runBlocking {
        seed()
        val store = FileProjectStore(temp.root)
        val old = store.loadCatalog()
        catalog.writeText("{\"future\":true}")
        assertTrue(runCatching { store.saveCatalog(old.projects, old.revision) }.exceptionOrNull() is IOException)
        assertEquals("{\"future\":true}", catalog.readText())
    }

    private fun durabilityBeforeReplace(action: () -> Unit): StreamPublicationDurability =
        object : StreamPublicationDurability {
            private var calls = 0
            override fun forceFile(channel: FileChannel) = StreamPublicationDurability.platform.forceFile(channel)
            override fun forceDirectory(directory: File) {
                StreamPublicationDurability.platform.forceDirectory(directory)
                if (++calls == 2) action() // Staged bytes forced, before final revision observation.
            }
        }

    @Test fun externalWriteBeforeFinalObservationPreservesBothVersions() = runBlocking {
        seed()
        val baseline = FileProjectStore(temp.root).loadCatalog()
        val external = catalog.readText().replace("Before", "External")
        var replaces = 0
        val store = FileProjectStore(temp.root, durabilityBeforeReplace { catalog.writeText(external) }, replace = { from, to, options ->
            replaces++
            Files.move(from, to, *options)
        })
        conflict { store.saveCatalog(baseline.projects.map { it.copy(name = "Proposal") }, baseline.revision) }
        assertEquals(0, replaces)
        assertEquals(external, catalog.readText())
        assertTrue(stages().single().readText().contains("Proposal"))
    }

    @Test fun externalWriteInsideReplaceRemainsAnExplicitOptimisticLimitation() = runBlocking {
        seed()
        val baseline = FileProjectStore(temp.root).loadCatalog()
        val external = catalog.readText().replace("Before", "External")
        val store = FileProjectStore(temp.root) { from, to, options ->
            // Simulates a writer AFTER the last observation. No CAS guarantee is claimed.
            Files.write(to, external.toByteArray())
            Files.move(from, to, *options)
        }
        val proposed = baseline.projects.map { it.copy(name = "Proposal") }
        store.saveCatalog(proposed, baseline.revision)
        assertEquals(proposed, store.load())
        assertNotEquals(external, catalog.readText())
    }

    @Test fun legacyAndVersionedCooperatingWritersShareSerialization() = runBlocking {
        val first = seed()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondReplaced = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val a = FileProjectStore(temp.root) { from, to, options ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Files.move(from, to, *options)
        }
        val b = FileProjectStore(temp.root) { from, to, options ->
            secondReplaced.countDown()
            Files.move(from, to, *options)
        }
        val baseline = a.loadCatalog()
        val firstThread = thread {
            try { runBlocking { a.saveCatalog(listOf(first.copy(name = "First")), baseline.revision) } }
            catch (error: Throwable) { failure.set(error) }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val secondThread = thread {
            try { secondStarted.countDown(); runBlocking { b.save(listOf(first.copy(name = "Second"))) } }
            catch (error: Throwable) { failure.set(error) }
        }
        try {
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFalse(secondReplaced.await(150, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            firstThread.join(5_000)
            secondThread.join(5_000)
        }
        assertFalse(firstThread.isAlive)
        assertFalse(secondThread.isAlive)
        failure.get()?.let { throw AssertionError("Publication failed", it) }
        assertEquals("Second", b.load().single().name)
    }
}
