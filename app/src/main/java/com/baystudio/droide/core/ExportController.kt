package com.baystudio.droide.core

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

/** Application-level runner. Source owner references exist only in the pending capture request. */
internal object ExportController {
    private data class Pending(
        val admission: ExportSourceAdmission, val ticket: ExportSourceTicket,
        val confirmed: Boolean,
    )
    private data class Destination(val uri: Uri, val flags: Int, val format: ExportFormat, val folderName: String)
    private val mutableState = MutableStateFlow<ExportJobState?>(null)
    val state: StateFlow<ExportJobState?> = mutableState
    private val mutableOpenRequest = MutableStateFlow(0L)
    val openRequest: StateFlow<Long> = mutableOpenRequest
    fun requestOpen(id: String, epoch: String) {
        val current = state.value ?: return
        if (current.id == id && current.epoch == epoch && ApplicationWorkAdmission.isCurrentOpen(epoch))
            mutableOpenRequest.value += 1
    }
    private var journal: ExportJournal? = null
    private var pointerAuthority: ExportPointerRecovery? = null
    private val initializationLock = Any()
    private val publicationLock = Any()
    private var pending: Pending? = null
    private var destination: Destination? = null
    private var worker: Job? = null
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var workerCompletion: CompletableDeferred<Unit>? = null
    @Volatile private var cancellation = false
    @Volatile private var interrupted = false
    private var lastProgress = 0L

    fun initialize(context: Context, checkCancelled: () -> Unit = {}) = synchronized(initializationLock) {
        if (journal != null) return
        check(android.app.Application.getProcessName() == context.applicationContext.applicationInfo.processName) {
            "Exports can only be owned by the main application process"
        }
        synchronized(this) {
            check(pending == null && destination == null && worker == null && workerCompletion == null) {
                "Export work still owns recovery state"
            }
        }
        checkCancelled()
        val base = File(context.applicationContext.filesDir, "exports-v1")
        check(base.mkdirs() || base.isDirectory) { "Export journal storage is unavailable" }
        val owner = ExportPointerRecovery.open(base)
        try {
            owner.recover(checkCancelled)
            val store = ExportJournal(base) { owner.requireHeld() }
            val previous = store.read()
            val restored = previous?.let {
                val recovered = if (it.terminal) it else it.copy(phase = ExportJobPhase.INTERRUPTED,
                    detail = "Export was interrupted. Existing output may be partial. Discard retained local staging before starting a new export; no automatic resume.")
                recovered.copy(retainedCapture = store.hasCapture(it.id))
            }
            checkCancelled()
            if (restored != previous && restored != null) store.write(restored)
            checkCancelled()
            pointerAuthority = owner
            journal = store
            mutableState.value = restored
        } catch (failure: Throwable) {
            owner.close()
            throw failure
        }
    }

