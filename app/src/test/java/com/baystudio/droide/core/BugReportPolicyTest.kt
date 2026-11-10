package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

internal fun validBugReport() = BugReportDraft(
    title = "Editor stops responding", category = BugReportCategory.EDITOR,
    description = "The editor stops responding after opening a second file.",
    steps = "1. Open project. 2. Open two files.", privacyAcknowledged = true,
)

class BugReportPolicyTest {
    @Test fun validTextReportHasNoImplicitDeviceData() {
        val draft = validBugReport()
        assertTrue(BugReportPolicy.validate(draft).isEmpty())
        assertEquals("", draft.version); assertEquals("", draft.android)
        assertEquals("", draft.device); assertEquals("", draft.email)
        assertEquals(BugReportPriority.NORMAL, draft.priority)
    }

    @Test fun requiredFieldsAndConsentFailClosed() {
        val errors = BugReportPolicy.validate(BugReportDraft())
        assertEquals(setOf(BugReportField.TITLE, BugReportField.CATEGORY, BugReportField.DESCRIPTION,
            BugReportField.STEPS, BugReportField.PRIVACY), errors.keys)
        assertTrue(BugReportPolicy.validate(validBugReport().copy(title = "        ")).containsKey(BugReportField.TITLE))
    }

    @Test fun everyTextLimitMatchesPublishedFormAtBothEdges() {
        data class Field(val id: BugReportField, val min: Int, val max: Int, val set: (BugReportDraft, String) -> BugReportDraft)
        val fields = listOf(
            Field(BugReportField.TITLE, 8, 120) { d, v -> d.copy(title=v) },
            Field(BugReportField.DESCRIPTION, 30, 4000) { d, v -> d.copy(description=v) },
            Field(BugReportField.STEPS, 10, 2500) { d, v -> d.copy(steps=v) },
            Field(BugReportField.EXPECTED, 0, 1200) { d, v -> d.copy(expected=v) },
            Field(BugReportField.ACTUAL, 0, 1200) { d, v -> d.copy(actual=v) },
            Field(BugReportField.VERSION, 0, 50) { d, v -> d.copy(version=v) },
            Field(BugReportField.ANDROID, 0, 60) { d, v -> d.copy(android=v) },
            Field(BugReportField.DEVICE, 0, 100) { d, v -> d.copy(device=v) },
        )
        for (field in fields) {
            for (size in listOf(field.min, field.max)) assertFalse("${field.id}:$size",
                BugReportPolicy.validate(field.set(validBugReport(), "x".repeat(size))).containsKey(field.id))
            assertTrue(BugReportPolicy.validate(field.set(validBugReport(), "x".repeat(field.max + 1))).containsKey(field.id))
            if (field.min > 0) assertTrue(BugReportPolicy.validate(field.set(validBugReport(), "x".repeat(field.min - 1))).containsKey(field.id))
        }
    }

    @Test fun emailIsOptionalAndBoundedWithoutHeaderInjection() {
        for (email in listOf("", "person@example.com", "person+bug@example.co.id"))
            assertFalse(BugReportPolicy.validate(validBugReport().copy(email=email)).containsKey(BugReportField.EMAIL))
        for (email in listOf("not an email", "x@", "person@example.com\r\nBcc:other@example.com", "@example.com", "x".repeat(250) + "@a.com"))
            assertTrue(email, BugReportPolicy.validate(validBugReport().copy(email=email)).containsKey(BugReportField.EMAIL))
    }

    @Test fun controlCharactersAreRejectedAndMultilineDetailsRemainIntact() {
        assertTrue(BugReportPolicy.validate(validBugReport().copy(description="x".repeat(31)+"\u0000")).containsKey(BugReportField.DESCRIPTION))
        val draft = validBugReport().copy(description="First line in a detailed report.\nSecond line\twith indentation.")
        assertTrue(BugReportPolicy.validate(draft).isEmpty())
        assertEquals(draft.description, BugReportPolicy.formFields(draft)["description"])
    }

    @Test fun exactWireFieldsNeverAddScreenshotTelemetryOrHoneypotData() {
        val fields = BugReportPolicy.formFields(validBugReport())
        assertEquals(setOf("title","category","priority","description","steps","expected","actual","version","android","device","email","website","privacy_ack"), fields.keys)
        assertEquals("", fields["website"]); assertEquals("yes", fields["privacy_ack"])
        assertEquals("editor", fields["category"]); assertEquals("normal", fields["priority"])
        assertFalse(fields.containsKey("screenshot"))
    }

    @Test fun categoriesAndPrioritiesAreTheWebsiteAllowlist() {
        assertEquals(setOf("crash","editor","agent","terminal","build","extensions","other"), BugReportCategory.entries.map { it.value }.toSet())
        assertEquals(setOf("normal","high","blocking"), BugReportPriority.entries.map { it.value }.toSet())
    }

    @Test fun emojiPairsRespectUtf16LimitsAndLoneSurrogatesNeverTransmit() {
        assertTrue(BugReportPolicy.validate(validBugReport().copy(title="😀".repeat(60))).isEmpty())
        assertTrue(BugReportPolicy.validate(validBugReport().copy(title="😀".repeat(61))).containsKey(BugReportField.TITLE))
        for (bad in listOf("\uD83D", "\uDE00", "\uD83Dx", "\uDE00\uD83D")) {
            assertTrue(BugReportPolicy.validate(validBugReport().copy(title="Example $bad")).containsKey(BugReportField.TITLE))
            assertTrue(BugReportPolicy.validate(validBugReport().copy(expected=bad)).containsKey(BugReportField.EXPECTED))
        }
    }

    @Test(expected = IllegalArgumentException::class) fun cannotEncodeUnvalidatedDraft() {
        BugReportPolicy.formFields(BugReportDraft())
    }
}
