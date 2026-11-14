package com.baystudio.droide.ui

import kotlin.jvm.JvmName

import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.baystudio.droide.core.EditorRecoveryBuffer
import com.baystudio.droide.core.WorkspaceSearchCaptureRequest
import com.baystudio.droide.core.WorkspaceSearchCapture
import com.baystudio.droide.core.WorkspaceDocumentSnapshot
import com.baystudio.droide.core.WorkspaceDocumentKind
import com.baystudio.droide.core.EditorRecoverySnapshot
import com.baystudio.droide.core.EditorSelectionSnapshot
import com.baystudio.droide.core.EditorFilePerformanceMode
import com.baystudio.droide.core.EditorLargeFilePolicy
import com.baystudio.droide.core.EditorReviewModePolicy
import com.baystudio.droide.core.FileIconRegistry
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.DiskFileVersion
import com.baystudio.droide.core.LargeFileException
import com.baystudio.droide.core.ProjectFileSyncException
import com.baystudio.droide.core.runSuspendCatching


@Stable
enum class EditorDocumentKind { TEXT, IMAGE, BINARY, LARGE, DOCUMENT }

/** A committed snapshot is distinct from the live buffer, which may already contain newer edits. */
data class EditorSaveReceipt(
    val path: String,
    val text: String,
    val didWrite: Boolean,
    val notifySaved: Boolean,
    val synchronizationFailure: ProjectFileSyncException? = null,
)

internal fun sameRecoveryBuffer(first: EditorRecoveryBuffer?, second: EditorRecoveryBuffer?): Boolean =
    first != null && second != null && (first === second ||
        (first.owner === second.owner && first.path == second.path && first.blobName == second.blobName &&
            first.contentSha256 == second.contentSha256 && first.diskVersion == second.diskVersion))

@Stable
class EditorDocument internal constructor(initialPath: String) {
    /** Per-instance owner identity. It survives snapshots/saves; a replacement document always gets a new one. */
    val documentId: String = java.util.UUID.randomUUID().toString()
    private var handoffOwner: Any? = null
    internal fun sealForHandoff(owner: Any) {
        check(handoffOwner == null || handoffOwner === owner) { "Editor document already belongs to a handoff" }
        handoffOwner = owner
    }
    internal fun abortHandoff(owner: Any) { if (handoffOwner === owner) handoffOwner = null }
    private fun requireMutableOwner() { check(handoffOwner == null) { "Editor document belongs to a retired workspace" } }

    var path by mutableStateOf(initialPath)
        private set
    var content by mutableStateOf("")
        private set
    var savedContent by mutableStateOf("")
        private set
    var loaded by mutableStateOf(false)
        private set
    var kind by mutableStateOf(EditorDocumentKind.TEXT)
        private set
    
    var loadError by mutableStateOf<String?>(null)
        private set
    var pendingRecoveryBuffer by mutableStateOf<EditorRecoveryBuffer?>(null)
        private set
    private var loadGeneration = 0L
    private var saveInProgress = false
    private var saveCompletion: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    internal val isSaveInProgress: Boolean get() = saveInProgress
    internal suspend fun awaitPendingSaveForExit() { saveCompletion?.await() }
    // Immutable identity from the accepted load/write, never today's disk adopted during recovery.
    internal var savedDiskVersion: DiskFileVersion? = null
        private set
    private val savedFileExisted: Boolean? get() = savedDiskVersion?.existed
    // Today's loaded text is available for explicit discard, but is not recovery's save identity.
    private var observedDiskVersion: DiskFileVersion? = null
    private var recoveryDirty by mutableStateOf(false)
    var status by mutableStateOf("Loading…")
    internal val dismissedAnalysisMessages = mutableStateMapOf<String, String>()
    var revision by mutableIntStateOf(0)
        private set
    var changeVersion by mutableIntStateOf(0)
        private set
    var fileBytes by mutableLongStateOf(0L)
        private set
    var lineCountHint by mutableIntStateOf(1)
        private set
    var performanceMode by mutableStateOf(EditorFilePerformanceMode.FULL_INTELLIGENCE)
        private set
    private var optimizedBufferDirty by mutableStateOf(false)
    // Large-file deltas update metrics without copying the native editor's complete text.
    private var nativeBufferPending = false
    internal val requiresBufferCapture: Boolean get() = nativeBufferPending
    internal var nativeMutationBinding: EditorNativeMutationBinding? = null
    internal val persistenceRequired: Boolean get() = dirty ||
        (editable && savedFileExisted == false)
    internal val needsSave: Boolean get() = persistenceRequired || saveSynchronizationPending || saveNotificationPending
    /** Observed path-mutation guard, not a reservation of native input or future saves. */
    internal val blocksPersistedPathMutation: Boolean get() = dirty || isSaveInProgress ||
        requiresBufferCapture || pendingRecoveryBuffer != null || saveSynchronizationPending || saveNotificationPending
    var selectionStart: Int = 0
        private set
    var selectionEnd: Int = 0
        private set
    internal var viewport: EditorViewportSnapshot? = null
        private set
    internal fun captureViewport(snapshot: EditorViewportSnapshot) { viewport = snapshot }

    internal var viewStateRevision: Long = 0L
        private set
    @set:JvmName("setReviewModeState")
    var reviewMode by mutableStateOf(false)
        private set

    val dirty: Boolean get() = pendingRecoveryBuffer != null || (kind == EditorDocumentKind.TEXT && loaded && (recoveryDirty || optimizedBufferDirty || content != savedContent))
     
    val editable: Boolean get() = pendingRecoveryBuffer == null && kind == EditorDocumentKind.TEXT && loaded
    val canEdit: Boolean get() = handoffOwner == null && EditorReviewModePolicy.canEditText(kind == EditorDocumentKind.TEXT, loaded && pendingRecoveryBuffer == null, reviewMode)
    val fullIntelligence: Boolean get() = editable && performanceMode == EditorFilePerformanceMode.FULL_INTELLIGENCE
    val largeFileOptimized: Boolean get() = editable && performanceMode == EditorFilePerformanceMode.LARGE_FILE_OPTIMIZED
     
    val agentEditable: Boolean get() = EditorReviewModePolicy.canAgentEdit(canEdit, fullIntelligence)

