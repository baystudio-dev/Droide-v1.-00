package com.baystudio.droide.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WorkspaceImportTicket internal constructor(
    internal val owner: String,
    internal val generation: Long,
    val workspaceRoot: String,
    val destination: String,
    internal val admittedAtNanos: Long,
)

enum class WorkspaceImportAdmission { ACCEPTED, BUSY, STALE, CLOSED }
enum class WorkspaceImportResultStatus { IMPORTED, SYNC_PENDING, CANCELLED, FAILED, RECOVERY_REQUIRED }

data class WorkspaceImportProgress(
    val id: Long,
    val displayName: String,
    val destination: String,
    val progress: StreamWriteProgress,
    val cancelling: Boolean = false,
)

data class WorkspaceImportResult(
    val id: Long,
    val status: WorkspaceImportResultStatus,
    val relativePath: String?,
    val displayName: String,
    val bytes: Long?,
    val message: String,
    val transactionId: String? = null,
    val localCommitRecorded: Boolean = false,
    val sha256: String? = null,
)

data class WorkspaceImportState(
    val active: WorkspaceImportProgress? = null,
    val result: WorkspaceImportResult? = null,
    val closed: Boolean = false,
) {
    val busy: Boolean get() = active != null
}

internal data class WorkspaceImportSourceMetadata(val displayName: String?, val expectedBytes: Long?)
internal interface WorkspaceImportSource {
    suspend fun metadata(uri: Uri): WorkspaceImportSourceMetadata
    suspend fun open(uri: Uri): InputStream
}

