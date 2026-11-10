package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class LspCMakePositionCodecTest {
    @Test fun scalarAndUtf16RoundTripsPreserveCrlf() {
        val text = "A😀é\r\n𐐀中\n"
        val view = requireNotNull(LspCMakePositionCodec.capture(text))
        assertEquals(text, view.text)
        for ((line, col, wire) in listOf(Triple(1,1,0),Triple(1,2,1),Triple(1,4,2),Triple(1,5,3),Triple(2,1,0),Triple(2,3,1),Triple(2,4,2),Triple(3,1,0))) {
            assertEquals(LspCMakePositionCodec.Position(line-1,wire),view.toWire(line,col))
            assertEquals(LspCompletionCaret(line,col),view.toEditor(line-1,wire))
        }
        assertNull(view.toWire(1,3)); assertNull(view.toWire(1,6)); assertNull(view.toWire(4,1))
        assertNull(view.toEditor(0,4)); assertNull(view.toEditor(-1,0)); assertNull(view.toEditor(0,Int.MAX_VALUE))
    }
    @Test fun textRejectsMalformedScalarsAndLineEndings() {
        for (text in listOf("x\u0000", "x\r", "\ud800", "\udc00", "\ud800x", "a".repeat(2_000_001))) assertNull(LspCMakePositionCodec.capture(text))
        assertNotNull(LspCMakePositionCodec.capture("\r\n")); assertNotNull(LspCMakePositionCodec.capture("a".repeat(2_000_000)))
    }
    @Test fun hoverConvertsBothEndpointsAndNeverClamps() {
        val snapshot = requireNotNull(LspCMakePositionCodec.capture("😀abc\r\nz"))
        fun range(sl:Int,sc:Int,el:Int,ec:Int)=Json.parseToJsonElement("""{"start":{"line":$sl,"character":$sc},"end":{"line":$el,"character":$ec}}""")
        assertEquals(TextRangeEdit(1,3,1,6,""),snapshot.hoverRange(range(0,1,0,4)))
        assertNull(snapshot.hoverRange(range(0,1,1,1)));assertNull(snapshot.hoverRange(range(0,1,0,1)))
        assertNull(snapshot.hoverRange(range(0,9,1,1)));assertNull(snapshot.hoverRange(range(1,1,0,0)))
        assertNull(snapshot.hoverRange(Json.parseToJsonElement("""{"start":{"line":"0","character":0},"end":{"line":0,"character":1}}""")))
    }
    @Test fun fullChangeIsRangeLessAndExact() {
        val text="😀\r\nx"
        val changes=requireNotNull(LspCMakePositionCodec.capture(text)).fullChanges()
        assertEquals(1,changes.size); assertEquals(setOf("text"),changes[0].jsonObject.keys)
        assertEquals(text,changes[0].jsonObject.getValue("text").jsonPrimitive.content)
    }
}
