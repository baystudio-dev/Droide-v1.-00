package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.seconds

/** Authoring only; not executed or installed in the source tree. */

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProjectSwitchCommitBoundaryBaselineTest {
    @get:Rule val temporary = TemporaryFolder()

    private enum class Fault { NONE, CANCEL_BEFORE_WRITE, CANCEL_AFTER_WRITE, FAIL_BEFORE_WRITE }

    private class ControlledStore(root: File, private val writer: CoroutineDispatcher) : ProjectStore {
        private val delegate = FileProjectStore(root)
        var armedId: String? = null
        var fault = Fault.NONE
        var caller: Job? = null
        var armedCalls = 0
        var armedWritesCompleted = 0

        override suspend fun load() = delegate.load()
        override suspend fun save(all: List<DroideProject>) = delegate.save(all)
        override suspend fun loadActiveId() = delegate.loadActiveId()

        override suspend fun saveActiveId(id: String) = withContext(writer) {
            if (id != armedId) {
                delegate.saveActiveId(id)
                return@withContext
            }
            armedCalls++
            val selected = fault
            // One operation, one deterministic interception; never a retry/stress loop.
            armedId = null
            when (selected) {
                Fault.CANCEL_BEFORE_WRITE -> {
                    checkNotNull(caller).cancel(CancellationException("fixture: before durable write"))
                    currentCoroutineContext().ensureActive()
                    error("Cancelled child must not reach durable write")
                }
                Fault.FAIL_BEFORE_WRITE -> throw IOException("fixture: active-ID write rejected")
                else -> Unit
            }
            // This is the production atomic file write, not an in-memory stand-in.
            delegate.saveActiveId(id)
            armedWritesCompleted++
            if (selected == Fault.CANCEL_AFTER_WRITE) {
                checkNotNull(caller).cancel(CancellationException("fixture: durable write completed"))
            }
        }
    }

    private class Recents : ProjectRecentsStore {
        private var ids: List<String>? = null
        override suspend fun load() = ids
        override suspend fun save(ids: List<String>) { this.ids = ids.toList() }
    }

    private data class Fixture(val manager: ProjectManager, val store: ControlledStore, val target: DroideProject)

    private suspend fun fixture(writer: CoroutineDispatcher): Fixture {
        val store = ControlledStore(temporary.root, writer)
        val manager = ProjectManager(temporary.root, store, Recents())
        manager.init()
        val target = manager.add("Second project")
        File(target.rootPath, "keep.txt").writeText("saved source sentinel")
        assertEquals("default", manager.activeId.value)
        assertEquals("default", store.loadActiveId())
        return Fixture(manager, store, target)
    }

    private fun Fixture.arm(fault: Fault) {
        store.armedId = target.id
        store.fault = fault
    }

    private fun Fixture.requireFilesPreserved() {
        assertEquals("saved source sentinel", File(target.rootPath, "keep.txt").readText())
        assertTrue(manager.projects.value.any { it.id == target.id })
    }

    /** Expected RED on untouched : durable B survives, published selection remains A. */
    @Test fun cancellationAfterDurableWriteMustNotSplitSelectedProject() = runTest(timeout = 15.seconds) {
        val f = fixture(StandardTestDispatcher(testScheduler, "active-id-writer"))
        f.arm(Fault.CANCEL_AFTER_WRITE)
        var returnedNormally = false
        val owner = launch {
            f.store.caller = currentCoroutineContext()[Job]
            f.manager.switch(f.target.id)
            returnedNormally = true
        }
        owner.join()
        assertTrue(owner.isCancelled)
        assertFalse(returnedNormally)
        assertEquals(1, f.store.armedCalls)
        assertEquals(1, f.store.armedWritesCompleted)
        val persisted = f.store.loadActiveId()
        assertEquals("The fault must occur after a real active-ID file commit", f.target.id, persisted)
        f.requireFilesPreserved()
        assertEquals(
            "Durable selection and published selection must agree after the cancelled operation settles",
            persisted,
            f.manager.activeId.value,
        )
    }

    @Test fun successfulSwitchKeepsDurableAndPublishedSelectionTogether() = runTest(timeout = 15.seconds) {
        val f = fixture(StandardTestDispatcher(testScheduler, "active-id-writer"))
        f.arm(Fault.NONE)
        f.manager.switch(f.target.id)
        assertEquals(1, f.store.armedCalls)
        assertEquals(1, f.store.armedWritesCompleted)
        assertEquals(f.target.id, f.store.loadActiveId())
        assertEquals(f.target.id, f.manager.activeId.value)
        f.requireFilesPreserved()
    }

    @Test fun cancellationBeforeWritePreservesPreviousSelectionAndSource() = runTest(timeout = 15.seconds) {
        val f = fixture(StandardTestDispatcher(testScheduler, "active-id-writer"))
        f.arm(Fault.CANCEL_BEFORE_WRITE)
        val owner = launch {
            f.store.caller = currentCoroutineContext()[Job]
            f.manager.switch(f.target.id)
        }
        owner.join()
        assertTrue(owner.isCancelled)
        assertEquals(1, f.store.armedCalls)
        assertEquals(0, f.store.armedWritesCompleted)
        assertEquals("default", f.store.loadActiveId())
        assertEquals("default", f.manager.activeId.value)
        f.requireFilesPreserved()
    }

    @Test fun failureBeforeWritePreservesPreviousSelectionAndSource() = runTest(timeout = 15.seconds) {
        val f = fixture(StandardTestDispatcher(testScheduler, "active-id-writer"))
        f.arm(Fault.FAIL_BEFORE_WRITE)
        val failure = runCatching { f.manager.switch(f.target.id) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("fixture: active-ID write rejected", failure?.message)
        assertEquals(1, f.store.armedCalls)
        assertEquals(0, f.store.armedWritesCompleted)
        assertEquals("default", f.store.loadActiveId())
        assertEquals("default", f.manager.activeId.value)
        f.requireFilesPreserved()
    }
}
