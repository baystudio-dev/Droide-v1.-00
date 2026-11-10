package com.baystudio.droide.core
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.*
import org.junit.Test
class CodeStyleTabWidthFallbackTest {
    private fun resolve(config: String, child: String? = null): CodeStyleProfile {
        val root=createTempDirectory("droide-style-width").toFile()
        try {
            File(root,".editorconfig").writeText("root = true\n[*]\n"+config)
            File(root,"src").mkdirs()
            if(child!=null) File(root,"src/.editorconfig").writeText("[*]\n"+child)
            return CodeStyleAuthority(root).resolve("src/test.kt","kotlin","",CodeStyleDefaults(detectIndentation=false))
        } finally { root.deleteRecursively() }
    }
    @Test fun spaceIndentSizeSuppliesMissingTabWidth() {
        val p=resolve("indent_style = space\nindent_size = 2")
        assertEquals(2,p.indentSize);assertEquals(2,p.tabWidth)
    }
    @Test fun tabIndentSizeSuppliesMissingTabWidth() { assertEquals(3,resolve("indent_style = tab\nindent_size = 3").tabWidth) }
    @Test fun nestedIndentSizeSuppliesWidthAfterMerge() { assertEquals(2,resolve("indent_size = 8","indent_size = 2").tabWidth) }
    @Test fun explicitWidthOverridesFallback() { assertEquals(7,resolve("indent_size = 2\ntab_width = 7").tabWidth) }
    @Test fun inheritedExplicitWidthIsPreserved() { assertEquals(7,resolve("indent_size = 8\ntab_width = 7","indent_size = 2").tabWidth) }
    @Test fun unsetWidthRestoresUserDefaultNotDerivedWidth() { assertEquals(4,resolve("indent_size = 2\ntab_width = 7","tab_width = unset").tabWidth) }
    @Test fun laterExplicitWidthReplacesUnset() { assertEquals(6,resolve("indent_size = 2\ntab_width = unset","tab_width = 6").tabWidth) }
    @Test fun tabSentinelUsesWidthWithoutNumericFallback() {
        val p=resolve("indent_style = tab\nindent_size = tab\ntab_width = 3")
        assertEquals(3,p.indentSize);assertEquals(3,p.tabWidth)
    }
    @Test fun malformedIndentSizeDoesNotInventWidth() { assertEquals(4,resolve("indent_size = nope").tabWidth) }
}
