package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** C1 fake-participant tests only: no SAF wiring, provider, native or power-loss claims. */
class FileRepositoryMutationLifecycleTest {
    private lateinit var root: File
    @Before fun setup() { root = Files.createTempDirectory("repo-lifecycle-").toFile() }
    @After fun cleanup() { PathSecurity.deleteTreeNoFollow(root) }

    private class Probe : WorkspaceMutationParticipant {
        val sessions = mutableListOf<Session>()
        var onBegin: suspend (WorkspaceMutationGuard, FileRepository.Mutation) -> Unit = { _, _ -> }
        var onPrepare: suspend (WorkspaceLocalPreparation) -> Unit = {}
        var onAttempt: (WorkspaceLocalPreparation) -> Unit = {}
        var onReceipt: (WorkspaceLocalReceipt) -> Unit = {}
        var onSync: suspend Session.() -> Unit = {}
        var onFinish: (WorkspaceMutationOutcome) -> Unit = {}
        override suspend fun begin(guard: WorkspaceMutationGuard, mutation: FileRepository.Mutation): WorkspaceMutationSession {
            onBegin(guard, mutation)
            return Session(guard, mutation).also { sessions += it }
        }
        inner class Session(guard: WorkspaceMutationGuard, mutation: FileRepository.Mutation) : WorkspaceMutationSession(guard, mutation) {
            val receipts = mutableListOf<WorkspaceLocalReceipt>()
            val outcomes = mutableListOf<WorkspaceMutationOutcome>()
            override suspend fun prepareLocal(preparation: WorkspaceLocalPreparation) = onPrepare(preparation)
            override fun beforeLocalAttempt(preparation: WorkspaceLocalPreparation) = onAttempt(preparation)
            override fun localReturned(receipt: WorkspaceLocalReceipt) { receipts += receipt; onReceipt(receipt) }
            override suspend fun synchronize() = onSync(this)
            override fun finish(outcome: WorkspaceMutationOutcome) { outcomes += outcome; onFinish(outcome) }
        }
    }

    private fun repository(probe: Probe, after: (suspend (FileRepository.Mutation) -> Unit)? = null) =
        FileRepository(root, onMutation = after, newFilePublisher = NewProjectFilePublisher.platform, participant = probe)

    @Test fun twoRepositoriesAndStandaloneOwnerUseTheSameRootMutex() = runBlocking {
        withTimeout(10_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val active = AtomicInteger(0)
            val probe = Probe().apply {
                onBegin = { guard, _ -> guard.requireRoot(root); assertEquals(0, active.getAndIncrement()) }
                onFinish = { assertEquals(1, active.getAndDecrement()) }
            }
            val first = repository(probe)
            val second = repository(probe)
            val standalone = launch(start = CoroutineStart.UNDISPATCHED) {
                WorkspaceFileMutationLocks.withRoot(root) { guard ->
                    guard.requireRoot(root)
                    assertEquals(0, active.getAndIncrement())
                    entered.complete(Unit)
                    release.await()
                    assertEquals(1, active.getAndDecrement())
                }
            }
            entered.await()
            val a = async { first.writeText("first.txt", "first sentinel") }
            val b = async { second.writeText("second.txt", "second sentinel") }
            release.complete(Unit)
            a.await(); b.await(); standalone.join()
            assertEquals(0, active.get())
            assertEquals(2, probe.sessions.size)
            assertTrue(probe.sessions.all { it.outcomes.size == 1 })
        }
    }

    @Test fun distinctRootDoesNotWaitForAnotherRootsOwner() = runBlocking {
        val other = Files.createTempDirectory("repo-independent-").toFile()
        try {
            WorkspaceFileMutationLocks.withRoot(root) {
                withTimeout(5_000) { FileRepository(other).writeText("independent.txt", "other sentinel") }
                assertEquals("other sentinel", other.resolve("independent.txt").readText())
            }
        } finally { PathSecurity.deleteTreeNoFollow(other) }
    }

