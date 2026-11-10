package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DirectoryRecoveryIntegrationTest {
    private val home = ".droide/local-delete-custody"
    private fun fixture(label: String): File = Files.createTempDirectory("f151-route-$label-").toFile()
    private fun source(root: File, path: String, text: String = "retained sentinel"): File =
        root.resolve("$path/deep/child.txt").apply { parentFile!!.mkdirs(); writeText(text) }
    private fun snapshot(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256")
            .digest(it.readBytes()).joinToString("") { b -> "%02x".format(b.toInt() and 255) }
    }
    private suspend fun inspect(controller: RetainedWorkController, directory: String): RetainedWorkInventory {
        controller.directory(directory)
        controller.inspect()
        withTimeout(10_000) { while (controller.state.busy) delay(1) }
        assertNull(controller.state.error)
        return requireNotNull(controller.state.inventory)
    }

    @Test fun normalDocumentAuthorityRouteAndFreshControllerDiscoverDurableCustodyWithoutMemoryNotices() = runBlocking {
        val root = fixture("controller")
        source(root, "src")
        val calls = mutableListOf<String>()
        val authority = WorkspaceDocumentAuthorityBridge { calls += "revision" }
        val binding = authority.bind(object : WorkspaceDocumentAuthority {
            override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
            override suspend fun snapshots() = emptyList<WorkspaceDocumentSnapshot>()
            override suspend fun validatePersistedMutation(mutation: FileRepository.Mutation) {
                assertEquals(FileRepository.Mutation.Delete("src"), mutation); calls += "admission"
            }
            override suspend fun persistedMutation(mutation: FileRepository.Mutation): WorkspacePersistedReconciliation {
                assertEquals(FileRepository.Mutation.Delete("src"), mutation)
                assertFalse(root.resolve("src").exists()); calls += "observer"
                return WorkspacePersistedReconciliation(removed = listOf("src/deep/child.txt"))
            }
        })
        try {
            val previous = FileRepository(root, beforeMutation = authority::validatePersistedMutation,
                onMutation = authority::persistedMutation)
            assertTrue(previous.delete("src"))
            assertEquals(listOf("admission", "admission", "observer", "revision"), calls)
            assertEquals(1L, authority.persistedRevision.value)
            val notice = previous.retainedWork.value.notices.single()
            assertEquals(RetainedWorkPurpose.DIRECTORY_DELETE, notice.purpose)
            assertFalse(notice.historicalWriteReturned)
            assertEquals(RetainedWorkPurpose.DIRECTORY_DELETE, notice.candidate.purpose)
            assertNull(notice.candidate.bytes)
            val before = snapshot(root)
            val reopened = FileRepository(root)
            assertTrue(reopened.retainedWork.value.notices.isEmpty())
            assertEquals("/retained-work", CommandManager(root).list().single { it.name == "retained-work" }.template)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val controller = RetainedWorkController(scope, { true }, { reopened.retainedWork.value },
                reopened::acknowledgeRetainedWork, reopened::inspectRetainedWork)
            try {
                controller.open()
                assertTrue(controller.state.open)
                assertTrue(controller.state.notices.isEmpty())
                assertNull(controller.state.inventory)
                val top = inspect(controller, "")
                assertTrue(top.complete)
                assertTrue(top.candidates.isEmpty())
                assertTrue(top.problem.orEmpty().contains(home))
                val catalog = inspect(controller, home)
                assertTrue(catalog.complete)
                val operation = catalog.candidates.single()
                assertEquals(home, operation.path.substringBeforeLast('/'))
                assertEquals(RetainedWorkPurpose.DIRECTORY_DELETE, operation.purpose)
                val leaf = inspect(controller, operation.path).candidates.single()
                assertEquals(notice.candidate.path, leaf.path)
                assertEquals(operation.path, leaf.path.substringBeforeLast('/'))
                assertEquals(LocalDirectoryDeleteCustody.State.RETAINED, leaf.directoryDeletion!!.state)
                assertEquals(LocalDirectoryDeleteCustody.SourceState.ABSENT, leaf.directoryDeletion!!.sourceState)
                assertEquals(LocalDirectoryDeleteCustody.ObserverState.COMPLETED, leaf.directoryDeletion!!.observer)
                assertTrue(leaf.detail.orEmpty().contains("Descendants may still change"))
                assertEquals("retained sentinel", root.resolve("${leaf.path}/deep/child.txt").readText())
                assertEquals(before, snapshot(root))
                assertTrue(reopened.retainedWork.value.notices.isEmpty())
            } finally { controller.dispose(); scope.cancel() }
        } finally { binding.close() }
    }

    private class NullStageParticipant(private val failSync: Throwable? = null) : WorkspaceMutationParticipant {
        val calls = mutableListOf<String>()
        var preparation: WorkspaceLocalPreparation? = null
        var receipt: WorkspaceLocalReceipt? = null
        var outcome: WorkspaceMutationOutcome? = null
        override suspend fun begin(guard: WorkspaceMutationGuard, mutation: FileRepository.Mutation): WorkspaceMutationSession {
            calls += "begin"
            return object : WorkspaceMutationSession(guard, mutation) {
                override suspend fun prepareDeleteStage(): String? { calls += "select"; return null }
                override suspend fun prepareLocal(preparation: WorkspaceLocalPreparation) {
                    calls += "prepare"; this@NullStageParticipant.preparation = preparation
                }
                override fun beforeLocalAttempt(preparation: WorkspaceLocalPreparation) {
                    assertSame(this@NullStageParticipant.preparation, preparation); calls += "before"
                }
                override fun localReturned(receipt: WorkspaceLocalReceipt) { calls += "receipt"; this@NullStageParticipant.receipt = receipt }
                override suspend fun synchronize() { calls += "sync"; failSync?.let { throw it } }
                override fun retainsDeleteStage(receipt: WorkspaceLocalReceipt): Boolean {
                    assertSame(this@NullStageParticipant.receipt, receipt); calls += "retain"; return false
                }
                override fun finish(outcome: WorkspaceMutationOutcome) { calls += "finish"; this@NullStageParticipant.outcome = outcome }
            }
        }
    }

    @Test fun nullStageParticipantRetainsCompleteExistingLifecycleWithoutOwningOrReleasingLocalCustody() = runBlocking {
        val root = fixture("participant")
        source(root, "src")
        val participant = NullStageParticipant()
        val repository = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, participant = participant, onMutation = { participant.calls += "observer" })
        assertTrue(repository.delete("src"))
        assertEquals(listOf("begin", "select", "prepare", "before", "receipt", "sync", "observer", "retain", "finish"), participant.calls)
        val receipt = requireNotNull(participant.receipt)
        assertEquals(WorkspaceLocalEffect.DELETE_STAGE, receipt.preparation.effect)
        assertEquals("src", receipt.preparation.sourcePath)
        assertTrue(receipt.preparation.relativePath.startsWith("$home/"))
        assertTrue(receipt.returned && receipt.successful)
        assertEquals(receipt.preparation.sourceIdentity, receipt.destinationIdentity)
        assertSame(receipt, participant.outcome!!.lastLocalReceipt)
        assertTrue(participant.outcome!!.localAttempted)
        assertNull(participant.outcome!!.failure)
        assertFalse(root.resolve("src").exists())
        assertEquals("retained sentinel", root.resolve("${receipt.preparation.relativePath}/deep/child.txt").readText())
    }

    @Test fun nullStageParticipantFailureIsNotSwallowedAsAnOrdinaryObserverFailure() = runBlocking {
        val root = fixture("participant-failure")
        source(root, "src")
        val failure = IOException("participant synchronization failed")
        val participant = NullStageParticipant(failure)
        val repository = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, participant = participant, onMutation = { error("Observer must not run") })
        assertSame(failure, runCatching { repository.delete("src") }.exceptionOrNull())
        assertEquals(listOf("begin", "select", "prepare", "before", "receipt", "sync", "finish"), participant.calls)
        assertSame(failure, participant.outcome!!.failure)
        assertSame(participant.receipt, participant.outcome!!.lastLocalReceipt)
        assertFalse(root.resolve("src").exists())
        val notice = repository.retainedWork.value.notices.single()
        assertEquals(LocalDirectoryDeleteCustody.State.RETAINED, notice.candidate.directoryDeletion!!.state)
        assertEquals(LocalDirectoryDeleteCustody.ObserverState.FAILED, notice.candidate.directoryDeletion!!.observer)
        assertEquals("retained sentinel", root.resolve("${notice.candidate.path}/deep/child.txt").readText())
    }

    @Test fun observerOnlyFailureKeepsLogicalSuccessAndDurableFailedSynchronizationInspection() = runBlocking {
        val root = fixture("observer-failure")
        source(root, "src")
        var calls = 0
        val repository = FileRepository(root, onMutation = { calls++; throw IOException("editor or LSP observer failed") })
        assertTrue(repository.delete("src"))
        assertEquals(1, calls)
        val notice = repository.retainedWork.value.notices.single()
        assertFalse(notice.historicalWriteReturned)
        val reopened = FileRepository(root)
        val token = requireNotNull(notice.candidate.directoryDeletion!!.token)
        val inspection = reopened.inspectDirectoryDeletion(token)
        assertEquals(LocalDirectoryDeleteCustody.State.RETAINED, inspection.state)
        assertEquals(LocalDirectoryDeleteCustody.ObserverState.FAILED, inspection.observer)
        assertEquals(inspection, reopened.reconcileDirectoryDeletion(token))
        assertEquals(1, calls)
        assertFalse(root.resolve("src").exists())
    }

    @Test fun oneHundredThirtyIndependentDeletesStayAvailableAndPartialCatalogKeepsOldOperationInspectable() = runBlocking {
        val root = fixture("capacity")
        val repository = FileRepository(root)
        var first: LocalDirectoryDeleteCustody.Inspection? = null
        repeat(130) { index ->
            source(root, "src-$index", "payload-$index")
            assertTrue("Independent delete $index must remain available", repository.delete("src-$index"))
            if (index == 0) first = repository.retainedWork.value.notices.single().candidate.directoryDeletion
        }
        assertEquals(130, root.resolve(home).listFiles()!!.size)
        val notifications = repository.retainedWork.value
        assertEquals(8, notifications.notices.size)
        assertEquals(122L, notifications.omitted)
        val reopened = FileRepository(root)
        val before = snapshot(root)
        val partial = reopened.inspectRetainedWork(home)
        assertFalse(partial.complete)
        assertTrue(partial.candidates.isNotEmpty())
        assertTrue(partial.candidates.size <= 64)
        assertTrue(partial.problem.orEmpty().contains("partial", ignoreCase = true))
        val oldest = requireNotNull(first)
        val direct = reopened.inspectRetainedWork(oldest.retainedPath.substringBeforeLast('/'))
        assertTrue(direct.complete)
        assertEquals(oldest.token, direct.candidates.single().directoryDeletion!!.token)
        assertEquals("payload-0", root.resolve("${oldest.retainedPath}/deep/child.txt").readText())
        assertEquals(before, snapshot(root))
    }

    @Test fun retainedPayloadIsHiddenFromExplorerAndSearchButIncludedInWholeRootExactCapture() = runBlocking {
        val root = fixture("export")
        source(root, "src", "retained export bytes")
        val repository = FileRepository(root)
        assertTrue(repository.delete("src"))
        val retained = repository.retainedWork.value.notices.single().candidate.path
        assertTrue(repository.listFilesPage(options = DirectoryListingOptions.EXPLORER).entries.isEmpty())
        assertTrue(repository.findFiles("child").isEmpty())
        val before = snapshot(root)
        val permit = object : ExportCapturePermit {
            override val root = root
            override val scope = ExportScope()
            override val stabilityAssumptions = listOf("Synthetic fixture has no external writer")
            override fun validate() = Unit
        }
        val captured = ExportCaptureStore(fixture("export-stage")).capture(permit)
        val entries = mutableListOf<ExportEntry>()
        ExportManifest.each(captured.manifest, captured.manifestDigest) { entries += it }
        val payload = entries.single { it.path == "$retained/deep/child.txt" }
        assertEquals(ExportEntryType.FILE, payload.type)
        assertEquals("retained export bytes", captured.data(payload.dataId).readText())
        assertEquals(before, snapshot(root))

    }
}
