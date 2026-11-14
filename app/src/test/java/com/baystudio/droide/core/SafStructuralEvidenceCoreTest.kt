package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CancellationException
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Unwired core: real host private bytes/forces and deterministic faults, no provider effects. */
class SafStructuralEvidenceCoreTest {
    private val roots = arrayListOf<File>()
    private val sentinel = byteArrayOf(0, -1, 42, 13, 10, 127)
    private val tree = "content://fixture/tree/root"
    private val directoryMime = SafOperationJournal.DIRECTORY_MIME
    @After fun cleanup() { roots.forEach { PathSecurity.deleteTreeNoFollow(it) } }

    @Test fun restoredEnvelopeBytesCannotReviveRejectedLiveHandle() {
        val f = Fixture()
        val operation = f.begin()
        val action = f.journal.prepare(operation, creation())
        val envelope = f.journal.file(operation, "operation.json")
        val key = f.storage.key(envelope, false)
        val retained = f.files(operation).associate { it.name to it.readBytes() }
        envelope.writeBytes(retained.getValue("operation.json") + byteArrayOf(32))

        assertTrue(runCatching { f.journal.attempting(action) }.isFailure)
        assertTrue(operation.poisoned)
        envelope.writeBytes(retained.getValue("operation.json"))
        assertEquals(key, f.storage.key(envelope, false))
        assertTrue(runCatching { f.journal.attempting(action) }.isFailure)
        assertTrue(runCatching { f.journal.prepare(operation, creation()) }.isFailure)

        assertEquals(retained.keys, f.files(operation).map { it.name }.toSet())
        retained.forEach { (name, bytes) -> assertArrayEquals(bytes, f.journal.file(operation, name).readBytes()) }
        assertArrayEquals(sentinel, f.original.readBytes())
        assertEquals(0, f.moves)
    }

    @Test fun restoredWorkspaceIdentityCannotReviveRejectedLiveHandle() {
        val f = Fixture()
        val operation = f.begin()
        val action = f.journal.prepare(operation, creation())
        val retained = f.files(operation).associate { it.name to it.readBytes() }
        val old = f.root.resolve("original-workspace")
        val newer = f.root.resolve("retained-newer-workspace")
        val newerBytes = byteArrayOf(9, 8, 7)
        Files.move(f.workspace.toPath(), old.toPath())
        assertTrue(f.workspace.mkdir())
        f.original.writeBytes(newerBytes)

        assertTrue(runCatching { f.journal.attempting(action) }.isFailure)
        assertTrue(operation.poisoned)
        Files.move(f.workspace.toPath(), newer.toPath())
        Files.move(old.toPath(), f.workspace.toPath())
        assertEquals(f.journal.anchor.workspaceKey, f.storage.key(f.workspace, true))
        assertTrue(runCatching { f.journal.attempting(action) }.isFailure)
        assertTrue(runCatching { f.journal.prepare(operation, creation()) }.isFailure)

        assertEquals(retained.keys, f.files(operation).map { it.name }.toSet())
        retained.forEach { (name, bytes) -> assertArrayEquals(bytes, f.journal.file(operation, name).readBytes()) }
        assertArrayEquals(sentinel, f.original.readBytes())
        assertArrayEquals(newerBytes, newer.resolve(f.original.name).readBytes())
        assertEquals(0, f.moves)
    }

    @Test fun postAttemptReturnMismatchCannotBeRetriedAfterEnvelopeRestoration() {
        val f = Fixture()
        val operation = f.begin()
        val action = f.journal.prepare(operation, creation())
        f.journal.attempting(action)
        val retained = f.files(operation).associate { it.name to it.readBytes() }
        val envelope = f.journal.file(operation, "operation.json")
        val key = f.storage.key(envelope, false)
        val raw = rawUri(document("project", false))
        envelope.writeBytes(retained.getValue("operation.json") + byteArrayOf(32))

        assertTrue(runCatching { f.journal.returned(action, SafEffectReturn.COMPLETED, raw) }.isFailure)
        assertTrue(operation.poisoned)
        envelope.writeBytes(retained.getValue("operation.json"))
        assertEquals(key, f.storage.key(envelope, false))
        assertTrue(runCatching { f.journal.returned(action, SafEffectReturn.COMPLETED, raw) }.isFailure)

        assertEquals(retained.keys, f.files(operation).map { it.name }.toSet())
        retained.forEach { (name, bytes) -> assertArrayEquals(bytes, f.journal.file(operation, name).readBytes()) }
        assertEquals(SafEffectPhase.ATTEMPTING, f.reopen().recover(operation.id).effects.getValue(action.id).phase)
        assertArrayEquals(sentinel, f.original.readBytes())
        assertEquals(0, f.moves)
    }