    suspend fun start(context: Context, admission: ExportSourceAdmission, ticket: ExportSourceTicket,
        format: ExportFormat, destination: Uri, actualGrantFlags: Int, externalQuiescenceConfirmed: Boolean,
        outputName: String? = null,
        beforeOwnerPublication: () -> Unit = {}, // Scheduling/fault seam; never replaces admission or storage.
    ): String = withContext(Dispatchers.IO) {
        ExportRolloutPolicy.requireNewExportEnabled()
        val caller = currentCoroutineContext()
        initialize(context) { caller.ensureActive() }
        caller.ensureActive()
        admission.validate(ticket)
        ExportSafDestination.requireAccess(destination, actualGrantFlags)
        requireSeparateDestination(context, ticket, destination, format)
        check(ticket.concurrentFileCapture || externalQuiescenceConfirmed) { "Confirm that external writers are quiet before exporting" }
        val id = UUID.randomUUID().toString()
        val folderName = outputName ?: "droide-export-${id.take(8)}"
        require(ExportFileNames.valid(folderName)) { "Choose a valid export name" }
        val initial = ExportJobState(id, ticket.epoch, ExportJobPhase.QUEUED, detail = "Preparing export",
            createdCount = if (format == ExportFormat.TREE) 0 else 1,
            sourceLabel = "${ticket.projectId}: ${ticket.scope.relativePath.ifEmpty { "entire workspace" }}",
            providerAuthority = destination.authority.orEmpty())
        synchronized(publicationLock) {
            check(mutableState.value?.terminal != false && worker?.isCompleted != false) { "An export is already active" }
            check(workerCompletion?.isCompleted != false) { "Wait for the previous export to finish stopping" }
            check(mutableState.value?.retainedCapture != true) { "Discard the previous export’s retained local staging before starting a new export" }
            check(ApplicationWorkAdmission.isCurrentOpen(ticket.epoch)) { "Application session has ended" }
            val store = checkNotNull(journal)
            checkNotNull(pointerAuthority).requireHeld()
            ExportStorageBudget.requireAvailable(File(context.applicationContext.filesDir, "exports-v1"))
            val requestRecord = java.util.Properties().apply {
                setProperty("projectId", ticket.projectId); setProperty("scope", ticket.scope.relativePath)
                setProperty("rootIdentity", ticket.rootIdentity); setProperty("format", format.name)
                setProperty("destination", destination.toString()); setProperty("grantFlags", actualGrantFlags.toString())
                setProperty("outputName", folderName)
                setProperty("externalQuiescenceConfirmed", externalQuiescenceConfirmed.toString())
            }
            java.io.FileOutputStream(File(store.jobDirectory(id), "request.properties")).use { requestRecord.store(it, "Export identity and destination"); it.fd.sync() }
            if (format != ExportFormat.TREE) store.recordCreated(id, destination.toString())
            store.write(initial)
            beforeOwnerPublication()
            // Atomic ownership handoff with cancel/interrupt. No disk I/O under this monitor.
            val published = synchronized(this@ExportController) {
                try {
                    ApplicationWorkAdmission.withOpen(ticket.epoch) {
                        cancellation = false; interrupted = false; worker = null; workerCompletion = null
                        pending = Pending(admission, ticket, externalQuiescenceConfirmed)
                        this@ExportController.destination = Destination(destination, actualGrantFlags, format, folderName)
                        mutableState.value = initial
                        true
                    }
                } catch (_: IllegalStateException) { false }
            }
            if (!published) {
                store.write(initial.copy(phase = ExportJobPhase.INTERRUPTED,
                    detail = "Application session ended before export could start. Picker output may be empty; no automatic resume."))
                throw CancellationException("Application session has ended")
            }
        }
        try { ExportForegroundService.start(context, id, ticket.epoch) }
        catch (failure: Exception) {
            synchronized(this@ExportController) {
                if (mutableState.value?.id == id) { pending = null; this@ExportController.destination = null }
            }
            publish(initial.copy(phase = ExportJobPhase.FAILED, detail = "ServiceStartDenied: return to Droide and start a new export. The picker may have left an empty output."))
            throw failure
        }
        id
    }


    private fun requireSeparateDestination(context: Context, ticket: ExportSourceTicket, target: Uri, format: ExportFormat) {
        val linked = ticket.sourceTreeUri?.let(Uri::parse) ?: return
        val source = SafProjectIdentity.from(linked)
        if (target.authority != source.authority) return
        val sourceDocument = android.provider.DocumentsContract.buildDocumentUriUsingTree(linked, source.documentId)
        val targetDocument = if (format == ExportFormat.TREE)
            android.provider.DocumentsContract.buildDocumentUriUsingTree(target, android.provider.DocumentsContract.getTreeDocumentId(target)) else target
        val targetId = android.provider.DocumentsContract.getDocumentId(targetDocument)
        check(targetId != source.documentId) { "Choose a location outside the linked source folder" }
        val inside = try {
            android.provider.DocumentsContract.isChildDocument(context.contentResolver, sourceDocument, targetDocument)
        } catch (_: Exception) {
            error("Cannot establish that this location is outside the linked source. Choose a separate provider/location.")
        }
        check(!inside) { "Choose a location outside the linked source folder" }
    }

