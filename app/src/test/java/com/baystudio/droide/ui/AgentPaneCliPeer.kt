package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.*

/** Actual ACP host/transport with a deterministic in-memory peer; no CLI or Linux process runs. */
internal class AgentPaneCliPeer(
    root: File,
    private val spec: WorkspaceAgentPluginSpec,
    scope: CoroutineScope,
    private val availableCommands: List<String> = emptyList(),
) : AutoCloseable {
    val sessionId = "pane_${spec.id}"
    val starts = AtomicInteger()
    val methods = CopyOnWriteArrayList<String>()
    val prompts = CopyOnWriteArrayList<JsonObject>()
    val cancellations = CopyOnWriteArrayList<JsonObject>()
    private val alive = AtomicBoolean(true)
    private val output = PipedOutputStream()
    private val input = PipedInputStream(output, 8_192)
    private val captured = ByteArrayOutputStream()
    private val writer = object : OutputStream() {
        @Synchronized override fun write(value: Int) { captured.write(value) }
        @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) { captured.write(bytes, offset, length) }
        @Synchronized override fun flush() {
            val text = captured.toString("UTF-8")
            if (text.isEmpty()) return
            check(text.endsWith('\n'))
            captured.reset()
            text.lineSequence().filter(String::isNotBlank).forEach { line ->
                val request = Json.parseToJsonElement(line).jsonObject
                val method = request.getValue("method").jsonPrimitive.content
                methods += method
                when (method) {
                    "initialize" -> respond(request, buildJsonObject {
                        put("protocolVersion", 1)
                        put("agentCapabilities", buildJsonObject { put("loadSession", true) })
                    })
                    "session/new" -> {
                        emit(buildJsonObject {
                            put("jsonrpc", "2.0"); put("method", "session/update")
                            put("params", buildJsonObject {
                                put("sessionId", sessionId)
                                put("update", buildJsonObject {
                                    put("sessionUpdate", "available_commands_update")
                                    put("availableCommands", buildJsonArray {
                                        availableCommands.forEach { name -> add(buildJsonObject { put("name", name) }) }
                                    })
                                })
                            })
                        })
                        respond(request, buildJsonObject {
                            put("sessionId", sessionId)
                            // Keep the active edit profile without assuming a vendor-specific mode ID.
                            put("modes", buildJsonObject {
                                put("currentModeId", "fixture-edit")
                                put("availableModes", buildJsonArray {
                                    add(buildJsonObject { put("id", "fixture-edit"); put("name", "Fixture edit") })
                                })
                            })
                        })
                    }
                    "session/prompt" -> prompts += request
                    "session/cancel" -> cancellations += request
                    else -> error("Unexpected ACP fixture request: $method")
                }
            }
        }
    }
    val process = object : HostedStdioProcess {
        override val stdin = writer
        override val stdout = input
        override val stderr = ByteArrayInputStream(byteArrayOf())
        override val isAlive get() = alive.get()
        override val exitCode get() = if (alive.get()) null else 0
        override suspend fun awaitExit() = 0
        override fun close() = closePeer()
    }
    private val processes = object : StdioProcessHost {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        override suspend fun resolveExecutable(command: String): String? =
            if (command == spec.command || command == spec.cliCommand) "/fixture/$command" else null
        override suspend fun pathMapper() = WorkspacePathMapper(root, root.canonicalPath)
        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
            check(argv == listOf("/fixture/${spec.command}") + spec.args)
            check(starts.incrementAndGet() == 1) { "The panel must reuse the prepared CLI process" }
            return process
        }
    }
    private val workspaceIo = object : AgentWorkspaceIo {
        override val checksumCommand = "fixture-checksum"
        override suspend fun baselineEntries(): Map<String, WorkspaceSyncManifest.Entry> = emptyMap()
        override fun requirePath(path: String) { check(path == root.canonicalPath) }
        override suspend fun refresh() = Unit
        override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult {
            check(command.contains("fixture-checksum"))
            check(maxOutputBytes == 2_000_000)
            return BridgeShellResult(0, "", "")
        }
        override suspend fun pull(path: String, output: File) = error("No file transfer in this fixture")
        override suspend fun push(input: File, path: String) = error("No file transfer in this fixture")
    }
    val host = WorkspaceAgentPluginHost(root, File(root, "cli-cache"), FileRepository(root), processes,
        ApprovalManager(), PermissionEngine(), scope, workspaceIo)

    fun promptSession(request: JsonObject) = request.getValue("params").jsonObject.getValue("sessionId").jsonPrimitive.content
    fun promptText(request: JsonObject) = request.getValue("params").jsonObject.getValue("prompt").jsonArray
        .joinToString("\n") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }

    fun finish(request: JsonObject, stop: String = "end_turn") =
        respond(request, buildJsonObject { put("stopReason", stop) })

    fun text(value: String) = emit(buildJsonObject {
        put("jsonrpc", "2.0"); put("method", "session/update")
        put("params", buildJsonObject {
            put("sessionId", sessionId)
            put("update", buildJsonObject {
                put("sessionUpdate", "agent_message_chunk")
                put("content", buildJsonObject { put("type", "text"); put("text", value) })
            })
        })
    })

    private fun respond(request: JsonObject, result: JsonObject) = emit(buildJsonObject {
        put("jsonrpc", "2.0"); put("id", request.getValue("id")); put("result", result)
    })

    @Synchronized private fun emit(frame: JsonObject) {
        val bytes = (frame.toString() + "\n").toByteArray()
        check(bytes.size < 1_024)
        output.write(bytes)
        output.flush()
    }

    private fun closePeer() {
        if (alive.compareAndSet(true, false)) {
            runCatching { output.close() }; runCatching { input.close() }
        }
    }

    override fun close() { host.close(); closePeer() }
}