    /** Search-only projection. No loading, native capture, dirty-cache mutation, or full-text copy. */
    internal fun captureForSearch(request: WorkspaceSearchCaptureRequest): WorkspaceDocumentSnapshot? {
        val capturedPath = path
        val capturedContent = content
        val capturedSaved = savedContent
        val capturedLoaded = loaded
        val capturedKind = kind
        val capturedPending = pendingRecoveryBuffer
        val capturedOptimizedDirty = optimizedBufferDirty
        val capturedRecoveryDirty = recoveryDirty
        val capturedPerformance = performanceMode
        val capturedReview = reviewMode
        val capturedMutableOwner = handoffOwner == null
        val capturedRevision = revision
        val capturedVersion = changeVersion
        val capturedStart = selectionStart
        val capturedEnd = selectionEnd
        val capturedDirty = capturedPending != null ||
            (capturedKind == EditorDocumentKind.TEXT && capturedLoaded &&
                (capturedRecoveryDirty || capturedOptimizedDirty || !request.sameContent(capturedContent, capturedSaved)))
        if (!capturedDirty) return null
        request.beforeSnapshot(capturedPath, capturedContent)
        val editable = capturedPending == null && capturedKind == EditorDocumentKind.TEXT && capturedLoaded
        val fullIntelligence = editable && capturedPerformance == EditorFilePerformanceMode.FULL_INTELLIGENCE
        val optimized = editable && capturedPerformance == EditorFilePerformanceMode.LARGE_FILE_OPTIMIZED
        val canEdit = capturedMutableOwner && EditorReviewModePolicy.canEditText(
            capturedKind == EditorDocumentKind.TEXT, capturedLoaded && capturedPending == null, capturedReview,
        )
        return WorkspaceDocumentSnapshot(
            path = capturedPath,
            content = capturedContent,
            loaded = capturedLoaded,
            kind = when (capturedKind) {
                EditorDocumentKind.TEXT -> if (optimized) WorkspaceDocumentKind.LARGE else WorkspaceDocumentKind.TEXT
                EditorDocumentKind.IMAGE -> WorkspaceDocumentKind.IMAGE
                EditorDocumentKind.BINARY -> WorkspaceDocumentKind.BINARY
                EditorDocumentKind.DOCUMENT -> WorkspaceDocumentKind.BINARY
                EditorDocumentKind.LARGE -> WorkspaceDocumentKind.LARGE
            },
            dirty = capturedDirty,
            editable = EditorReviewModePolicy.canAgentEdit(canEdit, fullIntelligence),
            revision = capturedRevision,
            changeVersion = capturedVersion,
            selectionStart = if (capturedLoaded && capturedPending == null) capturedStart else 0,
            selectionEnd = if (capturedLoaded && capturedPending == null) capturedEnd else 0,
            savedContentLength = capturedSaved.length,
            documentId = documentId,
        )
    }

    fun setReviewMode(enabled: Boolean) {
        if (handoffOwner != null) return
        if ((!editable && pendingRecoveryBuffer == null) || reviewMode == enabled) return
        reviewMode = enabled
        viewStateRevision++
        status = if (enabled) "Review Mode · read-only" else if (dirty) "Review Mode off · unsaved changes" else "Review Mode off"
    }

    private fun profileCapturedText(text: String) {
        fileBytes = EditorLargeFilePolicy.utf8Bytes(text)
        lineCountHint = EditorLargeFilePolicy.lineCount(text, EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_LINES + 1)
        val classified = EditorLargeFilePolicy.classify(fileBytes, lineCountHint).mode


        performanceMode = if (kind == EditorDocumentKind.TEXT && classified == EditorFilePerformanceMode.PREVIEW_ONLY) {
            EditorFilePerformanceMode.LARGE_FILE_OPTIMIZED
        } else classified
    }

    fun capture(text: String) {
        if (handoffOwner != null) return
        if (pendingRecoveryBuffer != null || kind != EditorDocumentKind.TEXT || !loaded) return
        if (reviewMode && content != text) return
        captureNativeText(text)
    }

    /** Observation is not permission to edit. Never discard already-delivered native bytes. */
    internal fun captureNativeText(text: String) {
        check(pendingRecoveryBuffer == null && kind == EditorDocumentKind.TEXT && loaded) {
            "Native editor capture does not match an initialized text document: $path"
        }
        if (content != text) {
            content = text
            changeVersion++
        }
        profileCapturedText(text)
        optimizedBufferDirty = text != savedContent
        nativeBufferPending = false
        captureSelection(selectionStart, selectionEnd)
    }


    fun captureInsert(start: Int, inserted: String): Boolean {
        if (!canEdit) return false
        if (inserted.isEmpty()) return true
        if (largeFileOptimized) {
            val wasOverCeiling = fileBytes > EditorLargeFilePolicy.MAX_EDITABLE_BYTES
            fileBytes += EditorLargeFilePolicy.utf8Bytes(inserted)
            lineCountHint = (lineCountHint + inserted.count { it == '\n' }).coerceAtLeast(1)
            markOptimizedBufferChanged()
            if (!wasOverCeiling && fileBytes > EditorLargeFilePolicy.MAX_EDITABLE_BYTES) {
                status = "Buffer exceeds 20 MiB · keep editing to reduce it before Save"
            }
            return true
        }
        if (start !in 0..content.length) return false
        content = buildString(content.length + inserted.length) {
            append(this@EditorDocument.content, 0, start)
            append(inserted)
            append(this@EditorDocument.content, start, this@EditorDocument.content.length)
        }
        fileBytes += EditorLargeFilePolicy.utf8Bytes(inserted)
        lineCountHint = (lineCountHint + inserted.count { it == '\n' }).coerceAtLeast(1)
        changeVersion++
        if (fileBytes > EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_BYTES ||
            lineCountHint > EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_LINES
        ) {
            performanceMode = EditorFilePerformanceMode.LARGE_FILE_OPTIMIZED
            optimizedBufferDirty = true
            status = "Large File Performance Mode · editing optimized"
        }
        captureSelection(selectionStart, selectionEnd)
        return true
    }

    fun captureDelete(start: Int, deleted: String): Boolean {
        if (!canEdit) return false
        if (deleted.isEmpty()) return true
        if (largeFileOptimized) {
            val wasOverCeiling = fileBytes > EditorLargeFilePolicy.MAX_EDITABLE_BYTES
            fileBytes = (fileBytes - EditorLargeFilePolicy.utf8Bytes(deleted)).coerceAtLeast(0L)
            lineCountHint = (lineCountHint - deleted.count { it == '\n' }).coerceAtLeast(1)
            markOptimizedBufferChanged()
            if (wasOverCeiling && fileBytes <= EditorLargeFilePolicy.MAX_EDITABLE_BYTES) {
                status = "Large File Performance Mode · editing optimized"
            }
            return true
        }
        val end = start + deleted.length
        if (start < 0 || end > content.length) return false
        if (!content.regionMatches(start, deleted, 0, deleted.length)) return false
        content = buildString(content.length - deleted.length) {
            append(this@EditorDocument.content, 0, start)
            append(this@EditorDocument.content, end, this@EditorDocument.content.length)
        }
        fileBytes = (fileBytes - EditorLargeFilePolicy.utf8Bytes(deleted)).coerceAtLeast(0L)
        lineCountHint = (lineCountHint - deleted.count { it == '\n' }).coerceAtLeast(1)
        changeVersion++
        captureSelection(selectionStart, selectionEnd)
        return true
    }

    private fun markOptimizedBufferChanged() {
        optimizedBufferDirty = true
        nativeBufferPending = true
        changeVersion++
    }

    fun captureSelection(start: Int, end: Int = start) = updateSelection(start, end, recordInteraction = true)

    private fun updateSelection(start: Int, end: Int, recordInteraction: Boolean) {
        val upper = if (!loaded || pendingRecoveryBuffer != null) Int.MAX_VALUE
            else if (largeFileOptimized) maxOf(content.length, start, end).coerceAtLeast(0) else content.length
        val nextStart = start.coerceIn(0, upper)
        val nextEnd = end.coerceIn(0, upper)
        if (recordInteraction && (selectionStart != nextStart || selectionEnd != nextEnd)) viewStateRevision++
        selectionStart = nextStart
        selectionEnd = nextEnd
    }

