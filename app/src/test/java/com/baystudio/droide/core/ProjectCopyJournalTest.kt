package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Failure-first source coverage. The coordinator owns execution; these are not pass claims. */
class ProjectCopyJournalTest {
    private lateinit var root: File
    private lateinit var io: TestDurability
    private lateinit var journal: ProjectCopyJournal
    private val agent = ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, "session-a")
    private val explorer = ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.EXPLORER_IMPORT)
    private var now = 1000L
    private var elapsed = 1000L
    private val bytes = "intended original bytes".toByteArray()

    @Before fun setup() {
        root = Files.createTempDirectory("copy-journal-test-").toFile()
        io = TestDurability()
        journal = newJournal()
    }
    @After fun cleanup() { PathSecurity.deleteTreeNoFollow(root) }

    @Test fun intentForceFailurePreventsAdmissionAndPreservesEvidence() {
        io.failFileForce = 1
        fails { journal.begin(owner(), "asset.bin") }
        assertFalse(root.resolve("asset.bin").exists())
        assertTrue(root.resolve(".droide/copy-recovery").listFiles()!!.isNotEmpty())
    }

    @Test fun attemptForceFailureNeverReturnsPermissionToPublish() {
        val lease = journal.begin(owner(), "asset.bin")
        val prepared = preparation()
        io.failFileForce = io.fileForces + 1
        var publicationReached = false
        fails { lease.beforePublish(prepared); publicationReached = true }
        assertFalse(publicationReached)
        lease.close()
        assertEquals(ProjectCopyJournal.Status.UNKNOWN, journal.recovery(agent).records.single().status)
        assertFalse(root.resolve("asset.bin").exists())
    }

    @Test fun processRestartWithMatchingBytesRemainsUnknownWithoutExactReceipt() {
        val lease = journal.begin(owner(), "asset.bin")
        lease.beforePublish(preparation())
        root.resolve("asset.bin").writeBytes(bytes)
        lease.close()
        val restarted = newJournal(process = UUID.randomUUID().toString())
        val record = restarted.recovery(agent).records.single()
        assertEquals(ProjectCopyJournal.Status.UNKNOWN, record.status)
        assertNull(record.receipt)
        assertEquals(ProjectCopyEvidence.sha256(bytes), restarted.inspect(agent, record.intent.id).current.sha256)
        assertEquals(ProjectCopyJournal.Status.UNKNOWN, restarted.recovery(agent).records.single().status)
        fails { restarted.begin(owner(run = "next-run"), "other-name.bin") }
        assertArrayEquals(bytes, root.resolve("asset.bin").readBytes())
    }

    @Test fun completedHistoricalReceiptDoesNotReverifyOrRecreateTodayFile() {
        val record = commit()
        root.resolve("asset.bin").writeText("later user change")
        val restarted = newJournal(process = UUID.randomUUID().toString())
        val historical = restarted.recovery(agent).records.single()
        assertEquals(ProjectCopyJournal.Status.COMMITTED, historical.status)
        assertEquals(record.receipt, historical.receipt)
        val error = fails { restarted.begin(owner(), "asset.bin") }
        assertTrue(error is ProjectCopyJournal.AlreadyCommitted)
        assertEquals("later user change", root.resolve("asset.bin").readText())
        root.resolve("asset.bin").delete()
        fails { restarted.begin(owner(), "asset.bin") }
        assertFalse(root.resolve("asset.bin").exists())
    }

    @Test fun receiptWithoutSealIsUnknownEvenWhenAllVisibleBytesMatch() {
        val lease = journal.begin(owner(), "asset.bin")
        val prepared = preparation()
        lease.beforePublish(prepared)
        // Fail the receipt's parent force. The later seal must never be created.
        io.failDirectoryForce = io.directoryForces + 1
        fails { lease.onPublished(StreamPublicationReceipt(prepared, prepared.stageIdentity)) }
        lease.close()
        val dir = directory(lease.intent.id)
        assertTrue(dir.resolve("receipt.json").exists())
        assertFalse(dir.resolve("seal.json").exists())
        assertEquals(ProjectCopyJournal.Status.UNKNOWN, newJournal(process = UUID.randomUUID().toString())
            .recovery(agent).records.single().status)
    }

    @Test fun sealForceFailureKeepsTheLiveTransactionUnknown() {
        val lease = journal.begin(owner(), "asset.bin")
        val prepared = preparation()
        lease.beforePublish(prepared)
        io.failDirectoryForce = io.directoryForces + 2
        fails { lease.onPublished(StreamPublicationReceipt(prepared, prepared.stageIdentity)) }
        lease.close()
        assertNull(lease.committedReceipt)
        assertTrue(lease.isUncertain)
        assertEquals(ProjectCopyJournal.Status.UNKNOWN, newJournal().recovery(agent).records.single().status)
        // A later process can only infer the receipt/data/parent barriers causally preceding seal
        // creation. It must not infer that the seal's own force returned successfully.
        assertTrue(directory(lease.intent.id).resolve("seal.json").exists())
    }

    @Test fun wrongTransactionRootPathDigestOrCanonicalIdentityCannotBecomeReceipt() {
        val lease = journal.begin(owner(), "asset.bin")
        val prepared = preparation()
        lease.beforePublish(prepared)
        listOf(
            StreamPublicationReceipt(prepared.copy(publicationId = UUID.randomUUID().toString()), prepared.stageIdentity),
            StreamPublicationReceipt(prepared.copy(canonicalRoot = root.parent), prepared.stageIdentity),
            StreamPublicationReceipt(prepared.copy(relativePath = "other.bin"), prepared.stageIdentity),
            StreamPublicationReceipt(prepared.copy(sha256 = "0".repeat(64)), prepared.stageIdentity),
            StreamPublicationReceipt(prepared, "foreign-inode"),
        ).forEach { wrong -> fails { lease.onPublished(wrong) } }
        lease.close()
        assertEquals(ProjectCopyJournal.Status.UNKNOWN, journal.recovery(agent).records.single().status)
        assertFalse(directory(lease.intent.id).resolve("receipt.json").exists())
    }

    @Test fun mismatchedPreparationIsRejectedBeforeAttemptMarker() {
        val lease = journal.begin(owner(), "asset.bin")
        fails { lease.beforePublish(preparation().copy(rootIdentity = "different-project")) }
        assertFalse(directory(lease.intent.id).resolve("attempt.json").exists())
        lease.close()
    }

    @Test fun anotherRunOrConversationCannotAcquireActiveDestination() {
        val lease = journal.begin(owner(), "asset.bin")
        fails { journal.begin(owner(run = "run-b"), "other.bin") }
        fails { journal.begin(owner(session = "session-b", source = "another-media"), "asset.bin") }
        journal.begin(owner(session = "session-b", source = "another-media"), "safe.bin").close()
        lease.close()
    }

    @Test fun knownUncertaintyBlocksOnlySameSourceOrOverlappingDestination() {
        unknown()
        fails { journal.begin(owner(run = "run-b"), "renamed.bin") }
        fails { journal.begin(owner(run = "run-b", source = "other-media"), "asset.bin") }
        fails { journal.begin(owner(run = "run-b", source = "other-media"), "asset.bin/child") }
        journal.begin(owner(run = "run-b", source = "other-media"), "safe.bin").close()
    }

    @Test fun explorerRecoveryIsProjectOwnedAndNeverVisibleInAgentConversation() {
        val lease = journal.begin(explorerOwner(), "asset.bin")
        lease.beforePublish(preparation())
        lease.close()
        assertTrue(journal.recovery(agent).records.isEmpty())
        assertEquals(1, newJournal(process = UUID.randomUUID().toString()).recovery(explorer).records.size)
        fails { journal.inspect(agent, lease.intent.id) }
        fails { journal.begin(owner(source = "another-media"), "asset.bin") }
        journal.begin(owner(source = "another-media"), "safe.bin").close()
        assertFalse(directory(lease.intent.id).resolve("intent.json").readText().contains("content://"))
    }

    @Test fun knownIntentWithCorruptReceiptPreservesBytesAndIndependentDestinations() {
        val lease = unknown()
        directory(lease.intent.id).resolve("receipt.json").writeText("{broken")
        val recovery = journal.recovery(agent)
        assertNull(recovery.blockingProblem)
        assertNotNull(recovery.records.single().problem)
        fails { journal.begin(owner(run = "run-b"), "new.bin") }
        journal.begin(owner(run = "run-b", source = "other-media"), "safe.bin").close()
        assertEquals("{broken", directory(lease.intent.id).resolve("receipt.json").readText())
        fails { journal.inspect(agent, lease.intent.id) }
    }

    @Test fun unknownIntentOwnershipBlocksWholeProjectWithoutDeletingAnything() {
        val lease = unknown()
        val intent = directory(lease.intent.id).resolve("intent.json")
        intent.writeText("{unknown ownership")
        assertNotNull(journal.recovery(agent).blockingProblem)
        fails { journal.begin(owner(source = "unrelated"), "safe.bin") }
        assertEquals("{unknown ownership", intent.readText())
    }

    @Test fun duplicateAndUnknownKeysAreNotAcceptedAsJournalTruth() {
        val lease = unknown()
        val attempt = directory(lease.intent.id).resolve("attempt.json")
        val original = attempt.readText()
        attempt.writeText(original.replaceFirst("{", "{\"version\":1,"))
        assertNotNull(journal.recovery(agent).records.single().problem)
        attempt.writeText(original.replaceFirst("{", "{\"futureField\":true,"))
        assertNotNull(journal.recovery(agent).records.single().problem)
        attempt.writeText(original.replace("\"version\":1", "\"version\":0"))
        assertNotNull(journal.recovery(agent).records.single().problem)
    }

    @Test fun oneValidInspectionOnlyPermitsNewManualRunAndRetainsUnknownHistory() {
        val old = unknown()
        val inspection = journal.inspect(agent, old.intent.id)
        journal.acknowledge(agent, inspection.token)
        assertFalse(root.resolve("asset.bin").exists())
        elapsed++
        fails { journal.begin(owner(), "asset.bin", elapsed * 1_000_000L) } // Original run cannot auto-retry.
        elapsed++
        val retry = journal.begin(owner(run = "new-manual-run"), "asset.bin", elapsed * 1_000_000L)
        retry.beforePublish(preparation())
        retry.close()
        assertEquals(2, journal.recovery(agent).records.count { it.status == ProjectCopyJournal.Status.UNKNOWN })
        fails { journal.begin(owner(run = "third-run"), "asset.bin") }
    }

    @Test fun alreadyRunningRequestCannotConsumeLaterPermission() {
        val old = unknown()
        val admitted = elapsed * 1_000_000L
        elapsed++
        journal.acknowledge(agent, journal.inspect(agent, old.intent.id).token)
        elapsed++
        fails { journal.begin(owner(run = "already-active"), "asset.bin", admitted) }
        assertEquals(1, journal.recovery(agent).records.size)
    }

    @Test fun queuedBeforeGrantStartingAfterGrantCannotConsumePermission() {
        val old = unknown()
        val acceptedBeforeQueue = elapsed * 1_000_000L
        elapsed++
        journal.acknowledge(agent, journal.inspect(agent, old.intent.id).token)
        elapsed += 100
        fails { journal.begin(owner(run = "starts-after-grant"), "asset.bin", acceptedBeforeQueue) }
        fails { journal.begin(owner(run = "missing-original-admission"), "asset.bin") }
        assertEquals(1, journal.recovery(agent).records.size)
    }

    @Test fun onlyFreshUserAdmissionAfterGrantCanConsumePermissionOnce() {
        val old = unknown()
        journal.acknowledge(agent, journal.inspect(agent, old.intent.id).token)
        // Equal-clock or future untrusted values cannot establish a subsequent accepted request.
        fails { journal.begin(owner(run = "same-clock"), "asset.bin", elapsed * 1_000_000L) }
        fails { journal.begin(owner(run = "future-clock"), "asset.bin", (elapsed + 1) * 1_000_000L) }
        elapsed++
        journal.begin(owner(run = "fresh-user-request"), "asset.bin", elapsed * 1_000_000L).close()
        elapsed++
        fails { journal.begin(owner(run = "another-request"), "asset.bin", elapsed * 1_000_000L) }
        assertEquals(ProjectCopyJournal.Status.UNKNOWN,
            journal.recovery(agent).records.single { it.intent.id == old.intent.id }.status)
    }

    @Test fun destinationMutationBetweenInspectAndAcknowledgeExpiresConfirmation() {
        val old = unknown()
        val inspection = journal.inspect(agent, old.intent.id)
        root.resolve("asset.bin").writeText("new external file")
        fails { journal.acknowledge(agent, inspection.token) }
        assertEquals("new external file", root.resolve("asset.bin").readText())
    }

    @Test fun destinationMutationDuringNewCopyInvalidatesPermissionBeforePublication() {
        val old = unknown()
        journal.acknowledge(agent, journal.inspect(agent, old.intent.id).token)
        elapsed++
        val retry = journal.begin(owner(run = "new-manual-run"), "asset.bin", elapsed * 1_000_000L)
        root.resolve("asset.bin").writeText("external arrival")
        fails { retry.beforePublish(preparation()) }
        retry.close()
        assertFalse(directory(retry.intent.id).resolve("attempt.json").exists())
        assertEquals("external arrival", root.resolve("asset.bin").readText())
    }

    @Test fun replacedParentInvalidatesInspectionEvenWhenTargetRemainsAbsent() {
        root.resolve("assets").mkdir()
        val lease = journal.begin(owner(), "assets/item.bin")
        lease.beforePublish(preparation(path = "assets/item.bin")); lease.close()
        val inspection = journal.inspect(agent, lease.intent.id)
        Files.move(root.resolve("assets").toPath(), root.resolve("old-assets").toPath())
        root.resolve("assets").mkdir()
        fails { journal.acknowledge(agent, inspection.token) }
    }

    @Test fun expiredOrChangedJournalInspectionCannotAuthorizeNewCopy() {
        val old = unknown()
        val first = journal.inspect(agent, old.intent.id)
        elapsed += 5L * 60 * 1000 + 1
        fails { journal.acknowledge(agent, first.token) }
        val second = journal.inspect(agent, old.intent.id)
        directory(old.intent.id).resolve("receipt.json").writeText("incomplete")
        fails { journal.acknowledge(agent, second.token) }
        assertFalse(root.resolve("asset.bin").exists())
    }

    @Test fun processRestartDoesNotRetainManualPermission() {
        val old = unknown()
        journal.acknowledge(agent, journal.inspect(agent, old.intent.id).token)
        elapsed++
        fails { newJournal(process = UUID.randomUUID().toString()).begin(owner(run = "new-run"), "asset.bin", elapsed * 1_000_000L) }
    }

    @Test fun successfulRetentionIsBoundedAndUncertainRecordIsNeverRetired() {
        journal = newJournal(history = 2)
        val old = unknown()
        repeat(5) { number ->
            now++
            commit(owner(source = "media-$number", run = "run-$number"), "asset-$number.bin")
        }
        val records = journal.recovery(agent).records
        assertEquals(2, records.count { it.status == ProjectCopyJournal.Status.COMMITTED })
        assertEquals(old.intent.id, records.single { it.status == ProjectCopyJournal.Status.UNKNOWN }.intent.id)
        assertTrue(directory(old.intent.id).resolve("attempt.json").exists())
    }

    @Test fun incompleteTerminalRetirementProofCannotEraseUnknownAttempt() {
        val old = unknown()
        directory(old.intent.id).resolve("retired.json").writeText("{incomplete")
        repeat(3) { number -> journal.begin(owner(source = "other-$number", run = "run-$number"), "other-$number.bin").close() }
        assertTrue(directory(old.intent.id).resolve("attempt.json").exists())
        assertEquals(ProjectCopyJournal.Status.UNKNOWN, journal.recovery(agent).records.single { it.intent.id == old.intent.id }.status)
    }

    @Test fun corruptRemainingPartsOfRetiredRecordStayVisibleAndBlockTheirScope() {
        val committed = commit()
        val dir = directory(committed.intent.id)
        val proof = "{\"version\":1,\"intent\":" + dir.resolve("intent.json").readText() +
            ",\"attempt\":" + dir.resolve("attempt.json").readText() +
            ",\"receipt\":" + dir.resolve("receipt.json").readText() +
            ",\"seal\":" + dir.resolve("seal.json").readText() + "}"
        dir.resolve("retired.json").writeText(proof)
        dir.resolve("receipt.json").writeText("corrupt remaining receipt")
        val record = journal.recovery(agent).records.single()
        assertEquals(ProjectCopyJournal.Status.UNKNOWN, record.status)
        assertNotNull(record.problem)
        fails { journal.begin(owner(run = "next-run"), "elsewhere.bin") }
        journal.begin(owner(run = "next-run", source = "unrelated"), "safe.bin").close()
        assertEquals("corrupt remaining receipt", dir.resolve("receipt.json").readText())
    }

    @Test fun onlyExplicitNoSuchFileMeansAbsentAndAccessErrorsPropagate() {
        assertNull(ProjectCopyEvidence.identityOrAbsent(root.resolve("missing")) {
            throw java.nio.file.NoSuchFileException(it.path)
        })
        val denied = fails { ProjectCopyEvidence.identityOrAbsent(root.resolve("unknown")) {
            throw java.nio.file.AccessDeniedException(it.path)
        } }
        assertTrue(denied is java.nio.file.AccessDeniedException)
        val ioError = fails { ProjectCopyEvidence.identityOrAbsent(root.resolve("unknown")) {
            throw IOException("provider identity unavailable")
        } }
        assertTrue(ioError is IOException)
    }

    @Test fun replacingStorageOrTransactionDirectoriesInvalidatesPermissionsAcrossFreshInstances() {
        for (replacement in listOf("metadata", "storage", "transaction")) {
            val old = unknown()
            journal.acknowledge(agent, journal.inspect(agent, old.intent.id).token)
            val original = when (replacement) {
                "metadata" -> root.resolve(".droide")
                "storage" -> root.resolve(".droide/copy-recovery")
                else -> directory(old.intent.id)
            }
            val backup = root.resolve("replaced-$replacement")
            Files.move(original.toPath(), backup.toPath())
            original.mkdir()
            backup.listFiles()!!.forEach { child -> Files.move(child.toPath(), original.resolve(child.name).toPath()) }
            journal = newJournal()
            elapsed++
            fails { journal.begin(owner(run = "new-$replacement"), "new-$replacement.bin", elapsed * 1_000_000L) }

            if (replacement != "transaction") {
                PathSecurity.deleteTreeNoFollow(root)
                root = Files.createTempDirectory("copy-journal-test-").toFile()
                journal = newJournal()
            }
        }
    }

    @Test fun wallClockRollbackDoesNotExtendTheElapsedApprovalLifetime() {
        val old = unknown()
        val inspection = journal.inspect(agent, old.intent.id)
        elapsed += 5L * 60 * 1000 + 1
        now = 1000L // A wall-clock correction cannot revive the elapsed deadline.
        fails { journal.acknowledge(agent, inspection.token) }
    }

    @Test fun retryAfterEachSetupForceFailureReestablishesBothAncestorBarriers() {
        for (failedBarrier in 1..2) {
            io.failDirectoryForce = failedBarrier
            fails { journal.begin(owner(), "asset.bin") }
            assertFalse(root.resolve("asset.bin").exists())
            val priorCalls = io.forcedDirectories.size
            io.failDirectoryForce = null
            journal.begin(owner(run = "retry-setup"), "asset.bin").close()
            val retried = io.forcedDirectories.drop(priorCalls)
            assertEquals(root.canonicalPath, retried[0])
            assertEquals(root.resolve(".droide").canonicalPath, retried[1])
            if (failedBarrier == 1) {
                PathSecurity.deleteTreeNoFollow(root)
                root = Files.createTempDirectory("copy-journal-test-").toFile()
                io = TestDurability()
                journal = newJournal()
            }
        }
    }

    @Test fun symlinkDestinationIsNeverReadOrAcknowledged() {
        val old = unknown()
        val outside = Files.createTempFile("copy-outside-", ".bin").toFile()
        try {
            outside.writeText("private external data")
            Files.createSymbolicLink(root.resolve("asset.bin").toPath(), outside.toPath())
            fails { journal.inspect(agent, old.intent.id) }
            assertEquals("private external data", outside.readText())
        } finally { outside.delete() }
    }

    @Test fun replacedProjectAtSamePathIsNotTheSameRecoveryOwner() {
        val old = unknown()
        val moved = File(root.parentFile, root.name + "-old")
        Files.move(root.toPath(), moved.toPath(), StandardCopyOption.ATOMIC_MOVE)
        try {
            root.mkdir()
            fails { journal.begin(owner(run = "new-run", source = "other"), "safe.bin") }
            // Copying old metadata into a new root does not transfer root identity authority.
            moved.resolve(".droide").copyRecursively(root.resolve(".droide"))
            assertNotNull(newJournal(process = UUID.randomUUID().toString()).recovery(agent).blockingProblem)
            assertTrue(moved.resolve(".droide/copy-recovery/${old.intent.id}/attempt.json").exists())
        } finally { PathSecurity.deleteTreeNoFollow(moved) }
    }

    private fun unknown(): ProjectCopyJournal.Lease {
        val lease = journal.begin(owner(), "asset.bin")
        lease.beforePublish(preparation())
        lease.close()
        return lease
    }
    private fun commit(copyOwner: ProjectCopyJournal.Owner = owner(), path: String = "asset.bin"): ProjectCopyJournal.Record {
        val lease = journal.begin(copyOwner, path)
        val prepared = preparation(path)
        lease.beforePublish(prepared)
        root.resolve(path).parentFile.mkdirs()
        root.resolve(path).writeBytes(bytes)
        lease.onPublished(StreamPublicationReceipt(prepared, prepared.stageIdentity))
        lease.close()
        return journal.recovery(ProjectCopyJournal.Scope(copyOwner.kind, copyOwner.sessionId)).records.single { it.intent.id == lease.intent.id }
    }
    private fun owner(session: String = "session-a", run: String = "run-a", source: String = "media-a") =
        ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.AGENT_MEDIA, session, run, source)
    private fun explorerOwner() = ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.EXPLORER_IMPORT, null, "picker-a",
        ProjectCopyJournal.explorerSourceId("content://provider/picked?secret=not-persisted"))
    private fun preparation(path: String = "asset.bin") = StreamPublicationPreparation(UUID.randomUUID().toString(),
        root.canonicalPath, ProjectCopyEvidence.root(root).second, path, bytes.size.toLong(), ProjectCopyEvidence.sha256(bytes), "stage-inode-test")
    private fun directory(id: String) = root.resolve(".droide/copy-recovery/$id")
    private fun newJournal(process: String = "test-process", history: Int = 48) =
        ProjectCopyJournal(root, clock = { now }, durability = io, historyLimit = history, processEpoch = process, monotonicClock = { elapsed }, admissionClockNanos = { elapsed * 1_000_000L })
    private fun fails(block: () -> Unit): Throwable {
        try { block() } catch (failure: Exception) { return failure }
        throw AssertionError("Expected a conservative refusal")
    }
    private class TestDurability : ProjectCopyJournal.Durability {
        var fileForces = 0
        var directoryForces = 0
        var failFileForce: Int? = null
        var failDirectoryForce: Int? = null
        val forcedDirectories = mutableListOf<String>()
        override fun forceFile(channel: FileChannel) {
            fileForces++
            if (fileForces == failFileForce) throw IOException("injected file force failure")
            channel.force(true)
        }
        override fun forceDirectory(directory: File) {
            directoryForces++
            forcedDirectories += directory.canonicalPath
            if (directoryForces == failDirectoryForce) throw IOException("injected parent force failure")
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
