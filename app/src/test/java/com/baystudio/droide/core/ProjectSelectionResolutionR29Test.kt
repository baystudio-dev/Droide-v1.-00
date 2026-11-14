package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProjectSelectionResolutionR29Test {
    @get:Rule val temporary = TemporaryFolder()

    private class Store : ProjectStore {
        var records = emptyList<DroideProject>()
        var selected: String? = null
        val writes = mutableListOf<String>()
        var reads = 0
        var beforeWrite: (String) -> Unit = {}
        var afterWrite: (String) -> Unit = {}
        var read: (() -> String?)? = null
        override suspend fun load() = records
        override suspend fun save(all: List<DroideProject>) { records = all }
        override suspend fun loadActiveId(): String? { reads++; return read?.invoke() ?: selected }
        override suspend fun saveActiveId(id: String) {
            writes += id
            beforeWrite(id)
            selected = id
            afterWrite(id)
        }
    }
    private class Recents : ProjectRecentsStore {
        var fail = false
        var writes = 0
        override suspend fun load(): List<String>? = null
        override suspend fun save(ids: List<String>) { writes++; if (fail) throw IOException("recents rejected") }
    }
    private class Fixture(val manager: ProjectManager, val store: Store, val recents: Recents, val target: DroideProject) {
        fun assertSource() = assertEquals("source remains", File(target.rootPath, "keep.txt").readText())
    }
    private suspend fun fixture(): Fixture {
        val store = Store()
        val recents = Recents()
        val manager = ProjectManager(temporary.root, store, recents)
        manager.init()
        val target = manager.add("Second")
        File(target.rootPath, "keep.txt").writeText("source remains")
        store.writes.clear(); store.reads = 0
        return Fixture(manager, store, recents, target)
    }

    @Test fun finalAdmissionRejectionRestoresOnlyBeforePublication() = runTest(timeout = 15.seconds) {
        val f = fixture()
        var published = false
        val failure = runCatching {
            f.manager.selectAndPublish(f.target.id, validate = { error("late dirty buffer") }, publish = { published = true })
        }.exceptionOrNull()
        assertEquals("late dirty buffer", failure?.message)
        assertFalse(published)
        assertEquals(listOf(f.target.id, "default"), f.store.writes)
        assertEquals(0, f.store.reads)
        assertEquals("default", f.store.selected)
        assertEquals("default", f.manager.activeId.value)
        assertFalse(f.manager.selectionIsUncertain)
        f.assertSource()
    }

    @Test fun cancelledCommittedWriteStillUsesFinalAdmissionBeforePublishing() = runTest(timeout = 15.seconds) {
        val f = fixture()
        var published = false
        var rejection: Throwable? = null
        val owner = launch {
            val caller = checkNotNull(currentCoroutineContext()[Job])
            f.store.afterWrite = { id -> if (id == f.target.id) {
                caller.cancel(CancellationException("after commit"))
                throw CancellationException("ambiguous return")
            } }
            rejection = runCatching {
                f.manager.selectAndPublish(f.target.id, validate = { error("late dirty buffer") }, publish = { published = true })
            }.exceptionOrNull()
        }
        // Admission refusal is an ordinary exception after caller cancellation; observe it
        // through the child, without allowing it to fail the test's parent scope.
        owner.join()
        assertTrue(owner.isCancelled)
        assertEquals("late dirty buffer", rejection?.message)
        assertFalse(published)
        assertEquals(listOf(f.target.id, "default"), f.store.writes)
        assertEquals(1, f.store.reads)
        assertEquals("default", f.store.selected)
        assertEquals("default", f.manager.activeId.value)
        f.assertSource()
    }

    @Test fun failedRollbackBlocksFurtherSelectionAndDeletionWithoutRetrying() = runTest(timeout = 15.seconds) {
        val f = fixture()
        f.store.beforeWrite = { if (it == "default") throw IOException("rollback refused") }
        val failure = runCatching { f.manager.selectAndPublish(f.target.id, validate = { error("late edit") }) }.exceptionOrNull()
        assertTrue(failure is ProjectSelectionUncertainException)
        assertTrue(f.manager.selectionIsUncertain)
        assertEquals(f.target.id, f.store.selected)
        assertEquals("default", f.manager.activeId.value)
        assertEquals(listOf(f.target.id, "default"), f.store.writes)
        assertEquals(1, f.store.reads)
        // withContext stacktrace recovery may copy the propagated failure. Fetch the
        // actual latch synchronously, then require that exact original in each cause chain.
        val latched = checkNotNull(runCatching { f.manager.requireSelectionCertain() }.exceptionOrNull())
        assertOriginatingFailure(latched, failure)
        assertOriginatingFailure(latched, runCatching { f.manager.switch(f.target.id) }.exceptionOrNull())
        assertOriginatingFailure(latched, runCatching { f.manager.deleteWorkspace(f.target.id) }.exceptionOrNull())
        assertSame(latched, runCatching { f.manager.requireSelectionCertain() }.exceptionOrNull())
        assertEquals(2, f.store.writes.size)
        assertEquals(1, f.store.reads)
        f.assertSource()
    }

    @Test fun rollbackThatCommittedBeforeThrowIsResolvedOnceAsOldSelection() = runTest(timeout = 15.seconds) {
        val f = fixture()
        f.store.afterWrite = { if (it == "default") throw IOException("rollback return lost") }
        val failure = runCatching { f.manager.selectAndPublish(f.target.id, validate = { error("late edit") }) }.exceptionOrNull()
        assertEquals("late edit", failure?.message)
        assertFalse(f.manager.selectionIsUncertain)
        assertEquals("default", f.store.selected)
        assertEquals("default", f.manager.activeId.value)
        assertEquals(1, f.store.reads)
        f.store.afterWrite = {}
        f.manager.switch(f.target.id)
        assertEquals(f.target.id, f.manager.activeId.value)
        f.assertSource()
    }

    @Test fun unreadableAmbiguousWriteDoesNotInventRollbackSuccess() = runTest(timeout = 15.seconds) {
        val f = fixture()
        f.store.afterWrite = { throw IOException("write return lost") }
        f.store.read = { throw IOException("read denied") }
        val failure = runCatching { f.manager.selectAndPublish(f.target.id) }.exceptionOrNull()
        assertTrue(failure is ProjectSelectionUncertainException)
        assertEquals(1, f.store.reads)
        assertEquals(listOf(f.target.id), f.store.writes)
        assertEquals("default", f.manager.activeId.value)
        assertEquals(f.target.id, f.store.selected)
        f.assertSource()
    }

    @Test fun unexpectedReadbackIdentityBlocksDestructiveRemoval() = runTest(timeout = 15.seconds) {
        val f = fixture()
        f.store.afterWrite = { throw IOException("write return lost") }
        f.store.read = { "unrelated" }
        assertTrue(runCatching { f.manager.selectAndPublish(f.target.id) }.exceptionOrNull() is ProjectSelectionUncertainException)
        assertTrue(runCatching { f.manager.deleteWorkspace(f.target.id) }.exceptionOrNull() is ProjectSelectionUncertainException)
        assertEquals(1, f.store.reads)
        assertEquals(1, f.store.writes.size)
        f.assertSource()
    }

    @Test fun successfulReadbackPublishesOnceAndReturnsStorageWarning() = runTest(timeout = 15.seconds) {
        val f = fixture()
        val storageFailure = IOException("write return lost")
        f.store.afterWrite = { throw storageFailure }
        var publications = 0
        val warning = f.manager.selectAndPublish(f.target.id, publish = {
            publications++
            assertEquals(f.target.id, f.manager.activeId.value)
            assertEquals(f.target.id, f.store.selected)
        })
        assertSame(storageFailure, warning)
        assertEquals(1, publications)
        assertEquals(1, f.store.reads)
        assertEquals(listOf(f.target.id), f.store.writes)
        f.assertSource()
    }

    @Test fun recentsFailureAfterCommitNeverRollsSelectionBack() = runTest(timeout = 15.seconds) {
        val f = fixture()
        f.recents.fail = true
        f.manager.switch(f.target.id)
        assertEquals(f.target.id, f.store.selected)
        assertEquals(f.target.id, f.manager.activeId.value)
        assertEquals(listOf(f.target.id), f.store.writes)
        assertEquals(0, f.store.reads)
        f.assertSource()
    }

    @Test fun fatalFinalAdmissionRestoresOldIdAndRethrowsTheExactFatalCause() = runTest(timeout = 15.seconds) {
        val f = fixture()
        val fatal = LinkageError("fixture capture linkage failure")
        var published = false
        val failure = runCatching {
            f.manager.selectAndPublish(f.target.id, validate = { throw fatal }, publish = { published = true })
        }.exceptionOrNull()
        assertSame(fatal, failure)
        assertFalse(published)
        assertEquals("default", f.store.selected)
        assertEquals("default", f.manager.activeId.value)
        assertFalse(f.manager.selectionIsUncertain)
        assertEquals(listOf(f.target.id, "default"), f.store.writes)
        f.assertSource()
    }

    @Test fun fatalRollbackLatchesUncertaintyBeforeRethrowingFatalAdmission() = runTest(timeout = 15.seconds) {
        val f = fixture()
        val admission = LinkageError("fixture capture linkage failure")
        val restoration = LinkageError("fixture rollback linkage failure")
        f.store.beforeWrite = { if (it == "default") throw restoration }
        val failure = runCatching {
            f.manager.selectAndPublish(f.target.id, validate = { throw admission })
        }.exceptionOrNull()
        assertSame(admission, failure)
        assertTrue(f.manager.selectionIsUncertain)
        assertEquals(f.target.id, f.store.selected)
        assertEquals("default", f.manager.activeId.value)
        assertEquals(1, f.store.reads)
        assertTrue(runCatching { f.manager.deleteWorkspace(f.target.id) }.exceptionOrNull() is ProjectSelectionUncertainException)
        f.assertSource()
    }

    @Test fun fatalWriteReturnNeverPublishesEvenWhenReadbackConfirmsTarget() = runTest(timeout = 15.seconds) {
        val f = fixture()
        val fatal = LinkageError("fixture durable write return failure")
        f.store.afterWrite = { if (it == f.target.id) throw fatal }
        var published = false
        assertSame(fatal, runCatching {
            f.manager.selectAndPublish(f.target.id, publish = { published = true })
        }.exceptionOrNull())
        assertFalse(published)
        assertFalse(f.manager.selectionIsUncertain)
        assertEquals("default", f.store.selected)
        assertEquals("default", f.manager.activeId.value)
        assertEquals(1, f.store.reads)
        assertEquals(listOf(f.target.id, "default"), f.store.writes)
        f.assertSource()
    }

    @Test fun fatalRollbackAfterOrdinaryRefusalIsNotSwallowedByUncertaintyReporting() = runTest(timeout = 15.seconds) {
        val f = fixture()
        val fatal = LinkageError("fixture rollback linkage failure")
        f.store.beforeWrite = { if (it == "default") throw fatal }
        assertOriginatingFailure(fatal, runCatching {
            f.manager.selectAndPublish(f.target.id, validate = { error("late edit") })
        }.exceptionOrNull())
        assertTrue(f.manager.selectionIsUncertain)
        assertEquals(f.target.id, f.store.selected)
        assertEquals("default", f.manager.activeId.value)
        assertTrue(runCatching { f.manager.deleteWorkspace(f.target.id) }.exceptionOrNull() is ProjectSelectionUncertainException)
        f.assertSource()
    }
}
