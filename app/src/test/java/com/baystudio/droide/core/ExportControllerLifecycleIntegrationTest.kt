package com.baystudio.droide.core

import android.app.Service
import android.content.*
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Real controller/admission/capture/engine/journal/service against a local DocumentsProvider.
 * This is host integration, not platform process-death/foreground-service acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ExportControllerLifecycleIntegrationTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var source: File
    private lateinit var provider: Provider
    private val scopes = mutableListOf<CoroutineScope>()
    private val barriers = mutableListOf<Barrier>()
    private val grants = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private val target get() = DocumentsContract.buildDocumentUri("export.lifecycle.test", "output")
    private val journalBase get() = File(context.filesDir, "exports-v1")
    private val payload = ByteArray(300_000) { (it % 251).toByte() }

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.shadows.ShadowApplication.setProcessName(context.packageName)
        context.applicationInfo.processName = context.packageName
        clearController()
        journalBase.deleteRecursively()
        ApplicationWorkAdmission.initialize(context)
        ApplicationWorkAdmission.reopenFromUser()
        directory = Files.createTempDirectory("export-controller").toFile()
        source = File(directory, "source").apply { mkdir(); resolve("data.bin").writeBytes(payload) }
        provider = Provider(File(directory, "provider").apply { mkdir() })
        provider.attachInfo(context, ProviderInfo().apply {
            authority = "export.lifecycle.test"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        })
        val bridge = LegacyBridge(provider).apply { attachInfo(context, ProviderInfo().apply { authority = "export.lifecycle.test" }) }
        ShadowContentResolver.registerProviderInternal("export.lifecycle.test", bridge)
    }
    @After fun cleanup() = runBlocking {
        barriers.forEach { it.release.countDown() }
        withTimeout(20_000) {
            ExportController.shutdownForExit(context)
            scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() }
            clearController()
        }
        directory.deleteRecursively(); journalBase.deleteRecursively(); Unit
    }
    private fun owner() = ExportSourceAdmission("lifecycle-project", source, object : WorkspaceDocumentAuthority {
        override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
        override suspend fun snapshots() = emptyList<WorkspaceDocumentSnapshot>()
    }, {})
    private suspend fun enqueue(admission: ExportSourceAdmission = owner()): String {
        val ticket = admission.prepare(admission.ticket(ExportScope("data.bin")), false)
        return ExportController.start(context, admission, ticket, ExportFormat.RAW_FILE, target, grants, true)
    }
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
    private fun launch(id: String, dispatcher: CoroutineDispatcher = Dispatchers.IO, finished: () -> Unit = {}) =
        ExportController.launch(scope(), context, id, ApplicationWorkAdmission.requireOpen(), dispatcher, finished)
    private suspend fun finished() {
        withTimeout(15_000) { (field("workerCompletion") as? CompletableDeferred<*>)?.await() }
        withTimeout(15_000) { persistenceJobs().joinAll() }
    }
    private fun field(name: String): Any? = ExportController.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(ExportController)
    private fun setField(name: String, value: Any?) = ExportController.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(ExportController, value)
    private fun persistenceJobs() = (field("persistenceScope") as CoroutineScope).coroutineContext[Job]!!.children.toList()
    @Suppress("UNCHECKED_CAST")
    private suspend fun clearController() {
        (field("worker") as? Job)?.cancelAndJoin()
        (field("workerCompletion") as? CompletableDeferred<*>)?.await()
        persistenceJobs().joinAll()
        ExportController::class.java.getDeclaredField("pointerAuthority").apply { isAccessible = true }.let {
            (it.get(ExportController) as? ExportPointerRecovery)?.close()
            it.set(ExportController, null)
        }
        listOf("journal", "pending", "destination", "worker", "workerCompletion").forEach { setField(it, null) }
        (field("mutableState") as MutableStateFlow<ExportJobState?>).value = null
        (field("mutableOpenRequest") as MutableStateFlow<Long>).value = 0
        setField("cancellation", false); setField("interrupted", false); setField("lastProgress", 0L)
    }
    private fun barrier() = Barrier().also { barriers += it }
    private class Barrier {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fun block() { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) { "Provider fixture timed out" } }
        fun awaitEntry() { assertTrue("Provider boundary was not reached", entered.await(15, TimeUnit.SECONDS)) }
    }
    private class QueuedDispatcher : CoroutineDispatcher() {
        val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun drain() { while (true) (queue.poll() ?: return).run() }
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected refusal") } catch (_: IllegalStateException) { }
    }
    private fun serviceIntent(id: String, epoch: String = ApplicationWorkAdmission.requireOpen(), action: String = "com.baystudio.droide.EXPORT_START") =
        Intent(context, ExportForegroundService::class.java).setAction(action)
            .putExtra(ExportForegroundService.EXTRA_ID, id).putExtra(ExportForegroundService.EXTRA_EPOCH, epoch)

    @Test fun malformedCurrentRecoversThenFreshExportVerifiesWithoutDeletingOldStaging() = runBlocking {
        journalBase.mkdirs()
        val bad = "version=broken\n"
        File(journalBase, "current.properties").writeText(bad)
        val precious = File(journalBase, "old/capture/payload").apply { checkNotNull(parentFile).mkdirs(); writeText("retained") }
        val id = enqueue(); assertTrue(launch(id)); finished()
        assertEquals(ExportJobPhase.COMPLETED_VERIFIED, ExportController.state.value!!.phase)
        assertArrayEquals(payload, provider.output.readBytes())
        assertEquals("retained", precious.readText())
        val evidence = journalBase.listFiles()!!.single { it.name.startsWith("recovery-") }.resolve("current.properties")
        assertEquals(bad, evidence.readText())
        assertEquals(1, ExportController.historySnapshot(context).preservedPointers)
        assertTrue(runCatching { ExportPointerRecovery.open(journalBase) }.isFailure)
        assertFalse(ExportJournal(journalBase).hasCapture(id))
    }
    @Test fun cancelledRecoveryReleasesOwnerAndRetrySameControllerSucceeds() = runBlocking {
        journalBase.mkdirs()
        File(journalBase, "current.properties").writeText("version=1\nid=old\nepoch=epoch\nphase=INVALID\nentries=0\nbytes=0\n")
        try {
            ExportController.initialize(context) {
                if (journalBase.listFiles().orEmpty().any { it.name.startsWith("recovery-") && it.resolve("current.properties").exists() })
                    throw CancellationException("cancel after metadata move")
            }
            fail("Cancellation was ignored")
        } catch (_: CancellationException) { }
        assertNull(field("journal")); assertNull(field("pointerAuthority")); assertNull(field("worker"))
        ExportController.initialize(context)
        assertNotNull(field("journal")); assertNotNull(field("pointerAuthority"))
        assertNull(ExportController.state.value)
    }

    @Test fun activeCorruptPointerStopsWorkerAndPublishesHonestTerminalMemoryState() = runBlocking {
        val blocked = barrier(); provider.beforeWrite = blocked::block
        val id = enqueue(); assertTrue(launch(id)); blocked.awaitEntry()
        val pointer = File(journalBase, "current.properties")
        val before = pointer.readBytes()
        val bad = pointer.readText().replace(Regex("entries=[0-9]+"), "entries=-1")
        try {
            pointer.writeText(bad)
            blocked.release.countDown(); finished()
            val stopped = checkNotNull(ExportController.state.value)
            assertEquals(ExportJobPhase.INTERRUPTED, stopped.phase)
            assertTrue(stopped.terminal)
            assertTrue(stopped.detail.contains("stopped", ignoreCase = true))
            assertTrue((field("worker") as Job).isCompleted)
            assertTrue((field("workerCompletion") as CompletableDeferred<*>).isCompleted)
            assertEquals(bad, pointer.readText())
            assertArrayEquals(payload, source.resolve("data.bin").readBytes())
        } finally {
            blocked.release.countDown()
            // Restore only this synthetic fixture so teardown can exercise ordinary shutdown.
            pointer.writeBytes(before)
        }
    }

    @Test fun realRawPipelineVerifiesOnceAndCleansOwnedCapture() = runBlocking {
        val id = enqueue(); assertTrue(launch(id)); finished()
        assertEquals(ExportJobPhase.COMPLETED_VERIFIED, ExportController.state.value!!.phase)
        assertArrayEquals(payload, provider.output.readBytes())
        assertEquals(1, provider.writes.get()); assertEquals(1, provider.reads.get())
        assertFalse(ExportController.state.value!!.retainedCapture)
        assertFalse(ExportJournal(journalBase).hasCapture(id))
        assertEquals(target, ExportController.destinationForReview(context, id))
        assertEquals(id, ExportController.history(context).single().id)
    }

    @Test fun cancelBeforeDispatchFinalizesExactlyOnceWithoutOpeningProvider() = runBlocking {
        val id = enqueue(); val dispatcher = QueuedDispatcher(); val callbacks = AtomicInteger()
        assertTrue(launch(id, dispatcher) { callbacks.incrementAndGet() })
        ExportController.cancel(id)
        assertFalse("A cancelled queued worker must not be replaced", launch(id, dispatcher) { callbacks.incrementAndGet() })
        dispatcher.drain(); finished()
        assertEquals(ExportJobPhase.CANCELLED, ExportController.state.value!!.phase)
        assertEquals(1, callbacks.get()); assertEquals(0, provider.writes.get()); assertEquals(0, provider.reads.get())
        assertNull(field("pending")); assertNull(field("destination"))
    }

    @Test fun cancelledWriteRetainsStagingUntilExplicitDiscardAndNeverDeletesOutput() = runBlocking {
        val blocked = barrier(); provider.beforeWrite = blocked::block
        val id = enqueue(); assertTrue(launch(id)); blocked.awaitEntry()
        ExportController.cancel(id)
        assertEquals(ExportJobPhase.CANCELLING, ExportController.state.value!!.phase)
        rejected { enqueue() }
        blocked.release.countDown(); finished()
        assertEquals(ExportJobPhase.CANCELLED, ExportController.state.value!!.phase)
        assertTrue(ExportController.state.value!!.retainedCapture)
        rejected { enqueue() }
        ExportController.discardRetainedCapture(context, id)
        assertFalse(ExportController.state.value!!.retainedCapture)
        assertTrue(provider.output.exists())
        assertArrayEquals(payload, source.resolve("data.bin").readBytes())
        assertEquals(target, ExportController.destinationForReview(context, id))
        assertNotEquals(id, enqueue())
    }

    @Test fun cancellationDuringReadbackNeverPublishesVerifiedSuccess() = runBlocking {
        val blocked = barrier(); provider.beforeRead = blocked::block
        val id = enqueue(); assertTrue(launch(id)); blocked.awaitEntry()
        ExportController.cancel(id); blocked.release.countDown(); finished()
        assertEquals(ExportJobPhase.CANCELLED, ExportController.state.value!!.phase)
        assertEquals(1, provider.writes.get()); assertEquals(1, provider.reads.get())
        assertArrayEquals(payload, provider.output.readBytes())
    }

    @Test fun readbackCorruptionCannotBecomeSuccess() = runBlocking {
        provider.beforeRead = { provider.output.writeBytes(byteArrayOf(1, 2, 3)) }
        val id = enqueue(); assertTrue(launch(id)); finished()
        assertNotEquals(ExportJobPhase.COMPLETED_VERIFIED, ExportController.state.value!!.phase)
        assertTrue(ExportController.state.value!!.retainedCapture)
        assertEquals(1, provider.writes.get()); assertEquals(1, provider.reads.get())
    }

    @Test fun sealedTransferNoLongerDependsOnSourceOwnerOrLiveBytes() = runBlocking {
        val blocked = barrier(); provider.beforeWrite = blocked::block
        val admission = owner(); val id = enqueue(admission); assertTrue(launch(id)); blocked.awaitEntry()
        assertNull("Controller must release its pending source owner", field("pending"))
        withTimeout(2_000) { admission.retireAndDrain() }
        source.resolve("data.bin").writeText("changed after capture")
        blocked.release.countDown(); finished()
        assertEquals(ExportJobPhase.COMPLETED_VERIFIED, ExportController.state.value!!.phase)
        assertArrayEquals(payload, provider.output.readBytes())
    }

    @Test fun completionCallbackMustDrainBeforeAnotherJobIsAdmitted() = runBlocking {
        val blocked = barrier()
        val id = enqueue(); assertTrue(launch(id) { blocked.block() }); blocked.awaitEntry()
        assertEquals(ExportJobPhase.COMPLETED_VERIFIED, ExportController.state.value!!.phase)
        rejected { enqueue() }
        blocked.release.countDown(); finished()
        assertNotEquals(id, enqueue())
    }

    @Test fun exitDrainsBlockedProviderAndPersistsInterruptedState() = runBlocking {
        val blocked = barrier(); provider.beforeWrite = blocked::block
        val id = enqueue(); assertTrue(launch(id)); blocked.awaitEntry()
        ApplicationWorkAdmission.closeForExit()
        val exit = async(Dispatchers.Default) { ExportController.shutdownForExit(context) }
        withTimeout(5_000) { while (ExportController.state.value!!.phase != ExportJobPhase.INTERRUPTED) delay(5) }
        assertFalse(exit.isCompleted)
        blocked.release.countDown(); withTimeout(15_000) { exit.await() }
        assertEquals(ExportJobPhase.INTERRUPTED, ExportJournal(journalBase).read()!!.phase)
        assertFalse(ApplicationWorkAdmission.isOpen)
    }

    @Test fun queuedExitHasDurableStateEvenWithoutAWorker() = runBlocking {
        enqueue(); ApplicationWorkAdmission.closeForExit(); ExportController.shutdownForExit(context)
        assertEquals(ExportJobPhase.INTERRUPTED, ExportJournal(journalBase).read()!!.phase)
        assertEquals(0, provider.writes.get())
    }

    @Test fun exitBetweenDurablePreparationAndOwnerHandoffLeavesInterruptedHistory() = runBlocking {
        val admission = owner(); val ticket = admission.prepare(admission.ticket(ExportScope("data.bin")), false)
        try {
            ExportController.start(context, admission, ticket, ExportFormat.RAW_FILE, target, grants, true,
                beforeOwnerPublication = { ApplicationWorkAdmission.closeForExit() })
            fail("Closed epoch must reject handoff")
        } catch (_: CancellationException) { }
        val record = ExportController.history(context).single()
        assertEquals(ExportJobPhase.INTERRUPTED, record.phase)
        assertEquals(target, ExportController.destinationForReview(context, record.id))
        assertNull(field("pending")); assertNull(field("worker")); assertEquals(0, provider.writes.get())
        clearController(); ExportController.initialize(context)
        assertEquals(ExportJobPhase.INTERRUPTED, ExportController.state.value!!.phase)
    }

    @Test fun journalFailureIsVisibleAndRecoveryNeverAutomaticallyReplays() = runBlocking {
        val id = enqueue()
        File(journalBase, "current.tmp").mkdir() // Force journal failure, not a mocked success.
        assertTrue(launch(id)); finished()
        assertEquals(ExportJobPhase.INTERRUPTED, ExportController.state.value!!.phase)
        assertTrue(ExportController.state.value!!.detail.contains("journal"))
        assertEquals(0, provider.writes.get())
        File(journalBase, "current.tmp").delete()
        clearController(); ExportController.initialize(context)
        assertEquals(id, ExportController.state.value!!.id)
        assertEquals(ExportJobPhase.INTERRUPTED, ExportController.state.value!!.phase)
        assertNull(field("worker")); assertEquals(0, provider.writes.get())
    }

    @Test fun realServiceDuplicateStartDoesNotDuplicateWritesAndTimeoutStopsPromptly() = runBlocking {
        val blocked = barrier(); provider.beforeWrite = blocked::block
        val id = enqueue(); val controller = Robolectric.buildService(ExportForegroundService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(serviceIntent(id), 0, 1))
            blocked.awaitEntry()
            service.onStartCommand(serviceIntent(id), 0, 2)
            assertEquals(1, provider.writes.get()); assertFalse(shadowOf(service).isStoppedBySelf)
            service.onTimeout(2, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals(ExportJobPhase.INTERRUPTED, ExportController.state.value!!.phase)
            assertFalse((field("workerCompletion") as CompletableDeferred<*>).isCompleted)
            blocked.release.countDown(); finished(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, provider.writes.get()); assertNotEquals(ExportJobPhase.COMPLETED_VERIFIED, ExportController.state.value!!.phase)
        } finally { blocked.release.countDown(); controller.destroy() }
    }

    @Test fun staleEpochCannotLaunchServiceOrOpenNotification() = runBlocking {
        val id = enqueue(); val epoch = ApplicationWorkAdmission.requireOpen()
        ExportController.requestOpen(id, "wrong-epoch"); assertEquals(0L, ExportController.openRequest.value)
        ExportController.requestOpen(id, epoch); assertEquals(1L, ExportController.openRequest.value)
        ApplicationWorkAdmission.closeForExit(); ExportController.shutdownForExit(context)
        ApplicationWorkAdmission.reopenFromUser()
        ExportController.requestOpen(id, epoch); assertEquals(1L, ExportController.openRequest.value)
        val controller = Robolectric.buildService(ExportForegroundService::class.java).create()
        try {
            assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(serviceIntent(id, epoch), 0, 1))
            assertTrue(shadowOf(controller.get()).isStoppedBySelf); assertEquals(0, provider.writes.get())
        } finally { controller.destroy() }
    }

    private class Provider(base: File) : DocumentsProvider() {
        val output = File(base, "output")
        val writes = AtomicInteger(); val reads = AtomicInteger()
        @Volatile var beforeWrite: () -> Unit = {}
        @Volatile var beforeRead: () -> Unit = {}
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?) = MatrixCursor(arrayOf("root_id"))
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?) = MatrixCursor(projection ?: arrayOf("document_id"))
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
            check(documentId == "output")
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_FLAGS)
            return MatrixCursor(columns).apply { addRow(columns.map<String, Any?> { when (it) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> "output"
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> "output.bin"
                DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                DocumentsContract.Document.COLUMN_MIME_TYPE -> "application/octet-stream"
                else -> null
            } }.toTypedArray()) }
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            check(documentId == "output")
            if (mode == "r") { reads.incrementAndGet(); beforeRead() } else { writes.incrementAndGet(); beforeWrite() }
            return ParcelFileDescriptor.open(output, ParcelFileDescriptor.parseMode(mode))
        }
    }
    private class LegacyBridge(private val provider: DocumentsProvider) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
            provider.query(uri, projection, Bundle(), null)
        override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor? = provider.query(uri, projection, queryArgs, cancellationSignal)
        override fun getType(uri: Uri) = provider.getType(uri)
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = provider.openFile(uri, mode)
        override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor? = provider.openFile(uri, mode, signal)
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = provider.call(method, arg, extras)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("No insertion in raw fixture")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("No deletion in fixture")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("No update in fixture")
    }
}