    @Test fun staleWrongRootAndReplacedRootGuardsAreRejected(): Unit = runBlocking {
        lateinit var stale: WorkspaceMutationGuard
        val other = Files.createTempDirectory("repo-wrong-root-").toFile()
        try {
            WorkspaceFileMutationLocks.withRoot(root) { guard ->
                stale = guard
                assertThrows(IllegalStateException::class.java) { guard.requireRoot(other) }
                val moved = File(root.parentFile, root.name + "-original")
                Files.move(root.toPath(), moved.toPath())
                try {
                    assertTrue(root.mkdir())
                    assertThrows(IllegalStateException::class.java) { guard.requireRoot(root) }
                } finally { root.delete(); Files.move(moved.toPath(), root.toPath()) }
            }
            assertThrows(IllegalStateException::class.java) { stale.requireRoot(root) }
        } finally { PathSecurity.deleteTreeNoFollow(other) }
    }

    @Test fun oneExactDescriptorIsSharedByAdmissionSessionAndAfterCallback() = runBlocking {
        val probe = Probe()
        var before: FileRepository.Mutation? = null
        var after: FileRepository.Mutation? = null
        FileRepository(root, beforeMutation = { before = it }, onMutation = { after = it },
            newFilePublisher = NewProjectFilePublisher.platform, participant = probe).writeText("file.txt", "content")
        val session = probe.sessions.single()
        assertSame(before, session.mutation)
        assertSame(before, after)
        assertEquals(1, session.outcomes.size)
        assertTrue(session.outcomes.single().localAttempted)
        assertFalse(session.receipts.single().durable)
    }

    @Test fun beginRejectionPrecedesImplicitParentCreationForEveryCreatingPath() = runBlocking {
        val probe = Probe().apply { onBegin = { _, _ -> throw IOException("pending overlap") } }
        val repo = repository(probe)
        root.resolve("source.txt").writeText("source sentinel")
        val operations: List<suspend () -> Unit> = listOf(
            { repo.writeText("text/child.txt", "new") },
            { repo.writeStream("stream/child.bin", byteArrayOf(1).inputStream()) },
            { repo.createDirectory("directory/child") },
            { repo.move("source.txt", "move/child.txt") },
        )
        for (operation in operations) {
            try { operation(); fail("Expected begin refusal") } catch (_: IOException) { }
        }
        assertEquals(listOf("source.txt"), root.list()!!.toList())
        assertEquals("source sentinel", root.resolve("source.txt").readText())
        assertTrue(probe.sessions.isEmpty())
    }

    @Test fun preparationIsCancellableAndFinishRunsOnceWithoutPublication() = runBlocking {
        val preparing = CompletableDeferred<Unit>()
        var prepared: WorkspaceLocalPreparation? = null
        val probe = Probe().apply { onPrepare = { prepared = it; preparing.complete(Unit); awaitCancellation() } }
        val work = launch { repository(probe).writeStream("asset.bin", byteArrayOf(1, 2, 3).inputStream()) }
        preparing.await(); work.cancel(); work.join()
        val session = probe.sessions.single()
        assertEquals(1, session.outcomes.size)
        assertFalse(session.outcomes.single().localAttempted)
        assertTrue(session.outcomes.single().failure is CancellationException)
        val retained = root.listFiles()!!.single()
        assertEquals(prepared!!.sourcePath, retained.name)
        assertEquals(prepared!!.sourceIdentity, ProjectCopyEvidence.identity(retained).key)
        assertArrayEquals(byteArrayOf(1, 2, 3), retained.readBytes())
    }

    @Test fun mandatoryLocalAdmissionFailurePreventsTextAndStreamPublication() = runBlocking {
        for (stream in listOf(false, true)) {
            val probe = Probe().apply { onAttempt = { throw IOException("local attempt force failed") } }
            try {
                if (stream) repository(probe).writeStream("asset.bin", byteArrayOf(1).inputStream())
                else repository(probe).writeText("asset.bin", "new")
                fail("Expected failed intent")
            } catch (_: IOException) { }
            assertFalse(root.resolve("asset.bin").exists())
            assertEquals(1, probe.sessions.single().outcomes.size)
            assertFalse(probe.sessions.single().outcomes.single().localAttempted)
        }
    }