/** One streamed import belongs to a workspace runtime, never to a pane or Activity. */
class WorkspaceImportController internal constructor(
    private val files: FileRepository,
    parentScope: CoroutineScope,
    private val source: WorkspaceImportSource,
    private val documentAuthority: WorkspaceDocumentAuthority? = null,
    private val copyJournal: ProjectCopyJournal = files.copyRecoveryJournal(),
    private val admissionClockNanos: () -> Long = System::nanoTime,
) {
    constructor(context: Context, files: FileRepository, parentScope: CoroutineScope,
        documentAuthority: WorkspaceDocumentAuthority? = null) :
        this(files, parentScope, ResolverSource(context.applicationContext), documentAuthority)

    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val owner = UUID.randomUUID().toString()
    private val root = files.root.canonicalPath
    private val lock = Any()
    private val mutableState = MutableStateFlow(WorkspaceImportState())
    val state: StateFlow<WorkspaceImportState> = mutableState.asStateFlow()
    private var pickerGeneration = 0L
    private var operationSequence = 0L
    private var activeJob: Job? = null
    private var activeInput: OnceClosedInput? = null
    private var closeInputJob: Job? = null
    private var closed = false

    init {
        // A parent cancellation also needs to close a provider read that does not observe
        // coroutine cancellation. This watcher retains only the runtime controller.
        parentScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { lifetime.join() }
            finally { if (!currentCoroutineContext().isActive) close() }
        }
    }

    /** Capture this before opening the picker; selection and runtime changes cannot retarget it. */
    fun capturePicker(destination: String): WorkspaceImportTicket {
        val path = ExplorerFilePolicy.requireUserTarget(files.root, destination)
        check(PathSecurity.resolveWithin(files.root, path).isDirectory) { "Import destination is no longer a project folder" }
        return synchronized(lock) {
            check(!closed && lifetime.isActive) { "Workspace is closed" }
            check(activeJob == null) { "A file import is already running" }
            WorkspaceImportTicket(owner, ++pickerGeneration, root, path, admissionClockNanos())
        }
    }

    /** Allows a retained Explorer to discard an obsolete picker without consuming a valid one. */
    fun isCurrentPicker(ticket: WorkspaceImportTicket): Boolean = synchronized(lock) {
        !closed && lifetime.isActive && ticket.owner == owner && ticket.generation == pickerGeneration &&
            ticket.workspaceRoot == root
    }

    fun submit(ticket: WorkspaceImportTicket, uri: Uri): WorkspaceImportAdmission = synchronized(lock) {
        if (closed || !lifetime.isActive) return@synchronized WorkspaceImportAdmission.CLOSED
        if (activeJob != null) return@synchronized WorkspaceImportAdmission.BUSY
        if (ticket.owner != owner || ticket.generation != pickerGeneration || ticket.workspaceRoot != root)
            return@synchronized WorkspaceImportAdmission.STALE
        ++pickerGeneration
        val id = ++operationSequence
        // Strings/Uri are immutable; no resolver cursor, view, callback or source stream is retained by the ticket.
        val selected = uri
        val destination = ticket.destination
        mutableState.value = mutableState.value.copy(active = WorkspaceImportProgress(
            id, "Selected file", destination, StreamWriteProgress(StreamWritePhase.PREPARING)))
        var completed: WorkspaceImportResult? = null
        var displayName = "Selected file"
        var relativePath: String? = null
        var committedBytes: Long? = null
        var lease: ProjectCopyJournal.Lease? = null
        fun committedResult(receipt: StreamPublicationReceipt, message: String) = WorkspaceImportResult(id,
            WorkspaceImportResultStatus.SYNC_PENDING, receipt.preparation.relativePath, displayName,
            receipt.preparation.bytes, message.take(MAX_MESSAGE_CHARS), lease?.intent?.id, true, receipt.preparation.sha256)
        fun recoveryResult(failure: Throwable) = WorkspaceImportResult(id, WorkspaceImportResultStatus.RECOVERY_REQUIRED,
            relativePath, displayName, null, ("An earlier import may have published. Open import recovery and inspect it. " +
                "Do not import again automatically. " + failure.message.orEmpty()).take(MAX_MESSAGE_CHARS),
            (failure as? ProjectCopyJournal.Blocked)?.transactionId ?: lease?.intent?.id)
        val operation = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val capturedDirectory = ExplorerFilePolicy.requireUserTarget(files.root, destination)
                check(capturedDirectory == destination && PathSecurity.resolveWithin(files.root, capturedDirectory).isDirectory) {
                    "Destination folder no longer exists; select a project folder again"
                }
                val metadata = source.metadata(selected)
                currentCoroutineContext().ensureActive()
                displayName = PathSecurity.safeLeafName(metadata.displayName ?: "imported_$id")
                val target = ExplorerFilePolicy.requireUserTarget(files.root,
                    if (destination.isBlank()) displayName else "$destination/$displayName")
                relativePath = target
                check(!files.exists(target)) { "A file named $displayName already exists here" }
                updateProgress(id, displayName, StreamWriteProgress(StreamWritePhase.PREPARING,
                    totalBytes = metadata.expectedBytes?.takeIf { it >= 0L }))
                lease = copyJournal.begin(ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.EXPLORER_IMPORT, null,
                    "$owner:$id", ProjectCopyJournal.explorerSourceId(selected.toString())), target, ticket.admittedAtNanos,
                    planStage = true)
                val admittedCopy = requireNotNull(lease)
                val input = OnceClosedInput(source.open(selected))
                try {
                    val admitted = synchronized(lock) {
                        if (closed || mutableState.value.active?.let { it.id != id || it.cancelling } != false) false
                        else { activeInput = input; true }
                    }
                    if (!admitted) throw CancellationException("Import stopped before its stream was admitted")
                    currentCoroutineContext().ensureActive()
                    try {
                        committedBytes = files.writeStream(target, input,
                            expectedBytes = metadata.expectedBytes, requireAbsent = true, requireExistingParent = true,
                            onProgress = { progress -> updateProgress(id, displayName, progress) },
                            publicationObserver = object : StreamPublicationObserver, StreamStagePlan {
                                override val plannedStagePath: String? get() = admittedCopy.plannedStagePath
                                override fun beforePublish(preparation: StreamPublicationPreparation) {
                                    requireCurrentOperation(id)
                                    admittedCopy.beforePublish(preparation)
                                }
                                override fun onPublished(receipt: StreamPublicationReceipt) = admittedCopy.onPublished(receipt)
                            },
                            beforePublication = {
                                requireCurrentOperation(id)
                                // A read-only late check narrows staging time, not an atomic editor reservation.
                                check(documentAuthority?.snapshot(target)?.dirty != true) {
                                    "An unsaved editor document appeared; choose another import name"
                                }
                            })
                        completed = WorkspaceImportResult(id, WorkspaceImportResultStatus.IMPORTED,
                            target, displayName, committedBytes, "Imported $displayName", admittedCopy.intent.id, true,
                            requireNotNull(admittedCopy.committedReceipt).preparation.sha256)
                    } catch (saved: ProjectFileSyncException) {
                        // FileRepository emits this only for its still-owned canonical publication.
                        // Require this operation's exact path and byte receipt, not a similar message.
                        val progress = synchronized(lock) { mutableState.value.active?.takeIf { it.id == id }?.progress }
                        val receipt = admittedCopy.committedReceipt
                        if (saved.relativePath != target || progress?.phase != StreamWritePhase.SYNCHRONIZING ||
                            saved.bytes != progress.completedBytes || receipt == null || receipt.preparation.relativePath != target ||
                            saved.bytes != receipt.preparation.bytes) throw saved
                        committedBytes = saved.bytes
                        completed = committedResult(receipt,
                            "Imported locally at $target; synchronization pending. Refresh; do not import again.")
                    }
                } finally {
                    // Detach and capture cleanup atomically: a later Cancel cannot create a
                    // close job after the worker has selected which cleanup it must join.
                    val closing = synchronized(lock) {
                        if (activeInput === input) activeInput = null
                        closeInputJob
                    }
                    try {
                        val closeFailure = withContext(NonCancellable) {
                            closing?.join()
                            input.finishClose()
                        }
                        if (closeFailure != null && completed != null) {
                            completed = completed!!.copy(message = (completed!!.message +
                                " Source handle cleanup failed (${closeFailure.javaClass.simpleName}); " +
                                "the file was saved. Do not import again.").take(MAX_MESSAGE_CHARS))
                        }
                    } finally {
                        synchronized(lock) { if (activeInput === input) activeInput = null }
                    }
                }
            } catch (cancelled: CancellationException) {
                // The exact receipt survives caller cancellation during non-cancellable publication/cleanup.
                lease?.committedReceipt?.let { receipt ->
                    committedBytes = receipt.preparation.bytes
                    if (completed?.localCommitRecorded != true) completed = committedResult(receipt,
                        "Imported locally at ${receipt.preparation.relativePath}; synchronization completion was not observed. Do not import again.")
                }
                if (lease?.isUncertain == true) completed = recoveryResult(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                val receipt = lease?.committedReceipt
                completed = when {
                    receipt != null -> committedResult(receipt,
                        "Imported locally at ${receipt.preparation.relativePath}; a later step failed (${failure.javaClass.simpleName}). Do not import again.")
                    lease?.isUncertain == true || failure is ProjectCopyJournal.Blocked || failure is StreamPublicationUncertainException -> recoveryResult(failure)
                    else -> WorkspaceImportResult(id, WorkspaceImportResultStatus.FAILED,
                        relativePath, displayName, null, (failure.message ?: "File import failed").take(MAX_MESSAGE_CHARS), lease?.intent?.id)
                }
                currentCoroutineContext().ensureActive()
            } finally {
                lease?.close()
            }
        }
        activeJob = operation
        // Busy is released only after stream/staging cleanup, including cancellation before dispatch.
        operation.invokeOnCompletion { failure -> synchronized(lock) {
            if (activeJob === operation) {
                activeJob = null
                closeInputJob = null
                val recorded = lease?.committedReceipt
                val result = when {
                    completed?.localCommitRecorded == true || completed?.status == WorkspaceImportResultStatus.RECOVERY_REQUIRED -> requireNotNull(completed)
                    recorded != null -> committedResult(recorded,
                        "Imported locally at ${recorded.preparation.relativePath}; a later step did not complete. Do not import again.")
                    lease?.isUncertain == true -> recoveryResult(failure ?: IllegalStateException("Publication outcome was not recorded"))
                    failure is CancellationException -> WorkspaceImportResult(id, WorkspaceImportResultStatus.CANCELLED,
                        relativePath, displayName, committedBytes,
                        "Import stopped. Any file already committed locally was preserved.")
                    failure != null -> WorkspaceImportResult(id, WorkspaceImportResultStatus.FAILED,
                        relativePath, displayName, null, "File import failed: ${failure.javaClass.simpleName}")
                    else -> requireNotNull(completed)
                }
                mutableState.value = WorkspaceImportState(result = result, closed = closed)
            }
        } }
        // async contains worker failures rather than reporting them as uncaught Android exceptions.
        operation.start()
        WorkspaceImportAdmission.ACCEPTED
    }

    private fun requireCurrentOperation(id: Long) = synchronized(lock) {
        check(!closed && lifetime.isActive && mutableState.value.active?.let { it.id == id && !it.cancelling } == true) {
            "Import operation is no longer current"
        }
    }

    // Explicitly opened recovery UI calls these; runtime construction performs no scan or action.
    internal suspend fun recovery(): ProjectCopyJournal.Recovery = withContext(Dispatchers.IO) {
        synchronized(lock) { check(!closed && lifetime.isActive) { "Workspace is closed" } }
        copyJournal.recovery(ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.EXPLORER_IMPORT))
    }
    internal suspend fun inspectRecovery(transactionId: String): ProjectCopyJournal.Inspection = withContext(Dispatchers.IO) {
        synchronized(lock) { check(!closed && lifetime.isActive && activeJob == null) { "Finish or stop the current import first" } }
        copyJournal.inspect(ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.EXPLORER_IMPORT), transactionId)
    }
    internal suspend fun acknowledgeRecovery(inspectionToken: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            check(!closed && lifetime.isActive && activeJob == null) { "Finish or stop the current import first" }
        }
        copyJournal.acknowledge(ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.EXPLORER_IMPORT), inspectionToken) { grant ->
            synchronized(lock) {
                check(!closed && lifetime.isActive && activeJob == null) { "Import recovery owner changed" }
                grant() // In-memory permission only; no disk read under the runtime monitor.
            }
        }
    }

    internal suspend fun inspectRetirementRecovery(): ProjectCopyJournal.RetirementInspection = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        synchronized(lock) { check(!closed && lifetime.isActive && activeJob == null) { "Finish or stop the current import first" } }
        copyJournal.inspectRetirement().also {
            currentCoroutineContext().ensureActive()
            synchronized(lock) { check(!closed && lifetime.isActive) { "Workspace is closed" } }
        }
    }

    internal suspend fun finishRetirementRecovery(token: String, requireView: () -> Unit = {}) = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        copyJournal.finishRetirement(token) {
            context.ensureActive()
            synchronized(lock) { check(!closed && lifetime.isActive && activeJob == null) { "Import recovery owner changed" } }
            requireView()
        }
    }

    private fun updateProgress(id: Long, displayName: String, progress: StreamWriteProgress) = synchronized(lock) {
        val active = mutableState.value.active ?: return@synchronized
        if (active.id == id) mutableState.value = mutableState.value.copy(active = active.copy(
            displayName = displayName, progress = progress))
    }

    fun cancel(): Boolean = synchronized(lock) { cancelLocked("Stopped by user") }

    private fun cancelLocked(reason: String): Boolean {
        val operation = activeJob ?: return false
        val active = mutableState.value.active ?: return false
        if (active.cancelling) return false
        mutableState.value = mutableState.value.copy(active = active.copy(cancelling = true))
        // Close on IO even when the parent has already been cancelled. The cancelled worker
        // joins this explicit cleanup job before releasing admission, so shutdown also waits
        // for it. Providers that ignore close are not forcibly interruptible/resumable.
        activeInput?.let { input -> closeInputJob = scope.async(NonCancellable + Dispatchers.IO) { input.close() } }
        operation.cancel(CancellationException(reason))
        return true
    }

    fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            ++pickerGeneration
            mutableState.value = mutableState.value.copy(closed = true)
            cancelLocked("Workspace closed")
            // Drain the cancelled worker and its stream-close child before completing ownership.
            lifetime.complete()
        }
    }

    suspend fun closeAndJoin() { close(); lifetime.join() }

    internal class OnceClosedInput(input: InputStream) : FilterInputStream(input) {
        private val closed = AtomicBoolean(false)
        private val closeCompleted = CompletableDeferred<Exception?>()

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                super.close()
                closeCompleted.complete(null)
            } catch (cancelled: CancellationException) {
                closeCompleted.completeExceptionally(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                // FileRepository closes its input inside writeStream. A close IOException
                // must not erase the canonical commit receipt before that method returns.
                closeCompleted.complete(failure)
            } catch (fatal: Throwable) {
                closeCompleted.completeExceptionally(fatal)
                throw fatal
            }
        }

        /** All closers join the actual provider close, even if another closer won the CAS. */
        suspend fun finishClose(): Exception? { close(); return closeCompleted.await() }
    }

    private class ResolverSource(context: Context) : WorkspaceImportSource {
        private val resolver = context.applicationContext.contentResolver
        override suspend fun metadata(uri: Uri): WorkspaceImportSourceMetadata {
            var name: String? = null
            var bytes: Long? = null
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) bytes = cursor.getLong(sizeIndex).takeIf { it >= 0L }
                }
            }
            return WorkspaceImportSourceMetadata(name, bytes)
        }
        override suspend fun open(uri: Uri): InputStream = resolver.openInputStream(uri) ?: error("Cannot read selected file")
    }

    companion object { const val MAX_MESSAGE_CHARS = 1_000 }
}
