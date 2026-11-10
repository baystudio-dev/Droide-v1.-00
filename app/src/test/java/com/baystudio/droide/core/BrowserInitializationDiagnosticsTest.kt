package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class BrowserInitializationDiagnosticsTest {
    @Test fun diagnosticFailuresCannotBlockConstructionOrReplaceItsFailure() {
        for (diagnosticFailure in listOf(IllegalStateException("log unavailable"), UnsatisfiedLinkError("log backend"))) {
            val diagnostic = BrowserInitializationDiagnostics(clock = { throw diagnosticFailure }, emit = { throw diagnosticFailure })
            assertEquals("created", diagnostic.phase(BrowserInitializationDiagnostics.Phase.CREATE) { "created" })
            val realFailure = IllegalStateException("constructor unavailable")
            try {
                diagnostic.phase(BrowserInitializationDiagnostics.Phase.CREATE) { throw realFailure }
                fail("The actual constructor failure must reach the existing UI handler")
            } catch (actual: IllegalStateException) { assertSame(realFailure, actual) }
        }
    }

    @Test fun failureRecordsOnlyClassAndDurationNeverItsSensitiveMessageOrStack() {
        for (failure in listOf(IllegalStateException("https://secret.example/token=credential"), UnsatisfiedLinkError("/private/project/file"))) {
            val lines = mutableListOf<String>()
            var now = 10L
            val diagnostic = BrowserInitializationDiagnostics(clock = { now }, emit = { lines += it })
            try {
                diagnostic.phase(BrowserInitializationDiagnostics.Phase.CONFIGURE) { now = 42; throw failure }
                fail("Expected original failure")
            } catch (actual: Throwable) { assertSame(failure, actual) }
            assertEquals(2, lines.size)
            assertTrue(lines.last().contains("event=failed elapsed_ms=32 type=${failure.javaClass.simpleName}"))
            assertFalse(lines.joinToString().contains(failure.message!!))
            assertFalse(lines.any { it.contains("event=complete") })
        }
    }

    @Test fun providerMetadataIsBoundedAndCannotInjectLogLines() {
        val lines = mutableListOf<String>()
        val diagnostic = BrowserInitializationDiagnostics({ 0 }, { lines += it })
        diagnostic.providerAfterCreation {
            BrowserInitializationDiagnostics.Provider("provider\nforged\tline", "x".repeat(20_000))
        }
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("package=provider_forged_line"))
        assertTrue(lines.single().length <= BrowserInitializationDiagnostics.MAX_LINE_CHARACTERS)
        assertFalse(lines.single().contains('\n'))
        assertFalse(lines.single().contains('\t'))
    }

    @Test fun unavailableProviderMetadataDoesNotBlockTheRemainingBrowserPhases() {
        val lines = mutableListOf<String>()
        val diagnostic = BrowserInitializationDiagnostics({ 0 }, { lines += it })
        diagnostic.phase(BrowserInitializationDiagnostics.Phase.CREATE) { Unit }
        diagnostic.providerAfterCreation { throw UnsatisfiedLinkError("metadata unavailable") }
        diagnostic.phase(BrowserInitializationDiagnostics.Phase.CONFIGURE) { Unit }
        assertTrue(lines[2].contains("event=provider unavailable=true"))
        assertTrue(lines.last().contains("stage=configure event=complete"))
    }
}
