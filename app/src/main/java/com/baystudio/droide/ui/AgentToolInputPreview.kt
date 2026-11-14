package com.baystudio.droide.ui

/** Bounded, iterative preview of top-level string fields in streamed tool JSON.
 * It does not validate or alter the arguments passed to the tool. */
internal class AgentToolInputPreview private constructor(private val fields: Map<String, Field>) {
    private data class Field(val text: String, val complete: Boolean, val shortened: Boolean)

    fun text(name: String): String? = fields[name]?.text
        ?.replace('\n', ' ')?.replace('\r', ' ')?.replace('\t', ' ')

    fun path(): String? = pathFields.firstNotNullOfOrNull { name ->
        fields[name]?.takeIf { it.complete && !it.shortened }?.text
            ?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
    }

    companion object {
        private const val MAX_SCAN_CHARS = 16_384
        private const val MAX_FIELD_CHARS = 512
        private const val MAX_NESTING = 32
        private val pathFields = listOf("path", "file_path", "filePath", "file", "rel_path", "relative_path", "relativePath")
        private val names = pathFields.toSet() + setOf("cwd", "command", "cmd", "query", "pattern",
            "action", "job_id", "session_id", "operation", "title", "family", "version", "url", "target")

        fun parse(input: String): AgentToolInputPreview {
            val fields = linkedMapOf<String, Field>()
            val end = minOf(input.length, MAX_SCAN_CHARS)
            var at = 0
            fun whitespace() { while (at < end && input[at].isWhitespace()) at++ }

            fun string(capture: Boolean): Field? {
                if (at >= end || input[at++] != '"') return null
                val text = StringBuilder()
                var shortened = false
                fun append(value: Char) {
                    if (!capture) return
                    if (text.length < MAX_FIELD_CHARS) text.append(value) else shortened = true
                }
                while (at < end) {
                    val c = input[at++]
                    when {
                        c == '"' -> return Field(text.toString(), complete = true, shortened)
                        c == '\\' -> {
                            if (at >= end) break
                            when (val escaped = input[at++]) {
                                '"', '\\', '/' -> append(escaped)
                                'b' -> append('\b')
                                'f' -> append('\u000c')
                                'n' -> append('\n')
                                'r' -> append('\r')
                                't' -> append('\t')
                                'u' -> {
                                    if (end - at < 4) break
                                    var code = 0
                                    repeat(4) {
                                        val digit = input[at++].digitToIntOrNull(16) ?: return null
                                        code = code * 16 + digit
                                    }
                                    append(code.toChar())
                                }
                                else -> return null
                            }
                        }
                        c < ' ' -> return null
                        else -> append(c)
                    }
                }
                return Field(text.toString(), complete = false, shortened = true)
            }

            fun skipValue(): Boolean {
                var depth = 0
                while (at < end) {
                    when (input[at]) {
                        '"' -> if (string(false)?.complete != true) return false
                        '{', '[' -> { if (++depth > MAX_NESTING) return false; at++ }
                        '}', ']' -> { if (depth == 0) return true; depth--; at++ }
                        ',' -> { if (depth == 0) return true; at++ }
                        else -> at++
                    }
                }
                return false
            }

            whitespace()
            if (at >= end || input[at++] != '{') return AgentToolInputPreview(fields)
            while (at < end) {
                whitespace()
                if (at >= end || input[at] == '}') break
                val key = string(true) ?: break
                if (!key.complete || key.shortened) break
                whitespace()
                if (at >= end || input[at++] != ':') break
                whitespace()
                if (at >= end) break
                if (input[at] == '"') {
                    val value = string(key.text in names) ?: break
                    if (key.text in names) fields[key.text] = value
                    if (!value.complete) break
                } else if (!skipValue()) break
                whitespace()
                if (at >= end || input[at++] != ',') break
            }
            return AgentToolInputPreview(fields)
        }
    }
}
