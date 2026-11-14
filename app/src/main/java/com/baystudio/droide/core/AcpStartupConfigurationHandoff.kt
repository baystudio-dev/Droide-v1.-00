package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Owns only configuration delivery. Lifecycle authority remains in AcpNdjsonConnection. */
internal class AcpStartupConfigurationHandoff(
    private val commands: AcpSessionCommands,
    private val getSessionId: () -> String?,
    private val hasReadBinding: () -> Boolean,
    private val getTransportFailure: () -> Throwable?,
) {
    private data class StartupConfiguration(val sessionId: String, var value: WorkspaceAgentSessionConfiguration)
    private val lock = Any()
    private var startupConfiguration: StartupConfiguration? = null
    private var configurationDeliveryInstalled = false
    @Volatile var listener: ((JsonObject) -> Unit)? = null

    fun observe(reportedSessionId: String, update: JsonObject) {
        synchronized(lock) {
            // The response owns the session identity. Notifications
            // may refine that snapshot, never choose another owner.
            startupConfiguration?.takeIf { it.sessionId == reportedSessionId }?.let {
                it.value = WorkspaceAgentSessionConfigurations.update(update, it.value)
            }
            if (getSessionId() != null && reportedSessionId == getSessionId()) listener?.invoke(update)
        }
    }

    fun retainStartupConfiguration(method: String, requestedSessionId: String?, result: JsonObject) {
        val owner = requireNotNull(if (method == "session/load") requestedSessionId
            else (result["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull) {
            "ACP startup response has no valid session identity"
        }
        require(owner.isNotBlank() && owner.length <= 512) { "ACP startup response has no valid session identity" }
        if (method == "session/load" && "sessionId" in result) {
            val reported = (result["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            require(reported == owner) { "ACP load response does not match the requested session" }
        }
        synchronized(lock) {
            check(getTransportFailure() == null && !configurationDeliveryInstalled && startupConfiguration == null) {
                "ACP startup configuration cannot replace an active or failed session"
            }
            startupConfiguration = StartupConfiguration(owner, WorkspaceAgentSessionConfigurations.merge(result))
        }
    }

    fun installConfigurationListener(expectedSessionId: String,
                                     onSnapshot: (WorkspaceAgentSessionConfiguration) -> Unit,
                                     onUpdate: (JsonObject) -> Unit) {
        synchronized(lock) {
            fun requireCurrent() {
                check(getTransportFailure() == null && getSessionId() == expectedSessionId && hasReadBinding() &&
                    !configurationDeliveryInstalled) { "ACP configuration handoff belongs to a closed or different session" }
            }
            requireCurrent()
            val startup = requireNotNull(startupConfiguration) { "ACP startup configuration is unavailable" }
            check(startup.sessionId == expectedSessionId) { "ACP startup configuration belongs to another session" }
            // Keep the retained snapshot until delivery succeeds. Commands already capture
            // pre-response announcements and remain scoped to this same native session.
            onSnapshot(startup.value.copy(availableCommands = commands.snapshot(expectedSessionId)))
            requireCurrent()
            listener = onUpdate
            configurationDeliveryInstalled = true
            startupConfiguration = null
        }
    }

}