    @Test fun foreignJournalCannotPoisonAnotherOwnersLiveOperation() {
        val f = Fixture()
        val operation = f.begin()
        val action = f.journal.prepare(operation, creation())
        val retained = f.files(operation).associate { it.name to it.readBytes() }
        val foreign = f.reopen()

        assertTrue(runCatching { foreign.attempting(action) }.isFailure)
        assertTrue(runCatching { foreign.prepare(operation, creation()) }.isFailure)
        assertFalse(operation.poisoned)
        assertEquals(retained.keys, f.files(operation).map { it.name }.toSet())
        retained.forEach { (name, bytes) -> assertArrayEquals(bytes, f.journal.file(operation, name).readBytes()) }
        f.journal.attempting(action)

        assertEquals(SafEffectPhase.ATTEMPTING, operation.effects.getValue(action.id).phase)
        assertFalse(operation.poisoned)
        assertArrayEquals(sentinel, f.original.readBytes())
        assertEquals(0, f.moves)
    }

    @Test fun initialValidationFailureIsPropagatedUnchangedAndPoisonsOnlyOwnedLiveHandle() {
        val f = Fixture()
        val operation = f.begin()
        val action = f.journal.prepare(operation, creation())
        val retained = f.files(operation).associate { it.name to it.readBytes() }
        val failure = IOException("initial envelope read failed")
        f.beforeRead = { if (it.name == "operation.json") throw failure }

        assertSame(failure, runCatching { f.journal.attempting(action) }.exceptionOrNull())
        assertTrue(operation.poisoned)
        f.beforeRead = {}
        assertTrue(runCatching { f.journal.attempting(action) }.isFailure)

        assertEquals(retained.keys, f.files(operation).map { it.name }.toSet())
        retained.forEach { (name, bytes) -> assertArrayEquals(bytes, f.journal.file(operation, name).readBytes()) }
        assertArrayEquals(sentinel, f.original.readBytes())
        assertEquals(0, f.moves)
    }

    @Test fun singleFileDeleteRequiresCompleteOriginalBytesAndRetainsThemAfterVerification() {
        val f = Fixture()
        val operation = f.begin()
        val payload = f.payload(operation)
        val original = document("project", false)
        val image = SafTreeEvidence(operationId = operation.id, treeUri = tree, rootPath = "project",
            rootDocument = original, entries = listOf(SafTreeEvidenceEntry("", original, payload)))
        val ref = f.journal.preserveTreeIndex(operation, image)
        val action = f.journal.prepare(operation, deletePlan(original, ref))
        f.journal.attempting(action)
        f.journal.returned(action, SafEffectReturn.COMPLETED, SafProviderReturn(SafProviderReturnShape.BOOLEAN, boolean = true))
        f.journal.verified(action)

        assertEquals(SafEffectPhase.VERIFIED, f.reopen().recover(operation.id).effects.getValue(action.id).phase)
        assertArrayEquals(sentinel, f.journal.file(operation, payload.name).readBytes())
        assertArrayEquals(sentinel, f.original.readBytes())
        assertEquals(0, f.moves)
    }

    @Test fun nestedBeforeimagePreservesEmptyDirectoriesAndNodeModulesSentinel() {
        val f = Fixture()
        val operation = f.begin()
        val image = f.nested(operation)
        val ref = f.journal.preserveTreeIndex(operation, image)
        val action = f.journal.prepare(operation, deletePlan(image.rootDocument, ref))
        f.journal.attempting(action)

        val decoded = SafJournalCodec.decode(SafTreeEvidence.serializer(), f.journal.file(operation, ref.name).readBytes())
        assertEquals(image, decoded)
        assertEquals(listOf("", "empty", "node_modules", "node_modules/pkg", "node_modules/pkg/index.js"), decoded.entries.map { it.relativePath })
        assertNull(decoded.entries[1].payload)
        assertArrayEquals(sentinel, f.journal.file(operation, decoded.entries.last().payload!!.name).readBytes())
        assertEquals(2, f.reopen().recover(operation.id).evidenceFiles)
        assertArrayEquals(sentinel, f.original.readBytes())
    }

    @Test fun structuralDeleteWithoutBeforeimageCannotPrepare() {
        val f = Fixture()
        val operation = f.begin()
        assertTrue(runCatching { f.journal.prepare(operation, deletePlan(document("project", true), null)) }.isFailure)
        assertFalse(f.files(operation).any { it.name.startsWith("action-") })
        assertArrayEquals(sentinel, f.original.readBytes())
    }