    private fun publish(value: ExportJobState): Unit = synchronized(publicationLock) {
        val old = mutableState.value
        if (old?.id != value.id || old.terminal) return
        if (value.phase == ExportJobPhase.COMPLETED_VERIFIED) {
            checkActive(value.id, value.epoch)
            checkNotNull(journal).write(value)
            try {
                synchronized(this) {
                    ApplicationWorkAdmission.withOpen(value.epoch) {
                        if (cancellation || mutableState.value?.id != value.id || mutableState.value?.terminal == true)
                            throw CancellationException("Export cancelled before completion publication")
                        mutableState.value = value
                    }
                }
            } catch (failure: Exception) {
                val stopped = synchronized(this) {
                    val current = checkNotNull(mutableState.value)
                    if (current.id == value.id && current.terminal) current else value.copy(
                        phase = if (interrupted || !ApplicationWorkAdmission.isCurrentOpen(value.epoch)) ExportJobPhase.INTERRUPTED else ExportJobPhase.CANCELLED,
                        detail = "Export stopped before completion was published.").also { mutableState.value = it }
                }
                checkNotNull(journal).write(stopped)
                throw failure
            }
        } else {
            checkNotNull(journal).write(value)
            val newer = synchronized(this) {
                val current = mutableState.value
                if (current?.id != value.id || current.terminal || (cancellation && !value.terminal)) current
                else { mutableState.value = value; null }
            }
            // Cancel/timeout can arrive while fsync is running. Keep its newer terminal state.
            newer?.let { checkNotNull(journal).write(it) }
        }
    }
    private fun checkActive(id: String, epoch: String) {
        if (cancellation || mutableState.value?.id != id || !ApplicationWorkAdmission.isCurrentOpen(epoch))
            throw CancellationException("Export cancelled or application session ended")
    }
    private suspend fun captureSource(id: String, control: ExportControl): ExportCapturedSource {
        val request = synchronized(this) { checkNotNull(pending).also { pending = null } }
        return request.admission.withCapture(request.ticket, request.confirmed) { permit ->
            ExportCaptureStore(File(checkNotNull(journal).jobDirectory(id), "capture")).capture(permit, control)
        }
    }
    @Synchronized fun launch(scope: CoroutineScope, context: Context, id: String, epoch: String, workerDispatcher: CoroutineDispatcher = Dispatchers.IO, finished: () -> Unit): Boolean {
        if (mutableState.value?.id != id || mutableState.value?.epoch != epoch || mutableState.value?.terminal != false ||
            !ApplicationWorkAdmission.isCurrentOpen(epoch) || pending == null || cancellation || worker != null ||
            workerCompletion?.isCompleted == false) return false
        val completion = CompletableDeferred<Unit>()
        workerCompletion = completion
        worker = ExportWorkerLifecycle.start(scope, workerDispatcher, completed = {
            persistenceScope.launch {
              try {
               synchronized(publicationLock) {
                if (mutableState.value?.id == id) {
                    pending = null; destination = null
                    val current = checkNotNull(mutableState.value)
                    val retained = runCatching { checkNotNull(journal).hasCapture(id) }.getOrDefault(true)
                    val terminal = if (current.terminal) current.copy(retainedCapture = retained) else current.copy(
                        phase = if (interrupted || !ApplicationWorkAdmission.isCurrentOpen(epoch)) ExportJobPhase.INTERRUPTED else ExportJobPhase.CANCELLED,
                        detail = "Export stopped. Any created output remains for review.", retainedCapture = retained)
                    runCatching { checkNotNull(journal).write(terminal) }.onFailure {
                        mutableState.value = terminal.copy(detail = "Export stopped; recovery journal could not be updated. Review output and retained staging.")
                    }.onSuccess { mutableState.value = terminal }
                }
            }
               finished()
              } finally { completion.complete(Unit) }
            }
        }) {
            try {
                val control = ExportControl(checkCancelled = { checkActive(id, epoch); ensureActive() }, onProgress = { progress ->
                    val now = System.nanoTime()
                    val phase = when (progress.phase) {
                        ExportPhase.CAPTURING -> ExportJobPhase.CAPTURING
                        ExportPhase.WRITING -> ExportJobPhase.WRITING
                        ExportPhase.VERIFYING -> ExportJobPhase.VERIFYING
                        ExportPhase.COMPLETED_VERIFIED -> ExportJobPhase.VERIFYING // final publication only after engine returns
                    }
                    val current = checkNotNull(mutableState.value)
                    if (current.phase != phase || now - lastProgress >= 250_000_000L) {
                        checkActive(id, epoch); lastProgress = now
                        publish(current.copy(phase = phase, entries = progress.entries, bytes = progress.bytes, detail = phase.name))
                    }
                })
                checkActive(id, epoch)
                publish(checkNotNull(mutableState.value).copy(phase = ExportJobPhase.CAPTURING, detail = "Capturing saved source"))
                val sealed = captureSource(id, control)
                // No runtime, authority, repository or admission owner is retained beyond this boundary.
                val target = synchronized(this@ExportController) { checkNotNull(destination).also { destination = null } }
                val writer = ExportSafDestination(context.applicationContext.contentResolver, target.uri, target.flags, target.format,
                    checkNotNull(journal).jobDirectory(id), target.folderName, { uri ->
                        checkNotNull(journal).recordCreated(id, uri)
                        val current = checkNotNull(mutableState.value)
                        publish(current.copy(createdCount = current.createdCount + 1))
                    }, { checkActive(id, epoch); ensureActive() })
                ExportEngine().transfer(sealed, target.format, writer, control)
                checkActive(id, epoch)
                val removed = runCatching { checkNotNull(journal).discardCapture(id) }.isSuccess
                checkActive(id, epoch)
                publish(checkNotNull(mutableState.value).copy(phase = ExportJobPhase.COMPLETED_VERIFIED,
                    entries = sealed.entries, bytes = sealed.bytes, retainedCapture = !removed,
                    detail = "Completed, bytes verified. " + sealed.stabilityAssumptions.joinToString(" ") + " POSIX metadata limits apply." +
                        if (removed) "" else " Local staging cleanup failed; discard retained staging before a new export."))
            } catch (failure: Throwable) {
                val current = mutableState.value
                if (current?.id == id && !current.terminal) {
                    val phase = when {
                        interrupted || !ApplicationWorkAdmission.isCurrentOpen(epoch) -> ExportJobPhase.INTERRUPTED
                        failure is CancellationException || cancellation -> ExportJobPhase.CANCELLED
                        failure is ExportFailure && failure.category == "HashMismatch" -> ExportJobPhase.PARTIAL
                        current.phase == ExportJobPhase.VERIFYING -> ExportJobPhase.UNVERIFIED
                        current.createdCount > 0 -> ExportJobPhase.PARTIAL
                        else -> ExportJobPhase.FAILED
                    }
                    val reason = if (failure is IllegalStateException && current.phase == ExportJobPhase.CAPTURING)
                        failure.message ?: "Source capture admission failed"
                        else if (phase == ExportJobPhase.CANCELLED) "Cancelled. Any created output is retained for review."
                        else if (phase == ExportJobPhase.INTERRUPTED) "Interrupted. Output may be partial; start a new export manually."
                        else "${if (failure is ExportFailure) failure.category else failure.javaClass.simpleName}: export did not complete. Output, if any, is retained for review."
                    runCatching { publish(current.copy(phase = phase, detail = reason)) }.onFailure {
                        mutableState.value = current.copy(phase = ExportJobPhase.INTERRUPTED, detail = "Export stopped; journal persistence failed. Review partial output.")
                    }
                }
                // Retain sealed/partial capture for explicit review; never replay automatically.
            }
        }
        return true
    }
    private fun persistAsync(value: ExportJobState) {
        persistenceScope.launch {
            synchronized(publicationLock) {
                // Never overwrite a newer phase or a replacement job with an old queued update.
                if (mutableState.value == value) runCatching { checkNotNull(journal).write(value) }.onFailure {
                    mutableState.value = value.copy(detail = "Export stopped; journal persistence failed. Review output and local staging.")
                }
            }
        }
    }
    fun cancel(jobId: String) {
        val (stopped, running) = synchronized(this) {
            val current = mutableState.value ?: return
            if (current.id != jobId || current.terminal) return
            cancellation = true
            val stopped = if (worker == null) current.copy(phase = ExportJobPhase.CANCELLED,
                detail = "Cancelled before capture. Picker output may be empty.") else current.copy(
                phase = ExportJobPhase.CANCELLING, detail = "Cancelling; waiting for any active provider operation")
            mutableState.value = stopped
            if (worker == null) { pending = null; destination = null }
            stopped to worker
        }
        persistAsync(stopped)
        running?.cancel()
    }
    fun interrupt(jobId: String) {
        val (stopped, running) = synchronized(this) {
            val current = mutableState.value ?: return
            if (current.id != jobId || current.terminal) return
            interrupted = true; cancellation = true
            val stopped = current.copy(phase = ExportJobPhase.INTERRUPTED,
                detail = "Service interrupted or timed out. Output may be partial; no automatic resume.")
            mutableState.value = stopped
            pending = null; destination = null
            stopped to worker
        }
        persistAsync(stopped)
        running?.cancel()
        // Timeout callers stopSelf immediately. Persistence and provider drainage stay off main.
    }
    suspend fun historySnapshot(context: Context): ExportHistorySnapshot = withContext(Dispatchers.IO) {
        // Viewing history may create the private journal directory, but never recovers/publishes a job.
        val caller = currentCoroutineContext()
        ExportJournal(File(context.applicationContext.filesDir, "exports-v1")).historySnapshot { caller.ensureActive() }
    }
    suspend fun history(context: Context): List<ExportJobState> = historySnapshot(context).jobs
    suspend fun destinationForReview(context: Context, jobId: String): Uri? = withContext(Dispatchers.IO) {
        initialize(context)
        val file = File(checkNotNull(journal).jobDirectory(jobId), "created-uris")
        if (!file.isFile) null else java.io.DataInputStream(file.inputStream()).use { Uri.parse(it.readUTF()) }
    }

    suspend fun discardRetainedCapture(context: Context, jobId: String) = withContext(Dispatchers.IO) {
        initialize(context)
        synchronized(publicationLock) {
            val current = checkNotNull(mutableState.value)
            check(current.id == jobId && current.terminal && worker?.isCompleted != false && workerCompletion?.isCompleted != false) { "Wait for export work to stop before discarding local staging" }
            val store = checkNotNull(journal)
            store.discardCapture(jobId)
            val updated = current.copy(retainedCapture = false, detail = current.detail + " Local export staging discarded; destination output and ownership records were preserved.")
            store.write(updated)
            mutableState.value = updated
        }
    }

    suspend fun shutdownForExit(context: Context) = withContext(Dispatchers.IO) {
        initialize(context)
        state.value?.takeUnless { it.terminal }?.let { interrupt(it.id) }
        worker?.join()
        workerCompletion?.await()
        // A queued job may have no worker: its terminal transition still must reach the journal.
        synchronized(publicationLock) { state.value?.let { checkNotNull(journal).write(it) } }
        context.stopService(android.content.Intent(context, ExportForegroundService::class.java))
    }
}
