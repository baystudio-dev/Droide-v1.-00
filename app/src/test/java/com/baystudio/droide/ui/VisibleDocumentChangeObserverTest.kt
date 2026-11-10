package com.baystudio.droide.ui

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VisibleDocumentChangeObserverTest {
    private class Watches : WorkspaceDocumentWatchFactory {
        data class Binding(val path: String, val emit: (Boolean) -> Unit, var closed: Boolean = false)
        val bindings = mutableListOf<Binding>()
        var active = 0
        var peak = 0
        var fail = false
        override fun open(path: String, invalidate: (Boolean) -> Unit): AutoCloseable {
            if (fail) error("Observer unavailable")
            val binding = Binding(path, invalidate)
            bindings += binding
            active++
            peak = maxOf(peak, active)
            return AutoCloseable {
                check(!binding.closed)
                binding.closed = true
                active--
            }
        }
    }

    @Test fun idleDoesNoWorkAndABurstReconcilesOnlyOneCurrentDocument() = runTest {
        val watches = Watches()
        val paths = mutableListOf<String>()
        val owner = VisibleDocumentChangeObserver(this, watches, paths::add, StandardTestDispatcher(testScheduler))
        owner.update("active.txt")
        runCurrent()
        advanceTimeBy(60_000)
        assertTrue(paths.isEmpty())
        repeat(1_000) { watches.bindings.single().emit(false) }
        runCurrent()
        advanceTimeBy(149)
        assertTrue(paths.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("active.txt"), paths)
        assertEquals(1, watches.bindings.size)
        assertEquals(1, watches.peak)
        owner.close(); runCurrent()
        assertEquals(0, watches.active)
    }

    @Test fun obsoleteCallbacksAndHiddenWorkspaceCannotRefreshAnotherDocument() = runTest {
        val watches = Watches()
        val paths = mutableListOf<String>()
        val owner = VisibleDocumentChangeObserver(this, watches, paths::add, StandardTestDispatcher(testScheduler))
        owner.update("old.txt"); runCurrent()
        val old = watches.bindings.single()
        owner.update(null)
        owner.update("intermediate.txt")
        owner.update("current.txt")
        runCurrent()
        assertTrue(old.closed)
        assertEquals(listOf("old.txt", "current.txt"), watches.bindings.map { it.path })
        old.emit(true)
        watches.bindings.last().emit(false)
        advanceUntilIdle()
        assertEquals(listOf("current.txt"), paths)
        owner.update(null); runCurrent()
        watches.bindings.last().emit(false)
        advanceUntilIdle()
        assertEquals(listOf("current.txt"), paths)
        assertEquals(0, watches.active)
        assertEquals(1, watches.peak)
        owner.close(); runCurrent()
    }

    @Test fun structuralChangeRebindsOnlyAfterClosingOldWatchAndCloseDropsPendingEvent() = runTest {
        val watches = Watches()
        val paths = mutableListOf<String>()
        val owner = VisibleDocumentChangeObserver(this, watches, paths::add, StandardTestDispatcher(testScheduler))
        owner.update("missing/active.txt"); runCurrent()
        val old = watches.bindings.single()
        repeat(10) { old.emit(true) }
        advanceUntilIdle()
        assertTrue(old.closed)
        assertEquals(2, watches.bindings.size)
        assertEquals(1, watches.active)
        assertEquals(1, watches.peak)
        assertEquals(listOf("missing/active.txt"), paths)
        old.emit(true)
        watches.bindings.last().emit(false)
        runCurrent()
        owner.close()
        advanceUntilIdle()
        assertEquals(listOf("missing/active.txt"), paths)
        assertEquals(0, watches.active)
        owner.update("after-close.txt")
        advanceUntilIdle()
        assertEquals(2, watches.bindings.size)
    }

    @Test fun failedObserverDoesNotLoopAndAnotherVisibilityBindingCanRecover() = runTest {
        val watches = Watches().apply { fail = true }
        val owner = VisibleDocumentChangeObserver(this, watches, { error("No event expected") },
            StandardTestDispatcher(testScheduler))
        owner.update("active.txt"); advanceUntilIdle()
        assertEquals(0, watches.active)
        watches.fail = false
        owner.update(null); runCurrent()
        owner.update("active.txt"); runCurrent()
        assertEquals(1, watches.active)
        owner.close(); runCurrent()
        assertEquals(0, watches.active)
    }

    @Test fun cancellationBeforeOpenReturnsStillClosesTheCreatedNativeHandle() = runTest {
        lateinit var owner: VisibleDocumentChangeObserver
        var opens = 0
        var closes = 0
        val factory = WorkspaceDocumentWatchFactory { _, _ ->
            opens++
            owner.close()
            AutoCloseable { closes++ }
        }
        owner = VisibleDocumentChangeObserver(this, factory, { error("No event expected") },
            StandardTestDispatcher(testScheduler))
        owner.update("active.txt")
        advanceUntilIdle()
        assertEquals(1, opens)
        assertEquals(1, closes)
    }
}
