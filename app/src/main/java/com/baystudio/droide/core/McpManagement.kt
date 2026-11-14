package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Workspace-scoped management. Saving/enabling never executes a server or grants tool permissions. */
class McpManagement internal constructor(
    private val files: FileRepository,
    private val manager: McpManager,
    private val permission: (String) -> PermEffect,
) {
    private val configuration = McpWorkspaceConfiguration(files) {
        McpToolRegistry.invalidate(files.root)
        manager.list() // Retire processes whose configuration changed, including disabled servers.
    }

    suspend fun load(): McpConfigurationSnapshot = withContext(Dispatchers.IO) {
        manager.list()
        configuration.load()
    }

    suspend fun save(snapshot: McpConfigurationSnapshot, draft: McpServerDraft, originalName: String?) =
        configuration.save(snapshot, draft, originalName)

    suspend fun setEnabled(snapshot: McpConfigurationSnapshot, name: String, enabled: Boolean) =
        configuration.setEnabled(snapshot, name, enabled)

    suspend fun remove(snapshot: McpConfigurationSnapshot, name: String) = configuration.remove(snapshot, name)

    class ConnectionReview internal constructor(
        internal val owner: McpManagement,
        internal val selection: McpManager.ServerSelection,
    ) {
        val server: McpServer get() = selection.server
        internal val consumed = AtomicBoolean(false)
    }

    suspend fun reviewConnection(name: String): ConnectionReview = withContext(Dispatchers.IO) {
        val selection = manager.select(name)
        check(permission(selection.runtimeIdentity) != PermEffect.DENY) {
            "Connection is blocked by Agent Permissions. Review the MCP permission rule first."
        }
        ConnectionReview(this@McpManagement, selection)
    }

    /** Called only by the user's Connect once action after displaying this exact command/argv. */
    suspend fun connect(review: ConnectionReview): McpHealthSnapshot = withContext(Dispatchers.IO) {
        check(review.owner === this@McpManagement && review.consumed.compareAndSet(false, true)) {
            "This connection review has expired. Review the server again."
        }
        check(permission(review.selection.runtimeIdentity) != PermEffect.DENY) {
            "Connection is now blocked by Agent Permissions."
        }
        manager.discoverTools(review.selection) // Revalidates configuration and executable provenance.
        McpToolRegistry.invalidate(files.root, review.server.name)
        checkNotNull(McpHealthRegistry.observe(files.root).value.firstOrNull { it.serverName == review.server.name })
    }
}
