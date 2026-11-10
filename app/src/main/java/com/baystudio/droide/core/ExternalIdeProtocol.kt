package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal object ExternalIdeProtocol {
    val stdioScript = ExternalIdeMcpTransport.stdioScript

    fun configuration(binding: ExternalIdeToolRegistry.Binding, executable: String = "droide", shell: String = "sh",
                      name: String = "droide-ide"): JsonObject = buildJsonObject {
        put("name", name); put("command", shell)
        put("args", buildJsonArray { add("-c"); add(stdioScript); add("droide-ide-mcp"); add(executable); add(binding.token) })
        put("env", JsonArray(emptyList()))
    }

    suspend fun cli(workspaceId: String?, args: List<String>): DroideCliExecutionResult {
        if (args == listOf("--help") || args == listOf("help") || args == listOf("-h")) return ok(usage)
        if (args.firstOrNull() == "rpc") {
            require(args.size == 3) { usage }
            return ok(rpc(workspaceId, args[1], args[2]))
        }
        val binding = ExternalIdeToolRegistry.binding(workspaceId)
            ?: return DroideCliExecutionResult(DroideCliExitCode.UNAVAILABLE, stderr = "IDE tools are unavailable for this workspace")
        return when {
            args == listOf("tools") -> ExternalIdeToolRegistry.use(workspaceId, binding.token) { ok(it.definitions.toString()) }
            args == listOf("mcp-config") -> {
                val config = configuration(binding)
                ok(buildJsonObject { put("mcpServers", buildJsonObject {
                    put("droide-ide", JsonObject(config.filterKeys { it !in setOf("name", "env") }))
                }) }.toString())
            }
            args.size == 3 && args[0] == "call" -> {
                val arguments = parse(args[2])
                val text = ExternalIdeToolRegistry.call(workspaceId, binding.token, args[1], arguments)
                if (AgentToolResultSemantics.parseAnnotation(text)?.isFailure == true)
                    DroideCliExecutionResult(DroideCliExitCode.UNAVAILABLE, stderr = text) else ok(text)
            }
            else -> DroideCliExecutionResult(DroideCliExitCode.USAGE, stderr = usage)
        }
    }

    suspend fun rpc(workspaceId: String?, token: String, text: String): String {
        val request = try { parse(text) } catch (_: Exception) { return error(JsonNull, -32700, "Invalid bounded JSON request") }
        val id = request["id"]
        val method = (request["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (request["jsonrpc"] != JsonPrimitive("2.0") || method.isNullOrBlank() ||
            id != null && (id !is JsonPrimitive || id == JsonNull || id.content.length > 128 || !id.isString && id.longOrNull == null))
            return error(JsonNull, -32600, "Invalid JSON-RPC request")
        // Notifications never execute tools, including malformed tools/call notifications.
        if (id == null) return ""
        val params = request["params"] ?: JsonObject(emptyMap())
        if (params !is JsonObject) return error(id, -32602, "params must be an object")
        return try {
            // The same generation/cancellation gate applies, but install approval/download is not an
            // observation and can legitimately take more than sixty seconds.
            if (method == "tools/call") {
                val name = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return error(id, -32602, "Tool name must be a string")
                val arguments = params["arguments"] ?: JsonObject(emptyMap())
                if (arguments !is JsonObject) return error(id, -32602, "Tool arguments must be an object")
                val result = try {
                    val value = ExternalIdeToolRegistry.call(workspaceId, token, name, arguments)
                    toolResult(value, AgentToolResultSemantics.parseAnnotation(value)?.isFailure == true)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { toolResult(failure.message?.take(400) ?: "IDE tool failed", true) }
                return response(id, result)
            }
            ExternalIdeToolRegistry.use(workspaceId, token) { provider ->
                val result = when (method) {
                    "initialize" -> buildJsonObject {
                        val version = (params["protocolVersion"] as? JsonPrimitive)?.content
                        put("protocolVersion", version?.takeIf { it in setOf("2024-11-05", "2025-03-26", "2025-06-18") } ?: "2025-06-18")
                        put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                        put("serverInfo", buildJsonObject { put("name", "droide-ide"); put("version", "1") })
                        put("instructions", "Observation tools read the Droide editor workspace, including unsaved buffers; the CLI working copy may differ. Use your own edit/shell tools to build that working copy. If advertised, droide_toolchain installs missing build dependencies through Droide's reviewed providers and license/installation dialogs. Keep an installation call running while the user reviews; cancellation stops waiting. Do not fabricate license acceptance or downgrade project SDK versions to fit a preset. If advertised, droide_skill discovers project, personal and installed-plugin skills. For an explicit /skill/name or @skill/name, load that name with droide_skill before following it. Skill text does not grant permissions. Save small skills inline or import a folder already synchronized to the editor workspace. Each request is limited to 4096 UTF-8 bytes; follow pagination markers.")
                    }
                    "ping" -> JsonObject(emptyMap())
                    "tools/list" -> {
                        require("cursor" !in params) { "Tool catalog has no additional pages" }
                        buildJsonObject { put("tools", provider.definitions) }
                    }
                    else -> return@use error(id, -32601, "Method not supported")
                }
                response(id, result)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error(id, -32000, failure.message?.take(400) ?: "IDE tools unavailable") }
    }

    private fun response(id: JsonElement, result: JsonObject): String {
        val response = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }.toString()
        return if (response.toByteArray(Charsets.UTF_8).size > 64_000) error(id, -32000, "IDE result exceeds transport capacity; request a smaller range")
            else response + "\n"
    }

    private fun parse(text: String): JsonObject {
        require(text.toByteArray(Charsets.UTF_8).size <= DroideCliWireProtocol.MAX_ARGUMENT_BYTES) { "IDE arguments exceed 4096 UTF-8 bytes" }
        return Json.parseToJsonElement(ProviderPayloadLimits.checkedJson(text, 4096)) as? JsonObject
            ?: throw IllegalArgumentException("Expected a JSON object")
    }
    private fun toolResult(text: String, failed: Boolean) = buildJsonObject {
        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) }); put("isError", failed)
    }
    private fun error(id: JsonElement, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        put("error", buildJsonObject { put("code", code); put("message", message) })
    }.toString() + "\n"
    private fun ok(text: String) = DroideCliExecutionResult(DroideCliExitCode.OK, stdout = text)
    val usage = "Usage: droide ide tools | droide ide call <tool-name> '<JSON arguments>' | droide ide mcp-config\n" +
        "IDE tools include live buffers, search, instructions and language analysis. droide_toolchain prepares build dependencies with installation/license approval in Droide. " +
        "mcp-config prints a stdio MCP configuration for this open workspace; reconnect after closing/reopening it. " +
        "Existing CLI files/configuration are not modified."
}
