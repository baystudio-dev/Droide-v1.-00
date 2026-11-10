package com.baystudio.droide.ui

import org.junit.Assert.*
import org.junit.Test

class AgentMarkdownMalformedInputTest {
    private fun visible(source: String) = AgentMarkdownParser.parseInline(source).joinToString("") { it.text }

    @Test fun unmatchedDelimitersKeepEveryCharacter() {
        for (delimiter in listOf('`', '[', '<')) {
            val source = "Literal 😄 " + delimiter.toString().repeat(64) + " 🚀TAIL"
            assertEquals(source, visible(source))
        }
    }

    @Test fun failedLookaheadsShareOneExactLinearBudget() {
        val source = "[".repeat(64)
        val scan = AgentMarkdownInlineScan(source)
        repeat(20) { assertEquals(-1, scan.find(']', it)) }
        assertTrue(scan.exhausted)
        assertEquals(source.length * 4L + 32L, scan.limit)
        assertEquals(scan.limit, scan.inspected)
        repeat(20) { assertEquals(-1, scan.linkTargetEnd(it)) }
        assertEquals(scan.limit, scan.inspected)
    }

    @Test fun failedBalancedTargetsUseTheSameBudgetAndRemainLiteral() {
        val source = "[x](".repeat(32) + "🚀TAIL"
        assertEquals(source, visible(source))
        val scan = AgentMarkdownInlineScan(source)
        repeat(20) { assertEquals(-1, scan.linkTargetEnd(it)) }
        assertTrue(scan.exhausted)
        assertEquals(scan.limit, scan.inspected)
    }

    @Test fun normalFormattingExactLinksAndFencedCodeRemainSupported() {
        val source = "**bold** *em* ~~strike~~ `` a`b 😄 `` [Docs](https://example.test/wiki/F_(x)?state=A%2BB)"
        val tokens = AgentMarkdownParser.parseInline(source)
        assertEquals("bold em strike a`b 😄 Docs", tokens.joinToString("") { it.text })
        assertEquals("a`b 😄", tokens.single { it.kind == AgentMarkdownInlineKind.CODE }.text)
        assertEquals("https://example.test/wiki/F_(x)?state=A%2BB", tokens.single { it.kind == AgentMarkdownInlineKind.LINK }.url)
        val code = "prefix " + "`".repeat(64) + "\n😄TAIL"
        assertEquals(code, (AgentMarkdownParser.parse("~~~text\n$code\n~~~").single() as AgentMarkdownBlock.CodeBlock).code)
    }
}
