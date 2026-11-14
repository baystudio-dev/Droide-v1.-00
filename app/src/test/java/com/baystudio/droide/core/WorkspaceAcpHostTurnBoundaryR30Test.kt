package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real Host/runMediaTurn, constructor-only seam; all process and workspace operations are fakes. */
class WorkspaceAcpHostTurnBoundaryR30Test {
    private class Peer(private val supportsLoad: Boolean = true) : AutoCloseable {
        private val alive = AtomicBoolean(true)
        private val exited = CompletableDeferred<Unit>()
        private val outgoing = Channel<JsonObject>(16)
        private val serverOutput = PipedOutputStream()
        private val clientOutput = PipedInputStream(serverOutput, 4_096)
        private val captured = ByteArrayOutputStream()
        val methods = CopyOnWriteArrayList<String>()
        val loadedIds = CopyOnWriteArrayList<String>()
        var cleanupGate = CompletableDeferred(Unit)
        val cleanupEntered = CompletableDeferred<Unit>()
        var cleanupFailure: Throwable? = null
        var stopFailure: Throwable? = null
        private val clientInput = object : OutputStream() {
            @Synchronized override fun write(value: Int) { captured.write(value) }
            @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) { captured.write(bytes, offset, length) }
            @Synchronized override fun flush() {
                val text = captured.toString("UTF-8")
                if (text.isEmpty()) return
                check(text.endsWith('\n'))
                captured.reset()
                text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    val request = Json.parseToJsonElement(line).jsonObject
                    val method = request["method"]!!.jsonPrimitive.content
                    methods += method
                    when (method) {
                        "initialize" -> respond(request, buildJsonObject {
                            put("protocolVersion", 1)
                            put("agentCapabilities", buildJsonObject { put("loadSession", supportsLoad) })
                        })
                        "session/new" -> respond(request, buildJsonObject {
                            put("sessionId", "session_one"); put("modes", planConfiguration().getValue("modes"))
                        })
                        "session/load" -> {
                            loadedIds += request["params"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content
                            respond(request, planConfiguration())
                        }
                        "session/prompt", "session/cancel" -> check(outgoing.trySend(request).isSuccess)
                        else -> error("Unexpected fake ACP request: $method")
                    }
                }
            }
        }
        val process = object : HostedStdioProcess {
            override val stdin = clientInput
            override val stdout = clientOutput
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive get() = alive.get()
            override val exitCode get() = if (alive.get()) null else 0
            override suspend fun awaitExit(): Int {
                // Natural completion is observed from launch; observation alone cannot report an exit.
                exited.await()
                cleanupEntered.complete(Unit)
                cleanupGate.await()
                cleanupFailure?.let { throw it }
                return 0
            }
            override fun close() {
                this@Peer.close()
                stopFailure?.let { throw it }
            }
        }
        suspend fun next(method: String): JsonObject = withTimeout(2_000) {
            while (true) {
                val frame = outgoing.receive()
                val actual = frame["method"]!!.jsonPrimitive.content
                if (actual == method) return@withTimeout frame
                check(actual == "session/cancel") { "Unexpected frame while waiting for $method: $actual" }
            }
            error("Unreachable")
        }
        fun finish(request: JsonObject, stop: String = "end_turn") =
            respond(request, buildJsonObject { put("stopReason", stop) })
        private fun planConfiguration() = buildJsonObject {
            put("modes", buildJsonObject {
                put("currentModeId", "plan")
                put("availableModes", buildJsonArray { add(buildJsonObject { put("id", "plan"); put("name", "Plan") }) })
            })
        }
        private fun respond(request: JsonObject, result: JsonObject) = emit(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", request.getValue("id")); put("result", result)
        })
        fun text(text: String) = emit(buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "session/update")
            put("params", buildJsonObject {
                put("sessionId", "session_one")
                put("update", buildJsonObject {
                    put("sessionUpdate", "agent_message_chunk")
                    put("content", buildJsonObject { put("type", "text"); put("text", text) })
                })
            })
        })
        @Synchronized private fun emit(value: JsonObject) {
            val bytes = (value.toString() + "\n").toByteArray()
            check(bytes.size < 1_024)
            serverOutput.write(bytes); serverOutput.flush()
        }
        override fun close() {
            if (alive.compareAndSet(true, false)) {
                runCatching { serverOutput.close() }; runCatching { clientOutput.close() }
                outgoing.close()
                exited.complete(Unit)
            }
        }
    }

    private class Processes(val root: File) : StdioProcessHost, AutoCloseable {
        val peers = CopyOnWriteArrayList<Peer>()
        var supportsLoad = true
        var beforeReturn: suspend (Peer) -> Unit = {}
        var refreshGate: CompletableDeferred<Unit>? = null
        val refreshEntered = CompletableDeferred<Unit>()
        private val created = Channel<Peer>(4)
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        override suspend fun resolveExecutable(command: String) = "/fixture/$command"
        override suspend fun pathMapper() = WorkspacePathMapper(root, root.canonicalPath)
        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
            check(argv == listOf("/fixture/opencode", "acp") || argv == listOf("/fixture/codex-acp"))
            val peer = Peer(supportsLoad).also { peers += it; check(created.trySend(it).isSuccess) }
            currentCoroutineContext()[SessionHostedProcessOwner]?.retain(peer.process)
            beforeReturn(peer)
            return peer.process
        }
        suspend fun nextPeer(): Peer = withTimeout(2_000) { created.receive() }
        override fun close() { peers.forEach(Peer::close); created.close() }
    }

    private fun fixture(block: suspend CoroutineScope.(WorkspaceAgentPluginHost, Processes, Peer) -> Unit) =
        unpreparedFixture { host, processes ->
            val config = host.prepareSession("opencode", null, {})
            assertEquals("session_one", config.sessionId)
            block(host, processes, processes.nextPeer())
        }

    private fun unpreparedFixture(sharedRoot: File? = null,
        block: suspend CoroutineScope.(WorkspaceAgentPluginHost, Processes) -> Unit) = runBlocking {
        val root = sharedRoot ?: Files.createTempDirectory("droide-r30-host-boundary-").toFile()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val ownedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> uncaught += t })
        val processes = Processes(root)
        val io = object : AgentWorkspaceIo {
            override val checksumCommand = "fixture-checksum"
            override suspend fun baselineEntries(): Map<String, WorkspaceSyncManifest.Entry> = emptyMap()
            override fun requirePath(path: String) { check(path == root.canonicalPath) }
            override suspend fun refresh() {
                processes.refreshGate?.let { processes.refreshEntered.complete(Unit); it.await() }
            }
            override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult {
                // The immutable empty baseline belongs to the host fixture.
                check(command.contains("fixture-checksum"))
                check(maxOutputBytes == 2_000_000)
                return BridgeShellResult(0, "", "")
            }
            override suspend fun pull(path: String, output: File) = error("No transfer permitted")
            override suspend fun push(input: File, path: String) = error("No transfer permitted")
        }
        val host = WorkspaceAgentPluginHost(root, File(root, "cache"), FileRepository(root), processes,
            ApprovalManager(), PermissionEngine(), ownedScope, io)
        try {
            withTimeout(7_000) {
                block(host, processes)
            }
        } finally {
            processes.refreshGate?.complete(Unit)
            processes.peers.forEach { it.cleanupGate.complete(Unit); it.cleanupFailure = null; it.stopFailure = null }
            host.closeAndAwait(); processes.close()
            withTimeout(2_000) { ownedScope.coroutineContext.job.cancelAndJoin() }
            if (sharedRoot == null) root.deleteRecursively()
            assertTrue("Uncaught Host/transport task failure: $uncaught", uncaught.isEmpty())
        }
    }

    private suspend fun WorkspaceAgentPluginHost.turn(label: String, event: (WorkspaceAgentEvent) -> Unit = {}) =
        runMediaTurn("opencode", label, emptyList(), null, WorkspaceAgentMode.PLAN, "session_one", event)

    @Test(timeout = 12_000) fun resetWaitsForCleanupReceiptEvenAfterProcessReportsDead() = fixture { host, processes, peer ->
        peer.cleanupGate = CompletableDeferred()
        val reset = async { host.newSession("opencode", {}) }
        peer.cleanupEntered.await()
        assertFalse(peer.process.isAlive)
        assertFalse(reset.isCompleted)
        assertEquals(1, processes.peers.size)
        peer.cleanupGate.complete(Unit)
        reset.await()
        assertEquals(2, processes.peers.size)
        assertTrue(processes.peers.last().process.isAlive)
    }

    @Test(timeout = 12_000) fun failedCleanupRetainsSessionAndBlocksBothResetAndPrepareUntilRetry() = fixture { host, processes, peer ->
        peer.cleanupFailure = IllegalStateException("cleanup fixture failed")
        assertTrue(runCatching { host.newSession("opencode", {}) }.isFailure)
        assertTrue(runCatching { host.prepareSession("opencode", null, {}) }.isFailure)
        assertEquals(1, processes.peers.size)
        assertTrue(host.workspaceSyncState.value.notice.orEmpty().contains("cleanup", ignoreCase = true))
        peer.cleanupFailure = null
        host.discardSession("opencode", "session_one")
        host.prepareSession("opencode", null, {})
        assertEquals(2, processes.peers.size)
    }

    @Test(timeout = 12_000) fun stopFailureStillAwaitsHandleAndCanBeRetried() = fixture { host, processes, peer ->
        val originalHandle = peer.process
        val stopFailure = IllegalStateException("stop failed")
        peer.stopFailure = stopFailure
        val firstReset = runCatching { host.newSession("opencode", {}) }
        assertTrue(firstReset.isFailure)
        assertTrue("The original stop error must remain visible",
            generateSequence(firstReset.exceptionOrNull()) { it.cause }.any { it === stopFailure })
        assertFalse(peer.process.isAlive)
        assertEquals(1, processes.peers.size)
        assertSame(originalHandle, processes.peers.single().process)

        // A dead handle and another failed stop attempt are not cleanup receipts.
        val repeatedReset = runCatching { host.newSession("opencode", {}) }
        assertTrue(repeatedReset.isFailure)
        assertTrue("Repeated failure must retain the exact stop error",
            generateSequence(repeatedReset.exceptionOrNull()) { it.cause }.any { it === stopFailure })
        assertTrue(runCatching { host.prepareSession("opencode", null, {}) }.isFailure)
        assertEquals(1, processes.peers.size)
        assertSame(originalHandle, processes.peers.single().process)

        val cleanupReceipt = CompletableDeferred<Unit>()
        peer.cleanupGate = cleanupReceipt
        peer.stopFailure = null
        val retry = async { host.newSession("opencode", {}) }
        try {
            withTimeout(2_000) { peer.cleanupEntered.await() }
            assertTrue(peer.cleanupEntered.isCompleted)
            assertFalse(peer.process.isAlive)
            assertFalse("The retry must await the original handle's receipt", retry.isCompleted)
            assertNull(withTimeoutOrNull(100) { retry.await(); true })
            assertEquals(1, processes.peers.size)
            assertSame(originalHandle, processes.peers.single().process)
        } finally { cleanupReceipt.complete(Unit) }
        assertEquals("session_one", withTimeout(2_000) { retry.await() })
        assertEquals(2, processes.peers.size)
        assertNotSame(originalHandle, processes.peers.last().process)
        assertTrue(processes.peers.last().process.isAlive)
    }

    @Test(timeout = 12_000) fun cancelledResetKeepsOwnershipUntilCleanupAndDoesNotLaunchReplacement() = fixture { host, processes, peer ->
        peer.cleanupGate = CompletableDeferred()
        val reset = launch { host.newSession("opencode", {}) }
        peer.cleanupEntered.await()
        reset.cancel()
        yield()
        assertFalse(reset.isCompleted)
        val prepare = async { host.prepareSession("opencode", null, {}) }
        yield()
        assertEquals(1, processes.peers.size)
        peer.cleanupGate.complete(Unit)
        reset.join(); prepare.await()
        assertEquals(2, processes.peers.size)
    }

    @Test(timeout = 12_000) fun repeatedDiscardAndStaleDiscardCannotRetireReplacement() = fixture { host, processes, peer ->
        peer.cleanupGate = CompletableDeferred()
        val first = async { host.discardSession("opencode", "session_one") }
        peer.cleanupEntered.await()
        val second = async { host.discardSession("opencode", "session_one") }
        yield(); assertFalse(first.isCompleted); assertFalse(second.isCompleted)
        peer.cleanupGate.complete(Unit)
        first.await(); second.await()
        host.prepareSession("opencode", null, {})
        host.discardSession("opencode", "obsolete-session")
        assertTrue(processes.peers.last().process.isAlive)
        assertEquals(2, processes.peers.size)
    }

    @Test(timeout = 12_000) fun bootstrapHandoffFailureRetainsBornProcessBeforeRetry() = fixture { host, processes, _ ->
        processes.beforeReturn = { peer ->
            peer.cleanupFailure = IllegalStateException("incomplete bootstrap cleanup")
            throw CancellationException("cancelled after creation")
        }
        assertTrue(runCatching { host.newSession("opencode", {}) }.isFailure)
        assertEquals(2, processes.peers.size)
        assertTrue(runCatching { host.prepareSession("opencode", null, {}) }.isFailure)
        assertEquals(2, processes.peers.size)
        processes.peers.last().cleanupFailure = null
        processes.beforeReturn = {}
        host.newSession("opencode", {})
        assertEquals(3, processes.peers.size)
    }

    @Test(timeout = 12_000) fun concurrentResetsSerializeAndOldCleanupCannotCloseTheLastSession() = fixture { host, processes, peer ->
        peer.cleanupGate = CompletableDeferred()
        val first = async { host.newSession("opencode", {}) }
        peer.cleanupEntered.await()
        val second = async { host.newSession("opencode", {}) }
        yield(); assertEquals(1, processes.peers.size)
        peer.cleanupGate.complete(Unit)
        first.await(); second.await()
        assertEquals(3, processes.peers.size)
        assertTrue(processes.peers.last().process.isAlive)
        assertTrue(processes.peers.dropLast(1).all { !it.process.isAlive })
    }

    @Test(timeout = 12_000) fun resettingOneWorkspaceDoesNotStopAnotherWorkspaceSession() = fixture { first, _, _ ->
        fixture { _, otherProcesses, otherPeer ->
            first.newSession("opencode", {})
            assertTrue(otherPeer.process.isAlive)
            assertFalse(otherPeer.cleanupEntered.isCompleted)
            assertEquals(1, otherProcesses.peers.size)
        }
    }

    @Test(timeout = 12_000) fun resetDoesNotStopHealthySiblingAgentInTheSameWorkspace() = fixture { host, processes, _ ->
        host.prepareSession("codex", null, {})
        val sibling = processes.nextPeer()
        host.newSession("opencode", {})
        assertTrue(sibling.process.isAlive)
        assertFalse(sibling.cleanupEntered.isCompleted)
        assertEquals(3, processes.peers.size)
    }

    @Test(timeout = 12_000) fun switchingAgentCannotBypassUnconfirmedCleanup() = fixture { host, processes, peer ->
        peer.cleanupFailure = IllegalStateException("cleanup failed")
        assertTrue(runCatching { host.newSession("opencode", {}) }.isFailure)
        assertTrue(runCatching { host.prepareSession("codex", null, {}) }.isFailure)
        assertEquals(1, processes.peers.size)
        peer.cleanupFailure = null
        host.prepareSession("codex", null, {})
        assertEquals(2, processes.peers.size)
    }

    @Test(timeout = 12_000) fun replacementHostWaitsForOldWorkspaceCleanupAndStaleCloseCannotClearNewOwner() = fixture { old, oldProcesses, peer ->
        peer.cleanupGate = CompletableDeferred()
        unpreparedFixture(oldProcesses.root) { replacement, newProcesses ->
            val start = async { replacement.prepareSession("opencode", null, {}) }
            peer.cleanupEntered.await()
            assertFalse(start.isCompleted)
            assertTrue(newProcesses.peers.isEmpty())
            peer.cleanupGate.complete(Unit)
            start.await()
            val current = newProcesses.nextPeer()
            old.closeAndAwait()
            assertTrue(current.process.isAlive)
            assertTrue(runCatching { old.newSession("opencode", {}) }.isFailure)
            replacement.prepareSession("opencode", null, {})
            assertEquals(1, newProcesses.peers.size)
        }
    }

    @Test(timeout = 12_000) fun replacementHostCannotBypassFailedCleanup() = fixture { _, oldProcesses, peer ->
        peer.cleanupFailure = IllegalStateException("old workspace cleanup failed")
        unpreparedFixture(oldProcesses.root) { replacement, newProcesses ->
            assertTrue(runCatching { replacement.prepareSession("opencode", null, {}) }.isFailure)
            assertTrue(newProcesses.peers.isEmpty())
            peer.cleanupFailure = null
            replacement.prepareSession("opencode", null, {})
            assertEquals(1, newProcesses.peers.size)
        }
    }

    @Test(timeout = 12_000) fun hostReplacementRacingResetDoesNotDeadlockOrStartARetiredHost() = fixture { old, oldProcesses, peer ->
        peer.cleanupGate = CompletableDeferred()
        val reset = async { runCatching { old.newSession("opencode", {}) } }
        peer.cleanupEntered.await()
        unpreparedFixture(oldProcesses.root) { replacement, newProcesses ->
            val start = async { replacement.prepareSession("opencode", null, {}) }
            yield()
            peer.cleanupGate.complete(Unit)
            start.await(); reset.await()
            assertEquals(1, newProcesses.peers.size)
            assertTrue(newProcesses.peers.single().process.isAlive)
            assertTrue(oldProcesses.peers.all { !it.process.isAlive })
        }
    }

    @Test(timeout = 12_000) fun replacementWaitsForOldTurnWorkspaceFinalizerAfterProcessExit() = fixture { old, oldProcesses, peer ->
        val turn = async { runCatching { old.turn("active") } }
        peer.next("session/prompt")
        oldProcesses.refreshGate = CompletableDeferred()
        unpreparedFixture(oldProcesses.root) { replacement, newProcesses ->
            val start = async { replacement.prepareSession("opencode", null, {}) }
            oldProcesses.refreshEntered.await()
            assertFalse(peer.process.isAlive)
            assertFalse(start.isCompleted)
            assertTrue(newProcesses.peers.isEmpty())
            oldProcesses.refreshGate!!.complete(Unit)
            start.await(); turn.await()
            assertEquals(1, newProcesses.peers.size)
        }
    }

    private fun cancelThenSend(explicitStop: Boolean) = fixture { host, processes, oldPeer ->
        val observed = CompletableDeferred<Unit>()
        val first = async { host.turn("first") { if (it is WorkspaceAgentEvent.Text) observed.complete(Unit) } }
        val oldPrompt = oldPeer.next("session/prompt")
        oldPeer.text("BEFORE")
        observed.await()
        first.cancel()
        if (explicitStop) host.cancel("opencode", "session_one")
        first.join()

        // This accepts either a proved-completed connection or retirement. Here the old prompt
        // deliberately has no terminal response, so a safe implementation must retire it.
        val retired = !oldPeer.process.isAlive
        val second = async { host.turn("second") }
        val current = if (retired) processes.nextPeer() else oldPeer
        val nextPrompt = current.next("session/prompt")
        if (!retired) {
            oldPeer.text("OLD")
            oldPeer.finish(oldPrompt, "cancelled")
        }
        current.text("NEW"); current.finish(nextPrompt)
        val result = second.await()
        assertEquals("Old turn output must not become the successor's answer", "NEW", result.text)
        assertTrue("No old response was observed: the original transport must be retired", retired)
        assertEquals("session_one", result.sessionId)
        assertEquals(listOf("session_one"), current.loadedIds)
        assertFalse("Reconnect must not silently create a fresh native context", "session/new" in current.methods)
    }

    @Test(timeout = 12_000) fun callerCancellationCannotMixOldOutputIntoNextTurn() = cancelThenSend(false)
    @Test(timeout = 12_000) fun explicitStopCannotMixOldOutputIntoNextTurn() = cancelThenSend(true)

    @Test(timeout = 12_000) fun normalCompletedTurnsReuseTheOriginalSession() = fixture { host, processes, peer ->
        for (label in listOf("FIRST", "SECOND")) {
            val turn = async { host.turn(label) }
            val request = peer.next("session/prompt")
            peer.text(label); peer.finish(request)
            assertEquals(label, turn.await().text)
        }
        assertEquals(1, processes.peers.size)
        assertTrue(peer.process.isAlive)
        assertEquals(listOf("initialize", "session/new", "session/prompt", "session/prompt"), peer.methods)
    }

    @Test(timeout = 12_000) fun actualAcknowledgedSteerKeepsTheOriginalSession() = fixture { host, processes, peer ->
        val first = async { host.turn("first") }
        val firstPrompt = peer.next("session/prompt")
        assertTrue(host.requestSteer("opencode", "session_one"))
        peer.text("TAIL")
        peer.next("session/cancel")
        peer.finish(firstPrompt, "cancelled")
        assertEquals("cancelled", first.await().stopReason)
        val second = async { host.turn("redirect") }
        val secondPrompt = peer.next("session/prompt")
        peer.text("REDIRECT"); peer.finish(secondPrompt)
        assertEquals("REDIRECT", second.await().text)
        assertEquals(1, processes.peers.size)
        assertTrue(peer.process.isAlive)
    }

    @Test(timeout = 12_000) fun disconnectedPromptCanLoadThePreservedNativeIdentity() = fixture { host, processes, peer ->
        val first = async { runCatching { host.turn("first") } }
        peer.next("session/prompt")
        peer.close()
        assertTrue(first.await().isFailure)
        val second = async { host.turn("second") }
        val replacement = processes.nextPeer()
        val secondPrompt = replacement.next("session/prompt")
        replacement.text("RESTORED"); replacement.finish(secondPrompt)
        assertEquals("RESTORED", second.await().text)
        assertEquals(listOf("session_one"), replacement.loadedIds)
        assertFalse("session/new" in replacement.methods)
        assertFalse(peer.process.isAlive)
    }

    @Test(timeout = 12_000) fun noLoadAdapterFailsExplicitlyInsteadOfStartingFreshAfterCancellation() = fixture { host, processes, peer ->
        val observed = CompletableDeferred<Unit>()
        val first = async { host.turn("first") { if (it is WorkspaceAgentEvent.Text) observed.complete(Unit) } }
        peer.next("session/prompt"); peer.text("BEFORE"); observed.await()
        first.cancelAndJoin()
        assertFalse("Unknown old-turn completion must retire the original connection", peer.process.isAlive)
        processes.supportsLoad = false
        val second = async { runCatching { host.turn("second") } }
        val replacement = processes.nextPeer()
        val failure = second.await().exceptionOrNull()
        assertNotNull("Unsupported resume must be visible", failure)
        assertTrue(failure!!.message.orEmpty().contains("cannot resume ACP sessions"))
        assertEquals(listOf("initialize"), replacement.methods)
        assertTrue(replacement.loadedIds.isEmpty())
        assertFalse(replacement.process.isAlive)
    }

}
