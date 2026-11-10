package com.baystudio.droide.core

import java.io.File
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
class MirrorInterruptedRecoveryTest {
    @get:org.junit.Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private class Interrupted : RuntimeException()
    private class Fixture {
        val root = Files.createTempDirectory("interrupted-mirror-").toFile()
        val source = File(root, "source").apply { mkdir(); resolve("file").writeText("old") }
        val working = File(root, "working")
        val directory = File(root, ".working-sync-authority")
        val authority get() = MirrorMetadataAuthority(directory, source, working)
        val recovery by lazy { MirrorInterruptedRecovery(source, working) }
        lateinit var operation: MirrorMetadataAuthority.Intent
        var processReceipt: AutoCloseable? = null
        suspend fun interrupt(boundary: String, startWriter: Boolean) {
            LocalAgentWorkspaceMirror.prepareRetained(source, working)
            if (startWriter) processReceipt = authority.processCustody(java.util.UUID.randomUUID().toString()).acquire(working).also { it.withFactory({}, {}) }
            source.resolve("file").writeText("new")
            val engine = MirrorWorkingCopyMutations(source, working, authority) { step, intent ->
                if (step == boundary) { operation = intent; throw Interrupted() }
            }
            val failure = runCatching { WorkspaceFileMutationLocks.withRoot(working) { guard ->
                engine.apply(guard, authority.load().revision, listOf(MirrorMetadataAuthority.Proposal("file", "replace", node("old"), node("new"))))
            } }.exceptionOrNull()
            assertTrue(failure.toString(), failure is Interrupted)
        }
        fun payloads() = listOf(source, working, directory.resolve("retained"), directory.resolve("prepared")).flatMap { base ->
            base.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path to hash(it.readBytes()) }.toList()
        }.toMap()
        fun metadata() = hash(directory.resolve("state.sqlite").readBytes())
    }
    private fun fixture(boundary: String = "published", startWriter: Boolean = false, action: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try { withTimeout(30_000) { fixture.interrupt(boundary, startWriter); fixture.action() } }
        finally { fixture.processReceipt?.close(); assertTrue(PathSecurity.deleteTreeNoFollow(fixture.root)) }
    }

    @Test fun publishedChangeCompletesAtomicallyWithoutChangingAnyPayload() = fixture {
        val before = payloads()
        val review = recovery.inspect(operation)
        assertTrue(review.canComplete)
        recovery.finish(review, true) {}
        val reopened = authority.loadExecutionReadyState()
        assertEquals(node("new").sha256, reopened.baseline["file"]!!.sha256)
        assertEquals(MirrorMetadataAuthority.Phase.APPLIED, authority.operation(operation.id).phase)
        assertTrue(reopened.conflicts.isEmpty()); assertEquals(before, payloads())
        LocalAgentWorkspaceMirror.openRetained(source, working)
    }

    @Test fun unfinishedPublicationCanOnlyBecomeAProtectedConflict() = fixture("staged") {
        val before = payloads()
        val review = recovery.inspect(operation)
        assertFalse(review.canComplete)
        val metadata = metadata()
        assertNotNull(runCatching { recovery.finish(review, true) {} }.exceptionOrNull())
        assertEquals(metadata, metadata())
        recovery.finish(review, false) {}
        assertEquals(setOf("file"), authority.loadExecutionReadyState().conflicts)
        assertEquals(MirrorMetadataAuthority.Phase.CONFLICT, authority.operation(operation.id).phase)
        LocalAgentWorkspaceMirror.openRetained(source, working).refresh()
        assertEquals(before, payloads())
        assertEquals("old", working.resolve("file").readText())
    }

    @Test fun lateWriterAfterInspectionPreventsCompletionAndPreservesJournal() = fixture {
        val review = recovery.inspect(operation)
        val metadata = metadata()
        directory.resolve("retained/${operation.retainedName}").writeText("late writer")
        val before = payloads()
        assertNotNull(runCatching { recovery.finish(review, true) {} }.exceptionOrNull())
        assertEquals(metadata, metadata()); assertEquals(before, payloads())
        assertEquals(MirrorMetadataAuthority.Phase.PREPARED, authority.operation(operation.id).phase)
    }

    @Test fun alreadyObservedLateWriterRemainsProtectedAfterCompletingPublication() = fixture {
        directory.resolve("retained/${operation.retainedName}").writeText("late writer")
        val review = recovery.inspect(operation)
        val before = payloads()
        assertTrue(review.canComplete)
        recovery.finish(review, true) {}
        assertEquals(setOf("file"), authority.loadExecutionReadyState().conflicts)
        assertEquals(before, payloads())
    }

    @Test fun sameContentReplacementInvalidatesReviewIdentity() = fixture {
        val review = recovery.inspect(operation)
        val metadata = metadata()
        Files.move(working.resolve("file").toPath(), working.resolve("saved").toPath())
        working.resolve("file").writeText("new")
        assertNotNull(runCatching { recovery.finish(review, true) {} }.exceptionOrNull())
        assertEquals(metadata, metadata()); assertEquals("new", working.resolve("saved").readText())
    }

    @Test fun reviewCannotBeReusedByAnotherOwnerOrAfterCompletion() = fixture {
        val review = recovery.inspect(operation)
        assertNotNull(runCatching { MirrorInterruptedRecovery(source, working).finish(review, true) {} }.exceptionOrNull())
        recovery.finish(review, true) {}
        val metadata = metadata()
        assertNotNull(runCatching { recovery.finish(review, true) {} }.exceptionOrNull())
        assertEquals(metadata, metadata())
    }

    @Test fun ownerRetirementOrCancellationDoesNotPublishRecoveryStatus() = fixture {
        val review = recovery.inspect(operation)
        val metadata = metadata()
        assertTrue(runCatching { recovery.finish(review, true) { throw CancellationException("closed owner") } }.exceptionOrNull() is CancellationException)
        assertEquals(metadata, metadata())
        assertEquals(MirrorMetadataAuthority.Phase.PREPARED, authority.operation(operation.id).phase)
    }

    @Test fun pendingWriterKeepsTheInterruptedJournalAndPayloadsUntilCleanup() = fixture {
        val review = recovery.inspect(operation); val metadata = metadata(); val before = payloads()
        ExportWriterAdmission.beginWriter(working, "unconfirmed mirror process").use {
            assertNotNull(runCatching { recovery.finish(review, true) {} }.exceptionOrNull())
            assertEquals(metadata, metadata()); assertEquals(before, payloads())
            assertEquals(MirrorMetadataAuthority.Phase.PREPARED, authority.operation(operation.id).phase)
        }
        recovery.finish(review, true) {}
        assertEquals(MirrorMetadataAuthority.Phase.APPLIED, authority.operation(operation.id).phase)
        assertEquals(before, payloads())
    }

    @Test fun persistedProcessCustodyBlocksCompletionAfterAuthorityRecreation() = fixture(startWriter = true) {
        val reopened = MirrorInterruptedRecovery(source, working)
        val review = reopened.inspect(operation); val metadata = metadata(); val before = payloads()
        ExportWriterAdmission.beginCapture(working).close()
        val failure = runCatching { reopened.finish(review, true) {} }.exceptionOrNull()
        assertTrue(failure.toString(), failure?.message.orEmpty().contains("cleanup receipt"))
        assertEquals(metadata, metadata()); assertEquals(before, payloads())
        assertEquals(MirrorMetadataAuthority.Phase.PREPARED, authority.operation(operation.id).phase)
        processReceipt!!.close()
        reopened.finish(review, true) {}
        assertEquals(MirrorMetadataAuthority.Phase.APPLIED, authority.operation(operation.id).phase)
        assertEquals(before, payloads())
    }

    @Test fun missingStableCopyPreventsAutomaticCompletion() = fixture {
        assertTrue(directory.resolve("retained/${operation.stableName}").delete())
        val review = recovery.inspect(operation)
        assertFalse(review.canComplete)
        assertNotNull(runCatching { recovery.finish(review, true) {} }.exceptionOrNull())
        recovery.finish(review, false) {}
        assertEquals(setOf("file"), authority.loadExecutionReadyState().conflicts)
        assertEquals("new", working.resolve("file").readText())
    }

    @Test fun pendingRecordWithoutRetainedBytesIsStillDiscoverable() = fixture("prepared") {
        val page = MirrorRetainedRecovery(source, working).list()
        assertEquals(listOf(operation), page.interrupted)
        assertFalse(page.items.any { it.operation.id == operation.id })
        recovery.finish(recovery.inspect(operation), false) {}
        assertEquals(setOf("file"), authority.loadExecutionReadyState().conflicts)
    }

    companion object {
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun node(text: String) = MirrorMetadataAuthority.Node.file(WorkspaceSyncManifest.Entry(hash(text.toByteArray()), 420))
    }
}