    /** Restore view metadata without eagerly reading every clean tab's contents. */
    internal fun restoreViewState(selection: EditorSelectionSnapshot?, review: Boolean) {
        selection?.let { captureSelection(it.start, it.end) }
        if (reviewMode != review) {
            reviewMode = review
            viewStateRevision++
        }
    }

    fun restoreDeferred(reference: EditorRecoveryBuffer, selection: EditorSelectionSnapshot? = null, review: Boolean = false) {
        requireMutableOwner()
        check(!dirty && reference.path == path) { "Cannot replace existing unsaved document" }
        loadGeneration++
        pendingRecoveryBuffer = reference
        savedDiskVersion = reference.diskVersion
        observedDiskVersion = null
        recoveryDirty = true
        nativeBufferPending = false
        optimizedBufferDirty = false
        content = ""
        savedContent = ""
        loaded = false
        kind = EditorDocumentKind.TEXT
        loadError = null
        reviewMode = review
        selectionStart = selection?.start ?: 0
        selectionEnd = selection?.end ?: selectionStart
        status = "Recovered unsaved changes · open to load"
        revision++
        changeVersion++
    }

    suspend fun ensureLoaded(files: FileRepository) {
        if (handoffOwner != null) return
        val reference = pendingRecoveryBuffer
        if (reference == null) {
            if (!loaded) reload(files)
            return
        }
        val generation = loadGeneration
        try {
            val recovered = reference.readText()
            if (handoffOwner != null || pendingRecoveryBuffer !== reference || loadGeneration != generation) return
            val selection = EditorSelectionSnapshot(selectionStart, selectionEnd)
            val review = reviewMode
            loadDisk(files, restoringRecovery = true)
            if (handoffOwner != null || pendingRecoveryBuffer !== reference) return
            pendingRecoveryBuffer = null
            restoreUnsaved(recovered, reference.diskVersion)
            setReviewMode(review)
            captureSelection(selection.start, selection.end)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (pendingRecoveryBuffer === reference) {
                loadError = "Cannot recover $path: ${failure.message ?: "unknown error"}"
                status = loadError.orEmpty()
            }
        }
    }

    suspend fun reload(files: FileRepository, discardUnsaved: Boolean = false) =
        loadDisk(files, discardUnsaved)

    /** Authority refresh may suspend; its captured owner/pending state must still admit publication. */
    internal suspend fun reloadForPersistedChange(files: FileRepository, canPublish: () -> Boolean): Boolean {
        var published = false
        loadDisk(files, canPublish = canPublish, onPublished = { published = true })
        return published
    }

