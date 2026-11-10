package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspPowerShellProtocolAdaptersTest {
    private val legend = LspSemanticTokenLegend(listOf("string", "variable"), listOf("readonly", "declaration"))
    private fun tokens(text: String, vararg data: Long) = LspSemanticTokensProtocol.parseFullMultiline(
        buildJsonObject { put("data", JsonArray(data.map(::JsonPrimitive))) }, legend, text)

    @Test fun multilineCountsButDoesNotPaintCrLfAndPreservesNegotiatedLegend() {
        val result = tokens("ab\r\n😀x\nz", 0, 1, 8, 1, 3)
        assertEquals(listOf(
            LspSemanticToken(0, 1, 1, "variable", setOf("readonly", "declaration")),
            LspSemanticToken(1, 0, 3, "variable", setOf("readonly", "declaration")),
            LspSemanticToken(2, 0, 1, "variable", setOf("readonly", "declaration")),
        ), result)
    }

    @Test fun multilineSupportsBlankLinesAndRelativeStartsWithoutNewlineSpans() {
        assertEquals(listOf(LspSemanticToken(2, 0, 1, "string")), tokens("\n\r\na", 0, 0, 4, 0, 0))
        assertEquals(listOf(LspSemanticToken(0, 0, 1, "string"), LspSemanticToken(1, 1, 1, "variable")),
            tokens("a\r\nxy", 0, 0, 1, 0, 0, 1, 1, 1, 1, 0))
        assertEquals(listOf(LspSemanticToken(0, 0, 1, "string")), tokens("a\n", 0, 0, 2, 0, 0))
    }

    @Test fun multilineRejectsMalformedOffsetsIndicesOverlapAndSurrogateOrCrLfSplits() {
        for (data in listOf(
            longArrayOf(-1, 0, 1, 0, 0), longArrayOf(0, -1, 1, 0, 0), longArrayOf(0, 0, 0, 0, 0),
            longArrayOf(0, 0, 16_385, 0, 0), longArrayOf(0, 0, 1, 2, 0), longArrayOf(0, 0, 1, 0, 4),
            longArrayOf(0, 0, 1, 0, -1), longArrayOf(0, Int.MAX_VALUE.toLong(), 1, 0, 0),
            longArrayOf(Int.MAX_VALUE.toLong(), 0, 1, 0, 0), longArrayOf(Long.MAX_VALUE, 0, 1, 0, 0),
            longArrayOf(0, 0, 99, 0, 0), longArrayOf(0, 1, 1, 0, 0), longArrayOf(0, 0, 1, 0, 0),
            longArrayOf(0, 0, 3, 0, 0), longArrayOf(0, 0, 5, 0, 0, 1, 0, 1, 0, 0),
        )) assertTrue("accepted ${data.toList()}", tokens("😀\r\nx", *data).isEmpty())
        assertTrue(tokens("\ud800", 0, 0, 1, 0, 0).isEmpty())
        assertTrue(tokens("a\rb", 0, 0, 1, 0, 0).isEmpty())
        assertTrue(tokens("a\nb", 0, 1, 1, 0, 0, 1, 0, 1, 0, 0).isEmpty())
        for (data in listOf("[0,0,1,0]", "[0,0,1,0,0,0]", "[0,0,1,{},0]", "[0,0,1,\"0\",0]", "[0,0,1.0,0,0]"))
            assertTrue(LspSemanticTokensProtocol.parseFullMultiline(Json.parseToJsonElement("{\"data\":$data}"), legend, "x").isEmpty())
    }

    @Test fun nativeAndExpandedTokenCapsFailClosedAndGenericDecoderStaysUnchanged() {
        assertTrue(tokens("x\n".repeat(8_001), 0, 0, 16_002, 0, 0).isEmpty())
        val data = buildJsonObject { put("data", buildJsonArray { repeat(8_001) { add(0); add(1); add(1); add(0); add(0) } }) }
        assertTrue(LspSemanticTokensProtocol.parseFullMultiline(data, legend, "x".repeat(8_002)).isEmpty())
        val raw = Json.parseToJsonElement("{\"data\":[0,0,5,0,0]}")
        assertEquals(listOf(LspSemanticToken(0, 0, 5, "string")), LspSemanticTokensProtocol.parseFull(raw, legend))
        assertEquals(listOf(LspSemanticToken(0, 0, 1, "string"), LspSemanticToken(1, 0, 2, "string")),
            LspSemanticTokensProtocol.parseFullMultiline(raw, legend, "a\r\nbc"))
    }

    private fun diagnostic(code: JsonElement?) = buildJsonObject {
        put("range", buildJsonObject {
            put("start", buildJsonObject { put("line", 0); put("character", 0) })
            put("end", buildJsonObject { put("line", 0); put("character", 1) })
        }); put("message", "native suggestion"); put("source", "PSScriptAnalyzer"); put("severity", 2)
        code?.let { put("code", it) }
    }

    @Test fun boundedDiagnosticCodesRetainStringVsIntegerOnlyForOptedInParser() {
        for (value in listOf(JsonPrimitive("PSUseDeclaredVarsMoreThanAssignments"), JsonPrimitive("123"), JsonPrimitive(123), JsonPrimitive(-1))) {
            val raw = diagnostic(value)
            assertNull(LspDiagnosticsProtocol.parse(raw)!!.code)
            assertEquals(value, LspDiagnosticsProtocol.parse(raw, retainCode = true)!!.code)
            assertEquals(value, LspDiagnosticsProtocol.parseItems(JsonArray(listOf(raw)), retainCode = true)!!.single().code)
            assertNull((LspDiagnosticsProtocol.parsePull(buildJsonObject { put("kind", "full"); put("items", JsonArray(listOf(raw))) }) as LspDiagnosticsProtocol.PullReport.Full).items.single().code)
        }
        assertTrue(LspDiagnosticsProtocol.parse(diagnostic(JsonPrimitive("123")), true)!!.code!!.isString)
        assertFalse(LspDiagnosticsProtocol.parse(diagnostic(JsonPrimitive(123)), true)!!.code!!.isString)
        for (value in listOf(null, JsonNull, JsonPrimitive(true), JsonPrimitive(1.5), JsonPrimitive(Long.MAX_VALUE), JsonPrimitive("x".repeat(257)), buildJsonObject {})) {
            val parsed = LspDiagnosticsProtocol.parse(diagnostic(value), true)!!
            assertNull(parsed.code)
            assertEquals("native suggestion", parsed.message)
            assertEquals("PSScriptAnalyzer", parsed.source)
        }
        assertEquals(256, LspDiagnosticsProtocol.parse(diagnostic(JsonPrimitive("x".repeat(256))), true)!!.code!!.content.length)
    }

    private fun completion(primary: Boolean = true, command: JsonElement? = null) = buildJsonArray {
        add(buildJsonObject {
            put("label", "native"); put("insertText", "ignored")
            if (primary) put("textEdit", buildJsonObject {
                put("newText", "authoritative")
                put("range", buildJsonObject {
                    put("start", buildJsonObject { put("line", 0); put("character", 0) })
                    put("end", buildJsonObject { put("line", 0); put("character", 1) })
                })
            })
            command?.let { put("command", it) }
        })
    }

    @Test fun nativePrimaryIsAuthoritativeAndAnyNonNullCommandRejectsWholeItemOnlyWhenOptedIn() {
        fun parse(raw: JsonElement) = LspCompletionProtocol.parse(raw, 1, 2, "x", strictSnippets = true, requirePrimaryEdit = true, rejectCommand = true)
        assertEquals("authoritative", parse(completion()).single().insertText)
        assertTrue(parse(completion(primary = false)).isEmpty())
        assertEquals(1, LspCompletionProtocol.parse(completion(primary = false), 1, 2, "x").size)
        assertEquals(1, parse(completion(command = JsonNull)).size)
        for (command in listOf(JsonPrimitive(false), JsonPrimitive("evil"), JsonPrimitive(1), buildJsonObject { put("command", "execute") })) {
            assertTrue(parse(completion(command = command)).isEmpty())
            assertEquals(1, LspCompletionProtocol.parse(completion(command = command), 1, 2, "x").size)
        }
    }
}
