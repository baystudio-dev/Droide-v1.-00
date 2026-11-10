package com.baystudio.droide.core

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class WorkspaceTaskControllerTest {
    private val root = Files.createTempDirectory("owned-workspace-task-").toFile()
    private fun task(label: String = "Build") = IdeTask(label, listOf("true"), root)

    @Test fun admissionRejectsDuplicateStartAndResultSurvivesObservers() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = WorkspaceTaskController(parent)
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val manager = TaskManager(root, taskExecutor = { task, timeout ->
            assertEquals("User tasks remain active until exit or explicit Stop", 0L, timeout)
            entered.complete(Unit); finish.await(); TaskResult(task, 0, "artifact ready", false, 1)
        })
        try {
            assertTrue(controller.start(listOf(task()), manager, {}))
            withTimeout(5_000) { entered.await() }
            assertFalse(controller.start(listOf(task("duplicate")), manager, {}))
            val observer = launch { controller.state.first { !it.busy } }
            observer.cancelAndJoin()
            assertTrue(controller.state.value.busy)
            finish.complete(Unit)
            val done = withTimeout(5_000) { controller.state.first { !it.busy } }
            assertEquals("[TASK Build]\nartifact ready\n", done.lastResult?.output)
            assertNull(done.error)
        } finally { controller.closeAndJoin(); parent.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun stopKeepsAdmissionAndForegroundLeaseUntilCleanupFinishes() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val leases = AtomicInteger()
        val controller = WorkspaceTaskController(parent) { _, _ ->
            leases.incrementAndGet(); AutoCloseable { leases.decrementAndGet() }
        }
        val entered = CompletableDeferred<Unit>(); val cleanup = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val manager = TaskManager(root, taskExecutor = { _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.complete(Unit); release.await() } }
        })
        try {
            assertTrue(controller.start(listOf(task()), manager, {}))
            withTimeout(5_000) { entered.await() }
            assertTrue(controller.stop()); assertFalse(controller.stop())
            withTimeout(5_000) { cleanup.await() }
            assertTrue(controller.state.value.busy); assertTrue(controller.state.value.stopping)
            assertEquals(1, leases.get())
            assertFalse(controller.start(listOf(task("other")), manager, {}))
            release.complete(Unit)
            val done = withTimeout(5_000) { controller.state.first { !it.busy } }
            assertEquals(0, leases.get()); assertEquals("Tasks stopped", done.error)
        } finally { release.complete(Unit); controller.closeAndJoin(); parent.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun failedSaveDoesNotStartProcessAndRetryRemainsPossible() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = WorkspaceTaskController(parent)
        val executions = AtomicInteger()
        val manager = TaskManager(root, taskExecutor = { task, _ ->
            executions.incrementAndGet(); TaskResult(task, 0, "ok", false, 1)
        })
        try {
            assertTrue(controller.start(listOf(task()), manager) { error("Save conflict") })
            val failed = withTimeout(5_000) { controller.state.first { !it.busy } }
            assertTrue(failed.error?.contains("Save conflict") == true); assertEquals(0, executions.get())
            assertTrue(controller.start(listOf(task()), manager, {}))
            withTimeout(5_000) { controller.state.first { !it.busy } }
            assertEquals(1, executions.get()); assertEquals("[TASK Build]\nok\n", controller.state.value.lastResult?.output)
        } finally { controller.closeAndJoin(); parent.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun deniedForegroundLeaseDoesNotStartProcess() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = WorkspaceTaskController(parent) { _, _ -> error("Foreground denied") }
        val executions = AtomicInteger()
        val manager = TaskManager(root, taskExecutor = { task, _ -> executions.incrementAndGet(); TaskResult(task, 0, "", false, 1) })
        try {
            assertTrue(controller.start(listOf(task()), manager, {}))
            val failed = withTimeout(5_000) { controller.state.first { !it.busy } }
            assertTrue(failed.error?.contains("Foreground denied") == true); assertEquals(0, executions.get())
        } finally { controller.closeAndJoin(); parent.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun exportLeasePrecedesPreparationAndIsReleasedAfterPreparationFailure() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = WorkspaceTaskController(parent) { _, _ ->
            ExportWriterAdmission.beginWriter(root, "Project task test")
        }
        val preparations = AtomicInteger()
        val executions = AtomicInteger()
        val manager = TaskManager(root, taskExecutor = { task, _ ->
            executions.incrementAndGet(); TaskResult(task, 0, "", false, 1)
        })
        try {
            ExportWriterAdmission.beginCapture(root).use {
                controller.start(listOf(task()), manager) { preparations.incrementAndGet() }
                val refused = withTimeout(5_000) { controller.state.first { !it.busy } }
                assertTrue(refused.error.orEmpty().contains("Export is capturing"))
                assertEquals(0, preparations.get())
                assertEquals(0, executions.get())
            }
            controller.start(listOf(task()), manager) {
                assertThrows(IllegalStateException::class.java) { ExportWriterAdmission.beginCapture(root) }
                error("Preparation failed")
            }
            withTimeout(5_000) { controller.state.first { !it.busy } }
            ExportWriterAdmission.beginCapture(root).close()
            assertEquals(0, executions.get())
        } finally { controller.closeAndJoin(); parent.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun runAllPreservesExitEvidenceAndBoundsRetainedResults() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = WorkspaceTaskController(parent)
        val manager = TaskManager(root, taskExecutor = { task, _ ->
            TaskResult(task, if (task.label == "task-99") 2 else 0, "x".repeat(81_000), task.label == "task-98", 1)
        })
        try {
            assertTrue(controller.start((0..99).map { task("task-$it") }, manager, {}))
            val done = withTimeout(10_000) { controller.state.first { !it.busy } }
            assertTrue(done.results.size <= WorkspaceTaskController.MAX_RESULTS)
            assertTrue(done.results.values.sumOf { it.output.length } <= WorkspaceTaskController.MAX_TOTAL_CHARS)
            assertEquals(2, done.results["task-99"]?.exitCode)
            assertTrue(done.results["task-98"]?.timedOut == true)
            assertTrue(done.results.values.all { it.output.length <= WorkspaceTaskController.MAX_RESULT_CHARS })
            assertTrue(done.lastResult?.output?.contains("x".repeat(10_000)) == true)
        } finally { controller.closeAndJoin(); parent.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun workspaceClosureCancelsTasksAndWaitsForProcessCleanup() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = WorkspaceTaskController(parent)
        val entered = CompletableDeferred<Unit>(); val cleaned = CompletableDeferred<Unit>()
        val manager = TaskManager(root, taskExecutor = { _, _ ->
            entered.complete(Unit); try { awaitCancellation() } finally { cleaned.complete(Unit) }
        })
        try {
            assertTrue(controller.start(listOf(task()), manager, {}))
            withTimeout(5_000) { entered.await(); controller.closeAndJoin() }
            assertTrue(cleaned.isCompleted); assertFalse(controller.state.value.busy)
            assertFalse(controller.start(listOf(task()), manager, {}))
            assertTrue(parent.isActive)
        } finally { controller.closeAndJoin(); parent.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }
}
