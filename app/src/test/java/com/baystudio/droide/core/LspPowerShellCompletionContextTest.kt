package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class LspPowerShellCompletionContextTest {
    @Test fun exactSixNativeCharactersDoNotDependOnTheGenericPrefixGate() {
        for (trigger in listOf(".", "-", ":", "\\", "$", " ")) {
            val text = "Get-Item$trigger"
            val context = LspPowerShellCompletionContext.capture(text, 1, text.length + 1)!!
            assertEquals(trigger, context.triggerCharacter)
            assertEquals(LspCompletionCaret(1, text.length + 1), context.requestCaret)
            assertEquals(ProfessionalCompletion.prefixFromLine("powershell", text, text.length), context.prefix)
        }
        assertNull(ProfessionalCompletion.triggerCharacter("powershell", "$", 1))
        assertNull(ProfessionalCompletion.triggerCharacter("powershell", "-", 1))
        assertFalse(ProfessionalCompletion.shouldQueryLsp("powershell", "", "\\"))
        assertEquals("", LspPowerShellCompletionContext.capture("a\\", 1, 3)!!.prefix)
        assertEquals("", LspPowerShellCompletionContext.capture("a ", 1, 3)!!.prefix)
    }

    @Test fun blankAndNonNativeWhitespaceNeverInventSpaceTriggers() {
        for (text in listOf("", "\t", "\u00a0")) {
            val context = LspPowerShellCompletionContext.capture(text, 1, text.length + 1)!!
            assertNull(context.triggerCharacter)
        }
        assertNull(LspPowerShellCompletionContext.capture("x\r\n", 2, 1)!!.triggerCharacter)
        assertNull(LspPowerShellCompletionContext.capture("x\n", 2, 1)!!.triggerCharacter)
    }

    @Test fun exactUtf16CaretAndCrLfAreValidated() {
        assertEquals(" ", LspPowerShellCompletionContext.capture("😀\r\n ", 2, 2)!!.triggerCharacter)
        assertEquals(".", LspPowerShellCompletionContext.capture("😀.", 1, 4)!!.triggerCharacter)
        assertNull(LspPowerShellCompletionContext.capture("😀.", 1, 2))
        for ((line, column) in listOf(0 to 1, 1 to 0, 2 to 1, 1 to 5, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE))
            assertNull(LspPowerShellCompletionContext.capture("😀.", line, column))
        assertNull(LspPowerShellCompletionContext.capture("x\r\n", 1, 3))
        assertNotNull(LspPowerShellCompletionContext.capture("x\r\n", 1, 2))
    }

    @Test fun finiteNativeTextAdmissionRejectsMalformedInputWithoutNormalization() {
        for (text in listOf("a\u0000b", "a\rb", "\ud800", "\udc00", "\ud800x", "\udc00\ud800")) {
            assertFalse(LspPowerShellCompletionContext.acceptsText(text))
            assertNull(LspPowerShellCompletionContext.capture(text, 1, 1))
        }
        assertTrue(LspPowerShellCompletionContext.acceptsText("@'\r\n😀\n'@\r\n"))
        assertTrue(LspPowerShellCompletionContext.acceptsText("a".repeat(2_000_000)))
        assertFalse(LspPowerShellCompletionContext.acceptsText("a".repeat(2_000_001)))
        assertEquals(10_000L, LspPowerShellCompletionContext.UI_WATCHDOG_MS)
        assertEquals(2_500L, ProfessionalCompletion.LSP_TIMEOUT_MS)
        assertEquals(110L, ProfessionalCompletion.LSP_DEBOUNCE_MS)
    }
}