    private suspend fun loadDisk(
        files: FileRepository, discardUnsaved: Boolean = false, restoringRecovery: Boolean = false,
        canPublish: () -> Boolean = { true }, onPublished: () -> Unit = {},
    ) {
        if (handoffOwner != null) return
        if (dirty && !discardUnsaved && !restoringRecovery) {
            status = "Reload skipped · unsaved edits preserved. Use Discard & reload to replace them."
            return
        }
        if (saveInProgress) {
            status = "Save in progress · reload after it finishes"
            return
        }
        val request = ++loadGeneration
        val path = this.path
        val startRevision = revision
        val startVersion = changeVersion
        fun stillCurrent() = handoffOwner == null && request == loadGeneration && this.path == path &&
            revision == startRevision && changeVersion == startVersion && canPublish()
        // Publish a complete read atomically on Main; never clear the buffer before I/O.
        try {
            var nextKind = EditorDocumentKind.TEXT
            var nextMode = EditorFilePerformanceMode.FULL_INTELLIGENCE
            var nextStatus = ""
            var nextBytes = 0L
            var nextLines = 1
            var nextExisted = false
            val text = if (com.baystudio.droide.core.DocumentPreviewPolicy.kind(path) != null) {
                nextKind = EditorDocumentKind.DOCUMENT
                nextMode = EditorFilePerformanceMode.PREVIEW_ONLY
                nextStatus = "Document preview · read-only · original bytes preserved"
                ""
            } else if (FileIconRegistry.isImageFile(path)) {
                nextKind = EditorDocumentKind.IMAGE
                nextStatus = "Image file — preview below"
                ""
            } else if (!files.exists(path)) {
                nextStatus = "New file: $path"
                ""
            } else {
                try {
                    files.readEditorText(path).also {
                        nextExisted = true
                        nextBytes = EditorLargeFilePolicy.utf8Bytes(it)
                        nextLines = EditorLargeFilePolicy.lineCount(it, EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_LINES + 1)
                        nextMode = EditorLargeFilePolicy.classify(nextBytes, nextLines).mode
                        if (nextMode == EditorFilePerformanceMode.LARGE_FILE_OPTIMIZED) {
                            nextStatus = "Large File Performance Mode · ${EditorLargeFilePolicy.formatBytes(nextBytes)} · editing enabled"
                        }
                    }
                } catch (e: LargeFileException) {
                    nextKind = EditorDocumentKind.LARGE
                    nextMode = EditorFilePerformanceMode.PREVIEW_ONLY
                    nextBytes = e.bytes
                    nextLines = EditorLargeFilePolicy.PREVIEW_LINES
                    nextStatus = "${EditorLargeFilePolicy.formatBytes(e.bytes)} · read-only preview (20 MiB limit)"
                    files.readRange(path, 1, EditorLargeFilePolicy.PREVIEW_LINES)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: com.baystudio.droide.core.UnsupportedTextEncodingException) {
                    nextKind = EditorDocumentKind.BINARY
                    nextStatus = "Unsupported encoding · original bytes preserved · convert a copy to UTF-8 before editing"
                    ""
                } catch (e: com.baystudio.droide.core.BinaryFileException) {
                    if (!loaded) {
                        nextKind = EditorDocumentKind.BINARY
                        nextStatus = e.message ?: "Binary file"
                        ""
                    } else throw e
                }
            }
            val nextDiskVersion = if (nextKind == EditorDocumentKind.TEXT) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    DiskFileVersion.fromText(if (nextExisted) text else null)
                }
            } else null
            if (!stillCurrent()) return
            // Release deferred edits only when their replacement can be published.
            // Failed/cancelled/stale reads must keep the recovery reference intact.
            if (discardUnsaved) pendingRecoveryBuffer = null
            content = text
            savedContent = text
            savedDiskVersion = nextDiskVersion
            observedDiskVersion = nextDiskVersion
            recoveryDirty = false
            kind = nextKind
            performanceMode = nextMode
            fileBytes = nextBytes
            lineCountHint = nextLines
            optimizedBufferDirty = false
            nativeBufferPending = false
            loadError = null
            status = nextStatus
            loaded = true
            revision++
            updateSelection(selectionStart, selectionEnd, recordInteraction = false)
            onPublished()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!stillCurrent()) return
            val message = "Cannot open $path: ${e.message ?: "unknown error"}"
            if (!loaded) loadError = message
            status = message
        }
    }

    suspend fun save(files: FileRepository): Result<EditorSaveReceipt> = save(files, {}, null)

    internal suspend fun save(
        files: FileRepository,
        requireCurrent: () -> Unit,
        commitPublication: (suspend (() -> Unit) -> Unit)?,
    ): Result<EditorSaveReceipt> {
        requireCurrent()
        if (handoffOwner != null) return Result.failure(IllegalStateException("Editor workspace is being replaced"))
        if (pendingRecoveryBuffer != null) ensureLoaded(files)
        requireCurrent()
        if (!editable) return Result.failure(IllegalStateException("This preview is read-only"))
        if (saveInProgress) return Result.failure(IllegalStateException("Save already in progress"))
        if (nativeBufferPending) return Result.failure(IllegalStateException("Editor buffer could not be captured; edits were preserved. Retry Save from the open editor."))
        val synchronizeOnly = !dirty && savedFileExisted == true
        if (synchronizeOnly && !saveSynchronizationPending) {
            return Result.success(EditorSaveReceipt(path, savedContent, didWrite = false, notifySaved = saveNotificationPending))
        }
        val completion = kotlinx.coroutines.CompletableDeferred<Unit>()
        saveCompletion = completion
        saveInProgress = true
        loadGeneration++
        val path = this.path
        val content = this.content
        val expected = savedDiskVersion
        val startRevision = revision
        val startVersion = changeVersion
        try {
            return runSuspendCatching {
                check(fileBytes <= EditorLargeFilePolicy.MAX_EDITABLE_BYTES) {
                    "Buffer exceeds the 20 MiB mobile editing/save ceiling. Reduce the file before saving."
                }
                check(expected != null) {
                    "Original disk version is unknown. Recovered edits were kept; Save is blocked to protect the file. Keep this buffer open and copy its text before discarding."
                }
                val observation = if (synchronizeOnly) null else {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        files.prepareEditorTextWriteObservation(path, content, expected)
                    }
                }
                val committedVersion = observation?.expectedAfter ?: expected
                check(this.path == path && revision == startRevision && changeVersion == startVersion) {
                    "Document changed while preparing Save. Review and save again."
                }
                requireCurrent()
                var syncPending: ProjectFileSyncException? = null
                try {
                    try {
                        if (synchronizeOnly) files.synchronizeEditorTextIfUnchanged(path, content, expected)
                        else files.writeEditorTextIfUnchanged(requireNotNull(observation), commitPublication)
                    } catch (saved: ProjectFileSyncException) {
                        // A callback exception alone cannot attribute the current file to
                        // this Save. Actual writes require this invocation's guarded receipt.
                        val matchesSaved = saved.relativePath == path && saved.bytes == committedVersion.bytes &&
                            (if (synchronizeOnly) files.matchesVersion(path, expected)
                            else observation?.disposition == FileRepository.TextWriteDisposition.OWNED_CURRENT)
                        if (!matchesSaved) throw saved
                        syncPending = saved
                    }
                } catch (cancelled: CancellationException) {
                    // The invocation records its final receipt while still holding the
                    // root guard, before an IO-return cancellation can discard its result.
                    // Equal foreign bytes are not proof that this Save published anything.
                    if (this@EditorDocument.path == path) {
                        if (synchronizeOnly) {
                            // No publication was requested. Keep the already accepted identity
                            // and any edits delivered while its observers were suspended.
                            saveSynchronizationPending = true
                            status = if (dirty) "Newer edits remain unsaved · synchronization interrupted"
                                else "Saved locally · synchronization interrupted; retry Save"
                        } else if (observation?.disposition == FileRepository.TextWriteDisposition.OWNED_CURRENT) {
                            savedContent = content
                            savedDiskVersion = committedVersion
                            observedDiskVersion = committedVersion
                            recoveryDirty = false
                            val changed = revision != startRevision || changeVersion != startVersion
                            optimizedBufferDirty = if (largeFileOptimized && changed) true else this@EditorDocument.content != content
                            saveSynchronizationPending = true
                            status = if (dirty) "Saved snapshot locally · newer edits remain unsaved · save completion interrupted"
                                else "Saved locally · save completion interrupted; refresh synchronization"
                        }
                    }
                    throw cancelled
                }
                check(this.path == path) { "Document moved during Save. Review before saving again." }
                if (!synchronizeOnly) {
                    check(observation?.disposition == FileRepository.TextWriteDisposition.OWNED_CURRENT) {
                        "Saved file could not be attributed to this Save. Edits were kept; review the file before retrying."
                    }
                    savedContent = content
                    savedDiskVersion = committedVersion
                    observedDiskVersion = committedVersion
                    recoveryDirty = false
                    val changedDuringWrite = revision != startRevision || changeVersion != startVersion
                    optimizedBufferDirty = if (largeFileOptimized && changedDuringWrite) true else this.content != content
                }
                saveSynchronizationPending = syncPending != null
                syncPending?.let { saved ->
                    status = if (dirty) "Saved snapshot locally · newer edits remain unsaved · synchronization pending"
                        else "Saved locally · synchronization pending; retry Save to synchronize"
                }
                if (syncPending == null) {
                    status = if (dirty) "Saved snapshot · newer edits remain unsaved"
                        else savedStatus()
                }
                EditorSaveReceipt(path, content, didWrite = !synchronizeOnly, notifySaved = true,
                    synchronizationFailure = syncPending)
            }
        } finally {
            saveInProgress = false
            saveCompletion = null
            completion.complete(Unit)
        }
    }

    internal var saveSynchronizationPending: Boolean = false
        private set
    internal var saveNotificationPending: Boolean = false
        private set

    internal fun markSaveNotificationPending() {
        saveNotificationPending = true
        val synchronization = if (saveSynchronizationPending) " · workspace synchronization pending" else ""
        status = if (dirty) "Saved snapshot · newer edits remain unsaved · language-server notification pending"
            else "Saved locally · language-server notification pending; retry Save to notify"
        status += synchronization
    }

    internal fun completeSaveNotification() {
        val wasPending = saveNotificationPending
        saveNotificationPending = false
        if (wasPending && !saveSynchronizationPending) {
            status = if (dirty) "Saved snapshot · newer edits remain unsaved"
                else savedStatus()
        }
    }

    // Native capture already profiles this version. Save must not rescan a 20 MiB
    // buffer on Main merely to update its completion label.
    private fun savedStatus(): String = if (largeFileOptimized) "Saved · large file"
        else "Saved — $lineCountHint lines"

    fun unloadClean() {
        requireMutableOwner()
        check(!dirty && !saveInProgress && !saveSynchronizationPending) { "Cannot unload unsaved, saving or pending-sync document" }
        loadGeneration++
        content = ""
        savedContent = ""
        savedDiskVersion = null
        observedDiskVersion = null
        recoveryDirty = false
        // The LSP owner closes this clean document; reopening sends a fresh didOpen.
        saveNotificationPending = false
        loaded = false
        revision++
    }

    /** Memory reclamation must never race a Save or discard an unsaved buffer. */
    internal fun tryUnloadClean(): Boolean {
        if (handoffOwner != null || !loaded || dirty || saveInProgress || saveSynchronizationPending) return false
        unloadClean()
        return true
    }

    fun discardUnsaved() {
        requireMutableOwner()
        if (pendingRecoveryBuffer != null) {
            pendingRecoveryBuffer = null
            savedDiskVersion = null
            observedDiskVersion = null
            recoveryDirty = false
            nativeBufferPending = false
            optimizedBufferDirty = false
            loadGeneration++
            revision++
            changeVersion++
            loadError = null
            status = "Changes discarded · reopen to load saved file"
            return
        }
        if (!editable) return
        content = savedContent
        savedDiskVersion = observedDiskVersion
        recoveryDirty = false
        optimizedBufferDirty = false
        nativeBufferPending = false
        profileCapturedText(content)
        status = "Changes discarded"
        revision++
        changeVersion++
    }

    fun restoreUnsaved(recoveredContent: String, diskVersion: DiskFileVersion? = null) {
        requireMutableOwner()
        val originalUnavailable = !editable
        if (originalUnavailable) {
            // A missing, unreadable, or newly binary original must not make a valid recovery blob disappear.

            kind = EditorDocumentKind.TEXT
            loaded = true
            savedContent = ""
            observedDiskVersion = null
            loadError = null
        }
        content = recoveredContent
        nativeBufferPending = false
        savedDiskVersion = diskVersion
        // Equal text does not prove that a legacy/externally changed original was accepted.
        recoveryDirty = diskVersion == null || diskVersion != observedDiskVersion
        profileCapturedText(content)
        optimizedBufferDirty = originalUnavailable || content != savedContent
        status = when {
            diskVersion == null -> "Recovered unsaved changes · original disk version unknown; Save blocked"
            recoveryDirty -> "Recovered unsaved changes · original disk changed; Save will check for conflicts"
            dirty -> "Recovered unsaved changes"
            else -> ""
        }
        revision++
        changeVersion++
    }

    fun applyWorkspaceText(updatedContent: String, reason: String = "Workspace edit") {
        check(agentEditable) { "Document is not Agent/LSP editable in its current performance mode: $path" }
        if (content == updatedContent) return
        content = updatedContent
        profileCapturedText(content)
        status = reason
        revision++
        changeVersion++
    }

    internal fun acceptPersistedText(text: String, reason: String, diskVersion: DiskFileVersion = DiskFileVersion.fromText(text)): Boolean {
        requireMutableOwner()
        check(kind == EditorDocumentKind.TEXT && loaded) { "Document is not editable text: $path" }
        val contentChanged = content != text
        content = text
        savedContent = text
        savedDiskVersion = diskVersion
        observedDiskVersion = diskVersion
        recoveryDirty = false
        optimizedBufferDirty = false
        nativeBufferPending = false
        profileCapturedText(text)
        status = reason
        if (contentChanged) {
            revision++
            changeVersion++
            captureSelection(selectionStart, selectionEnd)
        }
        return contentChanged
    }

    internal fun acceptPersistedBaseline(text: String, reason: String, diskVersion: DiskFileVersion = DiskFileVersion.fromText(text)) {
        requireMutableOwner()
        check(kind == EditorDocumentKind.TEXT && loaded) { "Document is not editable text: $path" }
        check(content == text) { "Persisted baseline does not match live content: $path" }
        savedContent = text
        savedDiskVersion = diskVersion
        observedDiskVersion = diskVersion
        recoveryDirty = false
        optimizedBufferDirty = false
        nativeBufferPending = false
        profileCapturedText(text)
        status = reason
    }

    internal fun remapPath(newPath: String) {
        requireMutableOwner()
        check(newPath.isNotBlank()) { "Document path is blank" }
        if (path == newPath) return
        path = newPath
        revision++
    }
}


