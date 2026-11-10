package com.baystudio.droide.core
import org.junit.Assert.*
import org.junit.Test
class LspTomlPositionAdmissionTest {
    @Test fun fourMethodsUseExactAsciiNextBoundary() {
        for (method in LspTomlPositionAdmission.cursorMethods) {
            assertFalse(LspTomlPositionAdmission.admits(method, "é😀x", 0, 0))
            assertFalse(LspTomlPositionAdmission.admits(method, "é😀x", 0, 1))
            assertFalse(LspTomlPositionAdmission.admits(method, "é😀x", 0, 2))
            assertTrue(LspTomlPositionAdmission.admits(method, "é😀x", 0, 3))
            assertTrue(LspTomlPositionAdmission.admits(method, "é😀x", 0, 4))
        }
        assertTrue(LspTomlPositionAdmission.outgoing("é😀x", 0, 1))
        assertFalse(LspTomlPositionAdmission.outgoing("é😀x", 0, 2))
        assertTrue(LspTomlPositionAdmission.admits("textDocument/formatting", "é", 0, 0))
    }
    @Test fun crlfAndMalformedTextAreNotRewritten() {
        assertTrue(LspTomlPositionAdmission.admits("textDocument/hover", "é\r\nx\n", 0, 1))
        assertFalse(LspTomlPositionAdmission.outgoing("é\r\nx\n", 0, 2))
        assertTrue(LspTomlPositionAdmission.outgoing("é\r\nx\n", 2, 0))
        assertFalse(LspTomlPositionAdmission.outgoing("a\r", 0, 0))
        assertFalse(LspTomlPositionAdmission.validText("\uD800"))
        assertFalse(LspTomlPositionAdmission.validText("\uDC00"))
        assertFalse(LspTomlPositionAdmission.validText("\u0000"))
        assertFalse(LspTomlPositionAdmission.validText("a".repeat(LspTomlPositionAdmission.MAX_TEXT_UNITS + 1)))
        assertTrue(LspTomlPositionAdmission.outgoing("", 0, 0))
    }
}
