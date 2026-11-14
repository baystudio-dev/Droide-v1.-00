package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** ACP patch fields stay typed. Display budgets never truncate the lifecycle envelope. */
data class ExternalAgentToolUpdate(
    val id: String,
    val title: String? = null,
    val kind: String? = null,
    val status: String? = null,
    val input: String? = null,
    val output: String? = null,
    val changes: List<AgentFileChange>? = null,
    val isUpdate: Boolean = false,
    val tasks: List<Todo>? = null,
) {
    fun retainedChars(): Long = 128L + id.length + (title?.length ?: 0) + (kind?.length ?: 0) +
        (status?.length ?: 0) + (input?.length ?: 0) + (output?.length ?: 0) +
        changes.orEmpty().sumOf { it.path.length.toLong() + it.diff.length } +
        tasks.orEmpty().sumOf { it.content.length.toLong() + it.status.length + it.priority.length }

    /** Normalize only paths inside this session's workspace; never guess from a basename or scan files. */
    fun withWorkspacePaths(mapper: WorkspacePathMapper): ExternalAgentToolUpdate {
        if (changes == null) return this
        val roots = listOf(mapper.remoteRoot, mapper.localRoot.path).distinct().sortedByDescending { it.length }
        fun relative(value: String): String {
            val path = if (value.startsWith("file:")) {
                val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return value
                if (uri.isOpaque || !uri.rawAuthority.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) return value
                uri.path ?: return value
            } else value
            val root = roots.firstOrNull { path.startsWith("$it/") }
            val candidate = if (root == null) path else path.removePrefix("$root/")
            return candidate.takeIf(AgentSessionResults::safePath) ?: value
        }
        return copy(changes = changes.map { it.copy(path = relative(it.path), previousPath = it.previousPath?.let(::relative)) })
    }

    companion object {
        const val OUTPUT_LIMIT = 32_000
        private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun fromAcp(update: JsonObject, terminalOutput: (String) -> String? = { null }): ExternalAgentToolUpdate? {
            val id = update.string("toolCallId")?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return null
            val status = update.string("status")
            if (status != null && status !in setOf("pending", "in_progress", "completed", "failed")) return null
            val text = StringBuilder()
            var omitted = false
            fun append(value: String) {
                if (text.isNotEmpty() && text.length < OUTPUT_LIMIT) text.append('\n')
                val remaining = (OUTPUT_LIMIT - text.length).coerceAtLeast(0)
                text.append(value.take(remaining)); omitted = omitted || value.length > remaining
            }
            val changes = mutableListOf<AgentFileChange>()
            val content = update["content"] as? JsonArray
            content?.take(64)?.forEach { item ->
                val block = item as? JsonObject ?: return@forEach
                when (block.string("type")) {
                    "content" -> {
                        val value = block["content"] as? JsonObject
                        value?.string("text")?.let(::append)
                        (value?.get("resource") as? JsonObject)?.string("text")?.let(::append)
                    }
                    "diff" -> {
                        val path = block.string("path")?.take(4096) ?: return@forEach
                        if (changes.size < 8) changes += AgentFileChanges.preview(path, block.string("oldText"),
                            block.string("newText"), limit = 4_000)
                        else omitted = true
                    }
                    "terminal" -> block.string("terminalId")?.let { append(terminalOutput(it) ?: "Terminal output unavailable") }
                }
            }
            omitted = omitted || (content?.size ?: 0) > 64
            if (text.isEmpty() && update["rawOutput"] != null && update["rawOutput"] != JsonNull) {
                val raw = update.getValue("rawOutput")
                append(if (raw is JsonPrimitive && raw.isString) raw.content else boundedJsonDiagnostic(raw, maxChars = OUTPUT_LIMIT))
            }
            if (omitted) text.append("\n[Preview truncated]")
            val locations = update["locations"] as? JsonArray
            val path = (locations?.firstOrNull() as? JsonObject)?.string("path")
            val rawInput = update["rawInput"]?.takeIf { it != JsonNull }
            val inputPath = path ?: (rawInput as? JsonObject)?.string("file_path")
            val input = if (inputPath != null && (rawInput == null || rawInput is JsonObject)) {
                boundedJsonDiagnostic(buildJsonObject {
                    put("path", inputPath.take(4096))
                    (rawInput as? JsonObject)?.forEach { (key, value) -> if (key != "path") put(key, value) }
                }, maxChars = 8_000)
            } else rawInput?.let { boundedJsonDiagnostic(it, maxChars = 8_000) }
            return ExternalAgentToolUpdate(id, update.string("title")?.take(512), update.string("kind")?.take(40),
                update.string("status")?.takeIf { it in setOf("pending", "in_progress", "completed", "failed") },
                input, text.toString().takeIf { content != null || update.containsKey("rawOutput") },
                changes.takeIf { content != null }, update.string("sessionUpdate") == "tool_call_update")
        }

        /** Compatibility for in-process backends; production ACP uses fromAcp before diagnostics. */
        fun fromLegacy(title: String, raw: String): ExternalAgentToolUpdate? {
            if (raw.length > 65_536) return null
            val objectValue = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
            val patch = fromAcp(objectValue) ?: return null
            return patch.copy(title = patch.title ?: title.takeIf { it.isNotBlank() && it != "Agent tool" && it != patch.kind }?.take(512))
        }
    }
}

