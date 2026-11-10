package com.baystudio.droide.core

import java.util.UUID

internal data class TerminalCapabilityResult(
    val execution: ExecResult?,
    val complete: Boolean,
    val commands: Map<String, String?>,
    val path: String?,
)

/** One bounded subprocess on an already-pinned session; never execute the requested commands. */
internal object TerminalCapabilityProbe {
    private val namePattern = Regex("[A-Za-z0-9_+.][A-Za-z0-9_+.-]{0,79}")
    fun validName(name: String): Boolean = namePattern.matches(name)

    suspend fun inspect(session: ITerminalSession, names: List<String>, includePath: Boolean): TerminalCapabilityResult {
        require(names.size <= 32 && names.distinct().size == names.size && names.all(::validName))
        if (names.isEmpty() && !includePath) return TerminalCapabilityResult(null, true, emptyMap(), null)
        val marker = "DROIDE_PROBE_" + UUID.randomUUID().toString().replace("-", "")
        // NUL framing handles spaces/newlines in executable paths without evaluating rc files. Names
        // cannot start with '-' (command -v options) and are individually shell-quoted.
        val script = buildString {
            append("printf '%s\\0' '").append(marker).append("'; ")
            names.forEachIndexed { index, name ->
                append("if _droide_probe_path=$(command -v '").append(name).append("' 2>/dev/null); then ")
                append("printf '%s\\0' C '").append(index).append("' 0; printf '%.400s\\0' \"\$_droide_probe_path\"; else ")
                append("printf '%s\\0' C '").append(index).append("' 1 ''; fi; ")
            }
            if (includePath) append("printf '%s\\0' P; printf '%.4000s\\0' \"\$PATH\"; ")
            append("printf '%s\\0' E '").append(marker).append("'")
        }
        val execution = session.execOnce(script, timeoutMs = 10_000)
        if (execution.timedOut || execution.exitCode != 0) return TerminalCapabilityResult(execution, false, emptyMap(), null)
        val fields = execution.output.split('\u0000')
        var cursor = fields.indexOf(marker)
        if (cursor < 0) return TerminalCapabilityResult(execution, false, emptyMap(), null)
        cursor++
        val resolved = linkedMapOf<String, String?>()
        for ((index, name) in names.withIndex()) {
            if (fields.getOrNull(cursor) != "C" || fields.getOrNull(cursor + 1) != index.toString() ||
                fields.getOrNull(cursor + 2) !in setOf("0", "1") || fields.getOrNull(cursor + 3) == null) {
                return TerminalCapabilityResult(execution, false, emptyMap(), null)
            }
            resolved[name] = fields[cursor + 3].takeIf { fields[cursor + 2] == "0" && it.isNotEmpty() }?.take(500)
            cursor += 4
        }
        val path = if (includePath) {
            if (fields.getOrNull(cursor) != "P" || fields.getOrNull(cursor + 1) == null)
                return TerminalCapabilityResult(execution, false, emptyMap(), null)
            fields[cursor + 1].take(4_000).also { cursor += 2 }
        } else null
        val complete = fields.getOrNull(cursor) == "E" && fields.getOrNull(cursor + 1) == marker
        return TerminalCapabilityResult(execution, complete, if (complete) resolved else emptyMap(), if (complete) path else null)
    }

    fun display(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 32 || character.code == 127) append('?') else append(character)
            }
        }
    }
}
