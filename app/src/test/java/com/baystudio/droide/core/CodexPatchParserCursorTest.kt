package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class CodexPatchParserCursorTest {
    private fun compare(text:String) {
        val old=runCatching {FrozenCodexPatchParserOracle.parse(text)}
        val now=runCatching {CodexPatchParser.parse(text)}
        if(old.isSuccess) assertEquals("Different parsed operations for ${text.take(300)}",old.getOrThrow(),now.getOrThrow())
        else {
            assertTrue("Unexpected acceptance for ${text.take(300)}",now.isFailure)
            assertEquals(old.exceptionOrNull()!!.javaClass,now.exceptionOrNull()!!.javaClass)
            assertEquals(old.exceptionOrNull()!!.message,now.exceptionOrNull()!!.message)
        }
    }
    @Test fun newlineEdgesMarkersEmptyAddAndMovePreserveFrozenSemantics() {
        val bodies=listOf(
            "*** Add File: a", "*** Add File: a\n+", "*** Add File: a\n+one\n+two",
            "*** Delete File: a", "*** Update File: a\n*** Move to: b",
            "*** Update File: a\n@@\n-old\n+new\n*** End of File\n\n",
            "*** Update File: a\n context\n-old\n+new\n@@ heading\n-x\n+y",
            "*** Add File: ","*** Update File: a\n@@\n same", "*** Bogus: a",
            "*** Add File: a\nnotplus", "*** Update File: a\n@@\n+new\n*** End of File\n\n*** Delete File: b"
        )
        for(body in bodies) for(nl in listOf("\n","\r","\r\n")) for(prefix in listOf("","\n\r\n")) {
            compare(prefix+("  *** Begin Patch \n"+body+"\n\t*** End Patch  ").replace("\n",nl)+prefix)
        }
        listOf("","\r\n","*** Begin Patch","*** End Patch","*** Begin Patch\r*** End Patch","*** Begin Patch\r\r\n*** End Patch").forEach(::compare)
    }
    @Test fun deterministicDifferentialMixedNewlinesAndInvalidTokens() {
        val random=java.util.Random(7103)
        val tokens=listOf("", " ","*** Add File: a","*** Delete File: b","*** Update File: c","*** Move to: d",
            "*** End of File","@@","@@ heading"," old","-old","+new","+","-","bad","*** Unknown","🌏","\u2028")
        repeat(2500) {
            val patch=buildString {
                if(random.nextBoolean())append("\r\n")
                append("*** Begin Patch")
                repeat(random.nextInt(25)) {
                    append(listOf("\r","\n","\r\n")[random.nextInt(3)])
                    append(tokens[random.nextInt(tokens.size)])
                }
                append("\n*** End Patch")
                if(random.nextBoolean())append("\r\n")
            }
            compare(patch)
        }
    }
    @Test fun operationAndHunkLimitsRemainExact() {
        for(n in listOf(100,101))compare("*** Begin Patch\n"+(0 until n).joinToString("\n"){"*** Delete File: a$it"}+"\n*** End Patch")
        for(n in listOf(1000,1001))compare("*** Begin Patch\n*** Update File: a\n"+"@@\n-x\n+y\n".repeat(n)+"*** End Patch")
    }
    @Test fun contextLinesShareImmutableSubstringWithoutChangingValues() {
        val result=CodexPatchParser.parse("*** Begin Patch\n*** Update File: a\n context\n-old\n+new\n*** End Patch").single() as PatchTool.Op.Update
        val hunk=result.hunks.single()
        assertEquals("context",hunk.before.first());assertSame(hunk.before.first(),hunk.after.first())
        compare("*** Begin Patch\n*** Update File: a\n context\n-old\n+new\n*** End Patch")
    }
    @Test fun denseNearTwoMillionAddRetainsExactTrailingNewlines() {
        val lines=999900
        val patch="*** Begin Patch\n*** Add File: a\n"+"+\n".repeat(lines)+"*** End Patch"
        assertTrue(patch.length<2_000_000)
        val add=CodexPatchParser.parse(patch).single() as PatchTool.Op.Add
        assertEquals(lines,add.content.length);assertTrue(add.content.all{it=='\n'})
    }
}
