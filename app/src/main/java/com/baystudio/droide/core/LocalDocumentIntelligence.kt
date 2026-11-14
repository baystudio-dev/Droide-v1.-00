package com.baystudio.droide.core

import java.io.StringReader
import java.util.Properties

/** Bounded, read-only document features. No process, project scan, or configuration evaluation. */
internal object LocalDocumentIntelligence {
    const val MAX_CHARS = 256 * 1024
    const val MAX_SYMBOLS = 2000
    private val languages = setOf("batch", "ini", "properties")
    private val label = Regex("^\\s*:(?!:)([A-Za-z0-9_.-]+)")
    private val setCommand = Regex("^\\s*@?set\\s+", RegexOption.IGNORE_CASE)
    private val jump = Regex("^\\s*@?(?:goto\\s+|call\\s+:)([A-Za-z0-9_.-]+)", RegexOption.IGNORE_CASE)
    private val variable = Regex("([%!])([A-Za-z_][A-Za-z0-9_.-]*)\\1")

    data class Result(val symbols: List<LspSymbol>, val diagnostics: List<LspDiagnostic>, val limit: String? = null)

    fun supports(path: String): Boolean = LanguageRegistry.forFile(path)?.id in languages

    fun status(path: String): String? = when (LanguageRegistry.forFile(path)?.id) {
        "batch" -> "Local Batch support · outline and label/variable navigation; execution requires Windows cmd.exe"
        "ini" -> "Local INI support · outline and section checks. Dialect/schema validation needs a custom server"
        "properties" -> "Local Properties support · outline and escape checks; framework validation needs a custom server"
        else -> null
    }

    fun analyze(path: String, text: String): Result {
        if (!supports(path)) return Result(emptyList(), emptyList())
        if (text.length > MAX_CHARS) return Result(emptyList(), emptyList(), "Local analysis limit: 256 KiB of text")
        if (text.indices.any { text[it] == '\r' && (it + 1 == text.length || text[it + 1] != '\n') })
            return Result(emptyList(), emptyList(), "Local analysis requires LF or CRLF line endings")
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        return when (LanguageRegistry.forFile(path)?.id) {
            "properties" -> properties(path, lines)
            "ini" -> ini(path, lines)
            "batch" -> batch(path, lines)
            else -> Result(emptyList(), emptyList())
        }
    }

    private fun diagnostic(line: Int, column: Int, message: String) = LspDiagnostic(
        line, column, line, column + 1, 1, message, "Droide local")

    private fun properties(path: String, lines: List<String>): Result {
        val symbols = mutableListOf<LspSymbol>()
        val diagnostics = mutableListOf<LspDiagnostic>()
        var line = 0
        while (line < lines.size && symbols.size + diagnostics.size < MAX_SYMBOLS) {
            val first = line
            val leading = lines[line].takeWhile { it in " \t\u000c" }.length
            val trimmed = lines[line].drop(leading)
            if (trimmed.isEmpty() || trimmed.first() in "#!") { line++; continue }
            val logical = StringBuilder()
            do {
                val natural = lines[line++]
                logical.append(natural).append('\n')
                val continuation = natural.takeLastWhile { it == '\\' }.length % 2 == 1
            } while (continuation && line < lines.size)
            try {
                val values = Properties().apply { load(StringReader(logical.toString())) }
                for (key in values.stringPropertyNames()) if (key.isNotEmpty())
                    symbols += LspSymbol(key, 7, path, first + 1, leading + 1)
            } catch (_: IllegalArgumentException) {
                diagnostics += diagnostic(first + 1, leading + 1, "Malformed Unicode escape in Java Properties; use \\u followed by four hexadecimal digits.")
            }
        }
        return Result(symbols, diagnostics, if (line < lines.size) "Local symbol limit reached" else null)
    }

    private fun ini(path: String, lines: List<String>): Result {
        val symbols = mutableListOf<LspSymbol>()
        val diagnostics = mutableListOf<LspDiagnostic>()
        var inSection = false
        val strictSectionChecks = path.substringAfterLast('.', "").equals("ini", ignoreCase = true)
        for ((index, line) in lines.withIndex()) {
            if (symbols.size + diagnostics.size >= MAX_SYMBOLS) return Result(symbols, diagnostics, "Local symbol limit reached")
            val trim = line.trimStart()
            if (trim.isEmpty() || trim.first() in ";#") continue
            val column = line.length - trim.length + 1
            if (trim.startsWith('[')) {
                val end = trim.indexOf(']')
                if (end <= 1 || trim.substring(1, end).isBlank()) {
                    if (strictSectionChecks) diagnostics += diagnostic(index + 1, column, "INI section needs a name and a closing ].")
                    continue
                }
                symbols += LspSymbol(trim.substring(1, end).trim(), 3, path, index + 1, column)
                inSection = true
            } else {
                val end = trim.indexOfFirst { it == '=' || it == ':' }
                if (end <= 0) continue
                val key = trim.substring(0, end).trimEnd()
                symbols += LspSymbol(key, 7, path, index + 1, column, depth = if (inSection) 1 else 0)
            }
        }
        return Result(symbols, diagnostics)
    }

    private fun batchComment(line: String): Boolean {
        val trim = line.trimStart().removePrefix("@")
        return trim.startsWith("::") || trim.equals("rem", true) || trim.startsWith("rem ", true) || trim.startsWith("rem\t", true)
    }

    private fun batchAssignment(line: String): Pair<String, Int>? {
        var start = setCommand.find(line)?.range?.last?.plus(1) ?: return null
        if (line.substring(start).startsWith("/p", ignoreCase = true) && line.getOrNull(start + 2)?.isWhitespace() == true) {
            start += 3
            while (line.getOrNull(start)?.isWhitespace() == true) start++
        }
        if (line.getOrNull(start) == '/') return null
        if (line.getOrNull(start) == '"') start++
        val end = line.indexOf('=', start)
        if (end <= start) return null
        val name = line.substring(start, end)
        if (name.isBlank() || '"' in name) return null
        return name to start
    }

    private fun batch(path: String, lines: List<String>): Result {
        val symbols = mutableListOf<LspSymbol>()
        for ((index, line) in lines.withIndex()) {
            if (symbols.size >= MAX_SYMBOLS) return Result(symbols, emptyList(), "Local symbol limit reached")
            if (batchComment(line)) continue
            val target = label.find(line)?.groups?.get(1)
            val value = batchAssignment(line)
            if (target != null) symbols += LspSymbol(target.value, 12, path, index + 1, target.range.first + 1)
            else if (value != null) symbols += LspSymbol(value.first, 13, path, index + 1, value.second + 1)
        }
        return Result(symbols, emptyList())
    }

    fun definition(path: String, text: String, line: Int, column: Int): List<LspLocation> {
        if (LanguageRegistry.forFile(path)?.id != "batch" || text.length > MAX_CHARS || line < 1 || column < 1) return emptyList()
        val current = text.lineSequence().elementAtOrNull(line - 1) ?: return emptyList()
        if (batchComment(current)) return emptyList()
        val destination = jump.find(current)?.groups?.get(1)?.takeIf { column - 1 in it.range }
        val target = destination ?: variable.findAll(current).firstOrNull { column - 1 in it.range }?.groups?.get(2) ?: return emptyList()
        return analyze(path, text).symbols.filter {
            it.kind == (if (destination != null) 12 else 13) && it.name.equals(target.value, true)
        }.map { LspLocation(it.path, it.line, it.column) }
    }
}
