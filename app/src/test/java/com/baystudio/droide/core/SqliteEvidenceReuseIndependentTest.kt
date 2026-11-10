package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Independent host tests. Synthetic journal exercises byte transformation, never SQLite crash recovery. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqliteEvidenceReuseIndependentTest {
    private class Fixture(val root: File, val source: File, val working: File, val directory: File) {
        fun authority(checkpoint: (String) -> Unit = {}) = MirrorMetadataAuthority(directory, source, working,
            processCheckpoint = { point ->
                check(point != "metadata-before-database-open") { "Forensics must not open live SQLite" }
                checkpoint(point)
            })
        fun captures() = directory.listFiles()!!.filter { it.name.startsWith("sqlite-review-") }.sortedBy { it.name }
        fun originals() = listOf("state.sqlite", "state.sqlite-journal").associateWith { hash(directory.resolve(it).readBytes()) }
        fun preserved() = directory.walkTopDown().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .associate { it.relativeTo(directory).path to hash(it.readBytes()) }
        fun assertKept(before: Map<String, String>) = before.forEach { (path, value) ->
            assertEquals("Existing bytes retained at $path", value, hash(directory.resolve(path).readBytes()))
        }
    }
    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("f159-independent-").toFile()
        val source = root.resolve("source").apply { mkdir() }
        val working = root.resolve("working")
        val directory = root.resolve(".working-sync-authority")
        MirrorMetadataAuthority.createForNewWorkingCopy(directory, source, working)
        val database = directory.resolve("state.sqlite").readBytes()
        val encoded = ((database[16].toInt() and 255) shl 8) or (database[17].toInt() and 255)
        val size = if (encoded == 1) 65536 else encoded
        val journal = ByteArray(512 + size + 8)
        byteArrayOf(0xd9.toByte(), 0xd5.toByte(), 5, 0xf9.toByte(), 0x20, 0xa1.toByte(), 0x63, 0xd7.toByte()).copyInto(journal)
        fun put(offset: Int, value: Int) { ByteBuffer.wrap(journal, offset, 4).putInt(value) }
        put(8, 1); put(12, 123); put(16, database.size / size); put(20, 512); put(24, size); put(512, 1)
        database.copyInto(journal, 516, 0, size)
        var checksum = 123L; var index = size - 200
        while (index > 0) { checksum = (checksum + (database[index].toInt() and 255)) and 0xffffffffL; index -= 200 }
        put(516 + size, checksum.toInt())
        directory.resolve("state.sqlite-journal").writeBytes(journal)
        println("PRESERVED_FIXTURE ${root.path}")
        return Fixture(root, source, working, directory)
    }
    private fun changeSource(f: Fixture) {
        val db = f.directory.resolve("state.sqlite")
        db.writeBytes(db.readBytes().also { it[it.size - 16] = (it[it.size - 16].toInt() xor 1).toByte() })
    }
    private fun addPartialCandidate(evidence: File): File = evidence.resolve("candidate-${UUID.randomUUID()}").apply {
        mkdir(); resolve(".report-pending").writeText("interrupted attempt retained")
    }
    private fun replaceKeepingOld(file: File) {
        val old = File(file.parentFile, file.name + ".preserved-original")
        Files.move(file.toPath(), old.toPath())
        Files.copy(old.toPath(), file.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
    }

    @Test fun productionOriginsAreFreshReviewCompilation() {

        val expected = System.getProperty("f159.review.classes")
        org.junit.Assume.assumeNotNull(expected)
        for (type in listOf(MirrorMetadataAuthority::class.java, MirrorSqliteRollbackRecovery::class.java,
            MirrorSqliteForensicCandidate::class.java, MirrorInterruptedRecovery::class.java,
            MirrorMetadataProcessLock::class.java, MirrorRecoveryWriterFence::class.java,
            AnchoredWorkspaceFiles::class.java, SqliteEvidenceReuseIndependentTest::class.java)) {
            val actual = type.getResource(type.simpleName + ".class")!!.toURI().path
            println("ORIGIN ${type.name} $actual")
            assertEquals(expected + "/" + type.name.replace('.', '/') + ".class", actual)
        }
    }

    @Test fun firstBuildAtEightCapturesRepeatsAcrossFreshAuthoritiesWithoutAllocating() = runBlocking {
        val f = fixture(); repeat(8) { f.authority().captureSqliteRollbackEvidence {} }
        val original = f.originals(); val preserved = f.preserved()
        val first = MirrorInterruptedRecovery(f.source, f.working).buildSqliteForensicCandidate {}
        assertEquals(8, f.captures().size); f.assertKept(preserved)
        val completed = f.preserved()
        repeat(3) { assertEquals(first, f.authority().buildSqliteForensicCandidate {}) }
        assertEquals(completed, f.preserved()); assertEquals(original, f.originals())
        assertTrue(first.directory.resolve("report").readText().contains("ACTIVATION_AND_CLEANUP_DENIED"))
        assertNotNull(runCatching { MirrorMetadataAuthority(f.directory, f.source, f.working).load() }.exceptionOrNull())
        Unit
    }

    @Test fun lostReturnAtCapacityReopensExactlyPublishedCandidate() {
        val f = fixture(); repeat(8) { f.authority().captureSqliteRollbackEvidence {} }
        val failure = runCatching { f.authority { if (it == "forensic-after-report-publication") error("independent lost return") }
            .buildSqliteForensicCandidate {} }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("independent lost return"))
        val published = f.directory.walkTopDown().single { it.name == "report" }.parentFile
        val before = f.preserved()
        assertEquals(published, f.authority().buildSqliteForensicCandidate {}.directory)
        assertEquals(before, f.preserved()); assertEquals(8, f.captures().size)
    }

    @Test fun changedCurrentPairCapturesFreshWhenRoomAndPreservesHistory() {
        val f = fixture(); val first = f.authority().buildSqliteForensicCandidate {}
        val evidence = first.directory.parentFile.walkTopDown().filter { it.isFile }.associateWith { hash(it.readBytes()) }
        changeSource(f); val original = f.originals()
        val second = f.authority().buildSqliteForensicCandidate {}
        assertNotEquals(first.directory.parentFile, second.directory.parentFile)
        assertEquals(2, f.captures().size); assertEquals(original, f.originals())
        evidence.forEach { (file, sha) -> assertEquals(sha, hash(file.readBytes())) }
    }

    @Test fun changedPairAtCapacityFailsWithoutMutation() {
        val f = fixture(); repeat(8) { f.authority().captureSqliteRollbackEvidence {} }
        changeSource(f); val before = f.preserved()
        assertNotNull(runCatching { f.authority().buildSqliteForensicCandidate {} }.exceptionOrNull())
        assertEquals(before, f.preserved()); assertEquals(8, f.captures().size)
    }

    @Test fun newerCorruptAndPartialEvidenceCannotHideOlderValidCapture() {
        val f = fixture(); val first = f.authority().captureSqliteRollbackEvidence {}
        val newer = f.authority().captureSqliteRollbackEvidence {}
        newer.evidenceDirectory.resolve("state.sqlite").appendText("corrupt retained copy")
        val partial = f.directory.resolve("sqlite-review-${UUID.randomUUID()}").apply { mkdir() }
        partial.resolve(".receipt-pending").writeText("incomplete")
        val before = f.preserved()
        val result = f.authority().buildSqliteForensicCandidate {}
        assertEquals(first.evidenceDirectory, result.directory.parentFile)
        assertEquals(3, f.captures().size); f.assertKept(before)
    }

    @Test fun corruptOnlyCaptureIsPreservedAndFreshIndependentCaptureUsesRemainingRoom() {
        val f = fixture(); val bad = f.authority().captureSqliteRollbackEvidence {}
        bad.evidenceDirectory.resolve("state.sqlite-journal").appendText("corrupt")
        val before = f.preserved()
        val result = f.authority().buildSqliteForensicCandidate {}
        assertNotEquals(bad.evidenceDirectory, result.directory.parentFile)
        assertEquals(2, f.captures().size); f.assertKept(before)
    }

    @Test fun coherentCandidateAndReportHashTamperingCannotBeReusedAsTransformation() {
        val f = fixture(); val first = f.authority().buildSqliteForensicCandidate {}
        val candidate = first.directory.resolve("candidate.sqlite")
        val expected = candidate.readBytes()
        candidate.writeBytes(expected.copyOf().also { it[200] = (it[200].toInt() xor 1).toByte() })
        val report = first.directory.resolve("report")
        val lines = report.readText().split('\n').toMutableList()
        lines[5] = hash(candidate.readBytes()); report.writeText(lines.joinToString("\n"))
        val before = f.preserved()
        val next = f.authority().buildSqliteForensicCandidate {}
        assertNotEquals(first.directory, next.directory)
        assertArrayEquals(expected, next.directory.resolve("candidate.sqlite").readBytes())
        f.assertKept(before); assertEquals(1, f.captures().size)
    }

    @Test fun completeCandidateIsReusableAtFourCandidateSlots() {
        val f = fixture(); val first = f.authority().buildSqliteForensicCandidate {}
        repeat(3) { addPartialCandidate(first.directory.parentFile) }
        val before = f.preserved()
        assertEquals(first, f.authority().buildSqliteForensicCandidate {})
        assertEquals(before, f.preserved()); assertEquals(1, f.captures().size)
    }

    @Test fun fourInvalidCandidatesPermitFreshCaptureWithinBothExistingLimits() {
        val f = fixture(); val evidence = f.authority().captureSqliteRollbackEvidence {}
        repeat(4) { addPartialCandidate(evidence.evidenceDirectory) }
        val before = f.preserved()
        val result = f.authority().buildSqliteForensicCandidate {}
        assertNotEquals(evidence.evidenceDirectory, result.directory.parentFile)
        f.assertKept(before); assertEquals(2, f.captures().size)
    }

    @Test fun everyCandidateSlotOccupiedAtEightCapturesRefusesWithoutAllocating() {
        val f = fixture()
        repeat(8) {
            val review = f.authority().captureSqliteRollbackEvidence {}
            repeat(4) { addPartialCandidate(review.evidenceDirectory) }
        }
        val before = f.preserved()
        assertNotNull(runCatching { f.authority().buildSqliteForensicCandidate {} }.exceptionOrNull())
        assertEquals(before, f.preserved()); assertEquals(8, f.captures().size)
    }

    @Test fun corruptOnlyMatchingCopiesAtCapacityRefuseWithoutAllocating() {
        val f = fixture()
        repeat(8) { f.authority().captureSqliteRollbackEvidence {}.evidenceDirectory.resolve("state.sqlite").appendText("corrupt") }
        val before = f.preserved()
        assertNotNull(runCatching { f.authority().buildSqliteForensicCandidate {} }.exceptionOrNull())
        assertEquals(before, f.preserved()); assertEquals(8, f.captures().size)
    }

    @Test fun wrongReceiptBindingIsNotAdoptedAndFreshCaptureLeavesItUntouched() {
        val f = fixture(); val bad = f.authority().captureSqliteRollbackEvidence {}
        val receipt = bad.evidenceDirectory.resolve("receipt")
        val lines = receipt.readText().split('\n').toMutableList(); lines[1] = "f".repeat(64)
        receipt.writeText(lines.joinToString("\n")); val before = f.preserved()
        val result = f.authority().buildSqliteForensicCandidate {}
        assertNotEquals(bad.evidenceDirectory, result.directory.parentFile); f.assertKept(before)
    }

    @Test fun staticSameByteSourceReplacementUsesCurrentValidationWithoutHistoricalInodeClaim() {
        val f = fixture(); val first = f.authority().buildSqliteForensicCandidate {}
        replaceKeepingOld(f.directory.resolve("state.sqlite"))
        replaceKeepingOld(f.directory.resolve("state.sqlite-journal"))
        val before = f.preserved()
        assertEquals(first, f.authority().buildSqliteForensicCandidate {})
        assertEquals(before, f.preserved()); assertEquals(1, f.captures().size)
    }

    @Test fun staticSameByteEvidenceAndReportReplacementUseCurrentValidationOnly() {
        val f = fixture(); val first = f.authority().buildSqliteForensicCandidate {}
        for (name in listOf("state.sqlite", "state.sqlite-journal", "receipt")) {
            replaceKeepingOld(first.directory.parentFile.resolve(name))
        }
        for (name in listOf("candidate.sqlite", "report")) replaceKeepingOld(first.directory.resolve(name))
        val before = f.preserved()
        assertEquals(first, f.authority().buildSqliteForensicCandidate {})
        assertEquals(before, f.preserved()); assertEquals(1, f.captures().size)
    }

    @Test fun hardlinkedCurrentSourceFailsWithoutCaptureOrOutsideMutation() {
        val f = fixture(); val db = f.directory.resolve("state.sqlite")
        val alias = f.root.resolve("outside-alias.sqlite"); Files.createLink(alias.toPath(), db.toPath())
        val before = f.preserved(); val outside = hash(alias.readBytes())
        assertNotNull(runCatching { f.authority().buildSqliteForensicCandidate {} }.exceptionOrNull())
        assertEquals(before, f.preserved()); assertEquals(outside, hash(alias.readBytes())); assertEquals(0, f.captures().size)
    }

    @Test fun walAndShmRemainUnsupportedEvenWithCompletedResult() {
        for (suffix in listOf("-wal", "-shm")) {
            val f = fixture(); f.authority().buildSqliteForensicCandidate {}
            f.directory.resolve("state.sqlite$suffix").writeText("do not replay")
            val before = f.preserved()
            assertNotNull(runCatching { f.authority().buildSqliteForensicCandidate {} }.exceptionOrNull())
            assertEquals(before, f.preserved())
        }
    }

    @Test fun unsupportedJournalCannotBecomeAcceptedThroughCachedReport() {
        val f = fixture(); val first = f.authority().buildSqliteForensicCandidate {}
        // Different current input with an unsupported extra segment must not return old result.
        f.directory.resolve("state.sqlite-journal").appendBytes(ByteArray(512))
        val before = f.preserved()
        assertNotNull(runCatching { f.authority().buildSqliteForensicCandidate {} }.exceptionOrNull())
        f.assertKept(before); assertTrue(first.directory.resolve("report").exists())
    }

    @Test fun candidateAndEvidenceReplacementAtOuterReturnCannotReturnStaleResult() {
        for (target in listOf("candidate.sqlite", "report", "evidence-db", "receipt")) {
            val f = fixture(); val first = f.authority().buildSqliteForensicCandidate {}
            val file = when (target) {
                "evidence-db" -> first.directory.parentFile.resolve("state.sqlite")
                "receipt" -> first.directory.parentFile.resolve("receipt")
                else -> first.directory.resolve(target)
            }
            var replaced = false
            val outcome = runCatching { f.authority { point ->
                if (point == "sqlite-reuse-before-return" && !replaced) { replaced = true; replaceKeepingOld(file) }
            }.buildSqliteForensicCandidate {} }
            assertTrue("Return boundary reached", replaced)
            assertNotNull("During-call $target replacement must not return stale success", outcome.exceptionOrNull())
            assertEquals(1, f.captures().size)
        }
    }

    @Test fun candidateSidecarsAppearingDuringValidatedReuseNeverReturnSuccess() {
        for (point in listOf("forensic-reuse-after-validation", "forensic-reuse-before-force", "forensic-reuse-after-force")) {
            for (suffix in listOf("-wal", "-shm")) {
                val f = fixture(); val first = f.authority().buildSqliteForensicCandidate {}
                var changed = false
                val outcome = runCatching { f.authority { boundary ->
                    if (boundary == point && !changed) {
                        changed = true; first.directory.resolve("candidate.sqlite$suffix").writeText("new unsupported sidecar")
                    }
                }.buildSqliteForensicCandidate {} }
                assertTrue(changed)
                assertNotNull("Candidate $suffix appearing at $point must refuse", outcome.exceptionOrNull())
            }
        }
    }

    @Test fun cancellationAtEveryReuseCheckpointPropagatesAndExistingEvidenceStaysExact() {
        val points = listOf("sqlite-reuse-after-capture-validation", "sqlite-reuse-before-consumer",
            "sqlite-reuse-before-return", "forensic-reuse-after-validation", "forensic-reuse-before-force",
            "forensic-reuse-after-force")
        for (point in points) {
            val f = fixture(); f.authority().buildSqliteForensicCandidate {}
            val before = f.preserved()
            val cancelled = java.util.concurrent.CancellationException("independent cancellation at $point")
            val outcome = runCatching { f.authority { if (it == point) throw cancelled }.buildSqliteForensicCandidate {} }
            assertSame("Cancellation must not become a content miss", cancelled, outcome.exceptionOrNull())
            assertEquals(before, f.preserved())
        }
    }

    @Test fun sourceReplacementAtReuseBoundariesFailsWithoutReturningStaleResult() {
        for (point in listOf("sqlite-reuse-after-capture-validation", "sqlite-reuse-before-consumer",
            "sqlite-reuse-before-return", "forensic-reuse-after-validation", "forensic-reuse-before-force",
            "forensic-reuse-after-force")) {
            val f = fixture(); f.authority().buildSqliteForensicCandidate {}
            var replaced = false
            val outcome = runCatching { f.authority { if (it == point && !replaced) {
                replaced = true; replaceKeepingOld(f.directory.resolve("state.sqlite"))
            } }.buildSqliteForensicCandidate {} }
            assertTrue(replaced); assertNotNull("Source replacement at $point must fail", outcome.exceptionOrNull())
        }
    }

    @Test fun selectedEvidenceDirectoryReplacementIsNotTreatedAsHistoricalContentMiss() {
        for (point in listOf("sqlite-reuse-before-consumer", "forensic-reuse-after-validation", "sqlite-reuse-before-return")) {
            val f = fixture(); val first = f.authority().buildSqliteForensicCandidate {}
            val evidence = first.directory.parentFile
            var replaced = false
            val outcome = runCatching { f.authority { if (it == point && !replaced) {
                replaced = true
                val preserved = f.root.resolve("preserved-evidence")
                Files.move(evidence.toPath(), preserved.toPath())
                preserved.copyRecursively(evidence)
            } }.buildSqliteForensicCandidate {} }
            assertTrue(replaced)
            assertNotNull("Selected directory replacement at $point must propagate", outcome.exceptionOrNull())
            assertTrue(f.root.resolve("preserved-evidence").resolve(first.directory.name).resolve("report").exists())
        }
    }

    @Test fun metadataLeaseReplacementAtReuseBoundaryPropagatesWithoutAllocation() {
        for (point in listOf("sqlite-reuse-before-consumer", "forensic-reuse-after-validation", "sqlite-reuse-before-return")) {
            val f = fixture(); f.authority().buildSqliteForensicCandidate {}
            val before = f.preserved(); var replaced = false
            val outcome = runCatching { f.authority { if (it == point && !replaced) {
                replaced = true; replaceKeepingOld(f.directory.resolve(".metadata-access.lock"))
            } }.buildSqliteForensicCandidate {} }
            assertTrue(replaced); assertNotNull(outcome.exceptionOrNull())
            f.assertKept(before); assertEquals(1, f.captures().size)
        }
    }

    @Test fun currentSourceSidecarsAppearingDuringReusePropagateWithoutNewCandidate() {
        for (point in listOf("sqlite-reuse-before-consumer", "forensic-reuse-after-force", "sqlite-reuse-before-return")) {
            for (suffix in listOf("-wal", "-shm")) {
                val f = fixture(); f.authority().buildSqliteForensicCandidate {}
                val before = f.preserved(); var changed = false
                val outcome = runCatching { f.authority { if (it == point && !changed) {
                    changed = true; f.directory.resolve("state.sqlite$suffix").writeText("unadmitted sidecar")
                } }.buildSqliteForensicCandidate {} }
                assertTrue(changed); assertNotNull(outcome.exceptionOrNull()); f.assertKept(before)
                assertEquals(1, f.captures().size)
            }
        }
    }

    @Test fun ioFailureInsideValidationCannotBecomeIneligibleContentAndTriggerFreshAllocation() {
        val f = fixture(); f.authority().buildSqliteForensicCandidate {}
        val before = f.preserved(); var selected = false; var calls = 0
        val expected = java.io.IOException("independent simulated source read failure")
        val outcome = runCatching { f.authority { if (it == "sqlite-reuse-before-consumer") selected = true }
            .buildSqliteForensicCandidate { if (selected && ++calls == 7) throw expected } }
        assertSame(expected, outcome.exceptionOrNull()); assertEquals(before, f.preserved())
    }

    @Test fun freshBuildFinalBoundaryRejectsRecordReplacementAndCandidateSidecars() {
        for (target in listOf("candidate.sqlite", "report", "receipt", "candidate.sqlite-wal", "candidate.sqlite-shm")) {
            val f = fixture(); var changed = false
            val outcome = runCatching { f.authority { if (it == "sqlite-reuse-before-return" && !changed) {
                changed = true
                val output = f.directory.walkTopDown().single { it.name == "report" }.parentFile
                when (target) {
                    "receipt" -> replaceKeepingOld(output.parentFile.resolve("receipt"))
                    "candidate.sqlite-wal", "candidate.sqlite-shm" -> output.resolve(target).writeText("late sidecar")
                    else -> replaceKeepingOld(output.resolve(target))
                }
            } }.buildSqliteForensicCandidate {} }
            assertTrue(changed)
            assertNotNull("Fresh-build late $target must not return success", outcome.exceptionOrNull())
            assertEquals(1, f.captures().size)
            assertTrue(f.directory.walkTopDown().any { it.name == "report" })
        }
    }

    @Test fun sameSizeMutationWithOldMtimeStillFailsCurrentVersionFence() {
        for (target in listOf("source", "evidence", "candidate")) {
            val f = fixture(); val first = f.authority().buildSqliteForensicCandidate {}
            val file = when (target) {
                "source" -> f.directory.resolve("state.sqlite")
                "evidence" -> first.directory.parentFile.resolve("state.sqlite")
                else -> first.directory.resolve("candidate.sqlite")
            }
            var changed = false
            val outcome = runCatching { f.authority { if (it == "forensic-reuse-after-validation" && !changed) {
                changed = true
                val modified = Files.getLastModifiedTime(file.toPath())
                file.writeBytes(file.readBytes().also { bytes -> bytes[200] = (bytes[200].toInt() xor 1).toByte() })
                Files.setLastModifiedTime(file.toPath(), modified)
            } }.buildSqliteForensicCandidate {} }
            assertTrue(changed); assertNotNull("$target old-mtime mutation must not pass", outcome.exceptionOrNull())
        }
    }

    @Test fun candidateMutationDuringTransformationCannotBecomeHistoricalContentMiss() {
        val f = fixture(); val first = f.authority().buildSqliteForensicCandidate {}
        val candidate = first.directory.resolve("candidate.sqlite")
        var changed = false
        val outcome = runCatching { f.authority().buildSqliteForensicCandidate {
            if (!changed && Thread.currentThread().stackTrace.any {
                it.className.startsWith("com.baystudio.droide.core.MirrorSqliteForensicCandidate") &&
                    it.methodName == "verifyTransformation"
            }) {
                changed = true
                val modified = Files.getLastModifiedTime(candidate.toPath())
                candidate.writeBytes(candidate.readBytes().also { it[200] = (it[200].toInt() xor 1).toByte() })
                Files.setLastModifiedTime(candidate.toPath(), modified)
            }
        } }
        assertTrue("Mutated after candidate observation while parser was active", changed)
        assertNotNull("Current mutation must propagate, not allocate a replacement candidate", outcome.exceptionOrNull())
        assertEquals(1, f.captures().size)
        assertEquals(1, first.directory.parentFile.listFiles()!!.count { it.name.startsWith("candidate-") })
    }

    companion object {
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
