package com.baystudio.droide.core
import org.junit.Assert.*
import org.junit.Test
class SnippetVariableLiteralTest {
    @Test fun ordinaryLiteralUnchanged() {
        assertEquals("val RELATIVE_FILEPATH = 1",ProfessionalSnippets.sanitizeForSora("val RELATIVE_FILEPATH = 1"))
    }
    @Test fun longerIdentifierUnchanged() {
        assertEquals("MY_RELATIVE_FILEPATH_SUFFIX",ProfessionalSnippets.sanitizeForSora("MY_RELATIVE_FILEPATH_SUFFIX"))
    }
    @Test fun escapedVariableLiteralUnchanged() {
        val source=92.toChar().toString()+36.toChar()+"RELATIVE_FILEPATH"
        assertEquals(source,ProfessionalSnippets.sanitizeForSora(source))
    }
    @Test fun placeholderDefaultLiteralUnchanged() {
        val source=36.toChar().toString()+"{1:RELATIVE_FILEPATH}"
        assertEquals(source,ProfessionalSnippets.sanitizeForSora(source))
    }
    @Test fun standardVariableRetainedForResolver() {
        val source=36.toChar().toString()+"{RELATIVE_FILEPATH}"
        assertEquals(source,ProfessionalSnippets.sanitizeForSora(source))
    }
}
