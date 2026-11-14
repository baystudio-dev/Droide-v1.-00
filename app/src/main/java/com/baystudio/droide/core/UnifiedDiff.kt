package com.baystudio.droide.core

 
enum class DiffLineKind { CONTEXT, ADD, REMOVE, META }

data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    val oldLine: Int? = null,
    val newLine: Int? = null,
)

data class DiffHunk(
    val header: String,
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>,
)

data class DiffFile(
    val oldPath: String?,
    val newPath: String?,
    val headers: List<String>,
    val hunks: List<DiffHunk>,
)

object UnifiedDiffParser {
    private val hunk = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$")
    private val legacyHunk = Regex("^@@ old (\\d+)-(\\d+) \\| new (\\d+)-(\\d+) @@.*$")

    private val legacyTruncation = Regex("^(?:-… [1-9][0-9]* more removed lines …|\\+… [1-9][0-9]* more added lines …)$")

    fun parse(text: String, maxLines: Int = 4_000): List<DiffFile> {
        if (text.isBlank()) return emptyList()
        val source = text.lineSequence().take(maxLines.coerceIn(1, 20_000)).toList()
        val result = mutableListOf<DiffFile>()
        var oldPath: String? = null
        var newPath: String? = null
        var headers = mutableListOf<String>()
        var hunks = mutableListOf<DiffHunk>()
        var i = 0

        fun flushFile() {
            if (headers.isNotEmpty() || hunks.isNotEmpty() || oldPath != null || newPath != null) {
                result += DiffFile(oldPath, newPath, headers.toList(), hunks.toList())
            }
            oldPath = null
            newPath = null
            headers = mutableListOf()
            hunks = mutableListOf()
        }

        while (i < source.size) {
            val line = source[i]
            if (line.startsWith("diff --git ")) {
                if (headers.isNotEmpty() || hunks.isNotEmpty()) flushFile()
                headers += line
                val parts = line.removePrefix("diff --git ").split(' ', limit = 2)
                oldPath = parts.getOrNull(0)?.removePrefix("a/")
                newPath = parts.getOrNull(1)?.removePrefix("b/")
                i++
                continue
            }
            if (line.startsWith("--- ")) {
                if (hunks.isNotEmpty()) flushFile()
                oldPath = normalizePatchPath(line.removePrefix("--- ").substringBefore('\t'))
                headers += line
                i++
                continue
            }
            if (line.startsWith("+++ ")) {
                newPath = normalizePatchPath(line.removePrefix("+++ ").substringBefore('\t'))
                headers += line
                i++
                continue
            }
            val legacy = legacyHunk.matchEntire(line)
            val match = hunk.matchEntire(line) ?: legacy
            if (match != null) {
                val oldStart = match.groupValues[1].toIntOrNull()
                val oldCount = if (legacy != null) match.groupValues[2].toIntOrNull()?.let { end -> oldStart?.let { (end - it + 1).coerceAtLeast(0) } }
                    else match.groupValues[2].ifBlank { "1" }.toIntOrNull()
                val newStart = match.groupValues[3].toIntOrNull()
                val newCount = if (legacy != null) match.groupValues[4].toIntOrNull()?.let { end -> newStart?.let { (end - it + 1).coerceAtLeast(0) } }
                    else match.groupValues[4].ifBlank { "1" }.toIntOrNull()
                if (oldStart == null || oldCount == null || newStart == null || newCount == null) {
                    // Tool output can contain an incomplete or malformed hunk.
                    // Keep its text visible rather than throwing during UI composition.
                    headers += line
                    i++
                    continue
                }
                var oldLine: Int? = oldStart
                var newLine: Int? = newStart
                var consumedOld = 0
                var consumedNew = 0
                fun advance(value: Int?) = value?.takeIf { it < Int.MAX_VALUE }?.plus(1)
                val lines = mutableListOf<DiffLine>()
                val header = line
                i++
                while (i < source.size) {
                    val body = source[i]
                    if (body.startsWith("diff --git ") || hunk.matches(body) || legacyHunk.matches(body)) break
                    if (consumedOld >= oldCount && consumedNew >= newCount && !body.startsWith("\\ No newline")) break
                    when {
                        legacy != null && legacyTruncation.matches(body) -> lines += DiffLine(DiffLineKind.META, body)
                        body.startsWith("+") -> {
                            lines += DiffLine(DiffLineKind.ADD, body.drop(1), null, newLine)
                            newLine = advance(newLine)
                            consumedNew++
                        }
                        body.startsWith("-") -> {
                            lines += DiffLine(DiffLineKind.REMOVE, body.drop(1), oldLine, null)
                            oldLine = advance(oldLine)
                            consumedOld++
                        }
                        body.startsWith(" ") -> {
                            lines += DiffLine(DiffLineKind.CONTEXT, body.drop(1), oldLine, newLine)
                            oldLine = advance(oldLine)
                            newLine = advance(newLine)
                            consumedOld++
                            consumedNew++
                        }
                        else -> lines += DiffLine(DiffLineKind.META, body)
                    }
                    i++
                }
                hunks += DiffHunk(header, oldStart, oldCount, newStart, newCount, lines)
                continue
            }
            headers += line
            i++
        }
        flushFile()
        return result
    }

    private fun normalizePatchPath(path: String): String? = when (path) {
        "/dev/null" -> null
        else -> path.removePrefix("a/").removePrefix("b/")
    }
}
