package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspSignatureParameterSelectionTest {
    private fun parse(active: Int?, local: Int? = null, malformed: Int? = null, count: Int = 3): LspSignatureHelp? {
        val root = buildJsonObject {
            active?.let { put("activeParameter", it) }
            put("signatures", buildJsonArray {
                add(buildJsonObject {
                    put("label", "f(" + (0 until count).joinToString(",") { "arg$it" } + ")")
                    local?.let { put("activeParameter", it) }
                    put("parameters", buildJsonArray {
                        repeat(count) { index ->
                            add(if (index == malformed) JsonNull else buildJsonObject { put("label", "arg$index") })
                        }
                    })
                })
            })
        }
        return LspSignatureHelpProtocol.parse(root)
    }
    private fun selected(help: LspSignatureHelp): String =
        help.signatures[help.activeSignature].parameters[help.activeParameter].label

    @Test fun outOfRangeRootDefaultsToFirstNotLast() { assertEquals("arg0", selected(parse(99)!!)) }
    @Test fun outOfRangeLocalOverridesRootWithDefaultZero() { assertEquals("arg0", selected(parse(1,99)!!)) }
    @Test fun negativeLocalDoesNotFallBackToRootSelection() { assertEquals("arg0", selected(parse(2,-1)!!)) }
    @Test fun validLocalOverridesRoot() { assertEquals("arg2", selected(parse(1,2)!!)) }
    @Test fun absentIndexesDefaultToFirst() { assertEquals("arg0", selected(parse(null)!!)) }
    @Test fun malformedPredecessorDoesNotShiftIdentity() { assertEquals("arg1", selected(parse(1,malformed=0)!!)) }
    @Test fun malformedSelectedDoesNotHighlightAnotherParameter() { assertNull(parse(1,malformed=1)) }
    @Test fun lateValidParameterSurvivesPresentationBound() {
        val help = parse(69,count=70)!!
        assertEquals("arg69",selected(help))
        assertEquals(LspSignatureHelpProtocol.MAX_PARAMETERS,help.signatures[0].parameters.size)
    }
    @Test fun emptyParametersRemainValid() {
        val help = parse(99,count=0)!!
        assertTrue(help.signatures[0].parameters.isEmpty())
        assertEquals(0,help.activeParameter)
    }
    @Test fun retriggerKeepsRemappedParameterIdentity() {
        val help=parse(69,count=70)!!
        val context=LspSignatureHelpProtocol.context(LspSignatureTriggerKind.CONTENT_CHANGE,null,true,help)
        val again=LspSignatureHelpProtocol.parse(context["activeSignatureHelp"])!!
        assertEquals("arg69",selected(again))
    }
}
