package com.baystudio.droide.core

import java.io.OutputStream
import java.util.Collections

/**
 * An on-demand, immutable copy of an already-retained terminal transcript.
 *
 * All lengths and offsets are UTF-16 units. Capture rejects oversized or malformed
 * input rather than silently trimming it or changing bytes. Runtime capture owns
 * choosing the retained tail; this helper does no work for terminal output events.
 */
internal class TerminalRetainedHistory private constructor(val text: String) {
    data class Match(
        val startUtf16: Int,
        val endUtf16Exclusive: Int,
        val snippet: String,
        val snippetStartUtf16: Int,
    )

    data class SearchResult(
        val matches: List<Match>,
        /** True only when another match was found beyond the returned limit. */
        val capped: Boolean,
    )

    /**
     * Finds literal, non-overlapping matches in original-text order. Empty queries
     * have no matches. Case-insensitive comparison uses Kotlin/JVM regionMatches
     * semantics without case-folded copies, locale dependence, or changed offsets.
     * Snippets preserve the original text, including whitespace and newlines.
     *
     * The callback may throw to cancel; its exception is propagated unchanged.
     * It runs between blocks of at most 256 candidate comparisons, each comparing
     * at most MAX_QUERY_UTF16 units. Results never retain an unbounded match list.
     */
    fun search(
        query: String,
        ignoreCase: Boolean = false,
        checkCancelled: () -> Unit = {},
    ): SearchResult {
        validateUtf16(query, MAX_QUERY_UTF16, "Query", checkCancelled)
        if (query.isEmpty()) return SearchResult(emptyList(), capped = false)

        val matches = ArrayList<Match>()
        val lastCandidate = text.length - query.length
        var candidate = 0
        var comparisonsUntilCheck = 0
        while (candidate <= lastCandidate) {
            if (comparisonsUntilCheck == 0) {
                checkCancelled()
                comparisonsUntilCheck = CANCELLATION_INTERVAL
            }
            comparisonsUntilCheck--
            if (text.regionMatches(candidate, query, 0, query.length, ignoreCase)) {
                if (matches.size == MAX_MATCHES) {
                    checkCancelled()
                    return SearchResult(Collections.unmodifiableList(matches), capped = true)
                }
                val end = candidate + query.length
                var snippetStart = (candidate - SNIPPET_CONTEXT_UTF16).coerceAtLeast(0)
                var snippetEnd = (end + SNIPPET_CONTEXT_UTF16).coerceAtMost(text.length)
                // Move context cuts inward so the bound is never exceeded and a
                // complete match is kept even when adjacent context contains emoji.
                if (snippetStart < text.length && Character.isLowSurrogate(text[snippetStart])) {
                    snippetStart++
                }
                if (snippetEnd < text.length && Character.isLowSurrogate(text[snippetEnd])) {
                    snippetEnd--
                }
                matches += Match(candidate, end, text.substring(snippetStart, snippetEnd), snippetStart)
                candidate = end
            } else {
                // A validated query cannot begin with an isolated low surrogate.
                candidate += if (Character.isHighSurrogate(text[candidate])) 2 else 1
            }
        }
        checkCancelled()
        return SearchResult(Collections.unmodifiableList(matches), capped = false)
    }

    /**
     * Writes precisely this snapshot as UTF-8, without a BOM, header, or added
     * newline. At most MAX_UTF8_BYTES bytes can be written, in pair-safe chunks of
     * at most 4096 UTF-16 units. Returns the number of bytes successfully written.
     *
     * The caller owns the stream: this method neither flushes nor closes it.
     * Cancellation and output errors are propagated unchanged and can leave a
     * partial document; the caller owns destination cleanup and success reporting.
     * No terminal content is logged or included in validation error messages.
     */
    fun writeUtf8(output: OutputStream, checkCancelled: () -> Unit = {}): Int {
        var start = 0
        var written = 0
        checkCancelled()
        while (start < text.length) {
            var end = (start + EXPORT_CHUNK_UTF16).coerceAtMost(text.length)
            if (end < text.length && Character.isLowSurrogate(text[end])) end--
            val bytes = text.substring(start, end).toByteArray(Charsets.UTF_8)
            checkCancelled()
            output.write(bytes)
            written += bytes.size
            start = end
            checkCancelled()
        }
        return written
    }

    companion object {
        const val MAX_TEXT_UTF16 = 300_000
        const val MAX_QUERY_UTF16 = 256
        const val MAX_MATCHES = 500
        const val MAX_SNIPPET_UTF16 = 384
        const val MAX_UTF8_BYTES = MAX_TEXT_UTF16 * 3
        private const val SNIPPET_CONTEXT_UTF16 = (MAX_SNIPPET_UTF16 - MAX_QUERY_UTF16) / 2
        private const val CANCELLATION_INTERVAL = 256
        private const val EXPORT_CHUNK_UTF16 = 4096

        fun capture(text: String, checkCancelled: () -> Unit = {}): TerminalRetainedHistory {
            validateUtf16(text, MAX_TEXT_UTF16, "Snapshot", checkCancelled)
            return TerminalRetainedHistory(text)
        }

        private fun validateUtf16(
            value: String,
            limit: Int,
            label: String,
            checkCancelled: () -> Unit,
        ) {
            checkCancelled()
            require(value.length <= limit) { "$label exceeds its UTF-16 limit" }
            var index = 0
            var nextCheck = CANCELLATION_INTERVAL
            while (index < value.length) {
                if (index >= nextCheck) {
                    checkCancelled()
                    nextCheck = index + CANCELLATION_INTERVAL
                }
                val unit = value[index]
                when {
                    Character.isHighSurrogate(unit) -> {
                        require(index + 1 < value.length && Character.isLowSurrogate(value[index + 1])) {
                            "$label contains malformed UTF-16"
                        }
                        index += 2
                    }
                    Character.isLowSurrogate(unit) -> throw IllegalArgumentException("$label contains malformed UTF-16")
                    else -> index++
                }
            }
            checkCancelled()
        }
    }
}
