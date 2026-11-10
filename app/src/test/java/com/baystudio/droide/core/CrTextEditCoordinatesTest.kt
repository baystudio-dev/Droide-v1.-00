package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class CrTextEditCoordinatesTest {
    @Test fun standaloneCrHasSecondLine() { assertEquals("a\rc", TextEditApplier.apply("a\rb",listOf(TextRangeEdit(2,1,2,2,"c")))) }
    @Test fun crlfAndMixedLineBreaksPreserved() { assertEquals("a\rB\r\nC\nd",TextEditApplier.apply("a\rb\r\nc\nd",listOf(TextRangeEdit(2,1,2,2,"B"),TextRangeEdit(3,1,3,2,"C")))) }
    @Test fun trailingCrHasEmptyFinalLine() { assertEquals("a\rb",TextEditApplier.apply("a\r",listOf(TextRangeEdit(2,1,2,1,"b")))) }
    @Test fun crossLineEditConsumesExactlyRequestedBreak() { assertEquals("aXd",TextEditApplier.apply("ab\rcd",listOf(TextRangeEdit(1,2,2,2,"X")))) }
    @Test fun coordinateCannotCrossCrFromPriorLine() {
        assertThrows(IllegalArgumentException::class.java) { TextEditApplier.apply("a\rb",listOf(TextRangeEdit(1,3,1,4,"X"))) }
    }
    @Test fun utf16BoundaryAfterCrRemainsStrict() {
        assertThrows(IllegalArgumentException::class.java) { TextEditApplier.apply("a\r😀",listOf(TextRangeEdit(2,2,2,2,"X"))) }
        assertEquals("a\rx",TextEditApplier.apply("a\r😀",listOf(TextRangeEdit(2,1,2,3,"x"))))
    }
    @Test fun consecutiveCrPreservesEmptyLine() {
        assertEquals("a\rX\rb",TextEditApplier.apply("a\r\rb",listOf(TextRangeEdit(2,1,2,1,"X"))))
    }
    @Test fun lfThenCrIsTwoBreaksNotOne() {
        assertEquals("a\n\rc",TextEditApplier.apply("a\n\rb",listOf(TextRangeEdit(3,1,3,2,"c"))))
    }
    private fun completion(text: String, line: Int, column: Int, end: Int) = LspCompletionProtocol.parse(
        buildJsonArray { add(buildJsonObject {
            put("label","new");put("textEdit",buildJsonObject {
                put("newText","c");put("range",buildJsonObject {
                    put("start",buildJsonObject {put("line",line-1);put("character",column-1)})
                    put("end",buildJsonObject {put("line",line-1);put("character",end-1)})
                })
            })
        }) },line,column,text)
    @Test fun completionCrCoordinatesAgreeWithApply() {
        val item=completion("a\rb",2,1,2).single()
        val edit=TextRangeEdit(item.editStartLine!!,item.editStartColumn!!,item.editEndLine!!,item.editEndColumn!!,item.insertText)
        assertEquals("a\rc",TextEditApplier.apply("a\rb",listOf(edit)))
    }
    @Test fun completionRejectsColumnPastCrLine() { assertTrue(completion("a\rb",1,3,4).isEmpty()) }
    @Test fun completionTrailingCrAllowsEmptyLastLine() { assertEquals("c",completion("a\r",2,1,1).single().insertText) }
    @Test fun existingLfAndCrlfAreUnchanged() {
        for (text in listOf("a\nb","a\r\nb")) assertEquals("c",completion(text,2,1,2).single().insertText)
    }
}
