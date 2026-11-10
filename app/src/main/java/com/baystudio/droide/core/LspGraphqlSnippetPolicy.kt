package com.baystudio.droide.core

/** A transport-template admission rule, not a GraphQL parser or snippet rewriter. */
internal object LspGraphqlSnippetPolicy {
    const val MAX_CHARS = 8_000
    const val MAX_MARKERS = 96
    data class Part(val literal: String? = null, val tabStop: Int? = null) {
        init { require((literal == null) != (tabStop == null)) }
    }

    /** Retain original bytes. Only fixed LF literals and numeric $n stops are admitted. */
    fun parts(source: String): List<Part> {
        require(source.length <= MAX_CHARS) { "GraphQL snippet is too large" }
        val result = ArrayList<Part>()
        var offset = 0
        var literalStart = 0
        var markers = 0
        while (offset < source.length) {
            val c = source[offset]
            require(c != '\r' && c != '\\' && c != '`' && (c >= ' ' || c == '\n' || c == '\t')) {
                "Unsupported GraphQL snippet template"
            }
            if (c.isHighSurrogate()) {
                require(offset + 1 < source.length && source[offset + 1].isLowSurrogate()) { "Invalid snippet UTF-16" }
                offset += 2
                continue
            }
            require(!c.isLowSurrogate()) { "Invalid snippet UTF-16" }
            if (c != '$') { offset++; continue }
            if (literalStart < offset) result += Part(literal = source.substring(literalStart, offset))
            val first = ++offset
            while (offset < source.length && source[offset] in '0'..'9') offset++
            require(offset > first && offset - first <= 9) { "GraphQL snippets require a numeric tab stop" }
            require(++markers <= MAX_MARKERS) { "Too many GraphQL snippet markers" }
            result += Part(tabStop = source.substring(first, offset).toInt())
            literalStart = offset
        }
        if (literalStart < source.length) result += Part(literal = source.substring(literalStart))
        return result
    }

    /** Validate parsed Sora structure without accepting a transform or alternate literal text. */
    fun requireSameParts(source: String, parsed: List<Part>) {
        val normalized = ArrayList<Part>()
        for (part in parsed) {
            require(part.tabStop == null || part.tabStop in 0..999_999_999)
            if (part.literal != null) {
                if (part.literal.isEmpty()) continue
                val previous = normalized.lastOrNull()
                if (previous?.literal != null) normalized[normalized.lastIndex] = Part(literal = previous.literal + part.literal)
                else normalized += part
            } else normalized += part
        }
        require(normalized == parts(source)) { "GraphQL snippet parser changed its native template" }
    }
}
