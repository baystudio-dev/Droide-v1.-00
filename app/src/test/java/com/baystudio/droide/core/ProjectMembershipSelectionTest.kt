package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProjectMembershipSelectionTest {
    @Test fun staleManagerCannotRemoveOtherManagersActiveWorkspace() = runBlocking {
        val app = Files.createTempDirectory("membership-selection-").toFile()
        try {
            val first = ProjectManager(app).apply { init() }
            val project = first.add("Shared")
            File(project.rootPath, "payload").writeText("preserve")
            val second = ProjectManager(app).apply { init(); switch(project.id) }
            val disk = FileProjectStore(app)
            val before = disk.loadCatalog()
            assertEquals("default", first.activeId.value)
            assertEquals(project.id, second.activeId.value)
            val failure = runCatching { first.removeWorkspace(project.id) }.exceptionOrNull()
            assertNotNull("A stale manager must not remove the persisted active workspace", failure)
            assertEquals(before, disk.loadCatalog())
            assertEquals(project.id, disk.loadActiveId())
            assertFalse(first.projects.value.single { it.id == project.id }.workspaceRemoved)
            assertEquals("preserve", File(project.rootPath, "payload").readText())
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    @Test fun staleManagerCannotSelectWorkspaceRemovedFromCurrentCatalog() = runBlocking {
        val app = Files.createTempDirectory("membership-selection-after-remove-").toFile()
        try {
            val first = ProjectManager(app).apply { init() }
            val project = first.add("Shared")
            File(project.rootPath, "payload").writeText("preserve")
            val second = ProjectManager(app).apply { init() }
            first.removeWorkspace(project.id)
            val disk = FileProjectStore(app)
            val before = disk.loadCatalog()
            assertTrue(before.projects.single { it.id == project.id }.workspaceRemoved)
            assertNotNull("Selection must check current membership, not just cached records", runCatching { second.switch(project.id) }.exceptionOrNull())
            assertEquals("default", disk.loadActiveId())
            assertEquals(before, disk.loadCatalog())
            assertEquals("default", second.activeId.value)
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    private class Store(val disk: FileProjectStore) : ProjectStore by disk {
        var beforeLoad: (suspend () -> Unit)? = null
        var beforeActiveRead: (suspend () -> Unit)? = null
        var activeWrites = 0
        override suspend fun load(): List<DroideProject> { beforeLoad?.invoke(); return disk.load() }
        override suspend fun loadActiveId(): String? { beforeActiveRead?.invoke(); return disk.loadActiveId() }
        override suspend fun saveActiveId(id: String) { activeWrites++; disk.saveActiveId(id) }
    }
    private fun fixture(action: suspend (File, Store, ProjectManager, DroideProject) -> Unit) = runBlocking {
        val app = Files.createTempDirectory("membership-owned-").toFile()
        try {
            val store = Store(FileProjectStore(app))
            val manager = ProjectManager(app, store).apply { init() }
            val project = manager.add("Owned")
            File(project.rootPath, "payload").writeText("preserve")
            action(app, store, manager, project)
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    @Test fun staleNonversionedCatalogRefusesBeforeCallbacksOrActiveWrite() = fixture { app, store, manager, project ->
        val other = ProjectManager(app).apply { init() }
        other.removeWorkspace(project.id)
        val before = store.disk.loadCatalog(); val activeWrites = store.activeWrites
        var validated = false; var published = false
        assertTrue(runCatching { manager.selectAndPublish(project.id, { validated = true }, { published = true }) }.isFailure)
        assertFalse(validated); assertFalse(published); assertEquals(activeWrites, store.activeWrites)
        assertFalse(manager.projects.value.single { it.id == project.id }.workspaceRemoved)
        assertEquals(before, store.disk.loadCatalog()); assertEquals("default", store.disk.loadActiveId())
    }

    @Test fun changedRegisteredRootRefusesWithoutRefreshingCachedOwner() = fixture { app, store, manager, project ->
        val otherRoot = File(app, "projects/other-root").apply { check(mkdir()) }
        store.disk.save(store.disk.load().map { if (it.id == project.id) it.copy(rootPath = otherRoot.path) else it })
        val before = store.disk.loadCatalog(); val activeWrites = store.activeWrites
        assertTrue(runCatching { manager.switch(project.id) }.isFailure)
        assertEquals(project.rootPath, manager.projects.value.single { it.id == project.id }.rootPath)
        assertEquals(activeWrites, store.activeWrites); assertEquals(before, store.disk.loadCatalog())
        assertEquals("preserve", File(project.rootPath, "payload").readText())
    }

    @Test fun catalogReadFailuresPropagateBeforeSelectionSideEffects() = fixture { _, store, manager, project ->
        for (failure in listOf(java.io.IOException("read"), CancellationException("cancelled"), AssertionError("fatal"))) {
            val writes = store.activeWrites; var validated = false; var published = false
            store.beforeLoad = { throw failure }
            assertSame(failure, runCatching { manager.selectAndPublish(project.id, { validated = true }, { published = true }) }.exceptionOrNull())
            assertEquals(writes, store.activeWrites); assertFalse(validated); assertFalse(published)
            assertEquals("default", store.disk.loadActiveId()); assertEquals("default", manager.activeId.value)
        }
        store.beforeLoad = null
        manager.switch(project.id)
        assertEquals(project.id, manager.activeId.value)
    }

    @Test fun activeReadFailuresLeaveRemovalUnpublished() = fixture { _, store, manager, project ->
        val before = store.disk.loadCatalog()
        for (failure in listOf(java.io.IOException("read"), CancellationException("cancelled"), AssertionError("fatal"))) {
            store.beforeActiveRead = { throw failure }
            assertSame(failure, runCatching { manager.removeWorkspace(project.id) }.exceptionOrNull())
            assertEquals(before, store.disk.loadCatalog())
            assertFalse(manager.projects.value.single { it.id == project.id }.workspaceRemoved)
        }
        store.beforeActiveRead = null
        assertTrue(manager.removeWorkspace(project.id)!!.workspaceRemoved)
        assertEquals("preserve", File(project.rootPath, "payload").readText())
    }

    @Test fun missingPersistedSelectionDoesNotFallbackToDefaultOrRemove() = fixture { app, store, manager, project ->
        check(File(app, ".droide/active_project.txt").delete())
        val before = store.disk.loadCatalog()
        assertTrue(runCatching { manager.removeWorkspace(project.id) }.isFailure)
        assertNull(store.disk.loadActiveId()); assertEquals(before, store.disk.loadCatalog())
        assertFalse(manager.projects.value.single { it.id == project.id }.workspaceRemoved)
    }

    @Test fun otherPersistedSelectionAlsoRequiresReopenBeforeRemoval() = fixture { app, store, manager, project ->
        val otherProject = manager.add("Other")
        ProjectManager(app).apply { init(); switch(otherProject.id) }
        val before = store.disk.loadCatalog()
        assertTrue(runCatching { manager.removeWorkspace(project.id) }.isFailure)
        assertEquals(before, store.disk.loadCatalog()); assertEquals(otherProject.id, store.disk.loadActiveId())
    }

    @Test fun cancelledRemovalReadReleasesRegistryAndPreservesMembership() = fixture { _, store, manager, project ->
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            store.beforeActiveRead = { entered.complete(Unit); awaitCancellation() }
            val before = store.disk.loadCatalog()
            val attempt = launch { manager.removeWorkspace(project.id) }
            withTimeout(5_000) { entered.await() }; attempt.cancelAndJoin()
            assertEquals(before, store.disk.loadCatalog())
            store.beforeActiveRead = null
            withTimeout(5_000) { manager.removeWorkspace(project.id) }
            assertEquals("preserve", File(project.rootPath, "payload").readText())
        }
    }

    @Test fun removalThenWaitingSelectionCannotActivateRemovedRecord() = fixture { app, store, manager, project ->
        coroutineScope {
            val other = ProjectManager(app).apply { init() }
            val readEntered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
            store.beforeActiveRead = { readEntered.complete(Unit); proceed.await() }
            val removal = async { manager.removeWorkspace(project.id) }
            withTimeout(5_000) { readEntered.await() }
            val selectionStarted = CompletableDeferred<Unit>()
            val selection = async { selectionStarted.complete(Unit); runCatching { other.switch(project.id) } }
            selectionStarted.await(); yield(); assertFalse(selection.isCompleted)
            proceed.complete(Unit)
            withTimeout(5_000) { assertTrue(removal.await()!!.workspaceRemoved); assertTrue(selection.await().isFailure) }
            assertEquals("default", store.disk.loadActiveId()); assertEquals("default", other.activeId.value)
        }
    }

    @Test fun selectionThenWaitingRemovalCannotRemoveActiveRecord() = fixture { app, store, manager, project ->
        coroutineScope {
            val otherStore = Store(store.disk)
            val other = ProjectManager(app, otherStore).apply { init() }
            val readEntered = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
            otherStore.beforeLoad = { readEntered.complete(Unit); proceed.await() }
            val selection = async { other.selectAndPublish(project.id) }
            withTimeout(5_000) { readEntered.await() }
            val removalStarted = CompletableDeferred<Unit>()
            val removal = async { removalStarted.complete(Unit); runCatching { manager.removeWorkspace(project.id) } }
            removalStarted.await(); yield(); assertFalse(removal.isCompleted)
            proceed.complete(Unit)
            withTimeout(5_000) { selection.await(); assertTrue(removal.await().isFailure) }
            assertEquals(project.id, store.disk.loadActiveId())
            assertFalse(store.disk.load().single { it.id == project.id }.workspaceRemoved)
        }
    }

    @Test fun matchingSelectionAllowsInactiveRemovalAndRestoreWithoutMovingFiles() = fixture { _, store, manager, project ->
        assertTrue(manager.removeWorkspace(project.id)!!.workspaceRemoved)
        assertTrue(File(project.rootPath).isDirectory)
        assertFalse(File(File(project.rootPath).parentFile, ".delete-${project.id}").exists())
        assertFalse(manager.restoreWorkspace(project.id).workspaceRemoved)
        manager.switch(project.id)
        assertEquals(project.id, store.disk.loadActiveId())
    }

    @Test fun existingDeletionGuardStillRejectsStaleActiveOwner() = fixture { app, store, manager, project ->
        ProjectManager(app).apply { init(); switch(project.id) }
        val before = store.disk.loadCatalog()
        assertTrue(runCatching { manager.deleteWorkspace(project.id) }.isFailure)
        assertEquals(before, store.disk.loadCatalog()); assertEquals("preserve", File(project.rootPath, "payload").readText())
    }

    @Test fun versionedSelectionRejectsChangedBytesEvenWithSameParsedCatalog() = runBlocking {
        val app = Files.createTempDirectory("membership-revision-").toFile()
        try {
            val manager = ProjectManager(app).apply { init() }; val project = manager.add("Owned")
            val disk = FileProjectStore(app); val before = disk.load()
            File(app, ".droide/projects.json").appendText(" ")
            assertEquals(before, disk.load())
            var published = false
            assertTrue(runCatching { manager.selectAndPublish(project.id, publish = { published = true }) }.isFailure)
            assertFalse(published); assertEquals("default", disk.loadActiveId())
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    @Test fun selectedJournaledPendingStateStillBlocksSelection() = runBlocking {
        val app = Files.createTempDirectory("membership-journaled-").toFile()
        try {
            val root = File(app, "projects/default").apply { check(mkdirs()) }
            val initial = DroideProject("default", "Default", root.path)
            val disk = FileProjectStore(app); disk.save(listOf(initial)); disk.saveActiveId("default")
            val storage = File(app, "explicit-startup").apply { check(mkdir()) }
            val admission = ProjectCatalogAdmission(app, storage, 1_000_000)
            val enrollment = CanonicalJournalBootstrap(admission.updateSlot, 1_000_000)
                .createWithMembership(File(app, ".droide"), ProjectCatalogUpdate.AUTHORITY_ID, admission.updateMembership, EmptyCoroutineContext)
            val selection = admission.recordUpdateChoice(enrollment)
            val policy = CanonicalJournalRetention.Policy(8, 100_000_000)
            val store = JournaledProjectStore(selection, policy)
            val manager = ProjectManager(app, store).apply { init() }
            val project = manager.add("Owned")
            val current = store.loadCatalog()
            selection.guardedUpdate(policy, EmptyCoroutineContext, {}).prepare(current.projects.map { it.copy(name = "Pending") }, current.revision)
            var published = false
            assertTrue(runCatching { manager.selectAndPublish(project.id, publish = { published = true }) }.isFailure)
            assertFalse(published); assertEquals("default", disk.loadActiveId())
            assertNotNull(store.inspectRecovery().pending)
        } finally { PathSecurity.deleteTreeNoFollow(app) }
    }
}
