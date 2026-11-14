package com.baystudio.droide.core

import android.app.Application
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkspaceImportControllerTest {
    private val uri: Uri get() = Uri.parse("content://test-provider/picked")

    @Test fun detachedObserverDoesNotCancelStreamAndCapturedDestinationWins() = workspace { root, parent ->
        root.resolve("chosen").mkdir(); root.resolve("later").mkdir()
        val input = PausedInput("source content".toByteArray())
        val controller = WorkspaceImportController(FileRepository(root), parent, source("main.kt", input))
        try {
            var selected = "chosen"
            val ticket = controller.capturePicker(selected)
            selected = "later"
            assertEquals(WorkspaceImportAdmission.ACCEPTED, controller.submit(ticket, uri))
            withTimeout(5_000) { input.entered.await() }
            val observer = launch { controller.state.first { !it.busy } }
            observer.cancelAndJoin()
            assertTrue(controller.state.value.busy)
            assertEquals(WorkspaceImportAdmission.BUSY, controller.submit(ticket, uri))
            input.release.countDown()
            val done = done(controller)
            assertEquals(WorkspaceImportResultStatus.IMPORTED, done.status)
            assertEquals("chosen/main.kt", done.relativePath)
            assertEquals("source content", root.resolve("chosen/main.kt").readText())
            assertFalse(root.resolve("$selected/main.kt").exists())
            assertTrue(input.closed.get())
            assertEquals(done, controller.state.value.result)
        } finally { input.release.countDown(); controller.closeAndJoin() }
    }

    @Test fun cancelledCopyKeepsAdmissionBusyUntilProviderCloseActuallyFinishes() = workspace { root, parent ->
        val input = DelayedCloseInput()
        val files = FileRepository(root)
        val controller = WorkspaceImportController(files, parent, source("large.bin", input))
        val ticket = controller.capturePicker("")
        try {
            assertEquals(WorkspaceImportAdmission.ACCEPTED, controller.submit(ticket, uri))
            withTimeout(5_000) { input.readStarted.await() }
            assertTrue(controller.cancel())
            assertFalse(controller.cancel())
            withTimeout(5_000) { input.closeStarted.await() }
            assertTrue(controller.state.value.busy)
            assertTrue(controller.state.value.active!!.cancelling)
            assertEquals(WorkspaceImportAdmission.BUSY, controller.submit(ticket, uri))
            input.finishClose.countDown()
            assertEquals(WorkspaceImportResultStatus.CANCELLED, done(controller).status)
            assertFalse(root.resolve("large.bin").exists())
            val retained = root.listFiles().orEmpty().filter { RetainedWorkPolicy.isCandidateName(it.name) }.single()
            assertEquals(0L, retained.length())
            val notice = files.retainedWork.value.notices.single()
            assertEquals(retained.name, notice.candidate.path)
            assertEquals(RetainedWorkKind.PRESENT, notice.candidate.kind)
            assertFalse(notice.historicalWriteReturned)
            assertEquals(1, input.closeCalls.get())
        } finally { input.finishClose.countDown(); controller.closeAndJoin() }
    }

    @Test fun runtimeCloseCancelsAcceptedWorkAndPreservesForeignDestination() = workspace { root, parent ->
        val admitted = CompletableDeferred<Unit>()
        val closedInput = AtomicBoolean()
        val input = object : ByteArrayInputStream("incoming".toByteArray()) {
            override fun close() { closedInput.set(true); super.close() }
        }
        val files = FileRepository(root, beforeMutation = {
            if (it is FileRepository.Mutation.Write) { admitted.complete(Unit); awaitCancellation() }
        })
        val controller = WorkspaceImportController(files, parent, source("same.txt", input))
        val ticket = controller.capturePicker("")
        try {
            assertEquals(WorkspaceImportAdmission.ACCEPTED, controller.submit(ticket, uri))
            withTimeout(5_000) { admitted.await() }
            root.resolve("same.txt").writeText("terminal owns this file")
            withTimeout(5_000) { controller.closeAndJoin() }
            assertTrue(closedInput.get())
            assertTrue(parent.isActive)
            assertTrue(controller.state.value.closed)
            assertFalse(controller.state.value.busy)
            assertEquals(WorkspaceImportResultStatus.CANCELLED, controller.state.value.result!!.status)
            assertEquals("terminal owns this file", root.resolve("same.txt").readText())
            assertEquals(WorkspaceImportAdmission.CLOSED, controller.submit(ticket, uri))
        } finally { controller.closeAndJoin() }
    }

    @Test fun parentCancellationAlsoClosesBlockedProviderReadAndJoinsItsCleanup() = workspace { root, parent ->
        val input = DelayedCloseInput()
        val controller = WorkspaceImportController(FileRepository(root), parent, source("cancelled.bin", input))
        try {
            assertEquals(WorkspaceImportAdmission.ACCEPTED, controller.submit(controller.capturePicker(""), uri))
            withTimeout(5_000) { input.readStarted.await() }
            parent.cancel()
            withTimeout(5_000) { input.closeStarted.await() }
            assertTrue(controller.state.value.busy)
            input.finishClose.countDown()
            withTimeout(5_000) { controller.closeAndJoin() }
            assertEquals(WorkspaceImportResultStatus.CANCELLED, controller.state.value.result!!.status)
            assertTrue(controller.state.value.closed)
            assertEquals(1, input.closeCalls.get())
            assertFalse(root.resolve("cancelled.bin").exists())
        } finally { input.finishClose.countDown(); controller.closeAndJoin() }
    }

    @Test fun matchingCanonicalReceiptBecomesSyncPendingWithoutRetryingTheImport() = workspace { root, parent ->
        val files = FileRepository(root, onMutation = { throw IOException("mirror unavailable") })
        val controller = WorkspaceImportController(files, parent, source("asset.txt", ByteArrayInputStream("saved".toByteArray())))
        try {
            assertEquals(WorkspaceImportAdmission.ACCEPTED, controller.submit(controller.capturePicker(""), uri))
            val result = done(controller)
            assertEquals(WorkspaceImportResultStatus.SYNC_PENDING, result.status)
            assertEquals("asset.txt", result.relativePath)
            assertEquals(5L, result.bytes)
            assertEquals("saved", root.resolve("asset.txt").readText())
            assertTrue(result.message.contains("do not import again"))
        } finally { controller.closeAndJoin() }
    }

    @Test fun wrongPathOrPrecommitTypedExceptionIsNeverReportedAsSaved() = workspace { root, parent ->
        val files = FileRepository(root, beforeMutation = {
            throw ProjectFileSyncException("other.txt", 5, IOException("unrelated receipt"))
        })
        val controller = WorkspaceImportController(files, parent, source("asset.txt", ByteArrayInputStream("saved".toByteArray())))
        try {
            assertEquals(WorkspaceImportAdmission.ACCEPTED, controller.submit(controller.capturePicker(""), uri))
            assertEquals(WorkspaceImportResultStatus.FAILED, done(controller).status)
            assertFalse(root.resolve("asset.txt").exists())
        } finally { controller.closeAndJoin() }
    }

    @Test fun providerCloseFailureAfterPublicationPreservesImportedReceipt() = closeFailureAfterCommit(syncPending = false)

    @Test fun providerCloseFailureAfterPendingSyncPreservesSavedReceipt() = closeFailureAfterCommit(syncPending = true)

    private fun closeFailureAfterCommit(syncPending: Boolean) = workspace { root, parent ->
        val closes = AtomicInteger()
        val input = object : ByteArrayInputStream("committed bytes".toByteArray()) {
            override fun close() { closes.incrementAndGet(); throw IOException("provider close failed") }
        }
        val files = FileRepository(root, onMutation = { if (syncPending) throw IOException("mirror unavailable") })
        val controller = WorkspaceImportController(files, parent, source("saved.txt", input))
        try {
            assertEquals(WorkspaceImportAdmission.ACCEPTED, controller.submit(controller.capturePicker(""), uri))
            val result = done(controller)
            assertEquals(if (syncPending) WorkspaceImportResultStatus.SYNC_PENDING else WorkspaceImportResultStatus.IMPORTED,
                result.status)
            assertEquals("saved.txt", result.relativePath)
            assertEquals("committed bytes".toByteArray().size.toLong(), result.bytes)
            assertEquals("committed bytes", root.resolve("saved.txt").readText())
            assertTrue(result.message.contains("Source handle cleanup failed"))
            assertTrue(result.message.contains("Do not import again"))
            assertEquals(1, closes.get())
        } finally { controller.closeAndJoin() }
    }

    @Test fun competingCleanupCannotFinishBeforeTheWinningProviderCloseReturns() = runBlocking {
        val provider = DelayedCloseInput()
        val input = WorkspaceImportController.OnceClosedInput(provider)
        val winningClose = async(Dispatchers.IO) { input.close() }
        try {
            withTimeout(5_000) { provider.closeStarted.await() }
            // UNDISTPATCHED executes the losing close through its first suspension now,
            // avoiding a sleep or a race between asserting and starting the waiter.
            val workerCleanup = async(start = CoroutineStart.UNDISPATCHED) { input.finishClose() }
            assertFalse("A losing close must join the provider, not just observe its claimed flag", workerCleanup.isCompleted)
            assertFalse(winningClose.isCompleted)
            provider.finishClose.countDown()
            withTimeout(5_000) { winningClose.await(); assertNull(workerCleanup.await()) }
            assertEquals(1, provider.closeCalls.get())
        } finally { provider.finishClose.countDown(); winningClose.join() }
    }

    @Test fun oldPickerGenerationAndDifferentRuntimeCannotImportIntoReplacementWorkspace() = workspace { root, parent ->
        val opens = AtomicInteger()
        val source = object : WorkspaceImportSource {
            override suspend fun metadata(uri: Uri) = WorkspaceImportSourceMetadata("file.txt", 1)
            override suspend fun open(uri: Uri): InputStream { opens.incrementAndGet(); return ByteArrayInputStream(byteArrayOf(1)) }
        }
        val controller = WorkspaceImportController(FileRepository(root), parent, source)
        val otherRoot = root.resolve("replacement").apply { mkdir() }
        val replacement = WorkspaceImportController(FileRepository(otherRoot), parent, source)
        try {
            val old = controller.capturePicker("")
            val current = controller.capturePicker("")
            assertFalse(controller.isCurrentPicker(old))
            assertTrue(controller.isCurrentPicker(current))
            assertFalse(replacement.isCurrentPicker(current))
            assertEquals(WorkspaceImportAdmission.STALE, controller.submit(old, uri))
            assertEquals(WorkspaceImportAdmission.STALE, replacement.submit(current, uri))
            assertEquals(0, opens.get())
            assertFalse(replacement.state.value.busy)
            assertNull(replacement.state.value.result)
            assertEquals(WorkspaceImportAdmission.ACCEPTED, controller.submit(current, uri))
            assertFalse(controller.isCurrentPicker(current))
            assertEquals(WorkspaceImportResultStatus.IMPORTED, done(controller).status)
            assertEquals(WorkspaceImportAdmission.STALE, controller.submit(current, uri))
            assertEquals(1, opens.get())
            assertFalse(otherRoot.resolve("file.txt").exists())
        } finally { controller.closeAndJoin(); replacement.closeAndJoin() }
    }

    @Test fun destinationRemovalAndUnsafeSourceNamesFailWithoutCreatingAnotherTarget() = workspace { root, parent ->
        val destination = root.resolve("selected").apply { mkdir() }
        val controller = WorkspaceImportController(FileRepository(root), parent, source("file.txt", ByteArrayInputStream(byteArrayOf(1))))
        val unsafe = WorkspaceImportController(FileRepository(root), parent, source("../escape.txt", ByteArrayInputStream(byteArrayOf(1))))
        try {
            val ticket = controller.capturePicker("selected")
            destination.delete()
            assertEquals(WorkspaceImportAdmission.ACCEPTED, controller.submit(ticket, uri))
            assertEquals(WorkspaceImportResultStatus.FAILED, done(controller).status)
            assertFalse(destination.exists())
            assertFalse(root.resolve("file.txt").exists())
            assertEquals(WorkspaceImportAdmission.ACCEPTED, unsafe.submit(unsafe.capturePicker(""), uri))
            assertEquals(WorkspaceImportResultStatus.FAILED, done(unsafe).status)
            assertFalse(root.resolve("escape.txt").exists())
        } finally { controller.closeAndJoin(); unsafe.closeAndJoin() }
    }

    private fun source(name: String, stream: InputStream) = object : WorkspaceImportSource {
        override suspend fun metadata(uri: Uri) = WorkspaceImportSourceMetadata(name, null)
        override suspend fun open(uri: Uri): InputStream = stream
    }

    private suspend fun done(controller: WorkspaceImportController): WorkspaceImportResult =
        withTimeout(5_000) { controller.state.first { !it.busy && it.result != null }.result!! }

    private class PausedInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val closed = AtomicBoolean()
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS)) { "Test source was never released" }
            return super.read(bytes, offset, length)
        }
        override fun close() { closed.set(true); release.countDown(); super.close() }
    }

    private class DelayedCloseInput : InputStream() {
        val readStarted = CompletableDeferred<Unit>()
        val closeStarted = CompletableDeferred<Unit>()
        val finishClose = CountDownLatch(1)
        val closeCalls = AtomicInteger()
        private val readRelease = CountDownLatch(1)
        override fun read(): Int {
            readStarted.complete(Unit)
            check(readRelease.await(5, TimeUnit.SECONDS)) { "Test stream was never closed" }
            throw IOException("Provider stream closed")
        }
        override fun close() {
            closeCalls.incrementAndGet()
            closeStarted.complete(Unit)
            readRelease.countDown()
            check(finishClose.await(5, TimeUnit.SECONDS)) { "Test close was never released" }
        }
    }

    private fun workspace(block: suspend CoroutineScope.(java.io.File, CoroutineScope) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("workspace-owned-import-").toFile()
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { block(root, parent) } finally { parent.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }
}