    @Test fun incompleteDuplicateAliasedAndCyclicIndexesRejectBeforeAnyAction() {
        val edits: List<(SafTreeEvidence) -> SafTreeEvidence> = listOf(
            { it.copy(entries = it.entries.dropLast(1) + it.entries.last().copy(payload = null)) },
            { it.copy(entries = it.entries + it.entries.last()) },
            { it.copy(entries = it.entries.mapIndexed { index, entry -> if (index == 1) entry.copy(document = entry.document.copy(documentId = it.rootDocument.documentId)) else entry }) },
            { it.copy(entries = it.entries.filter { entry -> entry.relativePath != "node_modules/pkg" }) },
            { it.copy(entries = it.entries.mapIndexed { index, entry -> if (index == 4) entry.copy(document = entry.document.copy(parentDocumentId = "foreign")) else entry }) },
            { it.copy(entries = it.entries.mapIndexed { index, entry -> if (index == 4) entry.copy(document = entry.document.copy(name = "different.js")) else entry }) },
            { it.copy(entries = it.entries.mapIndexed { index, entry -> if (index == 1) entry.copy(document = entry.document.copy(uri = it.rootDocument.uri)) else entry }) },
            { it.copy(entries = it.entries.reversed()) },
        )
        edits.forEach { edit ->
            val f = Fixture()
            val operation = f.begin()
            val image = f.nested(operation)
            assertTrue(runCatching { f.journal.preserveTreeIndex(operation, edit(image)) }.isFailure)
            assertFalse(f.files(operation).any { it.name.startsWith("tree-") || it.name.startsWith("action-") })
            assertArrayEquals(sentinel, f.original.readBytes())
        }
    }

    @Test fun operationTreeRootPathAndRootDocumentBindingsRejectEvenSelfConsistentRefHashes() {
        val edits: List<(SafTreeEvidence) -> SafTreeEvidence> = listOf(
            { it.copy(operationId = UUID.randomUUID().toString()) },
            { it.copy(treeUri = "content://foreign/tree/root") },
            { it.copy(rootPath = "other/project") },
            { it.copy(rootDocument = it.rootDocument.copy(documentId = "foreign-root")) },
        )
        edits.forEach { edit ->
            val f = Fixture()
            val operation = f.begin()
            val image = f.nested(operation)
            val ref = f.journal.preserveTreeIndex(operation, image)
            val file = f.journal.file(operation, ref.name)
            val damaged = SafJournalCodec.encode(SafTreeEvidence.serializer(), edit(image))
            file.writeBytes(damaged)
            val selfConsistent = ref.copy(identity = f.storage.describe(file, f.limits.treeIndexBytes))
            assertTrue(runCatching { f.journal.prepare(operation, deletePlan(image.rootDocument, selfConsistent)) }.isFailure)
            assertArrayEquals(damaged, file.readBytes())
            assertFalse(f.files(operation).any { it.name.startsWith("action-") })
            assertArrayEquals(sentinel, f.original.readBytes())
        }
    }

    @Test fun wrongPayloadHashOrIdentityCannotBecomeCompleteBeforeimage() {
        listOf(false, true).forEach { wrongKey ->
            val f = Fixture()
            val operation = f.begin()
            val image = f.nested(operation)
            val entry = image.entries.last()
            val ref = entry.payload!!
            val bad = ref.copy(identity = if (wrongKey) ref.identity.copy(key = "foreign") else ref.identity.copy(sha256 = "0".repeat(64)))
            assertTrue(runCatching { f.journal.preserveTreeIndex(operation,
                image.copy(entries = image.entries.dropLast(1) + entry.copy(payload = bad))) }.isFailure)
            assertArrayEquals(sentinel, f.journal.file(operation, ref.name).readBytes())
            assertFalse(f.files(operation).any { it.name.startsWith("tree-") })
        }
    }

    @Test fun oversizedPayloadIdentityRejectsBeforeEntryEncodingOrPayloadHashing() {
        val f = Fixture()
        val operation = f.begin()
        val image = f.nested(operation)
        val entry = image.entries.last()
        val payload = entry.payload!!
        val oversized = payload.copy(identity = payload.identity.copy(key = "k".repeat(4097)))
        f.reads.clear()

        assertTrue(runCatching { f.journal.preserveTreeIndex(operation,
            image.copy(entries = image.entries.dropLast(1) + entry.copy(payload = oversized))) }.isFailure)

        assertFalse(f.reads.any { it.name.startsWith("payload-") })
        assertFalse(f.files(operation).any { it.name.startsWith("tree-") || it.name.startsWith("action-") })
        assertArrayEquals(sentinel, f.journal.file(operation, payload.name).readBytes())
    }

