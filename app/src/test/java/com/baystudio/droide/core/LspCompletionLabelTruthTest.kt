package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LspCompletionLabelTruthTest {
    private fun parse(label: String, insert: String? = null) = LspCompletionProtocol.parse(buildJsonArray {
        add(buildJsonObject { put("label",label);insert?.let { put("insertText",it) } })
    })
    @Test fun fallbackInsertionUsesFullWireLabel() {
        val label="a".repeat(700)
        val item=parse(label).single()
        assertEquals(label,item.insertText);assertEquals(500,item.label.length)
    }
    @Test fun displayBoundaryDoesNotRejectValidSupplementaryCharacter() {
        val label="a".repeat(499)+"😀"+"tail"
        val item=parse(label).single()
        assertEquals(label,item.insertText);assertEquals("a".repeat(499),item.label)
    }
    @Test fun explicitInsertStillOverridesLongLabel() {
        val item=parse("a".repeat(700),"explicit").single()
        assertEquals("explicit",item.insertText);assertEquals(500,item.label.length)
    }
    @Test fun explicitInsertDisplayDoesNotSplitSupplementaryCharacter() {
        val item=parse("a".repeat(499)+"😀","explicit").single()
        assertEquals("a".repeat(499),item.label);assertEquals("explicit",item.insertText)
    }
    @Test fun shortFallbackIsUnchanged() { assertEquals("hello",parse("hello").single().insertText) }
    @Test fun explicitEmptyInsertionIsUnchanged() { assertEquals("",parse("hello","").single().insertText) }
    @Test fun existingInsertionLimitIsNotBypassedByDisplayTruncation() {
        assertTrue(parse("a".repeat(ProfessionalSnippets.MAX_SNIPPET_CHARS+1)).isEmpty())
    }
}
