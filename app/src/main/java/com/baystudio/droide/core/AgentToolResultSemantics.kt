package com.baystudio.droide.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The model must not collapse these states: - a callable tool was advertised.


internal enum class AgentToolEvidenceState {
    SUCCEEDED,
    STARTED,
    FAILED,
    DENIED,
    UNAVAILABLE,
}

internal data class AgentToolEvidence(
    val state: AgentToolEvidenceState,
    val operationStarted: Boolean,
    val success: Boolean?,
    val detail: String,
) {
    val isFailure: Boolean
        get() = state in setOf(AgentToolEvidenceState.FAILED, AgentToolEvidenceState.DENIED, AgentToolEvidenceState.UNAVAILABLE)

    fun annotate(tool: String, raw: String): String = buildString {
        append("[DROIDE_TOOL_EVIDENCE tool=")
        append(tool.take(120).replace(Regex("[^A-Za-z0-9_.:-]"), "_"))
        append(" state=").append(state.name)
        append(" operation_started=").append(operationStarted)
        append(" success=").append(success?.toString() ?: "unverified")
        append("]\n")
        append(raw)
    }

    val uiDetail: String
        get() = when (state) {
            AgentToolEvidenceState.SUCCEEDED -> "Succeeded"
            AgentToolEvidenceState.STARTED -> "Started · completion unverified"
            AgentToolEvidenceState.FAILED -> "Failed"
            AgentToolEvidenceState.DENIED -> "Denied"
            AgentToolEvidenceState.UNAVAILABLE -> "Unavailable"
        }
}