    @Test fun modifiedPayloadAfterPreparationRejectsDurableAttempt() {
        val f = Fixture()
        val operation = f.begin()
        val image = f.nested(operation)
        val ref = f.journal.preserveTreeIndex(operation, image)
        val action = f.journal.prepare(operation, deletePlan(image.rootDocument, ref))
        val payload = f.journal.file(operation, image.entries.last().payload!!.name)
        val newer = ByteArray(sentinel.size) { 9 }
        payload.writeBytes(newer)
        assertTrue(runCatching { f.journal.attempting(action) }.isFailure)
        assertFalse(f.journal.file(operation, SafOperationJournal.actionName(action.id, SafEffectPhase.ATTEMPTING)).exists())
        assertArrayEquals(newer, payload.readBytes())
        assertArrayEquals(sentinel, f.original.readBytes())
    }

    @Test fun entryDepthByteAndEncodedIndexBudgetsRejectBeforePreparing() {
        listOf(
            SafJournalLimits(treeEntries = 4), SafJournalLimits(treeDepth = 3),
            SafJournalLimits(treeBytes = sentinel.size.toLong() - 1), SafJournalLimits(treeIndexBytes = 256),
        ).forEach { limits ->
            val f = Fixture(limits)
            val operation = f.begin()
            val image = f.nested(operation)
            assertTrue(runCatching { f.journal.preserveTreeIndex(operation, image) }.isFailure)
            assertFalse(f.files(operation).any { it.name.startsWith("tree-") || it.name.startsWith("action-") })
            assertArrayEquals(sentinel, f.original.readBytes())
        }
    }

    @Test fun actualFileCountBudgetIncludesEveryBeforeimageFile() {
        val f = Fixture(SafJournalLimits(treeFiles = 1))
        val operation = f.begin()
        val image = f.nested(operation)
        val payload = f.payload(operation)
        val more = SafTreeEvidenceEntry("z.bin", document("project/z.bin", false), payload)
        assertTrue(runCatching { f.journal.preserveTreeIndex(operation, image.copy(entries = image.entries + more)) }.isFailure)
        assertFalse(f.files(operation).any { it.name.startsWith("tree-") })
        assertEquals(2, f.files(operation).count { it.name.startsWith("payload-") })
    }

    @Test fun evidenceFileReservationCountsPayloadAndIndexAndRetainsCapacityFailure() {
        val f = Fixture()
        val operation = f.begin(f.reservation.copy(evidenceFiles = 1))
        val image = f.nested(operation)
        assertTrue(runCatching { f.journal.preserveTreeIndex(operation, image) }.isFailure)
        assertEquals(1, f.reopen().recover(operation.id).evidenceFiles)
        assertArrayEquals(sentinel, f.journal.file(operation, image.entries.last().payload!!.name).readBytes())
        assertTrue(runCatching { f.reopen().begin(SafOperationRequest(SafOperationKind.DELETE, listOf("project")), listOf("project"), f.reservation) }.isFailure)
    }

    @Test fun treeIndexBytesConsumeMetadataRatherThanPayloadReservation() {
        val f = Fixture()
        val operation = f.begin()
        val image = f.nested(operation)
        val beforeMetadata = operation.metadataBytes
        val beforePayload = operation.payloadBytes
        val ref = f.journal.preserveTreeIndex(operation, image)
        assertEquals(beforeMetadata + ref.identity.bytes, operation.metadataBytes)
        assertEquals(beforePayload, operation.payloadBytes)
        val recovered = f.reopen().recover(operation.id)
        assertEquals(operation.metadataBytes, recovered.metadataBytes)
        assertEquals(operation.payloadBytes, recovered.payloadBytes)
    }

