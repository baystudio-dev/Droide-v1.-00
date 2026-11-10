package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectCopyPreparedRecoveryTest {
    private val scope = ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.EXPLORER_IMPORT)

    @Test fun retainedPayloadAndPreparedIntentSurvivePreAttemptRejectionAndHistoryChurn() = runBlocking {
        withProject { root ->
            val journal = journal(root)
            val lease = journal.begin(owner("original"), "asset.bin")
            val intent = intentFile(root, lease.intent.id)
            val originalIntent = intent.readBytes()
            var publisherCalled = false
            val observer = object : StreamPublicationObserver {
                override fun beforePublish(preparation: StreamPublicationPreparation) {
                    lease.beforePublish(preparation.copy(rootIdentity = "replaced-project"))
                }
                override fun onPublished(receipt: StreamPublicationReceipt) { fail("Publication was not admitted") }
            }
            val failure = runCatching {
                FileRepository(root, newFilePublisher = NewProjectFilePublisher { _, _ -> publisherCalled = true })
                    .writeStream("asset.bin", "single-use import bytes".byteInputStream(),
                        requireAbsent = true, publicationObserver = observer)
            }.exceptionOrNull()
            lease.close()
            assertTrue(failure is IllegalArgumentException)
            assertFalse(publisherCalled)
            assertFalse(File(intent.parentFile, "attempt.json").exists())
            val retained = root.listFiles()!!.single { it.name.startsWith(".droide-tmp-") }
            val retainedIdentity = ProjectCopyEvidence.identity(retained)
            val restarted = journal(root)
            repeat(8) { commit(root, restarted, "later-$it") }
            assertArrayEquals(originalIntent, intent.readBytes())
            val record = journal(root).recovery(scope).records.single { it.intent.id == lease.intent.id }
            assertEquals(ProjectCopyJournal.Status.PREPARED, record.status)
            assertFalse(record.active)
            assertNull(record.receipt)
            assertEquals(retainedIdentity, ProjectCopyEvidence.identity(retained))
            assertEquals("single-use import bytes", retained.readText())
            assertFalse(root.resolve("asset.bin").exists())
        }
    }

    @Test fun closedLeaseBeforeAttemptDoesNotTurnRetainedPayloadIntoDisposableHistory() = runBlocking {
        withProject { root ->
            val journal = journal(root)
            val lease = journal.begin(owner("cancelled"), "cancelled.bin")
            val originalIntent = intentFile(root, lease.intent.id).readBytes()
            val observer = object : StreamPublicationObserver {
                override fun beforePublish(preparation: StreamPublicationPreparation) {
                    lease.close()
                    lease.beforePublish(preparation)
                }
                override fun onPublished(receipt: StreamPublicationReceipt) { fail("Closed lease cannot publish") }
            }
            assertTrue(runCatching {
                FileRepository(root).writeStream("cancelled.bin", "cancelled source".byteInputStream(),
                    requireAbsent = true, publicationObserver = observer)
            }.exceptionOrNull() is IllegalStateException)
            val restarted = journal(root)
            repeat(5) { commit(root, restarted, "successful-$it") }
            assertArrayEquals(originalIntent, intentFile(root, lease.intent.id).readBytes())
            assertEquals("cancelled source", root.listFiles()!!.single { it.name.startsWith(".droide-tmp-") }.readText())
            assertFalse(root.resolve("cancelled.bin").exists())
        }
    }

    @Test fun legacyPreparedRetirementIsVisibleAndInspectableWithoutRewritingEitherLayout() = runBlocking {
        for (hasStandaloneIntent in listOf(true, false)) withProject { root ->
            val journal = journal(root)
            val originalOwner = owner("legacy")
            val lease = journal.begin(originalOwner, "legacy.bin")
            lease.close()
            val intent = intentFile(root, lease.intent.id)
            val proof = File(intent.parentFile, "retired.json")
            proof.writeText("{\"version\":1,\"intent\":" + intent.readText() +
                ",\"attempt\":null,\"receipt\":null,\"seal\":null}")
            if (!hasStandaloneIntent) Files.delete(intent.toPath())
            val original = metadata(root)
            val restarted = journal(root)
            val record = restarted.recovery(scope).records.single()
            assertEquals(ProjectCopyJournal.Status.UNKNOWN, record.status)
            assertFalse(record.retired)
            assertNull(record.problem)
            assertNull(record.receipt)
            val inspection = restarted.inspect(scope, record.intent.id)
            assertNull(inspection.current.target)
            assertTrue(runCatching { restarted.begin(originalOwner.copy(runId = "later-run"), "renamed.bin") }
                .exceptionOrNull() is ProjectCopyJournal.Blocked)
            assertEquals(original, metadata(root))
            repeat(5) { commit(root, restarted, "independent-$it") }
            original.forEach { (path, hash) -> assertEquals(hash, ProjectCopyEvidence.sha256(root.resolve(path).readBytes())) }
            assertEquals(ProjectCopyJournal.Status.UNKNOWN,
                journal(root).recovery(scope).records.single { it.intent.id == lease.intent.id }.status)
            assertFalse(root.resolve("legacy.bin").exists())
            assertFalse(root.resolve("renamed.bin").exists())
        }
    }

    @Test fun preparedEvidenceStopsNewAdmissionAt128RecordsWithoutMetadataMutation() {
        withProject { root ->
            val journal = journal(root, history = 48)
            repeat(128) { journal.begin(owner("pending-$it"), "pending-$it.bin").close() }
            assertEquals(128, journal.recovery(scope).records.size)
            val original = metadata(root)
            val failure = runCatching { journal(root, history = 48).begin(owner("overflow"), "overflow.bin") }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message!!.contains("full"))
            assertEquals(original, metadata(root))
            assertFalse(root.resolve("overflow.bin").exists())
        }
    }

    @Test fun legacyPreparedRecoveryApprovalAllowsOnlyOneFreshManualRequest() {
        withProject { root ->
            var nanos = 1_000_000L
            val journal = ProjectCopyJournal(root, processEpoch = UUID.randomUUID().toString(),
                admissionClockNanos = { nanos }, monotonicClock = { nanos / 1_000_000L })
            val originalOwner = owner("legacy")
            val lease = journal.begin(originalOwner, "legacy.bin")
            lease.close()
            val intent = intentFile(root, lease.intent.id)
            File(intent.parentFile, "retired.json").writeText("{\"version\":1,\"intent\":" + intent.readText() +
                ",\"attempt\":null,\"receipt\":null,\"seal\":null}")
            val original = metadata(root)
            val staleAdmission = nanos
            val inspection = journal.inspect(scope, lease.intent.id)
            nanos++
            journal.acknowledge(scope, inspection.token)
            assertEquals(original, metadata(root))
            nanos++
            assertTrue(runCatching { journal.begin(originalOwner, "legacy.bin", nanos) }
                .exceptionOrNull() is ProjectCopyJournal.Blocked)
            assertTrue(runCatching { journal.begin(originalOwner.copy(runId = "queued"), "legacy.bin", staleAdmission) }
                .exceptionOrNull() is ProjectCopyJournal.Blocked)
            assertEquals(original, metadata(root))
            journal.begin(originalOwner.copy(runId = "fresh-manual-request"), "legacy.bin", nanos).close()
            nanos++
            assertTrue(runCatching { journal.begin(originalOwner.copy(runId = "another-request"), "legacy.bin", nanos) }
                .exceptionOrNull() is ProjectCopyJournal.Blocked)
            original.forEach { (path, hash) -> assertEquals(hash, ProjectCopyEvidence.sha256(root.resolve(path).readBytes())) }
            assertEquals(ProjectCopyJournal.Status.UNKNOWN,
                journal.recovery(scope).records.single { it.intent.id == lease.intent.id }.status)
            assertFalse(root.resolve("legacy.bin").exists())
        }
    }

    @Test fun malformedLegacyPreparedProofsRemainBlockedAndByteIdentical() {
        for (layout in listOf(true, false)) for (damage in listOf("unknown-key", "future-version", "different-intent")) {
            if (!layout && damage == "different-intent") continue
            withProject { root ->
                val journal = journal(root)
                val originalOwner = owner("legacy")
                val lease = journal.begin(originalOwner, "legacy.bin")
                lease.close()
                val intent = intentFile(root, lease.intent.id)
                val originalProof = "{\"version\":1,\"intent\":" + intent.readText() +
                    ",\"attempt\":null,\"receipt\":null,\"seal\":null}"
                val proof = File(intent.parentFile, "retired.json")
                proof.writeText(when (damage) {
                    "unknown-key" -> originalProof.replaceFirst("{", "{\"futureField\":true,")
                    "future-version" -> originalProof.replaceFirst("\"version\":1", "\"version\":2")
                    else -> originalProof
                })
                if (damage == "different-intent") intent.writeText(intent.readText().replace("legacy.bin", "other.bin"))
                if (!layout) Files.delete(intent.toPath())
                val original = metadata(root)
                val restarted = journal(root)
                val recovery = restarted.recovery(scope)
                assertTrue(recovery.blockingProblem != null || recovery.records.single().problem != null)
                assertTrue(runCatching { restarted.inspect(scope, lease.intent.id) }.isFailure)
                assertTrue(runCatching { restarted.begin(originalOwner.copy(runId = "later"), "legacy.bin") }.isFailure)
                assertEquals(original, metadata(root))
                assertFalse(root.resolve("legacy.bin").exists())
            }
        }
    }

    @Test fun mixedPreparedAndCommittedRecordsObeyTheSameTotalAdmissionBound() = runBlocking {
        withProject { root ->
            val journal = journal(root)
            repeat(127) { journal.begin(owner("pending-$it"), "pending-$it.bin").close() }
            commit(root, journal, "completed")
            val records = journal.recovery(scope).records
            assertEquals(127, records.count { it.status == ProjectCopyJournal.Status.PREPARED })
            assertEquals(1, records.count { it.status == ProjectCopyJournal.Status.COMMITTED })
            val original = metadata(root)
            assertTrue(runCatching { journal.begin(owner("overflow"), "overflow.bin") }.isFailure)
            assertEquals(original, metadata(root))
            assertEquals("completed payload", root.resolve("completed.bin").readText())
        }
    }

    @Test fun successfulHistoryRemainsBoundedAcrossFreshJournalInstances() = runBlocking {
        withProject { root ->
            repeat(16) { commit(root, journal(root), "completed-$it") }
            val records = journal(root).recovery(scope).records
            assertEquals(2, records.size)
            assertTrue(records.all { it.status == ProjectCopyJournal.Status.COMMITTED })
            repeat(16) { assertEquals("completed-$it payload", root.resolve("completed-$it.bin").readText()) }
        }
    }

    @Test fun sealedLegacyRetirementStillAllowsBoundedTerminalCleanup() = runBlocking {
        withProject { root ->
            val journal = journal(root)
            commit(root, journal, "completed")
            val record = journal.recovery(scope).records.single()
            val directory = intentFile(root, record.intent.id).parentFile
            File(directory, "retired.json").writeText("{\"version\":1,\"intent\":" + File(directory, "intent.json").readText() +
                ",\"attempt\":" + File(directory, "attempt.json").readText() +
                ",\"receipt\":" + File(directory, "receipt.json").readText() +
                ",\"seal\":" + File(directory, "seal.json").readText() + "}")
            assertTrue(journal(root).recovery(scope).records.isEmpty())
            commit(root, journal(root), "later")
            assertFalse(directory.exists())
            assertEquals("completed payload", root.resolve("completed.bin").readText())
        }
    }

    private fun owner(id: String) = ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.EXPLORER_IMPORT, null,
        "picker-$id", ProjectCopyJournal.explorerSourceId("content://test/$id"))

    private fun journal(root: File, history: Int = 2) = ProjectCopyJournal(root, historyLimit = history,
        processEpoch = UUID.randomUUID().toString())

    private suspend fun commit(root: File, journal: ProjectCopyJournal, id: String) {
        journal.begin(owner(id), "$id.bin").use { lease ->
            FileRepository(root, newFilePublisher = NewProjectFilePublisher.native()).writeStream("$id.bin", "$id payload".byteInputStream(), requireAbsent = true, publicationObserver = lease)
        }
    }

    private fun intentFile(root: File, id: String) = root.resolve(".droide/copy-recovery/$id/intent.json")

    private fun metadata(root: File) = root.resolve(".droide/copy-recovery").walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to ProjectCopyEvidence.sha256(it.readBytes()) }

    private inline fun withProject(block: (File) -> Unit) {
        val root = Files.createTempDirectory("prepared-recovery-test-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
