package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AcpTerminalInputLifecycleTest {
    private class Child(val stdoutText: String = "", val onEof: () -> Unit = {}) : HostedStdioProcess {
        val eofCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        val eofFinished = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val exit = CompletableDeferred<Int>()
        var closeFailure: Throwable? = null
        override val stdin = object : OutputStream() {
            override fun write(value: Int) = error("ACP terminal stdin has no writer")
            override fun close() { eofCalls.incrementAndGet(); try { onEof() } finally { eofFinished.countDown() } }
        }
        override val stdout = ByteArrayInputStream(stdoutText.toByteArray())
        override val stderr = ByteArrayInputStream(byteArrayOf())
        override val isAlive get() = !exit.isCompleted
        override val exitCode: Int? get() = null
        override suspend fun awaitExit(): Int = exit.await()
        override fun close() { closeCalls.incrementAndGet(); stopped.countDown(); closeFailure?.let { throw it } }
    }
    private class Fixture {
        val root = Files.createTempDirectory("f148-input-lifecycle-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val children = CopyOnWriteArrayList<Child>()
        val next = AtomicInteger()
        var startGate: CompletableDeferred<Unit>? = null
        var started: CompletableDeferred<Unit>? = null
        val releases = mutableListOf<CountDownLatch>()
        val owner = SessionHostedProcessOwner()
        val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = "/fixture/$command"
            override suspend fun pathMapper() = WorkspacePathMapper(root, root.canonicalPath)
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                val child = children[next.getAndIncrement()]
                started?.complete(Unit); startGate?.await()
                return child
            }
        }
        val controller = AcpTerminalController(owner.bind(host), WorkspacePathMapper(root, root.canonicalPath), ApprovalManager(),
            PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), AcpPermissionLedger(),
            WorkspaceAgentAccessPolicy().apply { setMode(WorkspaceAgentMode.EDIT) }, scope).apply { bindApprovalSession("fixture") }
        suspend fun create() = controller.create(buildJsonObject { put("command", "fixture"); put("sessionId", "fixture") })
        fun child(text: String = "", eof: () -> Unit = {}): Child = Child(text, eof).also { children += it }
        fun gate() = CountDownLatch(1).also { releases += it }
        suspend fun finish() {
            releases.forEach(CountDownLatch::countDown); startGate?.complete(Unit)
            children.forEach { it.closeFailure = null; it.exit.complete(143) }
            controller.close(); owner.stopAndAwait()
            scope.cancel(); scope.coroutineContext.job.join()
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val f = Fixture()
        try { withTimeout(7_000) { f.block() } }
        finally { withContext(NonCancellable) { withTimeout(3_000) { f.finish() } } }
    }

    @Test(timeout = 10_000) fun blockedEofCannotHoldCreateSiblingOrActualExitAndDoesNotReplay() = fixture {
        val entered = CountDownLatch(1); val release = gate()
        val first = child("first-output") { entered.countDown(); check(release.await(6, TimeUnit.SECONDS)) }
        val firstId = withTimeout(500) { create() }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val second = child("second-output")
        val secondId = withTimeout(500) { create() }
        assertTrue(second.eofFinished.await(1, TimeUnit.SECONDS))
        first.exit.complete(37); second.exit.complete(0)
        assertEquals(37, withTimeout(1_000) { controller.waitForExit(firstId) }.getValue("exitCode").jsonPrimitive.int)
        assertEquals(0, withTimeout(1_000) { controller.waitForExit(secondId) }.getValue("exitCode").jsonPrimitive.int)
        assertEquals("first-output", controller.output(firstId).getValue("output").jsonPrimitive.content)
        assertEquals(1L, first.eofFinished.count)
        controller.release(firstId); controller.cancelActive(); controller.close()
        assertEquals(1, first.eofCalls.get()); assertEquals(1, second.eofCalls.get())
        release.countDown(); assertTrue(first.eofFinished.await(1, TimeUnit.SECONDS))
    }

    @Test(timeout = 10_000) fun eofFailureStopsOnlyExactChildAndWaitsForItsRealExit() = fixture {
        val release = gate(); val entered = CountDownLatch(1)
        val first = child { entered.countDown(); check(release.await(6, TimeUnit.SECONDS)); throw IOException("fixture EOF rejected " + "x".repeat(2_000)) }
        val firstId = create(); assertTrue(entered.await(1, TimeUnit.SECONDS))
        val second = child(); val secondId = create()
        release.countDown(); assertTrue(first.stopped.await(1, TimeUnit.SECONDS))
        val output = controller.output(firstId)
        assertTrue(output.getValue("truncated").jsonPrimitive.boolean)
        assertTrue(output.getValue("output").jsonPrimitive.content.contains("stdin EOF failed"))
        assertTrue(output.getValue("output").jsonPrimitive.content.length < 400)
        assertFalse(output.containsKey("exitStatus"))
        assertNull(withTimeoutOrNull(100) { controller.waitForExit(firstId) })
        assertEquals(1, first.closeCalls.get()); assertEquals(0, second.closeCalls.get())
        assertFalse(controller.output(secondId).containsKey("exitStatus"))
        first.exit.complete(23)
        assertEquals(23, withTimeout(1_000) { controller.waitForExit(firstId) }.getValue("exitCode").jsonPrimitive.int)
    }

    @Test(timeout = 10_000) fun fatalEofAndStopFailureAreContainedWithoutInventedExit() = fixture {
        val release = gate(); val entered = CountDownLatch(1)
        val child = child { entered.countDown(); check(release.await(6, TimeUnit.SECONDS)); throw AssertionError("fatal EOF fixture") }
        child.closeFailure = AssertionError("fatal cleanup fixture")
        val escaped = AtomicReference<Throwable?>()
        val workerDone = CountDownLatch(1)
        controller.startInputCloseWorker = { action -> Thread({ try { action.run() } catch (t: Throwable) { escaped.set(t) } finally { workerDone.countDown() } }).apply { isDaemon = true }.start() }
        val id = create(); assertTrue(entered.await(1, TimeUnit.SECONDS)); release.countDown()
        assertTrue(workerDone.await(1, TimeUnit.SECONDS)); assertNull(escaped.get())
        val output = controller.output(id)
        assertTrue(output.getValue("output").jsonPrimitive.content.contains("fatal EOF fixture"))
        assertTrue(output.getValue("output").jsonPrimitive.content.contains("fatal cleanup fixture"))
        assertFalse(output.containsKey("exitStatus"))
        child.closeFailure = null; child.exit.complete(42)
        assertEquals(42, withTimeout(1_000) { controller.waitForExit(id) }.getValue("exitCode").jsonPrimitive.int)
    }

    @Test(timeout = 10_000) fun workerLaunchFailureKeepsExactHandleAvailableAndNeverClosesInline() = fixture {
        val child = child()
        controller.startInputCloseWorker = { throw AssertionError("fixture thread launch rejected") }
        val id = withTimeout(500) { create() }
        assertEquals(0, child.eofCalls.get()); assertEquals(0, child.closeCalls.get())
        assertTrue(controller.output(id).getValue("output").jsonPrimitive.content.contains("worker startup failed"))
        assertFalse(controller.output(id).containsKey("exitStatus"))
        controller.kill(id); assertEquals(1, child.closeCalls.get())
        child.exit.complete(143)
        assertEquals(143, withTimeout(1_000) { controller.waitForExit(id) }.getValue("exitCode").jsonPrimitive.int)
    }

    @Test(timeout = 10_000) fun alreadyProvenExitSnapshotIsNotRewrittenByLateEofFailure() = fixture {
        val release = gate(); val entered = CountDownLatch(1)
        val workerDone = CountDownLatch(1)
        val child = child("final-output") { entered.countDown(); check(release.await(6, TimeUnit.SECONDS)); throw IOException("late EOF") }
        controller.startInputCloseWorker = { action -> Thread({ try { action.run() } finally { workerDone.countDown() } }).apply { isDaemon = true }.start() }
        val id = create(); assertTrue(entered.await(1, TimeUnit.SECONDS)); child.exit.complete(0)
        withTimeout(1_000) { controller.waitForExit(id) }
        val frozen = controller.output(id)
        assertFalse(frozen.getValue("truncated").jsonPrimitive.boolean)
        release.countDown(); assertTrue(workerDone.await(1, TimeUnit.SECONDS))
        assertEquals(frozen, controller.output(id)); assertEquals(0, child.closeCalls.get())
    }

    @Test(timeout = 10_000) fun lateWorkerAfterReleaseClosesOnlyItsOriginalInputOnce() = fixture {
        val action = AtomicReference<Runnable?>()
        controller.startInputCloseWorker = { check(action.compareAndSet(null, it)) }
        val old = child(); val id = create()
        controller.release(id); controller.cancelActive()
        val replacement = child()
        action.get()!!.run()
        assertEquals(1, old.eofCalls.get()); assertEquals(0, replacement.eofCalls.get())
        assertEquals(1, old.closeCalls.get()); assertEquals(0, replacement.closeCalls.get())
    }

    @Test(timeout = 10_000) fun returnedChildFromRetiredCreateGetsStoppedWithoutStartingInputWorker() = fixture {
        val child = child()
        startGate = CompletableDeferred(); started = CompletableDeferred()
        coroutineScope {
            val creation = async { runCatching { create() } }
            started!!.await(); controller.cancelActive(); startGate!!.complete(Unit)
            assertTrue(creation.await().isFailure)
        }
        assertEquals(0, child.eofCalls.get()); assertEquals(1, child.closeCalls.get())
    }
}
