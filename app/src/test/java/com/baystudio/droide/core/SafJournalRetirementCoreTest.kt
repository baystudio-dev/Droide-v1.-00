package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Tiny fixture-owned host files and injected boundaries. No provider/device/process execution. */
class SafJournalRetirementCoreTest {
    private val roots = arrayListOf<File>()
    private val sentinel = byteArrayOf(0, -1, 42, 10)
    @After fun cleanup() { roots.asReversed().forEach { PathSecurity.deleteTreeNoFollow(it) } }

    @Test(timeout = 30_000) fun oldEnvelopeBytesOmitTheNewOptionalBinding() {
        val raw = """{"version":2,"id":"00000000-0000-0000-0000-000000000001","anchorDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","directoryKey":"key","request":{"kind":"WRITE","paths":["x"]},"scopes":["x"],"reservation":{"actions":0,"metadataBytes":4096,"payloadBytes":4096,"indexBytes":4096,"evidenceFiles":0}}""".toByteArray()
        val decoded = SafJournalCodec.decode(SafOperationEnvelope.serializer(), raw)
        assertNull(decoded.headDigest)
        assertArrayEquals(raw, SafJournalCodec.encode(SafOperationEnvelope.serializer(), decoded))
    }

    @Test(timeout = 30_000) fun repeatedSmallSuccessesAdvanceExactHeadAndCoveredTailWithoutReplayingIt() {
        val f = Fixture()
        repeat(7) { ordinal ->
            f.commit("value-$ordinal")
            val reopened = f.reopen()
            val trusted = SafDurableManifestCommit(reopened).trusted()
            assertEquals((ordinal + 1).toLong(), trusted.revision)
            assertArrayEquals(f.manifest.readBytes(), trusted.bytes)
            assertTrue(reopened.summaries().all { it.committed })
            assertTrue(reopened.summaries().size <= 2)
            f.sentinel()
        }
        val state = f.head()
        assertTrue(state.checkpoint.generation >= 2)
        assertTrue(state.outstanding.isEmpty())
        assertEquals(0, state.charge.operations)
        assertEquals(state.identity.bytes, state.fileCharge.metadata + state.fileCharge.payload)
        assertEquals(state.fileCharge.metadata, state.fileCharge.index)
        assertTrue(state.fileCharge.metadata <= f.limits.recordBytes + 8)
        assertEquals(7, f.manifestMoves)
    }

    @Test(timeout = 30_000) fun checkpointForceFailureRetainsEverySelectedByteAndColdBeginResumes() {
        for (failParent in listOf(false, true)) {
        val f = Fixture()
        val first = f.commit("a")
        f.commit("b")
        val retained = f.snapshot(first)
        val visible = f.manifest.readBytes()
        f.failFile = { !failParent && it.name == SafJournalRetirement.CURRENT }
        f.failDirectory = { failParent && it == f.directory && f.directory.resolve(SafJournalRetirement.CURRENT).exists() }
        assertTrue(runCatching { f.begin("next") }.isFailure)
        assertEquals(0, f.unlinks.size)
        f.assertSnapshot(first, retained)
        assertArrayEquals(visible, f.manifest.readBytes())
        assertTrue(f.directory.resolve(SafJournalRetirement.CURRENT).isFile)
        f.failFile = { false }
        f.failDirectory = { false }
        val before = f.head()
        assertEquals(listOf(first.id), before.outstanding.map { it.id })
        val incoming = f.begin("next")
        assertFalse(f.directory.resolve(first.id).exists())
        assertEquals(before.identity, f.head().identity)
        assertEquals(before.identity.sha256, incoming.envelope.headDigest)
        assertArrayEquals(visible, SafDurableManifestCommit(f.reopen()).trusted().bytes)
        f.sentinel()
        }
    }

    @Test(timeout = 30_000) fun failedCandidateForceNeverAuthorizesMissingEvidenceOrCleanup() {
        val f = Fixture()
        val first = f.commit("a")
        f.commit("b")
        val retained = f.snapshot(first)
        f.failFile = { it.name == SafJournalRetirement.CANDIDATE }
        assertTrue(runCatching { f.begin("next") }.isFailure)
        f.failFile = { false }
        assertTrue(f.directory.resolve(SafJournalRetirement.CANDIDATE).isFile)
        assertFalse(f.directory.resolve(SafJournalRetirement.CURRENT).exists())
        assertTrue(runCatching { f.reopen().summaries() }.isFailure)
        f.assertSnapshot(first, retained)
        assertTrue(f.unlinks.isEmpty())
        f.sentinel()
    }

