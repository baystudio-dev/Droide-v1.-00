package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Tiny real host trees + deterministic forces/reads. No provider, native, process or runtime wiring. */
class SafLocalEffectsTest {
    private val roots = arrayListOf<File>()
    private val original = byteArrayOf(0, 1, -1, 127, 10, 42)
    private val intended = byteArrayOf(90, 0, -8, 11)
    @After fun cleanup() { roots.forEach { PathSecurity.deleteTreeNoFollow(it) } }

    @Test(timeout = 30_000) fun writePreservesOriginalAndIntendedBeforeVisibleThenVerifiedReceipt(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val helper = f.helper(guard)
            val prep = f.writePreparation()
            val effect = helper.prepare(prep)
            val plan = f.record(effect).plan.local!!
            assertArrayEquals(original, f.journal.file(f.operation, plan.previous!!.name).readBytes())
            assertArrayEquals(intended, f.journal.file(f.operation, plan.intended!!.name).readBytes())
            assertEquals(original.size + intended.size * 2L, f.operation.payloadBytes)
            helper.beforeAttempt(effect)
            f.publish()
            helper.returned(effect, f.receipt(prep))
            assertFalse(f.record(effect).localReturn!!.reportedDurable)
            assertNull(f.record(effect).localVerified)
            helper.verify(effect)
            assertTrue(f.record(effect).localVerified!!.forcesCompleted)
            assertTrue(f.forcedFiles.contains(f.target))
            assertArrayEquals(intended, f.target.readBytes())
            assertArrayEquals(original, f.journal.file(f.operation, plan.previous.name).readBytes())
            val recovered = f.reopen().recover(f.operation.id)
            assertFalse(recovered.activeInvocation)
            assertEquals(f.record(effect), recovered.effects.getValue(effect.action.id))
            assertEquals(f.operation.payloadBytes, recovered.payloadBytes)
            f.assertSentinel()
        }
    }

    @Test(timeout = 30_000) fun newWritePreservesAbsenceAndReportedStreamDurabilityIsSeparate(): Unit = runBlocking {
        val f = Fixture(existing = false)
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val h = f.helper(guard); val p = f.writePreparation(); val e = h.prepare(p)
            assertNull(f.record(e).plan.local!!.previous)
            h.beforeAttempt(e); f.publish(); h.returned(e, f.receipt(p).copy(durable = true))
            assertTrue(f.record(e).localReturn!!.reportedDurable)
            assertNull(f.record(e).localVerified)
            h.verify(e)
            assertTrue(f.forcedFiles.contains(f.target)); f.assertSentinel()
        }
    }

    @Test(timeout = 30_000) fun attemptAndReturnDoNotReadPayloadInventoryHistoryOrVisibleManifestBodies(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val h = f.helper(guard); val p = f.writePreparation(); val e = h.prepare(p)
            f.reads.clear(); h.beforeAttempt(e)
            assertTrue(f.reads.size <= 6)
            assertTrue(f.reads.all { it.name in setOf("anchor.json", "operation.json", SafOperationJournal.actionName(e.action.id, SafEffectPhase.PREPARED),
                SafOperationJournal.actionName(e.action.id, SafEffectPhase.ATTEMPTING)) })
            f.publish(); f.reads.clear(); h.returned(e, f.receipt(p))
            assertTrue(f.reads.size <= 6)
            assertTrue(f.reads.none { it.extension == "bin" || it.name.startsWith("local-") || it == f.manifest })
            f.assertSentinel()
        }
    }

    @Test(timeout = 30_000) fun sourceTargetAncestorAndManifestExchangeRefuseBeforeAttempt(): Unit = runBlocking {
        for (what in listOf("source", "target", "parent", "manifest")) {
            val f = Fixture(path = "dir/file")
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val h = f.helper(guard); val e = h.prepare(f.writePreparation())
                when (what) {
                    "source" -> f.stage.appendBytes(byteArrayOf(1))
                    "target" -> f.target.appendBytes(byteArrayOf(2))
                    "parent" -> { Files.move(f.target.parentFile.toPath(), f.workspace.resolve("saved-dir").toPath()); f.target.parentFile.mkdir() }
                    else -> f.manifest.appendText(" ")
                }
                val retained = f.evidence()
                assertTrue(what, runCatching { h.beforeAttempt(e) }.isFailure)
                assertEquals(SafEffectPhase.PREPARED, f.record(e).phase)
                f.assertRetained(retained); f.assertSentinel()
            }
        }
    }

    @Test(timeout = 30_000) fun consumedAndRestoredFailedAdmissionNeverRevives(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val h = f.helper(guard); val e = h.prepare(f.writePreparation())
            val time = Files.getLastModifiedTime(f.stage.toPath())
            f.stage.appendBytes(byteArrayOf(1))
            val retained = f.evidence()
            assertTrue(runCatching { f.journal.attemptLocal(e.admission) }.isFailure)
            f.stage.writeBytes(intended); Files.setLastModifiedTime(f.stage.toPath(), time)
            assertTrue(runCatching { f.journal.attemptLocal(e.admission) }.isFailure)
            f.assertRetained(retained); assertTrue(f.operation.poisoned)
        }
    }

    @Test(timeout = 30_000) fun successfulAttemptTokenCannotBeReusedOrReplayed(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val h = f.helper(guard); val e = h.prepare(f.writePreparation())
            h.beforeAttempt(e); val retained = f.evidence()
            assertTrue(runCatching { f.journal.attemptLocal(e.admission) }.isFailure)
            f.assertRetained(retained); assertArrayEquals(original, f.target.readBytes())
        }
    }

    @Test(timeout = 30_000) fun identicalPreparedRecordReplacementInvalidatesTheOneUseAdmission(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val h = f.helper(guard); val e = h.prepare(f.writePreparation())
            val record = f.journal.file(f.operation, SafOperationJournal.actionName(e.action.id, SafEffectPhase.PREPARED))
            val saved = f.root.resolve("original-prepared")
            Files.move(record.toPath(), saved.toPath())
            record.writeBytes(saved.readBytes())
            Files.setLastModifiedTime(record.toPath(), Files.getLastModifiedTime(saved.toPath()))
            val retained = f.evidence()
            assertTrue(runCatching { h.beforeAttempt(e) }.isFailure)
            assertTrue(e.admission.consumed)
            assertEquals(SafEffectPhase.PREPARED, f.record(e).phase)
            f.assertRetained(retained)
            assertArrayEquals(saved.readBytes(), record.readBytes())
            assertArrayEquals(original, f.target.readBytes()); f.assertSentinel()
        }
    }

    @Test(timeout = 30_000) fun interveningOwnedWriteInvalidatesTheOnePendingToken(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val h = f.helper(guard); val e = h.prepare(f.writePreparation())
            f.journal.preserve(f.operation, f.workspace.resolve("sentinel.bin"))
            val retained = f.evidence()
            assertTrue(runCatching { h.beforeAttempt(e) }.isFailure)
            f.assertRetained(retained); assertArrayEquals(original, f.target.readBytes())
        }
    }

    @Test(timeout = 30_000) fun foreignJournalCannotConsumeOrPoisonOwnedLocalToken(): Unit = runBlocking {
        val f = Fixture(); val foreign = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val h = f.helper(guard); val e = h.prepare(f.writePreparation())
            assertTrue(runCatching { foreign.journal.attemptLocal(e.admission) }.isFailure)
            assertFalse(f.operation.poisoned); assertFalse(e.admission.consumed)
            h.beforeAttempt(e)
            assertEquals(SafEffectPhase.ATTEMPTING, f.record(e).phase)
        }
    }

    @Test(timeout = 30_000) fun staleGuardAndRecoveredOperationCannotAuthorizeLocalWork(): Unit = runBlocking {
        val f = Fixture()
        lateinit var e: SafLocalEffects.Effect
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard -> e = f.helper(guard).prepare(f.writePreparation()) }
        assertTrue(runCatching { f.journal.attemptLocal(e.admission) }.isFailure)
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val reopened = f.reopen(); val recovered = reopened.recover(f.operation.id)
            assertTrue(runCatching { SafLocalEffects(guard, reopened, recovered) {} }.isFailure)
        }
        assertArrayEquals(original, f.target.readBytes())
    }

    @Test(timeout = 30_000) fun wrongRootHelperIsRejectedWithoutPoisoningOriginal(): Unit = runBlocking {
        val f = Fixture(); val other = Fixture()
        WorkspaceFileMutationLocks.withRoot(other.workspace) { guard -> assertTrue(runCatching { f.helper(guard) }.isFailure) }
        assertFalse(f.operation.poisoned); f.assertSentinel()
    }

    @Test(timeout = 30_000) fun deepCopyCancellationRetainsOriginalAndPartialEvidence(): Unit = runBlocking {
        val f = Fixture(); val cancelled = CancellationException("cancel private capture")
        var copying = false
        f.onRead = { if (it == f.stage) copying = true }
        f.active = { if (copying) throw cancelled }
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val failure = runCatching { f.helper(guard).prepare(f.writePreparation()) }.exceptionOrNull()
            assertSame(cancelled, failure)
            assertArrayEquals(original, f.target.readBytes()); assertArrayEquals(intended, f.stage.readBytes()); f.assertSentinel()
            assertTrue(f.operation.effects.isEmpty())
        }
    }

    @Test(timeout = 30_000) fun cancellationAfterPublicationStillRecordsRawReceiptBeforeDeepVerification(): Unit = runBlocking {
        val f = Fixture(); val cancelled = CancellationException("after publication")
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val h = f.helper(guard); val p = f.writePreparation(); val e = h.prepare(p)
            h.beforeAttempt(e); f.publish(); f.active = { throw cancelled }
            h.returned(e, f.receipt(p))
            assertSame(cancelled, runCatching { h.verify(e) }.exceptionOrNull())
            assertEquals(SafEffectPhase.RETURNED, f.record(e).phase)
            assertArrayEquals(intended, f.target.readBytes()); f.assertSentinel()
        }
    }

    @Test(timeout = 30_000) fun rawReturnPrecedesChangedDestinationOrManifestFreshnessFailure(): Unit = runBlocking {
        for (what in listOf("destination", "manifest", "parent")) {
            val f = Fixture(path = "dir/file")
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val h = f.helper(guard); val p = f.writePreparation(); val e = h.prepare(p)
                h.beforeAttempt(e); f.publish(); val receipt = f.receipt(p)
                when (what) {
                    "destination" -> { f.target.delete(); f.target.writeBytes(original) }
                    "parent" -> { Files.move(f.target.parentFile.toPath(), f.workspace.resolve("saved").toPath()); f.target.parentFile.mkdir() }
                    else -> f.manifest.appendText(" ")
                }
                h.returned(e, receipt)
                assertEquals(receipt.destinationIdentity, f.record(e).localReturn!!.destinationKey)
                if (what != "manifest") assertTrue(runCatching { h.verify(e) }.isFailure)
                assertTrue(f.journal.file(f.operation, SafOperationJournal.actionName(e.action.id, SafEffectPhase.RETURNED)).exists())
                f.assertSentinel()
            }
        }
    }

    @Test(timeout = 30_000) fun localThrownFailureSurvivesReceiptFailureWithoutSelfSuppression(): Unit = runBlocking {
        val f = Fixture(); val injected = IOException("same failure")
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val h = f.helper(guard); val e = h.prepare(f.writePreparation()); h.beforeAttempt(e)
            f.failForce = { if (it.name == SafOperationJournal.actionName(e.action.id, SafEffectPhase.RETURNED)) throw injected }
            val failure = runCatching { h.threw(e, injected) }.exceptionOrNull()
            assertSame(injected, failure); assertTrue(injected.suppressed.isEmpty())
            assertArrayEquals(original, f.target.readBytes()); f.assertSentinel()
        }
    }

    @Test(timeout = 30_000) fun throwingInvocationKeepsThrewRecordAndCannotVerify(): Unit = runBlocking {
        val f = Fixture(); val injected = IOException("mutated then threw")
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val h = f.helper(guard); val e = h.prepare(f.writePreparation()); h.beforeAttempt(e); f.publish()
            assertSame(injected, runCatching { h.threw(e, injected) }.exceptionOrNull())
            assertEquals(SafEffectReturn.THREW, f.record(e).result)
            assertTrue(runCatching { h.verify(e) }.isFailure)
            assertArrayEquals(intended, f.target.readBytes())
        }
    }

    @Test(timeout = 30_000) fun fileOrDirectoryForceFailureNeverProducesVerified(): Unit = runBlocking {
        for (directory in listOf(false, true)) {
            val f = Fixture()
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val h = f.helper(guard); val p = f.writePreparation(); val e = h.prepare(p)
                h.beforeAttempt(e); f.publish(); h.returned(e, f.receipt(p))
                val failedTarget = if (directory) f.workspace else f.target
                f.failForce = { if (it == failedTarget) throw IOException("force failure") }
                assertTrue(runCatching { h.verify(e) }.isFailure)
                assertEquals(SafEffectPhase.RETURNED, f.record(e).phase)
                assertArrayEquals(intended, f.target.readBytes()); f.assertSentinel()
            }
        }
    }

    @Test(timeout = 30_000) fun partialMkdirsIsRetainedAsRejectedNotNoEffect(): Unit = runBlocking {
        val f = Fixture(path = "new/deep", kind = SafOperationKind.CREATE_DIRECTORY, existing = false, makeParent = false)
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val h = f.helper(guard); val p = WorkspaceLocalPreparation(WorkspaceLocalEffect.CREATE_DIRECTORY, f.path); val e = h.prepare(p)
            h.beforeAttempt(e); f.workspace.resolve("new").mkdir()
            h.returned(e, WorkspaceLocalReceipt(p, true, null, successful = false))
            assertEquals(SafEffectReturn.REJECTED, f.record(e).result)
            assertTrue(runCatching { h.verify(e) }.isFailure)
            assertTrue(f.workspace.resolve("new").isDirectory); f.assertSentinel()
        }
    }

    @Test(timeout = 30_000) fun createdParentsAreVerifiedBeforeNextWritePreparation(): Unit = runBlocking {
        val f = Fixture(path = "new/deep/file", existing = false, makeParent = false)
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val h = f.helper(guard); val p = WorkspaceLocalPreparation(WorkspaceLocalEffect.CREATE_PARENTS, "new/deep"); val e = h.prepare(p)
            h.beforeAttempt(e); f.target.parentFile.mkdirs()
            h.returned(e, WorkspaceLocalReceipt(p, true, f.storage.key(f.target.parentFile, true)))
            // Exact C1 ordering: its owned stage exists before the next prepareLocal callback.
            f.stage.writeBytes(intended)
            val write = h.prepare(f.writePreparation())
            assertEquals(SafEffectPhase.VERIFIED, f.record(e).phase)
            assertEquals(SafEffectPhase.PREPARED, f.record(write).phase)
            assertFalse(f.operation.firstAttemptChecked)
            f.assertSentinel()
        }
    }

    @Test(timeout = 30_000) fun createdDirectoryGainingContentsDuringForceIsNotVerified(): Unit = runBlocking {
        val f = Fixture(path = "new", kind = SafOperationKind.CREATE_DIRECTORY, existing = false)
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val h = f.helper(guard); val p = WorkspaceLocalPreparation(WorkspaceLocalEffect.CREATE_DIRECTORY, "new"); val e = h.prepare(p)
            h.beforeAttempt(e); f.target.mkdir(); h.returned(e, WorkspaceLocalReceipt(p, true, f.storage.key(f.target, true)))
            f.failForce = { if (it == f.target) f.target.resolve("newer").writeBytes(original) }
            assertTrue(runCatching { h.verify(e) }.isFailure)
            assertArrayEquals(original, f.target.resolve("newer").readBytes())
            assertEquals(SafEffectPhase.RETURNED, f.record(e).phase)
        }
    }

    @Test(timeout = 30_000) fun movedTreeForcesEveryFileAndRetainsNestedEmptyDirectories(): Unit = runBlocking {
        val f = Fixture(path = "moved", kind = SafOperationKind.MOVE, source = "tree", existing = false)
        f.makeTree()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val h = f.helper(guard); val p = f.movePreparation(); val e = h.prepare(p)
            h.beforeAttempt(e); f.move(); h.returned(e, f.receipt(p)); h.verify(e)
            assertTrue(f.target.resolve("empty").isDirectory)
            assertArrayEquals(original, f.target.resolve("node_modules/pkg/index.js").readBytes())
            assertTrue(f.forcedFiles.contains(f.target.resolve("node_modules/pkg/index.js")))
            assertEquals(SafEffectPhase.VERIFIED, f.record(e).phase)
            f.assertSentinel()
        }
    }

    @Test(timeout = 30_000) fun deleteStageRetainsCompleteInventoryAndChargesMovedBytesOnlyOnce(): Unit = runBlocking {
        val f = Fixture(path = ".droide-tmp-delete", kind = SafOperationKind.DELETE, source = "tree", existing = false)
        f.makeTree()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val h = f.helper(guard); val p = f.movePreparation(); val e = h.prepare(p)
            assertEquals(original.size.toLong(), f.operation.payloadBytes)
            h.beforeAttempt(e); f.move(); h.returned(e, f.receipt(p)); h.verify(e)
            assertTrue(f.target.exists()) // No cleanup facility exists here.
            val recovered = f.reopen().recover(f.operation.id)
            assertEquals(original.size.toLong(), recovered.payloadBytes)
            assertArrayEquals(original, f.target.resolve("node_modules/pkg/index.js").readBytes())
        }
    }

    @Test(timeout = 30_000) fun changedOrAddedMovedDescendantsRemainRetained(): Unit = runBlocking {
        for (added in listOf(false, true)) {
            val f = Fixture(path = ".droide-tmp-delete", kind = SafOperationKind.DELETE, source = "tree", existing = false)
            f.makeTree()
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val h = f.helper(guard); val p = f.movePreparation(); val e = h.prepare(p)
                h.beforeAttempt(e); f.move(); h.returned(e, f.receipt(p))
                val changed = f.target.resolve(if (added) "newer" else "node_modules/pkg/index.js")
                changed.writeBytes(intended)
                assertTrue(runCatching { h.verify(e) }.isFailure)
                assertArrayEquals(intended, changed.readBytes()); assertEquals(SafEffectPhase.RETURNED, f.record(e).phase)
                assertFalse(f.workspace.resolve("tree").exists()); f.assertSentinel()
            }
        }
    }

    @Test(timeout = 30_000) fun recreatedMoveSourceRemainsAlongsideMovedOriginal(): Unit = runBlocking {
        val f = Fixture(path = "moved", kind = SafOperationKind.MOVE, source = "tree", existing = false); f.makeTree()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val h = f.helper(guard); val p = f.movePreparation(); val e = h.prepare(p)
            h.beforeAttempt(e); f.move(); h.returned(e, f.receipt(p)); f.workspace.resolve("tree").writeBytes(intended)
            assertTrue(runCatching { h.verify(e) }.isFailure)
            assertArrayEquals(intended, f.workspace.resolve("tree").readBytes())
            assertArrayEquals(original, f.target.resolve("node_modules/pkg/index.js").readBytes())
        }
    }

    @Test(timeout = 30_000) fun symlinkInventoryNeverTraversesOrForcesItsTarget(): Unit = runBlocking {
        val f = Fixture(path = "moved", kind = SafOperationKind.MOVE, source = "tree", existing = false); f.makeTree()
        val outside = f.root.resolve("outside").apply { writeBytes(intended) }
        Files.createSymbolicLink(f.workspace.resolve("tree/link").toPath(), outside.toPath())
        Files.createSymbolicLink(f.workspace.resolve("tree/dangling").toPath(), f.root.resolve("missing").toPath())
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val h = f.helper(guard); val p = f.movePreparation(); val e = h.prepare(p)
            val image = f.journal.localInventory(f.operation, f.record(e).plan.local!!) {}
            assertEquals(2, image.entries.count { it.version.kind == SafLocalKind.SYMLINK })
            assertTrue(f.reads.none { it == outside })
            h.beforeAttempt(e); f.move(); h.returned(e, f.receipt(p)); h.verify(e)
            assertFalse(f.forcedFiles.contains(outside)); assertArrayEquals(intended, outside.readBytes())
        }
    }

    @Test(timeout = 30_000) fun scopeDoesNotExpandToDeleteSiblingOrMissingParent(): Unit = runBlocking {
        val deletion = Fixture(path = ".droide-tmp-delete", kind = SafOperationKind.DELETE, source = "tree", existing = false, scopes = listOf("tree"))
        deletion.makeTree()
        WorkspaceFileMutationLocks.withRoot(deletion.workspace) { guard ->
            val before = deletion.evidence()
            assertTrue(runCatching { deletion.helper(guard).prepare(deletion.movePreparation()) }.isFailure)
            deletion.assertRetained(before); assertTrue(deletion.workspace.resolve("tree").exists())
        }
        val parents = Fixture(path = "a/b/file", existing = false, makeParent = false, scopes = listOf("a/b/file"))
        WorkspaceFileMutationLocks.withRoot(parents.workspace) { guard ->
            assertTrue(runCatching { parents.helper(guard).prepare(WorkspaceLocalPreparation(WorkspaceLocalEffect.CREATE_PARENTS, "a/b")) }.isFailure)
            assertFalse(parents.workspace.resolve("a").exists())
        }
    }

    @Test(timeout = 30_000) fun payloadEvidenceActionAndIndexCapsRejectBeforeAttempt(): Unit = runBlocking {
        for (cap in listOf("payload", "evidence", "action", "index")) {
            val limits = if (cap == "index") SafJournalLimits(treeIndexBytes = 128) else SafJournalLimits()
            val reserve = when (cap) {
                "payload" -> SafJournalReservation(8, 1024 * 1024, 1, evidenceFiles = 16)
                "evidence" -> SafJournalReservation(8, 1024 * 1024, 4096, evidenceFiles = 0)
                "action" -> SafJournalReservation(0, 1024 * 1024, 4096, evidenceFiles = 16)
                else -> SafJournalReservation(8, 1024 * 1024, 4096, evidenceFiles = 16)
            }
            val f = if (cap == "index") Fixture(path = "moved", kind = SafOperationKind.MOVE, source = "tree", existing = false, limits = limits, reservation = reserve)
                else Fixture(reservation = reserve)
            if (cap == "index") f.makeTree()
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                assertTrue(cap, runCatching { f.helper(guard).prepare(if (cap == "index") f.movePreparation() else f.writePreparation()) }.isFailure)
                assertTrue(f.operation.effects.values.none { it.phase >= SafEffectPhase.ATTEMPTING }); f.assertSentinel()
            }
        }
    }

    @Test(timeout = 30_000) fun localAttemptDoesNotCloseLaterProviderChildDeclaration(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val h = f.helper(guard); val p = f.writePreparation(); val e = h.prepare(p)
            h.beforeAttempt(e); f.publish(); h.returned(e, f.receipt(p)); h.verify(e)
            assertFalse(f.operation.firstAttemptChecked)
            val root = SafOwnedDocument("content://local.test/tree/root/document/root", "root", "root", SafOperationJournal.DIRECTORY_MIME, "root")
            val parent = f.journal.prepare(f.operation, SafEffectPlan(SafEffectKind.CREATE_DIRECTORY, "a", null, root, null, null, null,
                requestedMimeType = SafOperationJournal.DIRECTORY_MIME))
            val child = f.journal.prepare(f.operation, SafEffectPlan(SafEffectKind.CREATE_DIRECTORY, "a/b", null, null, null, null, null,
                requestedMimeType = SafOperationJournal.DIRECTORY_MIME, parentCreationActionId = parent.id))
            assertEquals(SafEffectPhase.PREPARED, f.operation.effects.getValue(child.id).phase)
        }
    }

    @Test(timeout = 30_000) fun genericPhaseApiCannotBypassLocalAttemptAdmission(): Unit = runBlocking {
        val f = Fixture()
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val e = f.helper(guard).prepare(f.writePreparation()); val retained = f.evidence()
            assertTrue(runCatching { f.journal.attempting(e.action) }.isFailure)
            f.assertRetained(retained); assertArrayEquals(original, f.target.readBytes())
        }
    }

    @Test(timeout = 30_000) fun protectedDeleteUsesOneMoveAndNoExtraPayloadCopy(): Unit = runBlocking {
        val f = Fixture(path = "unused", kind = SafOperationKind.DELETE, source = "tree", existing = false, scopes = listOf("tree"))
        f.makeTree()
        val base = f.workspace.resolve(".droide/saf-delete-custody").apply { mkdir() }
        val container = base.resolve(f.operation.id)
        f.storage.createDirectory(container)
        val binding = SafDeleteCustody(".droide/saf-delete-custody/${f.operation.id}", f.storage.key(container, true))
        val destination = container.resolve("original")
        val source = f.workspace.resolve("tree")
        val sourceKey = f.storage.key(source, true)
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val h = f.helper(guard)
            val p = WorkspaceLocalPreparation(WorkspaceLocalEffect.DELETE_STAGE, "${binding.directory}/original", "tree", sourceKey)
            val e = h.prepare(p, binding)
            assertEquals(5, f.record(e).version)
            assertEquals("tree", f.record(e).plan.path)
            assertTrue(source.isDirectory); assertFalse(destination.exists())
            assertTrue(f.evidence().keys.none { it.startsWith("payload-") })
            val retainedBytes = f.operation.payloadBytes
            h.beforeAttempt(e)
            Files.move(source.toPath(), destination.toPath()) // The one original repository move.
            h.returned(e, WorkspaceLocalReceipt(p, true, f.storage.key(destination, true)))
            h.verify(e)
            assertEquals(sourceKey, f.storage.key(destination, true))
            assertArrayEquals(original, destination.resolve("node_modules/pkg/index.js").readBytes())
            assertEquals(original.size.toLong(), retainedBytes)
            assertEquals(retainedBytes, f.operation.payloadBytes)
            assertTrue(f.evidence().keys.none { it.startsWith("payload-") })
            assertEquals(1, f.forcedFiles.count { it.toPath().startsWith(destination.toPath()) })
            assertEquals(f.record(e), f.reopen().recover(f.operation.id).effects.getValue(e.action.id))
            f.assertSentinel()
        }
    }

    @Test(timeout = 30_000) fun protectedDeleteRejectsForgedOperationBindingKeyAndOccupiedLeaf(): Unit = runBlocking {
        for (damage in listOf("operation", "key", "occupied")) {
            val f = Fixture(path = "unused", kind = SafOperationKind.DELETE, source = "tree", existing = false, scopes = listOf("tree"))
            f.makeTree()
            val id = if (damage == "operation") java.util.UUID.randomUUID().toString() else f.operation.id
            val relative = ".droide/saf-delete-custody/$id"
            val container = f.workspace.resolve(relative).apply { mkdirs() }
            val binding = SafDeleteCustody(relative, if (damage == "key") "unowned-key" else f.storage.key(container, true))
            if (damage == "occupied") container.resolve("original").writeBytes(intended)
            val before = f.evidence()
            WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
                val p = WorkspaceLocalPreparation(WorkspaceLocalEffect.DELETE_STAGE, "$relative/original", "tree", f.storage.key(f.workspace.resolve("tree"), true))
                assertTrue(damage, runCatching { f.helper(guard).prepare(p, binding) }.isFailure)
                assertTrue(f.operation.effects.isEmpty())
                f.assertRetained(before)
                assertArrayEquals(original, f.workspace.resolve("tree/node_modules/pkg/index.js").readBytes())
                if (damage == "occupied") assertArrayEquals(intended, container.resolve("original").readBytes())
            }
        }
    }

    @Test(timeout = 30_000) fun protectedDeleteContainerReplacementCannotPassTheAttemptGate(): Unit = runBlocking {
        val f = Fixture(path = "unused", kind = SafOperationKind.DELETE, source = "tree", existing = false)
        f.makeTree()
        val relative = ".droide/saf-delete-custody/${f.operation.id}"
        val container = f.workspace.resolve(relative).apply { mkdirs() }
        val binding = SafDeleteCustody(relative, f.storage.key(container, true))
        WorkspaceFileMutationLocks.withRoot(f.workspace) { guard ->
            val h = f.helper(guard)
            val p = WorkspaceLocalPreparation(WorkspaceLocalEffect.DELETE_STAGE, "$relative/original", "tree", f.storage.key(f.workspace.resolve("tree"), true))
            val e = h.prepare(p, binding)
            Files.move(container.toPath(), f.root.resolve("retained-container").toPath())
            container.mkdir(); container.resolve("unowned.bin").writeBytes(intended)
            val before = f.evidence()
            assertTrue(runCatching { h.beforeAttempt(e) }.isFailure)
            assertEquals(SafEffectPhase.PREPARED, f.record(e).phase)
            f.assertRetained(before)
            assertArrayEquals(intended, container.resolve("unowned.bin").readBytes())
            assertArrayEquals(original, f.workspace.resolve("tree/node_modules/pkg/index.js").readBytes())
        }
    }

    private inner class Fixture(
        val path: String = "file", val kind: SafOperationKind = SafOperationKind.WRITE, val source: String? = null,
        existing: Boolean = true, makeParent: Boolean = true, val scopes: List<String> = listOf(""),
        val limits: SafJournalLimits = SafJournalLimits(),
        reservation: SafJournalReservation = SafJournalReservation(8, 1024 * 1024, 1024 * 1024, evidenceFiles = 16),
    ) {
        val root = Files.createTempDirectory("saf-local-").toFile().also { roots += it }
        val workspace = root.resolve("workspace").apply { mkdir() }
        val manifest = workspace.resolve(".droide/saf_manifest.json")
        val target = workspace.resolve(path)
        val stage = target.parentFile.resolve(".droide-tmp-stage")
        val reads = arrayListOf<File>()
        val forcedFiles = arrayListOf<File>()
        var onRead: (File) -> Unit = {}
        var failForce: (File) -> Unit = {}
        var active: () -> Unit = {}
        val storage = SafOperationStorage(
            forceDirectory = { file -> failForce(file); FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) } },
            forceFile = { file -> forcedFiles += file; failForce(file); FileChannel.open(file.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) } },
            replaceAtomically = { from, to -> Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); Unit },
            beforeRead = { reads += it; onRead(it) },
        )
        val journal: SafOperationJournal
        val operation: SafOperationJournal.Operation
        init {
            workspace.resolve("sentinel.bin").writeBytes(original)
            manifest.parentFile.mkdir()
            manifest.writeBytes(SafJournalCodec.encode(SafLegacyTrustedManifest.serializer(), SafLegacyTrustedManifest(entries = sortedMapOf("sentinel.bin" to "f:${original.safDigest()}"))))
            if (makeParent) target.parentFile.mkdirs()
            if (existing) target.writeBytes(original)
            if (kind == SafOperationKind.WRITE && makeParent) stage.writeBytes(intended)
            journal = SafOperationJournal.create(workspace, "content://local.test/tree/root", root.resolve("journal"), storage, limits)
            val paths = when (kind) { SafOperationKind.MOVE -> listOf(requireNotNull(source), path); SafOperationKind.DELETE -> listOf(requireNotNull(source)); else -> listOf(path) }
            operation = journal.begin(SafOperationRequest(kind, paths), scopes, reservation)
        }
        fun helper(guard: WorkspaceMutationGuard) = SafLocalEffects(guard, journal, operation) { active() }
        fun writePreparation() = WorkspaceLocalPreparation(WorkspaceLocalEffect.WRITE, path,
            workspace.toPath().relativize(stage.toPath()).toString(), storage.key(stage, false), intended.size.toLong(), intended.safDigest())
        fun movePreparation() = WorkspaceLocalPreparation(if (kind == SafOperationKind.DELETE) WorkspaceLocalEffect.DELETE_STAGE else WorkspaceLocalEffect.MOVE,
            path, requireNotNull(source), storage.key(workspace.resolve(source), true))
        fun receipt(prep: WorkspaceLocalPreparation) = WorkspaceLocalReceipt(prep, true, SafLocalFiles.version(storage, target)?.key)
        fun publish() { Files.move(stage.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        fun move() { Files.move(workspace.resolve(requireNotNull(source)).toPath(), target.toPath()) }
        fun makeTree() { workspace.resolve("tree/empty").mkdirs(); workspace.resolve("tree/node_modules/pkg").mkdirs(); workspace.resolve("tree/node_modules/pkg/index.js").writeBytes(original) }
        fun record(effect: SafLocalEffects.Effect) = operation.effects.getValue(effect.action.id)
        fun evidence() = journal.directory.resolve(operation.id).listFiles()!!.associate { it.name to it.readBytes() }
        fun assertRetained(before: Map<String, ByteArray>) { assertEquals(before.keys, evidence().keys); before.forEach { (name, bytes) -> assertArrayEquals(bytes, journal.file(operation, name).readBytes()) } }
        fun assertSentinel() = assertArrayEquals(original, workspace.resolve("sentinel.bin").readBytes())
        fun reopen() = SafOperationJournal.open(workspace, "content://local.test/tree/root", journal.directory, storage, limits)
    }
}
