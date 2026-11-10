package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class ApprovalInputReviewTest {
    private fun readAll(review: ApprovalInputReview): String = buildString {
        var index = 0
        do { val page = review.readPage(index++); append(page.text) } while (page.hasNext)
    }
    @Test fun completeCommandPastPreviewAndOldLimit() {
        val command = "echo " + "x".repeat(40_000) + "; dangerous-suffix"
        val review = ApprovalInputReview.text(ApprovalInputReview.Label.COMMAND, command, "Background: false; TTY: false")
        assertEquals(command, readAll(review))
        assertEquals(command.toByteArray().size.toLong(), review.byteLength)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(command.toByteArray()).joinToString("") { "%02x".format(it) }, review.sha256)
        assertFalse(review.toString().contains("dangerous-suffix"))
    }
    @Test fun surrogatePairAtPageBoundaryIsPreserved() {
        val text = "x".repeat(4095) + "\uD83D\uDE00" + "y".repeat(9000)
        val review = ApprovalInputReview.text(ApprovalInputReview.Label.STDIN, text)
        assertEquals(text, readAll(review))
        assertFalse(Character.isHighSurrogate(review.readPage(0).text.last()))
        assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), review.byteLength)
    }
    @Test fun controlsAndEmptyInputPreserved() {
        for (text in listOf("", "a\r\n\t\u0003b"))
            assertEquals(text, readAll(ApprovalInputReview.text(ApprovalInputReview.Label.STDIN, text)))
    }
    @Test fun invalidUnicodeRejected() {
        try { ApprovalInputReview.text(ApprovalInputReview.Label.COMMAND, "\uD800"); fail("Expected rejection") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun revokedOwnerCannotRead() {
        var current = true
        val review = ApprovalInputReview.text(ApprovalInputReview.Label.COMMAND, "secret") { check(current) }
        current = false
        try { review.readPage(0); fail("Expected rejection") } catch (_: IllegalStateException) { }
    }
    @Test fun requestRevocationIndependentOfOwner() {
        var current = true
        val review = ApprovalInputReview.text(ApprovalInputReview.Label.COMMAND, "secret").bindCurrentRequest { check(current) }
        assertEquals("secret", review.readPage(0).text)
        current = false
        try { review.readPage(0); fail("Expected rejection") } catch (_: IllegalStateException) { }
    }
    @Test fun cancellationPropagates() {
        val review = ApprovalInputReview.text(ApprovalInputReview.Label.STDIN, "input")
        try { review.readPage(0) { throw java.util.concurrent.CancellationException() }; fail("Expected cancellation") }
        catch (_: java.util.concurrent.CancellationException) { }
    }
}
