package com.baystudio.droide.core

import kotlinx.serialization.json.*
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

/** Mixed-batch ingress planner. Only write_file takes the new large-source route. No executor.
 * All other tools keep their bounded legacy JsonObject path; never drop an unsupported call. */
internal object OwnedChatToolPlanner {
    sealed class Input {
        class WriteFile(val prepared: StreamedFileArgumentExtractor.Prepared) : Input()
        class Legacy(val rawArguments: String, val arguments: JsonObject) : Input()
    }
    data class Entry(val call: OwnedChatResult.Call, val input: Input, val repeatOrdinal: Int, val identity: String)
    data class Batch(val entries: List<Entry>, val lastIdentity: String, val lastRepeat: Int)

    fun prepare(
        result: OwnedChatResult,
        callSession: (String) -> OwnedResponseContentStore.Session,
        json: Json,
        priorIdentity: String = "",
        priorRepeat: Int = 0,
        maximumLegacyArgumentChars: Int = 128_000,
        checkCancelled: () -> Unit = {},
    ): Batch {
        require(maximumLegacyArgumentChars > 0)
        require(result.finishReason == "tool_calls" || result.calls.isEmpty()) { "Unadmitted typed tool batch" }
        require(result.calls.map { it.id }.distinct().size == result.calls.size) { "Duplicate typed call identity" }
        var identity = priorIdentity
        var repeat = priorRepeat
        val entries = result.calls.map { call ->
            checkCancelled()
            val session = callSession(call.id)
            call.arguments.verify(session)
            require(call.arguments.kind == OwnedResponseContentStore.Kind.TOOL_ARGUMENTS)
            val input = if (call.name == "write_file") Input.WriteFile(StreamedFileArgumentExtractor.extract(
                session, call.arguments.handle, StreamedFileArgumentExtractor.Tool.WRITE_FILE, call.id, checkCancelled,
            )) else {
                require(call.arguments.utf16Units in 0..maximumLegacyArgumentChars.toLong()) {
                    "Large arguments for this tool are not yet supported by the typed execution route"
                }
                val raw = session.openBytes(call.arguments.handle).use { source ->
                    val reader = InputStreamReader(source, Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT))
                    val text = StringBuilder(); val chars = CharArray(4096)
                    while (true) {
                        checkCancelled(); val n = reader.read(chars); if (n < 0) break; check(n > 0)
                        require(n <= maximumLegacyArgumentChars - text.length) { "Legacy tool arguments exceed supported capacity" }
                        text.append(chars, 0, n)
                    }; text.toString()
                }
                Input.Legacy(raw, json.parseToJsonElement(raw) as? JsonObject ?: error("Legacy arguments must be an object"))
            }
            val next = call.name + ":sha256:" + call.arguments.sha256
            repeat = if (next == identity) Math.addExact(repeat, 1) else 1
            identity = next
            Entry(call, input, repeat, next)
        }
        checkCancelled()
        return Batch(entries, identity, repeat)
    }
}
