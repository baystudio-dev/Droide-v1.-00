package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspSignatureSelectionTest {
    private fun parse(active: Int, entries: List<JsonElement>): LspSignatureHelp? =
        LspSignatureHelpProtocol.parse(buildJsonObject {
            put("signatures", JsonArray(entries))
            put("activeSignature", active)
        })
    private fun signature(label: String) = buildJsonObject { put("label", label) }

    @Test fun selectedOverloadOutsidePresentationWindowIsKept() {
        val help = parse(19, (0..19).map { signature("overload$it()") })!!
        assertEquals("overload19()", help.signatures[help.activeSignature].label)
        assertEquals(LspSignatureHelpProtocol.MAX_SIGNATURES, help.signatures.size)
    }
    @Test fun selectedOverloadAtWindowEdgeIsKept() {
        val help = parse(12, (0..12).map { signature("overload$it()") })!!
        assertEquals("overload12()", help.signatures[help.activeSignature].label)
    }
    @Test fun malformedPrecedingOverloadDoesNotShiftSelection() {
        val help = parse(1, listOf(JsonNull, signature("selected()"), signature("other()")))!!
        assertEquals("selected()", help.signatures[help.activeSignature].label)
    }
    @Test fun malformedSelectedOverloadDoesNotShowAnother() {
        assertNull(parse(1, listOf(signature("first()"), JsonNull, signature("other()"))))
    }
    @Test fun outOfRangeSelectionUsesProtocolDefaultFirst() {
        val help = parse(8, listOf(signature("first()"), signature("last()")))!!
        assertEquals("first()", help.signatures[help.activeSignature].label)
    }
    @Test fun retriggerContextKeepsSelectionOnSameBoundedOverload() {
        val help = parse(19, (0..19).map { signature("overload$it()") })!!
        val context = LspSignatureHelpProtocol.context(LspSignatureTriggerKind.CONTENT_CHANGE,
            null, true, help).getValue("activeSignatureHelp").jsonObject
        val selected = context.getValue("activeSignature").jsonPrimitive.int
        assertEquals("overload19()", context.getValue("signatures").jsonArray[selected]
            .jsonObject.getValue("label").jsonPrimitive.content)
    }
    @Test fun smallValidResponsePreservesOrderAndIndex() {
        val help = parse(1, listOf(signature("first()"), signature("selected()"), signature("last()")))!!
        assertEquals(1, help.activeSignature)
        assertEquals(listOf("first()", "selected()", "last()"), help.signatures.map { it.label })
    }
}
