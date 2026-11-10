package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspVueResultAuthorityTest {
    @Test fun nativeSignatureMetadataSurvivesValidUtf16TupleValidation() {
        val value = Json.parseToJsonElement("""{"signatures":[{"label":"f(😀, x)","documentation":{"kind":"markdown","value":"native docs"},"parameters":[{"label":[2,4],"documentation":"emoji"},{"label":[6,7],"documentation":"second"}],"activeParameter":1}],"activeSignature":0,"activeParameter":1}""")
        assertSame(value, LspVueResultAuthority.signatureHelp(value))
        val parsed = LspSignatureHelpProtocol.parse(LspVueResultAuthority.signatureHelp(value))!!
        assertEquals(1, parsed.activeParameter)
        assertEquals("😀", parsed.signatures.single().parameters[0].label)
        assertEquals(6, parsed.signatures.single().parameters[1].startOffset)
        assertEquals("second", parsed.signatures.single().parameters[1].documentation)
        assertEquals("native docs", parsed.signatures.single().documentation)
        for (bad in listOf("[3,4]", "[2,3]", "[2,99]", "[4,2]", "[\"2\",4]", "{}")) {
            val malformed = Json.parseToJsonElement(value.toString().replace("[2,4]", bad))
            assertEquals(JsonNull, LspVueResultAuthority.signatureHelp(malformed))
        }
    }
    @Test fun malformedContainersNeverEscapeAsJsonPrimitiveExceptions() {
        val malformed = listOf("{}", "[]", "true", "null", "{\"signatures\":[{\"label\":{},\"activeParameter\":[]}]}",
            "{\"signatures\":[{\"label\":\"fn(x)\",\"parameters\":[{\"label\":[{},[]]}]}]}")
        for (value in malformed) assertTrue(runCatching { LspSignatureHelpProtocol.parse(Json.parseToJsonElement(value)) }.isSuccess)
    }
    @Test fun responseCorrelatesEveryCapturedFieldAndRejectsMissingOutcomes() {
        val request = buildJsonObject { put("method", "textDocument/documentSymbol"); put("pairEpoch", 1); put("syncSerial", 2); put("captureDigest", "digest"); put("requestSerial", 3) }
        val result = JsonObject(request + mapOf("result" to JsonNull, "sourceOutcomes" to buildJsonObject {
            put("vue", buildJsonObject { put("status", "ok") })
        }))
        assertEquals(JsonNull, LspVueResultAuthority.response(result, request).first)
        for (key in listOf("pairEpoch", "syncSerial", "captureDigest", "requestSerial"))
            assertTrue(runCatching { LspVueResultAuthority.response(JsonObject(result - key), request) }.isFailure)
        assertTrue(runCatching { LspVueResultAuthority.response(JsonObject(result - "sourceOutcomes"), request) }.isFailure)
    }
    @Test fun fourDiagnosticSourcesAreRequiredForAnEmptyCleanClaim() {
        fun outcomes(bad: String? = null) = buildJsonObject {
            for (source in listOf("vue", "ts-syntactic", "ts-semantic", "ts-suggestion")) put(source,
                buildJsonObject { put("status", if (source == bad) "error" else "ok") })
        }
        assertTrue(LspVueResultAuthority.diagnosticComplete(outcomes()))
        for (source in outcomes().keys) assertFalse(LspVueResultAuthority.diagnosticComplete(outcomes(source)))
        assertFalse(LspVueResultAuthority.diagnosticComplete(JsonObject(outcomes() - "vue")))
    }
    @Test fun utf16CoordinatesAreMechanicalAndRejectSplitSurrogates() {
        val text = "A😀B\r\nnext"
        assertTrue(LspVueResultAuthority.validText(text))
        assertTrue(LspVueResultAuthority.validPosition(text, 0, 3))
        assertFalse(LspVueResultAuthority.validPosition(text, 0, 2))
        assertFalse(LspVueResultAuthority.validPosition(text, 1, 9))
        assertFalse(LspVueResultAuthority.validRange(text, JsonPrimitive("range")))
    }
}
