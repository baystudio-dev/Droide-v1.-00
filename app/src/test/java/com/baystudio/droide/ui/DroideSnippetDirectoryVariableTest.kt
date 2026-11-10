package com.baystudio.droide.ui
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class DroideSnippetDirectoryVariableTest {
    @Test fun standardDirectoryBaseAdvertised() {
        val resolver=DroideFileSnippetVariableResolver(File("/tmp/project/src/main.kt"),File("/tmp/project"),"src/main.kt")
        assertTrue(resolver.getResolvableNames().contains("TM_DIRECTORY_BASE"))
    }
    @Test fun directoryBaseResolvesOnlyLeafName() {
        val resolver=DroideFileSnippetVariableResolver(File("/tmp/project/src/main.kt"),File("/tmp/project"),"src/main.kt")
        assertEquals("src",resolver.resolve("TM_DIRECTORY_BASE"))
        assertEquals("/tmp/project/src",resolver.resolve("TM_DIRECTORY"))
    }
    @Test fun fileWithoutParentHasEmptyDirectoryBase() {
        val resolver=DroideFileSnippetVariableResolver(File("main.kt"),File("."),"main.kt")
        assertEquals("",resolver.resolve("TM_DIRECTORY_BASE"))
    }
}