data class EditorWorkspacePathMutation(
    val sequence: Long,
    val from: String,
    val to: String? = null,
)

@Stable
class EditorWorkspaceState {
    // Shared by the retained document authority and all screen/shortcut Save coordinators.
    internal val saveMutex = kotlinx.coroutines.sync.Mutex()
    internal val pendingAgentSaves = linkedMapOf<String, String>()

    /** Serializes final absent-target check with all document registration/path remap,
     * recovery retention and retirement. No suspension or stream IO is allowed in action. */
    internal fun commitDiskOnly(path: String, action: () -> Unit) = commitDiskOnlyChecked(path, null, action)

    @Synchronized
    internal fun commitDiskOnlyChecked(path: String, resolveEditorPath: ((String) -> String)?, action: () -> Unit) {
        require(path.isNotEmpty() && !path.startsWith('/') && !path.contains('\\') &&
            !path.contains('\u0000') && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Disk publication requires a normalized relative path"
        }
        requireMutableOwner()
        check(recoveryInitialized) { "Editor recovery has not initialized" }
        fun overlaps(other: String) = other == path || other.startsWith("$path/") || path.startsWith("$other/")
        val paths = (documents.values.map { it.path } + recoveryConflicts.keys + deferredRecoveryConflicts.keys).distinct()
        val lexical = paths.map(EditorDiskPublicationPaths::normalized)
        val occupied = lexical.filter(::overlaps)
        if (occupied.isNotEmpty()) throw com.baystudio.droide.core.WorkspaceDocumentConflictException(occupied.distinct())
        if (paths.isNotEmpty()) {
            val resolve = resolveEditorPath ?: throw com.baystudio.droide.core.WorkspaceRecoveryEditorUnavailableException(
                "Editor path identity is unavailable for disk publication")
            // Fail explicitly on aliases, never canonical-follow a live document optimistically.
            val resolved = paths.map(resolve)
            val conflicts = resolved.filter(::overlaps)
            if (conflicts.isNotEmpty()) throw com.baystudio.droide.core.WorkspaceDocumentConflictException(conflicts.distinct())
        }
        action()
    }

    private var handoffCaptureOwner: Any? = null
    private var handoffCapture: (() -> Unit)? = null
    private var handoffNative: (() -> EditorNativeHandoffBinding?)? = null
    private var sealedHandoffOwner: Any? = null
    private var captureBindingGeneration = 0L
    private var retirementGeneration = 0L
    private var handoffNativeGeneration: (() -> Long)? = null

    internal fun bindProjectHandoffCapture(capture: () -> Unit): AutoCloseable =
        bindProjectHandoffCapture(capture, { null })

    internal fun bindProjectHandoffCapture(
        capture: () -> Unit,
        native: () -> EditorNativeHandoffBinding?,
    ): AutoCloseable = bindProjectHandoffCapture(capture, native, null)

    internal fun bindProjectHandoffCapture(
        capture: () -> Unit,
        native: () -> EditorNativeHandoffBinding?,
        nativeGeneration: (() -> Long)?,
    ): AutoCloseable {
        check(sealedHandoffOwner == null) { "Cannot rebind a retiring editor workspace" }
        val owner = Any()
        captureBindingGeneration = Math.incrementExact(captureBindingGeneration)
        handoffCaptureOwner = owner
        handoffCapture = capture
        handoffNative = native
        handoffNativeGeneration = nativeGeneration
        return AutoCloseable {
            if (handoffCaptureOwner === owner) {
                captureBindingGeneration = Math.incrementExact(captureBindingGeneration)
                handoffCaptureOwner = null
                handoffCapture = null
                handoffNative = null
                handoffNativeGeneration = null
            }
        }
    }

    internal fun captureForProjectHandoff() { handoffCapture?.invoke() }
    internal fun nativeForProjectHandoff(): EditorNativeHandoffBinding? = handoffNative?.invoke()
    internal fun projectHandoffOwner(): Pair<Any?, Any?> = handoffCaptureOwner to nativeForProjectHandoff()?.owner
    @Synchronized
    internal fun sealForProjectHandoff(owner: Any) {
        check(sealedHandoffOwner == null) { "Editor workspace already belongs to a handoff" }
        retirementGeneration = Math.incrementExact(retirementGeneration)
        sealedHandoffOwner = owner
        documents.values.forEach { it.sealForHandoff(owner) }
    }
    @Synchronized
    internal fun abortProjectHandoff(owner: Any) {
        if (sealedHandoffOwner !== owner) return
        retirementGeneration = Math.incrementExact(retirementGeneration)
        documents.values.forEach { it.abortHandoff(owner) }
        sealedHandoffOwner = null
    }
    internal fun requireMutableOwner() {
        check(sealedHandoffOwner == null) { "Editor workspace is being replaced" }
    }

