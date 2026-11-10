package com.baystudio.droide.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Immutable completion identity captured before a task can be resumed. */
@Serializable
data class SubagentDeliveryReceipt(
    val parentSessionId: String,
    val taskId: String,
    val childSessionId: String,
    val attempt: Int,
) {
    internal fun isValid(): Boolean = attempt > 0 &&
        listOf(parentSessionId, taskId, childSessionId).all { value ->
            value.length in 1..128 && value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
        }

    internal fun matches(record: SubagentTaskRecord): Boolean = isValid() &&
        parentSessionId == record.parentSessionId && taskId == record.id &&
        childSessionId == record.childSessionId && attempt == record.attempt

    companion object {
        fun from(record: SubagentTaskRecord): SubagentDeliveryReceipt = SubagentDeliveryReceipt(
            record.parentSessionId, record.id, record.childSessionId, record.attempt,
        ).also { require(it.isValid()) { "Invalid subagent completion identity" } }
    }
}

/** Private history metadata, never evidence inferred from user/model/tool text. */
internal object SubagentDeliveryHistory {
    const val PRIVATE_FIELD = "_droide_subagent_delivery"
    private val fields = setOf("version", "parent_session_id", "task_id", "child_session_id", "attempt")

    fun boundReceipt(
        origin: AgentInputOrigin, sourceId: String?, parentSessionId: String?, receipt: SubagentDeliveryReceipt?,
    ): SubagentDeliveryReceipt? {
        if (receipt == null) return null // Legacy inputs carry text, never delivery authority.
        require(origin == AgentInputOrigin.SUBAGENT && receipt.isValid() &&
            sourceId == receipt.taskId && parentSessionId == receipt.parentSessionId) {
            "Subagent completion identity does not match its input owner"
        }
        return receipt
    }

    fun entry(content: String, receipt: SubagentDeliveryReceipt?): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", content)
        if (receipt != null) {
            require(receipt.isValid()) { "Invalid subagent completion identity" }
            put(PRIVATE_FIELD, buildJsonObject {
                put("version", 1)
                put("parent_session_id", receipt.parentSessionId)
                put("task_id", receipt.taskId)
                put("child_session_id", receipt.childSessionId)
                put("attempt", receipt.attempt)
            })
        }
    }

    fun read(entry: JsonObject): SubagentDeliveryReceipt? {
        if (entry["role"] != JsonPrimitive("user")) return null
        val raw = entry[PRIVATE_FIELD] as? JsonObject ?: return null
        if (raw.keys != fields) return null
        fun integer(key: String): Int? = (raw[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        fun token(key: String): String? = (raw[key] as? JsonPrimitive)?.takeIf { it.isString && it.content.length in 1..128 }?.content
        if (integer("version") != 1) return null
        return SubagentDeliveryReceipt(
            token("parent_session_id") ?: return null,
            token("task_id") ?: return null,
            token("child_session_id") ?: return null,
            integer("attempt") ?: return null,
        ).takeIf { it.isValid() }
    }
}
