package com.baystudio.droide.core

import java.security.MessageDigest

/** Runtime-only immutable input evidence, never persisted or sent to a provider. A reader does
 * not grant permission; ApprovalManager binds its lifetime to the current request. */
class ApprovalInputReview private constructor(
    val label: Label,
    val byteLength: Long,
    val sha256: String,
    val optionsSummary: String,
    private val checkOwner: () -> Unit,
    private val read: (Int, () -> Unit) -> Page,
) {
    enum class Label(val title: String) { COMMAND("Command"), STDIN("Terminal input"), CUSTOM_TOOL("Tool input") }
    internal data class Page(val text: String, val hasNext: Boolean)

    internal fun requireCurrent() = checkOwner()

    internal fun readPage(index: Int, checkCancelled: () -> Unit = {}): Page {
        require(index >= 0 && index < Int.MAX_VALUE)
        val check = { checkOwner(); checkCancelled() }
        check()
        return read(index, check).also { check() }
    }

    internal fun bindCurrentRequest(checkCurrent: () -> Unit): ApprovalInputReview =
        ApprovalInputReview(label, byteLength, sha256, optionsSummary,
            { checkOwner(); checkCurrent() }, read)

    companion object {
        internal const val PAGE_CHARS = 4096

        /** The caller must execute this exact immutable String, not a subsequently re-read input. */
        internal fun text(label: Label, text: String, optionsSummary: String = "",
            checkOwner: () -> Unit = {}): ApprovalInputReview {
            checkOwner()

            var at = 0
            while (at < text.length) {
                if (at % PAGE_CHARS == 0) checkOwner()
                val c = text[at++]
                if (Character.isHighSurrogate(c)) {
                    require(at < text.length && Character.isLowSurrogate(text[at])) { "Invalid input Unicode" }
                    at++
                } else require(!Character.isLowSurrogate(c)) { "Invalid input Unicode" }
            }
            val bytes = text.toByteArray(Charsets.UTF_8)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            // Fixed-width pages align boundaries around UTF-16 surrogate pairs without losing characters.
            fun boundary(page: Int): Int {
                val offset = (page.toLong() * PAGE_CHARS).coerceAtMost(text.length.toLong()).toInt()
                return if (offset > 0 && offset < text.length && Character.isLowSurrogate(text[offset]) &&
                    Character.isHighSurrogate(text[offset - 1])) offset - 1 else offset
            }
            checkOwner()
            return ApprovalInputReview(label, bytes.size.toLong(), hash, optionsSummary, checkOwner) { index, check ->
                check()
                val start = boundary(index)
                val end = boundary(index + 1)
                Page(text.substring(start, end), end < text.length)
            }
        }

    }
    override fun toString() = "ApprovalInputReview(label=$label, bytes=$byteLength, content=redacted)"
}
