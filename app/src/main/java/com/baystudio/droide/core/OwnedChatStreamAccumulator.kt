package com.baystudio.droide.core

import kotlinx.serialization.json.*
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** Chat-compatible typed ingress. No production switch and no tool executor live here.
 * Per-event JSON remains bounded; this does not yet parse a huge single SSE scalar incrementally.
 */
internal class OwnedChatStreamAccumulator(
    private val responseSession: OwnedResponseContentStore.Session,
    private val callSession: (String) -> OwnedResponseContentStore.Session,
    private val target: ProviderReplayTarget,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val checkCancelled: () -> Unit = {},
    private val maximumCalls: Int = 1024,
) : Closeable {
    enum class TransportEnd { DONE_MARKER, CLEAN_EOF }
    sealed interface Progress {
        data class Text(val delta: String) : Progress
        data object Thinking : Progress
        data class Tool(val index: Int, val id: String, val name: String, val receivedUtf16: Long) : Progress
    }
    private class Call(val index: Int) {
        var id: String? = null
        var name = ""
        var writer: OwnedResponseContentStore.Session.Writer? = null
        var received = 0L
    }
    private val calls = sortedMapOf<Int, Call>()
    private val fields = linkedMapOf<String, OwnedResponseContentStore.Session.Writer>()
    private var detailsCount = 0L
    private var finishReason: String? = null
    private var done = false
    private var closed = false
    private var failed = false
    private var completed = false
    private var responseId: String? = null
    private var reportedModel: String? = null
    private var usage: JsonObject? = null
    private var nativeFinishReason: String? = null
    private var refusalSeen = false
    private var meaningfulPublicText = false
    private var envelopeWriter: OwnedResponseContentStore.Session.Writer? = null
    init { require(target.protocol == ProviderReplayTarget.Protocol.OPENAI_CHAT); require(maximumCalls > 0) }

    fun accept(payload: String): List<Progress> = guarded {
        require(!done) { "Chat stream continued after DONE" }
        if (payload.trim() == "[DONE]") { done = true; return@guarded emptyList() }
        ProviderPayloadLimits.checkedJson(payload)
        OwnedChatEventKeys.rejectDuplicates(payload, json)
        val root = json.parseToJsonElement(payload) as? JsonObject
            ?: error("Chat event must be an object")
        root["error"]?.takeUnless { it == JsonNull }?.let { error("Chat provider returned an error event") }
        boundedString(root["id"], 1024)?.let { responseId = it }
        boundedString(root["model"], 1024)?.let { reportedModel = it }
        val reportedUsage = root["usage"]?.takeUnless { it == JsonNull }
            ?: (root["x_groq"] as? JsonObject)?.get("usage")?.takeUnless { it == JsonNull }
        if (reportedUsage != null) usage = reportedUsage as? JsonObject
        val rawChoices = root["choices"]
        require(rawChoices == null || rawChoices == JsonNull || rawChoices is JsonArray) { "Malformed chat choices" }
        val choices = rawChoices as? JsonArray ?: return@guarded emptyList()
        require(choices.size <= 1) { "Typed Chat route supports a single response choice" }
        if (choices.isEmpty()) return@guarded emptyList()
        val choice = choices.single() as? JsonObject ?: error("Malformed chat choice")
        choice["error"]?.takeUnless { it == JsonNull }?.let { error("Chat provider returned a choice error") }
        boundedString(choice["native_finish_reason"], 128)?.let { nativeFinishReason = it }
        val reason = boundedString(choice["finish_reason"], 128)
        if (reason != null) require(finishReason == null || finishReason == reason) { "Chat terminal reason changed" }
        val rawDelta = choice["delta"]
        require(rawDelta == null || rawDelta == JsonNull || rawDelta is JsonObject) { "Malformed chat delta" }
        val delta = rawDelta as? JsonObject
        if (finishReason != null) require(delta == null || delta.isEmpty()) { "Chat content continued after terminal reason" }
        val events = mutableListOf<Progress>()
        if (delta != null) {
            boundedString(delta["role"], 32)?.let { require(it == "assistant") { "Unexpected streamed message role" } }
            for (key in listOf("reasoning_content", "reasoning")) {
                val value = string(delta[key])
                if (!value.isNullOrEmpty()) { field(key).append(value); events += Progress.Thinking }
            }
            delta["reasoning_details"]?.takeUnless { it == JsonNull }?.let { raw ->
                require(raw is JsonArray && raw.all { it is JsonObject }) { "Malformed private reasoning details" }
                for (part in raw) {
                    val writer = field("reasoning_details")
                    if (detailsCount++ == 0L) writer.append("[") else writer.append(",")
                    writer.append(part.toString())
                }
            }
            val text = string(delta["content"])
            if (!text.isNullOrEmpty()) {
                if (text.any { !it.isWhitespace() }) meaningfulPublicText = true
                field("content").append(text); events += Progress.Text(text)
            }
            val refusal = string(delta["refusal"])
            if (!refusal.isNullOrEmpty()) {
                refusalSeen = true; field("content").append(refusal); field("refusal").append(refusal)
                events += Progress.Text(refusal)
            }
            val rawTools = delta["tool_calls"]
            require(rawTools == null || rawTools == JsonNull || rawTools is JsonArray) { "Malformed chat tool deltas" }
            (rawTools as? JsonArray)?.forEach { value ->
                val item = value as? JsonObject ?: error("Malformed tool delta")
                val rawIndex = item["index"] as? JsonPrimitive
                require(rawIndex != null && !rawIndex.isString) { "Typed tool delta requires numeric index" }
                val index = rawIndex.intOrNull ?: error("Typed tool delta requires integer index")
                require(index >= 0) { "Negative tool index" }
                val call = calls.getOrPut(index) {
                    require(calls.size < maximumCalls) { "Too many concurrent tool metadata entries" }; Call(index)
                }
                boundedString(item["type"], 32)?.let { require(it == "function") { "Unsupported tool delta type" } }
                boundedString(item["id"], 512)?.takeIf { it.isNotBlank() }?.let { id ->
                    require(call.id == null || call.id == id) { "Tool call identity changed" }
                    require(calls.values.none { it !== call && it.id == id }) { "Duplicate tool call identity" }
                    call.id = id
                }
                val rawFunction = item["function"]
                require(rawFunction == null || rawFunction == JsonNull || rawFunction is JsonObject) { "Malformed tool function" }
                val function = rawFunction as? JsonObject
                boundedString(function?.get("name"), 120)?.takeIf(String::isNotEmpty)?.let { fragment ->
                    val name = if (fragment == call.name) call.name else call.name + fragment
                    require(name.length <= 120) { "Tool name exceeds supported metadata capacity" }; call.name = name
                }
                val fragment = string(function?.get("arguments"))
                if (fragment != null) {
                    val id = requireNotNull(call.id) { "Typed route requires call identity before arguments" }
                    val writer = call.writer ?: callSession(id).begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).also { call.writer = it }
                    writer.append(fragment); call.received = Math.addExact(call.received, fragment.length.toLong())
                }
                call.id?.let { events += Progress.Tool(index, it, call.name, call.received) }
            }
        }
        if (reason != null) finishReason = reason
        events
    }

    /** Only the transport reader calls this after DONE or successful clean EOF. The independent
     * finish_reason gate is mandatory; incomplete, malformed and cancelled generations never yield
     * typed calls. Full response completion remains more conservative than Codex item-level dispatch.
     */
    fun finishAtTransportEnd(end: TransportEnd): OwnedChatResult = guarded {
        if (end == TransportEnd.DONE_MARKER) require(done) { "Transport DONE was not observed" }
        if (end == TransportEnd.CLEAN_EOF) require(!done) { "Inconsistent transport completion" }
        val reason = requireNotNull(finishReason) { "Chat stream ended before terminal finish_reason" }
        require(reason == "stop" || reason == "tool_calls") { "Chat generation is incomplete or not admissible" }
        if (calls.isNotEmpty()) require(reason == "tool_calls" && !refusalSeen) { "Tool calls lack admissible completion" }
        if (reason == "tool_calls") require(calls.isNotEmpty()) { "Empty tool-call terminal response" }
        val completeCalls = calls.values.map { call ->
            val id = requireNotNull(call.id) { "Missing tool identity" }
            require(call.name.isNotBlank()) { "Missing tool name" }
            val writer = requireNotNull(call.writer) { "Missing tool argument document" }
            val content = writer.sealCompleted { StreamedJsonValidator.requireObject(it) }.content
            OwnedChatResult.Call(call.index, id, call.name, source(content))
        }
        if (detailsCount > 0) fields.getValue("reasoning_details").append("]")
        field("content") // empty answer still has an explicit complete owned source
        val completeFields = fields.mapValues { (_, writer) -> source(writer.sealCompleted(::drain).content) }
        checkCancelled()
        val envelope = responseSession.begin(OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION)
        envelopeWriter = envelope
        try {
            val out = writerOutput(envelope)
            fun ascii(value: String) { out.write(value.toByteArray(Charsets.UTF_8)) }
            fun quote(value: String) { OwnedJsonStringWriter.writeValidatedUtf8(value.byteInputStream(), out, checkCancelled) }
            fun quotedSource(session: OwnedResponseContentStore.Session, value: StreamedFileArgumentExtractor.Source) {
                value.verify(session); session.openBytes(value.handle).use { OwnedJsonStringWriter.writeValidatedUtf8(it, out, checkCancelled) }
            }
            ascii("{\"role\":\"assistant\",\"content\":")
            quotedSource(responseSession, completeFields.getValue("content"))
            completeFields["refusal"]?.let { ascii(",\"refusal\":"); quotedSource(responseSession, it) }
            run { // Private provider fields are retained even for a refusal outcome.
                for (key in listOf("reasoning_content", "reasoning")) completeFields[key]?.let {
                    ascii(","); quote(key); ascii(":"); quotedSource(responseSession, it)
                }
                completeFields["reasoning_details"]?.let { details ->
                    ascii(",\"reasoning_details\":"); details.verify(responseSession)
                    responseSession.openBytes(details.handle).use { copy(it, out) }
                }
            }
            if (completeCalls.isNotEmpty()) {
                ascii(",\"tool_calls\":[")
                completeCalls.forEachIndexed { i, call ->
                    if (i > 0) ascii(",")
                    ascii("{\"id\":"); quote(call.id); ascii(",\"type\":\"function\",\"function\":{\"name\":"); quote(call.name)
                    ascii(",\"arguments\":"); quotedSource(callSession(call.id), call.arguments); ascii("}}")
                }
                ascii("]")
            }
            ascii("}")
            val native = envelope.sealCompleted { StreamedJsonValidator.requireObject(it) }.content
            checkCancelled()
            completed = true
            OwnedChatResult(target, completeFields.getValue("content"), source(native), completeCalls,
                reason, responseId, reportedModel, usage, nativeFinishReason, completeFields.filterKeys { it != "content" }.values.toList(), meaningfulPublicText, refusalSeen, completeFields.keys.any { it in setOf("reasoning_content", "reasoning", "reasoning_details") })
        } finally { envelope.close() }
    }

    fun transportFailed() { failed = true }

    /** Response fields use the responseSession; tool fields use callSession(callId). */
    data class ReceivedStage(val callId: String?, val handle: OwnedResponseContentStore.Handle)
    data class Recovery(
        val checkpoints: List<OwnedResponseContentStore.AbortCheckpoint>,
        // Includes already sealed or failed stages; caller must reconcile them, not assume loss/success.
        val uncheckpointed: List<ReceivedStage>,
        val checkpointOwners: List<ReceivedStage> = emptyList(),
    )
    fun receivedStages(): List<ReceivedStage> = fields.values.map { ReceivedStage(null, it.recoveryHandle) } +
        calls.values.mapNotNull { call -> call.writer?.let { ReceivedStage(call.id, it.recoveryHandle) } } +
        listOfNotNull(envelopeWriter?.let { ReceivedStage(null, it.recoveryHandle) })
    fun receivedHandles(): List<OwnedResponseContentStore.Handle> = receivedStages().map { it.handle }

    fun checkpointIncomplete(): Recovery {
        check(!closed && !completed)
        failed = true
        val checkpoints = mutableListOf<OwnedResponseContentStore.AbortCheckpoint>()
        val uncheckpointed = mutableListOf<ReceivedStage>()
        val checkpointOwners = mutableListOf<ReceivedStage>()
        val owned = fields.values.map { null to it } + calls.values.mapNotNull { call -> call.writer?.let { call.id to it } } +
            listOfNotNull(envelopeWriter?.let { null to it })
        for ((callId, writer) in owned) {
            try { val checkpoint = writer.abortToIncomplete(); checkpoints += checkpoint
                checkpointOwners += ReceivedStage(callId, checkpoint.content.handle) }
            catch (_: Exception) { uncheckpointed += ReceivedStage(callId, writer.recoveryHandle) }
        }
        return Recovery(checkpoints, uncheckpointed, checkpointOwners)
    }
    override fun close() {
        if (closed) return
        closed = true
        var failure: Exception? = null
        (fields.values + calls.values.mapNotNull { it.writer } + listOfNotNull(envelopeWriter)).forEach {
            try { it.close() } catch (error: Exception) { if (failure == null) failure = error else failure!!.addSuppressed(error) }
        }
        failure?.let { throw it }
    }
    private fun field(name: String): OwnedResponseContentStore.Session.Writer = fields.getOrPut(name) {
        responseSession.begin(if (name == "content") OwnedResponseContentStore.Kind.ANSWER else OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION)
    }
    private fun string(value: JsonElement?): String? = when (value) {
        null, JsonNull -> null
        is JsonPrimitive -> { require(value.isString) { "Chat string field has the wrong type" }; value.content }
        else -> error("Chat string field has the wrong type")
    }
    private fun boundedString(value: JsonElement?, maximum: Int): String? = string(value)?.also {
        require(it.length <= maximum) { "Chat metadata exceeds supported capacity" }
        Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(it))
    }
    private fun source(content: OwnedResponseContentStore.Content) = StreamedFileArgumentExtractor.Source(
        content.handle, content.byteLength, content.utf16Length, content.sha256, content.kind)
    private fun writerOutput(writer: OwnedResponseContentStore.Session.Writer) = object : OutputStream() {
        override fun write(value: Int) { writer.appendUtf8(byteArrayOf(value.toByte())) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { writer.appendUtf8(bytes, offset, length) }
    }
    private fun drain(input: InputStream) { val bytes = ByteArray(8192); while (true) { checkCancelled(); val n = input.read(bytes); if (n < 0) break; check(n > 0) } }
    private fun copy(input: InputStream, output: OutputStream) { val bytes = ByteArray(8192); while (true) { checkCancelled(); val n = input.read(bytes); if (n < 0) break; check(n > 0); output.write(bytes, 0, n) } }
    private inline fun <T> guarded(block: () -> T): T {
        check(!closed && !failed && !completed) { "Typed Chat stream is no longer active" }
        return try { checkCancelled(); block() } catch (failure: Exception) {
            failed = true
            if (failure is kotlinx.serialization.SerializationException) throw IllegalStateException("Malformed typed Chat event JSON")
            throw failure
        }
    }
}

internal class OwnedChatResult(
    val target: ProviderReplayTarget,
    val publicText: StreamedFileArgumentExtractor.Source,
    val nativeAssistantMessage: StreamedFileArgumentExtractor.Source,
    val calls: List<Call>,
    val finishReason: String,
    val responseId: String?,
    val reportedModel: String?,
    val usage: JsonObject?,
    val nativeFinishReason: String?,
    val auxiliarySources: List<StreamedFileArgumentExtractor.Source> = emptyList(),
    val hasMeaningfulPublicText: Boolean = false,
    val hasRefusal: Boolean = false,
    val hasPrivateContinuation: Boolean = false,
) {
    data class Call(val index: Int, val id: String, val name: String, val arguments: StreamedFileArgumentExtractor.Source)
}
