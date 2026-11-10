package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual RPC reader/handler ownership; tiny JSON frames, manually dispatched fake approvals only. */
class AcpInboundRetentionTest {
    private class ManualDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { synchronized(queued) { queued.addLast(block) } }
        val size: Int get() = synchronized(queued) { queued.size }
        fun next() { synchronized(queued) { checkNotNull(queued.pollFirst()) }.run() }
        fun last() { synchronized(queued) { checkNotNull(queued.pollLast()) }.run() }
        fun drain() { repeat(32) { if (size == 0) return; next() }; check(size == 0) }
    }

    private class Peer(root: File) : AutoCloseable {
        private val alive = AtomicBoolean(true)
        private val serverOutput = PipedOutputStream()
        private val clientOutput = PipedInputStream(serverOutput, 4_096)
        private val captured = ByteArrayOutputStream()
        val responses = Channel<JsonObject>(16)
        val process = object : HostedStdioProcess {
            override val stdin = object : OutputStream() {
                @Synchronized override fun write(value: Int) { captured.write(value) }
                @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) { captured.write(bytes, offset, length) }
                @Synchronized override fun flush() {
                    val text = captured.toString("UTF-8")
                    if (text.isEmpty()) return
                    check(text.endsWith('\n')); captured.reset()
                    text.lineSequence().filter { it.isNotBlank() }.forEach {
                        check(responses.trySend(Json.parseToJsonElement(it).jsonObject).isSuccess)
                    }
                }
            }
            override val stdout = clientOutput
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive get() = alive.get()
            override val exitCode get() = if (alive.get()) null else 0
            override suspend fun awaitExit() = 0
            override fun close() = this@Peer.close()
        }
        val mapper = WorkspacePathMapper(root, root.canonicalPath)
        val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = error("No process permitted")
            override suspend fun pathMapper() = mapper
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess = error("No process permitted")
        }
        val io = object : AgentWorkspaceIo {
            override val checksumCommand = "unused"
            override fun requirePath(path: String) = Unit
            override suspend fun refresh() = Unit
            override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult = error("No shell permitted")
            override suspend fun pull(path: String, output: File) = error("No transfer permitted")
            override suspend fun push(input: File, path: String) = error("No transfer permitted")
        }
        fun emit(value: JsonObject) {
            val bytes = (value.toString() + "\n").toByteArray()
            check(bytes.size < 1_024)
            serverOutput.write(bytes); serverOutput.flush()
        }
        suspend fun response(id: String): JsonObject = withTimeout(2_000) {
            while (true) {
                val value = responses.receive()
                if ((value["id"] as? JsonPrimitive)?.contentOrNull == id) return@withTimeout value
            }
            error("Unreachable")
        }
        override fun close() {
            if (alive.compareAndSet(true, false)) {
                runCatching { serverOutput.close() }; runCatching { clientOutput.close() }; responses.close()
            }
        }
    }

    private fun permission(id: String) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("method", "session/request_permission")
        put("params", buildJsonObject {
            put("sessionId", "s")
            put("toolCall", buildJsonObject { put("toolCallId", "t$id"); put("kind", "read"); put("title", "Ask") })
            put("options", buildJsonArray { add(buildJsonObject { put("optionId", "deny"); put("kind", "reject_once") }) })
        })
    }
    private suspend fun waitFor(check: () -> Boolean) = withTimeout(2_000) { while (!check()) delay(1) }

    private suspend fun driveUntil(dispatcher: ManualDispatcher, check: () -> Boolean) = withTimeout(2_000) {
        while (!check()) { dispatcher.drain(); delay(1) }
    }

    private fun fixture(maxChars: Long, maxIdChars: Int = 512, maxRequests: Int = 32,
        block: suspend CoroutineScope.(Peer, AcpNdjsonConnection, ApprovalManager, ManualDispatcher) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("r30-inbound-retention-").toFile()
        val errors = CopyOnWriteArrayList<Throwable>()
        val dispatcher = ManualDispatcher()
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + dispatcher + CoroutineExceptionHandler { _, error -> errors += error })
        val peer = Peer(root)
        val approvals = ApprovalManager()
        val permissions = PermissionEngine(base = listOf(PermRule("*", "*", PermEffect.ASK)))
        val rpc = AcpNdjsonConnection(peer.process, FileRepository(root), peer.mapper, peer.host,
            approvals, permissions, scope, peer.io, maxInboundRetainedChars = maxChars, maxInboundIdChars = maxIdChars, maxInboundRetainedRequests = maxRequests)
        rpc.bindSession("s", "fixture-approval")
        rpc.beginTurn(WorkspaceAgentMode.PLAN)
        try { withTimeout(6_000) { block(peer, rpc, approvals, dispatcher) } }
        finally {
            rpc.close(); peer.close(); owner.cancel()
            withContext(NonCancellable) {
                withTimeout(2_000) { while (!owner.isCompleted) { dispatcher.drain(); delay(1) } }
            }
            assertEquals(0L, rpc.retainedInboundChars)
            assertTrue("Uncaught RPC task errors: $errors", errors.isEmpty())
            check(root.deleteRecursively())
        }
    }

    @Test(timeout = 10_000) fun aggregateBudgetRejectsSecondPendingRequestThenReleasesAfterNormalCompletion() {
        val a = permission("a"); val b = permission("b")
        val size = a.toString().length.toLong()
        assertEquals(size, b.toString().length.toLong())
        fixture(size * 2 - 1) { peer, rpc, approvals, dispatcher ->
            peer.emit(a)
            waitFor { dispatcher.size > 0 }; dispatcher.next()
            waitFor { approvals.pending.value != null }
            assertEquals(size, rpc.retainedInboundChars)
            peer.emit(b)
            val rejected = peer.response("b")
            assertEquals(-32000, rejected["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
            assertEquals(size, rpc.retainedInboundChars)
            approvals.resolve(false)
            waitFor { dispatcher.size > 0 }; dispatcher.next()
            peer.response("a")
            driveUntil(dispatcher) { rpc.retainedInboundChars == 0L }
            peer.emit(b)
            waitFor { dispatcher.size > 0 }; dispatcher.next()
            waitFor { approvals.pending.value != null }
            approvals.resolve(false)
            waitFor { dispatcher.size > 0 }; dispatcher.next()
            peer.response("b")
            driveUntil(dispatcher) { rpc.retainedInboundChars == 0L }
            assertFalse(rpc.hasPendingClientCalls)
        }
    }

    @Test(timeout = 10_000) fun cancelledSameIdOwnerCannotDeleteReplacementAndStaysChargedUntilCompletion() {
        val request = permission("a")
        val size = request.toString().length.toLong()
        fixture(size * 2) { peer, rpc, approvals, dispatcher ->
            peer.emit(request)
            waitFor { dispatcher.size > 0 }; dispatcher.next()
            waitFor { approvals.pending.value != null }
            rpc.cancelTurn("s")
            assertFalse(rpc.hasPendingClientCalls)
            assertEquals("Cancelled handler has not been dispatched to unwind yet", size, rpc.retainedInboundChars)
            peer.emit(request)
            waitFor { rpc.retainedInboundChars == size * 2 && dispatcher.size >= 2 }
            // Start the replacement before running the old handler's cancellation continuation.
            dispatcher.last()
            dispatcher.next()
            waitFor { rpc.retainedInboundChars == size }
            assertTrue("Old completion must compare-remove its exact call", rpc.hasPendingClientCalls)
            waitFor { dispatcher.size > 0 }; dispatcher.next()
            waitFor { approvals.pending.value != null }
            approvals.resolve(false)
            waitFor { dispatcher.size > 0 }; dispatcher.next()
            driveUntil(dispatcher) { rpc.retainedInboundChars == 0L }
            assertFalse(rpc.hasPendingClientCalls)
        }
    }

    @Test(timeout = 10_000) fun oversizeScalarIdRetiresBeforeKeySerializationUsingTinyInjectedLimit() = fixture(1_024, maxIdChars = 2) { peer, rpc, _, _ ->
        peer.emit(permission("abc"))
        waitFor { !peer.process.isAlive }
        assertEquals(0L, rpc.retainedInboundChars)
        assertFalse(rpc.hasPendingClientCalls)
    }
    @Test(timeout = 10_000) fun cancelledUnwindingHandlerStillOwnsTheTinyRequestCountSlot() {
        val a = permission("a"); val b = permission("b")
        val size = a.toString().length.toLong()
        fixture(size * 2, maxRequests = 1) { peer, rpc, approvals, dispatcher ->
            peer.emit(a)
            waitFor { dispatcher.size > 0 }; dispatcher.next()
            waitFor { approvals.pending.value != null }
            rpc.cancelTurn("s")
            assertFalse(rpc.hasPendingClientCalls)
            assertEquals(size, rpc.retainedInboundChars)
            peer.emit(b)
            val rejected = peer.response("b")
            assertEquals(-32000, rejected["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
            assertEquals("Cancellation/map removal cannot release a still-owned count slot", size, rpc.retainedInboundChars)
            dispatcher.next() // Old cancellation now actually completes.
            waitFor { rpc.retainedInboundChars == 0L }
            peer.emit(b)
            waitFor { dispatcher.size > 0 }; dispatcher.next()
            waitFor { approvals.pending.value != null }
            approvals.resolve(false)
            driveUntil(dispatcher) { rpc.retainedInboundChars == 0L }
            assertFalse(rpc.hasPendingClientCalls)
        }
    }

}