    @Test(timeout = 30_000) fun failedAtomicCheckpointReplacementKeepsCandidateAndAllOriginalEvidence() {
        val f = Fixture()
        val first = f.commit("a"); f.commit("b")
        val retained = f.snapshot(first)
        f.failCheckpointMove = true
        assertTrue(runCatching { f.begin("next") }.isFailure)
        assertTrue(f.directory.resolve(SafJournalRetirement.CANDIDATE).isFile)
        assertFalse(f.directory.resolve(SafJournalRetirement.CURRENT).exists())
        f.assertSnapshot(first, retained)
        assertTrue(f.unlinks.isEmpty())
        f.sentinel()
    }

    @Test(timeout = 30_000) fun cancellationDuringReceiptCleanupLeavesDetachedAuthorityForColdResume() {
        val f = Fixture()
        val first = f.commit("a"); f.commit("b")
        var cancelled = false
        f.afterRemove = { if (it.name.endsWith(".bin")) cancelled = true }
        val failure = runCatching { f.reopen().begin(SafOperationRequest(SafOperationKind.WRITE, listOf("next")),
            listOf("next"), f.reservation, checkActive = {
                if (cancelled) throw java.util.concurrent.CancellationException("cancel exact cleanup")
            }) }.exceptionOrNull()
        assertTrue(failure is java.util.concurrent.CancellationException)
        assertTrue(f.directory.resolve(first.id).resolve("operation.json").isFile)
        assertEquals(1, f.head().outstanding.size)
        f.afterRemove = {}
        f.begin("next")
        assertFalse(f.directory.resolve(first.id).exists())
        f.sentinel()
    }

    @Test(timeout = 30_000) fun retainedBaselineSameInodeCorruptionStillBlocksLiveAdmissionAfterCheckpoint() {
        val f = Fixture()
        repeat(3) { f.commit("a-$it") }
        val live = f.reopen()
        val baseline = f.directory.resolve("baseline.bin")
        val bytes = baseline.readBytes()
        val index = bytes.indexOf('0'.code.toByte()).let { if (it >= 0) it else 0 }
        bytes[index] = (bytes[index].toInt() xor 1).toByte()
        baseline.writeBytes(bytes)
        val beforeUnlinks = f.unlinks.size
        assertTrue(runCatching { live.begin(SafOperationRequest(SafOperationKind.WRITE, listOf("new")),
            listOf("new"), f.reservation) }.isFailure)
        assertEquals(beforeUnlinks, f.unlinks.size)
        f.sentinel()
    }

    @Test(timeout = 30_000) fun partialMetadataCleanupUsesDetachedReceiptAndKeepsFullChargeUntilDirectoryAbsence() {
        val f = Fixture()
        val first = f.commit("a")
        f.commit("b")
        val original = SafRetirementCharge(1, first.metadataBytes, first.indexBytes, first.payloadBytes)
        f.failRemove = { it.parentFile?.name == first.id && it.name == "operation.json" }
        assertTrue(runCatching { f.begin("next") }.isFailure)
        assertEquals(setOf("operation.json"), f.directory.resolve(first.id).list()!!.toSet())
        val head = f.head()
        assertEquals(original, head.outstanding.single().charge)
        assertEquals(original.metadata + head.fileCharge.metadata, head.charge.metadata)
        assertEquals(original.payload + head.fileCharge.payload, head.charge.payload)
        assertEquals(listOf("b"), f.reopen().summaries().single().scopes)
        val attempted = f.unlinks.map { it.name }
        val firstMetadata = attempted.indexOfFirst { !it.endsWith(".bin") }
        assertTrue(firstMetadata > 0)
        assertTrue(attempted.drop(firstMetadata).none { it.endsWith(".bin") })
        assertEquals("operation.json", attempted.last())
        f.failRemove = { false }
        f.begin("next")
        assertFalse(f.directory.resolve(first.id).exists())
        assertTrue(f.head().outstanding.isEmpty())
        f.sentinel()
    }

