package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class TextEditUnicodeWorkflowTest {
    @Test fun malformedLspRangeCannotSplitEmoji() {
        assertThrows(IllegalArgumentException::class.java) {
            TextEditApplier.apply("a😀b", listOf(TextRangeEdit(1, 3, 1, 4, "x")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextEditApplier.apply("a😀b", listOf(TextRangeEdit(1, 2, 1, 3, "x")))
        }
    }
    @Test fun validUtf16RenameAndCrlfFormattingPreserveBytes() {
        assertEquals("aokb", TextEditApplier.apply("a😀b", listOf(TextRangeEdit(1, 2, 1, 4, "ok"))))
        assertEquals("ab!\r\ncd", TextEditApplier.apply("ab\r\ncd", listOf(TextRangeEdit(1, 3, 1, 3, "!"))))
    }
    @Test fun lspColumnCannotPointInsideCrlfTerminator() {
        assertThrows(IllegalArgumentException::class.java) {
            TextEditApplier.apply("ab\r\ncd", listOf(TextRangeEdit(1, 4, 1, 4, "!")))
        }
    }
    @Test fun malformedReplacementCannotIntroduceBrokenUtf16() {
        assertThrows(IllegalArgumentException::class.java) {
            TextEditApplier.apply("safe", listOf(TextRangeEdit(1, 1, 1, 1, "\uD83D")))
        }
    }
}
