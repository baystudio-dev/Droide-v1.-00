package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspClojureFormattingLineEndTest {
    private fun verify(text:String,line:Int,column:Int) {
        val original=buildJsonArray {
            add(buildJsonObject {
                put("range",buildJsonObject {
                    put("start",buildJsonObject {put("line",0);put("character",0)})
                    put("end",buildJsonObject {put("line",999999);put("character",999999)})
                })
                put("newText","(formatted)\n")
            })
        }
        val normalized=LspClojureProfile.normalizeFormatting(original,text)
        val parsed=requireNotNull(LspWorkspaceTextEditParser.parse(normalized.single()))
        assertEquals(line+1,parsed.endLine)
        assertEquals(column+1,parsed.endColumn)
        assertEquals("(formatted)\n",TextEditApplier.apply(text,listOf(parsed)))
        assertEquals(999999,original.single().jsonObject.getValue("range").jsonObject.getValue("end").jsonObject.getValue("line").jsonPrimitive.int)
    }
    @Test fun standaloneCrSeparatesLines()=verify("(a)\r(b)",1,3)
    @Test fun trailingCrCreatesEmptyFinalLine()=verify("(a)\r",1,0)
    @Test fun consecutiveCrAreSeparateLineBreaks()=verify("a\r\rb",2,1)
    @Test fun lfThenCrAreSeparateLineBreaks()=verify("a\n\rb",2,1)
    @Test fun mixedCrCrlfAndLfUseCapturedUtf16Eof()=verify("a\rb\r\nc\n😀",3,2)
    @Test fun crBeforeAstralTextUsesUtf16Columns()=verify("a\r😀x",1,3)
    @Test fun crlfIsCountedOnceAndFinalEmptyLinePreserved() {
        verify("a\r\nb",1,1);verify("a\r\n",1,0)
    }
    @Test fun emptyAndNoNewlineControls() {
        verify("",0,0);verify("😀x",0,3);verify("a\nb",1,1)
    }
}
