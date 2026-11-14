package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Independent host-only owner tests. Unsafe bypasses Android construction, not owner code. */
class BuildRunDebugExitDispatchTest {
    private class Fixture(
        val gate: BuildRunDebugOperationGate<BuildRunDebugCoordinator.Operation> = BuildRunDebugOperationGate(),
    ) {
        val root = Files.createTempDirectory("f156-independent-").toFile()
        val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> uncaught.add(error) })
        val tasks = WorkspaceTaskController(scope) { _, _ -> ExportWriterAdmission.beginWriter(root, "independent project task") }
        val coordinator = construct(gate, tasks, scope)
        suspend fun finish() {
            withTimeout(5_000) { tasks.closeAndJoin(); scope.coroutineContext[Job]!!.cancelAndJoin() }
            assertTrue("No owner failure may escape to its workspace parent: $uncaught", uncaught.isEmpty())
            assertTrue("No retained writer lease", ExportWriterAdmission.snapshotBlockers(root).isEmpty())
            assertTrue("No remaining workspace coroutine", scope.coroutineContext[Job]!!.children.none())
            // Keep the empty fixture directory as evidence; never delete user/source data.
        }
    }

    @Test(timeout = 20_000) fun realChildStopsBeforeBuildReceiptAndBothCleanupReceiptsRemainRequired() = runBlocking {
        origins()
        val f = Fixture()
        val buildEntered = CompletableDeferred<Unit>()
        val buildCleanup = CompletableDeferred<Unit>()
        val releaseBuild = CompletableDeferred<Unit>()
        val processEntered = CompletableDeferred<Unit>()
        val taskCleanup = CompletableDeferred<Unit>()
        val releaseTask = CompletableDeferred<Unit>()
        val pid = AtomicLong()
        val build = f.scope.launch {
            f.gate.run(BuildRunDebugCoordinator.Operation.BUILD_DEBUG) {
                ExportWriterAdmission.withSuspendingWriter(f.root, "independent build") {
                    buildEntered.complete(Unit)
                    try { awaitCancellation() }
                    finally { withContext(NonCancellable) { buildCleanup.complete(Unit); releaseBuild.await() } }
                }
            }
        }
        val task = IdeTask("Exact child", listOf("/bin/sh", "-c", "echo \$\$; exec sleep 30"), f.root)
        val manager = TaskManager(f.root, taskExecutor = { selected, timeout ->
            try {
                val result = LocalProcessSupervisor.capture(selected.argv, f.root, timeoutMs = timeout,
                    onOutput = { text -> text.trim().toLongOrNull()?.let { pid.set(it); processEntered.complete(Unit) } })
                TaskResult(selected, result.exitCode, result.output, result.timedOut, 0)
            } finally { withContext(NonCancellable) { taskCleanup.complete(Unit); releaseTask.await() } }
        })
        var exit: Deferred<Result<Unit>>? = null
        try {
            buildEntered.await()
            assertTrue(f.tasks.start(listOf(task), manager, {}))
            withTimeout(3_000) { processEntered.await() }
            exit = f.scope.async { runCatching { f.coordinator.stopAndJoinForExit() } }
            withTimeout(3_000) { buildCleanup.await() }
            val independentlyStopped = withTimeoutOrNull(1_500) { taskCleanup.await(); true } ?: false
            println("EXACT_CHILD taskCleanup=$independentlyStopped childAlive=${alive(pid.get())} buildHeld=${!releaseBuild.isCompleted}")
            assertTrue("Project task stop must not wait behind build cleanup", independentlyStopped)
            assertFalse("Exact owned child must be dead before its cleanup receipt is released", alive(pid.get()))
            assertTrue("Project task remains busy through cleanup", f.tasks.state.value.busy)
            assertEquals(2, ExportWriterAdmission.snapshotBlockers(f.root).size)
            assertFalse("Exit cannot certify either held owner", exit.isCompleted)
            releaseBuild.complete(Unit)
            withTimeout(3_000) { build.join() }
            assertFalse("A completed build cannot certify the held task receipt", exit.isCompleted)
            assertFalse("Closed tasks cannot accept replacement work", f.tasks.start(listOf(task), manager, {}))
            releaseTask.complete(Unit)
            withTimeout(3_000) { exit.await().getOrThrow() }
            assertFalse(f.tasks.state.value.busy)
            assertTrue(ExportWriterAdmission.snapshotBlockers(f.root).isEmpty())
        } finally {
            releaseBuild.complete(Unit); releaseTask.complete(Unit)
            withTimeout(5_000) { exit?.await(); build.join() }
            f.finish()
            assertFalse("No exact child leak", alive(pid.get()))
        }
    }

    @Test(timeout = 15_000) fun timedOutObserverCanRetryBothHeldOwnersWithoutRepeatingCleanup() = runBlocking {
        val f = Fixture()
        val buildEntered = CompletableDeferred<Unit>(); val buildCleanup = CompletableDeferred<Unit>()
        val taskEntered = CompletableDeferred<Unit>(); val taskCleanup = CompletableDeferred<Unit>()
        val releaseBuild = CompletableDeferred<Unit>(); val releaseTask = CompletableDeferred<Unit>()
        val builds = AtomicInteger(); val tasks = AtomicInteger()
        val build = f.scope.launch {
            f.gate.run(BuildRunDebugCoordinator.Operation.BUILD_DEBUG) {
                buildEntered.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { builds.incrementAndGet(); buildCleanup.complete(Unit); releaseBuild.await() } }
            }
        }
        val task = IdeTask("Held task", listOf("unused"), f.root)
        val manager = TaskManager(f.root, taskExecutor = { _, _ ->
            taskEntered.complete(Unit)
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { tasks.incrementAndGet(); taskCleanup.complete(Unit); releaseTask.await() } }
        })
        var retry: Deferred<Result<Unit>>? = null
        try {
            buildEntered.await(); assertTrue(f.tasks.start(listOf(task), manager, {})); taskEntered.await()
            val timed = withTimeoutOrNull(500) { f.coordinator.stopAndJoinForExit(); "incorrect early success" }
            assertNull("Timeout only abandons this observer", timed)
            assertTrue("Build stop must have been dispatched", buildCleanup.isCompleted)
            assertTrue("Task stop must already be dispatched before timeout", taskCleanup.isCompleted)
            assertFalse(build.isCompleted); assertTrue(f.tasks.state.value.busy)
            retry = f.scope.async { runCatching { f.coordinator.stopAndJoinForExit() } }
            releaseTask.complete(Unit)
            withTimeout(2_000) { f.tasks.state.first { !it.busy } }
            assertFalse("Retry retains build cleanup after task finishes", retry.isCompleted)
            releaseBuild.complete(Unit)
            withTimeout(3_000) { retry.await().getOrThrow() }
            assertEquals(1, builds.get()); assertEquals(1, tasks.get())
            f.coordinator.stopAndJoinForExit()
            println("TIMEOUT_RETRY buildCleanupCount=${builds.get()} taskCleanupCount=${tasks.get()} completed=true")
        } finally {
            releaseBuild.complete(Unit); releaseTask.complete(Unit)
            withTimeout(5_000) { retry?.await(); build.join() }
            f.finish()
        }
    }

    @Test(timeout = 15_000) fun firstDispatchFailureWaitsForOtherOwnerAndKnownUnstartedAttemptCanRetry() = runBlocking {
        val attempts = AtomicInteger()
        val expected = IllegalStateException("independent known-unstarted dispatch")
        val f = Fixture(BuildRunDebugOperationGate(cancellationThread = { action ->
            if (attempts.incrementAndGet() == 1) throw expected
            Thread(action, "f156-review-retry").apply { isDaemon = true }
        }))
        val buildEntered = CompletableDeferred<Unit>(); val buildCleanup = CompletableDeferred<Unit>()
        val taskEntered = CompletableDeferred<Unit>(); val taskCleanup = CompletableDeferred<Unit>()
        val releaseBuild = CompletableDeferred<Unit>(); val releaseTask = CompletableDeferred<Unit>()
        val build = f.scope.launch {
            f.gate.run(BuildRunDebugCoordinator.Operation.BUILD_DEBUG) {
                buildEntered.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { buildCleanup.complete(Unit); releaseBuild.await() } }
            }
        }
        val task = IdeTask("Other owner", listOf("unused"), f.root)
        val manager = TaskManager(f.root, taskExecutor = { _, _ ->
            taskEntered.complete(Unit)
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { taskCleanup.complete(Unit); releaseTask.await() } }
        })
        var first: Deferred<Result<Unit>>? = null; var retry: Deferred<Result<Unit>>? = null
        try {
            buildEntered.await(); assertTrue(f.tasks.start(listOf(task), manager, {})); taskEntered.await()
            first = f.scope.async { runCatching { f.coordinator.stopAndJoinForExit() } }
            withTimeout(2_000) { taskCleanup.await() }
            assertTrue("Failed build dispatch leaves its owner alive", build.isActive)
            assertFalse("First failure cannot cancel/skip the other held cleanup", first.isCompleted)
            releaseTask.complete(Unit)
            assertSame(expected, withTimeout(2_000) { first.await().exceptionOrNull() })
            assertTrue("Failure is not an Exit completion receipt", build.isActive)
            retry = f.scope.async { runCatching { f.coordinator.stopAndJoinForExit() } }
            withTimeout(2_000) { buildCleanup.await() }
            assertFalse(retry.isCompleted)
            releaseBuild.complete(Unit)
            withTimeout(2_000) { retry.await().getOrThrow() }
            assertEquals("Only the definitely unstarted cancellation is retried", 2, attempts.get())
            assertTrue(currentCoroutineContext().isActive)
        } finally {
            releaseBuild.complete(Unit); releaseTask.complete(Unit)
            withTimeout(5_000) { first?.await(); retry?.await(); f.gate.closeAndJoin(); build.join() }
            f.finish()
        }
    }

    @Test(timeout = 10_000) fun independentOwnerFailuresAreAggregatedInOriginalOwnerOrder() = runBlocking {
        val attempts = AtomicInteger()
        val buildFailure = IllegalStateException("first independent owner")
        val taskFailure = IllegalArgumentException("second independent owner")
        val f = Fixture(BuildRunDebugOperationGate(cancellationThread = { action ->
            if (attempts.incrementAndGet() == 1) throw buildFailure
            Thread(action, "f156-review-aggregation").apply { isDaemon = true }
        }))
        val buildEntered = CompletableDeferred<Unit>()
        val build = f.scope.launch { f.gate.run(BuildRunDebugCoordinator.Operation.BUILD_DEBUG) { buildEntered.complete(Unit); awaitCancellation() } }
        lifetime(f.tasks).invokeOnCompletion { throw taskFailure }
        try {
            buildEntered.await()
            val result = runCatching { f.coordinator.stopAndJoinForExit() }
            assertSame("First owner failure stays primary", buildFailure, result.exceptionOrNull())
            assertEquals("Second owner failure cannot be lost", 1, buildFailure.suppressed.size)
            assertSame(taskFailure, buildFailure.suppressed.single().cause)
            assertTrue("Observer failure must not cancel its caller", currentCoroutineContext().isActive)
            f.coordinator.stopAndJoinForExit()
            withTimeout(2_000) { build.join() }
        } finally {
            withTimeout(5_000) { f.gate.closeAndJoin(); build.join() }
            f.finish()
        }
    }

    @Test(timeout = 10_000) fun ordinaryOwnersRemainReusableUntilExitAndObserversInheritCallerContext() = runBlocking {
        val observedBuildThread = AtomicReference<Thread>()
        val observedTaskThread = AtomicReference<Thread>()
        val f = Fixture(BuildRunDebugOperationGate(cancellationThread = { action ->
            observedBuildThread.set(Thread.currentThread())
            Thread(action, "f156-review-context").apply { isDaemon = true }
        }))
        val count = AtomicInteger()
        val task = IdeTask("Reusable task", listOf("unused"), f.root)
        val manager = TaskManager(f.root, taskExecutor = { selected, timeout ->
            assertEquals("User task has no arbitrary cutoff", 0L, timeout)
            TaskResult(selected, 0, "normal ${count.incrementAndGet()}", false, 0)
        })
        val buildEntered = CompletableDeferred<Unit>()
        var build: Job? = null
        try {
            repeat(2) {
                assertEquals(it, f.gate.run(BuildRunDebugCoordinator.Operation.TEST) { it })
                assertTrue(f.tasks.start(listOf(task), manager, {}))
                withTimeout(2_000) { f.tasks.state.first { !it.busy } }
                assertEquals(0, f.tasks.state.value.lastResult!!.exitCode)
            }
            assertEquals(2, count.get())
            build = f.scope.launch { f.gate.run(BuildRunDebugCoordinator.Operation.BUILD_DEBUG) { buildEntered.complete(Unit); awaitCancellation() } }
            buildEntered.await()
            lifetime(f.tasks).invokeOnCompletion { observedTaskThread.set(Thread.currentThread()) }
            val callerThread = Thread.currentThread()
            f.coordinator.stopAndJoinForExit()
            assertSame("Build stop observer must inherit caller dispatcher", callerThread, observedBuildThread.get())
            assertSame("Task stop observer must inherit caller dispatcher", callerThread, observedTaskThread.get())
            assertFalse(f.tasks.start(listOf(task), manager, {}))
            assertTrue(runCatching { f.gate.run(BuildRunDebugCoordinator.Operation.TEST) {} }.exceptionOrNull() is IllegalStateException)
            f.coordinator.stopAndJoinForExit()
            assertEquals(2, count.get())
        } finally { withTimeout(5_000) { f.gate.closeAndJoin(); build?.join() }; f.finish() }
    }

    companion object {
        // Use the real host ProcessHandle API without requiring it in Android's compile API.
        private fun alive(pid: Long): Boolean {
            if (pid <= 0) return false
            val type = Class.forName("java.lang.ProcessHandle")
            val handle = type.getMethod("of", Long::class.javaPrimitiveType).invoke(null, pid) as java.util.Optional<*>
            return handle.isPresent && type.getMethod("isAlive").invoke(handle.get()) as Boolean
        }
        private fun lifetime(tasks: WorkspaceTaskController): Job = WorkspaceTaskController::class.java
            .getDeclaredField("lifetime").apply { isAccessible = true }.get(tasks) as Job
        private fun construct(gate: BuildRunDebugOperationGate<BuildRunDebugCoordinator.Operation>, tasks: WorkspaceTaskController,
            scope: CoroutineScope): BuildRunDebugCoordinator {
            // Host JDK APIs are not part of Android's compile API; retain the same allocation via reflection.
            val unsafeType = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val coordinator = unsafeType.getMethod("allocateInstance", Class::class.java).invoke(unsafe, BuildRunDebugCoordinator::class.java) as BuildRunDebugCoordinator
            fun set(name: String, value: Any) { BuildRunDebugCoordinator::class.java.getDeclaredField(name).apply { isAccessible = true }.set(coordinator, value) }
            set("operations", gate); set("projectTasks", tasks); set("operationScope", scope)
            return coordinator
        }
        private fun origins() = listOf(BuildRunDebugCoordinator::class.java, BuildRunDebugOperationGate::class.java,
            WorkspaceTaskController::class.java, TaskManager::class.java, LocalProcessSupervisor::class.java,
            OwnedResourceCleanup::class.java).forEach { println("ORIGIN ${it.name} ${it.protectionDomain.codeSource.location}") }
    }
}
