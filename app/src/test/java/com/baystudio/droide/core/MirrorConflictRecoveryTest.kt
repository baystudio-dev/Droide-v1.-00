package com.baystudio.droide.core

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MirrorConflictRecoveryTest {
    @get:org.junit.Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private class Fixture {
        val root = Files.createTempDirectory("reviewed-conflict-").toFile()
        val source = root.resolve("source").apply { mkdir(); resolve("file").writeText("base") }
        val cache = root.resolve("cache").apply { mkdir() }
        val persistent = root.resolve("persistent").apply { mkdir() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val process = LocalAgentWorkspaceProcessHost.forWorkspace(source, cache, scope, persistent)
        val working = persistent.resolve(hash(source.canonicalPath.toByteArray()))
        val directory = persistent.resolve(".${working.name}-sync-authority")
        val authority get() = MirrorMetadataAuthority(directory, source, working)
        var dirty = false
        var beforeSnapshots: suspend () -> Unit = {}
        val documents = object : WorkspaceDocumentAuthority {
            override suspend fun snapshot(path: String) = snapshots().firstOrNull { it.path == path }
            override suspend fun observeRecovery(path: String): WorkspaceRecoveryEditorObservation {
                val captured = snapshots()
                suspend fun validate() { check(captured == snapshots()) { "Editor changed during retention review; inspect again" } }
                return WorkspaceRecoveryEditorObservation(captured, emptySet(), ::validate)
            }
            override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> {
                beforeSnapshots()
                return if (!dirty) emptyList() else listOf(WorkspaceDocumentSnapshot(
                    "file", "unsaved", true, WorkspaceDocumentKind.TEXT, true, true, 1, 1, 0, 0, 4))
            }
        }
        val bridge = WorkspaceDocumentAuthorityBridge()
        val binding = bridge.bind(documents)
        val host by lazy { WorkspaceAgentPluginHost(source, cache, FileRepository(source, newFilePublisher = NewProjectFilePublisher.native()),
            process, ApprovalManager(), PermissionEngine(), scope, process.workspaceIo).also { it.bindRecoveryDocuments(bridge) } }
        suspend fun prepare() {
            process.workspaceIo.refresh()
            RandomAccessFile(working.resolve("file"), "rw").use { writer ->
                source.resolve("file").writeText("published"); process.workspaceIo.refresh()
                writer.setLength(0); writer.write("late CLI bytes".toByteArray()); writer.fd.sync()
            }
            process.workspaceIo.refresh()
        }
        suspend fun item() = host.retainedWorkspaceVersions().items.single { it.role == MirrorRetainedRecovery.Role.ORIGINAL && it.operation.path == "file" }
        suspend fun original() = directory.resolve("retained/${item().operation.retainedName}")
        suspend fun acknowledge() = host.acknowledgeRetainedVersion(host.inspectRetainedAcknowledgement(item()))
        suspend fun resolve() = host.resolveWorkspaceConflict(host.inspectWorkspaceConflict("file"))
        fun metadata() = hash(directory.resolve("state.sqlite").readBytes())
    }
    private fun fixture(action: suspend Fixture.() -> Unit) = runBlocking {
        val f = Fixture()
        try { withTimeout(60_000) { f.prepare(); f.action() } }
        finally {
            withContext(NonCancellable) { f.host.close(); f.scope.coroutineContext.job.cancelAndJoin() }
            assertTrue(PathSecurity.deleteTreeNoFollow(f.root))
        }
    }

    @Test fun retentionReviewReportsDirtyConflictAndUnprovenReleaseWithoutMutation() = fixture {
        dirty = true
        val selected = item()
        val database = metadata()
        val original = original().readBytes()
        val review = host.reviewRetainedVersionRetention(selected)
        assertEquals(selected, review.item)
        assertEquals(authority.load().revision, review.revision)
        assertEquals(listOf("file"), review.dirtyPaths)
        assertEquals(WorkspaceDocumentVersion(1, 1), review.documentVersions["file"])
        assertEquals(1, review.conflicts)
        assertEquals(0, review.recordedOwners)
        assertTrue(review.blockers.any { it.contains("Pending editor writes") })
        assertTrue(review.blockers.any { it.contains("inherited descriptors") })
        assertEquals(database, metadata())
        assertArrayEquals(original, original().readBytes())
    }

    @Test fun retentionReviewRejectsStaleOperationAndChangedEditor() = fixture {
        val old = item()
        acknowledge()
        assertNotNull(runCatching { host.reviewRetainedVersionRetention(old) }.exceptionOrNull())
        val selected = item()
        var reads = 0
        beforeSnapshots = { if (++reads == 2) dirty = true }
        assertNotNull(runCatching { host.reviewRetainedVersionRetention(selected) }.exceptionOrNull())
    }

    @Test fun retentionReviewRejectsSameContentReplacementAndCancellation() = fixture {
        val selected = item()
        val original = original()
        var reads = 0
        beforeSnapshots = {
            if (++reads == 2) {
                val temporary = File(original.parentFile, "replacement")
                temporary.writeBytes(original.readBytes())
                Files.move(temporary.toPath(), original.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        assertNotNull(runCatching { host.reviewRetainedVersionRetention(selected) }.exceptionOrNull())
        beforeSnapshots = { throw CancellationException("cancelled review") }
        assertTrue(runCatching { host.reviewRetainedVersionRetention(selected) }.exceptionOrNull() is CancellationException)
    }

    @Test fun retentionReviewReportsPendingAndOwnersWithoutRetiringThem() = fixture {
        val selected = item()
        val ticket = authority.processCustody().acquire(working)
        try {
            val pending = authority.prepare("file", "replace", selected.operation.before, selected.operation.proposed)
            val owners = authority.pendingProcessOwners()
            val database = metadata()
            val review = host.reviewRetainedVersionRetention(selected)
            assertEquals(1, review.pendingOperations)
            assertEquals(owners.size, review.recordedOwners)
            assertTrue(review.recordedOwners > 0)
            assertEquals(owners, authority.pendingProcessOwners())
            assertEquals(pending, authority.operation(pending.id))
            assertEquals(database, metadata())
        } finally { ticket.close() }
    }

    @Test fun retentionReviewRejectsPendingMembershipChangeWithoutRevisionChange() = fixture {
        val selected = item()
        val revision = authority.load().revision
        var reads = 0
        beforeSnapshots = { if (++reads == 2) authority.prepare("file", "replace", selected.operation.before, selected.operation.proposed) }
        val error = runCatching { host.reviewRetainedVersionRetention(selected) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("Pending synchronization changed"))
        assertEquals(revision, authority.load().revision)
    }

    @Test fun savedResolutionSurvivesEditorRebindWithoutClaimingEditorCleanliness() = fixture {
        acknowledge()
        val review = host.inspectWorkspaceConflict("file")
        val original = original(); val retained = original.readBytes()
        binding.close()
        assertTrue(host.inspectWorkspaceConflict("file").canResolve)
        dirty = true
        bridge.bind(documents)
        host.resolveWorkspaceConflict(review)
        assertTrue(authority.load().conflicts.isEmpty())
        assertTrue(documents.snapshot("file")!!.dirty)
        assertEquals("unsaved", documents.snapshot("file")!!.content)
        assertArrayEquals(retained, original.readBytes())
    }

    @Test fun savedResolutionDoesNotNeedOrInvokeAnEditorMutationCapability() = fixture {
        acknowledge()
        beforeSnapshots = { error("Saved-file resolution must not mutate or infer editor cleanliness") }
        val review = host.inspectWorkspaceConflict("file")
        assertTrue(review.canResolve)
        host.resolveWorkspaceConflict(review)
        assertEquals(MirrorConflictRecovery.ResolutionAttempt.Outcome.CONFIRMED, review.lastResolutionAttempt!!.outcome)
        assertEquals(authority.load().revision, review.lastResolutionAttempt!!.committedRevision)
        assertTrue(authority.load().conflicts.isEmpty())
    }

    @Test fun detachedAndNeverBoundEditorsAreUnavailableInRetentionDiagnostics() = fixture {
        val selected = item()
        val bridge = WorkspaceDocumentAuthorityBridge()
        val never = process.retainedRecovery.reviewRetention(selected, bridge) {}
        assertFalse(never.editorAvailable)
        assertTrue(never.editorUnavailableReason.orEmpty().contains("not attached"))
        bridge.bind(documents).close()
        val detached = process.retainedRecovery.reviewRetention(selected, bridge) {}
        assertFalse(detached.editorAvailable)
        assertTrue(detached.editorUnavailableReason.orEmpty().contains("detached"))
    }

    @Test fun retentionReviewCancelsInsideHistoryScanWithNoPendingMatches() = fixture {
        val selected = item()
        val database = metadata()
        val original = original().readBytes()
        var admissions = 0
        val failure = runCatching {
            process.retainedRecovery.reviewRetention(selected, documents) {
                // First admission is before inspection; second is inside the first
                // history page, which contains only completed/non-pending records.
                if (++admissions == 2) throw CancellationException("cancelled history scan")
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("cancelled history scan", failure?.message)
        assertEquals(2, admissions)
        assertEquals(database, metadata())
        assertArrayEquals(original, original().readBytes())
    }

    @Test fun retentionReviewHandlesStableVersionAndMissingEditorConservatively() = fixture {
        val selected = host.retainedWorkspaceVersions().items.single { it.role == MirrorRetainedRecovery.Role.STABLE && it.operation.path == "file" }
        val review = process.retainedRecovery.reviewRetention(selected, null) {}
        assertFalse(review.editorAvailable)
        assertTrue(review.blockers.any { it.contains("Editor ownership") })
        assertEquals(hash("base".toByteArray()), review.sha256)
        assertTrue(review.blockers.any { it.contains("No durable release receipt") })
    }

    @Test fun resolvingRequiresExplicitAcknowledgementAndPreservesAllVersions() = fixture {
        val review = host.inspectWorkspaceConflict("file")
        assertTrue(review.matchingFiles); assertEquals(1, review.unreviewedVersions); assertFalse(review.canResolve)
        assertNotNull(runCatching { host.resolveWorkspaceConflict(review) }.exceptionOrNull())
        val original = original(); val retained = original.readBytes()
        acknowledge()
        assertEquals(setOf("file"), authority.load().conflicts)
        assertTrue(host.inspectWorkspaceConflict("file").canResolve)
        resolve(); process.workspaceIo.refresh()
        assertTrue(authority.load().conflicts.isEmpty())
        assertArrayEquals(retained, original.readBytes())
        assertEquals("published", source.resolve("file").readText()); assertEquals("published", working.resolve("file").readText())
        assertEquals("base", File(original.path + ".stable").readText())
    }

    @Test fun laterRetainedWriteCreatesANewConflictAfterResolution() = fixture {
        val original = original()
        acknowledge(); resolve(); process.workspaceIo.refresh()
        original.writeText("even later CLI bytes")
        process.workspaceIo.refresh()
        assertEquals(setOf("file"), authority.load().conflicts)
        assertEquals(1, host.inspectWorkspaceConflict("file").unreviewedVersions)
        assertEquals("even later CLI bytes", original.readText())
    }

    @Test fun revertedRetainedBytesStillRequireNewReviewAfterAcknowledgement() = fixture {
        val original = original()
        acknowledge(); resolve()
        original.writeText("base")
        process.workspaceIo.refresh()
        assertEquals(setOf("file"), authority.load().conflicts)
    }

    @Test fun changedOrReplacedVersionCannotUseOldAcknowledgementReview() = fixture {
        val review = host.inspectRetainedAcknowledgement(item()); val original = original()
        val metadata = metadata()
        val saved = File(original.path + ".saved"); Files.move(original.toPath(), saved.toPath()); original.writeBytes(saved.readBytes())
        assertNotNull(runCatching { host.acknowledgeRetainedVersion(review) }.exceptionOrNull())
        assertEquals(metadata, metadata()); assertEquals("late CLI bytes", saved.readText())
    }

    @Test fun dirtyEditorDoesNotBlockSavedResolutionOrLoseItsBuffer() = fixture {
        acknowledge(); dirty = true
        val review = host.inspectWorkspaceConflict("file")
        val before = documents.snapshot("file")
        host.resolveWorkspaceConflict(review)
        assertEquals(before, documents.snapshot("file"))
        assertTrue(authority.load().conflicts.isEmpty())
        assertEquals("published", source.resolve("file").readText())
    }

    @Test fun acknowledgementWaitsForTheCaptureCleanupReceiptWithoutChangingMetadata() = fixture {
        val review = host.inspectRetainedAcknowledgement(item())
        val original = original(); val retained = original.readBytes(); val metadata = metadata()
        coroutineScope {
            val cleaning = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val capture = async {
                LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "exit 0"), working, cleanup = {
                    cleaning.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                })
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { cleaning.await(5, java.util.concurrent.TimeUnit.SECONDS) })
                val failure = runCatching { host.acknowledgeRetainedVersion(review) }.exceptionOrNull()
                assertTrue(failure.toString(), failure?.message.orEmpty().contains("unconfirmed workspace process"))
                assertEquals(metadata, metadata()); assertArrayEquals(retained, original.readBytes())
            } finally { release.countDown() }
            assertEquals(0, capture.await().exitCode)
        }
        host.acknowledgeRetainedVersion(review)
        assertNotNull(authority.operation(review.item.operation.id).acknowledgedOriginal)
        assertArrayEquals(retained, original.readBytes())
    }

    @Test fun sourceWriterPreventsResolutionUntilItsOwnershipIsReleased() = fixture {
        acknowledge(); val review = host.inspectWorkspaceConflict("file"); val metadata = metadata()
        ExportWriterAdmission.beginWriter(source, "pending source writer").use {
            assertNotNull(runCatching { host.resolveWorkspaceConflict(review) }.exceptionOrNull())
            assertEquals(metadata, metadata()); assertEquals(setOf("file"), authority.load().conflicts)
        }
        host.resolveWorkspaceConflict(review)
        assertTrue(authority.load().conflicts.isEmpty())
    }

    @Test fun resolutionRejectsALateProcessBeforeItCanBeCreated() = fixture {
        acknowledge()
        var inspected = false; var created = false
        val recovery = MirrorConflictRecovery(source, working) { checkpoint ->
            if (checkpoint == "saved-resolution-before-commit") {
                inspected = true
                val error = runCatching { WorkspaceProcessWriteAdmission.create<HostedStdioProcess>(working) {
                    created = true; error("Process must not start during recovery")
                } }.exceptionOrNull()
                assertTrue(error.toString(), error is ExportCaptureBusyException)
            }
        }
        val review = recovery.inspect("file")
        recovery.resolve(review) {}
        assertTrue(inspected); assertFalse(created); assertTrue(authority.load().conflicts.isEmpty())
    }

    @Test fun persistedOwnerFromAnEarlierRuntimeBlocksAcknowledgementWithoutAMemoryTicket() = fixture {
        val review = host.inspectRetainedAcknowledgement(item()); val original = original(); val retained = original.readBytes()
        authority.processCustody(java.util.UUID.randomUUID().toString()).acquire(working).also { it.withFactory({}, {}) }.use {
            // A recreated authority has only the durable row, with no process handle in
            // this runtime. Export's in-memory registry alone would allow this mutation.
            ExportWriterAdmission.beginCapture(working).close()
            val metadata = metadata()
            val error = runCatching { host.acknowledgeRetainedVersion(review) }.exceptionOrNull()
            assertTrue(error.toString(), error?.message.orEmpty().contains("cleanup receipt"))
            assertEquals(metadata, metadata()); assertArrayEquals(retained, original.readBytes())
        }
        host.acknowledgeRetainedVersion(review)
        assertArrayEquals(retained, original.readBytes())
    }

    @Test fun persistedOwnerRefusesConflictResolutionAndReadAccessRemainsAvailable() = fixture {
        acknowledge(); val review = host.inspectWorkspaceConflict("file")
        authority.processCustody(java.util.UUID.randomUUID().toString()).acquire(working).also { it.withFactory({}, {}) }.use {
            val metadata = metadata()
            assertNotNull(runCatching { host.resolveWorkspaceConflict(review) }.exceptionOrNull())
            assertTrue(host.inspectWorkspaceConflict("file").canResolve)
            assertTrue(host.retainedWorkspaceVersions().items.isNotEmpty())
            assertEquals(metadata, metadata()); assertEquals(setOf("file"), authority.load().conflicts)
        }
        host.resolveWorkspaceConflict(review)
        assertTrue(authority.load().conflicts.isEmpty())
    }

    @Test fun differentSavedFilesCannotBeForcedThroughResolution() = fixture {
        acknowledge(); working.resolve("file").writeText("CLI preferred")
        val review = host.inspectWorkspaceConflict("file")
        assertFalse(review.matchingFiles); assertFalse(review.canResolve)
        val metadata = metadata()
        assertNotNull(runCatching { host.resolveWorkspaceConflict(review) }.exceptionOrNull())
        assertEquals(metadata, metadata()); assertEquals("CLI preferred", working.resolve("file").readText())
    }

    @Test fun resolvedReviewCannotBeReplayedOrTransferredToANewOwner() = fixture {
        acknowledge(); val review = host.inspectWorkspaceConflict("file")
        assertNotNull(runCatching { MirrorConflictRecovery(source, working).resolve(review, {}) }.exceptionOrNull())
        host.resolveWorkspaceConflict(review); val metadata = metadata()
        assertNotNull(runCatching { host.resolveWorkspaceConflict(review) }.exceptionOrNull())
        assertEquals(metadata, metadata())
    }

    @Test fun newerNativeInputDuringResolutionSurvivesRefreshAndLaterSave() = fixture {
        acknowledge()
        val files = FileRepository(source, newFilePublisher = NewProjectFilePublisher.native())
        val editor = com.baystudio.droide.ui.EditorWorkspaceState()
        val document = editor.document("file").also { it.ensureLoaded(files) }
        val original = original(); val retained = original.readBytes()
        val editorThread = Thread.currentThread()
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val recovery = MirrorConflictRecovery(source, working) { point ->
            if (point == "saved-resolution-before-commit") {
                assertNotSame(editorThread, Thread.currentThread())
                entered.complete(Unit)
                check(release.await(20, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        val review = recovery.inspect("file")
        coroutineScope {
            val resolving = async { recovery.resolve(review) {} }
            try { entered.await(); document.captureNativeText("new native input") }
            finally { release.countDown() }
            resolving.await()
        }
        assertTrue(document.dirty); assertEquals("new native input", document.content)
        assertEquals("published", source.resolve("file").readText())
        process.workspaceIo.refresh()
        assertTrue(document.dirty); assertEquals("new native input", document.content)
        assertTrue(document.save(files).isSuccess)
        assertEquals("new native input", source.resolve("file").readText())
        process.workspaceIo.refresh()
        assertEquals("new native input", working.resolve("file").readText())
        assertArrayEquals(retained, original.readBytes())
    }

    @Test fun saveStartingDuringResolutionIsSerializedOrCanRetryWithoutDataLoss() = fixture {
        acknowledge()
        val files = FileRepository(source, newFilePublisher = NewProjectFilePublisher.native())
        val document = com.baystudio.droide.ui.EditorWorkspaceState().document("file").also { it.ensureLoaded(files) }
        document.capture("save after resolution")
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val recovery = MirrorConflictRecovery(source, working) { point ->
            if (point == "saved-resolution-before-commit") {
                entered.complete(Unit); check(release.await(20, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        val review = recovery.inspect("file")
        coroutineScope {
            val resolving = async { recovery.resolve(review) {} }
            entered.await()
            val saveStarted = CompletableDeferred<Unit>()
            val saving = async { saveStarted.complete(Unit); document.save(files) }
            try {
                saveStarted.await(); yield()
                assertEquals("published", source.resolve("file").readText())
            } finally { release.countDown() }
            resolving.await()
            val first = saving.await()
            if (first.isFailure) {
                assertTrue(document.dirty)
                assertEquals("save after resolution", document.content)
                assertTrue(document.save(files).isSuccess)
            }
        }
        assertEquals("save after resolution", source.resolve("file").readText())
        assertEquals("save after resolution", document.content)
    }

    @Test fun savedResolutionNeverMakesAStaleEditorSaveSafe() = fixture {
        acknowledge()
        val files = FileRepository(source, newFilePublisher = NewProjectFilePublisher.native())
        source.resolve("file").writeText("older editor baseline")
        val editor = com.baystudio.droide.ui.EditorWorkspaceState()
        val document = editor.document("file").also { it.ensureLoaded(files) }
        source.resolve("file").writeText("published")
        document.capture("unsaved newer buffer")
        resolve()
        assertTrue(document.save(files).isFailure)
        assertTrue(document.dirty); assertEquals("unsaved newer buffer", document.content)
        assertEquals("published", source.resolve("file").readText())
    }

    @Test fun changedSavedFileRejectsStaleReviewBeforeAttempt() = fixture {
        acknowledge(); val review = host.inspectWorkspaceConflict("file")
        source.resolve("file").writeText("new saved source")
        val database = metadata()
        assertTrue(runCatching { host.resolveWorkspaceConflict(review) }.isFailure)
        assertEquals(MirrorConflictRecovery.ResolutionAttempt.Outcome.NOT_ATTEMPTED, review.lastResolutionAttempt!!.outcome)
        assertEquals(database, metadata())
        assertEquals("new saved source", source.resolve("file").readText())
    }

    @Test fun cancellationBeforeCommitLeavesConflictAndPayloadsUnchanged() = fixture {
        acknowledge(); val database = metadata()
        val original = original(); val retained = original.readBytes()
        val recovery = MirrorConflictRecovery(source, working) { point ->
            if (point == "saved-resolution-before-commit") throw CancellationException("before commit")
        }
        val review = recovery.inspect("file")
        assertTrue(runCatching { recovery.resolve(review) {} }.exceptionOrNull() is CancellationException)
        assertEquals(MirrorConflictRecovery.ResolutionAttempt.Outcome.NOT_ATTEMPTED, review.lastResolutionAttempt!!.outcome)
        assertEquals(database, metadata()); assertArrayEquals(retained, original.readBytes())
    }

    @Test fun commitEntryFailureIsUnknownAndDoesNotEraseNewRollbackEvidence() = fixture {
        acknowledge()
        val database = metadata()
        val sidecar = directory.resolve("state.sqlite-journal")
        val recovery = MirrorConflictRecovery(source, working) { point ->
            if (point == "saved-resolution-before-commit") sidecar.writeText("foreign rollback evidence")
        }
        val review = recovery.inspect("file")
        assertTrue(runCatching { recovery.resolve(review) {} }.isFailure)
        assertEquals(MirrorConflictRecovery.ResolutionAttempt.Outcome.UNKNOWN, review.lastResolutionAttempt!!.outcome)
        assertNull(review.lastResolutionAttempt!!.committedRevision)
        assertEquals(database, metadata())
        assertEquals("foreign rollback evidence", sidecar.readText())
    }

    @Test fun cancellationOnIoReturnKeepsConfirmedOutcomeForRefresh() = fixture {
        acknowledge()
        val original = original(); val retained = original.readBytes()
        coroutineScope {
            lateinit var resolving: Deferred<Unit>
            val recovery = MirrorConflictRecovery(source, working) { point ->
                if (point == "saved-resolution-after-commit") resolving.cancel(CancellationException("after commit"))
            }
            val review = recovery.inspect("file")
            resolving = async(start = CoroutineStart.LAZY) { recovery.resolve(review) {} }
            resolving.start()
            assertTrue(runCatching { resolving.await() }.exceptionOrNull() is CancellationException)
            val attempt = review.lastResolutionAttempt!!
            assertEquals(MirrorConflictRecovery.ResolutionAttempt.Outcome.CONFIRMED, attempt.outcome)
            assertEquals(authority.load().revision, attempt.committedRevision)
            assertTrue(authority.load().conflicts.isEmpty())
            assertArrayEquals(retained, original.readBytes())
            assertTrue(runCatching { recovery.inspect("file") }.isFailure)
        }
    }

    @Test fun schemaUpgradeReopensAndAllowsNewOperationsWithoutRewritingOldPayloads() = fixture {
        val original = original(); val retained = original.readBytes()
        acknowledge(); resolve()
        source.resolve("file").writeText("next publication"); process.workspaceIo.refresh()
        assertEquals("next publication", working.resolve("file").readText())
        assertTrue(authority.loadExecutionReadyState().conflicts.isEmpty())
        assertArrayEquals(retained, original.readBytes())
        assertTrue(authority.operations().any { it.acknowledgedOriginal != null })
        assertTrue(authority.operations().any { it.acknowledgedOriginal == null && it.before.type == MirrorMetadataAuthority.NodeType.FILE })
    }

    @Test fun schemaDowngradeCannotHideAcknowledgedVersions() = fixture {
        acknowledge()
        val database = directory.resolve("state.sqlite")
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("PRAGMA user_version=2") }
        val broken = metadata()
        assertNotNull(runCatching { authority.load() }.exceptionOrNull())
        assertEquals(broken, metadata())
    }

    @Test fun acknowledgementChecksumRejectsAChangedReceipt() = fixture {
        acknowledge()
        val database = directory.resolve("state.sqlite")
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE operations SET ack_original=? WHERE ack_original IS NOT NULL", arrayOf("00".repeat(32)))
        }
        val broken = metadata()
        assertNotNull(runCatching { authority.loadExecutionReadyState() }.exceptionOrNull())
        assertEquals(broken, metadata())
    }

    @Test fun missingStableHistoryRemainsAnErrorAfterAcknowledgement() = fixture {
        val original = original(); acknowledge()
        assertTrue(File(original.path + ".stable").delete())
        assertNotNull(runCatching { host.inspectWorkspaceConflict("file") }.exceptionOrNull())
        assertEquals(setOf("file"), authority.load().conflicts)
        assertEquals("late CLI bytes", original.readText())
    }

    companion object {
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