    @Test(timeout = 30_000) fun unlinkReturnedButParentForceFailedRequiresColdAbsenceBarrierBeforeRelease() {
        val f = Fixture()
        val first = f.commit("a")
        f.commit("b")
        var removedRoot = false
        f.afterRemove = { if (it == f.directory.resolve(first.id)) removedRoot = true }
        f.failDirectory = { removedRoot && it == f.directory }
        assertTrue(runCatching { f.begin("next") }.isFailure)
        assertTrue(removedRoot)
        assertFalse(f.directory.resolve(first.id).exists())
        assertTrue(runCatching { f.head() }.isFailure)
        f.failDirectory = { false }
        assertTrue(f.head().outstanding.isEmpty())
        f.begin("next")
        f.sentinel()
    }

    @Test(timeout = 30_000) fun unknownAndRecreatedPrivateNamesBlockCleanupWithoutTouchingThem() {
        for (replaceDirectory in listOf(false, true)) {
            val f = Fixture()
            val first = f.commit("a")
            f.commit("b")
            f.failFile = { it.name == SafJournalRetirement.CURRENT }
            assertTrue(runCatching { f.begin("next") }.isFailure)
            f.failFile = { false }
            val owned = f.directory.resolve(first.id)
            val displaced = f.root.resolve("displaced")
            if (replaceDirectory) {
                Files.move(owned.toPath(), displaced.toPath())
                check(owned.mkdir())
            }
            val foreign = owned.resolve("foreign.bin").apply { writeBytes(sentinel) }
            assertTrue(runCatching { f.begin("next") }.isFailure)
            assertArrayEquals(sentinel, foreign.readBytes())
            assertTrue(f.unlinks.isEmpty())
            if (replaceDirectory) assertTrue(displaced.resolve("operation.json").isFile)
            f.sentinel()
        }
    }

    @Test(timeout = 30_000) fun projectedIncomingCapacityTriggersBeforeAllocationWithoutFixedReserves() {
        val f = Fixture(operations = 100)
        val first = f.commit("a")
        f.commit("b")
        assertFalse(f.directory.resolve(SafJournalRetirement.CURRENT).exists())
        val admitted = f.reopen().recoverAll()
        val currentPayload = f.journal.anchor.baseline.identity.bytes + admitted.sumOf { it.payloadBytes }
        val tighter = f.limits.copy(aggregatePayloadBytes = currentPayload + f.reservation.payloadBytes - 1)
        f.journal = f.reopen(tighter)
        val incoming = f.journal.begin(SafOperationRequest(SafOperationKind.WRITE, listOf("new")), listOf("new"), f.reservation)
        assertNotNull(incoming.envelope.headDigest)
        assertFalse(f.directory.resolve(first.id).exists())
        assertTrue(f.directory.resolve(incoming.id).isDirectory)
        f.sentinel()
    }

    @Test(timeout = 30_000) fun exactTransitionHeadroomFailureHasNoAllocationOrUnlink() {
        val f = Fixture()
        f.commit("a"); f.commit("b")
        val all = f.reopen().recoverAll()
        val full = f.limits.copy(indexBytes = f.journal.anchorIdentity.bytes + all.sumOf { it.indexBytes })
        val names = f.directory.list()!!.toSet()
        val visible = f.manifest.readBytes()
        val journal = f.reopen(full)
        assertTrue(runCatching { journal.begin(SafOperationRequest(SafOperationKind.WRITE, listOf("new")), listOf("new"),
            f.reservation.copy(indexBytes = minOf(f.reservation.indexBytes, full.indexBytes))) }.isFailure)
        assertEquals(names, f.directory.list()!!.toSet())
        assertArrayEquals(visible, f.manifest.readBytes())
        assertTrue(f.unlinks.isEmpty())
        f.sentinel()
    }

    @Test(timeout = 30_000) fun historicalPendingParentIsPinnedButCannotRetryAfterCheckpointAdvance() {
        val f = Fixture(operations = 6)
        val pending = f.begin("pending")
        val publisher = SafDurableManifestCommit(f.journal)
        publisher.plan(pending, mapOf("pending" to f.fingerprint))
        f.failFile = { it == f.manifest }
        assertTrue(runCatching { publisher.publish(pending) }.isFailure)
        f.failFile = { false }
        val retained = f.snapshot(pending)
        repeat(3) { f.commit("committed-$it") }
        f.begin("new")
        val state = f.head()
        assertTrue(state.checkpoint.pins.any { it.id == pending.id && it.parentRevision == 0L })
        val reopened = f.reopen()
        assertEquals(4L, SafDurableManifestCommit(reopened).trusted().revision)
        val recovered = reopened.recover(pending.id)
        val moves = f.manifestMoves
        assertTrue(runCatching { SafDurableManifestCommit(reopened).retryExactBarrier(recovered) }.isFailure)
        assertEquals(moves, f.manifestMoves)
        f.assertSnapshot(pending, retained)
        f.sentinel()
    }

