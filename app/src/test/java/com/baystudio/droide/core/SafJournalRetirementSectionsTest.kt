package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Small injected section capacities; no large tree, throughput, device or power-loss claim. */
class SafJournalRetirementSectionsTest {
    private val roots = arrayListOf<File>()
    @After fun cleanup() { roots.asReversed().forEach { PathSecurity.deleteTreeNoFollow(it) } }

    @Test(timeout = 30_000) fun twentyFourPayloadSuccessPagesCompactsAndKeepsExactTrustedBytes() {
        val f = Fixture()
        val large = f.captureLarge()
        val full = f.frame()
        assertTrue(full.pages.size >= 2)
        assertTrue(full.pages.all { it.files.isNotEmpty() })
        val expected = f.manifest.readBytes()
        val captured = f.snapshot(large)
        assertEquals(24, captured.keys.count { it.startsWith("payload-") })
        captured.filterKeys { it.startsWith("payload-") }.values.forEach { assertArrayEquals(byteArrayOf(7), it) }
        val incoming = f.begin("incoming")
        assertFalse(f.directory.resolve(large.id).exists())
        val compact = f.head()
        assertEquals(2, compact.checkpoint.version)
        assertTrue(compact.checkpoint.receipts.isEmpty())
        assertTrue(f.frame().pages.isEmpty())
        assertEquals(compact.identity.sha256, incoming.envelope.headDigest)
        assertArrayEquals(expected, f.manifest.readBytes())
        assertArrayEquals(expected, SafDurableManifestCommit(f.open()).trusted().bytes)
        assertEquals(2L, SafDurableManifestCommit(f.open()).trusted().revision)
        f.sentinel()
    }

    @Test(timeout = 30_000) fun sectionReaderCountsPhysicalBytesAndBoundsEachEncodedAllocation() {
        val f = Fixture(); f.captureLarge()
        val size = f.current.length()
        assertTrue(size > f.limits.recordBytes)
        f.counter.reset()
        val state = SafJournalRetirement(f.journal).load {}!!
        assertEquals(3L, f.counter.reads)
        assertEquals(3L * size, f.counter.bytes)
        assertTrue(f.counter.largestAllocation <= maxOf(f.limits.recordBytes, f.limits.manifestBytes))
        assertTrue(f.counter.largestAllocation.toLong() < size)
        assertEquals(state.identity.bytes, state.fileCharge.metadata + state.fileCharge.payload)
        println("streaming load: fullPasses=${f.counter.reads} physicalBytes=${f.counter.bytes} frameBytes=$size largestFrameAllocation=${f.counter.largestAllocation}")
        f.sentinel()
    }

    @Test(timeout = 30_000) fun everySectionByteIsChargedAndOneByteUnderActualMetadataRejectsBeforeUnlink() {
        val f = Fixture(); val large = f.captureLarge(tightTailReservation = true)
        val parsed = f.frame()
        val state = f.head()
        val controlBytes = SafJournalCodec.encode(SafRetirementFrame.serializer(), parsed.control).size
        assertEquals(8L + controlBytes, state.fileCharge.index)
        assertEquals(8L + controlBytes + parsed.control.sections.sumOf { it.bytes }, state.fileCharge.metadata)
        assertEquals(parsed.control.imageBytes, state.fileCharge.payload)
        assertTrue(parsed.control.sections.all { it.bytes <= f.limits.recordBytes })
        val remaining = f.open().recoverAll()
        val actual = state.charge.metadata + remaining.sumOf { it.metadataBytes }
        assertTrue(remaining.all { it.envelope.reservation.metadataBytes <= actual - 1 })
        val snapshot = f.snapshot(large)
        assertTrue(runCatching { f.open(f.limits.copy(aggregateMetadataBytes = actual - 1)).summaries() }.isFailure)
        assertEquals(0, f.unlinks.size)
        f.assertSnapshot(large, snapshot)
        assertEquals(remaining.size, f.open(f.limits.copy(aggregateMetadataBytes = actual)).summaries().size)
    }

    @Test(timeout = 30_000) fun malformedCrossSectionMembershipNeverAuthorizesCleanup() {
        for (mode in listOf("missing", "duplicate", "order", "alias", "digest", "trailing")) {
            val f = Fixture(); val large = f.captureLarge()
            val before = f.snapshot(large)
            val frame = f.frame()
            val pages = frame.pages.toMutableList()
            when (mode) {
                "missing" -> pages.removeAt(pages.lastIndex)
                "duplicate" -> pages[1] = pages[1].copy(files = listOf(pages[0].files.last()) + pages[1].files.drop(1))
                "order" -> pages[1] = pages[1].copy(ordinal = pages[0].ordinal)
                "alias" -> pages[1] = pages[1].copy(files = pages[1].files.mapIndexed { i, ref ->
                    if (i == 0) ref.copy(identity = ref.identity.copy(key = pages[0].files[0].identity.key)) else ref })
                "digest" -> Unit
            }
            var altered = frame.copy(pages = pages)
            if (mode == "missing") altered = altered.copy(control = altered.control.copy(sections = altered.control.sections.dropLast(1)))
            f.writeFrame(altered, rewriteRefs = mode != "digest")
            if (mode == "digest") {
                val raw = f.current.readBytes(); raw[raw.lastIndex] = (raw.last().toInt() xor 1).toByte(); f.current.writeBytes(raw)
            }
            if (mode == "trailing") f.current.appendBytes(byteArrayOf(1))
            assertTrue("mode=$mode", runCatching { f.begin("next") }.isFailure)
            assertEquals(0, f.unlinks.size)
            f.assertSnapshot(large, before)
            f.sentinel()
        }
    }

