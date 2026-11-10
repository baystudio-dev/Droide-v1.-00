package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.BitSet
import java.util.UUID

/** Data-only speculative transformation, never an SQLite connection or live authority. Format
 * checks and sparse journal checksums cannot prove pair coherence or writer exclusion. */
internal object MirrorSqliteForensicCandidate {
    enum class JournalShape { SINGLE_SEGMENT, SINGLE_SEGMENT_EMPTY_HEADER }
    data class Result(val directory: File, val sha256: String, val bytes: Long, val restoredPages: Int,
                      val pageSize: Int, val journalShape: JournalShape)
    private const val MAX_BYTES = 64L * 1024 * 1024
    private const val DB = "state.sqlite"
    private const val JOURNAL = "state.sqlite-journal"
    private val magic = byteArrayOf(0xd9.toByte(), 0xd5.toByte(), 0x05, 0xf9.toByte(), 0x20, 0xa1.toByte(), 0x63, 0xd7.toByte())
    private val dbMagic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private data class Snapshot(val observation: AnchoredWorkspaceFiles.Observation, val sha: String,
                                val head: ByteArray, val tail: ByteArray) {
        val size get() = observation.attributes.size()
    }
    private data class Layout(val records: Int, val nonce: Long, val pages: Long, val sector: Int, val page: Int,
                              val bytes: Long, val padding: Int, val emptyTail: Boolean)

    fun build(authorityDirectory: File, binding: String, review: MirrorSqliteRollbackRecovery.Review,
              requireCurrent: () -> Unit, checkpoint: (String) -> Unit = {}): Result =
        buildValidated(authorityDirectory, binding, review, requireCurrent, checkpoint, {})