    @Test fun streamedActualBytesCannotExceedDeclaredPayloadCapacity() {
        val f = Fixture()
        val operation = f.begin(f.reservation.copy(payloadBytes = 3))
        val input = object : ByteArrayInputStream(sentinel) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(1, length))
        }
        assertTrue(runCatching { f.journal.preserveInput(operation, input, 99) }.isFailure)
        val retained = f.files(operation).single { it.name.startsWith("payload-") }
        assertArrayEquals(sentinel.copyOf(3), retained.readBytes())
        assertEquals(3L, f.reopen().recover(operation.id).payloadBytes)
        assertFalse(f.files(operation).any { it.name.startsWith("action-") })
        assertArrayEquals(sentinel, f.original.readBytes())
    }

    @Test fun zeroProgressInputIsBoundedAndClosedWithoutSuccessfulReceipt() {
        val f = Fixture()
        val operation = f.begin()
        var reads = 0
        var closes = 0
        val input = object : InputStream() {
            override fun read(): Int = error("bulk expected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { reads++; return 0 }
            override fun close() { closes++ }
        }
        assertTrue(runCatching { f.journal.preserveInput(operation, input, 32) }.isFailure)
        assertEquals(32, reads)
        assertEquals(1, closes)
        assertTrue(operation.poisoned)
        assertEquals(1, f.reopen().recover(operation.id).evidenceFiles)
    }

    @Test fun inputCloseFailureRetainsBytesAndPoisonsLiveOperation() {
        val f = Fixture()
        val operation = f.begin()
        val failure = IOException("input close failed")
        val input = object : ByteArrayInputStream(sentinel) { override fun close() { throw failure } }
        assertSame(failure, runCatching { f.journal.preserveInput(operation, input, 32) }.exceptionOrNull())
        assertTrue(operation.poisoned)
        assertArrayEquals(sentinel, f.files(operation).single { it.name.startsWith("payload-") }.readBytes())
        assertFalse(f.files(operation).any { it.name.startsWith("tree-") || it.name.startsWith("action-") })
    }

    @Test fun sixPreEffectForceFailuresRetainSentinelsAndNeverReachSimulatedEffect() {
        listOf("payload-file", "payload-link", "tree-file", "tree-link", "prepared", "attempting").forEach { site ->
            val f = Fixture()
            val operation = f.begin()
            var effectCalls = 0
            var lastForced = ""
            f.beforeForceFile = { file ->
                lastForced = file.name
                if ((site == "payload-file" && file.name.startsWith("payload-")) ||
                    (site == "tree-file" && file.name.startsWith("tree-")) ||
                    (site == "prepared" && file.name.startsWith("action-") && file.name.endsWith("-0.json")) ||
                    (site == "attempting" && file.name.startsWith("action-") && file.name.endsWith("-1.json"))) throw IOException(site)
            }
            f.beforeForceDirectory = { file ->
                if (file == f.directory.resolve(operation.id) &&
                    ((site == "payload-link" && lastForced.startsWith("payload-")) ||
                        (site == "tree-link" && lastForced.startsWith("tree-")))) throw IOException(site)
            }
            assertTrue(runCatching {
                val image = f.nested(operation)
                val ref = f.journal.preserveTreeIndex(operation, image)
                val action = f.journal.prepare(operation, deletePlan(image.rootDocument, ref))
                f.journal.attempting(action)
                effectCalls++
            }.exceptionOrNull() is IOException)
            assertEquals(0, effectCalls)
            val retained = f.files(operation).associate { it.name to it.readBytes() }
            f.beforeForceFile = {}; f.beforeForceDirectory = {}
            f.reopen().recover(operation.id)
            retained.forEach { (name, bytes) -> assertArrayEquals(bytes, f.journal.file(operation, name).readBytes()) }
            assertArrayEquals(sentinel, f.original.readBytes())
            assertEquals(0, f.moves)
        }
    }

    @Test fun successfulAndThrownReturnFinalizersReadNoPayloadIndexOrHistoricalOperation() {
        listOf(false, true).forEach { thrown ->
            val f = Fixture()
            val history = f.journal.begin(SafOperationRequest(SafOperationKind.WRITE, listOf("history")), listOf("history"), f.reservation)
            f.journal.abandonPrepared(history)
            val operation = f.begin()
            val image = f.nested(operation, ByteArray(512 * 1024) { 42 })
            val ref = f.journal.preserveTreeIndex(operation, image)
            val action = f.journal.prepare(operation, deletePlan(image.rootDocument, ref))
            f.journal.attempting(action)
            f.reads.clear()
            f.journal.returned(action, if (thrown) SafEffectReturn.THREW else SafEffectReturn.COMPLETED,
                if (thrown) SafProviderReturn(SafProviderReturnShape.THREW, diagnosticClass = "java.io.IOException")
                else SafProviderReturn(SafProviderReturnShape.BOOLEAN, boolean = true))
            if (!thrown) f.journal.verified(action)
            assertEquals(if (thrown) 6 else 12, f.reads.size)
            assertFalse(f.reads.any { it.name.startsWith("payload-") || it.name.startsWith("tree-") || it.name == "baseline.bin" || it.parentFile?.name == history.id })
            assertTrue(f.reads.all { it.name in setOf("anchor.json", "operation.json") || it.name.startsWith("action-${action.id}-") })
            assertArrayEquals(sentinel, f.original.readBytes())
            assertEquals(0, f.moves)
        }
    }

    @Test fun cancellationDuringDeepTreeAdmissionStopsBeforeDurableAttempt() {
        val f = Fixture()
        val operation = f.begin()
        val image = f.nested(operation, ByteArray(512 * 1024) { 42 })
        val ref = f.journal.preserveTreeIndex(operation, image)
        val action = f.journal.prepare(operation, deletePlan(image.rootDocument, ref))
        var payloadRead = false
        var polls = 0
        f.beforeRead = { if (it.name.startsWith("payload-")) payloadRead = true }
        val cancellation = CancellationException("deep beforeimage cancellation")
        assertSame(cancellation, runCatching { f.journal.attempting(action) {
            if (payloadRead && ++polls == 4) throw cancellation
        } }.exceptionOrNull())
        assertEquals(4, polls)
        assertFalse(f.journal.file(operation, SafOperationJournal.actionName(action.id, SafEffectPhase.ATTEMPTING)).exists())
        assertArrayEquals(sentinel, f.original.readBytes())
    }

    @Test fun recoveryHashesEachReferencedPayloadAndIndexOnceAcrossFourPhases() {
        val f = Fixture()
        val operation = f.begin()
        val image = f.nested(operation, ByteArray(512 * 1024) { 42 })
        val ref = f.journal.preserveTreeIndex(operation, image)
        val action = f.journal.prepare(operation, deletePlan(image.rootDocument, ref))
        f.journal.attempting(action)
        f.journal.returned(action, SafEffectReturn.COMPLETED, SafProviderReturn(SafProviderReturnShape.BOOLEAN, boolean = true))
        f.journal.verified(action)
        f.reads.clear()

        assertEquals(SafEffectPhase.VERIFIED, f.reopen().recover(operation.id).effects.getValue(action.id).phase)

        assertEquals(1, f.reads.count { it.name == ref.name })
        assertEquals(1, f.reads.count { it.name == image.entries.last().payload!!.name })
        assertEquals(0, f.moves)
    }

    @Test fun cancellationDuringRecoveryDeepHashRetainsAllPrivateEvidence() {
        val f = Fixture()
        val operation = f.begin()
        val image = f.nested(operation, ByteArray(512 * 1024) { 42 })
        val ref = f.journal.preserveTreeIndex(operation, image)
        f.journal.prepare(operation, deletePlan(image.rootDocument, ref))
        val bytes = f.files(operation).associate { it.name to it.readBytes() }
        val reopened = f.reopen()
        var payloadRead = false
        var polls = 0
        f.beforeRead = { if (it.name.startsWith("payload-")) payloadRead = true }
        val cancellation = CancellationException("cancel recovery hash")

        assertSame(cancellation, runCatching { reopened.recover(operation.id) {
            if (payloadRead && ++polls == 4) throw cancellation
        } }.exceptionOrNull())

        assertEquals(4, polls)
        bytes.forEach { (name, original) -> assertArrayEquals(original, f.journal.file(operation, name).readBytes()) }
        assertArrayEquals(sentinel, f.original.readBytes())
        assertEquals(0, f.moves)
    }

    @Test fun boundedPredecessorCorruptionBlocksReturnWithoutDeletingEvidence() {
        val f = Fixture()
        val operation = f.begin()
        val action = f.journal.prepare(operation, creation())
        f.journal.attempting(action)
        val predecessor = f.journal.file(operation, SafOperationJournal.actionName(action.id, SafEffectPhase.ATTEMPTING))
        val damaged = byteArrayOf(1, 2, 3)
        predecessor.writeBytes(damaged)
        assertTrue(runCatching { f.journal.returned(action, SafEffectReturn.COMPLETED, rawUri(document("project", false))) }.isFailure)
        assertArrayEquals(damaged, predecessor.readBytes())
        assertFalse(f.journal.file(operation, SafOperationJournal.actionName(action.id, SafEffectPhase.RETURNED)).exists())
    }

    @Test fun rawCreateReturnPrecedesIdentityAdmissionAndCancellationRetainsIt() {
        val f = Fixture()
        val operation = f.begin()
        val action = f.journal.prepare(operation, creation())
        f.journal.attempting(action)
        val raw = rawUri(document("project", false))
        f.journal.returned(action, SafEffectReturn.COMPLETED, raw)
        val cancellation = CancellationException("identity query cancelled")
        assertSame(cancellation, runCatching { f.journal.verified(action, document("project", false)) { throw cancellation } }.exceptionOrNull())
        val record = f.reopen().recover(operation.id).effects.getValue(action.id)
        assertEquals(SafEffectPhase.RETURNED, record.phase)
        assertEquals(raw, record.providerReturn)
        assertNull(record.returnedDocument)
        assertFalse(f.journal.file(operation, "manifest-plan.json").exists())
    }

    @Test fun wrongRawUriIsRetainedButCannotBecomeAdmittedIdentity() {
        val f = Fixture()
        val operation = f.begin()
        val action = f.journal.prepare(operation, creation())
        f.journal.attempting(action)
        val raw = SafProviderReturn(SafProviderReturnShape.URI, uri = "content://foreign/tree/root/document/other")
        f.journal.returned(action, SafEffectReturn.COMPLETED, raw)
        assertTrue(runCatching { f.journal.verified(action, document("project", false)) }.isFailure)
        val record = f.reopen().recover(operation.id).effects.getValue(action.id)
        assertEquals(raw, record.providerReturn)
        assertNull(record.returnedDocument)
        assertEquals(SafEffectPhase.RETURNED, record.phase)
    }

    @Test fun linkedWriteCannotAttemptUntilCreationHasVerifiedExactIdentity() {
        val f = Fixture()
        val operation = f.begin()
        val create = f.journal.prepare(operation, creation())
        val intended = f.payload(operation)
        val write = f.journal.prepare(operation, SafEffectPlan(SafEffectKind.WRITE_FILE, "project", null, parent(), create.id, null, intended))
        f.journal.attempting(create)
        f.journal.returned(create, SafEffectReturn.COMPLETED, rawUri(document("project", false)))
        assertTrue(runCatching { f.journal.attempting(write) }.isFailure)
        assertFalse(f.journal.file(operation, SafOperationJournal.actionName(write.id, SafEffectPhase.ATTEMPTING)).exists())
        assertArrayEquals(sentinel, f.journal.file(operation, intended.name).readBytes())
    }

    @Test fun nullAndFalseRawReturnsAreRetainedAndCannotVerify() {
        listOf(false, true).forEach { deletion ->
            val f = Fixture()
            val operation = f.begin()
            val plan = if (deletion) f.nested(operation).let { deletePlan(it.rootDocument, f.journal.preserveTreeIndex(operation, it)) } else creation()
            val action = f.journal.prepare(operation, plan)
            f.journal.attempting(action)
            val raw = if (deletion) SafProviderReturn(SafProviderReturnShape.BOOLEAN, boolean = false) else SafProviderReturn(SafProviderReturnShape.URI)
            f.journal.returned(action, SafEffectReturn.REJECTED, raw)
            assertTrue(runCatching { f.journal.verified(action) }.isFailure)
            assertEquals(raw, f.reopen().recover(operation.id).effects.getValue(action.id).providerReturn)
        }
    }

    @Test fun oversizedOrWrongShapedRawReturnLeavesAttemptEvidence() {
        listOf(
            SafProviderReturn(SafProviderReturnShape.URI, uri = "x".repeat(256 * 1024 + 1)),
            SafProviderReturn(SafProviderReturnShape.BOOLEAN, boolean = true),
        ).forEach { raw ->
            val f = Fixture()
            val operation = f.begin()
            val action = f.journal.prepare(operation, creation())
            f.journal.attempting(action)
            assertTrue(runCatching { f.journal.returned(action, SafEffectReturn.COMPLETED, raw) }.isFailure)
            assertEquals(SafEffectPhase.ATTEMPTING, f.reopen().recover(operation.id).effects.getValue(action.id).phase)
            assertFalse(f.journal.file(operation, SafOperationJournal.actionName(action.id, SafEffectPhase.RETURNED)).exists())
        }
    }

    @Test fun rawReturnForceFailureRetainsAttemptAndNeverReplaysSimulatedCall() {
        val f = Fixture()
        val operation = f.begin()
        val action = f.journal.prepare(operation, creation())
        f.journal.attempting(action)
        var simulatedCalls = 1
        f.beforeForceFile = { if (it.name == SafOperationJournal.actionName(action.id, SafEffectPhase.RETURNED)) throw IOException("return force") }
        assertTrue(runCatching { f.journal.returned(action, SafEffectReturn.COMPLETED, rawUri(document("project", false))) }.isFailure)
        f.beforeForceFile = {}
        val reopened = f.reopen()
        val recovered = reopened.recover(operation.id)
        assertEquals(SafEffectPhase.RETURNED, recovered.effects.getValue(action.id).phase)
        assertTrue(runCatching { reopened.prepare(recovered, creation()); simulatedCalls++ }.isFailure)
        assertEquals(1, simulatedCalls)
        assertArrayEquals(sentinel, f.original.readBytes())
    }

    @Test fun changedVisibleManifestDuringPreparationBlocksFirstAttempt() {
        val f = Fixture()
        val operation = f.begin()
        val action = f.journal.prepare(operation, creation())
        val manifest = f.workspace.resolve(".droide/saf_manifest.json")
        assertTrue(manifest.parentFile!!.mkdir())
        manifest.writeBytes(SafJournalCodec.encode(SafLegacyTrustedManifest.serializer(), SafLegacyTrustedManifest()))
        val original = manifest.readBytes()
        assertTrue(runCatching { f.journal.attempting(action) }.isFailure)
        assertFalse(f.journal.file(operation, SafOperationJournal.actionName(action.id, SafEffectPhase.ATTEMPTING)).exists())
        assertArrayEquals(original, manifest.readBytes())
    }

    @Test fun previousJournalSchemaIsRetainedAndNeverSilentlyUpgraded() {
        val f = Fixture()
        val anchor = f.directory.resolve("anchor.json")
        val old = anchor.readText().replace("\"version\":2", "\"version\":1").toByteArray()
        anchor.writeBytes(old)
        assertTrue(runCatching { f.reopen() }.isFailure)
        assertArrayEquals(old, anchor.readBytes())
        assertArrayEquals(sentinel, f.original.readBytes())
        assertEquals(0, f.moves)
    }

    private fun parent() = SafOwnedDocument("$tree/document/root", "root", "root", directoryMime, "tree")
    private fun document(path: String, directory: Boolean): SafOwnedDocument {
        val id = "root/$path"
        return SafOwnedDocument("$tree/document/" + id.replace("/", "%2F"), id, path.substringAfterLast('/'),
            if (directory) directoryMime else "application/octet-stream", "root" + path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "/$it" })
    }
    private fun rawUri(document: SafOwnedDocument) = SafProviderReturn(SafProviderReturnShape.URI, uri = document.uri)
    private fun creation() = SafEffectPlan(SafEffectKind.CREATE_FILE, "project", null, parent(), null, null, null, requestedMimeType = "application/octet-stream")
    private fun deletePlan(document: SafOwnedDocument, ref: SafEvidenceFile?) =
        SafEffectPlan(SafEffectKind.DELETE, "project", document, parent(), null, null, null, beforeTree = ref)

    private inner class Fixture(val limits: SafJournalLimits = SafJournalLimits()) {
        val root = Files.createTempDirectory("saf-structural-core-").toFile().also { roots += it }
        val workspace = root.resolve("workspace").apply { mkdir() }
        val original = workspace.resolve("original-sentinel.bin").apply { writeBytes(sentinel) }
        val directory = root.resolve("journal")
        val reads = arrayListOf<File>()
        var beforeRead: (File) -> Unit = {}
        var beforeForceFile: (File) -> Unit = {}
        var beforeForceDirectory: (File) -> Unit = {}
        var moves = 0
        val reservation = SafJournalReservation(8, 2L * 1024 * 1024, 8L * 1024 * 1024, evidenceFiles = 16)
        val storage = SafOperationStorage(
            forceDirectory = { beforeForceDirectory(it); FileChannel.open(it.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel -> channel.force(true) } },
            forceFile = { beforeForceFile(it); FileChannel.open(it.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel -> channel.force(true) } },
            replaceAtomically = { _, _ -> moves++; error("No manifest publication expected in structural fixture") },
            beforeRead = { reads += it; beforeRead(it) },
        )
        val journal = SafOperationJournal.create(workspace, tree, directory, storage, limits)
        fun begin(reserve: SafJournalReservation = reservation) = journal.begin(SafOperationRequest(SafOperationKind.DELETE, listOf("project")), listOf("project"), reserve)
        fun reopen() = SafOperationJournal.open(workspace, tree, directory, storage, limits)
        fun files(operation: SafOperationJournal.Operation) = directory.resolve(operation.id).listFiles()!!.toList()
        fun payload(operation: SafOperationJournal.Operation, bytes: ByteArray = sentinel) = journal.preserveInput(operation, ByteArrayInputStream(bytes), bytes.size.toLong())
        fun nested(operation: SafOperationJournal.Operation, bytes: ByteArray = sentinel): SafTreeEvidence {
            val rootDocument = document("project", true)
            return SafTreeEvidence(operationId = operation.id, treeUri = tree, rootPath = "project", rootDocument = rootDocument,
                entries = listOf(
                    SafTreeEvidenceEntry("", rootDocument, null),
                    SafTreeEvidenceEntry("empty", document("project/empty", true), null),
                    SafTreeEvidenceEntry("node_modules", document("project/node_modules", true), null),
                    SafTreeEvidenceEntry("node_modules/pkg", document("project/node_modules/pkg", true), null),
                    SafTreeEvidenceEntry("node_modules/pkg/index.js", document("project/node_modules/pkg/index.js", false), payload(operation, bytes)),
                ))
        }
    }
}
