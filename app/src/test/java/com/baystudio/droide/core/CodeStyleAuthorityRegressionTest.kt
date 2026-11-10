package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Test

class CodeStyleAuthorityRegressionTest {
    private val defaults = CodeStyleDefaults(detectIndentation = false, indentSize = 4)

    private inline fun withProject(block: (File, CodeStyleAuthority) -> Unit) {
        val root = createTempDirectory("droide-code-style-regression").toFile()
        try { block(root, CodeStyleAuthority(root)) } finally { root.deleteRecursively() }
    }

    @Test fun malformedProjectGlobDoesNotCrashFileOpenOrHideValidSections() = withProject { root, styles ->
        root.resolve(".editorconfig").writeText("""
            root = true
            [*]
            indent_size = 3
            [*.kt[]
            indent_size = 8
            [*.kt]
            indent_style = tab
            tab_width = 2
        """.trimIndent())
        val result = styles.resolve("src/Main.kt", "kotlin", "", defaults)
        assertEquals(IndentStyle.TABS, result.indentStyle)
        assertEquals(3, result.indentSize)
        assertEquals(2, result.tabWidth)
        assertEquals(".editorconfig", result.source)
    }

    @Test fun boundedBraceExpansionPreservesFirstMatchingVariant() = withProject { root, styles ->
        val pattern = "{a,b}".repeat(24) + ".kt"
        root.resolve(".editorconfig").writeText("[$pattern]\nindent_size = 2\n")
        val result = styles.resolve("a".repeat(24) + ".kt", "kotlin", "", defaults)
        assertEquals(2, result.indentSize)
    }

    @Test fun recursiveWildcardBraceChoicesAndCharacterClassesStillMatch() = withProject { root, styles ->
        root.resolve(".editorconfig").writeText("[src/**/{main,test}[0-9].[ch]]\nindent_size = 2\n")
        assertEquals(2, styles.resolve("src/one/two/main3.c", "c", "", defaults).indentSize)
        assertEquals(2, styles.resolve("src/one/test7.h", "c", "", defaults).indentSize)
        assertEquals(4, styles.resolve("src/one/mainx.c", "c", "", defaults).indentSize)
    }

    @Test fun escapedMetaCharactersAreLiteralAndNegatedClassesWork() = withProject { root, styles ->
        root.resolve(".editorconfig").writeText("[literal\\[x\\].kt]\nindent_size = 2\n[*[!0-9].txt]\nindent_size = 3\n")
        assertEquals(2, styles.resolve("literal[x].kt", "kotlin", "", defaults).indentSize)
        assertEquals(3, styles.resolve("notesx.txt", "plaintext", "", defaults).indentSize)
        assertEquals(4, styles.resolve("notes7.txt", "plaintext", "", defaults).indentSize)
    }

    @Test fun regexPunctuationIsLiteralRatherThanUntrustedRegexSyntax() = withProject { root, styles ->
        root.resolve(".editorconfig").writeText("[literal(+)^$.kt]\nindent_size = 2\n")
        assertEquals(2, styles.resolve("literal(+)^$.kt", "kotlin", "", defaults).indentSize)
        assertEquals(4, styles.resolve("literal.kt", "kotlin", "", defaults).indentSize)
    }

    @Test fun oversizedConfigurationAndPatternsFallBackToUserDefaults() = withProject { root, styles ->
        root.resolve(".editorconfig").writeText("[${"a".repeat(2_000)}]\nindent_size = 8\n[*]\nindent_size = 2\n")
        assertEquals(2, styles.resolve("Main.kt", "kotlin", "", defaults).indentSize)
        root.resolve(".editorconfig").writeText("[*]\nindent_size = 8\n#" + "x".repeat(CodeStyleAuthority.MAX_EDITORCONFIG_BYTES.toInt()))
        assertEquals(4, styles.resolve("Main.kt", "kotlin", "", defaults).indentSize)
    }

    @Test fun configurationSymlinkOutsideProjectCannotOverrideStyle() = withProject { root, styles ->
        val outside = Files.createTempFile("droide-outside-config", ".txt").toFile()
        try {
            outside.writeText("[*]\nindent_size = 8\n")
            Files.createSymbolicLink(root.resolve(".editorconfig").toPath(), outside.toPath())
            assertEquals(4, styles.resolve("Main.kt", "kotlin", "", defaults).indentSize)
        } finally { outside.delete() }
    }

    @Test fun malformedClassRangeAndDanglingEscapeAreIgnored() = withProject { root, styles ->
        root.resolve(".editorconfig").writeText("[*]\nindent_size = 3\n[file[z-a].kt]\nindent_size = 8\n[*.kt\\]\nindent_size = 7\n")
        assertEquals(3, styles.resolve("filez.kt", "kotlin", "", defaults).indentSize)
    }
}