    private fun buildValidated(authorityDirectory: File, binding: String, review: MirrorSqliteRollbackRecovery.Review,
                               requireCurrent: () -> Unit, checkpoint: (String) -> Unit, beforeReturn: () -> Unit): Result {
        requireCurrent()
        require(review.evidenceDirectory.parentFile?.canonicalFile == authorityDirectory.canonicalFile &&
            review.evidenceDirectory.name.matches(Regex("sqlite-review-[0-9a-f-]{36}"))) {
            "Forensic evidence belongs to a different authority"
        }
        AnchoredWorkspaceFiles(authorityDirectory).use { authority ->
            authority.openDirectory(review.evidenceDirectory.name).use { evidence ->
                check(evidence.directoryKey() == review.evidenceIdentity) { "Forensic evidence directory was replaced" }
                requireReviewObservations(evidence, review)
                val expectedReceipt = "DROIDE-SQLITE-EVIDENCE-1\n$binding\n${authority.directoryKey()}\n" +
                    "${review.databaseSha256}\n${review.journalSha256}\n" +
                    "OBSERVED_STABLE_ONLY; WRITER_EXCLUSION_UNPROVEN; REPLAY_AND_ACTIVATION_DENIED\n"
                val receiptVersion = requireBytes(evidence, "receipt", expectedReceipt.toByteArray(Charsets.UTF_8), requireCurrent)
                check(evidence.kind("$DB-wal") == AnchoredWorkspaceFiles.Kind.ABSENT &&
                    evidence.kind("$DB-shm") == AnchoredWorkspaceFiles.Kind.ABSENT) { "WAL evidence is unsupported" }
                val database = inspect(evidence, DB, requireCurrent)
                val journal = inspect(evidence, JOURNAL, requireCurrent)
                check(database.sha == review.databaseSha256 && journal.sha == review.journalSha256) {
                    "Captured evidence hashes changed; no candidate was accepted"
                }
                val layout = layout(database, journal)
                check(MirrorSqliteRollbackRecovery.attemptNames(evidence, "candidate-", 4).size < 4) {
                    "Forensic candidate capacity reached; all attempts were preserved"
                }
                val candidateName = "candidate-${UUID.randomUUID()}"
                check(evidence.createDirectory(candidateName).durable) { "Candidate directory durability is unconfirmed" }
                val candidateDirectory = File(review.evidenceDirectory, candidateName)
                evidence.openDirectory(candidateName).use { output ->
                    val outputIdentity = output.directoryKey()
                    val written = output.createNew("candidate.sqlite").use { target ->
                        copyPrefix(evidence, database, target, layout.bytes, requireCurrent)
                        checkpoint("forensic-after-database-copy")
                        val renderedHash = target.openWriteChannel().use { channel ->
                            visitJournal(evidence, journal, layout, requireCurrent) { pageNumber, page ->
                                channel.position(Math.multiplyExact(pageNumber - 1, layout.page.toLong()))
                                val bytes = ByteBuffer.wrap(page)
                                while (bytes.hasRemaining()) { requireCurrent(); check(channel.write(bytes) > 0) }
                                checkpoint("forensic-after-page")
                            }
                            check(channel.size() == layout.bytes)
                            channel.position(0)
                            val pageOne = ByteBuffer.allocate(100)
                            while (pageOne.hasRemaining()) { requireCurrent(); check(channel.read(pageOne) > 0) }
                            requireDatabaseHeader(pageOne.array(), layout.page)
                            check(u32(pageOne.array(), 28) == layout.pages) { "Candidate page-count header is inconsistent" }
                            channel.position(0)
                            val digest = MessageDigest.getInstance("SHA-256")
                            val buffer = ByteBuffer.allocate(64 * 1024)
                            var total = 0L
                            while (true) {
                                requireCurrent(); buffer.clear()
                                val count = channel.read(buffer)
                                if (count < 0) break
                                check(count > 0 && total + count <= layout.bytes)
                                digest.update(buffer.array(), 0, count); total += count
                            }
                            check(total == layout.bytes)
                            hex(digest.digest())
                        }
                        checkpoint("forensic-before-candidate-force")
                        requireCurrent(); target.force(384)
                        target.observe().attributes.fileKey() to renderedHash
                    }
                    val candidate = inspect(output, "candidate.sqlite", requireCurrent)
                    check(candidate.size == layout.bytes && candidate.sha == written.second &&
                        candidate.observation.attributes.fileKey() == written.first) { "Rendered candidate changed before publication" }
                    requireOriginalEvidence(evidence, database, journal, review, requireCurrent)
                    requireBytes(evidence, "receipt", expectedReceipt.toByteArray(Charsets.UTF_8), requireCurrent)
                    val shape = if (layout.emptyTail) JournalShape.SINGLE_SEGMENT_EMPTY_HEADER else JournalShape.SINGLE_SEGMENT
                    val report = report(binding, review, database, journal, candidate, layout, shape)
                    output.createNew(".report-pending").use { it.write(report); it.force(384) }
                    requireBytes(output, ".report-pending", report, requireCurrent)
                    checkpoint("forensic-before-report-publication")
                    requireCurrent()
                    check(output.directoryKey() == outputIdentity)
                    requireCandidate(output, candidate, requireCurrent)
                    check(output.moveNoReplaceDurable(".report-pending", output, "report").durable) {
                        "Forensic report publication durability is unconfirmed; attempt preserved"
                    }
                    val publishedReport = requireBytes(output, "report", report, requireCurrent)
                    requireOriginalEvidence(evidence, database, journal, review, requireCurrent)
                    checkpoint("forensic-after-report-publication")
                    beforeReturn()
                    requireCurrent()
                    check(output.directoryKey() == outputIdentity)
                    requireCandidate(output, candidate, requireCurrent)
                    requireOriginalEvidence(evidence, database, journal, review, requireCurrent)
                    same(receiptVersion, requireBytes(evidence, "receipt", expectedReceipt.toByteArray(Charsets.UTF_8), requireCurrent))
                    same(publishedReport, requireBytes(output, "report", report, requireCurrent))
                    return Result(candidateDirectory, candidate.sha, layout.bytes, layout.records, layout.page, shape)
                }
            }
        }
    }

