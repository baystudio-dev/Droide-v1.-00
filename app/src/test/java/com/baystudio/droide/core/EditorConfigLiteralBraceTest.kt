package com.baystudio.droide.core

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.*
import org.junit.Test

class EditorConfigLiteralBraceTest {
    private fun matches(pattern: String, path: String) =
        EditorConfigGlob.matches(pattern, path, EditorConfigGlob.Budget())

    @Test fun singleItemBracesAreLiteral() {
        assertTrue(matches("file{one}.kt", "file{one}.kt"))
        assertFalse(matches("file{one}.kt", "fileone.kt"))
    }
    @Test fun unmatchedOpeningBraceIsLiteral() {
        assertTrue(matches("file{one.kt", "file{one.kt"))
        assertFalse(matches("file{one.kt", "fileone.kt"))
    }
    @Test fun innerChoicePreservesOuterLiteralBraces() {
        assertTrue(matches("file{{one,two}}.kt", "file{one}.kt"))
        assertTrue(matches("file{{one,two}}.kt", "file{two}.kt"))
        assertFalse(matches("file{{one,two}}.kt", "fileone.kt"))
    }
    @Test fun laterChoiceStillExpandsAfterLiteralBraces() {
        assertTrue(matches("file{one}{a,b}.kt", "file{one}a.kt"))
        assertTrue(matches("file{one}{a,b}.kt", "file{one}b.kt"))
        assertFalse(matches("file{one}{a,b}.kt", "fileonea.kt"))
    }
    @Test fun laterChoiceStillExpandsAfterUnmatchedOpeningBrace() {
        assertTrue(matches("file{one{a,b}.kt", "file{onea.kt"))
        assertTrue(matches("file{one{a,b}.kt", "file{oneb.kt"))
    }
    @Test fun ordinaryNestedChoicesAndEscapesRemainValid() {
        assertTrue(matches("file{a,{b,c}}.kt", "filec.kt"))
        assertTrue(matches("file{a,{b,c}}.kt", "filea.kt"))
        assertTrue(matches("file\\{a,b\\}.kt", "file{a,b}.kt"))
        assertFalse(matches("file{a,b}.kt", "filec.kt"))
    }
    @Test fun emptyAlternativesAndBudgetRemainBounded() {
        assertTrue(matches("file{,a}.kt", "file.kt"))
        assertTrue(matches("file{,a}.kt", "filea.kt"))
        assertFalse(EditorConfigGlob.matches("file{one}.kt", "file{one}.kt", EditorConfigGlob.Budget(0)))
        assertFalse(matches("{".repeat(513), "{".repeat(513)))
    }
    @Test fun unmatchedClosingBraceIsLiteral() {
        assertTrue(matches("file}.kt", "file}.kt"))
        assertFalse(matches("file}.kt", "file.kt"))
    }
    @Test fun escapedCommaIsNotAnAlternative() {
        assertTrue(matches("file{a\\,b}.kt", "file{a,b}.kt"))
        assertFalse(matches("file{a\\,b}.kt", "filea,b.kt"))
        assertTrue(matches("file{a,b\\,c}.kt", "fileb,c.kt"))
    }
    @Test fun bracesWithinCharacterClassFollowReferenceExpansion() {
        assertTrue(matches("file[{}].kt", "file{.kt"))
        assertTrue(matches("file[{}].kt", "file}.kt"))
        assertTrue(matches("file[{a,b}].kt", "filea.kt"))
        assertTrue(matches("file[{a,b}].kt", "fileb.kt"))
        assertFalse(matches("file[{a,b}].kt", "file{.kt"))
    }
    @Test fun publicAuthorityDoesNotApplyLiteralRuleToDifferentFilename() {
        val root = createTempDirectory("droide-literal-brace").toFile()
        try {
            File(root, ".editorconfig").writeText("root = true\n[*]\nindent_size = 4\n[file{one}.kt]\nindent_size = 2\n")
            File(root, "file{one}.kt").writeText("")
            File(root, "fileone.kt").writeText("")
            val authority = CodeStyleAuthority(root)
            val defaults = CodeStyleDefaults(detectIndentation = false)
            assertEquals(2, authority.resolve("file{one}.kt", "kotlin", "", defaults).indentSize)
            assertEquals(4, authority.resolve("fileone.kt", "kotlin", "", defaults).indentSize)
        } finally { root.deleteRecursively() }
    }
}
