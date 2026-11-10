package com.baystudio.droide.core

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException

val AgentService.mcpHealth: StateFlow<List<McpHealthSnapshot>>
    get() = McpHealthRegistry.observe(workDir)

fun AgentService.syncMcpHealth() {
    McpManager(workDir, mcpPluginSource(), mcpProcessHost).list()
}

suspend fun AgentService.refreshMcpHealth(serverName: String): McpHealthSnapshot? {
    val manager = McpManager(workDir, mcpPluginSource(), mcpProcessHost)
    val server = manager.list().firstOrNull { it.name == serverName }
        ?: return mcpHealth.value.firstOrNull { it.serverName == serverName }
    if (!server.enabled) return mcpHealth.value.firstOrNull { it.serverName == serverName }
    val selection = manager.select(server)
    val identity = selection.runtimeIdentity
    val scope = AgentPermissionScope.id(mode.value, AgentRequestProvenance.primary())
    when (perms.decide("mcp", identity, scope)) {
        PermEffect.ALLOW -> try { manager.discoverTools(selection) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {   }
        PermEffect.DENY -> McpHealthRegistry.permissionDeferred(workDir, server, denied = true)
        else -> McpHealthRegistry.permissionDeferred(workDir, server, denied = false)
    }
    return mcpHealth.value.firstOrNull { it.serverName == serverName }
}

fun AgentService.mcpManagement(files: FileRepository): McpManagement = McpManagement(
    files, McpManager(workDir, mcpPluginSource(), mcpProcessHost),
    permission = { identity -> perms.decide("mcp", identity,
        AgentPermissionScope.id(mode.value, AgentRequestProvenance.primary())) },
)
