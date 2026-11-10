package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Independent lifecycle review: real controller/owner, deterministic exact-handle process fixture. */
class AcpTerminalEofIndependenceTest {
    private class Child(val text: String) : HostedStdioProcess {
        val eofCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val gate = CountDownLatch(1)
        private val live = AtomicBoolean(true)
        private val receipt = CompletableDeferred<Int>()
        @Volatile private var result: Int? = null
        var onInputClose: (Child) -> Unit = { it.finish(0) }
        var onStop: (Child) -> Unit = { it.finish(143) }
        override val stdin = object : OutputStream() {
            override fun write(value: Int) = error("ACP must not write input")
            override fun close() {
                eofCalls.incrementAndGet(); entered.countDown()
                try { onInputClose(this@Child) } finally { returned.countDown() }
            }
        }
        override val stdout = ByteArrayInputStream("$text-out\n".toByteArray())
        override val stderr = ByteArrayInputStream("$text-err\n".toByteArray())
        override val isAlive get() = live.get()
        override val exitCode get() = result
        override suspend fun awaitExit(): Int = receipt.await()
        override fun close() { closeCalls.incrementAndGet(); onStop(this) }
        fun finish(code: Int) { result = code; live.set(false); receipt.complete(code) }
        fun hold(thenFail: Boolean = false) {
            onInputClose = {
                check(gate.await(8, TimeUnit.SECONDS)) { "Review fixture was not released" }
                if (thenFail) throw IOException("late EOF fixture failure")
            }
        }
    }