    // This state is retained by the application workspace across Activity recreation/closure.
    // Loading the disk checkpoint again would replace newer tabs/edits/selections.
    var recoveryInitialized by mutableStateOf(false)
        private set

    @Synchronized
    fun completeRecovery() { recoveryInitialized = true }

    internal data class Navigation(val openFiles: List<String>, val activeFile: String, val tab: Int)
    // Plain retained state: publishing a screen snapshot must not cause another recomposition.
    internal var navigation: Navigation? = null
        private set
    internal var navigationRevision = 0L
        private set
    internal fun retainNavigation(openFiles: List<String>, activeFile: String, tab: Int) {
        if (sealedHandoffOwner != null) return
        val next = Navigation(openFiles.toList(), activeFile, tab)
        if (navigation != next) navigationRevision++
        navigation = next
    }

    private var recoveryGeneration = 0L
    internal data class RecoveryDocumentToken(
        val document: EditorDocument, val revision: Int, val changeVersion: Int, val viewRevision: Long,
        val diskVersion: DiskFileVersion?,
    )
    internal data class RecoveryAdmission(
        val owner: EditorWorkspaceState, val generation: Long, val navigationRevision: Long,
        val navigation: Navigation?, val documents: Map<String, RecoveryDocumentToken>,
    )
    @Synchronized
    internal fun beginRecovery(currentNavigation: Navigation? = navigation): RecoveryAdmission = RecoveryAdmission(
        this, ++recoveryGeneration, navigationRevision, currentNavigation,
        documents.mapValues { (_, doc) -> RecoveryDocumentToken(doc, doc.revision, doc.changeVersion, doc.viewStateRevision, doc.savedDiskVersion) },
    )
    internal fun isRecoveryCurrent(admission: RecoveryAdmission): Boolean =
        admission.owner === this && admission.generation == recoveryGeneration && !recoveryInitialized &&
            sealedHandoffOwner == null
    @Synchronized
    internal fun cancelRecovery(admission: RecoveryAdmission) {
        if (admission.owner === this && admission.generation == recoveryGeneration) recoveryGeneration++
    }
    internal fun canRestoreDocument(admission: RecoveryAdmission, path: String): Boolean {
        if (!isRecoveryCurrent(admission)) return false
        val current = documents[path]
        val token = admission.documents[path] ?: return current == null
        return current != null && current === token.document && current.revision == token.revision &&
            current.changeVersion == token.changeVersion && current.viewStateRevision == token.viewRevision &&
            current.savedDiskVersion == token.diskVersion
    }

    internal var recoveryDiscardActions: EditorRecoveryDiscardActions? = null
    internal val recoveryConflictAuthority = com.baystudio.droide.core.EditorRecoveryConflictAuthority()
    private var pendingRecoveryDiscard: RecoveryDiscard? = null
    private val recoveryConflicts = linkedMapOf<String, String>()
    private val deferredRecoveryConflicts = linkedMapOf<String, EditorRecoveryBuffer>()
    private val recoveryConflictDiskVersions = linkedMapOf<String, DiskFileVersion>()
    /** Main-owned, read-only observation of authority outside the live document map.
     * Captures references/immutable metadata only: never invokes native capture or reads blobs.
     * This detects stale observations; it does not reserve or freeze any writer. */
    internal data class RecoveryAuthorityObservation(
        val owner: EditorWorkspaceState,
        val captureGeneration: Long,
        val captureOwner: Any?,
        val nativeGeneration: Long?,
        val nativeBinding: EditorNativeHandoffBinding?,
        val retirementGeneration: Long,
        val retirementOwner: Any?,
        val recoveryGeneration: Long,
        val initialized: Boolean,
        val navigationRevision: Long,
        val navigation: Navigation?,
        val activePath: String,
        val retainedText: Map<String, String>,
        val retainedDeferred: Map<String, EditorRecoveryBuffer>,
        val retainedDiskVersions: Map<String, DiskFileVersion>,
    ) {
        val retainedPaths: Set<String> get() = retainedText.keys + retainedDeferred.keys
    }

    internal fun observeRecoveryAuthority(): RecoveryAuthorityObservation {
        val native = nativeForProjectHandoff()
        // The production readiness callback only checks release/failure/capture
        // availability. Never begin handoff or invoke native capture during observation.
        native?.requireReady?.invoke()
        return RecoveryAuthorityObservation(
            this, captureBindingGeneration, handoffCaptureOwner,
            handoffNativeGeneration?.invoke(), native, retirementGeneration, sealedHandoffOwner,
            recoveryGeneration, recoveryInitialized, navigationRevision, navigation, activeEditorPath,
            recoveryConflicts.toMap(), deferredRecoveryConflicts.toMap(), recoveryConflictDiskVersions.toMap(),
        )
    }

    internal fun retainRecoveryConflicts(snapshot: EditorRecoverySnapshot) {
        for (path in snapshot.conflictPaths) retainRecoveryConflict(
            path, snapshot.conflictBuffers[path], snapshot.deferredConflictBuffers[path],
            snapshot.conflictDiskVersions[path] ?: snapshot.deferredConflictBuffers[path]?.diskVersion,
        )
    }
    @Synchronized
    internal fun retainRecoveryConflict(path: String, text: String?, deferred: EditorRecoveryBuffer?, diskVersion: DiskFileVersion? = deferred?.diskVersion) {
        require(text != null || deferred != null) { "Missing recovery conflict payload: $path" }
        require(deferred == null || diskVersion == deferred.diskVersion) { "Recovery conflict disk identity changed: $path" }
        val previousText = recoveryConflicts[path]
        val previousDeferred = deferredRecoveryConflicts[path]
        require((previousText == null && previousDeferred == null) ||
            (text != null && previousText == text && recoveryConflictDiskVersions[path] == diskVersion) ||
            (deferred != null && sameRecoveryBuffer(previousDeferred, deferred))) {
            "Another recovery copy already exists for $path; previous recovery data was preserved"
        }
        if (previousText == null && previousDeferred == null) {
            recoveryConflictAuthority.revoke()
            require(recoveryConflicts.size + deferredRecoveryConflicts.size < 256) { "Too many retained recovery copies" }
            if (deferred != null) deferredRecoveryConflicts[path] = deferred else recoveryConflicts[path] = requireNotNull(text)
            diskVersion?.let { recoveryConflictDiskVersions[path] = it }
        }
        documents[path]?.status = "Newer workspace state kept · older recovery copy retained"
    }

    internal class RetainedCopy internal constructor(
        internal val owner: EditorWorkspaceState,
        val path: String,
        internal val text: String?,
        internal val deferred: EditorRecoveryBuffer?,
        internal val diskVersion: DiskFileVersion?,
        internal val retirement: Long,
        internal val captureBinding: Long,
    )

