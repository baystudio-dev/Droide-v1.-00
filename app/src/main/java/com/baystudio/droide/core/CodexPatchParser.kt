package com.baystudio.droide.core

/** A line patch is resolved against the reviewed snapshot, before any write occurs. */
data class CodexPatchHunk(
    val heading: String?, val before: List<String>, val after: List<String>, val atEnd: Boolean,
)

internal object CodexPatchParser {
    fun parse(text: String): List<PatchTool.Op> {
        val lines = LineCursor(text)
        require(lines.first.trim() == "*** Begin Patch" && lines.last.trim() == "*** End Patch") {
            "A canonical patch must be enclosed by *** Begin Patch and *** End Patch"
        }
        val ops = mutableListOf<PatchTool.Op>()
        fun path(prefix: String): String = lines.peek().removePrefix(prefix).trim().also {
            require(it.isNotEmpty()) { "Patch path is missing" }
        }
        while (lines.hasNext) {
            val marker = lines.peek()
            if (marker.isBlank()) { lines.take(); continue }
            when {
                marker.startsWith("*** Add File: ") -> {
                    val target = path("*** Add File: ")
                    lines.take()
                    val body = StringBuilder()
                    while (lines.hasNext && !lines.peek().startsWith("*** ")) {
                        require(lines.peek().startsWith('+')) { "Add File lines must start with +: $target" }
                        val line = lines.take()
                        body.append(line, 1, line.length).append('\n')
                    }
                    ops += PatchTool.Op.Add(target, body.toString())
                }
                marker.startsWith("*** Delete File: ") -> {
                    ops += PatchTool.Op.Delete(path("*** Delete File: "))
                    lines.take()
                }
                marker.startsWith("*** Update File: ") -> {
                    val target = path("*** Update File: ")
                    lines.take()
                    val move = if (lines.hasNext && lines.peek().startsWith("*** Move to: ")) {
                        lines.take().removePrefix("*** Move to: ").trim().also { require(it.isNotEmpty()) }
                    } else null
                    val hunks = mutableListOf<CodexPatchHunk>()
                    while (lines.hasNext && !lines.peek().startsWith("*** ")) {
                        val hasHeader = lines.peek() == "@@" || lines.peek().startsWith("@@ ")
                        require(hasHeader || hunks.isEmpty()) {
                            "Update $target requires an @@ hunk"
                        }
                        val heading = if (hasHeader) lines.take().removePrefix("@@").trimStart().takeIf { it.isNotEmpty() } else null
                        val before = mutableListOf<String>()
                        val after = mutableListOf<String>()
                        var changed = false
                        while (lines.hasNext && !lines.peek().startsWith("*** ") &&
                            lines.peek() != "@@" && !lines.peek().startsWith("@@ ")) {
                            val line = lines.take()
                            if (line.isEmpty()) { before += ""; after += ""; continue }
                            require(line[0] in " +-") { "Invalid hunk line in $target" }
                            when (line[0]) {
                                ' ' -> { val context = line.substring(1); before += context; after += context }
                                '-' -> { before += line.substring(1); changed = true }
                                '+' -> { after += line.substring(1); changed = true }
                            }
                        }
                        require(changed) { "Hunk in $target does not change text" }
                        val atEnd = lines.hasNext && lines.peek() == "*** End of File"
                        if (atEnd) lines.take()
                        if (atEnd) while (lines.hasNext && lines.peek().isBlank()) lines.take()
                        hunks += CodexPatchHunk(heading, before, after, atEnd)
                        require(hunks.size <= 1_000) { "Too many patch hunks" }
                    }
                    require(hunks.isNotEmpty() || move != null) { "Empty Update File: $target" }
                    ops += PatchTool.Op.Update(target, "", "", move, hunks, canonical = true)
                }
                else -> error("Unknown canonical patch marker: $marker")
            }
            require(ops.size <= 100) { "Too many patch operations" }
        }
        require(ops.isNotEmpty()) { "Patch has no operations" }
        return ops
    }

