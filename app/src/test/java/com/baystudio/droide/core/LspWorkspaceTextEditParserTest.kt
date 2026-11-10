package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LspWorkspaceTextEditParserTest {
    private fun parse(raw: String)=LspWorkspaceTextEditParser.parse(Json.parseToJsonElement(raw))
    private val range = """"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":3}}"""
    @Test fun missingTextNeverBecomesDeletion() { assertNull(parse("{$range}")) }
    @Test fun nullTextNeverBecomesDeletion() { assertNull(parse("""{$range,"newText":null}""")) }
    @Test fun numericTextNeverBecomesCode() { assertNull(parse("""{$range,"newText":42}""")) }
    @Test fun missingCoordinateNeverDefaultsToDocumentStart() {
        assertNull(parse("""{"range":{"start":{"line":0},"end":{"line":0,"character":3}},"newText":"x"}"""))
    }
    @Test fun quotedCoordinateRejected() {
        assertNull(parse("""{"range":{"start":{"line":"0","character":0},"end":{"line":0,"character":3}},"newText":"x"}"""))
    }
    @Test fun overflowCoordinateRejected() {
        assertNull(parse("""{"range":{"start":{"line":2147483647,"character":0},"end":{"line":2147483647,"character":3}},"newText":"x"}"""))
    }
    @Test fun negativeCoordinateRejected() {
        assertNull(parse("""{"range":{"start":{"line":0,"character":-1},"end":{"line":0,"character":3}},"newText":"x"}"""))
    }
    @Test fun reversedRangeRejected() {
        assertNull(parse("""{"range":{"start":{"line":0,"character":3},"end":{"line":0,"character":0}},"newText":"x"}"""))
    }
    @Test fun explicitDeletionStillWorks() {
        val edit=parse("""{$range,"newText":""}""")!!
        assertEquals("",TextEditApplier.apply("abc",listOf(edit)))
    }
    @Test fun validReplacementStillWorks() {
        val edit=parse("""{$range,"newText":"xyz"}""")!!
        assertEquals("xyz",TextEditApplier.apply("abc",listOf(edit)))
    }
    @Test fun annotatedEditFieldsRemainCompatible() {
        assertEquals("x",parse("""{$range,"newText":"x","annotationId":"approval"}""")!!.newText)
    }
}
