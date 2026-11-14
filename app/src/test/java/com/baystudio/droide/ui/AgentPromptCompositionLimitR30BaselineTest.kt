package com.baystudio.droide.ui

import com.baystudio.droide.core.AgentService
import com.baystudio.droide.core.ChatMediaRef
import org.junit.Assert.*
import org.junit.Test

/** Pure existing-API baseline; no Android owner, process, network, media file, or backend. */
class AgentPromptCompositionLimitR30BaselineTest {
    @Test(timeout = 5_000) fun overLimitUserTextWithoutAttachmentsFailsInsteadOfTruncating() {
        val userText = "x".repeat(64_001)
        assertEquals(AgentService.MAX_USER_MESSAGE_CHARS + 1, userText.length)
        assertThrows(IllegalArgumentException::class.java) { composeAgentPrompt(userText, emptyList()) }
    }

    @Test(timeout = 5_000) fun underLimitUserTextWithContextCrossingTheCombinedLimitFails() {
        val attachment = AgentAttachedContext("context.txt", "Attached context must remain complete.")
        val envelope = "\n\n[Attached context]\n\n--- context.txt ---\n${attachment.text}\n"
        val userText = "u".repeat(64_001 - envelope.length)
        assertTrue(userText.length < AgentService.MAX_USER_MESSAGE_CHARS)
        assertEquals(64_001, userText.length + envelope.length)
        assertThrows(IllegalArgumentException::class.java) { composeAgentPrompt(userText, listOf(attachment)) }
    }

    @Test(timeout = 5_000) fun underLimitPromptKeepsExactUnicodeMediaReceiptAndExcerptWarnings() {
        val ref = ChatMediaRef("fixture", ".droide/chat-media/sample.mp4", "sample.mp4", "video/mp4", 42L, 1L, 2L)
        val attachment = AgentAttachedContext(ref.name, "Preview \uD83E\uDDEA", media = ref, previewTruncated = true)
        val expected = "Review \uD83E\uDDEA\n\n[Attached context]\n\n--- sample.mp4 ---\n" +
            "[Chat attachment: path=.droide/chat-media/sample.mp4; type=video/mp4; bytes=42; expires_at=2]\n" +
            "[Text excerpt is incomplete; use read_file with this path and next_offset to read the rest.]\n" +
            "[Video input consists of bounded sampled frames only. Audio and unsampled intervals were not analyzed.]\n" +
            "Preview \uD83E\uDDEA\n"
        assertEquals(expected, composeAgentPrompt("Review \uD83E\uDDEA", listOf(attachment)))
    }

    @Test(timeout = 5_000) fun exactlyAtTheLimitKeepsItsFinalSurrogatePairWhole() {
        val text = "x".repeat(63_998) + "\uD83E\uDDEA"
        assertEquals(64_000, text.length)
        assertEquals(text, composeAgentPrompt(text, emptyList()))
    }

    @Test(timeout = 5_000) fun exactlyAtTheCombinedLimitPreservesTheEntireEnvelope() {
        val attachment = AgentAttachedContext("context.txt", "Complete context \uD83E\uDDEA")
        val envelope = "\n\n[Attached context]\n\n--- context.txt ---\n${attachment.text}\n"
        val text = "u".repeat(64_000 - envelope.length)
        val result = composeAgentPrompt(text, listOf(attachment))
        assertEquals(64_000, result.length)
        assertEquals(text + envelope, result)
    }

    @Test(timeout = 5_000) fun oneUnitPastTheCombinedLimitThrowsTheSpecificValidationFailure() {
        val attachment = AgentAttachedContext("context.txt", "Complete context \uD83E\uDDEA")
        val envelope = "\n\n[Attached context]\n\n--- context.txt ---\n${attachment.text}\n"
        val text = "u".repeat(64_001 - envelope.length)
        val failure = assertThrows(AgentPromptLimitException::class.java) {
            composeAgentPrompt(text, listOf(attachment))
        }
        assertEquals(AGENT_PROMPT_LIMIT_ERROR, failure.message)
        assertEquals(64_001, text.length + envelope.length)
        assertEquals("Complete context \uD83E\uDDEA", attachment.text)
    }
}
