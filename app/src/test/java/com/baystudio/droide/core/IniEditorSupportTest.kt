package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class IniEditorSupportTest {
    @Test fun preservesExistingAssociationsWithoutLaunchingACompilerOrGenericServer() {
        for (path in listOf("app.ini", "APP.INI", "config.cfg", "server.conf")) {
            assertEquals("ini", LanguageRegistry.forFile(path)?.id)
            assertNull(LanguageRegistry.runPlanFor(path))
            assertTrue(LanguageServerRegistry.forPath(path).isEmpty())
        }
        assertTrue(LanguageServerRegistry.unavailableReason("app.ini")!!.contains("Dialect/schema"))
        assertEquals("toml", LanguageRegistry.forFile("pyproject.toml")?.id)
        assertEquals("properties", LanguageRegistry.forFile("app.properties")?.id)
    }

    @Test fun colorsSectionsKeysBothCommentMarkersAndQuotedUrls() {
        BuiltInEditorGrammars.highlight("[ready]\nx=1", "ini", 100)
        val source = "[server]\nport=8080\n; comment\n# other comment\nurl=\"https://host/;keep#fragment\"\n"
        val result = DroideSyntaxSemanticEngine.highlight(source, "ini")
        assertTrue(result.spans.any { it.line == 0 && it.kind == DroideHighlightKind.TYPE })
        assertTrue(result.spans.any { it.line == 1 && it.kind == DroideHighlightKind.KEYWORD })
        assertTrue(result.spans.any { it.line == 2 && it.kind == DroideHighlightKind.COMMENT })
        assertTrue(result.spans.any { it.line == 3 && it.kind == DroideHighlightKind.COMMENT })
        assertTrue(result.spans.any { it.line == 4 && it.kind == DroideHighlightKind.STRING })
        assertFalse(result.spans.any { it.line == 4 && it.kind == DroideHighlightKind.COMMENT })
    }

    @Test fun bmpTextAndCrLfKeepValidRangesWithoutCrossDocumentState() {
        val source = "[文é]\r\nx=\"文é\"\r\n; été"
        val result = DroideSyntaxSemanticEngine.highlight(source, "ini")
        assertTrue(result.spans.any { it.line == 1 && it.kind == DroideHighlightKind.STRING })
        for (span in result.spans) {
            assertTrue(span.start >= 0 && span.end <= source.split('\n')[span.line].length && span.start < span.end)
        }
        DroideSyntaxSemanticEngine.highlight("key=\"unterminated", "ini")
        assertTrue(DroideSyntaxSemanticEngine.highlight("; fresh document", "ini").spans.any { it.kind == DroideHighlightKind.COMMENT })
    }

    @Test fun snippetsAndDocumentWordsWorkLocallyWithoutInventingSchemaErrors() {
        val section = ProfessionalCompletion.localCandidates("ini", "sec", 3, "sec").first { it.isSnippet }
        assertEquals("section", section.label)
        assertEquals("[${'$'}{1:section}]\n${'$'}0", section.insertText)
        val document = ProfessionalCompletion.localCandidates("ini", "custom.key=value\ncustom", 23, "custom")
        assertTrue(document.any { it.label == "custom.key" })
        assertTrue(ProfessionalSnippets.builtIns("ini", "key").single().insertText.contains("=${'$'}{2:value}"))
        assertTrue(DroideSyntaxSemanticEngine.highlight("[section", "ini").spans.all { !it.semantic })
    }

    @Test fun resourceBoundsAndTheKnownSupplementaryFallbackStayExplicit() {
        assertTrue(DroideSyntaxSemanticEngine.highlight("key=😀", "ini").spans.isEmpty())
        assertTrue(DroideSyntaxSemanticEngine.highlight("x".repeat(DroideSyntaxSemanticEngine.MAX_HIGHLIGHT_CHARS + 1), "ini").skippedForSize)
        assertTrue(BuiltInEditorGrammars.highlight("a=b\n".repeat(100), "ini", 2)!!.size <= 2)
        assertTrue(BuiltInEditorGrammars.highlight("a=b", "ini", 0)!!.isEmpty())
    }
}
