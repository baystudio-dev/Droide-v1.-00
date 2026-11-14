package com.baystudio.droide.core

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Host tests of the actual production coordinator method and both actual owners.
 * Unsafe bypasses only its Android constructor/services, which are outside this host test.
 * No shutdown implementation is copied or mocked. Empty fixture roots are retained.
 */
class BuildRunDebugExitOwnershipTest {
    private class Hold {
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        suspend fun run(): Nothing {
            entered.complete(Unit)
            try { awaitCancellation() }
            finally {
                withContext(NonCancellable) {
                    cleanup.complete(Unit)
                    release.await()
                    finished.complete(Unit)
                }
            }
        }
    }

    private class Fixture(
        val gate: BuildRunDebugOperationGate<BuildRunDebugCoordinator.Operation> = BuildRunDebugOperationGate(),
    ) {
        val root = Files.createTempDirectory("f156-exit-ownership-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val leases = AtomicInteger()
        val controller = WorkspaceTaskController(scope) { _, _ ->
            leases.incrementAndGet()
            AutoCloseable { leases.decrementAndGet() }
        }
        val coordinator = coordinator(gate, controller, scope)
        val task = IdeTask("Held task", listOf("true"), root)
        fun build(hold: Hold) = scope.launch { gate.run(BuildRunDebugCoordinator.Operation.BUILD_DEBUG) { hold.run() } }
        fun tasks(hold: Hold) = controller.start(listOf(task), TaskManager(root, taskExecutor = { _, _ -> hold.run() }), {})
        suspend fun finish(vararg holds: Hold) {
            holds.forEach { it.release.complete(Unit) }
            withTimeout(5_000) { coordinator.stopAndJoinForExit() }
            scope.cancel()
        }
    }

    @Test(timeout = 10_000) fun idleExitIsIdempotentAndClosesBothAdmissions() = runBlocking {
        val f = Fixture()
        try {
            assertEquals("ready", f.gate.run(BuildRunDebugCoordinator.Operation.TEST) { "ready" })
            val manager = TaskManager(f.root, taskExecutor = { task, _ -> TaskResult(task, 0, "ok", false, 0) })
            assertTrue(f.controller.start(listOf(f.task), manager, {}))
            withTimeout(3_000) { f.controller.state.first { !it.busy } }
            assertEquals(0, f.leases.get())
            repeat(2) { f.coordinator.stopAndJoinForExit() }
            assertFalse(f.controller.start(listOf(f.task), manager, {}))
            assertTrue(runCatching { f.gate.run(BuildRunDebugCoordinator.Operation.TEST) {} }.exceptionOrNull() is IllegalStateException)
            assertTrue("Closing owners must not cancel the retained workspace scope", f.scope.isActive)
        } finally { f.finish() }
    }

    @Test(timeout = 15_000) fun taskReceiptCannotCertifyHeldBuildCleanup() = runBlocking { bothReceipts(taskFirst = true) }
    @Test(timeout = 15_000) fun buildReceiptCannotCertifyHeldTaskCleanup() = runBlocking { bothReceipts(taskFirst = false) }

    private suspend fun bothReceipts(taskFirst: Boolean) {
        val f = Fixture()
        val buildHold = Hold(); val taskHold = Hold()
        val build = f.build(buildHold)
        var exit: Deferred<Unit>? = null
        try {
            withTimeout(3_000) { buildHold.entered.await() }
            assertTrue(f.tasks(taskHold))
            withTimeout(3_000) { taskHold.entered.await() }
            exit = f.scope.async { f.coordinator.stopAndJoinForExit() }
            withTimeout(3_000) { buildHold.cleanup.await(); taskHold.cleanup.await() }
            assertFalse("Both owners still hold cleanup receipts", exit.isCompleted)
            assertTrue(f.controller.state.value.busy)
            assertEquals(1, f.leases.get())
            if (taskFirst) {
                taskHold.release.complete(Unit)
                withTimeout(3_000) { f.controller.state.first { !it.busy } }
                assertEquals(0, f.leases.get())
            } else {
                buildHold.release.complete(Unit)
                withTimeout(3_000) { build.join() }
                assertEquals(1, f.leases.get())
            }
            assertNull("One receipt cannot make Exit succeed", withTimeoutOrNull(100) { exit.await(); true })
            buildHold.release.complete(Unit); taskHold.release.complete(Unit)
            withTimeout(3_000) { exit.await(); build.join() }
            assertTrue(buildHold.finished.isCompleted); assertTrue(taskHold.finished.isCompleted)
            assertFalse(f.controller.state.value.busy)
            assertEquals(0, f.leases.get())
        } finally { f.finish(buildHold, taskHold); exit?.join(); build.join() }
    }

    @Test(timeout = 15_000) fun observerTimeoutRetainsBothOwnerReceiptsForRetry() = runBlocking {
        val dispatches = AtomicInteger()
        val f = Fixture(BuildRunDebugOperationGate(cancellationThread = { action ->
            dispatches.incrementAndGet()
            Thread(action, "f156-retry-cancel").apply { isDaemon = true }
        }))
        val buildHold = Hold(); val taskHold = Hold()
        val build = f.build(buildHold)
        var retry: Deferred<Unit>? = null
        try {
            buildHold.entered.await(); assertTrue(f.tasks(taskHold)); taskHold.entered.await()
            val first = f.scope.async {
                runCatching { withTimeout(200) { f.coordinator.stopAndJoinForExit() } }.exceptionOrNull()
            }
            withTimeout(3_000) { buildHold.cleanup.await(); taskHold.cleanup.await() }
            assertTrue(withTimeout(3_000) { first.await() } is TimeoutCancellationException)
            assertFalse(buildHold.finished.isCompleted); assertFalse(taskHold.finished.isCompleted)
            assertEquals(1, f.leases.get())
            assertTrue(f.scope.isActive)
            retry = f.scope.async { f.coordinator.stopAndJoinForExit() }
            assertNull(withTimeoutOrNull(100) { retry.await(); true })
            taskHold.release.complete(Unit)
            withTimeout(3_000) { f.controller.state.first { !it.busy } }
            assertNull("Retry must still wait for the original build", withTimeoutOrNull(100) { retry.await(); true })
            buildHold.release.complete(Unit)
            withTimeout(3_000) { retry.await(); build.join() }
            assertEquals("Retry reuses the accepted build cancellation", 1, dispatches.get())
            assertEquals(0, f.leases.get())
        } finally { f.finish(buildHold, taskHold); retry?.join(); build.join() }
    }

    @Test(timeout = 15_000) fun failedBuildDispatchStillWaitsForTaskCleanupAndRetainsFailure() = runBlocking {
        val failure = IllegalStateException("controlled build dispatch failure")
        val dispatches = AtomicInteger()
        val f = Fixture(BuildRunDebugOperationGate(cancellationThread = { action ->
            if (dispatches.incrementAndGet() == 1) throw failure
            Thread(action, "f156-failed-dispatch-retry").apply { isDaemon = true }
        }))
        val buildHold = Hold(); val taskHold = Hold()
        val build = f.build(buildHold)
        var exit: Deferred<Throwable?>? = null
        try {
            buildHold.entered.await(); assertTrue(f.tasks(taskHold)); taskHold.entered.await()
            exit = f.scope.async { runCatching { f.coordinator.stopAndJoinForExit() }.exceptionOrNull() }
            withTimeout(3_000) { taskHold.cleanup.await() }
            assertTrue("Failed dispatch is not a build completion receipt", build.isActive)
            assertNull("A failed owner cannot skip another held cleanup", withTimeoutOrNull(100) { exit.await() })
            taskHold.release.complete(Unit)
            assertSame(failure, withTimeout(3_000) { exit.await() })
            assertEquals(0, f.leases.get()); assertFalse(f.controller.state.value.busy)
            assertTrue("Caught child failure must not cancel caller/workspace", currentCoroutineContext().isActive && f.scope.isActive)
            buildHold.release.complete(Unit)
            withTimeout(3_000) { f.coordinator.stopAndJoinForExit(); build.join() }
            assertEquals(2, dispatches.get())
        } finally { f.finish(buildHold, taskHold); exit?.join(); build.join() }
    }

    @Test(timeout = 15_000) fun bothShutdownFailuresAreReportedWithoutUncaughtParentFailure() = runBlocking {
        val buildFailure = IllegalStateException("controlled build dispatch failure")
        val taskFailure = IllegalArgumentException("controlled task completion callback failure")
        val dispatches = AtomicInteger()
        val f = Fixture(BuildRunDebugOperationGate(cancellationThread = { action ->
            if (dispatches.incrementAndGet() == 1) throw buildFailure
            Thread(action, "f156-aggregate-retry").apply { isDaemon = true }
        }))
        val buildHold = Hold()
        val build = f.build(buildHold)
        // Inject a throwing completion callback on the actual controller's real lifetime Job.
        // No owner implementation is substituted and the test does not manufacture an Exit result.
        val lifetime = WorkspaceTaskController::class.java.getDeclaredField("lifetime").apply { isAccessible = true }.get(f.controller) as Job
        lifetime.invokeOnCompletion { throw taskFailure }
        try {
            buildHold.entered.await()
            val result = withTimeout(3_000) { runCatching { f.coordinator.stopAndJoinForExit() } }
            val actual = result.exceptionOrNull()
            assertSame(buildFailure, actual)
            assertEquals(1, requireNotNull(actual).suppressed.size)
            assertSame(taskFailure, actual.suppressed.single().cause)
            assertTrue("Observed failures must not escape as parent cancellation", currentCoroutineContext().isActive)
            assertTrue(f.scope.isActive)
            assertTrue(build.isActive)
            buildHold.release.complete(Unit)
            withTimeout(3_000) { f.coordinator.stopAndJoinForExit(); build.join() }
        } finally { f.finish(buildHold); build.join() }
    }

    @Test(timeout = 20_000) fun realProjectChildDiesWhileIndependentBuildCleanupIsHeld() = runBlocking {
        listOf(BuildRunDebugCoordinator::class.java, BuildRunDebugOperationGate::class.java,
            WorkspaceTaskController::class.java, TaskManager::class.java, LocalProcessSupervisor::class.java,
            OwnedResourceCleanup::class.java).forEach { println("F156 ORIGIN ${it.name} ${it.protectionDomain.codeSource.location}") }
        val f = Fixture()
        val buildHold = Hold()
        val build = f.build(buildHold)
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>(); val finished = CompletableDeferred<Unit>()
        val pid = AtomicLong()
        val task = IdeTask("Exact host child", listOf("/bin/sh", "-c", "echo \$\$; exec sleep 30"), f.root)
        val manager = TaskManager(f.root, taskExecutor = { selected, timeout ->
            try {
                val result = LocalProcessSupervisor.capture(selected.argv, f.root, timeoutMs = timeout,
                    onOutput = { output -> output.trim().toLongOrNull()?.let { pid.set(it); entered.complete(Unit) } })
                TaskResult(selected, result.exitCode, result.output, result.timedOut, 0)
            } catch (failure: CancellationException) { cancelled.complete(Unit); throw failure }
            finally { finished.complete(Unit) }
        })
        var exit: Deferred<Unit>? = null
        try {
            buildHold.entered.await(); assertTrue(f.controller.start(listOf(task), manager, {}))
            withTimeout(5_000) { entered.await() }
            assertTrue("The exact child must be live before Exit", alive(pid.get()))
            exit = f.scope.async { f.coordinator.stopAndJoinForExit() }
            withTimeout(5_000) { buildHold.cleanup.await(); cancelled.await(); finished.await(); f.controller.state.first { !it.busy } }
            val alive = alive(pid.get())
            println("F156 exactChildPid=${pid.get()} childAlive=$alive buildCleanupHeld=${!buildHold.release.isCompleted} exitComplete=${exit.isCompleted}")
            assertFalse("Project child teardown must not wait for build cleanup", alive)
            assertFalse(exit.isCompleted); assertEquals(0, f.leases.get())
            buildHold.release.complete(Unit)
            withTimeout(5_000) { exit.await(); build.join() }
        } finally {
            f.finish(buildHold); exit?.join(); build.join()
            assertFalse("No exact child may leak after test cleanup", alive(pid.get()))
        }
    }

    companion object {
        // Use the real host ProcessHandle API without requiring it in Android's compile API.
        private fun alive(pid: Long): Boolean {
            if (pid <= 0) return false
            val type = Class.forName("java.lang.ProcessHandle")
            val handle = type.getMethod("of", Long::class.javaPrimitiveType).invoke(null, pid) as java.util.Optional<*>
            return handle.isPresent && type.getMethod("isAlive").invoke(handle.get()) as Boolean
        }
        private fun coordinator(gate: BuildRunDebugOperationGate<BuildRunDebugCoordinator.Operation>,
            tasks: WorkspaceTaskController, scope: CoroutineScope): BuildRunDebugCoordinator {
            // Host JDK APIs are not part of Android's compile API; retain the same allocation via reflection.
            val unsafeType = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val actual = unsafeType.getMethod("allocateInstance", Class::class.java).invoke(unsafe, BuildRunDebugCoordinator::class.java) as BuildRunDebugCoordinator
            fun field(name: String, value: Any) { BuildRunDebugCoordinator::class.java.getDeclaredField(name).apply { isAccessible = true }.set(actual, value) }
            field("operations", gate); field("projectTasks", tasks); field("operationScope", scope)
            return actual
        }
    }
}
