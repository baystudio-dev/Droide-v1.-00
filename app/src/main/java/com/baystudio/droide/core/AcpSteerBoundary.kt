package com.baystudio.droide.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A client redirect can cancel an ACP turn only outside reported tool/client work. */
internal class AcpSteerBoundary {
    private var generation = 0L
    private var open = false
    private var requested = false
    private val tools = linkedSetOf<String>()
    private var unknownTool = false

    @Synchronized fun begin(): Long {
        generation++; open = true; requested = false; tools.clear(); unknownTool = false
        return generation
    }

    @Synchronized fun request(): Boolean {
        if (!open) return false
        requested = true
        return true
    }

    @Synchronized fun update(update: JsonObject, pendingClientCalls: Boolean): Long? {
        if (!open) return null
        val kind = update.string("sessionUpdate")
        if (kind == "tool_call" || kind == "tool_call_update") {
            val id = update.string("toolCallId")
            if (id == null) unknownTool = true
            else when (update.string("status")) {
                "completed", "failed", "cancelled" -> tools.remove(id)
                "pending", "in_progress" -> track(id)
                else -> if (kind == "tool_call") track(id)
            }
        }
        val boundary = kind in setOf("agent_message_chunk", "agent_thought_chunk", "tool_call_update")
        if (!requested || !boundary || pendingClientCalls || unknownTool || tools.isNotEmpty()) return null
        requested = false
        return generation
    }

    @Synchronized fun isOpen(value: Long): Boolean = open && generation == value
    @Synchronized fun clearRequest() { requested = false }
    @Synchronized fun end(value: Long) { if (generation == value) { open = false; requested = false; tools.clear() } }

    private fun track(id: String) { if (tools.size >= 256 && id !in tools) unknownTool = true else tools.add(id) }
    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }
        ?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 512 }
}