    @Test(timeout = 30_000) fun higherUncommittedRevisionRemainsHighwaterAfterAcknowledgedHistoryRetires() {
        val f = Fixture(operations = 100)
        repeat(3) { f.commit("committed-$it") }
        val pending = f.begin("pending")
        SafDurableManifestCommit(f.journal).plan(pending, mapOf("pending" to f.fingerprint))
        assertEquals(4L, f.journal.read(pending, "manifest-plan.json", SafManifestPlan.serializer()).revision)
        val journal = f.reopen(f.limits.copy(operations = 4))
        val next = journal.begin(SafOperationRequest(SafOperationKind.WRITE, listOf("next")), listOf("next"), f.reservation)
        val head = SafJournalRetirement(journal).load {}!!
        assertEquals(3L, head.checkpoint.revision)
        assertEquals(4L, head.checkpoint.highwater)
        assertTrue(head.checkpoint.pins.any { it.id == pending.id && it.planDigest != null && it.committedDigest == null })
        SafDurableManifestCommit(journal).plan(next, mapOf("next" to f.fingerprint))
        val plan = journal.read(next, "manifest-plan.json", SafManifestPlan.serializer())
        assertEquals(5L, plan.revision)
        assertEquals(3L, plan.parentRevision)
        assertTrue(SafDurableManifestCommit(journal).publish(next).acknowledged)
        assertEquals(5L, SafDurableManifestCommit(f.reopen()).trusted().revision)
        f.sentinel()
    }

