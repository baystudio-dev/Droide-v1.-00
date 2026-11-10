package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspVueCompanionAuthorityTest {
    private fun doc(n: Int = 0, text: String = "dirty", lifetime: Long = 1, revision: Long = 1) =
        LspVueCompanionAuthority.Document("$n.ts", "file:///root/$n.ts", "typescript", lifetime, revision, text)
    private fun capture(vararg docs: LspVueCompanionAuthority.Document, mutation: Long = 1) =
        LspVueCompanionAuthority.capture(1, mutation, docs.toList())
    private fun response(request: JsonObject, capture: LspVueCompanionAuthority.Capture) = buildJsonObject {
        listOf("pairEpoch", "syncSerial", "captureDigest").forEach { put(it, request.getValue(it)) }
        put("wireVersions", buildJsonObject { capture.documents.forEach { put(it.uri, 1) } })
    }
    @Test fun receiptRequiresExactFullMembershipAndActualResponse() {
        val authority = LspVueCompanionAuthority(); val capture = capture(doc(), doc(1))
        assertNull(authority.reusable(capture, 7))
        val params = authority.synchronize(capture, 7)
        assertEquals(2, (params["documents"] as JsonArray).size)
        assertTrue(runCatching { authority.accept(capture, params, JsonNull) }.isFailure)
        val accepted = authority.accept(capture, params, response(params, capture))
        assertSame(accepted, authority.reusable(capture, 7))
        assertNull(authority.reusable(capture, 8))
        val missing = JsonObject(response(params, capture) + ("wireVersions" to buildJsonObject { put("file:///root/0.ts", 1) }))
        assertTrue(runCatching { authority.accept(capture, params, missing) }.isFailure)
    }
    @Test fun membershipCloseReopenRevisionAndMonotonicMutationChangeDigest() {
        val baseline = capture(doc())
        for (changed in listOf(capture(doc(), doc(1)), capture(doc(lifetime = 2)), capture(doc(revision = 2)),
            capture(doc(), mutation = 2), capture(mutation = 2))) assertNotEquals(baseline.digest, changed.digest)
        assertEquals(capture(doc(1), doc()).digest, capture(doc(), doc(1)).digest)
    }
    @Test fun openCountAndUtf8AggregateBoundsNeverEvict() {
        assertEquals(128, LspVueCompanionAuthority.capture(1, 1, List(128) { doc(it) }).documents.size)
        assertTrue(runCatching { LspVueCompanionAuthority.capture(1, 1, List(129) { doc(it) }) }.isFailure)
        val oneMiB = "x".repeat(1024 * 1024)
        assertEquals(4, LspVueCompanionAuthority.capture(1, 1, List(4) { doc(it, oneMiB) }).documents.size)
        assertTrue(runCatching { capture(doc(text = oneMiB + "x")) }.isFailure)
        assertTrue(runCatching { LspVueCompanionAuthority.capture(1, 1, List(5) { doc(it, oneMiB) }) }.isFailure)
        assertTrue(runCatching { capture(doc(text = "😀".repeat(262145))) }.isFailure)
    }
    @Test fun encodedRequestBoundIsIndependentOfTextBound() {
        val authority = LspVueCompanionAuthority()
        val large = capture(doc(text = "\u0001".repeat(1024 * 1024)))
        assertTrue(runCatching { authority.synchronize(large, 1) }.isFailure)
    }
    @Test fun malformedReceiptAndUnsafeTextFailClosed() {
        for (text in listOf("\u0000", "\ud800", "\udc00", "a\rb")) assertTrue(runCatching { capture(doc(text = text)) }.isFailure)
        val authority = LspVueCompanionAuthority(); val c = capture(doc()); val p = authority.synchronize(c, 1)
        for (value in listOf(JsonNull, JsonPrimitive("1"), JsonPrimitive(0), JsonObject(emptyMap()))) {
            val wrong = JsonObject(response(p, c) + ("wireVersions" to buildJsonObject { put(c.documents.single().uri, value) }))
            assertTrue(runCatching { authority.accept(c, p, wrong) }.isFailure)
        }
        authority.clear(); assertNull(authority.reusable(c, 1))
    }
}