data class ExternalAgentPlanEntry(val content: String, val status: String)

/** Text deltas are lossless on disk; tool/plan snapshots replace their original ordered slot. */
data class ExternalTranscriptUpdate(val part: AgentStreamPart, val textDelta: String? = null,
                                    val patch: ExternalAgentToolUpdate? = null, val plan: Boolean = false) {
    val retainedChars: Int get() = 256 + (textDelta?.length ?: 0) + part.input.length + part.output.length +
        part.title.length + part.detail.length + part.fileChanges.sumOf { it.path.length + it.diff.length } +
        part.tasks.orEmpty().sumOf { it.content.length + it.status.length + it.priority.length }
}

/** The same patch merge applies to the live window and an older activity loaded from SQLite. */
fun ExternalAgentToolUpdate.applyTo(base: AgentStreamPart, plan: Boolean = false): AgentStreamPart {
    val nextState = if (base.state in setOf(AgentStreamPartState.COMPLETED, AgentStreamPartState.ERROR, AgentStreamPartState.CANCELLED) && !plan) base.state
        else when (status) {
            "completed" -> AgentStreamPartState.COMPLETED; "failed" -> AgentStreamPartState.ERROR
            "in_progress" -> AgentStreamPartState.RUNNING; "pending" -> AgentStreamPartState.PENDING
            else -> base.state
        }
    val name = when (kind) {
        "read" -> "read_file"; "search" -> "search_files"; "execute" -> "run_command"
        "edit" -> "edit_file"; "delete" -> "delete_file"; "move" -> "move_file"
        else -> kind ?: base.toolName.ifBlank { "tool" }
    }
    return base.copy(state = nextState, toolCallId = id, toolName = name,
        title = title ?: base.title.ifBlank { kind ?: "Agent tool" },
        input = input ?: base.input, output = output ?: base.output, fileChanges = changes ?: base.fileChanges,
        tasks = tasks ?: base.tasks,
        endedAtMs = if (nextState in setOf(AgentStreamPartState.COMPLETED, AgentStreamPartState.ERROR)) base.endedAtMs ?: System.currentTimeMillis() else null)
}

fun externalFinishedPart(part: AgentStreamPart, status: PluginHistoryStatus): AgentStreamPart {
    if (part.state !in setOf(AgentStreamPartState.RUNNING, AgentStreamPartState.PENDING)) return part
    val state = if (part.kind == AgentStreamPartKind.TEXT && status == PluginHistoryStatus.COMPLETED) AgentStreamPartState.COMPLETED
        else if (status == PluginHistoryStatus.FAILED) AgentStreamPartState.ERROR else AgentStreamPartState.CANCELLED
    return part.copy(state = state, endedAtMs = System.currentTimeMillis(),
        detail = if (part.kind == AgentStreamPartKind.TEXT) part.detail else "Result not reported before the turn ended")
}
