package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskDeadlineBaselineTest {
    @get:Rule val temp = TemporaryFolder()

    private fun graph() {
        File(temp.root, ".droide/tasks.json").apply {
            parentFile.mkdirs()
            writeText("""{"tasks":[
              {"label":"prepare","command":["r39-fake-prepare"]},
              {"label":"build","command":["r39-fake-build"],"dependsOn":"prepare"},
              {"label":"launch","command":["r39-fake-launch"],"dependsOn":"build"}
            ]}""")
        }
    }

    @Test(timeout = 10_000) fun explicitZeroReachesEveryDependencyWithOutputStillBounded() = runBlocking {
        graph()
        val calls = mutableListOf<Pair<String, Long>>()
        val manager = TaskManager(temp.root, taskExecutor = { task, timeout ->
            calls += task.label to timeout
            TaskResult(task, 0, "x".repeat(81_000), false, 0)
        })
        val result = manager.runNamed("launch", timeoutMs = 0L)
        assertEquals(listOf("prepare" to 0L, "build" to 0L, "launch" to 0L), calls)
        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertEquals(80_000, result.output.length)
    }

    @Test(timeout = 10_000) fun explicitPositiveGraphBudgetExpiresBeforeTheNextDependency() = runBlocking {
        graph()
        val calls = mutableListOf<Pair<String, Long>>()
        val manager = TaskManager(temp.root, taskExecutor = { task, timeout ->
            calls += task.label to timeout
            // Only a coroutine timer: no command, subprocess, shell, or guest runs. TaskManager uses
            // System.nanoTime, so cross its real one-second graph budget.
            delay(1_100)
            TaskResult(task, 0, "synthetic first dependency completed", false, 1_100)
        })
        val result = manager.runNamed("launch", timeoutMs = 1_000L)
        assertEquals(listOf("prepare"), calls.map { it.first })
        assertTrue(calls.single().second in 1L..1_000L)
        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
        assertTrue(result.output.contains("[TASK TIMEOUT 1000ms before build]"))
    }

    @Test(timeout = 10_000) fun zeroGraphCancellationJoinsCleanupAndPreventsDebugLaunch() = runBlocking {
        graph()
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<Pair<String, Long>>()
        var cleaned = false
        var launched = false
        val manager = TaskManager(temp.root, taskExecutor = { task, timeout ->
            calls += task.label to timeout
            entered.complete(Unit)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) {
                    cleaning.complete(Unit)
                    withTimeout(2_000) { release.await() }
                    cleaned = true
                }
            }
        })
        val job = async {
            ProjectDebugTaskGate.start("launch",
                execute = { manager.runNamed(it, timeoutMs = 0L) },
                launch = { launched = true })
        }
        try {
            withTimeout(2_000) { entered.await() }
            job.cancel(CancellationException("Explicit test Stop"))
            withTimeout(2_000) { cleaning.await() }
            assertFalse(job.isCompleted)
            assertFalse(launched)
            release.complete(Unit)
            withTimeout(2_000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue(cleaned)
            assertFalse(launched)
            assertEquals(listOf("prepare" to 0L), calls)
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) { withTimeout(2_000) { job.cancelAndJoin() } }
        }
    }
}