    /** Kotlin lines() delimiter semantics without retaining a whole-input line list.
     * Only CR/LF edges are trimmed; first/last marker whitespace rules remain unchanged. */
    private class LineCursor(private val text: String) {
        private var start = 0
        private var end = text.length
        private var position = 0
        private val lastStart: Int
        private var cached: String? = null
        val first: String
        val last: String
        val hasNext: Boolean get() = cached != null || position < lastStart
        init {
            while (start < end && (text[start] == '\r' || text[start] == '\n')) start++
            while (end > start && (text[end - 1] == '\r' || text[end - 1] == '\n')) end--
            var boundary = end
            while (boundary > start && text[boundary - 1] != '\r' && text[boundary - 1] != '\n') boundary--
            lastStart = boundary
            last = text.substring(lastStart, end)
            position = start
            first = readLine()
        }
        private fun readLine(): String {
            val from = position
            while (position < end && text[position] != '\r' && text[position] != '\n') position++
            val line = text.substring(from, position)
            if (position < end) {
                val separator = text[position++]
                if (separator == '\r' && position < end && text[position] == '\n') position++
            }
            return line
        }
        fun peek(): String {
            check(hasNext)
            return cached ?: readLine().also { cached = it }
        }
        fun take(): String = peek().also { cached = null }
    }

    /** Apply normalizes only CRLF before indexing. Lone CR remains literal text, unlike parse.
     * Index exactly models removeSuffix(LF).split(LF), including retained empty final lines. */
    private class ApplyLineIndex(private val normalized: String) {
        private val end = normalized.length - if (normalized.endsWith('\n')) 1 else 0
        private val starts: IntArray
        val size: Int get() = starts.size
        init {
            var count = if (normalized.isEmpty()) 0 else 1
            for (i in 0 until end) if (normalized[i] == '\n') count++
            starts = IntArray(count)
            var next = 1
            for (i in 0 until end) if (normalized[i] == '\n') starts[next++] = i + 1
        }
        private fun lineEnd(index: Int): Int = if (index + 1 < size) starts[index + 1] - 1 else end
        fun matches(index: Int, text: String): Boolean {
            val from = starts[index]
            val length = lineEnd(index) - from
            return length == text.length && normalized.regionMatches(from, text, 0, length)
        }
        fun appendTo(target: StringBuilder, index: Int) { target.append(normalized, starts[index], lineEnd(index)) }
    }

    fun apply(text: String, hunks: List<CodexPatchHunk>, path: String): String {
        if (hunks.isEmpty()) return text // A move without text changes.
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val normalized = text.replace("\r\n", "\n")
        val trailingNewline = normalized.endsWith('\n')
        val original = ApplyLineIndex(normalized)
        val result = StringBuilder()
        var resultLines = 0
        fun appendLine(line: String) {
            if (resultLines > 0) result.append(newline)
            result.append(line)
            resultLines++
        }
        var cursor = 0
        var comparisons = 0
        fun spend() {
            comparisons++
            require(comparisons <= 2_000_000) { "Patch context search exceeded the mobile work budget; split the patch or add @@ headings" }
        }
        for (hunk in hunks) {
            var from = cursor
            hunk.heading?.let { heading ->
                val anchor = (cursor until original.size).firstOrNull { spend(); original.matches(it, heading) }
                    ?: error("Hunk heading not found in $path: $heading")
                from = anchor + 1
            }
            var match = if (hunk.before.isEmpty()) original.size else -1
            if (hunk.before.isNotEmpty()) {
                val first = if (hunk.atEnd) original.size - hunk.before.size else from
                val last = original.size - hunk.before.size
                for (candidate in first..last) {
                    if (candidate < from) continue
                    val matches = hunk.before.indices.all {
                        spend(); original.matches(candidate + it, hunk.before[it])
                    }
                    if (matches) {
                        require(match == -1) { "Hunk in $path is ambiguous; read current text and add context" }
                        match = candidate
                    }
                }
            }
            require(match >= 0) { "Hunk in $path has no exact match; read current text and add context" }
            val start = match
            require(start >= cursor) { "Overlapping or out-of-order hunks in $path" }
            for (index in cursor until start) {
                if (resultLines > 0) result.append(newline)
                original.appendTo(result, index); resultLines++
            }
            for (line in hunk.after) appendLine(line)
            cursor = start + hunk.before.size
        }
        for (index in cursor until original.size) {
            if (resultLines > 0) result.append(newline)
            original.appendTo(result, index); resultLines++
        }
        if (resultLines > 0 && (trailingNewline || text.isEmpty())) result.append(newline)
        return result.toString()
    }
}
