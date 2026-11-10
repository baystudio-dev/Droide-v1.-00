package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class WorkspaceAgentBootstrapResult(
    val process: HostedStdioProcess,
    val rpc: AcpNdjsonConnection,
    val sessionId: String,
    val approvalSessionId: String,
    val mapper: WorkspacePathMapper,
    val modes: List<String>,
    val configuration: WorkspaceAgentSessionConfiguration,
    val promptCapabilities: AcpPromptCapabilities = AcpPromptCapabilities(),
)

internal object WorkspaceAgentSessionBootstrap {
    suspend fun start(
        spec: WorkspaceAgentPluginSpec,
        projectRoot: File,
        files: FileRepository,
        processHost: StdioProcessHost,
        bridge: DeviceBridgeManager,
        approvals: ApprovalManager,
        permissions: PermissionEngine,
        scope: CoroutineScope,
        resumeSessionId: String?,
        onEvent: (WorkspaceAgentEvent) -> Unit,
        workspaceIo: AgentWorkspaceIo = BridgeAgentWorkspaceIo(bridge),
    ): WorkspaceAgentBootstrapResult = start(
        spec, projectRoot, files, processHost, approvals, permissions, scope, resumeSessionId, onEvent, workspaceIo,
    )

    // Local ACP transports need their bound workspace IO, not an unrelated ADB initialization.
    suspend fun start(
        spec: WorkspaceAgentPluginSpec,
        projectRoot: File,
        files: FileRepository,
        processHost: StdioProcessHost,
        approvals: ApprovalManager,
        permissions: PermissionEngine,
        scope: CoroutineScope,
        resumeSessionId: String?,
        onEvent: (WorkspaceAgentEvent) -> Unit,
        workspaceIo: AgentWorkspaceIo,
        lifetime: WorkspaceAgentSessionLifetime = WorkspaceAgentSessionLifetime(scope),
    ): WorkspaceAgentBootstrapResult {
        val executable = processHost.resolveExecutable(spec.command)
            ?: error("${spec.displayName} is not installed or not executable in the active Linux environment")
        onEvent(WorkspaceAgentEvent.Status("Starting ${spec.displayName} ACP session…"))
        val mapper = requireNotNull(processHost.pathMapper()) { "Linux workspace mapper is unavailable" }
        try {
            val ownedHost = lifetime.processes.bind(processHost)
            val process = ownedHost.start(listOf(executable) + spec.args)
            val rpc = AcpNdjsonConnection(process, files, mapper, ownedHost, approvals, permissions, lifetime.scope, workspaceIo)
            lifetime.rpc = rpc
            onEvent(WorkspaceAgentEvent.Status("Connecting to ${spec.displayName}…"))
            val init = rpc.request(
                "initialize",
                buildJsonObject {
                    put("protocolVersion", 1)
                    put("clientCapabilities", buildJsonObject {
                        put("fs", buildJsonObject { put("readTextFile", true); put("writeTextFile", true) })
                        put("terminal", true)
                    })
                    put("clientInfo", buildJsonObject { put("name", "Droide"); put("version", "1.00") })
                },
                timeoutMs = AgentTimeoutPolicy.ACP_STARTUP_MS,
            )
            val negotiated = init["protocolVersion"]?.jsonPrimitive?.intOrNull
                ?: init["protocolVersion"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            require(negotiated == 1) { "${spec.displayName} negotiated unsupported ACP protocol $negotiated" }
            val capabilities = init["agentCapabilities"] as? JsonObject
            val supportsLoad = capabilities?.get("loadSession")?.jsonPrimitive?.booleanOrNull == true
            val supportsResume = ((capabilities?.get("sessionCapabilities") as? JsonObject)?.get("resume") as? JsonObject) != null
            val resumeMethod = when {
                supportsLoad -> "session/load"
                supportsResume -> "session/resume"
                else -> null
            }
            val mcpServers = WorkspaceMcpServers.project(projectRoot, mapper, processHost)
            val sessionResult: JsonObject
            val sessionId: String
            onEvent(WorkspaceAgentEvent.Status(if (resumeSessionId == null) "Loading ${spec.displayName} models and session…" else "Restoring ${spec.displayName} session…"))
            if (resumeSessionId != null) {
                require(resumeMethod != null) { "${spec.displayName} cannot resume ACP sessions; start a new session instead" }
                require(resumeSessionId.isNotBlank() && resumeSessionId.length <= 512) { "Invalid ACP resume session id" }
                rpc.commands.beginCapture(resumeSessionId)
                sessionResult = rpc.request(
                    resumeMethod,
                    buildJsonObject {
                        put("sessionId", resumeSessionId)
                        put("cwd", mapper.remoteRoot)
                        put("mcpServers", mcpServers)
                    },
                    timeoutMs = AgentTimeoutPolicy.ACP_STARTUP_MS,
                )
                sessionId = resumeSessionId
                onEvent(WorkspaceAgentEvent.Status("Resumed ${spec.displayName} ACP session."))
            } else {
                rpc.commands.beginCapture()
                sessionResult = rpc.request(
                    "session/new",
                    buildJsonObject { put("cwd", mapper.remoteRoot); put("mcpServers", mcpServers) },
                    timeoutMs = AgentTimeoutPolicy.ACP_STARTUP_MS,
                )
                sessionId = sessionResult["sessionId"]?.jsonPrimitive?.contentOrNull
                    ?: error("ACP agent did not return a sessionId")
            }
            val approvalSessionId = "acp-${sha256(agentId = spec.id, sessionId = sessionId)}"
            rpc.bindSession(sessionId, approvalSessionId)
            val configuration = WorkspaceAgentSessionConfigurations.merge(sessionResult)
                .copy(availableCommands = rpc.commands.snapshot(sessionId))
            return WorkspaceAgentBootstrapResult(
                process, rpc, sessionId, approvalSessionId, mapper, configuration.modes, configuration,
                AcpPromptCapabilities.fromInitialize(init),
            )
        } catch (failure: Throwable) {
            try { lifetime.closeAndAwait() }
            catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun sha256(agentId: String, sessionId: String): String = MessageDigest.getInstance("SHA-256")
        .digest((agentId + "\u0000" + sessionId).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

}