    @Test fun cancellationAfterPublicationKeepsExactReceiptAcrossIoReturnAndSkipsSync() = runBlocking {
        val original = byteArrayOf(4, 5, 6, 7)
        val probe = Probe()
        val job = Job()
        var synchronized = false
        probe.onReceipt = { receipt -> if (receipt.preparation.effect == WorkspaceLocalEffect.WRITE) job.cancel() }
        probe.onSync = { synchronized = true }
        val work = async(job) { repository(probe).writeStream("asset.bin", original.inputStream()) }
        try { work.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        work.join()
        val outcome = probe.sessions.single().outcomes.single()
        assertTrue(outcome.localAttempted)
        assertTrue(outcome.lastLocalReceipt!!.durable)
        assertEquals(original.size.toLong(), outcome.lastLocalReceipt!!.preparation.bytes)
        assertEquals(ProjectCopyEvidence.sha256(original), outcome.lastLocalReceipt!!.preparation.sha256)
        assertArrayEquals(original, root.resolve("asset.bin").readBytes())
        assertFalse(synchronized)
    }

    @Test fun synchronizationUsesTheOriginalCancellableJobOutsidePublicationFinalizer() = runBlocking {
        val syncing = CompletableDeferred<Unit>()
        val probe = Probe().apply {
            onSync = { assertTrue(currentCoroutineContext()[Job]!!.isActive); syncing.complete(Unit); awaitCancellation() }
        }
        val work = launch { repository(probe).writeStream("asset.bin", byteArrayOf(8).inputStream()) }
        syncing.await(); work.cancel(); withTimeout(5_000) { work.join() }
        assertEquals(1, probe.sessions.single().outcomes.size)
        assertTrue(probe.sessions.single().outcomes.single().failure is CancellationException)
        assertArrayEquals(byteArrayOf(8), root.resolve("asset.bin").readBytes())
    }

    @Test fun providerAttemptedDeletePreservesStageWithoutRollbackOrReplayEvenWithNoAfterCallback() = runBlocking {
        root.resolve("original.txt").writeText("original delete sentinel")
        val probe = Probe().apply { onSync = { markExternalEffectAttempted(); throw IOException("provider outcome unknown") } }
        try { repository(probe).delete("original.txt"); fail("Expected uncertain delete") } catch (_: IllegalStateException) { }
        assertFalse(root.resolve("original.txt").exists())
        val retained = root.listFiles()!!.single()
        assertTrue(retained.name.startsWith(".droide-tmp-"))
        assertEquals("original delete sentinel", retained.readText())
        assertEquals(1, probe.sessions.size)
        assertTrue(probe.sessions.single().outcomes.single().externalEffectAttempted)
    }

    @Test fun providerAttemptedMovePreservesDestinationWithoutRollbackOrAutomaticSync() = runBlocking {
        root.resolve("from.txt").writeText("original move sentinel")
        var afterCalls = 0
        val probe = Probe().apply { onSync = { markExternalEffectAttempted(); throw IOException("manifest outcome unknown") } }
        try { repository(probe) { afterCalls++ }.move("from.txt", "to.txt"); fail("Expected uncertain move") }
        catch (_: IllegalStateException) { }
        assertFalse(root.resolve("from.txt").exists())
        assertEquals("original move sentinel", root.resolve("to.txt").readText())
        assertEquals(0, afterCalls)
        assertEquals(1, probe.sessions.single().outcomes.size)
    }

    @Test fun exactNoExternalEffectMoveMayStillRollBackWithoutAutomaticReplay() = runBlocking {
        root.resolve("from.txt").writeText("rollback sentinel")
        val probe = Probe().apply { onSync = { throw IOException("no provider attempt") } }
        try { repository(probe).move("from.txt", "to.txt"); fail("Expected sync failure") } catch (_: IOException) { }
        assertEquals("rollback sentinel", root.resolve("from.txt").readText())
        assertFalse(root.resolve("to.txt").exists())
        assertEquals(1, probe.sessions.size)
        assertFalse(probe.sessions.single().outcomes.single().externalEffectAttempted)
    }

    @Test fun bothMandatoryReceiptObserversRunWhenEitherOneFails() = runBlocking {
        for (failSession in listOf(true, false)) {
            val events = mutableListOf<String>()
            val probe = Probe().apply {
                onAttempt = { events += "session intent" }
                onReceipt = { events += "session receipt"; if (failSession) throw IOException("session receipt failed") }
            }
            val observer = object : StreamPublicationObserver {
                override fun beforePublish(preparation: StreamPublicationPreparation) { events += "media intent" }
                override fun onPublished(receipt: StreamPublicationReceipt) {
                    events += "media receipt"
                    if (!failSession) throw IOException("media receipt failed")
                }
            }
            val name = "asset-$failSession.bin"
            try { repository(probe).writeStream(name, byteArrayOf(9).inputStream(), publicationObserver = observer); fail("Expected uncertainty") }
            catch (_: StreamPublicationUncertainException) { }
            assertEquals(listOf("session intent", "media intent", "session receipt", "media receipt"), events)
            assertArrayEquals(byteArrayOf(9), root.resolve(name).readBytes())
            assertTrue(probe.sessions.single().outcomes.single().lastLocalReceipt!!.durable)
        }
    }

    @Test fun eitherMandatoryAttemptObserverCanForbidPublication() = runBlocking {
        for (failSession in listOf(true, false)) {
            var mediaAttempted = false
            val probe = Probe().apply { onAttempt = { if (failSession) throw IOException("session admission failed") } }
            val observer = object : StreamPublicationObserver {
                override fun beforePublish(preparation: StreamPublicationPreparation) { mediaAttempted = true; throw IOException("media admission failed") }
                override fun onPublished(receipt: StreamPublicationReceipt) { fail("No receipt before publication") }
            }
            try { repository(probe).writeStream("asset.bin", byteArrayOf(1).inputStream(), publicationObserver = observer); fail("Expected refusal") }
            catch (_: IOException) { }
            assertEquals(!failSession, mediaAttempted)
            assertFalse(root.resolve("asset.bin").exists())
            assertFalse(probe.sessions.single().outcomes.single().localAttempted)
        }
    }

    @Test fun afterOnlyEntrypointsAcquireAndAdmitBeforeCallbacksWithoutRewritingBytes() = runBlocking {
        root.resolve("existing.txt").writeText("existing sentinel")
        val identity = ProjectCopyEvidence.identity(root.resolve("existing.txt")).key
        var callbacks = 0
        val probe = Probe()
        val repo = repository(probe) { callbacks++ }
        repo.synchronizeEditorTextIfUnchanged("existing.txt", "existing sentinel", DiskFileVersion.fromText("existing sentinel"))
        repo.syncExisting("existing.txt")
        repo.reconcileExternal("existing.txt")
        assertEquals(3, callbacks)
        assertEquals(3, probe.sessions.size)
        assertTrue(probe.sessions.all { !it.outcomes.single().localAttempted && it.receipts.isEmpty() })
        assertEquals(identity, ProjectCopyEvidence.identity(root.resolve("existing.txt")).key)
        probe.onBegin = { _, _ -> throw IOException("pending overlap") }
        try { repo.syncExisting("existing.txt"); fail("Expected admission refusal") } catch (_: IOException) { }
        assertEquals(3, callbacks)
    }

    @Test fun finishFailureIsSuppressedOnTheOriginalFailureAndNeverRunsTwice() = runBlocking {
        val probe = Probe().apply {
            onSync = { throw IOException("original sync failure") }
            onFinish = { throw IOException("finish persistence failed") }
        }
        try { repository(probe).writeText("file.txt", "saved sentinel"); fail("Expected sync failure") }
        catch (failure: IOException) { assertTrue(failure.suppressed.any { it.message == "finish persistence failed" }) }
        assertEquals(1, probe.sessions.single().outcomes.size)
        assertEquals("saved sentinel", root.resolve("file.txt").readText())
    }

    @Test fun successfulParticipantDeleteWithoutAfterCallbackStillHasAttemptReceiptAndFinish() = runBlocking {
        root.resolve("file.txt").writeText("delete sentinel")
        val probe = Probe()
        assertTrue(repository(probe).delete("file.txt"))
        assertTrue(root.list()!!.isEmpty())
        val session = probe.sessions.single()
        assertEquals(WorkspaceLocalEffect.DELETE_STAGE, session.receipts.single().preparation.effect)
        assertEquals(1, session.outcomes.size)
        assertNull(session.outcomes.single().failure)
    }

    @Test fun implicitParentIntentFailureLeavesAllMissingParentsAbsent() = runBlocking {
        val probe = Probe().apply {
            onAttempt = { preparation ->
                assertEquals(WorkspaceLocalEffect.CREATE_PARENTS, preparation.effect)
                assertFalse(root.resolve("new-parent").exists())
                throw IOException("parent intent failed")
            }
        }
        try { repository(probe).writeText("new-parent/nested/file.txt", "new"); fail("Expected refusal") }
        catch (_: IOException) { }
        assertTrue(root.list()!!.isEmpty())
        assertFalse(probe.sessions.single().outcomes.single().localAttempted)
    }

    @Test fun afterCallbackFailureCannotRollBackAnAlreadyAttemptedProviderMove() = runBlocking {
        root.resolve("from.txt").writeText("preserve move sentinel")
        var callbacks = 0
        val probe = Probe().apply { onSync = { markExternalEffectAttempted() } }
        try {
            repository(probe) { callbacks++; throw IOException("document observer failed") }.move("from.txt", "to.txt")
            fail("Expected observer failure")
        } catch (_: IllegalStateException) { }
        assertEquals(1, callbacks)
        assertFalse(root.resolve("from.txt").exists())
        assertEquals("preserve move sentinel", root.resolve("to.txt").readText())
        assertTrue(probe.sessions.single().outcomes.single().externalEffectAttempted)
    }

    @Test fun replacedTextStageDuringPreparationIsRetainedAndNeverPublishedOrDeleted() = runBlocking {
        var replacement: File? = null
        val retained = root.resolve("retained-stage.txt")
        val probe = Probe().apply {
            onPrepare = { preparation ->
                if (preparation.effect == WorkspaceLocalEffect.WRITE) {
                    val staged = root.resolve(preparation.sourcePath!!)
                    Files.move(staged.toPath(), retained.toPath())
                    staged.writeText("foreign stage sentinel")
                    replacement = staged
                }
            }
        }
        try { repository(probe).writeText("file.txt", "original stage sentinel"); fail("Expected identity refusal") }
        catch (_: IllegalStateException) { }
        assertFalse(root.resolve("file.txt").exists())
        assertEquals("foreign stage sentinel", replacement!!.readText())
        assertEquals("original stage sentinel", retained.readText())
        assertFalse(probe.sessions.single().outcomes.single().localAttempted)
    }

    @Test fun participantConstructorDoesNotCreateAnUnadmittedProjectRoot() {
        val absent = root.resolve("absent-project")
        assertThrows(IllegalStateException::class.java) {
            FileRepository(absent, newFilePublisher = NewProjectFilePublisher.platform, participant = Probe())
        }
        assertFalse(absent.exists())
        FileRepository(absent)
        assertTrue(absent.isDirectory)
    }

    @Test fun exchangedTextDestinationAfterPublisherReturnCannotEarnACommittedReceipt() = runBlocking {
        val retained = root.resolve("retained-original.txt")
        val probe = Probe().apply { onSync = { fail("Unverified publication must not synchronize") } }
        val repo = FileRepository(root, participant = probe, newFilePublisher = NewProjectFilePublisher { stage, target ->
            Files.move(stage.toPath(), target.toPath())
            Files.move(target.toPath(), retained.toPath())
            target.writeText("newer destination sentinel")
        })
        try {
            repo.writeTextIfUnchanged("file.txt", "original published sentinel", DiskFileVersion.fromText(null))
            fail("Expected uncertain publication")
        } catch (_: ProjectFilePublicationUncertainException) { }
        assertEquals("newer destination sentinel", root.resolve("file.txt").readText())
        assertEquals("original published sentinel", retained.readText())
        val receipt = probe.sessions.single().receipts.single()
        assertTrue(receipt.returned)
        assertFalse(receipt.successful)
        assertNull(receipt.destinationIdentity)
        assertNotNull(receipt.failure)
        assertEquals(1, probe.sessions.single().outcomes.size)
    }

    @Test fun missingTextDestinationAfterPublisherReturnCannotEarnACommittedReceipt() = runBlocking {
        val retained = root.resolve("retained-original.txt")
        val probe = Probe().apply { onSync = { fail("Missing publication must not synchronize") } }
        val repo = FileRepository(root, participant = probe, newFilePublisher = NewProjectFilePublisher { stage, target ->
            Files.move(stage.toPath(), target.toPath())
            Files.move(target.toPath(), retained.toPath())
        })
        try {
            repo.writeTextIfUnchanged("file.txt", "original missing sentinel", DiskFileVersion.fromText(null))
            fail("Expected uncertain publication")
        } catch (_: ProjectFilePublicationUncertainException) { }
        assertFalse(root.resolve("file.txt").exists())
        assertEquals("original missing sentinel", retained.readText())
        val outcome = probe.sessions.single().outcomes.single()
        assertTrue(outcome.localAttempted)
        assertFalse(outcome.lastLocalReceipt!!.successful)
        assertNull(outcome.lastLocalReceipt!!.destinationIdentity)
    }

    @Test fun exchangedTextAncestorAfterPublisherReturnPreservesBothSidesAndWithholdsReceipt() = runBlocking {
        val parent = root.resolve("documents").apply { mkdir() }
        parent.resolve("sibling.txt").writeText("original sibling sentinel")
        val retained = root.resolve("retained-documents")
        val probe = Probe().apply { onSync = { fail("Changed ancestor must not synchronize") } }
        val repo = FileRepository(root, participant = probe, newFilePublisher = NewProjectFilePublisher { stage, target ->
            Files.move(stage.toPath(), target.toPath())
            Files.move(parent.toPath(), retained.toPath())
            assertTrue(parent.mkdir())
            parent.resolve("file.txt").writeText("newer ancestor sentinel")
        })
        try {
            repo.writeTextIfUnchanged("documents/file.txt", "original parent sentinel", DiskFileVersion.fromText(null))
            fail("Expected uncertain publication")
        } catch (_: ProjectFilePublicationUncertainException) { }
        assertEquals("newer ancestor sentinel", parent.resolve("file.txt").readText())
        assertEquals("original parent sentinel", retained.resolve("file.txt").readText())
        assertEquals("original sibling sentinel", retained.resolve("sibling.txt").readText())
        assertFalse(probe.sessions.single().receipts.single().successful)
    }

    @Test fun finalStreamAdmissionRunsAfterPreparationAndProgressCanChangeItsState() = runBlocking {
        val events = mutableListOf<String>()
        var admissionValid = true
        var mediaAttempted = false
        val probe = Probe().apply { onPrepare = { events += "prepare"; admissionValid = false } }
        val observer = object : StreamPublicationObserver {
            override fun beforePublish(preparation: StreamPublicationPreparation) { mediaAttempted = true }
            override fun onPublished(receipt: StreamPublicationReceipt) { fail("Rejected admission cannot publish") }
        }
        try {
            repository(probe).writeStream("asset.bin", byteArrayOf(1, 2).inputStream(),
                publicationObserver = observer,
                onProgress = { if (it.phase == StreamWritePhase.COMMITTING) events += "committing progress" },
                beforePublication = {
                    events += "final admission"
                    if (!admissionValid) throw IOException("editor changed during preparation")
                })
            fail("Expected final admission refusal")
        } catch (_: IOException) { }
        assertEquals(listOf("prepare", "committing progress", "final admission"), events)
        assertFalse(mediaAttempted)
        assertFalse(root.resolve("asset.bin").exists())
        assertFalse(probe.sessions.single().outcomes.single().localAttempted)
    }

    @Test fun oneSharedReceiptHookFailureDoesNotBecomeSelfSuppressionError() = runBlocking {
        val shared = IOException("shared mandatory receipt failure")
        var mediaReceipts = 0
        val probe = Probe().apply { onReceipt = { throw shared } }
        val observer = object : StreamPublicationObserver {
            override fun beforePublish(preparation: StreamPublicationPreparation) {}
            override fun onPublished(receipt: StreamPublicationReceipt) { mediaReceipts++; throw shared }
        }
        try {
            repository(probe).writeStream("asset.bin", byteArrayOf(7, 8).inputStream(), publicationObserver = observer)
            fail("Expected receipt persistence failure")
        } catch (failure: StreamPublicationUncertainException) {
            assertSame(shared, failure.cause)
            assertTrue(shared.suppressed.isEmpty())
        }
        assertEquals(1, mediaReceipts)
        assertEquals(1, probe.sessions.single().receipts.size)
        assertArrayEquals(byteArrayOf(7, 8), root.resolve("asset.bin").readBytes())
        assertTrue(probe.sessions.single().outcomes.single().lastLocalReceipt!!.durable)
    }

    @Test fun textTargetMadeReadOnlyDuringPreparationIsNeverOverwritten() = runBlocking {
        val target = root.resolve("file.txt").apply { writeText("read-only original sentinel") }
        val probe = Probe().apply {
            onPrepare = {
                val permissions = Files.getPosixFilePermissions(target.toPath()).toMutableSet()
                permissions.remove(PosixFilePermission.OWNER_WRITE)
                Files.setPosixFilePermissions(target.toPath(), permissions)
            }
        }
        try { repository(probe).writeText("file.txt", "replacement must not publish"); fail("Expected read-only refusal") }
        catch (_: IllegalArgumentException) { }
        assertEquals("read-only original sentinel", target.readText())
        assertFalse(Files.getPosixFilePermissions(target.toPath()).contains(PosixFilePermission.OWNER_WRITE))
        assertTrue(probe.sessions.single().receipts.isEmpty())
        assertFalse(probe.sessions.single().outcomes.single().localAttempted)
    }

    @Test fun textPublicationPreservesExecutablePermissionChangedDuringPreparation() = runBlocking {
        val target = root.resolve("script.sh").apply { writeText("original script sentinel") }
        val probe = Probe().apply {
            onPrepare = {
                val permissions = Files.getPosixFilePermissions(target.toPath()).toMutableSet()
                permissions.add(PosixFilePermission.OWNER_EXECUTE)
                Files.setPosixFilePermissions(target.toPath(), permissions)
            }
        }
        repository(probe).writeText("script.sh", "updated script sentinel")
        assertEquals("updated script sentinel", target.readText())
        assertTrue(Files.getPosixFilePermissions(target.toPath()).contains(PosixFilePermission.OWNER_EXECUTE))
        assertEquals(ProjectCopyEvidence.identity(target).key, probe.sessions.single().receipts.single().destinationIdentity)
    }

    @Test fun inPlaceStreamStageEditDuringPreparationCannotAcquireTheOriginalDigestReceipt() = runBlocking {
        var mediaAttempted = false
        val probe = Probe().apply {
            onPrepare = { preparation ->
                val staged = root.resolve(preparation.sourcePath!!)
                val originalModified = Files.getLastModifiedTime(staged.toPath())
                assertEquals("ORIG", staged.readText())
                staged.writeText("EDIT")
                Files.setLastModifiedTime(staged.toPath(), FileTime.fromMillis(originalModified.toMillis() + 2_000))
            }
        }
        val observer = object : StreamPublicationObserver {
            override fun beforePublish(preparation: StreamPublicationPreparation) { mediaAttempted = true }
            override fun onPublished(receipt: StreamPublicationReceipt) { fail("Edited stage must not publish") }
        }
        try {
            repository(probe).writeStream("asset.bin", "ORIG".byteInputStream(), publicationObserver = observer)
            fail("Expected stage-version refusal")
        } catch (_: IllegalStateException) { }
        assertFalse(root.resolve("asset.bin").exists())
        assertFalse(mediaAttempted)
        assertTrue(probe.sessions.single().receipts.isEmpty())
        assertFalse(probe.sessions.single().outcomes.single().localAttempted)
    }
}