    @Test(timeout = 30_000) fun coldReceiptRejectsChangedPrivateBytesInodeAndSymlinkBeforeUnlink() {
        for (mode in listOf("bytes", "inode", "symlink")) {
            val f = Fixture()
            val first = f.commit("a"); f.commit("b")
            f.failFile = { it.name == SafJournalRetirement.CURRENT }
            assertTrue(runCatching { f.begin("next") }.isFailure)
            f.failFile = { false }
            val file = f.directory.resolve(first.id).resolve("planned.bin")
            val original = file.readBytes()
            val displaced = f.root.resolve("displaced-payload")
            if (mode == "bytes") file.writeBytes(original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            else {
                Files.move(file.toPath(), displaced.toPath())
                if (mode == "inode") file.writeBytes(original)
                else Files.createSymbolicLink(file.toPath(), displaced.toPath())
            }
            assertTrue(runCatching { f.begin("next") }.isFailure)
            assertTrue(f.unlinks.isEmpty())
            if (mode != "bytes") assertArrayEquals(original, displaced.readBytes())
            if (mode == "symlink") assertTrue(Files.isSymbolicLink(file.toPath()))
            f.sentinel()
        }
    }

    @Test(timeout = 30_000) fun cleanupReadsEachRemainingBodyOnceAndLaterColdAdmissionNeverReadsRetiredBodies() {
        val f = Fixture()
        val first = f.commit("a"); f.commit("b")
        val root = f.directory.resolve(first.id)
        val bodies = root.listFiles()!!.filter { it.name.endsWith(".bin") }.toSet()
        var inCleanup = false
        val during = arrayListOf<File>()
        f.afterRemove = { file ->
            if (file in bodies) inCleanup = true
            if (file == root) inCleanup = false
        }
        f.beforeRead = { if (inCleanup && it in bodies) during += it }
        f.begin("next")
        assertEquals(bodies.size - 1, during.size)
        assertTrue(during.groupingBy { it }.eachCount().values.all { it == 1 })
        assertFalse(root.exists())
        f.beforeRead = {}
        f.reads.clear()
        val reopened = f.reopen()
        reopened.summaries()
        SafDurableManifestCommit(reopened).trusted()
        assertTrue(f.reads.none { it.parentFile == root })
        f.sentinel()
    }

    @Test(timeout = 30_000) fun completedReceiptParentBarriersAreDeduplicatedWithinColdAdmission() {
        val f = Fixture(operations = 6)
        repeat(3) { f.commit("value-$it") }
        f.begin("new")
        assertEquals(2, f.head().checkpoint.receipts.size)
        f.directories.clear()
        val state = SafJournalRetirement(f.reopen()).load {}!!
        assertTrue(state.outstanding.isEmpty())
        assertEquals(2, f.directories.count { it == f.directory })
        f.sentinel()
    }

    @Test(timeout = 30_000) fun protectedDeleteCustodyIsRetainedInitiallyAndRetiredOnlyWithExactOwnedInventory(): Unit = runBlocking {
        for (mode in listOf("normal", "unknown", "partial", "replaced", "cold-bytes", "cold-inode", "cold-symlink", "cold-parent")) {
            val f = Fixture()
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val source = f.workspace.resolve("deleted").apply { check(mkdir()) }
                source.resolve("original.bin").writeBytes(sentinel)
                val operation = f.begin("deleted", SafOperationKind.DELETE)
                val custody = f.workspace.resolve(".droide/saf-delete-custody/${operation.id}")
                check(custody.mkdirs())
                val binding = SafDeleteCustody(".droide/saf-delete-custody/${operation.id}", f.storage.key(custody, true))
                val preparation = WorkspaceLocalPreparation(WorkspaceLocalEffect.DELETE_STAGE,
                    "${binding.directory}/original", "deleted", f.storage.key(source, true))
                val adapter = SafLocalEffects(guard, f.journal, operation) { guard.requireRoot(f.workspace) }
                val effect = adapter.prepare(preparation, binding)
                adapter.beforeAttempt(effect)
                val destination = custody.resolve("original")
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                adapter.returned(effect, WorkspaceLocalReceipt(preparation, true, f.storage.key(destination, true)))
                adapter.verify(effect)
                val publisher = SafDurableManifestCommit(f.journal)
                publisher.plan(operation, emptyMap())
                assertTrue(publisher.publish(operation).acknowledged)
                val key = f.storage.key(destination.resolve("original.bin"), false)
                f.commit("later")
                assertEquals(key, f.storage.key(destination.resolve("original.bin"), false))
                if (mode == "unknown") destination.resolve("foreign.bin").writeBytes(sentinel)
                if (mode == "partial" || mode == "replaced")
                    f.failRemove = { it == destination }
                if (mode.startsWith("cold-")) f.failFile = { it.name == SafJournalRetirement.CURRENT }
                val failure = runCatching { f.begin("next") }.exceptionOrNull()
                if (mode == "unknown") {
                    assertNotNull(failure)
                    assertEquals(key, f.storage.key(destination.resolve("original.bin"), false))
                    assertArrayEquals(sentinel, destination.resolve("foreign.bin").readBytes())
                    assertTrue(f.unlinks.isEmpty())
                } else if (mode.startsWith("cold-")) {
                    assertNotNull(failure)
                    f.failFile = { false }
                    val file = destination.resolve("original.bin")
                    val displaced = f.root.resolve("displaced-original")
                    when (mode) {
                        "cold-bytes" -> file.writeBytes(sentinel.copyOf().also { it[0] = 1 })
                        "cold-inode", "cold-symlink" -> {
                            Files.move(file.toPath(), displaced.toPath())
                            if (mode == "cold-inode") file.writeBytes(sentinel)
                            else Files.createSymbolicLink(file.toPath(), displaced.toPath())
                        }
                        "cold-parent" -> {
                            val parent = custody.parentFile!!
                            Files.move(parent.toPath(), displaced.toPath())
                            check(parent.mkdir())
                            Files.move(displaced.resolve(operation.id).toPath(), custody.toPath())
                        }
                    }
                    assertTrue(runCatching { f.begin("next") }.isFailure)
                    assertTrue(f.unlinks.isEmpty())
                    if (mode == "cold-inode" || mode == "cold-symlink") assertArrayEquals(sentinel, displaced.readBytes())
                    if (mode == "cold-parent") assertEquals(key, f.storage.key(file, false))
                } else if (mode == "partial" || mode == "replaced") {
                    assertNotNull(failure)
                    assertTrue(destination.isDirectory)
                    assertFalse(destination.resolve("original.bin").exists())
                    assertTrue(f.directory.resolve(operation.id).resolve("operation.json").isFile)
                    val retainedCharge = f.head().outstanding.single().charge
                    assertEquals(operation.payloadBytes, retainedCharge.payload)
                    f.failRemove = { false }
                    if (mode == "replaced") {
                        val displaced = f.root.resolve("displaced-custody")
                        Files.move(custody.toPath(), displaced.toPath())
                        check(custody.mkdir())
                        custody.resolve("foreign.bin").writeBytes(sentinel)
                        val before = f.unlinks.size
                        assertTrue(runCatching { f.begin("next") }.isFailure)
                        assertEquals(before, f.unlinks.size)
                        assertArrayEquals(sentinel, custody.resolve("foreign.bin").readBytes())
                        assertTrue(displaced.resolve("original").isDirectory)
                    } else {
                        f.begin("next")
                        assertFalse(custody.exists())
                        assertTrue(f.head().outstanding.isEmpty())
                    }
                } else {
                    assertNull(failure)
                    assertFalse(custody.exists())
                    assertFalse(f.directory.resolve(operation.id).exists())
                    assertTrue(f.head().outstanding.isEmpty())
                }
                f.sentinel()
            }
        }
    }

