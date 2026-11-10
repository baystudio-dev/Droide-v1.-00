package com.baystudio.droide.core

import java.io.OutputStream
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

/** Context admission remains approximate UTF-8 bytes / 4 (not a tokenizer or token upper bound)
 * plus image reservations. */
internal class OwnedChatRequestBody private constructor(
    private val options: JsonObject,
    private val history: List<SourceAwareChatHistoryWriter.Item>,
    private val target: ProviderReplayTarget,
    private val checkCancelled: () -> Unit,
    private val admittedBytes: Long,
) : RequestBody() {
    override fun contentType() = "application/json".toMediaType()
    override fun contentLength() = admittedBytes
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) {
        val counter = CountingOutput(sink.outputStream(), checkCancelled)
        write(options, history, target, counter, checkCancelled)
        check(counter.bytes == admittedBytes) { "Typed request source changed after admission" }
    }
    companion object {
        fun admit(options: JsonObject, history: List<SourceAwareChatHistoryWriter.Item>,
            target: ProviderReplayTarget, provider: AiProvider, checkCancelled: () -> Unit): OwnedChatRequestBody {
            require("messages" !in options)
            val immutable = history.toList()
            // Complete legacy groups must not straddle an owned group.
            val segment = mutableListOf<JsonObject>()
            fun flush() { if (segment.isNotEmpty()) { AgentHistory.requireCompleteGroups(segment); segment.clear() } }
            immutable.forEach { item -> when(item) {
                is SourceAwareChatHistoryWriter.Item.Legacy -> segment += item.message
                is SourceAwareChatHistoryWriter.Item.OwnedAssistantGroup -> flush()
                is SourceAwareChatHistoryWriter.Item.OwnedAssistant -> {
                    // Group-shape-only metadata; never emitted on wire or used for execution.
                    segment += buildJsonObject {
                        put("role", "assistant"); put("content", "")
                        if (item.bound.callIds.isNotEmpty()) put("tool_calls", buildJsonArray {
                            item.bound.callIds.forEach { id -> add(buildJsonObject {
                                put("id", id); put("type", "function")
                                put("function", buildJsonObject { put("name", "shape_only"); put("arguments", "{}") })
                            }) }
                        })
                    }
                }
            } }; flush()
            val counter = CountingOutput(null, checkCancelled)
            write(options, immutable, target, counter, checkCancelled) // validates every owned group BEFORE networking
            val reserve = checkNotNull(ProviderRequestBudget.explicitOutputTokens(options))
            val available = ContextBudget.effectiveInputTokens(provider.id, target.modelId, reserve)
            if (available != null) {
                val legacy = immutable.filterIsInstance<SourceAwareChatHistoryWriter.Item.Legacy>().map { it.message }
                // Wire base64 size is not model context. Replace only legacy message accounting with the shared
                // Unicode/image-aware estimate; retain every exact wire byte.
                val legacyEstimate = ContextBudget.estimate("", legacy, null, provider.id, target.modelId, null, reserve)
                val otherBytes = (counter.bytes - legacy.sumOf { ContextBudget.serializedUtf8Bytes(it).toLong() }).coerceAtLeast(0)
                val estimated = (otherBytes + 3) / 4 + legacyEstimate.approximateTokens
                val margin = minOf(256, (available / 16).coerceAtLeast(1))
                if (estimated + margin > available) throw ProviderContextOverflowException(
                    counter.utf16.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    "Estimated streamed provider input is $estimated tokens plus $margin safety tokens; available input is $available after reserving $reserve output tokens. Compact complete older turns or select a larger model. Counts are approximate; request preserved.")
            }
            return OwnedChatRequestBody(options, immutable, target, checkCancelled, counter.bytes)
        }
        private fun write(options: JsonObject, history: List<SourceAwareChatHistoryWriter.Item>,
            target: ProviderReplayTarget, out: OutputStream, check: () -> Unit) {
            check(); val prefix = options.toString(); out.write(prefix.dropLast(1).toByteArray(Charsets.UTF_8))
            if (options.isNotEmpty()) out.write(','.code)
            out.write("\"messages\":".toByteArray(Charsets.UTF_8))
            SourceAwareChatHistoryWriter.writeMessages(history,target,out,check)
            check();out.write('}'.code)
        }
    }
    private class CountingOutput(private val destination: OutputStream?, private val check: () -> Unit) : OutputStream() {
        var bytes = 0L; private set
        var utf16 = 0L; private set
        override fun write(value: Int) { val one=byteArrayOf(value.toByte());write(one,0,1) }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            check(); bytes=Math.addExact(bytes,length.toLong())
            for(i in offset until offset+length) { val c=buffer[i].toInt() and 255
                if(c < 128 || c >= 192) utf16=Math.addExact(utf16,if(c >= 240) 2L else 1L)
            }
            destination?.write(buffer,offset,length)
        }
    }
}