    @Test(timeout = 30_000) fun sectionReceiptUnderchargeCannotReleaseAnyOriginalBytes() {
        val f = Fixture(); val large = f.captureLarge()
        val before = f.snapshot(large)
        val frame = f.frame()
        val head = frame.control.checkpoint
        f.writeFrame(frame.copy(control = frame.control.copy(checkpoint = head.copy(receipts = head.receipts.map {
            it.copy(charge = it.charge.copy(payload = it.charge.payload - 1))
        }))))
        assertTrue(runCatching { f.begin("next") }.isFailure)
        assertTrue(f.unlinks.isEmpty())
        f.assertSnapshot(large, before)
    }

    @Test(timeout = 30_000) fun boundedCaptureBudgetStopsBeforeAccumulatingAnotherLargeReceiptButAdmitsSmallOne() {
        val f = Fixture(operations = 12)
        val large = f.begin("large")
        val source = f.root.resolve("zero").apply { writeBytes(byteArrayOf()) }
        repeat(24) { f.journal.preserve(large, source) }
        f.acknowledge(large, mapOf("large" to f.fingerprint))
        val tiny = f.commit("tiny")
        val retirement = SafJournalRetirement(f.open())
        val capture = SafJournalRetirement::class.java.getDeclaredMethod("inventory", SafOperationJournal.Operation::class.java,
            java.lang.Long.TYPE, kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
        fun bytes(value: Any): Long = value.javaClass.getDeclaredField("metadataBytes").apply { isAccessible = true }.getLong(value)
        fun invoke(operation: SafOperationJournal.Operation, limit: Long): Any = requireNotNull(capture.invoke(retirement, operation, limit, {}))
        val largeBytes = bytes(invoke(large, f.limits.aggregateMetadataBytes))
        val tinyBytes = bytes(invoke(tiny, f.limits.aggregateMetadataBytes))
        assertTrue(largeBytes > tinyBytes)
        val budget = largeBytes + tinyBytes
        val used = bytes(invoke(large, budget))
        f.reads.clear()
        val failure = runCatching { invoke(large, budget - used) }.exceptionOrNull()
        assertTrue(failure is java.lang.reflect.InvocationTargetException)
        assertEquals("InventoryTooLarge", requireNotNull(failure?.cause).javaClass.simpleName)
        val partialPayloadReads = f.reads.count { it.parentFile?.name == large.id && it.name.startsWith("payload-") }
        assertTrue("Capture must stop before hashing all 24 later payload refs", partialPayloadReads < 24)
        assertEquals(tinyBytes, bytes(invoke(tiny, budget - used)))
        assertTrue(f.unlinks.isEmpty()); assertFalse(f.current.exists())
        assertEquals(2, f.open().recoverAll().size)
        println("bounded capture: totalBudget=$budget first=$used laterSmall=$tinyBytes rejectedSecondLarge=$largeBytes partialPayloadReads=$partialPayloadReads")
    }

    @Test(timeout = 30_000) fun aliasedPrivatePayloadNamesCannotPublishAnInvalidRetirementReceipt() {
        val f = Fixture()
        val large = f.begin("large")
        val source = f.root.resolve("byte").apply { writeBytes(byteArrayOf(7)) }
        val first = f.journal.preserve(large, source)
        val second = f.journal.preserve(large, source)
        val firstFile = f.directory.resolve(large.id).resolve(first.name)
        val secondFile = f.directory.resolve(large.id).resolve(second.name)
        Files.delete(secondFile.toPath()); Files.createLink(secondFile.toPath(), firstFile.toPath())
        f.acknowledge(large, mapOf("large" to f.fingerprint))
        f.commit("tiny")
        val before = f.snapshot(large)
        val visible = f.manifest.readBytes()
        assertTrue(runCatching { f.begin("next") }.isFailure)
        assertFalse(f.current.exists()); assertFalse(f.directory.resolve(SafJournalRetirement.CANDIDATE).exists())
        assertTrue(f.unlinks.isEmpty()); f.assertSnapshot(large, before)
        assertArrayEquals(visible, f.manifest.readBytes())
    }

    @Test(timeout = 30_000) fun partialSectionCleanupRetainsOriginalChargeUntilWholeAbsence() {
        val f = Fixture(); val large = f.captureLarge()
        val original = f.head().outstanding.single().charge
        f.failRemove = { it.name == "operation.json" && it.parentFile?.name == large.id }
        assertTrue(runCatching { f.begin("next") }.isFailure)
        assertEquals(setOf("operation.json"), f.directory.resolve(large.id).list()!!.toSet())
        assertEquals(original, f.head().outstanding.single().charge)
        f.failRemove = { false }
        f.begin("next")
        assertFalse(f.directory.resolve(large.id).exists())
        assertTrue(f.head().checkpoint.receipts.isEmpty())
        f.sentinel()
    }

    @Test(timeout = 30_000) fun compactPublicationFaultsLeaveDurableFullOrCompactHeadAndRetainCandidate() {
        for (mode in listOf("candidate-force", "replace", "current-force", "parent-force")) {
            val f = Fixture(); val large = f.captureLarge()
            val trusted = f.manifest.readBytes()
            f.failFile = { file -> f.isCompactCandidateOrCurrent(file) &&
                (mode == "candidate-force" && file.name == SafJournalRetirement.CANDIDATE ||
                 mode == "current-force" && file.name == SafJournalRetirement.CURRENT) }
            f.failMove = { source, _ -> mode == "replace" && f.isCompactCandidateOrCurrent(source) }
            f.failDirectory = { dir -> mode == "parent-force" && dir == f.directory && f.isCompactCandidateOrCurrent(f.current) }
            assertTrue("mode=$mode", runCatching { f.begin("next") }.isFailure)
            assertFalse(f.directory.resolve(large.id).exists())
            assertArrayEquals(trusted, f.manifest.readBytes())
            f.failFile = { false }; f.failMove = { _, _ -> false }; f.failDirectory = { false }
            if (mode == "candidate-force" || mode == "replace") {
                assertTrue(f.directory.resolve(SafJournalRetirement.CANDIDATE).isFile)
                assertTrue(runCatching { f.open().summaries() }.isFailure)
            } else {
                assertTrue(f.head().checkpoint.receipts.isEmpty())
                assertArrayEquals(trusted, SafDurableManifestCommit(f.open()).trusted().bytes)
                f.begin("resumed")
            }
            f.sentinel()
        }
    }

    @Test(timeout = 30_000) fun laterCompactionPinsEveryPostHeadEnvelopeAndPreservesHighwater() {
        val f = Fixture(); val large = f.captureLarge(tightTailReservation = true)
        f.afterRemove = { if (it == f.directory.resolve(large.id)) throw CancellationException("stop after exact root unlink") }
        assertTrue(runCatching { f.begin("next") }.isFailure)
        f.afterRemove = {}
        val before = f.head()
        assertTrue(before.outstanding.isEmpty())
        val all = f.open().recoverAll()
        val actualPayload = before.charge.payload + all.sumOf { it.payloadBytes } + f.journal.anchor.baseline.identity.bytes
        assertTrue(before.checkpoint.receipts.all { it.charge.payload <= actualPayload })
        assertTrue(all.all { it.envelope.reservation.payloadBytes <= actualPayload })
        val low = f.open(f.limits.copy(aggregatePayloadBytes = actualPayload))
        val pending = low.begin(SafOperationRequest(SafOperationKind.WRITE, listOf("pending")), listOf("pending"),
            SafJournalReservation(0, 4096, 0, 4096, 0))
        assertEquals(before.identity.sha256, pending.envelope.headDigest)
        assertEquals(before.identity, f.head().identity)
        f.begin("after")
        val after = f.head()
        assertTrue(after.checkpoint.receipts.isEmpty())
        assertTrue(after.checkpoint.generation > before.checkpoint.generation)
        assertEquals(before.checkpoint.highwater, after.checkpoint.highwater)
        assertArrayEquals(before.image, after.image)
        val pin = after.checkpoint.pins.single { it.id == pending.id }
        assertEquals(pending.envelopeIdentity.sha256, pin.envelopeDigest)
        assertNull(pin.planDigest)
        assertNotNull(f.open().recover(pending.id))
    }

    @Test(timeout = 30_000) fun coldPagedPayloadDamageOrUnknownNameCannotBeCleaned() {
        for (mode in listOf("bytes", "inode", "unknown")) {
            val f = Fixture(); val large = f.captureLarge()
            val payload = f.directory.resolve(large.id).listFiles()!!.first { it.name.startsWith("payload-") }
            when (mode) {
                "bytes" -> payload.writeBytes(byteArrayOf(8))
                "inode" -> { Files.move(payload.toPath(), f.root.resolve("displaced").toPath()); payload.writeBytes(byteArrayOf(7)) }
                "unknown" -> payload.parentFile!!.resolve("unknown.json").writeText("retained")
            }
            val before = f.snapshot(large)
            assertTrue(runCatching { f.begin("next") }.isFailure)
            assertTrue(f.unlinks.isEmpty())
            f.assertSnapshot(large, before)
        }
    }

    @Test(timeout = 30_000) fun pagedProtectedDeleteCustodyRetiresOnlyAfterExactDurableReceipt(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val operation = f.deleteTree(guard)
            val custody = f.workspace.resolve(".droide/saf-delete-custody/${operation.id}")
            val bytes = custody.walkTopDown().filter { it.isFile }.associate { it.relativeTo(custody).path to it.readBytes() }
            f.commit("later")
            bytes.forEach { (path, content) -> assertArrayEquals(content, custody.resolve(path).readBytes()) }
            f.failFile = { it == f.current }
            assertTrue(runCatching { f.begin("next") }.isFailure)
            f.failFile = { false }
            val pages = f.frame().pages
            assertTrue(pages.count { it.kind == "custody" } >= 2)
            bytes.forEach { (path, content) -> assertArrayEquals(content, custody.resolve(path).readBytes()) }
            f.begin("next")
            assertFalse(custody.exists())
            assertFalse(f.directory.resolve(operation.id).exists())
            assertTrue(f.head().checkpoint.receipts.isEmpty())
            f.sentinel()
        }
    }

