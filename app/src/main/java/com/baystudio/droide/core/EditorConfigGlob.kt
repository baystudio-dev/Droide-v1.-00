package com.baystudio.droide.core

/** Project globs are data, never executable regular expressions. */
internal object EditorConfigGlob {
    const val MAX_PATTERN_CHARS = 512
    private const val MAX_PATH_CHARS = 4_096
    private const val MAX_VARIANTS = 32
    private const val MAX_EXPANSION_STEPS = 512
    private const val MAX_PENDING_VARIANTS = 512
    private const val MAX_CHOICES = 16

    class Budget(var remainingCells: Int = 2_000_000)

    private sealed interface Token
    private data class Literal(val char: Char) : Token
    private data object AnyCharacter : Token
    private data class Star(val crossesDirectories: Boolean) : Token
    private data object DirectoryStar : Token
    private data class CharacterClass(val ranges: List<IntRange>, val negated: Boolean) : Token {
        fun contains(char: Char): Boolean = ranges.any { char.code in it } != negated
    }

    fun matches(pattern: String, path: String, budget: Budget): Boolean {
        if (pattern.isEmpty() || pattern.length > MAX_PATTERN_CHARS || path.length > MAX_PATH_CHARS) return false
        val normalized = path.replace('\\', '/')
        for (glob in expandBraces(pattern)) {
            val candidate = if (glob.startsWith('/') || '/' in glob) normalized else normalized.substringAfterLast('/')
            val tokens = tokenize(glob.removePrefix("/")) ?: continue
            val cells = tokens.size.toLong() * (candidate.length + 1L)
            if (cells > budget.remainingCells) return false
            budget.remainingCells -= cells.toInt()
            var previous = BooleanArray(candidate.length + 1).also { it[0] = true }
            for (token in tokens) {
                val next = BooleanArray(candidate.length + 1)
                when (token) {
                    is Star -> {
                        next[0] = previous[0]
                        for (i in 1..candidate.length) next[i] = previous[i] ||
                            (next[i - 1] && (token.crossesDirectories || candidate[i - 1] != '/'))
                    }
                    DirectoryStar -> {
                        var earlierMatch = false
                        for (i in 0..candidate.length) {
                            next[i] = previous[i] || (i > 0 && earlierMatch && candidate[i - 1] == '/')
                            earlierMatch = earlierMatch || previous[i]
                        }
                    }
                    else -> for (i in 1..candidate.length) {
                        val char = candidate[i - 1]
                        next[i] = previous[i - 1] && when (token) {
                            is Literal -> char == token.char
                            AnyCharacter -> char != '/'
                            is CharacterClass -> char != '/' && token.contains(char)
                            else -> false
                        }
                    }
                }
                previous = next
            }
            if (previous[candidate.length]) return true
        }
        return false
    }

    /** Ordered depth-first expansion, bounded before allocation rather than after recursion. */
    private fun expandBraces(pattern: String): List<String> {
        val pending = ArrayDeque<String>().also { it.addLast(pattern) }
        val completed = ArrayList<String>(MAX_VARIANTS)
        var steps = 0
        while (pending.isNotEmpty() && completed.size < MAX_VARIANTS && steps++ < MAX_EXPANSION_STEPS) {
            val current = pending.removeFirst()
            var start = -1
            var end = -1
            val choices = ArrayList<String>()
            var scan = 0
            // Non-choice braces are literal. Keep looking inside/after them for
            // real alternatives, as EditorConfig's brace expansion does.
            while (scan < current.length) {
                if (current[scan] == '\\') { scan += 2; continue }
                if (current[scan] != '{') { scan++; continue }
                val opening = scan
                var depth = 1
                var from = opening + 1
                var index = from
                var hasComma = false
                choices.clear()
                while (index < current.length) {
                    when (current[index]) {
                        '\\' -> index++
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                if (hasComma) {
                                    if (choices.size < MAX_CHOICES) choices += current.substring(from, index)
                                    start = opening
                                    end = index
                                }
                                break
                            }
                        }
                        ',' -> if (depth == 1) {
                            hasComma = true
                            if (choices.size < MAX_CHOICES) choices += current.substring(from, index)
                            from = index + 1
                        }
                    }
                    index++
                }
                if (start >= 0) break
                scan = opening + 1
            }
            if (start < 0) { completed += current; continue }
            for (choice in choices.take(MAX_PENDING_VARIANTS - pending.size).asReversed()) {
                val expanded = current.substring(0, start) + choice + current.substring(end + 1)
                if (expanded.length <= MAX_PATTERN_CHARS) pending.addFirst(expanded)
            }
        }
        return completed
    }

    private fun tokenize(glob: String): List<Token>? {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < glob.length) {
            when (val char = glob[i++]) {
                '\\' -> {
                    if (i == glob.length) return null
                    tokens += Literal(glob[i++])
                }
                '*' -> {
                    val start = i - 1
                    while (i < glob.length && glob[i] == '*') i++
                    val recursive = i - start >= 2
                    if (recursive && i < glob.length && glob[i] == '/') {
                        tokens += DirectoryStar
                        i++
                    } else tokens += Star(recursive)
                }
                '?' -> tokens += AnyCharacter
                '[' -> {
                    var close = i
                    while (close < glob.length && glob[close] != ']') {
                        if (glob[close] == '\\') close++
                        close++
                    }
                    if (close >= glob.length) return null
                    val ranges = ArrayList<IntRange>()
                    val negated = i < close && glob[i] == '!'
                    if (negated) i++
                    while (i < close) {
                        val from = if (glob[i] == '\\') {
                            if (++i >= close) return null
                            glob[i++].code
                        } else glob[i++].code
                        if (i + 1 < close && glob[i] == '-') {
                            i++
                            val to = if (glob[i] == '\\') {
                                if (++i >= close) return null
                                glob[i++].code
                            } else glob[i++].code
                            if (to < from) return null
                            ranges += from..to
                        } else ranges += from..from
                    }
                    if (ranges.isEmpty()) return null
                    tokens += CharacterClass(ranges, negated)
                    i = close + 1
                }
                else -> tokens += Literal(char)
            }
        }
        return tokens
    }
}