    private class Fixture {
        val root = Files.createTempDirectory("f148-eof-independent-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = SessionHostedProcessOwner()
        val children = mutableListOf<Child>()
        private var next = 0
        private val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = "/fixture/$command"
            override suspend fun pathMapper() = WorkspacePathMapper(root, root.canonicalPath)
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?) = children[next++]
        }
        val controller = AcpTerminalController(owner.bind(host), WorkspacePathMapper(root, root.canonicalPath), ApprovalManager(),
            PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), AcpPermissionLedger(),
            WorkspaceAgentAccessPolicy().apply { setMode(WorkspaceAgentMode.EDIT) }, scope).apply { bindApprovalSession("review-owner") }
        suspend fun create(): JsonObject = withTimeout(1_500) {
            controller.create(buildJsonObject { put("command", "fixture"); put("sessionId", "review-session") })
        }
        suspend fun finished(id: JsonObject): Int = withTimeout(1_500) { controller.waitForExit(id)["exitCode"]!!.jsonPrimitive.int }
        suspend fun close() {
            children.forEach { it.gate.countDown(); it.finish(it.exitCode ?: 143); it.onStop = { child -> child.finish(child.exitCode ?: 143) } }
            runCatching { controller.close() }
            withTimeout(2_000) { owner.stopAndAwait() }
            scope.cancel(); withTimeout(2_000) { scope.coroutineContext.job.join() }
            children.filter { it.eofCalls.get() > 0 }.forEach { assertTrue(it.returned.await(2, TimeUnit.SECONDS)) }

        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val f = Fixture()
        try { withTimeout(6_000) { f.block() } } finally { f.close() }
    }
    private fun observeWorkers(controller: AcpTerminalController): MutableList<CountDownLatch> {
        val completed = mutableListOf<CountDownLatch>()
        controller.startInputCloseWorker = { action ->
            val done = CountDownLatch(1); completed += done
            Thread({ try { action.run() } finally { done.countDown() } }, "f148-review-eof").apply { isDaemon = true }.start()
        }
        return completed
    }
    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        assertTrue("Expected fixture event", latch.await(2, TimeUnit.SECONDS))
    }

    @Test(timeout = 12_000) fun heldEofDoesNotBlockSiblingAdmissionOrProvenExit() = fixture {
        val first = Child("first").apply { hold() }; val second = Child("second")
        children += first; children += second
        val firstId = create(); await(first.entered)
        assertEquals(1L, first.returned.count)
        val secondId = create()
        assertEquals(0, finished(secondId))
        first.finish(23)
        assertEquals("Actual process receipt must win without EOF worker completion", 23, finished(firstId))
        assertEquals(1L, first.returned.count)
        assertEquals(0, first.closeCalls.get())
        val output = controller.output(firstId)
        assertTrue(output["output"]!!.jsonPrimitive.content.contains("first-out"))
        assertTrue(output["output"]!!.jsonPrimitive.content.contains("first-err"))
        assertFalse(output["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(23, output["exitStatus"]!!.jsonObject["exitCode"]!!.jsonPrimitive.int)
    }

    @Test(timeout = 12_000) fun lateEofFailureCannotMutateCompletedSnapshotOrStopProcess() = fixture {
        val child = Child("frozen").apply { hold(thenFail = true) }; children += child
        val workers = observeWorkers(controller)
        val id = create(); await(child.entered)
        child.finish(17); assertEquals(17, finished(id))
        val before = controller.output(id)
        child.gate.countDown(); await(workers.single())
        assertEquals(before, controller.output(id))
        assertEquals("Late failure must not trigger a second stop", 0, child.closeCalls.get())
        assertEquals(1, child.eofCalls.get())
    }

    @Test(timeout = 12_000) fun startupFailureRemainsObservableAndReleaseFailureKeepsExactOwner() = fixture {
        val child = Child("startup"); children += child
        controller.startInputCloseWorker = { throw RejectedExecutionException("fixture rejected EOF thread") }
        val id = create()
        assertEquals(0, child.eofCalls.get()); assertEquals(0, child.closeCalls.get())
        assertTrue(child.isAlive)
        val before = controller.output(id)
        assertTrue(before["output"]!!.jsonPrimitive.content.contains("stdin EOF worker startup failed"))
        assertTrue(before["truncated"]!!.jsonPrimitive.boolean)
        assertNull(before["exitStatus"])
        assertNull(withTimeoutOrNull(100) { controller.waitForExit(id) })
        child.onStop = { throw IOException("fixture first stop failed") }
        assertTrue(runCatching { controller.release(id) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { controller.output(id) }.isFailure)
        assertTrue(child.isAlive)
        child.onStop = { it.finish(71) }
        withTimeout(1_500) { owner.stopAndAwait() }
        assertFalse(child.isAlive); assertEquals(71, child.exitCode)
        assertEquals(2, child.closeCalls.get()); assertEquals(0, child.eofCalls.get())
    }

    @Test(timeout = 12_000) fun releaseAndTurnCancellationNeverReplayOldEofOrAffectNewChild() = fixture {
        val first = Child("released").apply { hold(thenFail = true) }
        val second = Child("cancelled").apply { hold(thenFail = true) }
        val latest = Child("latest")
        val workers = observeWorkers(controller)
        children += first; children += second; children += latest
        val firstId = create(); await(first.entered); controller.release(firstId)
        val secondId = create(); await(second.entered); controller.cancelActive().getOrThrow()
        assertTrue(runCatching { controller.output(firstId) }.isFailure)
        assertTrue(runCatching { controller.output(secondId) }.isFailure)
        controller.bindApprovalSession("next-owner")
        val latestId = create(); assertEquals(0, finished(latestId))
        val latestSnapshot = controller.output(latestId)
        first.gate.countDown(); second.gate.countDown()
        workers.forEach { await(it) }
        assertEquals(latestSnapshot, controller.output(latestId))
        assertEquals(0, latest.closeCalls.get())
        assertEquals(1, first.eofCalls.get()); assertEquals(1, second.eofCalls.get()); assertEquals(1, latest.eofCalls.get())
        withTimeout(1_500) { owner.stopAndAwait() }
        assertEquals(1, first.eofCalls.get()); assertEquals(1, second.eofCalls.get()); assertEquals(1, latest.eofCalls.get())
    }
}
