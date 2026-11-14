package com.baystudio.droide.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

 
internal object McpAgentOperations {
    suspend fun execute(
        args: JsonObject,
        c: AgentTools.Ctx,
        gate: suspend (action: String, resource: String) -> String?,
        gatePreview: suspend (action: String, resource: String, kind: String, summary: String, detail: String) -> String?,
    ): String {
        val server = args["server"]?.jsonPrimitive?.contentOrNull ?: return "ERROR: missing 'server'"
        val operation = args["operation"]?.jsonPrimitive?.contentOrNull
        val toolName = args["tool"]?.jsonPrimitive?.contentOrNull
        val manager = McpManager(c.workDir, c.pluginSource, c.mcpProcessHost)
        val repair = McpRepairEngine(c.workDir, c.pluginSource, c.mcpProcessHost)

        suspend fun approved(tool: String? = null, run: suspend (McpManager.ServerSelection) -> String): String {
            val selection = manager.select(server)
            gate("mcp", selection.permissionResource(tool))?.let { return it }
            return run(selection)
        }

        return when (operation) {
            "status" -> repair.status(server)
            "diagnose" -> repair.diagnose(server)
            "repair_config" -> {
                val patch = args["patch"] as? JsonObject ?: return "ERROR: MCP repair_config requires object 'patch'"
                val preview = repair.previewConfigRepair(server, patch)
                gatePreview("mcp_repair", preview.permissionResource, "mcp_repair", preview.summary, preview.detail)?.let { return it }
                repair.applyConfigRepair(preview)
            }
            "rollback" -> {
                val repairId = args["repair_id"]?.jsonPrimitive?.contentOrNull ?: return "ERROR: MCP rollback requires 'repair_id'"
                val preview = repair.previewRollback(server, repairId)
                gatePreview("mcp_repair", preview.permissionResource, "mcp_repair", preview.summary, preview.detail)?.let { return it }
                repair.applyRollback(preview)
            }
            "retry", "verify" -> approved { repair.verify(it, clearCatalog = false) }
            "refresh_catalog" -> approved { repair.verify(it, clearCatalog = true) }
            null, "" -> approved(toolName) { selection ->
                val legacy = args["input"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (toolName.isNullOrBlank()) manager.tool(selection, legacy)
                else manager.callTool(selection, toolName, (args["arguments"] as? JsonObject) ?: buildJsonObject {})
            }
            "list_tools" -> approved { manager.listTools(it) }
            "call_tool" -> {
                val selected = toolName ?: return "ERROR: MCP call_tool requires 'tool'"
                approved(selected) { manager.callTool(it, selected, (args["arguments"] as? JsonObject) ?: buildJsonObject {}) }
            }
            "list_resources", "list_resource_templates", "read_resource", "list_prompts", "get_prompt" -> {
                val parameters = buildJsonObject {
                    if (operation in setOf("list_resources", "list_resource_templates", "list_prompts")) {
                        args["cursor"]?.let { put("cursor", it) }
                    }
                    if (operation == "read_resource") put("uri", args["uri"] ?: return "ERROR: MCP read_resource requires 'uri'")
                    if (operation == "get_prompt") {
                        put("name", args["prompt"] ?: return "ERROR: MCP get_prompt requires 'prompt'")
                        args["arguments"]?.let { put("arguments", it) }
                    }
                }
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(parameters.toString().toByteArray())
                    .joinToString("") { "%02x".format(it) }
                approved("$operation:$digest") { manager.contextOperation(it, operation, parameters) }
            }
            else -> "ERROR: Unsupported MCP operation: $operation"
        }
    }
}
