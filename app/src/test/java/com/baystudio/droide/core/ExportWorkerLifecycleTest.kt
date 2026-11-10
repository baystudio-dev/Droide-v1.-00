package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class ExportWorkerLifecycleTest {
    private class Queued : CoroutineDispatcher() {
        val queue = java.util.ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun drain() { while(queue.isNotEmpty()) queue.removeFirst().run() }
    }
    @Test fun cancellationBeforeBodyStillFinishesExactlyOnce() {
        val dispatcher = Queued()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var entered = false
        var finished = 0
        val worker = ExportWorkerLifecycle.start(scope, dispatcher, { finished++ }) { entered = true }
        worker.cancel()
        dispatcher.drain()
        assertFalse(entered)
        assertEquals(1, finished)
        assertTrue(worker.isCompleted)
        worker.cancel(); dispatcher.drain()
        assertEquals(1, finished)
        scope.cancel()
    }
    @Test fun alreadyCancelledOwnerStillFinishesWithoutBody() {
        val dispatcher = Queued()
        val scope = CoroutineScope(Job().also { it.cancel() } + dispatcher)
        var finished = 0
        var entered = false
        val worker = ExportWorkerLifecycle.start(scope, dispatcher, { finished++ }) { entered = true }
        dispatcher.drain()
        assertTrue(worker.isCompleted)
        assertFalse(entered)
        assertEquals(1, finished)
    }
}