    @Test(timeout = 30_000) fun selfConsistentPagesCannotOmitTheCustodyParentOfLaterSections(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val operation = f.deleteTree(guard)
            val custody = f.workspace.resolve(".droide/saf-delete-custody/${operation.id}")
            val retained = f.snapshot(operation)
            val content = custody.walkTopDown().filter { it.isFile }.associate { it.relativeTo(custody).path to it.readBytes() }
            f.commit("later")
            f.failFile = { it == f.current }
            assertTrue(runCatching { f.begin("next") }.isFailure)
            f.failFile = { false }
            val frame = f.frame()
            assertTrue(frame.pages.count { it.kind == "custody" } >= 2)
            assertEquals("original", frame.pages.first { it.kind == "custody" }.nodes.first().path)
            val pages = frame.pages.map { page -> page.copy(nodes = page.nodes.filter { it.path != "original" }) }
            f.writeFrame(frame.copy(pages = pages)) // canonical page lengths, counts and hashes all recomputed
            assertTrue(runCatching { f.begin("next") }.isFailure)
            assertTrue(f.unlinks.isEmpty()); f.assertSnapshot(operation, retained)
            content.forEach { (path, bytes) -> assertArrayEquals(bytes, custody.resolve(path).readBytes()) }
            f.sentinel()
        }
    }

    @Test(timeout = 30_000) fun interruptedStreamingCandidateRetainsPartialBytesAndCannotAuthorizeCleanup() {
        val f = Fixture()
        val large = f.begin("large")
        val source = f.root.resolve("one-byte").apply { writeBytes(byteArrayOf(7)) }
        repeat(24) { f.journal.preserve(large, source) }
        f.acknowledge(large, mapOf("large" to f.fingerprint)); f.commit("tiny")
        val retained = f.snapshot(large)
        val visible = f.manifest.readBytes()
        val candidate = f.directory.resolve(SafJournalRetirement.CANDIDATE)
        var interrupted = false
        val failure = runCatching { f.open().begin(SafOperationRequest(SafOperationKind.WRITE, listOf("next")),
            listOf("next"), f.reservation, {
                if (candidate.length() >= 8) { interrupted = true; throw CancellationException("stop after frame header") }
            }) }.exceptionOrNull()
        assertTrue(interrupted); assertTrue(failure is CancellationException)
        assertEquals(8L, candidate.length()); assertFalse(f.current.exists())
        assertEquals(0x53414632, ByteBuffer.wrap(candidate.readBytes()).int)
        assertTrue(runCatching { f.open().summaries() }.isFailure)
        assertTrue(f.unlinks.isEmpty()); f.assertSnapshot(large, retained)
        assertArrayEquals(visible, f.manifest.readBytes()); f.sentinel()
    }

    @Test(timeout = 30_000) fun capturedJobUsesOneDeepPayloadAdmissionAndFourPhysicalFramePasses() {
        for (planning in listOf(false, true)) {
            val f = Fixture(operations = 6)
            repeat(3) { f.commit("small-$it") }
            val body = f.commitBody("body")
            val job = Job()
            val journal = f.open(job = job)
            val incoming = if (planning) journal.begin(SafOperationRequest(SafOperationKind.WRITE, listOf("next")), listOf("next"), f.reservation) else null
            f.counter.reset(); f.reads.clear()
            var bodySeen = false
            var lateCaller = 0
            var sealedReads = 0
            var readsBeforeFirstAllocation = 0
            f.beforeRead = {
                if (it == body) bodySeen = true
                if (it == f.current) sealedReads++
                else if (planning && sealedReads == 4 &&
                    !f.directory.resolve(requireNotNull(incoming).id).resolve("trusted.bin").exists()) readsBeforeFirstAllocation++
            }
            val poll = { if (bodySeen) lateCaller++ }
            val size = f.current.length()
            if (planning) SafDurableManifestCommit(journal).plan(requireNotNull(incoming), mapOf("next" to f.fingerprint), poll)
            else journal.begin(SafOperationRequest(SafOperationKind.WRITE, listOf("next")), listOf("next"), f.reservation, poll)
            assertEquals("No general caller callback may follow production deep admission", 0, lateCaller)
            assertEquals("No observed read may reopen a callback gap between the final seal and first allocation", 0, readsBeforeFirstAllocation)
            assertEquals("Retained provider payload must be admitted once", 1, f.reads.count { it == body })
            val retainedBodies = body.parentFile!!.listFiles()!!.filter { it.name.endsWith(".bin") }
            val bodyReads = retainedBodies.associate { it.name to f.reads.count { read -> read == it } }
            // Preserve the existing two begin ancestry checks / one plan ancestry check.
            assertEquals(if (planning) 3 else 4, bodyReads.getValue("planned.bin"))
            assertEquals(2, bodyReads.getValue("trusted.bin"))
            assertEquals(2, bodyReads.getValue("delta.bin"))
            val retainedBytes = retainedBodies.sumOf { it.length() * bodyReads.getValue(it.name) }
            assertEquals(4L, f.counter.reads)
            assertEquals(4L * size, f.counter.bytes)
            println("production admission: planning=$planning framePasses=${f.counter.reads} physicalFrameBytes=${f.counter.bytes} retainedReads=$bodyReads retainedBodyBytes=$retainedBytes")
        }
    }

    @Test(timeout = 30_000) fun lateLegacyNamespaceAdditionOrEnvelopeChangeBlocksBeginAndPlan() {
        for (planning in listOf(false, true)) for (mode in listOf("add", "change")) {
            val f = Fixture(operations = 6)
            repeat(4) { f.commit("small-$it") }
            val journal = f.open()
            val incoming = if (planning) journal.begin(SafOperationRequest(SafOperationKind.WRITE, listOf("next")), listOf("next"), f.reservation) else null
            val visible = f.manifest.readBytes()
            val beforeNames = f.directory.list()!!.toSet()
            var armed = false
            var changed = false
            f.beforeRead = { if (it == f.manifest) armed = true }
            val poll = {
                if (armed && !changed) {
                    changed = true
                    if (mode == "add") check(f.directory.resolve("00000000-0000-0000-0000-000000000099").mkdir())
                    else {
                        val target = f.directory.listFiles()!!.first { it.isDirectory && it.name != incoming?.id }.resolve("operation.json")
                        val bytes = target.readBytes(); bytes[0] = '['.code.toByte(); target.writeBytes(bytes)
                    }
                }
            }
            val failure = runCatching {
                if (planning) SafDurableManifestCommit(journal).plan(requireNotNull(incoming), mapOf("next" to f.fingerprint), poll)
                else journal.begin(SafOperationRequest(SafOperationKind.WRITE, listOf("next")), listOf("next"), f.reservation, poll)
            }.exceptionOrNull()
            assertTrue(changed); assertNotNull(failure)
            assertArrayEquals(visible, f.manifest.readBytes())
            assertEquals(beforeNames + if (mode == "add") setOf("00000000-0000-0000-0000-000000000099") else emptySet(), f.directory.list()!!.toSet())
            if (planning) assertEquals(setOf("operation.json"), requireNotNull(incoming).files)
            f.sentinel()
        }
    }

    @Test(timeout = 30_000) fun capturedJobCancellationAtFinalReadLeavesPriorStateAndEvidenceIntact() {
        for (before in listOf(false, true)) {
            val f = Fixture(operations = 6); repeat(4) { f.commit("small-$it") }
            val job = Job(); val journal = f.open(job = job)
            val old = f.directory.walkTopDown().filter { it.isFile }.associate { it.relativeTo(f.directory).path to it.readBytes() }
            var frameReads = 0
            f.beforeRead = { if (it == f.current && ++frameReads == 4) job.cancel() }
            if (before) job.cancel()
            val failure = runCatching { journal.begin(SafOperationRequest(SafOperationKind.WRITE, listOf("next")), listOf("next"), f.reservation) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(old.keys, f.directory.walkTopDown().filter { it.isFile }.map { it.relativeTo(f.directory).path }.toSet())
            old.forEach { (path, bytes) -> assertArrayEquals(bytes, f.directory.resolve(path).readBytes()) }
            f.beforeRead = {}
            assertArrayEquals(f.manifest.readBytes(), SafDurableManifestCommit(f.open()).trusted().bytes)
            f.sentinel()
        }
    }

    @Test(timeout = 30_000) fun finalFrameHookCannotExchangeVisibleAnchorBaselineOrOperationNamespace() {
        for (planning in listOf(false, true)) for (mode in listOf("visible", "metadata", "anchor", "baseline", "operation-add", "operation-envelope")) {
            val f = Fixture(operations = 6); repeat(4) { f.commit("small-$it") }
            val journal = f.open(job = Job())
            val incoming = if (planning) journal.begin(SafOperationRequest(SafOperationKind.WRITE, listOf("next")), listOf("next"), f.reservation) else null
            val names = f.directory.list()!!.toSet()
            val frame = f.current.readBytes()
            val visible = f.manifest.readBytes()
            f.unlinks.clear()
            var reads = 0
            var changed = false
            f.beforeRead = { file -> if (file == f.current && ++reads == 4) {
                changed = true
                when (mode) {
                    "operation-add" -> check(f.directory.resolve("00000000-0000-0000-0000-000000000099").mkdir())
                    "metadata" -> {
                        Files.move(f.manifest.parentFile!!.toPath(), f.root.resolve("old-metadata").toPath())
                        check(f.manifest.parentFile!!.mkdir()); f.manifest.writeBytes(visible)
                    }
                    else -> {
                        val target = when (mode) {
                            "visible" -> f.manifest
                            "anchor" -> f.directory.resolve("anchor.json")
                            "baseline" -> f.directory.resolve("baseline.bin")
                            else -> f.directory.listFiles()!!.first { it.isDirectory && it.name != incoming?.id }.resolve("operation.json")
                        }
                        val bytes = target.readBytes()
                        Files.move(target.toPath(), f.root.resolve("old-$mode").toPath()); target.writeBytes(bytes)
                    }
                }
            } }
            val failure = runCatching {
                if (planning) SafDurableManifestCommit(journal).plan(requireNotNull(incoming), mapOf("next" to f.fingerprint))
                else journal.begin(SafOperationRequest(SafOperationKind.WRITE, listOf("next")), listOf("next"), f.reservation)
            }.exceptionOrNull()
            assertTrue("planning=$planning mode=$mode", changed); assertNotNull(failure)
            assertEquals(names + if (mode == "operation-add") setOf("00000000-0000-0000-0000-000000000099") else emptySet(), f.directory.list()!!.toSet())
            if (incoming != null) assertEquals(setOf("operation.json"), f.directory.resolve(incoming.id).list()!!.toSet())
            assertArrayEquals(frame, f.current.readBytes()); assertArrayEquals(visible, f.manifest.readBytes())
            assertTrue(f.unlinks.isEmpty()); f.sentinel()
        }
    }

    @Test(timeout = 30_000) fun finalFrameReceiptChangeBlocksPlanWithoutRehashingCustodyBodies(): Unit = runBlocking {
        for (mode in listOf("body", "unknown", "parent")) {
            val f = Fixture(operations = 6)
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val retired = f.deleteTree(guard)
                val custody = f.workspace.resolve(".droide/saf-delete-custody/${retired.id}")
                val body = custody.resolve("original/file-00.bin")
                f.commit("tiny")
                val journal = f.open(job = Job())
                val pending = journal.begin(SafOperationRequest(SafOperationKind.WRITE, listOf("pending")), listOf("pending"), f.reservation)
                f.commit("tiny-2")
                f.failFile = { it == f.current }
                assertTrue(runCatching { f.begin("trigger") }.isFailure)
                f.failFile = { false }; f.reads.clear(); f.unlinks.clear()
                var reads = 0
                var changed = false
                f.beforeRead = { file -> if (file == f.current && ++reads == 4) {
                    changed = true
                    when (mode) {
                        "body" -> body.writeBytes(f.sentinelBytes.reversedArray())
                        "unknown" -> f.directory.resolve(retired.id).resolve("unknown.json").writeText("retained")
                        "parent" -> {
                            val parent = custody.parentFile!!
                            val old = f.root.resolve("old-custody-parent")
                            Files.move(parent.toPath(), old.toPath()); check(parent.mkdir())
                            Files.move(old.resolve(retired.id).toPath(), custody.toPath())
                        }
                    }
                } }
                assertTrue(runCatching { SafDurableManifestCommit(journal).plan(pending, mapOf("pending" to f.fingerprint)) }.isFailure)
                assertTrue(changed); assertTrue(f.unlinks.isEmpty())
                assertEquals(setOf("operation.json"), f.directory.resolve(pending.id).list()!!.toSet())
                assertEquals("Custody body is deeply admitted once; final continuity is direct", 1, f.reads.count { it == body })
                assertTrue(custody.exists()); f.sentinel()
            }
        }
    }

    @Test(timeout = 30_000) fun shallowReceiptObservationRejectsSymlinkRootsAndParentsBeforeDescendants(): Unit = runBlocking {
        for (mode in listOf("private-root", "custody-parent", "owned-directory")) {
            val f = Fixture()
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val operation = f.deleteTree(guard); f.commit("tiny")
                f.failFile = { it == f.current }; assertTrue(runCatching { f.begin("capture") }.isFailure); f.failFile = { false }
                val custody = f.workspace.resolve(".droide/saf-delete-custody/${operation.id}")
                val target = when (mode) {
                    "private-root" -> f.directory.resolve(operation.id)
                    "custody-parent" -> custody.parentFile!!
                    else -> custody.resolve("original")
                }
                val before = target.walkTopDown().filter { it.isFile }.associate { it.relativeTo(target).path to it.readBytes() }
                val outside = f.root.resolve("outside").apply { check(mkdir()) }
                val sentinel = outside.resolve("file-00.bin").apply { writeBytes(byteArrayOf(99, 0, -1)) }
                val key = f.storage.key(sentinel, false)
                val displaced = f.root.resolve("retained-$mode")
                Files.move(target.toPath(), displaced.toPath()); Files.createSymbolicLink(target.toPath(), outside.toPath())
                f.reads.clear()
                assertTrue(runCatching { f.begin("next") }.isFailure)
                assertTrue(f.unlinks.isEmpty())
                assertEquals(0, f.reads.count { it.toPath().startsWith(outside.toPath()) })
                assertEquals(key, f.storage.key(sentinel, false)); assertArrayEquals(byteArrayOf(99, 0, -1), sentinel.readBytes())
                before.forEach { (path, bytes) -> assertArrayEquals(bytes, displaced.resolve(path).readBytes()) }
                assertTrue(Files.isSymbolicLink(target.toPath()))
            }
        }
    }

    @Test(timeout = 30_000) fun finalPublicationCompactionAndCleanupCutsRejectChangedAdmittedFacts(): Unit = runBlocking {
        for (mode in listOf("publication", "compaction", "private-cleanup", "custody-cleanup")) {
            val f = Fixture()
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val operation = if (mode == "custody-cleanup") {
                    val value = f.deleteTree(guard); f.commit("tiny")
                    f.failFile = { it == f.current }; assertTrue(runCatching { f.begin("capture") }.isFailure); f.failFile = { false }
                    value
                } else if (mode == "publication") {
                    val value = f.begin("large")
                    val source = f.root.resolve("byte").apply { writeBytes(byteArrayOf(7)) }
                    repeat(24) { f.journal.preserve(value, source) }
                    f.acknowledge(value, mapOf("large" to f.fingerprint)); f.commit("tiny"); value
                } else f.captureLarge()
                if (mode == "compaction") {
                    f.afterRemove = { if (it == f.directory.resolve(operation.id)) throw CancellationException("after exact retirement") }
                    assertTrue(runCatching { f.begin("retire") }.isFailure); f.afterRemove = {}
                }
                val checkpoint = f.current.takeIf { it.exists() }?.readBytes()
                val retained = f.directory.resolve(operation.id).takeIf { it.exists() }?.let { f.snapshot(operation) }
                val candidate = f.directory.resolve(SafJournalRetirement.CANDIDATE)
                val target = if (mode == "custody-cleanup") f.workspace.resolve(".droide/saf-delete-custody/${operation.id}/original/file-00.bin")
                    else f.directory.resolve(operation.id).resolve("delta.bin")
                val targetBytes = target.takeIf { it.exists() }?.readBytes()
                f.unlinks.clear()
                var candidateReads = 0; var bodyReads = 0; var changed = false
                f.beforeRead = { file ->
                    if (mode == "publication" && file == candidate && ++candidateReads == 2) {
                        changed = true; target.writeBytes(requireNotNull(targetBytes).reversedArray())
                    } else if (mode == "compaction" && file == f.current && candidate.exists() && !changed) {
                        changed = true; check(f.directory.resolve(operation.id).mkdir())
                    } else if (mode.endsWith("cleanup") && file == target && ++bodyReads == 3) {
                        changed = true
                        val raw = f.current.readBytes(); raw[raw.lastIndex] = (raw.last().toInt() xor 1).toByte(); f.current.writeBytes(raw)
                    }
                }
                assertTrue("mode=$mode", runCatching { f.begin("next") }.isFailure)
                assertTrue("mode=$mode", changed); assertTrue(f.unlinks.isEmpty())
                when (mode) {
                    "publication" -> { assertFalse(f.current.exists()); assertTrue(candidate.exists()) }
                    "compaction" -> { assertArrayEquals(checkpoint, f.current.readBytes()); assertTrue(candidate.exists()); assertTrue(f.directory.resolve(operation.id).isDirectory) }
                    else -> { f.assertSnapshot(operation, requireNotNull(retained)); assertArrayEquals(targetBytes, target.readBytes()) }
                }
                f.sentinel()
            }
        }
    }

    @Test(timeout = 30_000) fun cancellationInsideModeledChunkCannotReturnPartialVerifiedFrame() {
        val job = Job()
        val bytes = ByteArray(17) { it.toByte() }
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(target, offset, minOf(2, length))
                if (count > 0) job.cancel()
                return count
            }
        }
        val counter = SafFrameReadCounter(File("modeled-frame"))
        val reader = SafOperationStorage.FrameReader(input, bytes.size.toLong(), MessageDigest.getInstance("SHA-256"), { job.ensureActive() }, counter)
        var accepted = false
        val failure = runCatching { reader.readExact(bytes.size.toLong(), 32).also { accepted = true } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(accepted)
        assertEquals(2L, counter.bytes)
        assertArrayEquals(ByteArray(17) { it.toByte() }, bytes)
    }

    private data class Frame(val control: SafRetirementFrame, val image: ByteArray, val pages: List<SafRetirementSection>)

    private inner class Fixture(operations: Int = 3) {
        val root = Files.createTempDirectory("saf-section-").toFile().also { roots += it }
        val workspace = root.resolve("workspace").apply { check(mkdir()) }
        val directory = root.resolve("journal")
        val current = directory.resolve(SafJournalRetirement.CURRENT)
        val manifest = workspace.resolve(".droide/saf_manifest.json")
        val sentinelBytes = byteArrayOf(0, -1, 42, 10)
        val fingerprint = "f:${sentinelBytes.safDigest()}"
        val limits = SafJournalLimits(operations = operations, indexBytes = 128L * 1024, recordBytes = 4096,
            manifestBytes = 4096, aggregateMetadataBytes = 2L * 1024 * 1024, aggregatePayloadBytes = 2L * 1024 * 1024,
            actionsPerOperation = 2, evidenceFilesPerOperation = 64)
        val reservation = SafJournalReservation(2, 128L * 1024, 64L * 1024, 8192, 64)
        val counter = SafFrameReadCounter(current)
        val reads = arrayListOf<File>()
        val unlinks = arrayListOf<File>()
        var beforeRead: (File) -> Unit = {}
        var failFile: (File) -> Boolean = { false }
        var failDirectory: (File) -> Boolean = { false }
        var failMove: (File, File) -> Boolean = { _, _ -> false }
        var failRemove: (File) -> Boolean = { false }
        var afterRemove: (File) -> Unit = {}
        val storage = SafOperationStorage(
            forceFile = { if (failFile(it)) throw IOException("file force"); FileChannel.open(it.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { c -> c.force(true) } },
            forceDirectory = { if (failDirectory(it)) throw IOException("directory force"); FileChannel.open(it.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { c -> c.force(true) } },
            replaceAtomically = { source, target -> if (failMove(source, target)) throw IOException("replace"); Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); Unit },
            beforeRead = { reads += it; beforeRead(it) },
            removeOwned = { if (failRemove(it)) throw IOException("unlink"); Files.delete(it.toPath()); unlinks += it; afterRemove(it) },
            frameReadCounter = counter,
        )
        var journal: SafOperationJournal
        init {
            workspace.resolve("sentinel.bin").writeBytes(sentinelBytes)
            check(manifest.parentFile!!.mkdir())
            manifest.writeBytes(SafJournalCodec.encode(SafLegacyTrustedManifest.serializer(), SafLegacyTrustedManifest(entries = sortedMapOf("sentinel.bin" to fingerprint))))
            journal = SafOperationJournal.create(workspace, TREE, directory, storage, limits)
        }
        fun open(limits: SafJournalLimits = this.limits, job: Job? = null) = SafOperationJournal.open(workspace, TREE, directory, storage, limits, job)
        fun begin(path: String, kind: SafOperationKind = SafOperationKind.WRITE): SafOperationJournal.Operation {
            journal = open(); return journal.begin(SafOperationRequest(kind, listOf(path)), listOf(path), reservation)
        }
        fun commit(path: String): SafOperationJournal.Operation = begin(path).also { acknowledge(it, mapOf(path to fingerprint)) }
        fun acknowledge(operation: SafOperationJournal.Operation, delta: Map<String, String?>) {
            val publisher = SafDurableManifestCommit(journal); publisher.plan(operation, delta); assertTrue(publisher.publish(operation).acknowledged)
        }
        fun commitBody(path: String): File {
            val operation = begin(path)
            val source = root.resolve("body").apply { writeBytes(byteArrayOf(7)) }
            val ref = journal.preserve(operation, source)
            val parent = SafOwnedDocument("$TREE/document/root", "root", "root", SafOperationJournal.DIRECTORY_MIME, "tree")
            val doc = SafOwnedDocument("$TREE/document/value", "value", path, "application/octet-stream", "root")
            // An existing document requires previous evidence, even for a same-byte write.
            val action = journal.prepare(operation, SafEffectPlan(SafEffectKind.WRITE_FILE, path, doc, parent, null, ref, ref))
            journal.attempting(action); journal.returned(action, SafEffectReturn.COMPLETED, SafProviderReturn(SafProviderReturnShape.VOID)); journal.verified(action, doc)
            acknowledge(operation, mapOf(path to "f:${byteArrayOf(7).safDigest()}"))
            return directory.resolve(operation.id).resolve(ref.name)
        }
        fun captureLarge(forcePressure: Boolean = false, tightTailReservation: Boolean = false): SafOperationJournal.Operation {
            val large = begin("large")
            val source = root.resolve("one-byte").apply { writeBytes(byteArrayOf(7)) }
            repeat(24) { journal.preserve(large, source) }
            acknowledge(large, mapOf("large" to "f:${byteArrayOf(7).safDigest()}"))
            if (tightTailReservation) commitTightTail("tiny") else commit("tiny")
            if (forcePressure) commit("tiny-2")
            failFile = { it == current }
            assertTrue(runCatching { begin("not-created") }.isFailure)
            failFile = { false }
            assertEquals(0, unlinks.size)
            assertEquals(2, head().checkpoint.version)
            return large
        }
        /** The two cap-boundary fixtures must keep the surviving immutable reservation admissible. */
        private fun commitTightTail(path: String) {
            assertFalse(current.exists())
            journal = open()
            val base = SafDurableManifestCommit(journal).trusted()
            val id = "00000000-0000-0000-0000-000000000002"
            val edits = sortedMapOf<String, String?>(path to fingerprint)
            val desired = base.entries.toSortedMap().apply { put(path, fingerprint) }
            val image = SafManifestImage(3, id, base.revision + 1, base.revision, base.digest, desired)
            val imageBytes = SafJournalCodec.encode(SafManifestImage.serializer(), image)
            val deltaBytes = SafJournalCodec.encode(SafManifestDelta.serializer(), SafManifestDelta(1, edits))
            val exactPayload = base.bytes.size.toLong() + 2L * imageBytes.size + deltaBytes.size
            val tight = reservation.copy(metadataBytes = 8192, payloadBytes = exactPayload)
            val operation = journal.begin(SafOperationRequest(SafOperationKind.WRITE, listOf(path)), listOf(path), tight, id = id)
            acknowledge(operation, edits)
            assertEquals(exactPayload, requireNotNull(open().recover(id)).payloadBytes)
        }
        fun head() = SafJournalRetirement(open()).load {}!!
        fun frame(file: File = current): Frame {
            val raw = ByteBuffer.wrap(file.readBytes()); require(raw.int == 0x53414632)
            val control = SafJournalCodec.decode(SafRetirementFrame.serializer(), ByteArray(raw.int).also { raw.get(it) })
            val image = ByteArray(control.imageBytes.toInt()).also { raw.get(it) }
            val pages = control.sections.map { ref -> SafJournalCodec.decode(SafRetirementSection.serializer(), ByteArray(ref.bytes.toInt()).also { raw.get(it) }) }
            check(!raw.hasRemaining()); return Frame(control, image, pages)
        }
        fun writeFrame(frame: Frame, rewriteRefs: Boolean = true) {
            val encoded = frame.pages.map { SafJournalCodec.encode(SafRetirementSection.serializer(), it) }
            val refs = if (rewriteRefs) frame.pages.mapIndexed { i, page -> SafRetirementSectionRef(page.ordinal, page.operationId, page.kind,
                encoded[i].size.toLong(), encoded[i].safDigest(), page.files.size + page.nodes.size) } else frame.control.sections
            val control = SafJournalCodec.encode(SafRetirementFrame.serializer(), frame.control.copy(sections = refs))
            val out = ByteArrayOutputStream(); out.write(ByteBuffer.allocate(8).putInt(0x53414632).putInt(control.size).array()); out.write(control); out.write(frame.image); encoded.forEach(out::write)
            current.writeBytes(out.toByteArray())
        }
        fun isCompactCandidateOrCurrent(file: File) = file.isFile && runCatching { frame(file).control.checkpoint.receipts.isEmpty() }.getOrDefault(false)
        fun snapshot(op: SafOperationJournal.Operation) = directory.resolve(op.id).listFiles()!!.associate { it.name to it.readBytes() }
        fun assertSnapshot(op: SafOperationJournal.Operation, before: Map<String, ByteArray>) {
            assertEquals(before.keys, directory.resolve(op.id).list()!!.toSet()); before.forEach { (name, bytes) -> assertArrayEquals(bytes, directory.resolve(op.id).resolve(name).readBytes()) }
        }
        fun sentinel() { assertArrayEquals(sentinelBytes, workspace.resolve("sentinel.bin").readBytes()) }
        fun deleteTree(guard: WorkspaceMutationGuard): SafOperationJournal.Operation {
            val source = workspace.resolve("deleted").apply { check(mkdir()) }
            repeat(24) { source.resolve("file-${it.toString().padStart(2, '0')}.bin").writeBytes(sentinelBytes) }
            val operation = begin("deleted", SafOperationKind.DELETE)
            val custody = workspace.resolve(".droide/saf-delete-custody/${operation.id}").apply { check(mkdirs()) }
            val binding = SafDeleteCustody(".droide/saf-delete-custody/${operation.id}", storage.key(custody, true))
            val preparation = WorkspaceLocalPreparation(WorkspaceLocalEffect.DELETE_STAGE, "${binding.directory}/original", "deleted", storage.key(source, true))
            val effects = SafLocalEffects(guard, journal, operation) { guard.requireRoot(workspace) }
            val effect = effects.prepare(preparation, binding); effects.beforeAttempt(effect)
            val target = custody.resolve("original"); Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            effects.returned(effect, WorkspaceLocalReceipt(preparation, true, storage.key(target, true))); effects.verify(effect)
            acknowledge(operation, emptyMap()); return operation
        }
    }
    companion object { private const val TREE = "content://fixture/tree/root" }
}
