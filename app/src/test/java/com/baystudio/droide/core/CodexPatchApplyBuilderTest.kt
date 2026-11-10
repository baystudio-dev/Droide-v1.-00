package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class CodexPatchApplyBuilderTest {
    private fun compare(text:String,hunks:List<CodexPatchHunk>) {
        val old=runCatching{FrozenCodexPatchParserOracle.apply(text,hunks,"test.txt")}
        val now=runCatching{CodexPatchParser.apply(text,hunks,"test.txt")}
        if(old.isSuccess)assertEquals(old.getOrThrow(),now.getOrThrow()) else {
            assertTrue(now.isFailure)
            assertEquals(old.exceptionOrNull()!!.javaClass,now.exceptionOrNull()!!.javaClass)
            assertEquals(old.exceptionOrNull()!!.message,now.exceptionOrNull()!!.message)
        }
    }
    @Test fun emptyAndTrailingLineSeparatorSemanticsMatchFrozen() {
        val originals=listOf("","\n","\n\n","\r","\r\n","\r\n\r\n","a","a\n","a\n\n","a\r\n","a\rb\n","🌏\r\n漢\n","\uFEFFa\n")
        val hunks=listOf(
            emptyList(),listOf(CodexPatchHunk(null,emptyList(),emptyList(),false)),
            listOf(CodexPatchHunk(null,emptyList(),listOf(""),false)),
            listOf(CodexPatchHunk(null,emptyList(),listOf("","","x",""),true)),
            listOf(CodexPatchHunk(null,listOf("a"),emptyList(),false)),
            listOf(CodexPatchHunk(null,listOf(""),listOf("","tail"),true)),
            listOf(CodexPatchHunk(null,listOf("a"),listOf("","after",""),false))
        )
        for(text in originals)for(change in hunks)compare(text,change)
    }
    @Test fun deterministicRandomHunksPreserveOutputsAndErrorClassMessage() {
        val r=java.util.Random(21991)
        val tokens=listOf("","a","b","x","🌏"," lone\rCR","a\r","embedded\nline")
        repeat(4000){
            val rows=List(r.nextInt(12)){tokens[r.nextInt(tokens.size)]}
            val text=rows.joinToString(if(r.nextBoolean())"\n" else "\r\n")+if(r.nextBoolean())"\n" else ""
            val hunks=List(r.nextInt(4)){
                CodexPatchHunk(if(r.nextInt(3)==0)tokens[r.nextInt(tokens.size)] else null,
                    List(r.nextInt(4)){tokens[r.nextInt(tokens.size)]},
                    List(r.nextInt(4)){tokens[r.nextInt(tokens.size)]},r.nextBoolean())
            }
            compare(text,hunks)
        }
    }
    @Test fun validUniqueContextAndHeadingPatchesPreserveExactResult() {
        for(n in 1..60)for(newline in listOf("\n","\r\n")){
            val rows=(0 until n).map{"unique-$it"}
            val target=n/2
            val h=CodexPatchHunk(if(target>0)rows[target-1] else null,listOf(rows[target]),listOf("","replacement",""),target==n-1)
            compare(rows.joinToString(newline),listOf(h));compare(rows.joinToString(newline)+newline,listOf(h))
        }
    }
    @Test fun headingAmbiguityEofAndOverlapStayExact() {
        val h=CodexPatchHunk(null,listOf("same"),listOf("new"),false)
        compare("same\nsame\n",listOf(h))
        compare("same\nsame\n",listOf(h.copy(atEnd=true)))
        compare("a\nb\nc\n",listOf(CodexPatchHunk(null,listOf("b"),listOf("B"),false),CodexPatchHunk(null,listOf("a"),listOf("A"),false)))
        compare("heading\na\nheading\na\n",listOf(CodexPatchHunk("heading",listOf("a"),listOf("A"),false)))
    }
    @Test fun comparisonBudgetBoundaryStillRejectsSameWork() {
        compare("x\n".repeat(3200)+"end\n",listOf(CodexPatchHunk(null,List(1200){"x"}+"never",listOf("new"),false)))
    }
}
