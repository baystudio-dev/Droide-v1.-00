package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LspSignatureLongLabelTest {
    private fun parse(label: String, start: Int, end: Int): LspSignatureHelp? = LspSignatureHelpProtocol.parse(buildJsonObject {
        put("signatures",buildJsonArray { add(buildJsonObject {
            put("label",label)
            put("parameters",buildJsonArray { add(buildJsonObject {
                put("label",buildJsonArray { add(start);add(end) });put("documentation","selected parameter")
            }) })
        }) })
    })
    private val longLabel = "f(" + "x".repeat(2100) + ", arg)"
    @Test fun validOffsetBeyondPreviewDoesNotDropSignature() {
        val help=parse(longLabel,2104,2107)!!
        assertEquals(longLabel,help.signatures[0].label)
        assertEquals("arg",help.signatures[0].parameters[0].label)
        assertEquals("selected parameter",help.signatures[0].parameters[0].documentation)
    }
    @Test fun retriggerPreservesFullSemanticLabelAndOffsets() {
        val help=parse(longLabel,2104,2107)!!
        val context=LspSignatureHelpProtocol.context(LspSignatureTriggerKind.CONTENT_CHANGE,null,true,help)
        val again=LspSignatureHelpProtocol.parse(context["activeSignatureHelp"])!!
        assertEquals(longLabel,again.signatures[0].label)
        assertEquals(2104,again.signatures[0].parameters[0].startOffset)
    }
    @Test fun rangeCrossingPreviewBoundaryRemainsValid() {
        val label="x".repeat(1998)+"abcdef"
        assertEquals("abcdef",parse(label,1998,2004)!!.signatures[0].parameters[0].label)
    }
    @Test fun utf16OffsetsAfterSupplementaryCharactersRemainValid() {
        val label="😀".repeat(1100)+"arg"
        val param=parse(label,2200,2203)!!.signatures[0].parameters[0]
        assertEquals("arg",param.label);assertEquals(2200,param.startOffset)
    }
    @Test fun offsetsOutsideOriginalLabelRemainRejected() { assertNull(parse(longLabel,2104,9999)) }
    private fun preview(label: String): String = LspSignatureHelpProtocol::class.java
        .getDeclaredMethod("displayLabel",String::class.java).invoke(LspSignatureHelpProtocol,label) as String
    @Test fun previewKeepsExistingBound() { assertEquals(2000,preview(longLabel).length) }
    @Test fun previewNeverSplitsSurrogatePair() {
        val label="x".repeat(1999)+"😀"+"z"
        assertEquals("x".repeat(1999),preview(label))
    }
    @Test fun shortPreviewUnchanged() { assertEquals("f(😀)",preview("f(😀)")) }
}
