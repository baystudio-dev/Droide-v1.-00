package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectWorkspaceRemovalTest {
    // Keep exact application failure identity observable even with coroutine recovery enabled.
    @OptIn(ExperimentalCoroutinesApi::class)
    private class FixtureIOException(message: String) : IOException(message), CopyableThrowable<FixtureIOException> {
        override fun createCopy(): FixtureIOException? = null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private class FixtureCancellation(message: String) : CancellationException(message), CopyableThrowable<FixtureCancellation> {
        override fun createCopy(): FixtureCancellation? = null
    }

    private class CallbackStore(val delegate: FileProjectStore) : ProjectStore by delegate {
        var writes = 0
        var beforeSave: (suspend (List<DroideProject>) -> Unit)? = null
        var afterSave: (suspend (List<DroideProject>) -> Unit)? = null
        var beforeActiveSave: (suspend () -> Unit)? = null
        override suspend fun save(all: List<DroideProject>) {
            writes++
            val before = beforeSave.also { beforeSave = null }
            before?.invoke(all)
            delegate.save(all)
            val after = afterSave.also { afterSave = null }
            after?.invoke(all)
        }
        override suspend fun saveActiveId(id: String) {
            val before = beforeActiveSave.also { beforeActiveSave = null }
            before?.invoke()
            delegate.saveActiveId(id)
        }
    }

    private class Recents : ProjectRecentsStore {
        var ids: List<String>? = null
        var failWrites = false
        var beforeSave: (suspend () -> Unit)? = null
        override suspend fun load() = ids
        override suspend fun save(ids: List<String>) {
            val before = beforeSave.also { beforeSave = null }
            before?.invoke()
            if (failWrites) throw IOException("recent-list write failed")
            this.ids = ids
        }
    }

    private class Fixture(val app: File, val store: CallbackStore, val recents: Recents,
                          val manager: ProjectManager, val project: DroideProject) {
        val root = File(project.rootPath)
        val source = File(root, "pending.txt")
        val recovery = File(root.parentFile, ".delete-${project.id}")
        val records = manager.projects.value.toList()
        val active = manager.activeId.value

        suspend fun assertRemoved() {
            val expected = records.map { if (it.id == project.id) it.copy(workspaceRemoved = true) else it }
            assertEquals(expected, manager.projects.value)
            assertEquals(expected, store.load())
            assertEquals(active, manager.activeId.value)
            assertEquals(active, store.loadActiveId())
            assertFalse(manager.recentProjects.value.any { it.id == project.id })
            assertFalse(manager.otherProjects.value.any { it.id == project.id })
            assertEquals(listOf(project.copy(workspaceRemoved = true)), manager.storedProjects.value)
            assertFalse(recovery.exists())
        }
    }

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val app = Files.createTempDirectory("droide-membership-removal-").toFile()
        try {
            val store = CallbackStore(FileProjectStore(app))
            val recents = Recents()
            val manager = ProjectManager(app, store, recents).apply { init() }
            val project = manager.add("Sample")
            manager.restoreToRecent(project.id)
            val f = Fixture(app, store, recents, manager, project)
            f.source.writeText("unpublished source")
            block(f)
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    private fun key(file: File) =
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()

    @Test fun removalChangesMembershipWithoutMovingOrEditingAnyProjectState() = fixture { f ->
        val protected = listOf(
            f.source,
            File(f.root, ".git/config"),
            File(f.root, ".droide/sessions/pending.json"),
            File(f.root, ".droide/sessions/plugins.sqlite"),
            File(f.app, "agent-workspaces/fixture/pending-cli.txt"),
            File(f.app, "agent-workspace-state/fixture/baseline.bin"),
            File(f.app, ".droide/editor-recovery/${f.project.id}.bin"),
            File(f.app, ".droide/editor-recovery/${f.project.id}.blobs/pending.bin"),
            File(f.app, "shared-toolchain/bin/compiler"),
        )
        protected.drop(1).forEachIndexed { index, file ->
            file.parentFile!!.mkdirs()
            file.writeText("retained state $index")
        }
        val before = protected.associateWith { Triple(key(it), it.lastModified(), it.readBytes().toList()) }
        val rootKey = key(f.root)
        val rootMtime = f.root.lastModified()

        assertEquals(f.project.copy(workspaceRemoved = true), f.manager.removeWorkspace(f.project.id))

        f.assertRemoved()
        assertEquals(rootKey, key(f.root))
        assertEquals(rootMtime, f.root.lastModified())
        assertEquals(before, protected.associateWith { Triple(key(it), it.lastModified(), it.readBytes().toList()) })
    }

    @Test fun restartDoesNotResurrectMembershipOrCleanTheOriginalFiles() = fixture { f ->
        val rootKey = key(f.root)
        val sourceKey = key(f.source)
        f.manager.removeWorkspace(f.project.id)
        val restarted = ProjectManager(f.app, recentsStore = f.recents).apply { init() }
        assertTrue(restarted.collectStartupGarbage().isEmpty())
        assertEquals(listOf(f.project.copy(workspaceRemoved = true)), restarted.storedProjects.value)
        assertEquals(rootKey, key(f.root))
        assertEquals(sourceKey, key(f.source))
        assertEquals("unpublished source", f.source.readText())
        assertFalse(f.recovery.exists())
    }

    @Test fun openDescriptorCanFinishWritingAtTheOriginalName() = fixture { f ->
        val originalKey = key(f.source)
        val finalText = "CLI writer finished after catalog publication"
        FileChannel.open(f.source.toPath(), WRITE).use { writer ->
            f.store.afterSave = {
                val data = ByteBuffer.wrap(finalText.toByteArray())
                while (data.hasRemaining()) writer.write(data)
                writer.truncate(finalText.toByteArray().size.toLong())
                writer.force(true)
            }
            f.manager.removeWorkspace(f.project.id)
            assertEquals(originalKey, key(f.source))
            assertEquals(finalText, f.source.readText())
        }
    }

    @Test(timeout = 15_000) fun runningProcessKeepsItsCwdAndCanReadTheOriginalPath() = fixture { f ->
        val process = ProcessBuilder("/bin/sh", "-c", "printf 'ready:%s\\n' \"$$\"; read signal; cat pending.txt")
            .directory(f.root).redirectErrorStream(true).start()
        try {
            val output = process.inputStream.bufferedReader()
            val ready = output.readLine()
            assertTrue(ready.startsWith("ready:"))
            val pid = ready.removePrefix("ready:").toLong()
            assertEquals(f.project.copy(workspaceRemoved = true), f.manager.removeWorkspace(f.project.id))
            assertTrue(process.isAlive)
            assertEquals(f.root.toPath(), Files.readSymbolicLink(File("/proc/$pid/cwd").toPath()))
            f.source.writeText("new source at the same path\n")
            process.outputStream.bufferedWriter().use { it.write("continue\n") }
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            assertEquals("new source at the same path", output.readLine())
        } finally { if (process.isAlive) process.destroyForcibly().waitFor() }
    }

    @Test fun missingRootDoesNotCauseDirectoryCreationOrRecoveryRelocation() = fixture { f ->
        val held = File(f.app, "held-source")
        Files.move(f.root.toPath(), held.toPath())
        val heldKey = key(held)
        assertEquals(f.project.copy(workspaceRemoved = true), f.manager.removeWorkspace(f.project.id))
        f.assertRemoved()
        assertFalse(f.root.exists())
        assertEquals(heldKey, key(held))
        assertEquals("unpublished source", File(held, "pending.txt").readText())
    }

    @Test fun nestedSymlinkAndUnrelatedExternalFolderAreNotTraversedOrChanged() = fixture { f ->
        val outside = File(f.app, "external-source").apply { mkdir() }
        val external = File(outside, "keep.txt").apply { writeText("external source") }
        val link = File(f.root, "dependency").toPath()
        Files.createSymbolicLink(link, outside.toPath())
        val externalKey = key(external)
        f.manager.removeWorkspace(f.project.id)
        assertEquals(outside.toPath(), Files.readSymbolicLink(link))
        assertEquals(externalKey, key(external))
        assertEquals("external source", external.readText())
    }

    @Test fun defaultProjectCannotBeRemoved() = fixture { f ->
        val before = f.store.writes
        assertThrows(IllegalArgumentException::class.java) { runBlocking { f.manager.removeWorkspace("default") } }
        assertEquals(before, f.store.writes)
        assertEquals(f.records, f.manager.projects.value)
    }

    @Test fun activeProjectIsRecheckedBeforeMembershipRemoval() = fixture { f ->
        f.manager.switch(f.project.id)
        val before = f.store.writes
        assertThrows(IllegalArgumentException::class.java) { runBlocking { f.manager.removeWorkspace(f.project.id) } }
        assertEquals(before, f.store.writes)
        assertEquals(f.records, f.manager.projects.value)
        assertEquals("unpublished source", f.source.readText())
    }

    @Test fun unknownProjectIsANoOpWithoutCatalogWrites() = fixture { f ->
        val before = f.store.writes
        assertNull(f.manager.removeWorkspace("not-registered"))
        assertEquals(before, f.store.writes)
        assertEquals(f.records, f.manager.projects.value)
    }

    @Test fun failedCatalogWritePreservesRegistrationAndFiles() = fixture { f ->
        val failure = FixtureIOException("catalog write rejected")
        f.store.beforeSave = { throw failure }
        val caught = assertThrows(IOException::class.java) { runBlocking { f.manager.removeWorkspace(f.project.id) } }
        assertSame(failure, caught)
        assertEquals(f.records, f.manager.projects.value)
        assertEquals(f.records, f.store.load())
        assertTrue(f.manager.recentProjects.value.any { it.id == f.project.id })
        assertEquals("unpublished source", f.source.readText())
        assertFalse(f.recovery.exists())
    }

    @Test fun committedCatalogLostReturnPublishesRemovalWithoutTouchingSource() = fixture { f ->
        f.store.afterSave = { throw IOException("catalog return was lost") }
        assertEquals(f.project.copy(workspaceRemoved = true), f.manager.removeWorkspace(f.project.id))
        f.assertRemoved()
        assertFalse(f.manager.selectionIsUncertain)
        assertEquals("unpublished source", f.source.readText())
    }

    @Test fun cancellationAfterCommitPublishesKnownOutcomeBeforeRethrowing() = fixture { f ->
        val cancellation = FixtureCancellation("interrupted after commit")
        f.store.afterSave = { throw cancellation }
        val caught = assertThrows(CancellationException::class.java) { runBlocking { f.manager.removeWorkspace(f.project.id) } }
        assertSame(cancellation, caught)
        f.assertRemoved()
        assertEquals("unpublished source", f.source.readText())
    }

    @Test fun ambiguousCatalogCommitStopsFurtherMutationsAndKeepsFiles() = fixture { f ->
        f.store.afterSave = {
            f.store.delegate.save(emptyList())
            throw IOException("unexpected saved catalog")
        }
        assertThrows(ProjectCatalogUncertainException::class.java) { runBlocking { f.manager.removeWorkspace(f.project.id) } }
        assertTrue(f.manager.selectionIsUncertain)
        assertEquals(f.records, f.manager.projects.value)
        assertThrows(ProjectCatalogUncertainException::class.java) { runBlocking { f.manager.add("Blocked") } }
        assertEquals("unpublished source", f.source.readText())
        assertFalse(f.recovery.exists())
    }

    @Test fun failedRecentListWriteCannotResurrectRemovedMembershipAfterRestart() = fixture { f ->
        f.recents.failWrites = true
        f.manager.removeWorkspace(f.project.id)
        f.assertRemoved()
        f.recents.failWrites = false
        val restarted = ProjectManager(f.app, recentsStore = f.recents).apply { init() }
        assertEquals(listOf(f.project.copy(workspaceRemoved = true)), restarted.storedProjects.value)
        assertFalse(restarted.recentProjects.value.any { it.id == f.project.id })
        assertEquals("unpublished source", f.source.readText())
    }

    @Test fun concurrentRegistrationAndRemovalDoNotLoseEitherCatalogOutcome() = fixture { f ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.store.beforeSave = { entered.complete(Unit); release.await() }
        val removing = kotlinx.coroutines.coroutineScope {
            val first = async(Dispatchers.Default) { f.manager.removeWorkspace(f.project.id) }
            entered.await()
            val second = async(Dispatchers.Default) { f.manager.add("Concurrent project") }
            release.complete(Unit)
            first.await() to second.await()
        }
        assertEquals(f.project.copy(workspaceRemoved = true), removing.first)
        assertEquals(f.records.map { if (it.id == f.project.id) it.copy(workspaceRemoved = true) else it } + removing.second, f.manager.projects.value)
        assertEquals(f.manager.projects.value, f.store.load())
        assertEquals("unpublished source", f.source.readText())
        assertTrue(File(removing.second.rootPath).isDirectory)
        assertFalse(f.recovery.exists())
    }

    @Test fun registrationThatCommitsFirstIsKeptByConcurrentRemoval() = fixture { f ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.store.beforeSave = { entered.complete(Unit); release.await() }
        val added = coroutineScope {
            val registration = async(Dispatchers.Default) { f.manager.add("Concurrent project") }
            entered.await()
            val removal = async(Dispatchers.Default) { f.manager.removeWorkspace(f.project.id) }
            release.complete(Unit)
            registration.await().also { assertEquals(f.project.copy(workspaceRemoved = true), removal.await()) }
        }
        assertEquals(f.records.map { if (it.id == f.project.id) it.copy(workspaceRemoved = true) else it } + added, f.store.load())
        assertEquals(f.store.load(), f.manager.projects.value)
        assertEquals("unpublished source", f.source.readText())
    }

    @Test fun selectionThatCommitsFirstBlocksTheQueuedRemoval() = fixture { f ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.store.beforeActiveSave = { entered.complete(Unit); release.await() }
        val outcome = coroutineScope {
            val selecting = async(Dispatchers.Default) { f.manager.switch(f.project.id) }
            entered.await()
            val removal = async(Dispatchers.Default) { runCatching { f.manager.removeWorkspace(f.project.id) } }
            release.complete(Unit)
            selecting.await()
            removal.await()
        }
        assertTrue(outcome.exceptionOrNull() is IllegalArgumentException)
        assertEquals(f.project.id, f.manager.activeId.value)
        assertEquals(f.records, f.store.load())
        assertEquals("unpublished source", f.source.readText())
        assertFalse(f.recovery.exists())
    }

    @Test fun actualJobCancellationDuringRecentsSaveKeepsTheKnownCatalogOutcome() = fixture { f ->
        val entered = CompletableDeferred<Unit>()
        f.recents.beforeSave = { entered.complete(Unit); awaitCancellation() }
        coroutineScope {
            val removal = launch(Dispatchers.Default) { f.manager.removeWorkspace(f.project.id) }
            entered.await()
            removal.cancelAndJoin()
            assertTrue(removal.isCancelled)
        }
        f.assertRemoved()
        assertEquals("unpublished source", f.source.readText())
        val restarted = ProjectManager(f.app, recentsStore = f.recents).apply { init() }
        assertEquals(listOf(f.project.copy(workspaceRemoved = true)), restarted.storedProjects.value)
        assertFalse(restarted.recentProjects.value.any { it.id == f.project.id })
        assertEquals("unpublished source", f.source.readText())
    }
}
