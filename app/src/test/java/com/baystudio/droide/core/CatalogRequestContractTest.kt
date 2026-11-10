package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class CatalogRequestContractTest {
    private val authority = CatalogRequestContract.Authority("1066e46f-30db-4415-8197-66d972246771", "a".repeat(64))
    private fun operationId(sequence: Long = 1) = "2066e46f-30db-4415-8197-" + sequence.toString(16).padStart(12, '0')
    private val id = operationId()
    private fun token(sequence: Long = 1) = CatalogRequestContract.Token(authority, sequence)
    private fun descriptor(sequence: Long = 1) = CatalogRequestContract.Descriptor(token(sequence), "parent-inode",
        CanonicalFileSnapshot.Version("before-inode", 10, "b".repeat(64), 384),
        CanonicalFileOperationJournal.Content(12, "c".repeat(64), 384))
    private fun result(sequence: Long = 1) = CatalogRequestContract.ResultIdentity(operationId(sequence),
        CanonicalFileSnapshot.Version("published-inode", 12, "c".repeat(64), 384))
    private fun record(sequence: Long = 1, delivery: CatalogRequestContract.Delivery = CatalogRequestContract.Delivery.ACKNOWLEDGED) =
        CatalogRequestContract.Record(descriptor(sequence), operationId(sequence), delivery,
            if (delivery == CatalogRequestContract.Delivery.PENDING) null else result(sequence))
    private fun refused(action: () -> Unit) { assertTrue(runCatching(action).isFailure) }

    @Test fun tokenAndDescriptorRoundTripCanonically() {
        assertEquals(token(), CatalogRequestContract.decodeToken(CatalogRequestContract.encodeToken(token())))
        assertEquals(descriptor(), CatalogRequestContract.decodeDescriptor(CatalogRequestContract.encodeDescriptor(descriptor())))
    }
    @Test fun tokenRejectsUnknownFormatAndExtraFields() {
        val text = CatalogRequestContract.encodeToken(token())
        refused { CatalogRequestContract.decodeToken(text.replaceFirst("[1,", "[2,")) }
        refused { CatalogRequestContract.decodeToken(text.dropLast(1) + ",0]") }
    }
    @Test fun tokenRejectsZeroNegativeAndNoncanonicalIntegers() {
        refused { token(0) }; refused { token(-1) }
        val text = CatalogRequestContract.encodeToken(token())
        for (suffix in listOf("1.0]", "1e0]", "\"1\"]", "9223372036854775808]"))
            refused { CatalogRequestContract.decodeToken(text.dropLast(2) + suffix) }
    }
    @Test fun tokenRejectsMalformedAuthority() {
        refused { CatalogRequestContract.Authority("not-a-uuid", "a".repeat(64)) }
        refused { CatalogRequestContract.Authority(authority.incarnation, "A".repeat(64)) }
    }
    @Test fun codecRejectsWhitespaceAndOversizedInput() {
        refused { CatalogRequestContract.decodeToken(" " + CatalogRequestContract.encodeToken(token())) }
        refused { CatalogRequestContract.decodeDescriptor(" ".repeat(4097)) }
    }
    @Test fun descriptorRejectsForeignKindAndPath() {
        val text = CatalogRequestContract.encodeDescriptor(descriptor())
        refused { CatalogRequestContract.decodeDescriptor(text.replace("REPLACE", "CREATE")) }
        refused { CatalogRequestContract.decodeDescriptor(text.replace("projects.json", "../projects.json")) }
    }
    @Test fun descriptorRejectsInvalidModesSizesAndIdentities() {
        refused { descriptor().copy(before = descriptor().before.copy(mode = 511)) }
        refused { descriptor().copy(proposal = descriptor().proposal.copy(size = -1)) }
        refused { descriptor().copy(parentKey = "") }
        refused { descriptor().copy(parentKey = "x\u0000") }
    }
    @Test fun fingerprintBindsOwnerGenerationParentsBeforeAndProposal() {
        val original = descriptor()
        val variants = listOf(original.copy(token = token(2)), original.copy(parentKey = "other-parent"),
            original.copy(token = token().copy(authority = authority.copy(enrollmentDigest = "d".repeat(64)))),
            original.copy(before = original.before.copy(identity = "other-inode")),
            original.copy(proposal = original.proposal.copy(sha256 = "d".repeat(64))))
        for (variant in variants) assertNotEquals(CatalogRequestContract.fingerprint(original), CatalogRequestContract.fingerprint(variant))
    }
    @Test fun laterCounterDoesNotRewriteFrozenDescriptor() {
        val original = record()
        val window = CatalogRequestContract.Window(authority, 3, 0, listOf(original, record(2)))
        assertEquals(original, (window.lookup(token()) as CatalogRequestContract.Lookup.Retained).record)
        assertEquals(CatalogRequestContract.fingerprint(descriptor()), CatalogRequestContract.fingerprint(original.descriptor))
    }
    @Test fun windowDistinguishesRetiredFutureAndForeignAuthority() {
        val window = CatalogRequestContract.Window(authority, 3, 1, listOf(record(2)))
        assertEquals(CatalogRequestContract.Lookup.RetiredOutcomeUnavailable, window.lookup(token()))
        assertEquals(CatalogRequestContract.Lookup.NeverAdmitted, window.lookup(token(3)))
        assertEquals(CatalogRequestContract.Lookup.AuthorityMismatch,
            window.lookup(token().copy(authority = authority.copy(enrollmentDigest = "e".repeat(64)))))
    }
    @Test fun staleAckNeverClaimsExactSuccess() {
        val window = CatalogRequestContract.Window(authority, 2, 1, emptyList())
        assertEquals(CatalogRequestContract.Acknowledgement.RETIRED_OUTCOME_UNAVAILABLE, window.acknowledgement(token(), result()))
    }
    @Test fun retainedAcknowledgementRequiresExactResultProof() {
        val window = CatalogRequestContract.Window(authority, 2, 0, listOf(record()))
        assertEquals(CatalogRequestContract.Acknowledgement.ALREADY_APPLIED_WITH_EXACT_PROOF, window.acknowledgement(token(), result()))
        assertEquals(CatalogRequestContract.Acknowledgement.RESULT_MISMATCH,
            window.acknowledgement(token(), result().copy(published = result().published.copy(identity = "different"))))
    }
    @Test fun completedDeliveryIsApplicableButNeverMutatedByInspection() {
        val r = record(delivery = CatalogRequestContract.Delivery.COMMITTED_UNACKNOWLEDGED)
        val window = CatalogRequestContract.Window(authority, 2, 0, listOf(r))
        repeat(2) { assertEquals(CatalogRequestContract.Acknowledgement.APPLICABLE, window.acknowledgement(token(), result())) }
        assertEquals(r, window.outstanding)
        refused { window.requireNewCommandShape() }
    }
    @Test fun pendingDeliveryCannotAcknowledgeOrAdmitNewCommand() {
        val window = CatalogRequestContract.Window(authority, 2, 0, listOf(record(delivery = CatalogRequestContract.Delivery.PENDING)))
        assertEquals(CatalogRequestContract.Acknowledgement.PENDING, window.acknowledgement(token(), result()))
        refused { window.requireNewCommandShape() }
    }
    @Test fun missingIssuedRowAndDuplicateSequenceRefuse() {
        refused { CatalogRequestContract.Window(authority, 3, 0, listOf(record())) }
        refused { CatalogRequestContract.Window(authority, 3, 0, listOf(record(), record())) }
    }
    @Test fun outstandingMustBeSingleLatestRecord() {
        val pending = record(delivery = CatalogRequestContract.Delivery.PENDING)
        refused { CatalogRequestContract.Window(authority, 3, 0, listOf(pending, record(2))) }
        refused { CatalogRequestContract.Window(authority, 3, 0, listOf(pending,
            record(2, CatalogRequestContract.Delivery.COMMITTED_UNACKNOWLEDGED))) }
    }
    @Test fun windowSnapshotsCallerCollection() {
        val mutable = mutableListOf(record())
        val window = CatalogRequestContract.Window(authority, 2, 0, mutable)
        mutable.clear()
        assertEquals(1, window.records.size)
        refused { (window.records as MutableList).clear() }
    }
    @Test fun resultCannotNameDifferentOperationOrProposal() {
        refused { record().copy(result = result().copy(operationId = authority.incarnation)) }
        refused { record().copy(result = result().copy(published = result().published.copy(size = 13))) }
        refused { record().copy(delivery = CatalogRequestContract.Delivery.PENDING) }
    }
    @Test fun windowCapacityAndSequenceOverflowRefuseWithoutRollover() {
        val full = CatalogRequestContract.Window(authority, 129, 0, (1L..128L).map { record(it) })
        refused { full.requireNewCommandShape() }
        val exhausted = CatalogRequestContract.Window(authority, Long.MAX_VALUE, Long.MAX_VALUE - 1, emptyList())
        refused { exhausted.requireNewCommandShape() }
        refused { CatalogRequestContract.Window(authority, Long.MAX_VALUE, 0, emptyList()) }
    }
    @Test fun invalidFloorAndForeignRecordRefuse() {
        refused { CatalogRequestContract.Window(authority, 1, 1, emptyList()) }
        refused { CatalogRequestContract.Window(authority, 1, -1, emptyList()) }
        refused { CatalogRequestContract.Window(authority.copy(enrollmentDigest = "d".repeat(64)), 2, 0, listOf(record())) }
    }
    @Test fun metadataIncludesCompletionAndAcknowledgementEnvelope() {
        val reserve = CatalogRequestContract.MetadataReservation(10, 20, 30, 40, 50)
        assertEquals(150L, reserve.bytes)
        reserve.requireFits(50, 200)
        refused { reserve.requireFits(51, 200) }
    }
    @Test fun metadataRejectsOverflowNegativeAndInvalidUsage() {
        refused { CatalogRequestContract.MetadataReservation(1, Long.MAX_VALUE, 1, 1, 1) }
        refused { CatalogRequestContract.MetadataReservation(1, 0, 1, 1, 1) }
        refused { CatalogRequestContract.MetadataReservation(4097, 1, 1, 1, 1) }
        val reserve = CatalogRequestContract.MetadataReservation(1, 1, 1, 1, 1)
        refused { reserve.requireFits(-1, 100) }; refused { reserve.requireFits(101, 100) }
    }
    @Test fun emptyWindowShapeAllowsNewCommandWithoutIssuingAnything() {
        val window = CatalogRequestContract.Window(authority, 1, 0, emptyList())
        window.requireNewCommandShape()
        assertEquals(CatalogRequestContract.Lookup.NeverAdmitted, window.lookup(token()))
        assertNull(window.outstanding)
    }

    @Test fun distinctSequencesCannotAliasOneOperation() {
        val alias = record(2).copy(operationId = id, result = result())
        refused { CatalogRequestContract.Window(authority, 3, 0, listOf(record(), alias)) }
    }
    @Test fun malformedTokenShapesAreRejected() {
        for (input in listOf("{}", "null", "[]", "true", "[null]")) refused { CatalogRequestContract.decodeToken(input) }
        val encoded = CatalogRequestContract.encodeToken(token())
        refused { CatalogRequestContract.decodeToken(encoded.replaceFirst("[1,", "[true,")) }
        refused { CatalogRequestContract.decodeToken(encoded.replaceFirst("[1,", "[\"1\",")) }
    }
    @Test fun malformedDescriptorShapesAndNestedFormatsAreRejected() {
        val encoded = CatalogRequestContract.encodeDescriptor(descriptor())
        refused { CatalogRequestContract.decodeDescriptor(encoded.dropLast(1) + ",0]") }
        refused { CatalogRequestContract.decodeDescriptor(encoded.replaceFirst("[1,[1,", "[1,[2,")) }
        refused { CatalogRequestContract.decodeDescriptor(encoded.replace("\"parent-inode\"", "null")) }
        refused { CatalogRequestContract.decodeDescriptor(encoded.replace("[12,", "[\"12\",")) }
    }
    @Test fun malformedUnicodeCannotCollapseFingerprintInputs() {
        for (identity in listOf("\uD800", "\uD801", "\uDC00", "x\uD800y")) {
            refused { descriptor().copy(parentKey = identity) }
            refused { descriptor().copy(before = descriptor().before.copy(identity = identity)) }
        }
        val valid = descriptor().copy(parentKey = "parent-\uD83D\uDE00")
        assertEquals(valid, CatalogRequestContract.decodeDescriptor(CatalogRequestContract.encodeDescriptor(valid)))
    }
}