    @Test(timeout = 30_000) fun malformedRawFrameAndCorruptHeadBlockBeforeAnyCleanup() {
        for (damageLength in listOf(false, true)) {
            val f = Fixture()
            val first = f.commit("a"); f.commit("b")
            f.failFile = { it.name == SafJournalRetirement.CURRENT }
            assertTrue(runCatching { f.begin("next") }.isFailure)
            f.failFile = { false }
            val file = f.directory.resolve(SafJournalRetirement.CURRENT)
            val raw = file.readBytes()
            if (damageLength) ByteBuffer.wrap(raw).putInt(4, Int.MAX_VALUE) else raw[raw.lastIndex] = 0
            file.writeBytes(raw)
            val retained = f.snapshot(first)
            assertTrue(runCatching { f.begin("next") }.isFailure)
            f.assertSnapshot(first, retained)
            assertTrue(f.unlinks.isEmpty())
            f.sentinel()
        }
    }

    @Test(timeout = 30_000) fun oversizedProvenSuccessStaysChargedWhileLaterTinySuccessCanRetire(): Unit = runBlocking {

        val f = Fixture(recordBytes = 4096, metadataBytes = 128L * 1024)
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val source = f.workspace.resolve("deleted").apply { check(mkdir()) }
            var leaf = source
            repeat(21) { leaf = leaf.resolve("d" + "\"".repeat(99)).apply { check(mkdir()) } }
            leaf = leaf.resolve("leaf.bin").apply { writeBytes(sentinel) }
            assertTrue(leaf.absolutePath.toByteArray().size < 3072)
            val large = f.begin("deleted", SafOperationKind.DELETE)
            val custody = f.workspace.resolve(".droide/saf-delete-custody/${large.id}").apply { check(mkdirs()) }
            val binding = SafDeleteCustody(".droide/saf-delete-custody/${large.id}", f.storage.key(custody, true))
            val preparation = WorkspaceLocalPreparation(WorkspaceLocalEffect.DELETE_STAGE,
                "${binding.directory}/original", "deleted", f.storage.key(source, true))
            val effects = SafLocalEffects(guard, f.journal, large) { guard.requireRoot(f.workspace) }
            val effect = effects.prepare(preparation, binding); effects.beforeAttempt(effect)
            val destination = custody.resolve("original")
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            effects.returned(effect, WorkspaceLocalReceipt(preparation, true, f.storage.key(destination, true))); effects.verify(effect)
            val publisher = SafDurableManifestCommit(f.journal)
            publisher.plan(large, emptyMap()); assertTrue(publisher.publish(large).acknowledged)
            val retained = f.snapshot(large)
            val inventory = f.journal.localInventory(large, requireNotNull(large.effects.values.single().plan.local)) {}
            val largest = inventory.entries.maxOf { SafJournalCodec.encode(SafLocalEvidenceEntry.serializer(),
                it.copy(path = "original" + if (it.path.isEmpty()) "" else "/${it.path}")).size }
            assertTrue(largest > f.limits.recordBytes)
            val custodyBytes = custody.walkTopDown().filter { it.isFile }.associate { it.relativeTo(custody).path to it.readBytes() }
            val key = f.storage.key(custody, true)
            val tiny = f.commit("tiny")
            f.commit("tiny-2")
            val incoming = f.begin("incoming")
            assertNotNull(incoming.envelope.headDigest)
            assertTrue(f.journal.retirementDiagnostic.orEmpty().contains("Oversize successful operation"))
            assertFalse(f.directory.resolve(tiny.id).exists())
            f.assertSnapshot(large, retained)
            val admittedLarge = f.reopen().recover(large.id)
            assertEquals(large.payloadBytes, admittedLarge.payloadBytes)
            assertEquals(large.metadataBytes, admittedLarge.metadataBytes)
            assertEquals(key, f.storage.key(custody, true))
            custodyBytes.forEach { (path, bytes) -> assertArrayEquals(bytes, custody.resolve(path).readBytes()) }
            assertEquals(3L, SafDurableManifestCommit(f.reopen()).trusted().revision)
            f.sentinel()
        }
    }

    @Test(timeout = 30_000) fun decodedReceiptCannotUnderchargeItsListedPayloadOrCandidate() {
        val f = Fixture()
        val first = f.commit("a"); f.commit("b")
        f.failFile = { it.name == SafJournalRetirement.CURRENT }
        assertTrue(runCatching { f.begin("next") }.isFailure)
        f.failFile = { false }
        val file = f.directory.resolve(SafJournalRetirement.CURRENT)
        val raw = ByteBuffer.wrap(file.readBytes())
        val magic = raw.int
        val control = ByteArray(raw.int).also { raw.get(it) }
        val image = ByteArray(raw.remaining()).also { raw.get(it) }
        val head = SafJournalCodec.decode(SafRetirementCheckpoint.serializer(), control)
        val changed = head.copy(receipts = head.receipts.map { it.copy(charge = it.charge.copy(payload = it.charge.payload - 1)) })
        val altered = SafJournalCodec.encode(SafRetirementCheckpoint.serializer(), changed)
        file.writeBytes(ByteBuffer.allocate(8 + altered.size + image.size).putInt(magic).putInt(altered.size).put(altered).put(image).array())
        val retained = f.snapshot(first)
        assertTrue(runCatching { f.begin("next") }.isFailure)
        f.assertSnapshot(first, retained)
        assertTrue(f.unlinks.isEmpty())
        f.sentinel()
    }

    @Test(timeout = 30_000) fun representativeEncoderSizesAndOversizeFrameStayFiniteWithoutLargeTrees() {
        val key = "(dev=1000,ino=1000000)"
        val digest = "0".repeat(64)
        val id = "00000000-0000-0000-0000-000000000001"
        val payload = SafEvidenceFile("payload-$id.bin", SafStoredFile(key, 4096, digest))
        val action = payload.copy(name = "action-$id-0.json")
        val node = SafLocalEvidenceEntry("original/node_modules/pkg/index.js",
            SafLocalVersion(SafLocalKind.FILE, key, 4096, "2026-10-16T00:00:00Z"), digest)
        val payloadBytes = SafJournalCodec.encode(SafEvidenceFile.serializer(), payload).size
        val actionBytes = SafJournalCodec.encode(SafEvidenceFile.serializer(), action).size
        val nodeBytes = SafJournalCodec.encode(SafLocalEvidenceEntry.serializer(), node).size
        assertEquals(192, payloadBytes)
        assertEquals(194, actionBytes)
        assertEquals(243, nodeBytes)
        val receipt = SafRetirementReceipt(id, key, digest, digest, SafRetirementCharge(1, 1, 1, 8192), 0,
            listOf(payload, action), null)
        val wrapper = SafJournalCodec.encode(SafRetirementReceipt.serializer(), receipt).size - payloadBytes - actionBytes - 1
        println("retirement sizing: payloadRef=$payloadBytes actionRef=$actionBytes custodyNode=$nodeBytes receiptWrapper=$wrapper; default control cap=1048576; frame header=8")
        assertTrue(wrapper > 0)
        // Fixed encoder-only arithmetic, not allocation of thousands of objects or a large tree.
        assertEquals(973, 4 * (actionBytes + 1) + payloadBytes + 1)
        assertTrue(1078L * 973 > 1024 * 1024)
        val f = Fixture()
        val file = f.directory.resolve(SafJournalRetirement.CURRENT)
        file.writeBytes(ByteBuffer.allocate(8).putInt(0x53414631).putInt((f.limits.recordBytes + 1).toInt()).array())
        assertTrue(runCatching { SafJournalRetirement(f.reopen()).load {} }.isFailure)
        assertTrue(f.unlinks.isEmpty())
        f.sentinel()
    }

    private inner class Fixture(operations: Int = 3, recordBytes: Long = 64L * 1024, metadataBytes: Long = 32L * 1024) {
        val root = Files.createTempDirectory("saf-retirement-").toFile().also { roots += it }
        val workspace = root.resolve("workspace").apply { check(mkdir()) }
        val directory = root.resolve("journal")
        val manifest = workspace.resolve(".droide/saf_manifest.json")
        val limits = SafJournalLimits(operations = operations, indexBytes = 128L * 1024, recordBytes = recordBytes,
            manifestBytes = 4096, aggregateMetadataBytes = 2L * 1024 * 1024, aggregatePayloadBytes = 2L * 1024 * 1024,
            actionsPerOperation = 2, evidenceFilesPerOperation = 64)
        val reservation = SafJournalReservation(2, metadataBytes, 64L * 1024, 8192, 64)
        val fingerprint = "f:${sentinel.safDigest()}"
        var failFile: (File) -> Boolean = { false }
        var failDirectory: (File) -> Boolean = { false }
        var failRemove: (File) -> Boolean = { false }
        var afterRemove: (File) -> Unit = {}
        var beforeRead: (File) -> Unit = {}
        val reads = arrayListOf<File>()
        val unlinks = arrayListOf<File>()
        val directories = arrayListOf<File>()
        var manifestMoves = 0
        var failCheckpointMove = false
        val storage = SafOperationStorage(
            forceDirectory = { file ->
                directories += file
                if (failDirectory(file)) throw IOException("injected directory force")
                FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
            },
            forceFile = { file ->
                if (failFile(file)) throw IOException("injected file force")
                FileChannel.open(file.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
            },
            replaceAtomically = { source, target ->
                if (target.name == SafJournalRetirement.CURRENT && failCheckpointMove) throw IOException("injected checkpoint move")
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                if (target == manifest) manifestMoves++
                Unit
            },
            beforeRead = { file -> reads += file; beforeRead(file) },
            removeOwned = { file ->
                unlinks += file
                if (failRemove(file)) throw IOException("injected exact unlink")
                Files.delete(file.toPath())
                afterRemove(file)
            },
        )
        var journal: SafOperationJournal
        init {
            workspace.resolve("sentinel.bin").writeBytes(sentinel)
            check(manifest.parentFile!!.mkdir())
            manifest.writeBytes(SafJournalCodec.encode(SafLegacyTrustedManifest.serializer(),
                SafLegacyTrustedManifest(entries = sortedMapOf("sentinel.bin" to fingerprint))))
            journal = SafOperationJournal.create(workspace, "content://fixture/tree/root", directory, storage, limits)
        }
        fun reopen(limitsOverride: SafJournalLimits = limits) = SafOperationJournal.open(workspace,
            "content://fixture/tree/root", directory, storage, limitsOverride)
        fun begin(path: String, kind: SafOperationKind = SafOperationKind.WRITE): SafOperationJournal.Operation {
            journal = reopen()
            return journal.begin(SafOperationRequest(kind, listOf(path)), listOf(path), reservation)
        }
        fun commit(path: String): SafOperationJournal.Operation {
            val operation = begin(path)
            val publisher = SafDurableManifestCommit(journal)
            publisher.plan(operation, mapOf(path to fingerprint))
            assertTrue(publisher.publish(operation).acknowledged)
            return operation
        }
        fun head() = SafJournalRetirement(reopen()).load {}!!
        fun snapshot(operation: SafOperationJournal.Operation) = directory.resolve(operation.id).listFiles()!!.associate { it.name to it.readBytes() }
        fun assertSnapshot(operation: SafOperationJournal.Operation, expected: Map<String, ByteArray>) {
            assertEquals(expected.keys, directory.resolve(operation.id).list()!!.toSet())
            expected.forEach { (name, bytes) -> assertArrayEquals(bytes, directory.resolve(operation.id).resolve(name).readBytes()) }
        }
        fun sentinel() { assertArrayEquals(sentinel, workspace.resolve("sentinel.bin").readBytes()) }
    }
}
