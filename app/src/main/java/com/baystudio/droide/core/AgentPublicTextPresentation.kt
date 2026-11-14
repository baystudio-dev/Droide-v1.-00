package com.baystudio.droide.core

import kotlinx.serialization.Serializable

/** UI provenance only. Never a provider role, command authority or hidden thought. */
@Serializable
enum class AgentTextPresentation { LEGACY, RESPONSE, UPDATE, ANSWER }

/** Only an adapter's documented public message phase may populate this value. */
@Serializable
enum class ProviderPublicTextPhase { UNSPECIFIED, COMMENTARY, FINAL_ANSWER }

/** UTF-16 offsets into the exact public text; no duplicated text or private payload. */
@Serializable
data class AgentPublicTextSpan(val start: Int, val endExclusive: Int, val phase: ProviderPublicTextPhase)

data class AgentPublicTextSlice(val start: Int, val endExclusive: Int, val presentation: AgentTextPresentation)

internal object AgentPublicTextPresentation {
    const val MAX_SPANS = 128

    fun valid(text: String, spans: List<AgentPublicTextSpan>): Boolean {
        if (spans.isEmpty() || spans.size > MAX_SPANS) return false
        var end = 0
        for (span in spans) {
            if (span.start != end || span.endExclusive <= span.start || span.endExclusive > text.length) return false
            if (!boundary(text, span.start) || !boundary(text, span.endExclusive)) return false
            end = span.endExclusive
        }
        return end == text.length
    }

    private fun boundary(text: String, offset: Int): Boolean = offset <= 0 || offset >= text.length ||
        !(text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())

    /** Invalid metadata cannot hide/reorder text or certify a final answer. */
    fun slices(text: String, presentation: AgentTextPresentation, spans: List<AgentPublicTextSpan>, completed: Boolean): List<AgentPublicTextSlice> {
        if (text.isEmpty()) return emptyList()
        if (presentation == AgentTextPresentation.LEGACY) return listOf(AgentPublicTextSlice(0, text.length, presentation))
        val fallback = if (presentation == AgentTextPresentation.ANSWER && !completed) AgentTextPresentation.RESPONSE else presentation
        if (spans.isEmpty()) return listOf(AgentPublicTextSlice(0, text.length, fallback))
        if (!valid(text, spans)) return listOf(AgentPublicTextSlice(0, text.length, AgentTextPresentation.RESPONSE))
        return spans.map { span ->
            val role = when {
                presentation == AgentTextPresentation.UPDATE -> AgentTextPresentation.UPDATE
                span.phase == ProviderPublicTextPhase.COMMENTARY -> AgentTextPresentation.UPDATE
                // A provider final_answer hint cannot override the coordinator's boundary.
                presentation == AgentTextPresentation.ANSWER && completed -> AgentTextPresentation.ANSWER
                else -> AgentTextPresentation.RESPONSE
            }
            AgentPublicTextSlice(span.start, span.endExclusive, role)
        }
    }

    /** Bounded preview metadata; arbitrary chunk boundaries never add visible characters. */
    fun append(text: String, previous: List<AgentPublicTextSpan>, delta: String, phase: ProviderPublicTextPhase, mergedText: String = text + delta): List<AgentPublicTextSpan> {
        if (delta.isEmpty()) return previous
        val end = text.length + delta.length
        val old = when {
            text.isEmpty() -> emptyList()
            valid(text, previous) -> previous
            else -> listOf(AgentPublicTextSpan(0, text.length, ProviderPublicTextPhase.UNSPECIFIED))
        }
        val result = if (old.lastOrNull()?.phase == phase) old.dropLast(1) + old.last().copy(endExclusive = end)
            else old + AgentPublicTextSpan(text.length, end, phase)
        // A split surrogate is neutral until complete; no code point is split in rendering.
        return result.takeIf { mergedText.length == end && valid(mergedText, it) }.orEmpty()
    }

    fun afterBoundary(stopContinues: Boolean, promotedUser: Boolean, promotedSynthetic: Boolean, promotionAbsent: Boolean): AgentTextPresentation = when {
        stopContinues -> AgentTextPresentation.UPDATE
        promotedUser && promotedSynthetic -> AgentTextPresentation.RESPONSE
        promotedSynthetic -> AgentTextPresentation.UPDATE
        promotedUser || promotionAbsent -> AgentTextPresentation.ANSWER
        else -> AgentTextPresentation.RESPONSE
    }
}
