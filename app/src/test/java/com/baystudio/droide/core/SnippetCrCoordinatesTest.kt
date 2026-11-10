package com.baystudio.droide.core
import org.junit.Assert.*
import org.junit.Test
class SnippetCrCoordinatesTest {
    private fun check(text:String,line:Int,start:Int,end:Int,additional:List<TextRangeEdit> = emptyList()) {
        val plan=ProfessionalSnippets.planSnippetEdits(text,TextRangeEdit(line,1,line,if(start==end) 1 else 2,"snippet"),additional)
        assertEquals(start,plan.adjustedStart);assertEquals(end,plan.adjustedEnd)
    }
    @Test fun standaloneCrLine()=check("a"+13.toChar()+"b",2,2,3)
    @Test fun crlfLine()=check("a"+13.toChar()+10.toChar()+"b",2,3,4)
    @Test fun trailingCrEmptyLine()=check("a"+13.toChar(),2,2,2)
    @Test fun mixedLineSeparators()=check("a"+13.toChar()+"b"+10.toChar()+"c"+13.toChar()+10.toChar()+"d",4,7,8)
    @Test fun additionalEditShiftsCrPrimary()=check("a"+13.toChar()+"b",2,4,5,listOf(TextRangeEdit(1,1,1,1,"XX")))
}
