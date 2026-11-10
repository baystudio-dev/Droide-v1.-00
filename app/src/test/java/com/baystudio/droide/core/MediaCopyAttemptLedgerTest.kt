package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class MediaCopyAttemptLedgerTest {
    @Test fun terminalFailureBlocksSameSourceAcrossDestinationsWithoutEviction() {
        val ledger = MediaCopyAttemptLedger(2)
        assertNull(ledger.begin("owner:one", "assets/one.zip"))
        ledger.record("owner:one", "assets/one.zip", "ERROR: NO_SPACE retryable=false")
        assertNotNull(ledger.begin("owner:one", "different.zip"))
        assertNull(ledger.begin("owner:two", "other.zip"))
        assertTrue(ledger.begin("owner:three", "third.zip")!!.contains("RUN_LIMIT"))
        assertTrue(ledger.begin("owner:one", "changed-again.zip")!!.contains("NO_SPACE"))
    }

    @Test fun successfulSameDestinationIsIdempotentAndAnotherDestinationMayProceed() {
        val ledger = MediaCopyAttemptLedger()
        assertNull(ledger.begin("source", "first.zip"))
        ledger.record("source", "first.zip", successReceipt("first.zip"), completed = true)
        val replay = requireNotNull(ledger.begin("source", "first.zip"))
        assertTrue(replay.startsWith("MEDIA_SAVE_RECEIPT_REPLAY "))
        assertTrue(Json.parseToJsonElement(replay.substringAfter(' ')).jsonObject.getValue("no_new_copy").jsonPrimitive.boolean)
        assertNull(ledger.begin("source", "second.zip"))
        assertTrue(ledger.begin("source", "third.zip")!!.contains("ALREADY_ATTEMPTED"))
    }

    @Test fun longSuccessIsReserializedCompletelyAndKeepsTheFullDestinationIdentity() {
        val ledger = MediaCopyAttemptLedger()
        val destination = "a".repeat(240) + "/" + "\"".repeat(250)
        val original = successReceipt(destination, "long diagnostic ".repeat(500))
        assertTrue(original.length > 4096)
        assertNull(ledger.begin("source", destination))
        ledger.record("source", destination, original, completed = true)
        val replay = requireNotNull(ledger.begin("source", destination))
        val receipt = Json.parseToJsonElement(replay.substringAfter(' ')).jsonObject
        assertEquals(destination, receipt.getValue("path").jsonPrimitive.content)
        assertEquals(42L, receipt.getValue("committed_bytes").jsonPrimitive.long)
        assertEquals("completed", receipt.getValue("synchronization").jsonPrimitive.content)
        for (key in listOf("historical_receipt", "replayed", "no_new_copy", "local_commit_completed", "do_not_recopy"))
            assertTrue(key, receipt.getValue(key).jsonPrimitive.boolean)
        assertFalse(receipt.getValue("current_file_reverified").jsonPrimitive.boolean)
        assertFalse(receipt.getValue("retryable").jsonPrimitive.boolean)
        assertTrue(receipt.getValue("detail_truncated").jsonPrimitive.boolean)
        assertEquals(500, receipt.getValue("detail").jsonPrimitive.content.length)
        assertNull(ledger.begin("source", destination + "x"))
    }

    @Test fun malformedSuccessFailsClosedInsteadOfReturningTruncatedJson() {
        val ledger = MediaCopyAttemptLedger()
        assertNull(ledger.begin("source", "file.zip"))
        ledger.record("source", "file.zip", "MEDIA_SAVED_TO_PROJECT {\"path\":", completed = true)
        val blocked = requireNotNull(ledger.begin("source", "file.zip"))
        assertTrue(blocked.startsWith("ERROR: MEDIA_COPY_ALREADY_ATTEMPTED "))
        val receipt = Json.parseToJsonElement(blocked.substringAfter("ERROR: MEDIA_COPY_ALREADY_ATTEMPTED ")).jsonObject
        assertTrue(receipt.getValue("no_new_copy").jsonPrimitive.boolean)
    }

    private fun successReceipt(destination: String, detail: String = "") = "MEDIA_SAVED_TO_PROJECT " + buildJsonObject {
        put("path", destination)
        put("bytes", 42)
        put("local_commit_completed", true)
        put("synchronization", "completed")
        put("detail", detail)
    }

    @Test fun explicitNewRunGetsANewBoundedDecisionBudget() {
        val old = MediaCopyAttemptLedger()
        assertNull(old.begin("source", "path"))
        assertNotNull(old.begin("source", "path"))
        assertNull(MediaCopyAttemptLedger().begin("source", "path"))
    }
}
