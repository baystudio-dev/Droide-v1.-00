package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DebugStateOrderingRegressionTest {
    private class ControlledDispatcher : CoroutineDispatcher() {
        private class Once(private val task: Runnable) : Runnable {
            private val used = AtomicBoolean()
            override fun run() { if (used.compareAndSet(false, true)) task.run() }
        }
        private val work = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { work.add(Once(block)) }
        fun takeAll(): List<Runnable> = buildList {
            while (true) add(work.poll() ?: break)
        }
        suspend fun complete(tasks: List<Runnable>, jobs: List<Job>) {
            tasks.forEach(Runnable::run)
            withTimeout(5_000) {
                while (jobs.any { !it.isCompleted }) {
                    takeAll().forEach(Runnable::run)
                    delay(1)
                }
            }
        }
    }

    private fun reversedSaveOrder(breakpoints: Boolean) = runBlocking<Unit> {
        val root = Files.createTempDirectory("droide-debug-ordering-").toFile()
        val project = File(root, "project").apply { mkdirs(); File(this, "Main.kt").writeText("fun main() = Unit") }
        val stateRoot = File(root, "state")
        val dispatcher = ControlledDispatcher()
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + dispatcher)
        val store = DebugStateStore(stateRoot, "ordering", { bytes, _ -> bytes }, { bytes, _ -> bytes })
        val debugger = DebugManager(scope, FileRepository(project), TerminalManager(scope, project), stateStore = store)
        val held = mutableListOf<Runnable>()
        try {
            debugger.restoreState()
            check(owner.children.none()) { "Fixture has unexpected startup jobs" }
            if (breakpoints) debugger.addBreakpoint("Main.kt", 1) else debugger.addWatch("temporary")
            val olderJobs = owner.children.toList()
            val olderTasks = dispatcher.takeAll().also(held::addAll)
            if (breakpoints) debugger.clearBreakpoint("Main.kt", 1) else debugger.clearWatches()
            val newerJobs = owner.children.filter { it !in olderJobs }.toList()
            val newerTasks = dispatcher.takeAll().also(held::addAll)
            dispatcher.complete(newerTasks, newerJobs)
            dispatcher.complete(olderTasks, olderJobs)
            val persisted = checkNotNull(store.load()) { "No persisted state after admitted edits" }
            if (breakpoints) {
                assertTrue("Older breakpoint snapshot replaced newer deletion", persisted.breakpoints["Main.kt"].orEmpty().isEmpty())
                assertTrue(debugger.breakpoints.value["Main.kt"].orEmpty().isEmpty())
            } else {
                assertTrue("Older watch snapshot replaced newer deletion", persisted.watches.isEmpty())
                assertTrue(debugger.watches.value.isEmpty())
            }
        } finally {
            debugger.close(); owner.cancel()
            held.forEach(Runnable::run)
            withTimeout(5_000) {
                while (!owner.isCompleted) { dispatcher.takeAll().forEach(Runnable::run); delay(1) }
            }
            root.deleteRecursively()
        }
    }

    @Test fun deletedWatchRemainsDeletedWhenOlderSaveRunsLast() = reversedSaveOrder(breakpoints = false)
    @Test fun clearedBreakpointRemainsClearedWhenOlderSaveRunsLast() = reversedSaveOrder(breakpoints = true)
}