    fun buildCurrent(authorityDirectory: File, binding: String, reviews: List<MirrorSqliteRollbackRecovery.Review>,
                     requireCurrent: () -> Unit, checkpoint: (String) -> Unit, beforeReturn: () -> Unit): Result {
        for (review in reviews) reopen(authorityDirectory, binding, review, requireCurrent, checkpoint, beforeReturn)?.let { return it }
        for (review in reviews) {
            AnchoredWorkspaceFiles(authorityDirectory).use { authority ->
                authority.openDirectory(review.evidenceDirectory.name).use { evidence ->
                    check(evidence.directoryKey() == review.evidenceIdentity) { "Forensic evidence directory was replaced" }
                    if (MirrorSqliteRollbackRecovery.attemptNames(evidence, "candidate-", 4).size < 4)
                        return buildValidated(authorityDirectory, binding, review, requireCurrent, checkpoint, beforeReturn)
                }
            }
        }
        // A fresh capture is independent evidence, not repair of any invalid/exhausted attempt.
        val review = MirrorSqliteRollbackRecovery.capture(authorityDirectory, binding, requireCurrent, checkpoint)
        return buildValidated(authorityDirectory, binding, review, requireCurrent, checkpoint, beforeReturn)
    }

    private fun reopen(authorityDirectory: File, binding: String, review: MirrorSqliteRollbackRecovery.Review,
                       requireCurrent: () -> Unit, checkpoint: (String) -> Unit, beforeReturn: () -> Unit): Result? {
        requireCurrent()
        AnchoredWorkspaceFiles(authorityDirectory).use { authority ->
            authority.openDirectory(review.evidenceDirectory.name).use { evidence ->
                check(evidence.directoryKey() == review.evidenceIdentity) { "Forensic evidence directory was replaced" }
                requireReviewObservations(evidence, review)
                val expectedReceipt = MirrorSqliteRollbackRecovery.receipt(binding, authority.directoryKey(),
                    review.databaseSha256, review.journalSha256)
                val receipt = MirrorSqliteRollbackRecovery.privateBytes(evidence, "receipt", expectedReceipt, requireCurrent)
                val database = inspect(evidence, DB, requireCurrent)
                val journal = inspect(evidence, JOURNAL, requireCurrent)
                check(database.sha == review.databaseSha256 && journal.sha == review.journalSha256) {
                    "Selected evidence changed before reuse"
                }
                val layout = layout(database, journal)
                val shape = if (layout.emptyTail) JournalShape.SINGLE_SEGMENT_EMPTY_HEADER else JournalShape.SINGLE_SEGMENT
                for (name in MirrorSqliteRollbackRecovery.attemptNames(evidence, "candidate-", 4)) {
                    requireCurrent()
                    if (!name.matches(Regex("candidate-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) ||
                        evidence.kind(name) != AnchoredWorkspaceFiles.Kind.DIRECTORY) continue
                    evidence.openDirectory(name).use { output ->
                        val candidate = try {
                            content(output.kind("candidate.sqlite") == AnchoredWorkspaceFiles.Kind.FILE &&
                                output.kind("report") == AnchoredWorkspaceFiles.Kind.FILE) { "Candidate publication is incomplete" }
                            content(output.kind("candidate.sqlite-wal") == AnchoredWorkspaceFiles.Kind.ABSENT &&
                                output.kind("candidate.sqlite-shm") == AnchoredWorkspaceFiles.Kind.ABSENT) { "Candidate sidecars are unsupported" }
                            val found = inspect(output, "candidate.sqlite", requireCurrent)
                            content(found.size == layout.bytes) { "Candidate length differs from the transformation" }
                            requireDatabaseHeader(found.head, layout.page)
                            content(u32(found.head, 28) == layout.pages) { "Candidate page count differs" }
                            val report = report(binding, review, database, journal, found, layout, shape)
                            val reportVersion = MirrorSqliteRollbackRecovery.privateBytes(output, "report", report, requireCurrent)
                            verifyTransformation(evidence, output, database, journal, found, layout, requireCurrent)
                            Triple(found, report, reportVersion)
                        } catch (_: MirrorSqliteRollbackRecovery.Ineligible) { null }
                        if (candidate != null) {
                            checkpoint("forensic-reuse-after-validation")
                            requireCurrent()
                            val (found, report, reportVersion) = candidate
                            requireCandidate(output, found, requireCurrent)
                            requireEvidenceBytes(evidence, database, journal, review, requireCurrent)
                            same(receipt, MirrorSqliteRollbackRecovery.privateBytes(evidence, "receipt", expectedReceipt, requireCurrent))
                            same(reportVersion, MirrorSqliteRollbackRecovery.privateBytes(output, "report", report, requireCurrent))
                            checkpoint("forensic-reuse-before-force")
                            requireCurrent()
                            for ((file, version) in listOf(DB to database.observation, JOURNAL to journal.observation, "receipt" to receipt))
                                forceObserved(evidence, file, version)
                            for ((file, version) in listOf("candidate.sqlite" to found.observation, "report" to reportVersion))
                                forceObserved(output, file, version)
                            output.forceDirectory(); evidence.forceDirectory(); authority.forceDirectory()
                            checkpoint("forensic-reuse-after-force")
                            beforeReturn()
                            requireCurrent()
                            requireCandidate(output, found, requireCurrent)
                            requireEvidenceBytes(evidence, database, journal, review, requireCurrent)
                            same(receipt, MirrorSqliteRollbackRecovery.privateBytes(evidence, "receipt", expectedReceipt, requireCurrent))
                            same(reportVersion, MirrorSqliteRollbackRecovery.privateBytes(output, "report", report, requireCurrent))
                            return Result(File(review.evidenceDirectory, name), found.sha, layout.bytes, layout.records, layout.page, shape)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun forceObserved(owner: AnchoredWorkspaceFiles, name: String, expected: AnchoredWorkspaceFiles.Observation) {
        owner.openRead(name).use { input ->
            same(expected, input.observe())
            MirrorSqliteRollbackRecovery.privateFile(input)
            input.forceContents()
            same(expected, input.observe())
        }
    }

    private fun report(binding: String, review: MirrorSqliteRollbackRecovery.Review, database: Snapshot, journal: Snapshot,
                       candidate: Snapshot, layout: Layout, shape: JournalShape) =
        ("DROIDE-FORENSIC-CANDIDATE-1\n$binding\n${review.evidenceIdentity}\n" +
            "${database.sha}\n${journal.sha}\n${candidate.sha}\n${layout.bytes}\n${layout.records}\n${layout.page}\n${shape.name}\n" +
            "UNVERIFIED_SPECULATIVE_BYTES; COHERENCE_AND_WRITER_EXCLUSION_UNPROVEN; ACTIVATION_AND_CLEANUP_DENIED\n")
            .toByteArray(Charsets.UTF_8)

    private fun verifyTransformation(evidence: AnchoredWorkspaceFiles, output: AnchoredWorkspaceFiles,
                                     database: Snapshot, journal: Snapshot, candidate: Snapshot, layout: Layout,
                                     requireCurrent: () -> Unit) {
        output.openRead("candidate.sqlite").use { target ->
            same(candidate.observation, target.observe())
            try {
                target.openReadChannel().use { channel ->
                    fun compare(page: ByteArray, number: Long) {
                        val actual = ByteBuffer.allocate(page.size)
                        channel.position((number - 1L) * layout.page)
                        while (actual.hasRemaining()) { requireCurrent(); content(channel.read(actual) > 0) { "Candidate page is incomplete" } }
                        content(actual.array().contentEquals(page)) { "Candidate is not the captured journal transformation" }
                    }
                    val seen = visitJournal(evidence, journal, layout, requireCurrent) { number, page -> compare(page, number) }
                    evidence.openRead(DB).use { input ->
                        same(database.observation, input.observe())
                        val digest = MessageDigest.getInstance("SHA-256")
                        repeat((database.size / layout.page).toInt()) { index ->
                            val page = read(input, layout.page, digest, requireCurrent)
                            if (index < layout.pages && !seen[index + 1]) compare(page, index + 1L)
                        }
                        check(input.read() == -1 && hex(digest.digest()) == database.sha) { "Captured database changed during validation" }
                        same(database.observation, input.observe())
                    }
                }
            } finally {
                same(candidate.observation, target.observe())
                output.openRead("candidate.sqlite").use { same(candidate.observation, it.observe()) }
            }
        }
    }

    private fun requireReviewObservations(evidence: AnchoredWorkspaceFiles, review: MirrorSqliteRollbackRecovery.Review) {
        review.observations.forEach { (name, before) -> evidence.openRead(name).use { same(before, it.observe()) } }
    }

    private fun requireEvidenceBytes(owner: AnchoredWorkspaceFiles, database: Snapshot, journal: Snapshot,
                                     review: MirrorSqliteRollbackRecovery.Review, requireCurrent: () -> Unit) {
        requireOriginalEvidence(owner, database, journal, review, requireCurrent)
        for ((name, before) in listOf(DB to database, JOURNAL to journal)) {
            val current = inspect(owner, name, requireCurrent)
            same(before.observation, current.observation)
            check(current.sha == before.sha) { "Captured evidence changed during validation" }
        }
    }

    private inline fun content(value: Boolean, message: () -> String = { "Unsupported forensic content" }) {
        if (!value) throw MirrorSqliteRollbackRecovery.Ineligible(message())
    }

    private fun visitJournal(evidence: AnchoredWorkspaceFiles, journal: Snapshot, layout: Layout,
                             requireCurrent: () -> Unit, visit: (Long, ByteArray) -> Unit): BitSet {
        return evidence.openRead(JOURNAL).use { input ->
            same(journal.observation, input.observe())
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val header = read(input, layout.sector, digest, requireCurrent)
                content(header.take(28).toByteArray().contentEquals(journal.head.copyOf(28)) &&
                    header.drop(28).all { it == 0.toByte() }) { "Journal header/padding changed or is unsupported" }
                val seen = BitSet(layout.pages.toInt() + 1)
                repeat(layout.records) {
                    requireCurrent()
                    val pageNumber = u32(read(input, 4, digest, requireCurrent), 0)
                    content(pageNumber in 1..layout.pages && pageNumber != 0x40000000L / layout.page + 1L &&
                        !seen[pageNumber.toInt()]) { "Invalid, duplicate or lock-byte journal page" }
                    seen.set(pageNumber.toInt())
                    val page = read(input, layout.page, digest, requireCurrent)
                    val checksum = u32(read(input, 4, digest, requireCurrent), 0)
                    var actual = layout.nonce
                    var index = layout.page - 200
                    while (index > 0) { actual = (actual + (page[index].toInt() and 255)) and 0xffffffffL; index -= 200 }
                    content(checksum == actual) { "Journal page checksum mismatch; candidate remains unverified" }
                    visit(pageNumber, page)
                }
                if (layout.emptyTail) {
                    content(read(input, layout.padding, digest, requireCurrent).all { it == 0.toByte() }) {
                        "Nonzero journal alignment padding is unsupported"
                    }
                    val stub = read(input, layout.sector, digest, requireCurrent)
                    content(stub.take(12).all { it == 0.toByte() } && u32(stub, 16) == layout.pages &&
                        u32(stub, 20) == layout.sector.toLong() && u32(stub, 24) == layout.page.toLong() &&
                        stub.drop(28).all { it == 0.toByte() }) {
                        "Only a wholly empty zero-magic trailing header is supported"
                    }
                }
                check(input.read() == -1 && hex(digest.digest()) == journal.sha) { "Journal bytes changed during rendering" }
                same(journal.observation, input.observe())
                seen
            } finally {
                same(journal.observation, input.observe())
                evidence.openRead(JOURNAL).use { same(journal.observation, it.observe()) }
            }
        }
    }

    private fun layout(database: Snapshot, journal: Snapshot): Layout {
        content(journal.head.size >= 28 && journal.head.copyOfRange(0, 8).contentEquals(magic)) { "Unsupported or incomplete journal header" }
        content(!journal.tail.contentEquals(magic)) { "Super-journal/trailer reference is unsupported" }
        val records = u32(journal.head, 8)
        val nonce = u32(journal.head, 12)
        val pages = u32(journal.head, 16)
        val sector = u32(journal.head, 20)
        val page = u32(journal.head, 24)
        fun power(value: Long) = value in 512..65536 && value and (value - 1) == 0L
        content(power(sector) && power(page)) { "Unsupported journal sector/page size" }
        content(pages in 1..(MAX_BYTES / page) && records in 1..pages) { "Unsupported journal page count or candidate size" }
        val bytes = Math.multiplyExact(pages, page)
        content(bytes in 100..MAX_BYTES && bytes <= database.size && database.size % page == 0L) {
            "Captured database cannot supply the bounded candidate prefix"
        }
        val exactLength = Math.addExact(sector, Math.multiplyExact(records, page + 8L))
        val alignedLength = Math.multiplyExact((Math.addExact(exactLength, sector - 1) / sector), sector)
        val emptyTail = journal.size == Math.addExact(alignedLength, sector)
        content(exactLength == journal.size || emptyTail) {
            "Only a single segment and optional empty zero-header are supported; other sections preserved"
        }
        requireDatabaseHeader(database.head, page.toInt())
        return Layout(records.toInt(), nonce, pages, sector.toInt(), page.toInt(), bytes,
            (alignedLength - exactLength).toInt(), emptyTail)
    }

    private fun requireDatabaseHeader(header: ByteArray, pageSize: Int) {
        content(header.size >= 100 && header.copyOfRange(0, 16).contentEquals(dbMagic)) { "SQLite database header is invalid" }
        val encoded = ((header[16].toInt() and 255) shl 8) or (header[17].toInt() and 255)
        content((if (encoded == 1) 65536 else encoded) == pageSize && header[18] == 1.toByte() && header[19] == 1.toByte()) {
            "Database page size or rollback format is inconsistent"
        }
    }

    private fun copyPrefix(owner: AnchoredWorkspaceFiles, expected: Snapshot, target: AnchoredWorkspaceFiles.OpenedFile,
                           bytes: Long, requireCurrent: () -> Unit) {
        owner.openRead(DB).use { source ->
            same(expected.observation, source.observe())
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var offset = 0L
            while (true) {
                requireCurrent()
                val count = source.read(buffer)
                if (count < 0) break
                check(count > 0 && offset + count <= expected.size)
                val copied = minOf(count.toLong(), (bytes - offset).coerceAtLeast(0)).toInt()
                if (copied > 0) target.write(buffer, 0, copied)
                digest.update(buffer, 0, count); offset += count
            }
            check(offset == expected.size && hex(digest.digest()) == expected.sha) { "Captured database changed during rendering" }
            same(expected.observation, source.observe())
        }
    }

    private fun inspect(owner: AnchoredWorkspaceFiles, name: String, requireCurrent: () -> Unit): Snapshot = owner.openRead(name).use { input ->
        MirrorSqliteRollbackRecovery.privateFile(input)
        val before = input.observe()
        content(before.attributes.fileKey() != null && before.attributes.size() in 1..MAX_BYTES) { "Forensic input size or identity unavailable" }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        val head = ByteArray(minOf(before.attributes.size(), 100).toInt())
        val tail = ByteArray(8)
        var offset = 0L
        while (true) {
            requireCurrent()
            val count = input.read(buffer)
            if (count < 0) break
            check(count > 0 && offset + count <= before.attributes.size()) { "Forensic input changed while reading" }
            if (offset < head.size) buffer.copyInto(head, offset.toInt(), 0, minOf(count, head.size - offset.toInt()))
            if (count >= 8) buffer.copyInto(tail, 0, count - 8, count)
            else { tail.copyInto(tail, 0, count, 8); buffer.copyInto(tail, 8 - count, 0, count) }
            digest.update(buffer, 0, count); offset += count
        }
        check(offset == before.attributes.size())
        same(before, input.observe())
        Snapshot(before, hex(digest.digest()), head, tail)
    }

    private fun read(input: AnchoredWorkspaceFiles.OpenedFile, size: Int, digest: MessageDigest,
                     requireCurrent: () -> Unit): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            requireCurrent()
            val count = input.read(bytes, offset, size - offset)
            content(count > 0) { "Journal record is incomplete" }
            offset += count
        }
        digest.update(bytes)
        return bytes
    }

    private fun requireCandidate(owner: AnchoredWorkspaceFiles, expected: Snapshot, requireCurrent: () -> Unit) {
        check(owner.kind("candidate.sqlite-wal") == AnchoredWorkspaceFiles.Kind.ABSENT &&
            owner.kind("candidate.sqlite-shm") == AnchoredWorkspaceFiles.Kind.ABSENT) { "Candidate sidecars appeared during validation" }
        val current = inspect(owner, "candidate.sqlite", requireCurrent)
        same(expected.observation, current.observation)
        check(current.sha == expected.sha) { "Candidate bytes changed before completion; report is not current" }
    }

    private fun requireOriginalEvidence(owner: AnchoredWorkspaceFiles, database: Snapshot, journal: Snapshot,
                                        review: MirrorSqliteRollbackRecovery.Review, requireCurrent: () -> Unit) {
        requireCurrent()
        check(owner.directoryKey() == review.evidenceIdentity)
        check(owner.kind("$DB-wal") == AnchoredWorkspaceFiles.Kind.ABSENT &&
            owner.kind("$DB-shm") == AnchoredWorkspaceFiles.Kind.ABSENT) { "WAL evidence appeared during validation" }
        owner.openRead(DB).use { same(database.observation, it.observe()) }
        owner.openRead(JOURNAL).use { same(journal.observation, it.observe()) }
    }

    private fun requireBytes(owner: AnchoredWorkspaceFiles, name: String, expected: ByteArray,
                             requireCurrent: () -> Unit): AnchoredWorkspaceFiles.Observation {
        return owner.openRead(name).use { input ->
            MirrorSqliteRollbackRecovery.privateFile(input)
            val before = input.observe()
            check(before.attributes.size() == expected.size.toLong()) { "Forensic receipt/report size changed" }
            val digest = MessageDigest.getInstance("SHA-256")
            check(read(input, expected.size, digest, requireCurrent).contentEquals(expected) && input.read() == -1) {
                "Forensic receipt/report mismatch"
            }
            same(before, input.observe())
            before
        }
    }
    private fun same(before: AnchoredWorkspaceFiles.Observation, after: AnchoredWorkspaceFiles.Observation) {
        check(before.attributes.fileKey() == after.attributes.fileKey() && before.attributes.size() == after.attributes.size() &&
            before.attributes.lastModifiedTime() == after.attributes.lastModifiedTime() && before.changed == after.changed) {
            "Forensic input identity/version changed; no candidate was accepted"
        }
    }
    private fun u32(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 255) }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
