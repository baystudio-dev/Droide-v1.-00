package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LspDiagnosticMessageUnicodeTest {
    private fun diagnostic(message:String)=buildJsonObject {
        put("range",buildJsonObject {
            put("start",buildJsonObject {put("line",0);put("character",0)})
            put("end",buildJsonObject {put("line",0);put("character",1)})
        });put("message",message)
    }
    @Test fun messagePreviewDoesNotSplitPair() {
        assertEquals("a".repeat(1999),LspDiagnosticsProtocol.parse(diagnostic("a".repeat(1999)+"🙂"))!!.message)
    }
    @Test fun pullReportUsesSameSafeMessagePreview() {
        val report=buildJsonObject {
            put("kind","full");put("items",JsonArray(listOf(diagnostic("a".repeat(1999)+"🙂"))))
        }
        val result=LspDiagnosticsProtocol.parsePull(report) as LspDiagnosticsProtocol.PullReport.Full
        assertEquals("a".repeat(1999),result.items.single().message)
    }
    @Test fun completePairAtBoundaryPreserved() {
        val message="a".repeat(1998)+"🙂"
        assertEquals(message,LspDiagnosticsProtocol.parse(diagnostic(message))!!.message)
    }
    @Test fun asciiLimitUnchanged() {
        assertEquals("a".repeat(2000),LspDiagnosticsProtocol.parse(diagnostic("a".repeat(2001)))!!.message)
    }
    @Test fun shortMessageAndCoordinatesPreserved() {
        val result=LspDiagnosticsProtocol.parse(diagnostic("error"))!!
        assertEquals("error",result.message);assertEquals(1,result.line);assertEquals(1,result.column)
    }
}
