package com.baystudio.droide.core

import java.io.StringReader
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class PropertiesEditorSupportTest {
    private fun load(source: String) = Properties().apply { load(StringReader(source)) }
    private fun spans(source: String) = DroideSyntaxSemanticEngine.highlight(source, "properties").spans
    private fun covered(source: String, line: Int, start: Int, end: Int, kind: DroideHighlightKind) {
        val result = spans(source)
        for (column in start until end) assertTrue("$line:$column $result", result.any {
            it.line == line && column in it.start until it.end && it.kind == kind
        })
    }

    @Test fun commentsFollowJavaRulesAndInlineMarkersRemainValues() {
        val source = "  ! note\\\n# another\n;key=value\nurl=https://host/#part;tail!"
        val parsed = load(source)
        assertEquals("value", parsed.getProperty(";key"))
        assertEquals("https://host/#part;tail!", parsed.getProperty("url"))
        assertEquals(setOf(0, 1), spans(source).filter { it.kind == DroideHighlightKind.COMMENT }.map { it.line }.toSet())
        covered(source, 2, 0, 4, DroideHighlightKind.PROPERTY)
        covered(source, 3, 4, source.substringAfterLast('\n').length, DroideHighlightKind.STRING)
    }

    @Test fun escapedDelimitersAndWhitespaceBelongToKeys() {
        val source = "a\\:b\\=c\\ d : value\nempty"
        assertEquals("value", load(source).getProperty("a:b=c d"))
        assertEquals("", load(source).getProperty("empty"))
        covered(source, 0, 0, source.indexOf(" :"), DroideHighlightKind.PROPERTY)
        covered(source, 1, 0, 5, DroideHighlightKind.PROPERTY)
    }

    @Test fun oddBackslashesContinueValuesAndEvenBackslashesEndThem() {
        val source = "key=first\\\n  #literal\\\n  !literal\nnext=ends\\\\\n!comment"
        val parsed = load(source)
        assertEquals("first#literal!literal", parsed.getProperty("key"))
        assertEquals("ends\\", parsed.getProperty("next"))
        covered(source, 1, 2, 10, DroideHighlightKind.STRING)
        covered(source, 2, 2, 10, DroideHighlightKind.STRING)
        assertEquals(setOf(4), spans(source).filter { it.kind == DroideHighlightKind.COMMENT }.map { it.line }.toSet())
    }

    @Test fun keysCanContinueBeforeTheSeparator() {
        val source = "hello\\\n  world=value"
        assertEquals("value", load(source).getProperty("helloworld"))
        covered(source, 1, 2, 7, DroideHighlightKind.PROPERTY)
        covered(source, 1, 8, 13, DroideHighlightKind.STRING)
    }

    @Test fun separatorOnFollowingLineRemainsOptional() {
        val source = "key \\\n  : value"
        assertEquals("value", load(source).getProperty("key"))
        covered(source, 1, 2, 3, DroideHighlightKind.OPERATOR)
        covered(source, 1, 4, 9, DroideHighlightKind.STRING)

        val explicit = "key=\\\n  : value"
        assertEquals(": value", load(explicit).getProperty("key"))
        covered(explicit, 1, 2, 9, DroideHighlightKind.STRING)
    }

    @Test fun utf16CrLfAndCrOffsetsArePreservedWithoutDecodingSourceEscapes() {
        val source = "😀.clé=文\\u0020value\r\nother:two\rthird three"
        val parsed = load(source)
        assertEquals("文 value", parsed.getProperty("😀.clé"))
        assertEquals("two", parsed.getProperty("other"))
        assertEquals("three", parsed.getProperty("third"))
        covered(source, 0, 0, source.indexOf('='), DroideHighlightKind.PROPERTY)
        covered(source, 1, 10, 15, DroideHighlightKind.PROPERTY)
        for (span in spans(source)) {
            val line = source.split('\n')[span.line]
            assertTrue(span.start >= 0 && span.end <= line.length && span.end > span.start)
        }
    }

    @Test fun incompleteEscapesAreEditableAndDocumentStateNeverLeaks() {
        for (source in listOf("key=\\u12", "key\\", "\\", "=", ":", "", " \t\u000c")) {
            assertTrue(spans(source).all { it.start >= 0 && it.end <= source.length && it.end > it.start })
        }
        assertTrue(spans("!fresh").single().kind == DroideHighlightKind.COMMENT)
        assertTrue(JavaPropertiesHighlighting.highlight("x=y\n".repeat(100), 3).size <= 3)
        assertTrue(JavaPropertiesHighlighting.highlight("x=y", 0).isEmpty())
        assertTrue(DroideSyntaxSemanticEngine.highlight("x".repeat(DroideSyntaxSemanticEngine.MAX_HIGHLIGHT_CHARS + 1), "properties").skippedForSize)
    }

    @Test fun snippetsAndRuntimeInventoryRemainLocalAndHonest() {
        assertEquals("properties", LanguageRegistry.forFile("i18n/messages.PROPERTIES")?.id)
        assertNull(LanguageRegistry.runPlanFor("messages.properties"))
        assertTrue(LanguageServerRegistry.forPath("messages.properties").isEmpty())
        val item = ProfessionalCompletion.localCandidates("properties", "key", 3, "key").first { it.isSnippet }
        assertEquals("${'$'}{1:key}=${'$'}{2:value}${'$'}0", item.insertText)
        assertFalse(ProfessionalSnippets.builtIns("properties", "section").any())
        assertEquals("ini", LanguageRegistry.forFile("other.ini")?.id)
    }
}
