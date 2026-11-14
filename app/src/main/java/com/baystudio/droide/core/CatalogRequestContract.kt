package com.baystudio.droide.core

import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import kotlinx.serialization.json.*

/** Dormant value/codec contract. No persistence, admission, acknowledgement, retirement or IO.
 * Shape validation is not provenance, publication proof, or custody release authority. */
internal object CatalogRequestContract {
    private const val FORMAT = 1
    const val MAX_RECORDS = 128
    const val MAX_DESCRIPTOR_BYTES = 4096
    private val hash = Regex("[0-9a-f]{64}")

    data class Authority(val incarnation: String, val enrollmentDigest: String) {
        init { uuid(incarnation); require(hash.matches(enrollmentDigest)) }
    }
    data class Token(val authority: Authority, val sequence: Long) {
        init { require(sequence > 0) }
    }
    // sequence belongs to the original admission. Never substitute current nextSequence on retry.
    data class Descriptor(val token: Token, val parentKey: String,
                          val before: CanonicalFileSnapshot.Version,
                          val proposal: CanonicalFileOperationJournal.Content) {
        init { identity(parentKey); version(before); content(proposal.size, proposal.sha256, proposal.mode) }
    }
    data class ResultIdentity(val operationId: String, val published: CanonicalFileSnapshot.Version) {
        init { uuid(operationId); version(published) }
    }
    enum class Delivery { PENDING, COMMITTED_UNACKNOWLEDGED, ACKNOWLEDGED }
    data class Record(val descriptor: Descriptor, val operationId: String, val delivery: Delivery,
                      val result: ResultIdentity? = null) {
        init {
            uuid(operationId)
            require((delivery == Delivery.PENDING) == (result == null))
            result?.let {
                require(it.operationId == operationId)
                require(it.published.size == descriptor.proposal.size &&
                    it.published.sha256 == descriptor.proposal.sha256 && it.published.mode == descriptor.proposal.mode)
            }
        }
    }
    sealed interface Lookup {
        data class Retained(val record: Record) : Lookup
        data object RetiredOutcomeUnavailable : Lookup
        data object NeverAdmitted : Lookup
        data object AuthorityMismatch : Lookup
    }
    enum class Acknowledgement {
        APPLICABLE, ALREADY_APPLIED_WITH_EXACT_PROOF, PENDING, RESULT_MISMATCH,
        RETIRED_OUTCOME_UNAVAILABLE, NEVER_ADMITTED, AUTHORITY_MISMATCH,
    }

    /** A bounded candidate persisted-state shape, NOT a second runtime ledger.
     * Missing issued rows above the floor are corruption. Legacy UUIDs cannot enter this window.
     * No method advances the floor, mints tokens or modifies acknowledgement state. */
    class Window(val authority: Authority, val nextSequence: Long, val retiredThrough: Long,
                 records: List<Record>) {
        val records: List<Record> = Collections.unmodifiableList(records.toList())
        init {
            require(nextSequence > 0 && retiredThrough >= 0 && retiredThrough < nextSequence)
            val live = nextSequence - retiredThrough - 1 // nonnegative and cannot overflow
            require(live <= MAX_RECORDS && this.records.size.toLong() == live)
            this.records.forEachIndexed { index, record ->
                require(record.descriptor.token == Token(authority, retiredThrough + index + 1))
            }
            require(this.records.map { it.operationId }.distinct().size == this.records.size) { "An operation cannot belong to multiple requests" }
            val outstanding = this.records.filter { it.delivery != Delivery.ACKNOWLEDGED }
            require(outstanding.size <= 1)
            require(outstanding.isEmpty() || outstanding.single() === this.records.last())
        }
        val outstanding: Record? get() = records.lastOrNull()?.takeIf { it.delivery != Delivery.ACKNOWLEDGED }
        fun lookup(token: Token): Lookup = when {
            token.authority != authority -> Lookup.AuthorityMismatch
            token.sequence <= retiredThrough -> Lookup.RetiredOutcomeUnavailable
            token.sequence >= nextSequence -> Lookup.NeverAdmitted
            else -> Lookup.Retained(records[(token.sequence - retiredThrough - 1).toInt()])
        }
        fun acknowledgement(token: Token, result: ResultIdentity): Acknowledgement = when (val found = lookup(token)) {
            Lookup.AuthorityMismatch -> Acknowledgement.AUTHORITY_MISMATCH
            Lookup.NeverAdmitted -> Acknowledgement.NEVER_ADMITTED
            Lookup.RetiredOutcomeUnavailable -> Acknowledgement.RETIRED_OUTCOME_UNAVAILABLE
            is Lookup.Retained -> when {
                found.record.delivery == Delivery.PENDING -> Acknowledgement.PENDING
                found.record.result != result -> Acknowledgement.RESULT_MISMATCH
                found.record.delivery == Delivery.ACKNOWLEDGED -> Acknowledgement.ALREADY_APPLIED_WITH_EXACT_PROOF
                else -> Acknowledgement.APPLICABLE
            }
        }
        fun requireNewCommandShape() {
            check(outstanding == null) { "Discover and reconcile the outstanding command first" }
            check(records.size < MAX_RECORDS) { "Result window is full" }
            check(nextSequence < Long.MAX_VALUE) { "Sequence exhausted; no rollover is permitted" }
        }
    }