    internal fun retainedCopies(): List<RetainedCopy> =
        (recoveryConflicts.keys + deferredRecoveryConflicts.keys).sorted().map { path ->
            RetainedCopy(this, path, recoveryConflicts[path], deferredRecoveryConflicts[path],
                recoveryConflictDiskVersions[path], retirementGeneration, captureBindingGeneration)
        }

    internal fun requireRetainedCopy(copy: RetainedCopy) {
        check(pendingRecoveryDiscard?.copy?.path != copy.path) { "Permanent removal of this recovery copy is pending; finish its review first" }
        requireRetainedCopyIdentity(copy)
    }

    private fun requireRetainedCopyIdentity(copy: RetainedCopy) {
        requireMutableOwner()
        check(copy.owner === this && copy.retirement == retirementGeneration && copy.captureBinding == captureBindingGeneration &&
            recoveryConflicts[copy.path] === copy.text && deferredRecoveryConflicts[copy.path] === copy.deferred &&
            recoveryConflictDiskVersions[copy.path] == copy.diskVersion) {
            "Recovery copy ownership changed. Reopen recovery copies and try again."
        }
    }

    internal class RecoveryDiscard internal constructor(internal val copy: RetainedCopy,
        internal val revision: com.baystudio.droide.core.EditorRecoveryConflictAuthority.Revision) {
        internal var finished = false
    }
    internal fun beginRecoveryDiscard(copy: RetainedCopy): RecoveryDiscard {
        check(pendingRecoveryDiscard == null) { "Finish the pending recovery removal first" }
        requireRetainedCopy(copy)
        recoveryConflictAuthority.revoke()
        return RecoveryDiscard(copy, recoveryConflictAuthority.capture()).also { pendingRecoveryDiscard = it }
    }
    internal fun snapshotForRecoveryDiscard(token: RecoveryDiscard, openFiles: List<String>, activeFile: String): EditorRecoverySnapshot {
        check(pendingRecoveryDiscard === token && !token.finished)
        requireRetainedCopyIdentity(token.copy)
        val snapshot = recoverySnapshot(openFiles, activeFile) ?: emptyRecoverySnapshot()
        val path = token.copy.path
        return snapshot.copy(conflictBuffers = snapshot.conflictBuffers - path,
            deferredConflictBuffers = snapshot.deferredConflictBuffers - path,
            conflictDiskVersions = snapshot.conflictDiskVersions - path)
    }
    /** May be called from IO at durable publication: invalidate queued snapshots before unlocking. */
    internal fun revokeDiscardCheckpoints() { recoveryConflictAuthority.revoke() }
    @Synchronized
    internal fun finishRecoveryDiscard(token: RecoveryDiscard, committed: Boolean) {
        check(pendingRecoveryDiscard === token && !token.finished)
        if (committed) {
            val copy = token.copy
            check(recoveryConflicts[copy.path] === copy.text && deferredRecoveryConflicts[copy.path] === copy.deferred &&
                recoveryConflictDiskVersions[copy.path] == copy.diskVersion) { "Selected recovery copy changed; do not overwrite newer copies" }
            recoveryConflicts.remove(copy.path); deferredRecoveryConflicts.remove(copy.path); recoveryConflictDiskVersions.remove(copy.path)
        }
        token.finished = true; pendingRecoveryDiscard = null; recoveryConflictAuthority.revoke()
    }
    internal fun emptyRecoverySnapshot() = EditorRecoverySnapshot(emptyList(), "", emptyMap(),
        conflictRevision = pendingRecoveryDiscard?.revision ?: recoveryConflictAuthority.capture())

    /** No IO and no suspension: construct completely before registration. Original recovery stays untouched. */
    @Synchronized
    internal fun registerRetainedCopy(copy: RetainedCopy, destination: String, text: String): EditorDocument {
        requireRetainedCopy(copy)
        check(documents.values.none { it.path == destination || it.path.startsWith("$destination/") || destination.startsWith("${it.path}/") }) {
            "An editor already owns this path or an overlapping path. Choose another filename."
        }
        val document = EditorDocument(destination)
        document.restoreUnsaved(text, DiskFileVersion.fromText(null))
        documents[destination] = document
        return document
    }

    private val documents = mutableStateMapOf<String, EditorDocument>()
    // A tab can retain its selection/review state without retaining two full text snapshots.
    // The visible editor remains protected; dirty and deferred documents are never discarded.
    var activeEditorPath: String = ""
        private set
    fun protectActiveEditor(path: String) { activeEditorPath = path }

    fun trimInactiveCleanDocuments(): List<String> = documents.values.toList().mapNotNull { document ->
        document.path.takeIf { it != activeEditorPath && document.tryUnloadClean() }
    }
    private val pathMutationLog = ArrayDeque<EditorWorkspacePathMutation>()
    private var pathMutationListener: ((EditorWorkspacePathMutation) -> Unit)? = null
    var pathMutationSequence by mutableLongStateOf(0L)
        private set

    @Synchronized
    fun document(path: String): EditorDocument = documents[path] ?: run {
        requireMutableOwner()
        EditorDocument(path).also { documents[path] = it }
    }
    fun peek(path: String): EditorDocument? = documents[path]
    fun allDocuments(): List<EditorDocument> = documents.values.toList()
    fun dirtyDocuments(): List<EditorDocument> = documents.values.filter { it.dirty }

    /** Called without suspension on the editor dispatcher so the captured tuple stays coherent. */
    internal fun captureSearchSnapshots(request: WorkspaceSearchCaptureRequest): WorkspaceSearchCapture {
        val captured = ArrayList<WorkspaceDocumentSnapshot>()
        val iterator = documents.values.iterator()
        while (true) {
            request.checkpoint()
            if (!iterator.hasNext()) break
            request.beforeDocument()
            iterator.next().captureForSearch(request)?.let(captured::add)
        }
        return WorkspaceSearchCapture(captured)
    }

    fun deferredRecoveryBuffers(): Map<String, EditorRecoveryBuffer> = documents.values.mapNotNull { doc ->
        doc.pendingRecoveryBuffer?.let { doc.path to it }
    }.toMap()

    /** Cached metadata only. A committed Save can advance the baseline while newer text/version stays unchanged. */
    internal fun recoveryCheckpointSignature(openFiles: List<String>, activeFile: String): String = buildString {
        append(activeFile).append('|').append(openFiles.joinToString(";"))
        // Finalization/reconciliation schedules a fresh checkpoint even without new typing.
        append("|recovery-conflicts:").append(recoveryConflictAuthority.capture().value)
        dirtyDocuments().sortedBy { it.path }.forEach { document ->
            append('|').append(document.path).append(':').append(document.changeVersion)
            val version = document.savedDiskVersion
            if (version == null) append("@baseline:unknown")
            else append("@baseline:").append(version.existed).append(':').append(version.bytes).append(':').append(version.sha256)
        }
        selectionSnapshots().toSortedMap().forEach { (path, selection) ->
            append('|').append(path).append('@').append(selection.start).append(':').append(selection.end)
        }
        reviewModePaths().sorted().forEach { append('|').append(it).append("#review") }
    }

