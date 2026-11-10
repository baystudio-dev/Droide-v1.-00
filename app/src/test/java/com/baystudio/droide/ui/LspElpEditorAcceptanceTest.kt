package com.baystudio.droide.ui

import com.baystudio.droide.core.LspCallHierarchyItem
import com.baystudio.droide.core.LspElpCompletionContext
import com.baystudio.droide.core.LspHeldResultGuard
import org.junit.Assert.*
import org.junit.Test

class LspElpEditorAcceptanceTest {
    @Test fun triggerUsesCapturedLogicalCaretOnly() {
        assertEquals(":", elpCapturedTrigger("m:\r\nf()", 1, 3))
        assertNull(elpCapturedTrigger("m:\r\nf()", 1, 4))
        assertNull(elpCapturedTrigger("m:", 2, 1))
        assertNull(elpCapturedTrigger("m@", 1, 3))
        assertNull(elpCapturedTrigger("m:", 1, 1))
    }
    @Test fun exactNativeTriggerAlphabetIsFinite() {
        for (c in ":#?.-\\") assertEquals(c.toString(), elpCapturedTrigger("x$c", 1, 3))
        for (c in "@()[]{} ;$") assertNull(elpCapturedTrigger("x$c", 1, 3))
    }
    @Test fun nativeNumericDefaultsPreserveLiteralVariableNames() {
        assertTrue(LspElpCompletionContext.validSnippet("f(\${1:RELATIVE_FILEPATH}, \$2)"))
        assertTrue(LspElpCompletionContext.validSnippet("\${1:Unicode λ}\$0"))
    }
    @Test fun unsupportedSnippetGrammarNeverGetsAnImplicitFallback() {
        for (value in listOf("\$ENV", "\${ENV}", "\${1/a/b/}", "\${1|a,b|}", "\${1:\$2}", "\${1:missing")) {
            assertFalse(value, LspElpCompletionContext.validSnippet(value))
        }
    }
    @Test fun heldGuardFailsClosedWithoutThrowingIntoUi() {
        assertFalse(LspHeldResultGuard { error("expired") }.accepts())
        assertFalse(LspHeldResultGuard { false }.accepts())
        assertTrue(LspHeldResultGuard { true }.accepts())
    }
}