    /** Worst-case logical metadata reservation, not physical free space or custody credit.
     * Caller must prove each bound against actual serialization before using it for admission.
     * This class only checks/sums supplied bounds; it does not derive them from descriptors.
     * All simultaneous envelopes are conservatively included; no compression credit is assumed. */
    data class MetadataReservation(val descriptorBytes: Long, val deliverySlotBytes: Long,
                                   val pendingBytes: Long, val completedBytes: Long, val acknowledgementBytes: Long) {
        val bytes: Long
        init {
            val values = listOf(descriptorBytes, deliverySlotBytes, pendingBytes, completedBytes, acknowledgementBytes)
            require(values.all { it > 0 })
            require(descriptorBytes <= MAX_DESCRIPTOR_BYTES)
            bytes = values.fold(0L, Math::addExact)
        }
        fun requireFits(usedBytes: Long, maxBytes: Long) {
            require(usedBytes >= 0 && maxBytes > 0 && usedBytes <= maxBytes)
            check(bytes <= maxBytes - usedBytes) { "Metadata envelope capacity is exhausted" }
        }
    }

    fun encodeToken(token: Token): String = tokenJson(token).toString()
    fun decodeToken(encoded: String): Token {
        require(encoded.toByteArray(Charsets.UTF_8).size <= 256)
        val token = token(fields(Json.parseToJsonElement(encoded), 4))
        require(encodeToken(token) == encoded) { "Expected canonical request token" }
        return token
    }
    fun encodeDescriptor(descriptor: Descriptor): String = JsonArray(listOf(
        JsonPrimitive(FORMAT), tokenJson(descriptor.token), JsonPrimitive("REPLACE"), JsonPrimitive("projects.json"),
        JsonPrimitive(descriptor.parentKey), versionJson(descriptor.before),
        JsonArray(listOf(JsonPrimitive(descriptor.proposal.size), JsonPrimitive(descriptor.proposal.sha256), JsonPrimitive(descriptor.proposal.mode))),
    )).toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_DESCRIPTOR_BYTES) }

    fun decodeDescriptor(encoded: String): Descriptor {
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_DESCRIPTOR_BYTES)
        val parts = fields(Json.parseToJsonElement(encoded), 7)
        require(number(parts[0]) == FORMAT.toLong() && string(parts[2]) == "REPLACE" && string(parts[3]) == "projects.json")
        val before = fields(parts[5], 4); val proposal = fields(parts[6], 3)
        val decoded = Descriptor(token(fields(parts[1], 4)), string(parts[4]),
            CanonicalFileSnapshot.Version(string(before[0]), number(before[1]), string(before[2]), mode(before[3])),
            CanonicalFileOperationJournal.Content(number(proposal[0]), string(proposal[1]), mode(proposal[2])))
        require(encodeDescriptor(decoded) == encoded) { "Expected canonical request descriptor" }
        return decoded
    }
    fun fingerprint(descriptor: Descriptor): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeDescriptor(descriptor).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun tokenJson(token: Token) = JsonArray(listOf(JsonPrimitive(FORMAT),
        JsonPrimitive(token.authority.incarnation), JsonPrimitive(token.authority.enrollmentDigest), JsonPrimitive(token.sequence)))
    private fun token(parts: List<JsonElement>): Token {
        require(number(parts[0]) == FORMAT.toLong())
        return Token(Authority(string(parts[1]), string(parts[2])), number(parts[3]))
    }
    private fun fields(value: JsonElement, size: Int): List<JsonElement> =
        (value as? JsonArray)?.also { require(it.size == size) } ?: error("Expected bounded array")
    private fun string(value: JsonElement): String = (value as? JsonPrimitive)?.also { require(it.isString) }?.content
        ?: error("Expected string")
    private fun number(value: JsonElement): Long = (value as? JsonPrimitive)?.also { require(!it.isString) }?.longOrNull
        ?: error("Expected integer")
    private fun mode(value: JsonElement): Int = number(value).also { require(it in listOf(384L, 420L, 493L)) }.toInt()
    private fun versionJson(value: CanonicalFileSnapshot.Version) = JsonArray(listOf(JsonPrimitive(value.identity),
        JsonPrimitive(value.size), JsonPrimitive(value.sha256), JsonPrimitive(value.mode)))
    private fun uuid(value: String) { require(value.length == 36 && UUID.fromString(value).toString() == value) }
    private fun identity(value: String) {
        require(value.isNotEmpty() && value.length <= 256 && value.none { it.code < 32 })
        require(Charsets.UTF_8.newEncoder().canEncode(value)) { "Identity must contain valid Unicode scalars" }
    }
    private fun version(value: CanonicalFileSnapshot.Version) { identity(value.identity); content(value.size, value.sha256, value.mode) }
    private fun content(size: Long, sha: String, mode: Int) { require(size >= 0 && hash.matches(sha) && mode in listOf(384, 420, 493)) }
}
