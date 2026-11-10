package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProjectWorkspaceRelocationTest {
    private class Store(val disk: FileProjectStore) : ProjectStore by disk {
        var beforeSave: (suspend () -> Unit)? = null
        var afterSave: (() -> Unit)? = null
        var beforeActive: (suspend () -> Unit)? = null
        override suspend fun save(all: List<DroideProject>) {
            beforeSave?.invoke()
            disk.save(all)
            afterSave?.invoke()
        }
        override suspend fun saveActiveId(id: String) {
            beforeActive?.invoke()
            disk.saveActiveId(id)
        }
    }
    private class Fixture(val app: File, val store: Store, val manager: ProjectManager, val project: DroideProject) {
        val root = File(project.rootPath)
        val quarantine = File(root.parentFile, ".delete-" + project.id)
        val record = File(app, ".droide/workspace-relocation-" + project.id + ".json")
        val originalKey = key(root)
        suspend fun pending(move: Boolean = true, commit: Boolean = false) {
            val coordinator = ProjectWorkspaceRelocation(app)
            coordinator.withLock {
                val before = store.load()
                val after = before.filterNot { it.id == project.id }
                val intent = coordinator.prepare(project, before, after, store)
                if (move) coordinator.move(intent)
                if (commit) store.save(after)
            }
        }
        fun assertOriginal() {
            assertEquals(originalKey, key(root))
            assertEquals("original", File(root, "source.txt").readText())
            assertFalse(quarantine.exists())
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val app = Files.createTempDirectory("droide-relocation-protocol-").toFile()
        try {
            val store = Store(FileProjectStore(app))
            val manager = ProjectManager(app, store).apply { init() }
            val project = manager.add("Original")
            File(project.rootPath, "source.txt").writeText("original")
            Fixture(app, store, manager, project).block()
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    @Test fun clearingOccupiedDestinationAllowsExactRecoveryAndNormalDeleteRetry() = fixture {
        var entered = false
        store.beforeSave = {
            entered = true
            check(root.mkdir())
            File(root, "other.txt").writeText("other")
            throw IOException("refused")
        }
        assertTrue(runCatching { manager.deleteWorkspace(project.id) }.isFailure)
        assertTrue(entered)
        store.beforeSave = null
        val held = File(app, "held-other")
        Files.move(root.toPath(), held.toPath())
        manager.switch(project.id)
        assertOriginal()
        assertEquals("other", File(held, "other.txt").readText())
        manager.switch("default")
        manager.deleteWorkspace(project.id)
        assertEquals(originalKey, key(quarantine))
    }

    @Test fun restoredReceiptSurvivesUnrelatedEditsAndTwoRestartsThenRetry() = fixture {
        pending()
        var reopened = ProjectManager(app).apply { init() }
        assertOriginal()
        reopened.add("Unrelated")
        repeat(2) { reopened = ProjectManager(app).apply { init(); switch(project.id); switch("default") } }
        reopened.deleteWorkspace(project.id)
        assertEquals(originalKey, key(quarantine))
        assertEquals(1, File(app, ".droide").listFiles()!!.count { it.name.startsWith("workspace-relocation-") && it.name.endsWith(".json") })
    }

    @Test fun committedReceiptSurvivesUnrelatedEditsAndTwoRestartsWithoutReplay() = fixture {
        pending(commit = true)
        var reopened = ProjectManager(app).apply { init() }
        val unrelated = reopened.add("Unrelated")
        repeat(2) { reopened = ProjectManager(app).apply { init(); switch(unrelated.id) } }
        assertFalse(root.exists())
        assertEquals(originalKey, key(quarantine))
        assertEquals("original", File(quarantine, "source.txt").readText())
    }

    @Test fun preparedBeforeMoveSettlesAndCanBeRetried() = fixture {
        pending(move = false)
        val reopened = ProjectManager(app).apply { init() }
        assertOriginal()
        reopened.deleteWorkspace(project.id)
        assertEquals(originalKey, key(quarantine))
    }

    @Test fun readOnlyColdAndCachedStartupNeverRepairPendingRelocation() = fixture {
        val cached = ProjectManager(app).apply { init() }
        pending()
        val receipt = record.readBytes()
        val catalog = File(app, ".droide/projects.json").readBytes()
        assertTrue(runCatching { ProjectManager(app).init(readOnlyExisting = true) }.isFailure)
        assertTrue(runCatching { cached.init(readOnlyExisting = true) }.isFailure)
        assertArrayEquals(receipt, record.readBytes())
        assertArrayEquals(catalog, File(app, ".droide/projects.json").readBytes())
        assertFalse(root.exists())
        assertEquals(originalKey, key(quarantine))
        ProjectManager(app).init()
        assertOriginal()
    }

    @Test fun restoredReceiptRejectsCatalogRedirectOfItsProjectIdentity() = fixture {
        pending(move = false)
        ProjectManager(app).init()
        val other = File(root.parentFile, "other-root").apply { mkdir() }
        store.disk.save(store.load().map { if (it.id == project.id) it.copy(rootPath = other.path) else it })
        assertTrue(runCatching { ProjectManager(app).init() }.isFailure)
        assertOriginal()
        assertTrue(other.isDirectory)
    }

    @Test fun committedReceiptRejectsCatalogReownership() = fixture {
        pending(commit = true)
        ProjectManager(app).init()
        store.disk.save(store.load() + project)
        root.mkdir()
        assertTrue(runCatching { ProjectManager(app).init() }.isFailure)
        assertEquals(originalKey, key(quarantine))
    }

    @Test fun mismatchedCustomStoreRefusesBeforeMovingAnyBytes() = fixture {
        val bad = object : ProjectStore by store { override suspend fun load() = emptyList<DroideProject>() }
        val coordinator = ProjectWorkspaceRelocation(app)
        assertTrue(runCatching { coordinator.withLock { coordinator.prepare(project, store.load(), emptyList(), bad) } }.isFailure)
        assertOriginal()
        assertFalse(record.exists())
    }

    @Test fun realCancellationAfterRelocationRestoresOriginalAndReleasesAuthority() = fixture {
        val entered = CompletableDeferred<Unit>()
        store.beforeSave = { entered.complete(Unit); awaitCancellation() }
        coroutineScope {
            val deletion = launch { manager.deleteWorkspace(project.id) }
            entered.await()
            assertEquals(originalKey, key(quarantine))
            deletion.cancelAndJoin()
        }
        store.beforeSave = null
        assertOriginal()
        manager.add("After cancellation")
        manager.deleteWorkspace(project.id)
        assertEquals(originalKey, key(quarantine))
    }

    @Test fun liveDeletionExcludesSecondManagerUntilItsActualOutcome() = fixture {
        val second = ProjectManager(app).apply { init() }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.beforeSave = { entered.complete(Unit); release.await() }
        coroutineScope {
            val deletion = async { manager.deleteWorkspace(project.id) }
            entered.await()
            val selection = async(start = CoroutineStart.UNDISPATCHED) { runCatching { second.switch(project.id) } }
            assertFalse(selection.isCompleted)
            release.complete(Unit)
            deletion.await()
            assertTrue(selection.await().isFailure)
        }
        assertEquals(originalKey, key(quarantine))
    }

    @Test fun selectionPublicationExcludesCompetingDeletionAndChecksFreshActiveOwner() = fixture {
        val second = ProjectManager(app).apply { init() }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.beforeActive = { entered.complete(Unit); release.await() }
        coroutineScope {
            val selection = async { manager.switch(project.id) }
            entered.await()
            val deletion = async(start = CoroutineStart.UNDISPATCHED) { runCatching { second.deleteWorkspace(project.id) } }
            assertFalse(deletion.isCompleted)
            release.complete(Unit)
            selection.await()
            assertTrue(deletion.await().isFailure)
        }
        assertOriginal()
    }

    @Test fun terminalReceiptFailureCannotResurrectCommittedProjectInSameManager() = fixture {
        store.afterSave = { record.writeText("corrupt after verified catalog commit") }
        assertTrue(runCatching { manager.deleteWorkspace(project.id) }.isFailure)
        store.afterSave = null
        assertFalse(manager.projects.value.any { it.id == project.id })
        assertFalse(store.load().any { it.id == project.id })
        assertTrue(runCatching { manager.add("Must not resurrect") }.isFailure)
        assertFalse(store.load().any { it.id == project.id })
        assertEquals(originalKey, key(quarantine))
    }

    @Test fun publishedButUnconfirmedMoveIsRecoveredFromExactPhysicalIdentity() = fixture {
        val coordinator = ProjectWorkspaceRelocation(app, moveDirectory = { files, source, destination ->
            val real = files.moveNoReplaceDurable(source, files, destination)
            check(real.durable)
            AnchoredWorkspaceFiles.MoveReceipt(true, 5)
        })
        coordinator.withLock {
            val before = store.load()
            val intent = coordinator.prepare(project, before, before.filterNot { it.id == project.id }, store)
            assertTrue(runCatching { coordinator.move(intent) }.isFailure)
        }
        assertFalse(root.exists())
        assertEquals(originalKey, key(quarantine))
        ProjectManager(app).init()
        assertOriginal()
    }

    @Test fun receiptPublicationInterruptionDoesNotEraseIntentOrPreventRetry() = fixture {
        val coordinator = ProjectWorkspaceRelocation(app, checkpoint = { throw IOException("force boundary") })
        assertTrue(runCatching { coordinator.withLock {
            val before = store.load()
            coordinator.prepare(project, before, before.filterNot { it.id == project.id }, store)
        } }.isFailure)
        assertTrue(record.isFile)
        assertOriginal()
        val reopened = ProjectManager(app).apply { init() }
        reopened.deleteWorkspace(project.id)
        assertEquals(originalKey, key(quarantine))
    }

    @Test fun oversizedReceiptRemainsUnchanged() = fixture {
        pending(move = false)
        record.writeBytes(ByteArray(16_385) { 65 })
        assertTrue(runCatching { ProjectManager(app).init() }.isFailure)
        assertEquals(16_385, record.length().toInt())
        assertOriginal()
    }

    @Test fun dedicatedMetadataLeaseReleasesAfterFailureAndPinsOriginalInode() = fixture {
        val folder = File(app, "lease-test").apply { mkdir() }
        AnchoredWorkspaceFiles(folder).use { anchor ->
            anchor.createNew("lock").use { it.force(384) }
            anchor.openRead("lock").use { input ->
                val lease = input.acquireExclusiveMetadataLease()
                assertTrue(runCatching { input.acquireExclusiveMetadataLease() }.isFailure)
                assertTrue(runCatching { input.close() }.isFailure)
                lease.requireHeld()
                lease.close()
                assertTrue(runCatching { lease.requireHeld() }.isFailure)
                val next = input.acquireExclusiveMetadataLease()
                lease.close()
                next.requireHeld()
                next.close()
            }
        }
    }

    @Test fun replacingHeldLockPreventsMoveAndCannotRebindReceiptOnRestart() = fixture {
        val lock = File(app, ".droide/workspace-relocation.lock")
        val held = File(app, "held-lock")
        val coordinator = ProjectWorkspaceRelocation(app, checkpoint = { phase ->
            if (phase == "before-relocation") {
                Files.move(lock.toPath(), held.toPath())
                lock.writeBytes(byteArrayOf())
            }
        })
        assertTrue(runCatching { coordinator.withLock {
            val before = store.load()
            val intent = coordinator.prepare(project, before, before.filterNot { it.id == project.id }, store)
            coordinator.move(intent)
        } }.isFailure)
        assertOriginal()
        assertTrue(held.exists())
        assertTrue(runCatching { ProjectManager(app).init() }.isFailure)
        assertOriginal()
    }

    @Test fun identicalReplacementReceiptCannotBeOverwrittenDuringAnOwnedAttempt() = fixture {
        val held = File(app, "held-receipt")
        val coordinator = ProjectWorkspaceRelocation(app, checkpoint = { phase ->
            if (phase == "before-relocation") {
                Files.move(record.toPath(), held.toPath())
                record.writeBytes(held.readBytes())
            }
        })
        assertTrue(runCatching { coordinator.withLock {
            val before = store.load()
            val intent = coordinator.prepare(project, before, before.filterNot { it.id == project.id }, store)
            coordinator.move(intent)
        } }.isFailure)
        assertOriginal()
        assertArrayEquals(held.readBytes(), record.readBytes())
        assertNotEquals(key(held), key(record))
    }

    @Test fun repeatedProvenRollbacksReuseOneBoundedSlot() = fixture {
        var calls = 0
        store.beforeSave = { calls++; throw IOException("refused") }
        repeat(12) {
            assertTrue(runCatching { manager.deleteWorkspace(project.id) }.isFailure)
            assertOriginal()
        }
        assertEquals(12, calls)
        assertEquals(listOf(record.name), File(app, ".droide").listFiles()!!
            .filter { it.name.startsWith("workspace-relocation-") }.map { it.name })
        store.beforeSave = null
        manager.deleteWorkspace(project.id)
        assertEquals(originalKey, key(quarantine))
    }

    @Test fun capacityRefusesNewSlotButStillRestoresExistingPendingOperation() = fixture {
        pending()
        repeat(127) { File(app, ".droide/workspace-relocation-reserved-" + it + ".json.stage").writeText("") }
        val reopened = ProjectManager(app).apply { init() }
        assertOriginal()
        val extra = reopened.add("Extra")
        assertTrue(runCatching { reopened.deleteWorkspace(extra.id) }.isFailure)
        assertTrue(File(extra.rootPath).isDirectory)
        assertEquals(128, File(app, ".droide").listFiles()!!.count { it.name.startsWith("workspace-relocation-") })
        reopened.deleteWorkspace(project.id)
        assertEquals(originalKey, key(quarantine))
    }

    @Test fun committedCancellationStaysPrimaryWhenReceiptFinalizationAlsoFails() = fixture {
        val cancelled = CancellationException("committed return lost")
        store.afterSave = {
            record.writeText("receipt changed after commit")
            throw cancelled
        }
        val failure = runCatching { manager.deleteWorkspace(project.id) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(generateSequence(failure) { it.cause }.any { it === cancelled })
        assertTrue(failure!!.suppressed.isNotEmpty())
        assertFalse(manager.projects.value.any { it.id == project.id })
        assertFalse(store.load().any { it.id == project.id })
        assertEquals(originalKey, key(quarantine))
    }

    @Test fun startupDoesNotPublishAfterItsLockBindingChanges() = fixture {
        val lock = File(app, ".droide/workspace-relocation.lock")
        val recents = object : ProjectRecentsStore {
            override suspend fun load(): List<String>? = null
            override suspend fun save(ids: List<String>) {
                Files.move(lock.toPath(), File(app, "held-startup-lock").toPath())
                lock.writeBytes(byteArrayOf())
            }
        }
        val reopened = ProjectManager(app, recentsStore = recents)
        assertTrue(runCatching { reopened.init() }.isFailure)
        assertNull(reopened.activeId.value)
        assertTrue(reopened.projects.value.isEmpty())
        assertOriginal()
    }

    @Test fun newlyEnrolledPeerCommitCannotPublishStaleStartupRegistry() = runBlocking {
        val app = Files.createTempDirectory("droide-startup-enrollment-race-").toFile()
        try {
            val defaultRoot = File(app, "projects/default").apply { mkdirs() }
            val targetRoot = File(app, "projects/p-peer").apply { mkdirs() }
            File(targetRoot, "work.txt").writeText("peer retained work")
            val targetKey = key(targetRoot)
            val target = DroideProject("p-peer", "Peer", targetRoot.path)
            val disk = FileProjectStore(app)
            disk.save(listOf(DroideProject("default", "Default", defaultRoot.path), target))
            disk.saveActiveId("default")
            assertFalse(File(app, ".droide/workspace-relocation.lock").exists())
            var peerCompleted = false
            val recents = object : ProjectRecentsStore {
                override suspend fun load(): List<String>? = null
                override suspend fun save(ids: List<String>) {
                    val javaCommand = File(System.getProperty("java.home"), "bin/java").path
                    val process = ProcessBuilder(javaCommand,
                        "-Djava.library.path=" + System.getProperty("java.library.path"),
                        "-cp", System.getProperty("java.class.path"),
                        PeerDeletion::class.java.name, app.path, target.id).redirectErrorStream(true).start()
                    check(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) { "Peer process did not finish" }
                    val output = process.inputStream.bufferedReader().readText()
                    assertEquals(output, 0, process.exitValue())
                    peerCompleted = true
                }
            }
            val starting = ProjectManager(app, recentsStore = recents)
            val outcome = runCatching { starting.init() }
            assertTrue(peerCompleted)
            assertFalse(disk.load().any { it.id == target.id })
            val quarantine = File(app, "projects/.delete-" + target.id)
            assertEquals(targetKey, key(quarantine))
            assertEquals("peer retained work", File(quarantine, "work.txt").readText())
            assertTrue("A newly enrolled peer committed removal; stale startup must not publish", outcome.isFailure)
            assertTrue(starting.projects.value.isEmpty())
            assertNull(starting.activeId.value)
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    object PeerDeletion {
        @JvmStatic fun main(args: Array<String>) = runBlocking {
            val manager = ProjectManager(File(args[0]))
            manager.init()
            manager.deleteWorkspace(args[1])
            Unit
        }
    }

    companion object {
        private fun key(file: File): Any = checkNotNull(Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey())
    }
}