    /** A lifecycle checkpoint preserves deferred blob ownership without reading its body. */
    fun recoverySnapshot(openFiles: List<String>, activeFile: String): EditorRecoverySnapshot? {
        // An empty, known-absent new file has persistence intent even before its first edit.
        val dirty = documents.values.filter { it.persistenceRequired }
        check(dirty.none { it.requiresBufferCapture }) {
            "Editor buffer could not be captured; previous recovery was kept. Retry from the open editor."
        }
        if (openFiles.isEmpty() && dirty.isEmpty() && recoveryConflicts.isEmpty() && deferredRecoveryConflicts.isEmpty()) return null
        return EditorRecoverySnapshot(
            openFiles = openFiles,
            activeFile = activeFile,
            dirtyBuffers = dirty.filter { it.pendingRecoveryBuffer == null }.associate { it.path to it.content },
            selections = selectionSnapshots(),
            reviewFiles = reviewModePaths(),
            deferredBuffers = deferredRecoveryBuffers(),
            conflictBuffers = recoveryConflicts.toMap(),
            deferredConflictBuffers = deferredRecoveryConflicts.toMap(),
            diskVersions = dirty.mapNotNull { doc -> doc.savedDiskVersion?.let { doc.path to it } }.toMap(),
            conflictDiskVersions = recoveryConflictDiskVersions.toMap(),
            conflictRevision = pendingRecoveryDiscard?.revision ?: recoveryConflictAuthority.capture(),
        )
    }
    fun hasDirtyAtOrUnder(path: String): Boolean {
        val prefix = path.trimEnd('/') + "/"
        return documents.values.any { it.dirty && (it.path == path || it.path.startsWith(prefix)) }
    }

    internal fun hasPendingPathMutationAtOrUnder(path: String): Boolean =
        documentsAtOrUnder(path).any { it.blocksPersistedPathMutation }

    @Synchronized
    fun forgetAtOrUnder(path: String) {
        requireMutableOwner()
        val prefix = path.trimEnd('/') + "/"
        documents.keys.filter { it == path || it.startsWith(prefix) }.toList().forEach(documents::remove)
    }

    internal fun documentsAtOrUnder(path: String): List<EditorDocument> {
        val prefix = path.trimEnd('/') + "/"
        return documents.values.filter { it.path == path || it.path.startsWith(prefix) }
    }

    @Synchronized
    internal fun remapPersistedPath(oldPath: String, newPath: String): List<Pair<String, String>> {
        requireMutableOwner()
        val affected = documentsAtOrUnder(oldPath)
        if (affected.isEmpty()) return emptyList()
        val oldPrefix = oldPath.trimEnd('/') + "/"
        fun remap(path: String): String = if (path == oldPath) newPath else newPath.trimEnd('/') + "/" + path.removePrefix(oldPrefix)
        val pairs = affected.map { it.path to remap(it.path) }
        val affectedKeys = pairs.mapTo(hashSetOf()) { it.first }
        pairs.forEach { (_, target) ->
            check(target !in documents || target in affectedKeys) { "Live editor destination already exists: $target" }
        }
        affected.forEach { documents.remove(it.path) }
        pairs.zip(affected).forEach { (pair, doc) ->
            doc.remapPath(pair.second)
            documents[pair.second] = doc
        }
        recordPathMutation(oldPath, newPath)
        return pairs
    }

    @Synchronized
    internal fun removePersistedPath(path: String): List<EditorDocument> {
        requireMutableOwner()
        val affected = documentsAtOrUnder(path)
        if (affected.isEmpty()) return emptyList()
        affected.forEach { documents.remove(it.path) }
        recordPathMutation(path, null)
        return affected
    }

    fun pathMutationsAfter(sequence: Long): List<EditorWorkspacePathMutation> =
        pathMutationLog.filter { it.sequence > sequence }

    fun bindPathMutationListener(listener: (EditorWorkspacePathMutation) -> Unit): AutoCloseable {
        pathMutationListener = listener
        return AutoCloseable { if (pathMutationListener === listener) pathMutationListener = null }
    }

    private fun recordPathMutation(from: String, to: String?) {
        pathMutationSequence += 1L
        val event = EditorWorkspacePathMutation(pathMutationSequence, from, to)
        pathMutationLog.addLast(event)
        while (pathMutationLog.size > 64) pathMutationLog.removeFirst()
        pathMutationListener?.invoke(event)
    }

    fun selectionSnapshots(): Map<String, com.baystudio.droide.core.EditorSelectionSnapshot> =
        documents.values.associate { it.path to com.baystudio.droide.core.EditorSelectionSnapshot(it.selectionStart, it.selectionEnd) }

    fun reviewModePaths(): Set<String> = documents.values.filter { it.reviewMode }.mapTo(linkedSetOf()) { it.path }

    @Synchronized
    fun clear() { requireMutableOwner(); documents.clear() }
}

 
class ActiveEditorBridge {
    internal var recoveryLifecycleGeneration: Long = 0L
        private set
    internal var nativeHandoff: EditorNativeHandoffBinding? = null
        set(value) {
            if (field === value) return
            recoveryLifecycleGeneration = Math.incrementExact(recoveryLifecycleGeneration)
            field = value
        }
    var snapshot: (() -> Unit)? = null
        set(value) {
            if (field === value) return
            recoveryLifecycleGeneration = Math.incrementExact(recoveryLifecycleGeneration)
            field = value
        }
    internal var yieldInputFocus: (() -> Unit)? = null
    var find: (() -> Unit)? = null
    var quickFix: (() -> Unit)? = null
    var renameSymbol: (() -> Unit)? = null
    var documentSymbols: (() -> Unit)? = null
    var goToDefinition: (() -> Unit)? = null
    var findReferences: (() -> Unit)? = null
    var formatDocument: (() -> Unit)? = null
    var completion: (() -> Unit)? = null
    var signatureHelp: (() -> Unit)? = null
    internal var resetZoom: (() -> Unit)? = null
    var cursorLocation: (() -> Pair<Int, Int>)? = null


    fun capture(): Boolean = safeInvoke(snapshot)
    internal fun yieldToAgentComposer(): Boolean = safeInvoke(yieldInputFocus)
    /** Save must observe a capture failure instead of publishing an old optimized buffer. */
    internal fun captureForSave() { snapshot?.invoke() }
    fun requestFind(): Boolean = safeInvoke(find)
    fun requestQuickFix(): Boolean = safeInvoke(quickFix)
    fun requestRenameSymbol(): Boolean = safeInvoke(renameSymbol)
    fun requestDocumentSymbols(): Boolean = safeInvoke(documentSymbols)
    fun requestGoToDefinition(): Boolean = safeInvoke(goToDefinition)
    fun requestFindReferences(): Boolean = safeInvoke(findReferences)
    fun requestFormatDocument(): Boolean = safeInvoke(formatDocument)
    fun requestCompletion(): Boolean = safeInvoke(completion)
    fun requestSignatureHelp(): Boolean = safeInvoke(signatureHelp)
    internal fun requestResetZoom(): Boolean = safeInvoke(resetZoom)
    fun currentCursorLocation(): Pair<Int, Int>? = try { cursorLocation?.invoke() } catch (_: Exception) { null }

    private fun safeInvoke(action: (() -> Unit)?): Boolean {
        val current = action ?: return false
        return try {
            current()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun clear() {
        snapshot = null
        yieldInputFocus = null
        find = null
        quickFix = null
        renameSymbol = null
        documentSymbols = null
        goToDefinition = null
        findReferences = null
        formatDocument = null
        completion = null
        signatureHelp = null
        resetZoom = null
        cursorLocation = null
    }
}
