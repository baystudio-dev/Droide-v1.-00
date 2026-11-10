package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

open class LlmRequestTooLargeException(
    val requestChars: Int,
    detail: String = "LLM request is too large ($requestChars chars); compact the session",
) : IllegalStateException(detail)

class LlmHttpException(
    val statusCode: Int,
    val responseBody: String,
    val retryAfter: String? = null,
    val retryAfterMs: String? = null,
) : IllegalStateException(ProviderFailure.describe(statusCode, responseBody)) {
    fun isContextOverflow(): Boolean {
        if (statusCode == 413) return true
        if (statusCode !in setOf(400, 422)) return false
        val evidence = overflowEvidence(responseBody) ?: return false
        if (evidence.codes.any { it in CONTEXT_OVERFLOW_CODES }) return true
        val rawMessage = evidence.message.lowercase()
        if (rawMessage.trim() in CONTEXT_OVERFLOW_CODES) return true

        // Quoted field names, echoed prompts and documentation examples are not provider assertions
        // about this request's context size.
        val message = QUOTED_TEXT.replace(rawMessage, " ")
        if (INPUT_OVERFLOW.any { it.containsMatchIn(message) }) return true
        val contextOverflow = CONTEXT_OVERFLOW.any { it.containsMatchIn(message) }
        if (!contextOverflow) return false

        // Reducing history cannot fix max_tokens/max_output_tokens validation.

        val outputConfiguration = OUTPUT_CONFIGURATION.containsMatchIn(rawMessage) ||
            OUTPUT_CONFIGURATION.containsMatchIn(evidence.param.orEmpty().lowercase())
        return !outputConfiguration || message.split('.', '!', '?', ';', '\n').any { clause ->
            COMBINED_INPUT_OUTPUT.containsMatchIn(clause) && CONTEXT_OVERFLOW.any { it.containsMatchIn(clause) }
        }
    }

    companion object {
        private const val MAX_ERROR_BODY_CHARS = 64 * 1024
        private const val MAX_ERROR_MESSAGE_CHARS = 4096
        private val json = Json { ignoreUnknownKeys = true }
        private data class OverflowEvidence(val codes: List<String>, val message: String, val param: String?)

        private fun overflowEvidence(body: String): OverflowEvidence? {
            if (body.length > MAX_ERROR_BODY_CHARS) return null
            val text = body.trim()
            if (text.isEmpty()) return null
            if (text.startsWith('<')) return null
            if (text.first() !in listOf('{', '[', '"')) {
                return OverflowEvidence(emptyList(), text.take(MAX_ERROR_MESSAGE_CHARS), null)
            }
            // Malformed structured bodies must not fall back to searching raw JSON, HTML or arbitrary custom
            // metadata for incidental keywords.
            val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
            if (root is JsonPrimitive && root.isString) {
                return OverflowEvidence(emptyList(), root.content.take(MAX_ERROR_MESSAGE_CHARS), null)
            }
            if (root !is JsonObject) return null
            val error = root["error"]
            val fields = when (error) {
                is JsonObject -> error
                null -> root
                is JsonPrimitive -> if (error.isString) {
                    return OverflowEvidence(emptyList(), error.content.take(MAX_ERROR_MESSAGE_CHARS), null)
                } else return null
                else -> return null
            }
            fun stringField(key: String): String? = (fields[key] as? JsonPrimitive)
                ?.takeIf { it.isString }?.contentOrNull
            return OverflowEvidence(
                listOfNotNull(stringField("code"), stringField("type")).map { it.lowercase() },
                stringField("message").orEmpty().take(MAX_ERROR_MESSAGE_CHARS),
                stringField("param"),
            )
        }

        private val CONTEXT_OVERFLOW_CODES = setOf(
            "context_length_exceeded", "context_window_exceeded", "input_too_long", "prompt_too_long",
        )
        private val QUOTED_TEXT = Regex(""""[^"\r\n]*"|`[^`\r\n]*`|(?<![a-z])'[^'\r\n]*'(?![a-z])""")
        private val INPUT_OVERFLOW = listOf(
            Regex("""\b(?:input|prompt)(?: is)? too long\b"""),
            Regex("""\b(?:input|prompt) (?:has|contains) too many tokens\b|\btoo many (?:input|prompt) tokens\b"""),
            Regex("""\binput token count\s*\(\d+\)\s+exceeds the maximum number of tokens allowed\s*\(\d+\)"""),
            Regex("""\b(?:input|prompt) (?:tokens?|length|size)(?:\s*\(?\d+\)?)?\s+(?:exceeds?|exceeded)\s+(?:the )?(?:maximum|allowed|model(?:'s)? context|context|token limit)\b"""),
        )
        private val CONTEXT_OVERFLOW = listOf(
            Regex("""\bcontext (?:window|length|size)(?: limit)?(?: has been| is| was)? (?:exceeded|exhausted|too (?:long|large|small))\b"""),
            Regex("""\b(?:exceeds?|exceeded|larger than|longer than|greater than|too (?:large|long) for)\b[^.!?\n]{0,80}\b(?:context (?:length|window|size)|model(?:'s)? context)\b"""),
            Regex("""\b(?:maximum|max) context (?:length|window|size) (?:is|of|:)\s*\d+\b[^\n]{0,256}\b(?:requested|resulted in|provided)\b"""),
            Regex("""\b(?:input|prompt|messages?)\b[^.!?\n]{0,160}\b(?:must be|cannot be)\s+(?:less than|lower than|at most|<=|greater than)\s+[^.!?\n]{0,40}\bcontext (?:length|window|size)\b"""),
        )
        private val OUTPUT_CONFIGURATION = Regex(
            """\b(?:max_?(?:output|completion)?_?tokens|maxoutputtokens|maxcompletiontokens|output (?:token|tokens|length)|completion (?:token|tokens|length))\b""",
        )
        private const val INPUT_TERM = """(?:input|prompt|messages?)(?: (?:tokens?|token count|length|size))?(?:\s*\([\d,]+(?: tokens?)?\))?"""
        private const val OUTPUT_TERM = """(?:max_?(?:output|completion)?_?tokens|(?:output|completion)(?: (?:tokens?|token count|length|size))?)(?:\s*\([\d,]+(?: tokens?)?\))?"""
        private val COMBINED_INPUT_OUTPUT = Regex(
            """\b(?:$INPUT_TERM\s*(?:\+|plus|and)\s*$OUTPUT_TERM|$OUTPUT_TERM\s*(?:\+|plus|and)\s*$INPUT_TERM)\b""",
        )
    }
}
