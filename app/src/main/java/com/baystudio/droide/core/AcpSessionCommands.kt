package com.baystudio.droide.core

import java.util.Collections
import kotlinx.serialization.json.*

/** Command names only: no transcript, tool, permission, or unbound listener replay. */
internal class AcpSessionCommands {
    private val lock = Any()
    private var capturing = false
    private var expectedBootstrapId: String? = null
    private var boundSessionId: String? = null
    private val early = linkedMapOf<String, List<String>>()
    private var commands: List<String> = emptyList()

    /** Called immediately before session/new or session/load, never during initialize. */
    fun beginCapture(expectedSessionId: String? = null) = synchronized(lock) {
        check(!capturing && boundSessionId == null) { "ACP command capture already started" }
        require(expectedSessionId == null || validSessionId(expectedSessionId)) { "Invalid ACP command session id" }
        expectedBootstrapId = expectedSessionId
        capturing = true
    }

    /** Installed with the RPC reader itself; later listener installation cannot lose commands. */
    fun observe(params: JsonObject) = synchronized(lock) {
        if (!capturing && boundSessionId == null) return@synchronized
        val sessionId = (params["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?.takeIf(::validSessionId) ?: return@synchronized
        if (boundSessionId != null && sessionId != boundSessionId) return@synchronized
        if (capturing && expectedBootstrapId != null && sessionId != expectedBootstrapId) return@synchronized
        val update = params["update"] as? JsonObject ?: return@synchronized
        val snapshot = parseSnapshot(update) ?: return@synchronized
        if (boundSessionId != null) commands = snapshot
        else if (sessionId in early || early.size < MAX_EARLY_SESSIONS) early[sessionId] = snapshot
    }

    /** Binding is one-way. A new native session requires a new connection/collector. */
    fun bind(sessionId: String) = synchronized(lock) {
        require(validSessionId(sessionId)) { "Invalid ACP command session id" }
        check(boundSessionId == null) { "ACP command session is already bound" }
        check(expectedBootstrapId == null || expectedBootstrapId == sessionId) { "ACP command session changed while loading" }
        commands = early[sessionId] ?: emptyList()
        boundSessionId = sessionId
        capturing = false
        expectedBootstrapId = null
        early.clear()
    }

    fun snapshot(expectedSessionId: String): List<String> = synchronized(lock) {
        if (expectedSessionId == boundSessionId) commands else emptyList()
    }

    companion object {
        const val MAX_EARLY_SESSIONS = 4
        const val MAX_COMMANDS = 128
        const val MAX_COMMAND_NAME_CHARS = 128
        private const val MAX_SESSION_ID_CHARS = 512

        private fun validSessionId(value: String): Boolean = value.isNotBlank() &&
            value.length <= MAX_SESSION_ID_CHARS && value.none { Character.isISOControl(it) }

        private fun validName(value: String): Boolean = value.isNotEmpty() &&
            value.length <= MAX_COMMAND_NAME_CHARS && !value.startsWith('/') &&
            value.none { it.isWhitespace() || Character.isISOControl(it) }

        /** null means unrelated update. Malformed or oversized command snapshots fail closed. */
        fun parseSnapshot(update: JsonObject): List<String>? {
            if ((update["sessionUpdate"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull != "available_commands_update") return null
            val entries = update["availableCommands"] as? JsonArray ?: return emptyList()
            if (entries.size > MAX_COMMANDS) return emptyList()
            val names = linkedSetOf<String>()
            for (entry in entries) {
                val name = ((entry as? JsonObject)?.get("name") as? JsonPrimitive)
                    ?.takeIf { it.isString }?.contentOrNull ?: return emptyList()
                if (!validName(name)) return emptyList()
                names += name
            }
            return Collections.unmodifiableList(ArrayList(names))
        }

        /** null keeps ordinary scope-wrapped text, including unknown slash tokens and paths. */
        fun nativePrompt(prompt: String, hasMedia: Boolean, availableCommands: List<String>): JsonArray? {
            if (!prompt.startsWith('/')) return null
            val end = prompt.indexOfFirst { it.isWhitespace() }.let { if (it < 0) prompt.length else it }
            val name = prompt.substring(1, end)
            if (!validName(name) || name !in availableCommands) return null
            require(!hasMedia) { "Native CLI commands cannot include attachments. No CLI prompt was sent; send the command separately." }
            require(prompt.isNotBlank() && prompt.length <= 200_000) { "Agent prompt is empty or too large" }
            return buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", prompt) })
            }
        }
    }
}
