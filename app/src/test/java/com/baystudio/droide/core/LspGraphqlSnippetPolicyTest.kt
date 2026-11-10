package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class LspGraphqlSnippetPolicyTest {
    @Test fun nativeLfTemplateAndLiteralNameRemainExact() {
        val source = "RELATIVE_FILEPATH: {\n   ${'$'}1\n}"
        val parts = LspGraphqlSnippetPolicy.parts(source)
        assertEquals(listOf(LspGraphqlSnippetPolicy.Part(literal = "RELATIVE_FILEPATH: {\n   "),
            LspGraphqlSnippetPolicy.Part(tabStop = 1), LspGraphqlSnippetPolicy.Part(literal = "\n}")), parts)
        LspGraphqlSnippetPolicy.requireSameParts(source, parts)
    }
    @Test fun variablesTransformsShellEscapesAndCrAreRejected() {
        for (source in listOf("${'$'}{ENV}", "${'$'}name", "${'$'}{1:x}", "${'$'}{1/a/b/}", "`shell`", "\\${'$'}1", "x\r\ny", "${'$'}1234567890", "\uD800", "\uDC00", "\u0000"))
            assertTrue(source, runCatching { LspGraphqlSnippetPolicy.parts(source) }.isFailure)
    }
    @Test fun boundsAreExactAndNeverTruncate() {
        assertEquals(8000, LspGraphqlSnippetPolicy.parts("x".repeat(8000)).single().literal?.length)
        assertTrue(runCatching { LspGraphqlSnippetPolicy.parts("x".repeat(8001)) }.isFailure)
        assertEquals(96, LspGraphqlSnippetPolicy.parts("${'$'}1".repeat(96)).size)
        assertTrue(runCatching { LspGraphqlSnippetPolicy.parts("${'$'}1".repeat(97)) }.isFailure)
    }
    @Test fun parsedLiteralAndStopPositionsMustRemainExact() {
        val s = "a${'$'}1b"
        LspGraphqlSnippetPolicy.requireSameParts(s, listOf(LspGraphqlSnippetPolicy.Part(literal="a"),LspGraphqlSnippetPolicy.Part(tabStop=1),LspGraphqlSnippetPolicy.Part(literal="b")))
        assertTrue(runCatching { LspGraphqlSnippetPolicy.requireSameParts(s, listOf(LspGraphqlSnippetPolicy.Part(literal="ab"),LspGraphqlSnippetPolicy.Part(tabStop=1))) }.isFailure)
        assertTrue(runCatching { LspGraphqlSnippetPolicy.requireSameParts(s, listOf(LspGraphqlSnippetPolicy.Part(literal="a"),LspGraphqlSnippetPolicy.Part(tabStop=2),LspGraphqlSnippetPolicy.Part(literal="b"))) }.isFailure)
    }
}
