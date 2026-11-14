package com.baystudio.droide.core

import java.io.InputStream
import java.nio.charset.CharacterCodingException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Fail visibly rather than searching with missing/partially loaded exclusions. */
internal class RootIgnoreException(message: String, cause: Throwable? = null) :
    IllegalStateException("Root .gitignore filtering unavailable: $message", cause)

/** Root-only semantics. Dynamic programming avoids regex backtracking; a query owns its work budget. */
internal class RootIgnoreRules private constructor(
    private val rules: List<Rule>,
    private val checkActive: () -> Unit = {},
) {
    private data class Rule(val tokens: List<Token>, val anchored: Boolean,
        val negated: Boolean, val directoryOnly: Boolean)
    private sealed interface Token {
        data class Literal(val char: Char) : Token
        data class CharacterClass(val ranges: List<CharRange>, val negated: Boolean) : Token
        data object AnyChar : Token
        data object Star : Token
        data object GlobStar : Token
        data class Directories(val requireOne: Boolean) : Token
    }
    private var workRemaining = MAX_QUERY_WORK

    fun isIgnored(path: String, isDirectory: Boolean = false): Boolean {
        checkActive()
        if (rules.isEmpty()) return false
        if (path.length > MAX_PATH_CHARS) throw RootIgnoreException("path exceeds the safe matching length")
        var slash = path.indexOf('/')
        while (slash >= 0) {
            if (entryIgnored(path.substring(0, slash), true)) return true
            slash = path.indexOf('/', slash + 1)
        }
        return entryIgnored(path, isDirectory)
    }

    /** A child negation cannot reopen an excluded ancestor, so excluded directories may be pruned. */
    fun canDescend(path: String): Boolean = !isIgnored(path, isDirectory = true)

    private fun entryIgnored(path: String, isDirectory: Boolean): Boolean {
        // Include ancestor-path processing in the query budget; compute basename once per node.
        charge(path.length + 1)
        val basename = path.substringAfterLast('/')
        for (index in rules.indices.reversed()) {
            charge(1)
            val rule = rules[index]
            if ((!rule.directoryOnly || isDirectory) &&
                matches(rule.tokens, if (rule.anchored) path else basename)) return !rule.negated
        }
        return false
    }

    private fun charge(amount: Int) {
        checkActive()
        if (amount > workRemaining) throw RootIgnoreException("matching work limit reached; simplify the ignore rules")
        workRemaining -= amount
    }

    private fun matches(tokens: List<Token>, path: String): Boolean {
        var previous = BooleanArray(path.length + 1).also { it[0] = true }
        var next = BooleanArray(path.length + 1)
        for (token in tokens) {
            val classCost = if (token is Token.CharacterClass) token.ranges.size.coerceAtLeast(1) else 1
            charge((path.length + 1) * classCost)
            next.fill(false)
            when (token) {
                Token.Star, Token.GlobStar -> {
                    next[0] = previous[0]
                    for (i in path.indices) next[i + 1] = previous[i + 1] ||
                        next[i] && (token == Token.GlobStar || path[i] != '/')
                }
                is Token.Directories -> {
                    if (!token.requireOne) previous.copyInto(next)
                    var component = false
                    for (i in path.indices) {
                        if (path[i] == '/') {
                            if (component) next[i + 1] = true
                            component = false
                        } else component = component || previous[i] || next[i]
                    }
                }
                else -> for (i in path.indices) if (previous[i]) {
                    val char = path[i]
                    next[i + 1] = when (token) {
                        is Token.Literal -> char == token.char
                        Token.AnyChar -> char != '/'
                        is Token.CharacterClass -> char != '/' &&
                            (token.ranges.any { char in it } != token.negated)
                        else -> false
                    }
                }
            }
            val reusable = previous
            previous = next
            next = reusable
            if (previous.none { it }) return false
        }
        return previous[path.length]
    }

    companion object {
        const val MAX_BYTES = 1_000_000L
        const val MAX_RULES = 2048
        const val MAX_LINE_CHARS = 4096
        private const val MAX_PATH_CHARS = 4096
        private const val MAX_QUERY_WORK = 50_000_000
        val EMPTY = RootIgnoreRules(emptyList())

        suspend fun read(input: InputStream): RootIgnoreRules {
            val context = currentCoroutineContext()
            val text = try {
                BoundedUtf8.read(input, MAX_BYTES) {
                    throw RootIgnoreException("file exceeds $MAX_BYTES bytes; exclusions were not applied")
                }
            } catch (invalid: CharacterCodingException) {
                throw RootIgnoreException("file is not valid UTF-8", invalid)
            }
            return parseSequence(text.lineSequence()) { context.ensureActive() }
        }

        fun parse(lines: List<String>): RootIgnoreRules = parseSequence(lines.asSequence()) {}

        private fun parseSequence(lines: Sequence<String>, checkActive: () -> Unit): RootIgnoreRules {
            val rules = ArrayList<Rule>()
            var bytes = 0L
            lines.forEachIndexed { index, raw ->
                checkActive()
                if (raw.length > MAX_LINE_CHARS) throw RootIgnoreException("line ${index + 1} exceeds $MAX_LINE_CHARS characters")
                bytes += raw.toByteArray(Charsets.UTF_8).size.toLong() + if (index == 0) 0 else 1
                if (bytes > MAX_BYTES) throw RootIgnoreException("file exceeds $MAX_BYTES bytes")
                parseRule(if (index == 0) raw.removePrefix("\uFEFF") else raw)?.let {
                    if (rules.size >= MAX_RULES) throw RootIgnoreException("more than $MAX_RULES rules")
                    rules += it
                }
            }
            return RootIgnoreRules(rules, checkActive)
        }

        private fun parseRule(raw: String): Rule? {
            var pattern = trimTrailingSpaces(raw)
            if (pattern.isEmpty() || pattern.startsWith('#')) return null
            val negated = pattern.startsWith('!')
            if (negated) pattern = pattern.drop(1)
            val directoryOnly = pattern.endsWith('/')
            if (directoryOnly) pattern = pattern.dropLast(1)
            val leadingSlash = pattern.startsWith('/')
            if (leadingSlash) pattern = pattern.drop(1)
            if (pattern.isEmpty()) return null
            return Rule(tokens(pattern) ?: return null, leadingSlash || '/' in pattern, negated, directoryOnly)
        }

        private fun trimTrailingSpaces(raw: String): String {
            var end = raw.length
            while (end > 0 && raw[end - 1] == ' ') {
                var backslashes = 0
                var index = end - 2
                while (index >= 0 && raw[index] == '\\') { backslashes++; index-- }
                if (backslashes % 2 != 0) break
                end--
            }
            return raw.substring(0, end)
        }

        private fun tokens(pattern: String): List<Token>? {
            val tokens = ArrayList<Token>()
            var index = 0
            while (index < pattern.length) {
                when (val char = pattern[index]) {
                    '\\' -> {
                        if (++index == pattern.length) return null
                        tokens += Token.Literal(pattern[index++])
                    }
                    '*' -> {
                        val start = index
                        while (index < pattern.length && pattern[index] == '*') index++
                        val separatorLength = when {
                            index < pattern.length && pattern[index] == '/' -> 1
                            pattern.startsWith("\\/", index) -> 2
                            else -> 0
                        }
                        val component = (start == 0 || pattern[start - 1] == '/') &&
                            (index == pattern.length || separatorLength > 0)
                        if (index - start >= 2 && component) {
                            if (separatorLength > 0) {
                                tokens += Token.Directories(requireOne = separatorLength == 2)
                                index += separatorLength
                            } else tokens += Token.GlobStar
                        } else tokens += Token.Star
                    }
                    '?' -> { tokens += Token.AnyChar; index++ }
                    '[' -> {
                        val parsed = characterClass(pattern, index) ?: return null
                        tokens += parsed.first; index = parsed.second
                    }
                    else -> { tokens += Token.Literal(char); index++ }
                }
            }
            return tokens
        }

        private fun characterClass(pattern: String, start: Int): Pair<Token.CharacterClass, Int>? {
            var index = start + 1
            val negated = index < pattern.length && pattern[index] in "!^"
            if (negated) index++
            val first = index
            val ranges = ArrayList<CharRange>()
            var previous: Char? = null
            while (index < pattern.length) {
                val char = pattern[index]
                if (char == ']' && index > first) return Token.CharacterClass(ranges, negated) to index + 1
                if (char == '\\') {
                    if (++index == pattern.length) return null
                    previous = pattern[index]; ranges += pattern[index]..pattern[index]
                } else if (char == '-' && previous != null && index + 1 < pattern.length && pattern[index + 1] != ']') {
                    if (pattern[++index] == '\\' && ++index == pattern.length) return null
                    val end = pattern[index]
                    if (previous <= end) ranges += previous..end
                    previous = null
                } else if (pattern.startsWith("[:", index)) {
                    val end = pattern.indexOf(":]", index + 2)
                    if (end < 0) return null
                    ranges += posixClasses[pattern.substring(index + 2, end)] ?: return null
                    index = end + 2; previous = null; continue
                } else { ranges += char..char; previous = char }
                index++
            }
            return null
        }

        private val posixClasses = mapOf(
            "alnum" to listOf('A'..'Z', 'a'..'z', '0'..'9'), "alpha" to listOf('A'..'Z', 'a'..'z'),
            "blank" to listOf(' '..' ', '\t'..'\t'), "cntrl" to listOf('\u0000'..'\u001f', '\u007f'..'\u007f'),
            "digit" to listOf('0'..'9'), "graph" to listOf('!'..'~'), "lower" to listOf('a'..'z'),
            "print" to listOf(' '..'~'), "punct" to listOf('!'..'/', ':'..'@', '['..'`', '{'..'~'),
            "space" to listOf(' '..' ', '\t'..'\r'), "upper" to listOf('A'..'Z'),
            "xdigit" to listOf('A'..'F', 'a'..'f', '0'..'9'),
        )
    }
}
