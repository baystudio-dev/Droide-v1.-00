package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

internal sealed interface PreparedToolInput {
    data class Legacy(val raw: String, val parsed: JsonObject) : PreparedToolInput
    class SourceFile(val prepared: StreamedFileArgumentExtractor.Prepared,
        val original: StreamedFileArgumentExtractor.Source,
        val session: OwnedResponseContentStore.Session) : PreparedToolInput
    class SourceCommand(val prepared: StreamedCommandArgumentExtractor.Prepared,
        val original: StreamedFileArgumentExtractor.Source,
        val session: OwnedResponseContentStore.Session) : PreparedToolInput
    class SourceStdin(val prepared: StreamedStdinArgumentExtractor.Prepared,
        val original: StreamedFileArgumentExtractor.Source,
        val session: OwnedResponseContentStore.Session) : PreparedToolInput
    class SourcePatch(val prepared: StreamedPatchArgumentExtractor.Prepared,
        val original: StreamedFileArgumentExtractor.Source,
        val session: OwnedResponseContentStore.Session) : PreparedToolInput
    class SourceArtifact(val prepared: StreamedArtifactArgumentExtractor.Prepared,
        val original: StreamedFileArgumentExtractor.Source,
        val session: OwnedResponseContentStore.Session) : PreparedToolInput
}

internal data class PreparedToolCall(
    val index: Int,
    val id: String,
    val name: String,
    val input: PreparedToolInput,
    val partId: String,
    val repeatOrdinal: Int,
    val signature: String,
    val argumentError: String? = null,
) {
    constructor(index: Int, id: String, name: String, rawArgs: String, args: JsonObject,
        partId: String, repeatOrdinal: Int, signature: String, argumentError: String? = null) :
        this(index,id,name,PreparedToolInput.Legacy(rawArgs,args),partId,repeatOrdinal,signature,argumentError)

    // A source call has no placeholder String/JsonObject. Any accidental legacy fallthrough fails closed.
    val rawArgs: String get() = (input as? PreparedToolInput.Legacy)?.raw
        ?: error("Source-backed arguments require their typed execution path")
    val args: JsonObject get() = (input as? PreparedToolInput.Legacy)?.parsed
        ?: error("Source-backed arguments require their typed execution path")
    fun displayInput(): String = when(val value=input) {
        is PreparedToolInput.Legacy -> value.raw
        is PreparedToolInput.SourceCommand -> "Source-backed ${value.prepared.toolName} arguments: ${value.original.bytes} bytes; SHA-256 ${value.original.sha256}"
        is PreparedToolInput.SourceStdin -> "Source-backed write_stdin arguments: ${value.original.bytes} bytes; SHA-256 ${value.original.sha256}"
        is PreparedToolInput.SourcePatch -> "Source-backed apply_patch arguments: ${value.original.bytes} bytes; SHA-256 ${value.original.sha256}"
        is PreparedToolInput.SourceArtifact -> "Source-backed artifact arguments: ${value.original.bytes} bytes; SHA-256 ${value.original.sha256}"
        is PreparedToolInput.SourceFile -> "Source-backed ${value.prepared.wireName} arguments: ${value.original.bytes} bytes; SHA-256 ${value.original.sha256}"
    }
}

internal data class ToolOutcome(
    val call: PreparedToolCall,
    val result: String,
    val failed: Boolean = false,
    val images: List<AgentImageRef> = emptyList(),
    val media: List<ChatMediaRef> = emptyList(),
)

 
internal object AgentToolTurnPlanner {
    fun prepare(
        toolCalls: JsonArray,
        json: Json,
        turn: Int,
        maxArgumentChars: Int,
        priorSignature: String,
        priorRepeat: Int,
    ): List<PreparedToolCall> {
        var lastSignature = priorSignature
        var repeat = priorRepeat
        val prepared = toolCalls.mapIndexed { index, raw ->
            val call = raw as? JsonObject ?: throw IllegalStateException("Malformed tool call")
            val id = (call["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Tool call is missing id")
            val fn = call["function"] as? JsonObject ?: throw IllegalStateException("Tool call is missing function")
            val name = (fn["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Tool call is missing function name")
            require(name.length <= 120) { "Tool name is too long" }
            val argumentValue = fn["arguments"] as? JsonPrimitive
            val rawArgs = argumentValue?.takeIf { it.isString }?.contentOrNull.orEmpty()
            require(rawArgs.length <= maxArgumentChars) { "Tool arguments are too large" }
            // Invalid provider arguments are a failed call, never implicit authority
            // to invoke argument-free mutation tools with an empty object.
            val parsed = if (argumentValue?.isString == true) runCatching { json.parseToJsonElement(ProviderPayloadLimits.checkedJson(rawArgs)) }.getOrNull() else null
            val args = parsed as? JsonObject
            val argumentError = if (args == null)
                "ERROR: INVALID_TOOL_ARGUMENTS: $name requires a valid JSON object encoded as a string. No tool or lifecycle hook was executed; retry with corrected arguments."
                else null
            val signature = "$name:$rawArgs"
            val ordinal = if (signature == lastSignature) repeat + 1 else 1
            lastSignature = signature
            repeat = ordinal
            PreparedToolCall(index, id, name, rawArgs, args ?: buildJsonObject {}, "tool:$turn:$index", ordinal, signature, argumentError)
        }
        require(prepared.map { it.id }.distinct().size == prepared.size) { "Duplicate tool call id in provider response" }
        return prepared
    }

    fun schedulingFacts(
        prepared: List<PreparedToolCall>,
        permissionAllows: (AgentToolReadAccess) -> Boolean,
        hasLifecycleHook: (String) -> Boolean,
    ): List<AgentToolScheduler.Facts> = prepared.map { call ->
        val readAccess = if (call.input is PreparedToolInput.Legacy && call.argumentError == null) AgentTools.parallelReadAccess(call.name, call.args) else null
        AgentToolScheduler.Facts(
            index = call.index,
            readOnlyCandidate = readAccess != null,
            permissionAllowsWithoutPrompt = readAccess != null && permissionAllows(readAccess),
            lifecycleHookBoundary = hasLifecycleHook(call.name),
            doomLoopApprovalPossible = call.repeatOrdinal >= 3,
        )
    }
}
