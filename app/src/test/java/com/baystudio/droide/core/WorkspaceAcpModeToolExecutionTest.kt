package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Only the ACP provider and OS launch adapter are fixtures. */

class WorkspaceAcpModeToolExecutionTest {
    private class Peer(val provider: String) : AutoCloseable {
        val sid = "actual_tools_fixture"
        val requests = CopyOnWriteArrayList<JsonObject>()
        val replies = CopyOnWriteArrayList<JsonObject>()
        val prompts = Channel<JsonObject>(Channel.UNLIMITED)
        val heldModes = Channel<JsonObject>(Channel.UNLIMITED)
        val transcript = CopyOnWriteArrayList<JsonObject>()
        private val calls = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
        private val ids = AtomicLong(1)
        private val alive = AtomicBoolean(true)
        private val peerOut = PipedOutputStream()
        private val clientOut = PipedInputStream(peerOut, 131_072)
        private val captured = ByteArrayOutputStream()
        var current = "default"
        @Volatile var holdMode = false
        private val codex = if (provider == "codex") requireNotNull(javaClass.getResourceAsStream("/agent-acp/codex-session.json"))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject } else null

        private fun configuration(): JsonObject {
            if (codex != null) return JsonObject(codex.toMutableMap().apply {
                put("sessionId", JsonPrimitive(sid))
                put("configOptions", JsonArray(codex.getValue("configOptions").jsonArray.map { raw ->
                    val option = raw.jsonObject
                    if (option["id"]?.jsonPrimitive?.content == "collaboration_mode")
                        JsonObject(option.toMutableMap().apply { put("currentValue", JsonPrimitive(current)) }) else option
                }))
            })
            return buildJsonObject {
                put("sessionId", sid)
                put("configOptions", buildJsonArray { add(buildJsonObject {
                    put("id", "mode"); put("category", "mode"); put("type", "select"); put("currentValue", current)
                    put("options", buildJsonArray { listOf("default", "plan").forEach { value ->
                        add(buildJsonObject { put("value", value); put("name", value) })
                    } })
                }) })
            }
        }
        val process = object : HostedStdioProcess {
            override val stdin = object : OutputStream() {
                @Synchronized override fun write(value: Int) { captured.write(value) }
                @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) { captured.write(bytes, offset, length) }
                @Synchronized override fun flush() {
                    val text = captured.toString("UTF-8")
                    if (text.isEmpty()) return
                    check(text.endsWith('\n')); captured.reset()
                    text.lineSequence().filter(String::isNotBlank).forEach { line ->
                        val frame = Json.parseToJsonElement(line).jsonObject
                        record("host_to_peer", frame)
                        val method = frame["method"]?.jsonPrimitive?.contentOrNull
                        if (method == null) {
                            replies += frame
                            val id = frame.getValue("id").jsonPrimitive.content
                            checkNotNull(calls.remove(id)) { "Unexpected or duplicate response $id" }.complete(frame)
                        } else {
                            requests += frame
                            when (method) {
                                "initialize" -> respond(frame, buildJsonObject { put("protocolVersion", 1) })
                                "session/new" -> respond(frame, configuration())
                                "session/set_config_option" -> if (holdMode) check(heldModes.trySend(frame).isSuccess) else finishMode(frame)
                                "session/prompt" -> check(prompts.trySend(frame).isSuccess)
                                "session/cancel" -> Unit
                                else -> error("Unexpected provider request: $method")
                            }
                        }
                    }
                }
            }
            override val stdout = clientOut
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive get() = alive.get()
            override val exitCode get() = if (isAlive) null else 0
            override suspend fun awaitExit() = 0
            override fun close() = this@Peer.close()
        }
        fun finishMode(request: JsonObject) {
            val params = request.getValue("params").jsonObject
            check(params.getValue("configId").jsonPrimitive.content == if (provider == "codex") "collaboration_mode" else "mode")
            current = params.getValue("value").jsonPrimitive.content
            respond(request, configuration())
        }
        fun finishPrompt(prompt: JsonObject) = respond(prompt, buildJsonObject { put("stopReason", "end_turn") })
        suspend fun call(method: String, body: JsonObjectBuilder.() -> Unit): JsonObject {
            val id = "actual-tool-${ids.getAndIncrement()}"
            val completion = CompletableDeferred<JsonObject>()
            check(calls.putIfAbsent(id, completion) == null)
            emit(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id); put("method", method)
                put("params", buildJsonObject { put("sessionId", sid); body() })
            })
            return withTimeout(7_000) { completion.await() }
        }
        private fun respond(request: JsonObject, result: JsonObject) = emit(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", request.getValue("id")); put("result", result)
        })
        private fun record(direction: String, frame: JsonObject) {
            transcript += buildJsonObject { put("direction", direction); put("frame", frame) }
        }
        @Synchronized private fun emit(frame: JsonObject) {
            record("peer_to_host", frame)
            peerOut.write((frame.toString() + "\n").toByteArray()); peerOut.flush()
        }
        override fun close() {
            if (alive.compareAndSet(true, false)) { runCatching { peerOut.close() }; runCatching { clientOut.close() } }
        }
    }

    private class Fixture(val provider: String, val label: String) {
        val root = Files.createTempDirectory("droide-actual-mode-tools-").toFile()
        val project = File(root, "project").apply { mkdirs() }
        val cache = File(root, "cache").apply { mkdirs() }
        val errors = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> errors += t })
        val peer = Peer(provider)
        val local = LocalAgentWorkspaceProcessHost(project, cache, scope, File(root, "workspaces"))
        val approvals = ApprovalManager()
        val permissions = PermissionEngine(listOf("read", "write_file", "run_command", "edit_workspace").map { PermRule(it, "*", PermEffect.ALLOW) })
        val starts = CopyOnWriteArrayList<List<String>>()
        val processes = CopyOnWriteArrayList<HostedStdioProcess>()
        val allowedScripts = ConcurrentHashMap.newKeySet<String>()
        var providerStarts = 0
        lateinit var working: File
        val hostAdapter = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun pathMapper() = local.pathMapper()
            override suspend fun resolveExecutable(command: String): String? = when (command) {
                "claude-agent-acp", "codex-acp" -> "/fixture/$command"
                "sh" -> "/bin/sh"
                else -> error("No executable outside the fixture allowlist: $command")
            }
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                check(argv == listOf(if (provider == "codex") "/fixture/codex-acp" else "/fixture/claude-agent-acp"))
                check(++providerStarts == 1)
                currentCoroutineContext()[SessionHostedProcessOwner]?.retain(peer.process)
                return peer.process
            }
            override suspend fun startInWorkspace(argv: List<String>, environment: Map<String, String>, remoteCwd: String): HostedStdioProcess {
                check(argv.size == 3 && argv.take(2) == listOf("/bin/sh", "-c") && argv[2] in allowedScripts)
                check(environment.isEmpty())
                check(File(remoteCwd).canonicalFile == working.canonicalFile)
                starts += argv
                val raw = ProcessBuilder(argv).directory(working).apply {
                    environment().clear(); environment()["PATH"] = "/usr/bin:/bin"; environment()["LANG"] = "C.UTF-8"
                }.start()
                val process = LocalHostedProcess(raw, scope) {}
                processes += process
                currentCoroutineContext()[SessionHostedProcessOwner]?.retain(process)
                return process
            }
        }
        val host = WorkspaceAgentPluginHost(project, cache, FileRepository(project), hostAdapter, approvals, permissions, scope, local.workspaceIo)
        suspend fun start() {
            File(project, "fixture.txt").writeText("seed\n")
            local.workspaceIo.refresh()
            working = File(local.pathMapper().remoteRoot)
        }
        fun script(tag: String): String {
            check(tag.matches(Regex("[a-z-]+")))
            return "printf '%s\\n' '$tag' > terminal-$tag.txt; cat terminal-$tag.txt".also { allowedScripts += it }
        }
        suspend fun turn(mode: WorkspaceAgentMode) = host.runMediaTurn(provider, "Exercise local fixture $label", emptyList(), null, mode, null, {})
        suspend fun read() = peer.call("fs/read_text_file") { put("path", File(working, "fixture.txt").path) }
        suspend fun write(text: String) = peer.call("fs/write_text_file") { put("path", File(working, "fixture.txt").path); put("content", text) }
        suspend fun terminal(tag: String) = peer.call("terminal/create") {
            put("command", "sh"); put("args", buildJsonArray { add("-c"); add(script(tag)) }); put("cwd", working.path)
        }
        suspend fun blockingTerminal() = peer.call("terminal/create") {
            val command = "printf 'waiting-for-kill\\n'; exec sleep 30"
            allowedScripts += command
            put("command", "sh"); put("args", buildJsonArray { add("-c"); add(command) }); put("cwd", working.path)
        }
        suspend fun terminalMethod(method: String, id: String) = peer.call(method) { put("terminalId", id) }
        suspend fun close() {
            try {
                withTimeout(10_000) { host.closeAndAwait() }

                assertTrue("Production host shutdown leaked a real subprocess", processes.none { it.isAlive })
                assertNull(approvals.pending.value)
            } finally {
                processes.forEach { it.close(); withTimeout(3_000) { it.awaitExit() } }
                peer.close()
                withTimeout(5_000) { scope.coroutineContext.job.cancelAndJoin() }
                System.getProperty("droide.mode.evidence")?.let { path ->
                    File(path, "$label.ndjson").writeText(peer.transcript.joinToString("\n") + "\n")
                    File(path, "$label-files.json").writeText(buildJsonObject {
                        put("providerStarts", providerStarts); put("terminalStarts", starts.size)
                        put("project", buildJsonObject { project.walkTopDown().filter { it.isFile && it.name.endsWith(".txt") }.forEach { put(it.relativeTo(project).invariantSeparatorsPath, it.readText()) } })
                        put("execution", buildJsonObject { working.walkTopDown().filter { it.isFile && it.name.endsWith(".txt") }.forEach { put(it.relativeTo(working).invariantSeparatorsPath, it.readText()) } })
                    }.toString())
                }
                PathSecurity.deleteTreeNoFollow(root)
                assertTrue("Unexpected background error: $errors", errors.isEmpty())
            }
        }
    }
    private fun fixture(provider: String, suffix: String, block: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        val f = Fixture(provider, "$provider-$suffix")
        try { withTimeout(35_000) { f.start(); block(f) } } finally { f.close() }
    }
    private fun result(frame: JsonObject): JsonObject {
        assertNull("Unexpected tool error: $frame", frame["error"])
        return frame.getValue("result").jsonObject
    }
    private fun denied(frame: JsonObject) {
        assertNull(frame["result"])
        assertTrue("Expected read-only mode denial: $frame", frame.getValue("error").jsonObject.getValue("message").jsonPrimitive.content.contains("read-only"))
    }
    private suspend fun proveTools(f: Fixture, tag: String) {
        val content = "$tag file write\n"
        result(f.write(content))
        assertEquals(content, File(f.project, "fixture.txt").readText())
        assertEquals(content, File(f.working, "fixture.txt").readText())
        assertEquals(content, result(f.read()).getValue("content").jsonPrimitive.content)
        val terminalId = result(f.terminal(tag)).getValue("terminalId").jsonPrimitive.content
        assertEquals(0, result(f.terminalMethod("terminal/wait_for_exit", terminalId)).getValue("exitCode").jsonPrimitive.int)
        val output = result(f.terminalMethod("terminal/output", terminalId))
        assertEquals("$tag\n", output.getValue("output").jsonPrimitive.content)
        assertFalse(output.getValue("truncated").jsonPrimitive.boolean)
        assertEquals(0, output.getValue("exitStatus").jsonObject.getValue("exitCode").jsonPrimitive.int)
        assertEquals("$tag\n", File(f.working, "terminal-$tag.txt").readText())
        result(f.terminalMethod("terminal/release", terminalId))
        // Exercise kill on an actually live OS process, not just an already-ended handle.
        val liveId = result(f.blockingTerminal()).getValue("terminalId").jsonPrimitive.content
        assertTrue("Kill fixture exited before terminal/kill", f.processes.last().isAlive)
        result(f.terminalMethod("terminal/kill", liveId))
        val killed = result(f.terminalMethod("terminal/wait_for_exit", liveId))
        assertTrue("Killed process must not report a successful normal exit", killed.getValue("exitCode").jsonPrimitive.int != 0)
        assertFalse("terminal/kill did not reap its real process", f.processes.last().isAlive)
        result(f.terminalMethod("terminal/release", liveId))
    }
    private fun roundTrip(provider: String) = fixture(provider, "roundtrip") { f ->
        val before = async { f.turn(WorkspaceAgentMode.EDIT) }
        val beforePrompt = f.peer.prompts.receive()
        proveTools(f, "before-plan")
        f.peer.finishPrompt(beforePrompt)
        val beforeResult = before.await()
        assertTrue(beforeResult.changedFiles.containsAll(listOf("fixture.txt", "terminal-before-plan.txt")))
        assertTrue(beforeResult.conflicts.isEmpty())
        assertEquals("before-plan\n", File(f.project, "terminal-before-plan.txt").readText())
        assertEquals(2, f.starts.size)

        val plan = async { f.turn(WorkspaceAgentMode.PLAN) }
        val planPrompt = f.peer.prompts.receive()
        assertEquals("plan", f.peer.current)
        assertEquals("before-plan file write\n", result(f.read()).getValue("content").jsonPrimitive.content)
        denied(f.write("MUST NOT WRITE")); denied(f.terminal("during-plan"))
        assertEquals("before-plan file write\n", File(f.project, "fixture.txt").readText())
        assertEquals("before-plan file write\n", File(f.working, "fixture.txt").readText())
        assertFalse(File(f.working, "terminal-during-plan.txt").exists())
        assertEquals(2, f.starts.size)
        f.peer.finishPrompt(planPrompt)
        val planResult = plan.await()
        assertTrue(planResult.changedFiles.isEmpty())
        assertTrue(planResult.conflicts.isEmpty())

        val after = async { f.turn(WorkspaceAgentMode.EDIT) }
        val afterPrompt = f.peer.prompts.receive()
        assertEquals("default", f.peer.current)
        proveTools(f, "after-plan")
        f.peer.finishPrompt(afterPrompt)
        val afterResult = after.await()
        assertTrue(afterResult.changedFiles.containsAll(listOf("fixture.txt", "terminal-after-plan.txt")))
        assertTrue(afterResult.conflicts.isEmpty())
        assertEquals("after-plan\n", File(f.project, "terminal-after-plan.txt").readText())
        assertEquals(4, f.starts.size)
        assertEquals(1, f.providerStarts)
        val modes = f.peer.requests.filter { it["method"]?.jsonPrimitive?.content == "session/set_config_option" }
        assertEquals(listOf("plan", "default"), modes.map { it.getValue("params").jsonObject.getValue("value").jsonPrimitive.content })
        assertEquals(3, f.peer.requests.count { it["method"]?.jsonPrimitive?.content == "session/prompt" })
        // A successful Build turn must not leave mutation authority open between turns.
        denied(f.write("STALE WRITE")); denied(f.terminal("after-completion"))
        assertEquals(4, f.starts.size)
        assertEquals("after-plan file write\n", File(f.project, "fixture.txt").readText())
        assertEquals("after-plan file write\n", File(f.working, "fixture.txt").readText())
    }
    private fun heldAcknowledgment(provider: String) = fixture(provider, "held-ack") { f ->
        val plan = async { f.turn(WorkspaceAgentMode.PLAN) }
        val planPrompt = f.peer.prompts.receive(); f.peer.finishPrompt(planPrompt)
        val planResult = plan.await()
        assertTrue(planResult.changedFiles.isEmpty())
        assertTrue(planResult.conflicts.isEmpty())
        f.peer.holdMode = true
        val build = async { f.turn(WorkspaceAgentMode.EDIT) }
        val mode = f.peer.heldModes.receive()
        assertEquals("default", mode.getValue("params").jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals("plan", f.peer.current)
        assertEquals(1, f.peer.requests.count { it["method"]?.jsonPrimitive?.content == "session/prompt" })
        denied(f.write("UNCONFIRMED WRITE")); denied(f.terminal("before-ack"))
        assertTrue(f.starts.isEmpty())
        assertEquals("seed\n", File(f.project, "fixture.txt").readText())
        assertEquals("seed\n", File(f.working, "fixture.txt").readText())
        assertFalse(File(f.working, "terminal-before-ack.txt").exists())
        assertFalse(build.isCompleted)

        assertEquals(1, f.peer.requests.count { it["method"]?.jsonPrimitive?.content == "session/prompt" })
        f.peer.finishMode(mode)
        val buildPrompt = f.peer.prompts.receive()
        proveTools(f, "after-ack")
        f.peer.finishPrompt(buildPrompt)
        assertTrue(build.await().changedFiles.containsAll(listOf("fixture.txt", "terminal-after-ack.txt")))
        assertEquals(1, f.providerStarts)
        assertEquals(2, f.starts.size)
        assertEquals("after-ack\n", File(f.project, "terminal-after-ack.txt").readText())
    }
    @Test(timeout = 45_000) fun claudeBuildPlanBuildExecutesRealFileAndTerminalTools() = roundTrip("claude")
    @Test(timeout = 45_000) fun codexBuildPlanBuildExecutesRealFileAndTerminalTools() = roundTrip("codex")
    @Test(timeout = 45_000) fun claudeBuildToolsRemainDeniedUntilModeAcknowledged() = heldAcknowledgment("claude")
    @Test(timeout = 45_000) fun codexBuildToolsRemainDeniedUntilCollaborationAcknowledged() = heldAcknowledgment("codex")
}
