package com.baystudio.droide.core
import org.junit.Assert.*
import org.junit.Test
class TextEditInsertionOrderTest {
    private fun insert(column:Int,text:String)=TextRangeEdit(1,column,1,column,text)
    @Test fun equalPositionInsertionsRetainArrayOrder() {
        assertEquals("abx",TextEditApplier.apply("x",listOf(insert(1,"a"),insert(1,"b"))))
    }
    @Test fun threeInsertionsRetainArrayOrderAtEnd() {
        assertEquals("xabc",TextEditApplier.apply("x",listOf(insert(2,"a"),insert(2,"b"),insert(2,"c"))))
    }
    @Test fun insertsBeforeReplacementRetainArrayOrder() {
        assertEquals("abR",TextEditApplier.apply("x",listOf(insert(1,"a"),insert(1,"b"),TextRangeEdit(1,1,1,2,"R"))))
    }
    @Test fun independentPositionsPreserved() {
        assertEquals("aXbY",TextEditApplier.apply("XY",listOf(insert(1,"a"),insert(2,"b"))))
    }
    @Test fun overlappingNonemptyEditsStillRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TextEditApplier.apply("abc",listOf(TextRangeEdit(1,1,1,3,"x"),TextRangeEdit(1,2,1,4,"y")))
        }
    }
}
