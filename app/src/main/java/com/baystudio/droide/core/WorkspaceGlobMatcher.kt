package com.baystudio.droide.core

/** Finite matcher for the existing discovery grammar, not the richer root-ignore grammar. */
internal class WorkspaceGlobMatcher(pattern: String) {
    private data class Token(val kind: Int, val literal: Int = 0)
    private val tokens: List<Token>

    init {
        require(pattern.length <= 256)
        val parsed = ArrayList<Token>()
        var index = 0
        while (index < pattern.length) {
            when {
                pattern.startsWith("**/", index) -> { parsed += Token(DIRECTORIES); index += 3 }
                pattern.startsWith("**", index) -> { parsed += Token(DOT_STAR); index += 2 }
                pattern[index] == '*' -> { parsed += Token(SEGMENT_STAR); index++ }
                pattern[index] == '?' -> { parsed += Token(SEGMENT_ONE); index++ }
                else -> {
                    val first = pattern[index]
                    if (quotedSurrogatesJoin && Character.isHighSurrogate(first) && index + 1 < pattern.length &&
                        Character.isLowSurrogate(pattern[index + 1])) {
                        parsed += Token(LITERAL, Character.toCodePoint(first, pattern[index + 1]))
                        index += 2
                    } else {
                        parsed += Token(LITERAL, first.code)
                        index++
                    }
                }
            }
        }
        tokens = parsed
    }

    fun matches(path: String, budget: WorkspaceSearchBudget): Boolean {
        budget.checkpoint()
        if (path.length > budget.limits.pathChars) budget.stop(WorkspaceSearchStopReason.INPUT_LIMIT)
        val count = Character.codePointCount(path, 0, path.length)
        budget.beforeMatch((tokens.size + 1).toLong() * (count + 1).toLong())
        val points = IntArray(count)
        var pointIndex = 0
        var offset = 0
        while (offset < path.length) {
            val point = Character.codePointAt(path, offset)
            points[pointIndex++] = point
            offset += Character.charCount(point)
        }
        var previous = BooleanArray(count + 1)
        var current = BooleanArray(count + 1)
        previous[0] = true
        for (token in tokens) {
            budget.checkpoint()
            current.fill(false)
            current[0] = token.kind in STAR_KINDS && previous[0]
            var directoryRun = previous[0]
            for (index in 1..count) {
                if ((index and 0xff) == 0) budget.checkpoint()
                val point = points[index - 1]
                current[index] = when (token.kind) {
                    LITERAL -> previous[index - 1] && point == token.literal
                    SEGMENT_ONE -> previous[index - 1] && point != '/'.code
                    SEGMENT_STAR -> previous[index] || (current[index - 1] && point != '/'.code)
                    DOT_STAR -> previous[index] || (current[index - 1] && dotMatches(point))
                    DIRECTORIES -> previous[index] || (point == '/'.code && directoryRun)
                    else -> error("Unknown glob token")
                }
                if (token.kind == DIRECTORIES) directoryRun = previous[index] || (directoryRun && dotMatches(point))
            }
            val old = previous
            previous = current
            current = old
        }
        budget.checkpoint()
        return previous[count]
    }

    companion object {
        private const val LITERAL = 0
        private const val SEGMENT_ONE = 1
        private const val SEGMENT_STAR = 2
        private const val DOT_STAR = 3
        private const val DIRECTORIES = 4
        private val STAR_KINDS = setOf(SEGMENT_STAR, DOT_STAR, DIRECTORIES)

        private val dotAllowsVt = Regex(".").matches("\u000b")
        private val dotAllowsFf = Regex(".").matches("\u000c")
        private val quotedSurrogatesJoin = Regex(
            "^" + Regex.escape("\uD83D") + Regex.escape("\uDE00") + "$",
        ).matches("\uD83D\uDE00")

        private fun dotMatches(point: Int): Boolean = when (point) {
            '\n'.code, '\r'.code, 0x85, 0x2028, 0x2029 -> false
            0x0b -> dotAllowsVt
            0x0c -> dotAllowsFf
            else -> true
        }
    }
}
