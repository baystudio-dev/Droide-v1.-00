package com.baystudio.droide.ui
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class DroideSnippetVariableAliasTest {
    private val root=File("/tmp/droide-workspace")
    private val resolver=DroideFileSnippetVariableResolver(File(root,"src/main.kt"),root,"src/main.kt")
    @Test fun standardAndLegacyNamesAdvertised() {
        assertTrue(resolver.getResolvableNames().contains("RELATIVE_FILEPATH"))
        assertTrue(resolver.getResolvableNames().contains("RELATIVE_PATH"))
    }
    @Test fun bothNamesResolveIdentically() {
        assertEquals("src/main.kt",resolver.resolve("RELATIVE_FILEPATH"))
        assertEquals(resolver.resolve("RELATIVE_PATH"),resolver.resolve("RELATIVE_FILEPATH"))
    }
    @Test fun existingFilenameVariablesPreserved() {
        assertEquals("main.kt",resolver.resolve("TM_FILENAME"));assertEquals("main",resolver.resolve("TM_FILENAME_BASE"))
    }
}
