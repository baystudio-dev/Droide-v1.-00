package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Candidate sources only, NOT compiled or run. Real host, fake process/workspace only. */
class WorkspaceAcpModeBoundaryTest {
    private class Peer : AutoCloseable {
        private val alive = AtomicBoolean(true)
        private val serverOutput = PipedOutputStream()
        private val clientOutput = PipedInputStream(serverOutput, 16_384)
        private val captured = ByteArrayOutputStream()
        val methods = CopyOnWriteArrayList<String>()
        val requestedModes = CopyOnWriteArrayList<String>()
        val modeRequested = CompletableDeferred<JsonObject>()
        var holdMode = false
        var rejectMode = false
        var reportWrongMode = false
        var omitModes = false
        var legacyModes = false
        var modeResultOverride: JsonElement? = null
        var current = "default"
        var initializeParams: JsonObject? = null
        private fun configuration(): JsonObject = buildJsonObject {
            put("sessionId", "mode_fixture")
            if (!omitModes && legacyModes) put("modes", buildJsonObject {
                put("currentModeId", current)
                put("availableModes", buildJsonArray {
                    listOf("default", "acceptEdits", "plan", "auto", "bypassPermissions").forEach {
                        add(buildJsonObject { put("id", it); put("name", it) })
                    }
                })
            })
            else if (!omitModes) put("configOptions", buildJsonArray {
                add(buildJsonObject {
                    put("id", "mode"); put("category", "mode"); put("type", "select")
                    put("currentValue", current)
                    put("options", buildJsonArray {
                        listOf("default", "acceptEdits", "plan", "auto", "bypassPermissions").forEach {
                            add(buildJsonObject { put("value", it); put("name", it) })
                        }
                    })
                })
            })
        }
        private val clientInput = object : OutputStream() {
            @Synchronized override fun write(value: Int) { captured.write(value) }
            @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) { captured.write(bytes, offset, length) }
            @Synchronized override fun flush() {
                val frames = captured.toString("UTF-8")
                if (frames.isEmpty()) return
                check(frames.endsWith('\n')); captured.reset()
                frames.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    val request = Json.parseToJsonElement(line).jsonObject
                    val method = request.getValue("method").jsonPrimitive.content
                    methods += method
                    when (method) {
                        "initialize" -> {
                            initializeParams = request.getValue("params").jsonObject
                            respond(request, buildJsonObject { put("protocolVersion", 1) })
                        }
                        "session/new" -> respond(request, configuration())
                        "session/set_config_option", "session/set_mode" -> {
                            val params = request.getValue("params").jsonObject
                            requestedModes += params.getValue(if (method == "session/set_mode") "modeId" else "value").jsonPrimitive.content
                            modeRequested.complete(request)
                            if (!holdMode) finishMode(request)
                        }
                        "session/prompt" -> respond(request, buildJsonObject { put("stopReason", "end_turn") })
                        "session/cancel" -> Unit
                        else -> error("Unexpected fake request: $method")
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
            override suspend fun awaitExit() = 0
            override fun close() { this@Peer.close() }
        }
        fun finishMode(request: JsonObject) {
            if (modeResultOverride != null) emit(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", request.getValue("id")); put("result", modeResultOverride!!)
            }) else if (rejectMode) emit(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", request.getValue("id"))
                put("error", buildJsonObject { put("code", -32602); put("message", "Mode rejected") })
            }) else {
                if (!reportWrongMode) {
                    val params = request.getValue("params").jsonObject
                    current = (params["modeId"] ?: params.getValue("value")).jsonPrimitive.content
                }
                respond(request, configuration())
            }
        }
        private fun respond(request: JsonObject, result: JsonObject) = emit(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", request.getValue("id")); put("result", result)
        })
        @Synchronized private fun emit(value: JsonObject) {
            serverOutput.write((value.toString() + "\n").toByteArray()); serverOutput.flush()
        }
        override fun close() {
            if (alive.compareAndSet(true, false)) {
                runCatching { serverOutput.close() }; runCatching { clientOutput.close() }
            }
        }
    }

    private fun fixture(block: suspend CoroutineScope.(WorkspaceAgentPluginHost, Peer) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-mode-boundary-").toFile()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> uncaught += error })
        val peer = Peer()
        val processes = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = "/fixture/$command"
            override suspend fun pathMapper() = WorkspacePathMapper(root, root.canonicalPath)
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                check(argv == listOf("/fixture/claude-agent-acp"))
                currentCoroutineContext()[SessionHostedProcessOwner]?.retain(peer.process)
                return peer.process
            }
        }
        val io = object : AgentWorkspaceIo {
            override val checksumCommand = "fixture-checksum"
            override suspend fun baselineEntries(): Map<String, WorkspaceSyncManifest.Entry> = emptyMap()
            override fun requirePath(path: String) { check(path == root.canonicalPath) }
            override suspend fun refresh() = Unit
            override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult {
                check(command.contains("fixture-checksum"))
                return BridgeShellResult(0, "", "")
            }
            override suspend fun pull(path: String, output: File) = error("No transfer permitted")
            override suspend fun push(input: File, path: String) = error("No transfer permitted")
        }
        val host = WorkspaceAgentPluginHost(root, File(root, "cache"), FileRepository(root), processes,
            ApprovalManager(), PermissionEngine(), scope, io)
        try { withTimeout(7_000) { block(host, peer) } }
        finally {
            host.closeAndAwait(); peer.close()
            withTimeout(2_000) { scope.coroutineContext.job.cancelAndJoin() }
            root.deleteRecursively()
            assertTrue("Uncaught host/transport failure: $uncaught", uncaught.isEmpty())
        }
    }

    private suspend fun WorkspaceAgentPluginHost.turn(mode: WorkspaceAgentMode) =
        runMediaTurn("claude", "Inspect the fixture", emptyList(), null, mode, null, {})

    @Test(timeout = 12_000) fun planThenEditUsesSameSessionAndKeepsAdvertisedClientTools() = fixture { host, peer ->
        host.turn(WorkspaceAgentMode.PLAN)
        host.turn(WorkspaceAgentMode.EDIT)
        assertEquals(listOf("plan", "default"), peer.requestedModes)
        assertEquals(1, peer.methods.count { it == "session/new" })
        assertEquals(2, peer.methods.count { it == "session/prompt" })
        val caps = peer.initializeParams!!.getValue("clientCapabilities").jsonObject
        assertTrue(caps.getValue("terminal").jsonPrimitive.boolean)
        assertTrue(caps.getValue("fs").jsonObject.getValue("readTextFile").jsonPrimitive.boolean)
        assertTrue(caps.getValue("fs").jsonObject.getValue("writeTextFile").jsonPrimitive.boolean)
    }

    @Test(timeout = 12_000) fun rejectedNativeModeSendsNoPromptAndRetiresTheTransport() = fixture { host, peer ->
        peer.rejectMode = true
        assertTrue(runCatching { host.turn(WorkspaceAgentMode.PLAN) }.isFailure)
        assertFalse("session/prompt" in peer.methods)
        assertFalse(peer.process.isAlive)
    }

    @Test(timeout = 12_000) fun contradictoryModeSnapshotSendsNoPromptAndRetiresTheTransport() = fixture { host, peer ->
        peer.reportWrongMode = true
        assertTrue(runCatching { host.turn(WorkspaceAgentMode.PLAN) }.isFailure)
        assertFalse("session/prompt" in peer.methods)
        assertFalse(peer.process.isAlive)
    }

    @Test(timeout = 12_000) fun unsupportedModeMetadataFailsExplicitlyBeforePrompt() = fixture { host, peer ->
        peer.omitModes = true
        assertTrue(runCatching { host.turn(WorkspaceAgentMode.PLAN) }.isFailure)
        assertFalse("session/prompt" in peer.methods)
        assertTrue(peer.requestedModes.isEmpty())
    }

    @Test(timeout = 12_000) fun stopDuringModeTransitionCannotRegrantEditOrSendPrompt() = fixture { host, peer ->
        peer.current = "plan"
        peer.holdMode = true
        val turn = async { runCatching { host.turn(WorkspaceAgentMode.EDIT) } }
        val request = peer.modeRequested.await()
        host.cancel("claude", "mode_fixture")
        peer.finishMode(request)
        assertTrue(turn.await().isFailure)
        assertTrue("session/cancel" in peer.methods)
        assertFalse("session/prompt" in peer.methods)
    }

    @Test(timeout = 12_000) fun malformedLegacyModeAcknowledgementCannotConfirmOrAdmitPrompt() = fixture { host, peer ->
        peer.legacyModes = true
        peer.modeResultOverride = JsonNull
        assertTrue(runCatching { host.turn(WorkspaceAgentMode.PLAN) }.isFailure)
        assertTrue("session/set_mode" in peer.methods)
        assertFalse("session/prompt" in peer.methods)
        assertFalse(peer.process.isAlive)
    }
    @Test(timeout = 12_000) fun modeLessAdapterCanEditWithoutInventingModeOrDroppingClientTools() = fixture { host, peer ->
        peer.omitModes = true
        host.turn(WorkspaceAgentMode.EDIT)
        host.turn(WorkspaceAgentMode.EDIT)
        assertEquals(2, peer.methods.count { it == "session/prompt" })
        assertEquals(1, peer.methods.count { it == "session/new" })
        assertTrue(peer.requestedModes.isEmpty())
        val caps = peer.initializeParams!!.getValue("clientCapabilities").jsonObject
        assertTrue(caps.getValue("terminal").jsonPrimitive.boolean)
        assertTrue(caps.getValue("fs").jsonObject.getValue("writeTextFile").jsonPrimitive.boolean)
    }

}