internal object AgentToolResultSemantics {
    fun operationFor(tool: String, arguments: JsonObject): String? =
        (arguments[if (tool in setOf("ide", "capability", "document", "audio", "generate_image")) "operation" else "action"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.trim()

    private val evidenceMarkerRegex = Regex(
        "^\\[DROIDE_TOOL_EVIDENCE\\s+tool=([^\\s\\]]+)\\s+state=(SUCCEEDED|STARTED|FAILED|DENIED|UNAVAILABLE)\\s+operation_started=(true|false)\\s+success=(true|false|unverified)\\][ \t]*(?:\\r?\\n)?",
        RegexOption.IGNORE_CASE,
    )
    private val exitRegex = Regex("(?:^|\\s)exit=(-?\\d+)(?:\\s|$)")
    private val backgroundStateRegex = Regex("(?:^|\\s)state=(running|exited|failed|timed_out|killed)(?:\\s|$)", RegexOption.IGNORE_CASE)
    private val httpFailureRegex = Regex("^HTTP\\s+[45]\\d\\d(?:\\s|:)", RegexOption.IGNORE_CASE)

    // Parse evidence already attached to a durable tool result without re-inferring success.
    fun parseAnnotation(raw: String): AgentToolEvidence? {
        val match = evidenceMarkerRegex.find(raw.trimStart()) ?: return null
        val state = runCatching { AgentToolEvidenceState.valueOf(match.groupValues[2].uppercase()) }.getOrNull() ?: return null
        val operationStarted = match.groupValues[3].equals("true", ignoreCase = true)
        val success = when (match.groupValues[4].lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        val detail = when (state) {
            AgentToolEvidenceState.SUCCEEDED -> "Succeeded"
            AgentToolEvidenceState.STARTED -> "Started · completion unverified"
            AgentToolEvidenceState.FAILED -> "Failed"
            AgentToolEvidenceState.DENIED -> "Denied"
            AgentToolEvidenceState.UNAVAILABLE -> "Unavailable"
        }
        return AgentToolEvidence(state, operationStarted, success, detail)
    }

     
    fun stripAnnotation(raw: String): String = evidenceMarkerRegex.replaceFirst(raw.trimStart(), "")

    fun classify(tool: String, raw: String, operation: String? = null): AgentToolEvidence {
        val text = raw.trimStart()
        val processOutput = tool in setOf("run_command", "bash", "exec_command", "write_stdin", "background_job", "capability") ||
            tool.startsWith("custom_") || tool.startsWith("plugin_")
        if (tool in setOf("read_file", "read") && text.startsWith("[DROIDE_READ_RESULT returned=true]\n"))
            return AgentToolEvidence(AgentToolEvidenceState.SUCCEEDED, true, true, "File read returned; document contents are not execution evidence")
        if (text.startsWith("DENIED", ignoreCase = true)) {
            return AgentToolEvidence(AgentToolEvidenceState.DENIED, operationStarted = false, success = false, detail = "Policy or user denied execution")
        }
        if (tool in setOf("document", "audio", "transcribe_audio")) {
            val result = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            when (result?.get("ok")) {
                JsonPrimitive(false) -> return AgentToolEvidence(AgentToolEvidenceState.FAILED, true, false, "Media operation failed")
                JsonPrimitive(true) -> return AgentToolEvidence(AgentToolEvidenceState.SUCCEEDED, true, true, "Media operation returned verified metadata")
                else -> Unit
            }
        }
        if (tool in setOf("web", "webfetch", "websearch", "browser")) {
            val result = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            when (result?.get("success")) {
                JsonPrimitive(true) -> return if (tool == "browser" && result["loading"] == JsonPrimitive(true))
                    AgentToolEvidence(AgentToolEvidenceState.STARTED, true, null, "Browser page is still loading; completion unverified")
                    else AgentToolEvidence(AgentToolEvidenceState.SUCCEEDED, true, true, "Web operation returned verified result metadata")
                JsonPrimitive(false) -> return AgentToolEvidence(AgentToolEvidenceState.FAILED, true, false, "Web operation reported a failure or blocked source")
                else -> Unit
            }
        }
        if (tool == "mcp" || McpToolIdentity.isFirstClassName(tool)) {
            val result = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (result?.get("isError") == JsonPrimitive(true) || (result?.get("result") as? JsonObject)?.get("isError") == JsonPrimitive(true)) {
                return AgentToolEvidence(AgentToolEvidenceState.FAILED, true, false, "MCP server returned isError=true")
            }
        }
        if (tool == "ide") {
            AgentIdeResult.evidence(text, operation)?.let { return it }
            val missing = text.contains("manager unavailable", ignoreCase = true)
            return AgentToolEvidence(if (missing) AgentToolEvidenceState.UNAVAILABLE else AgentToolEvidenceState.FAILED,
                false, false, if (missing) "IDE manager is unavailable" else "IDE returned no valid execution evidence")
        }
        if (tool in setOf("diagnose", "lsp")) {
            val header = text.substringBefore('\n').trimEnd('\r')
            if (header.startsWith("Diagnostics for ") && header.endsWith(":")) {
                return AgentToolEvidence(AgentToolEvidenceState.SUCCEEDED, true, true,
                    "Diagnostic report returned; diagnostic contents do not establish a missing runtime or a clean workspace")
            }
            if (returnedLspPage(text)) {
                return AgentToolEvidence(AgentToolEvidenceState.SUCCEEDED, true, true,
                    "Captured LSP page returned; upstream completeness is not established")
            }
        }
        val control = if (processOutput) stripDiskNotice(text) else text
        val firstLine = control.substringBefore('\n').trimEnd('\r')
        if (tool == "capability" && (operation == "run" || firstLine == "CAPABILITY_RUN"))
            return AgentCapabilityResult.evidence(control)
        // The producer-owned exit wrapper precedes stdout. Program text below
        // it cannot declare a different session state or change its exit code.
        if (processOutput && firstLine.matches(Regex("exit=-?\\d+"))) {
            return processExit(firstLine.substringAfter('=').toIntOrNull())
        }
        if (processOutput && firstLine.startsWith("BACKGROUND_JOB_STARTED ")) {
            return AgentToolEvidence(AgentToolEvidenceState.STARTED, operationStarted = true, success = null, detail = "Background process started; terminal result not known yet")
        }
        val commandSession = processOutput && firstLine.startsWith("COMMAND_SESSION ")
        val backgroundSummary = tool == "background_job" && operation != "list" && firstLine.startsWith("id=")
        val stateHeader = firstLine.substringBefore(" command=")
        if (commandSession || backgroundSummary) backgroundStateRegex.find(stateHeader)?.groupValues?.getOrNull(1)?.lowercase()?.let { state ->
            return when (state) {
                "running" -> AgentToolEvidence(AgentToolEvidenceState.STARTED, true, null, "Background process is still running")
                "exited" -> {
                    val exitHeader = if (backgroundSummary) stateHeader else control.lineSequence().drop(1).take(3)
                        .takeWhile { !it.startsWith('{') }.joinToString("\n")
                    processExit(exitRegex.find(exitHeader)?.groupValues?.getOrNull(1)?.toIntOrNull())
                }
                // KILLED is the command controller's cancellation state. Joining its
                // coroutine does not supply a native process-exit/cleanup receipt.
                "killed" -> if (operation == "kill" || (tool == "write_stdin" && stateHeader.contains("interrupted=true"))) AgentToolEvidence(AgentToolEvidenceState.STARTED, true, null, "Stop requested; process exit and cleanup are unconfirmed")
                    else AgentToolEvidence(AgentToolEvidenceState.FAILED, true, false, "Command execution was canceled; process exit and cleanup are unconfirmed")
                else -> AgentToolEvidence(AgentToolEvidenceState.FAILED, true, false, "Background process ended in $state")
            }
        }
        if (processOutput && (firstLine.startsWith("[TIMEOUT", ignoreCase = true) || control == "(timeout)")) {
            return AgentToolEvidence(AgentToolEvidenceState.FAILED, true, false, "Operation timed out")
        }
        // Workspace text and catalog status are data, not proof of a missing runtime.
        val runtimeFailureText = processOutput || tool in setOf("diagnose", "lsp") || text.startsWith("ERROR", ignoreCase = true)
        val unavailable = text.startsWith("Unknown tool:", ignoreCase = true) || (runtimeFailureText && (
            text.contains("tool not found", ignoreCase = true) ||
            text.contains("tool definition is stale or unavailable", ignoreCase = true) ||
            text.contains("manager unavailable", ignoreCase = true) ||
            text.contains("subagents unavailable", ignoreCase = true) ||
            text.contains("executable not found", ignoreCase = true) ||
            text.contains("command not found", ignoreCase = true) ||
            text.contains("not installed", ignoreCase = true) ||
            (tool in setOf("diagnose", "lsp") && text.contains("unavailable", ignoreCase = true))))
        if (unavailable) {
            return AgentToolEvidence(AgentToolEvidenceState.UNAVAILABLE, false, false, "Required tool/runtime dependency is unavailable")
        }
        if (text.startsWith("ERROR", ignoreCase = true) || text.startsWith("git error:", ignoreCase = true) || httpFailureRegex.containsMatchIn(text)) {
            return AgentToolEvidence(AgentToolEvidenceState.FAILED, true, false, "Tool returned an explicit failure")
        }
        return AgentToolEvidence(AgentToolEvidenceState.SUCCEEDED, true, true, "Tool returned normally")
    }

    private fun returnedLspPage(text: String): Boolean {
        if (!text.startsWith("LSP_RESULT_PAGE\n") || !text.contains("\nLSP_PAGE_TEXT\n")) return false
        val metadata = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(
                text.substringAfter('\n').substringBefore('\n')) as? JsonObject
        }.getOrNull() ?: return false
        return metadata["scope"] == JsonPrimitive("captured_provider_text") &&
            metadata["upstream_completeness"] == JsonPrimitive("not_established") &&
            (metadata["result_id"] as? JsonPrimitive)?.isString == true
    }

    private fun stripDiskNotice(text: String): String {
        if (!text.startsWith("[DROIDE_DISK_STATE ")) return text
        val closing = "\n[/DROIDE_DISK_STATE]\n"
        val end = text.indexOf(closing)
        return if (end < 0) text else text.substring(end + closing.length).trimStart()
    }

    private fun processExit(code: Int?): AgentToolEvidence = if (code == 0)
        AgentToolEvidence(AgentToolEvidenceState.SUCCEEDED, true, true, "Process exited with code 0")
        else AgentToolEvidence(AgentToolEvidenceState.FAILED, true, false, "Process exited without verified success${code?.let { ": $it" }.orEmpty()}")
}
