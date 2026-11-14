package com.baystudio.droide.ui

import androidx.compose.runtime.mutableStateOf
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.UUID
import com.baystudio.droide.core.ChatMediaStore
import com.baystudio.droide.core.PluginConversationHistory
import com.baystudio.droide.core.SqlitePluginConversationHistory

/** Captured before opening a picker; a returned URI never decides its own conversation. */
internal data class AgentMediaDraftToken(val root: File, val ownerId: String, val generation: Long)

/** Retained presentation/admission state; it contains no Activity, View, or UI callback. */
class AgentWorkspaceUiState internal constructor(
    parent: CoroutineScope,
    context: Context? = null,
    workspaceRoot: File? = null,
    history: PluginConversationHistory? = null,
    projectId: String? = null,
    exitDraftStoreOverride: com.baystudio.droide.core.ExitDraftStore? = null,
) : AutoCloseable {
    private val parentScope = parent
    private val exitDraftStore = exitDraftStoreOverride ?: if (context != null && projectId != null) com.baystudio.droide.core.ExitDraftStore(context, projectId) else null
    internal val recoveredExitDrafts = mutableStateOf<List<com.baystudio.droide.core.ExitDraftRecord>>(emptyList())
    internal val exitDraftError = mutableStateOf<String?>(null)
    internal val exitDraftNotice = mutableStateOf<String?>(null)
    internal val unconfirmedExitMediaDrafts = mutableStateOf<List<com.baystudio.droide.core.ExitDraftRecord>>(emptyList())
    private val referenceOnlyExitApprovals = mutableSetOf<String>()
    internal val exitDraftsFrozen = mutableStateOf(false)
    internal val exitDraftOperationBusy = mutableStateOf(false)
    internal val exitDraftLoading = mutableStateOf(exitDraftStore != null)
    private var exitDraftLoadJob: Job? = null
    private var exiting = false
    internal val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    internal val pluginHistory = history ?: workspaceRoot?.let { SqlitePluginConversationHistory(it) }
    internal val externalController = ExternalAgentSessionController(scope, context?.applicationContext, pluginHistory, workDir = workspaceRoot)
    internal val input = mutableStateOf("")
    internal val attachments = mutableStateOf<List<AgentAttachedContext>>(emptyList())
    internal val mediaImporting = mutableStateOf(false)
    internal val mediaPickerPending = mutableStateOf(false)
    internal val mediaImportError = mutableStateOf<String?>(null)
    internal var mediaImportOperation: AgentMediaImportOperation? = null
        private set
    private var mediaRoot: File? = null
    private var mediaConversation: AgentChatConversationKey? = null
    private var mediaOwnerId = "draft-${UUID.randomUUID()}"
    private var mediaGeneration = 0L
    private var mediaSubmissionPending = false
    internal var pendingMediaPicker: AgentMediaDraftToken? = null
    internal val backendId = mutableStateOf("droide")
    internal val preflightJob = mutableStateOf<Job?>(null)
    internal val submissionError = mutableStateOf<String?>(null)
    private val chatPositions = linkedMapOf<AgentChatConversationKey, AgentChatPosition>()

    init {
        if (exitDraftStore != null) exitDraftLoadJob = scope.launch {
            try { recoveredExitDrafts.value = exitDraftStore.load() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { exitDraftError.value = "Saved unsent drafts could not be read. Their encrypted recovery was kept." }
            finally { exitDraftLoading.value = false }
        }
    }

    internal suspend fun preserveDraftsForExit(nativePending: List<com.baystudio.droide.core.AgentPendingInput> = emptyList()) {
        exiting = true
        exitDraftsFrozen.value = true
        exitDraftLoadJob?.join()
        preflightJob.value?.cancel(); preflightJob.value?.join()
        val importing = mediaImportOperation
        importing?.cancel(parentScope); importing?.job?.join()
        mediaPickerPending.value = false
        pendingMediaPicker = null
        val selected = attachments.value.toList()
        // Legacy attachments have no durable media reference. Preserve every available name
        // and preview, but never claim that the archive contains the original file bytes.
        val records = buildList {
            add(com.baystudio.droide.core.ExitDraftRecord(backendId.value, mediaConversation?.sessionId,
                input.value, selected.map { com.baystudio.droide.core.ExitDraftAttachment(it.name, it.text, it.media, it.previewTruncated) }))
            nativePending.filter { it.origin == com.baystudio.droide.core.AgentInputOrigin.USER }.forEach { queued ->
                add(com.baystudio.droide.core.ExitDraftRecord("droide", null, queued.text,
                    queued.media.map { com.baystudio.droide.core.ExitDraftAttachment(it.name, "Queued attachment", it) } +
                        queued.images.filter { image -> queued.media.none { it.id == image.mediaId || it.path == image.path } }.map { image ->
                            com.baystudio.droide.core.ExitDraftAttachment(image.path.substringAfterLast('/'),
                                "Legacy image reference only; original bytes are not included. Path: ${image.path}; type: ${image.mediaType}; bytes: ${image.bytes}; expiry: ${image.expiresAt ?: "unknown"}")
                        }))
            }
            externalController.snapshotPendingPromptsForExit().forEach { queued ->
                add(com.baystudio.droide.core.ExitDraftRecord(queued.agentId, queued.sessionId, queued.input.text,
                    queued.media.map { com.baystudio.droide.core.ExitDraftAttachment(it.name, "Queued attachment", it) }))
            }
        }.filter { it.text.isNotEmpty() || it.attachments.isNotEmpty() }
        val store = exitDraftStore
        check(store != null || records.isEmpty()) { "Unsent draft recovery is unavailable; keep Droide open" }
        if (store != null) {
            exitDraftOperationBusy.value = true
            try {
                // Existing attachment retention/expiry policy stays intact; the recovery UI
                // identifies references and previews explicitly, never a guaranteed byte backup.
                val approvedReferenceOnly = referenceOnlyExitApprovals.toSet()
                val attachmentRoot = mediaRoot
                val unconfirmed = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    records.filter { record ->
                        val refs = record.attachments.mapNotNull { it.media }
                        if (refs.isEmpty() || record.identity in approvedReferenceOnly) false
                        else try {
                            val root = checkNotNull(attachmentRoot) { "Attachment storage is unavailable" }
                            ChatMediaStore(root).retain("exit-draft-${record.identity.take(40)}", refs)
                            false
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { true }
                    }
                }
                unconfirmedExitMediaDrafts.value = unconfirmed
                check(unconfirmed.isEmpty()) {
                    "Attachment copies could not be retained. Keep Droide open and Retry, or open Review saved drafts to explicitly save text and attachment references only."
                }
                recoveredExitDrafts.value = store.preserve(records)
                exitDraftError.value = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                exitDraftError.value = failure.message ?: "Unsent drafts could not be saved. Keep Droide open and retry Exit."
                throw failure
            } finally { exitDraftOperationBusy.value = false }
        }
    }

    internal fun restoreExitDraft(record: com.baystudio.droide.core.ExitDraftRecord): Boolean {
        if (exiting || exitDraftOperationBusy.value || exitDraftLoading.value || input.value.isNotEmpty() || attachments.value.isNotEmpty() ||
            mediaImporting.value || mediaPickerPending.value || preflightJob.value?.isCompleted == false) {
            exitDraftError.value = "Restoring needs an empty, idle composer while the workspace is open. The saved draft is unchanged."
            return false
        }
        if (recoveredExitDrafts.value.none { it.identity == record.identity }) {
            exitDraftError.value = "That saved draft is no longer available. Choose another saved copy."
            return false
        }
        // Exact text only. Keep the current backend/session; switching could start plugin setup.
        // The archive copy stays available until the user explicitly confirms deleting it.
        input.value = record.text
        exitDraftError.value = null
        exitDraftNotice.value = if (record.text.length > com.baystudio.droide.core.AgentService.MAX_USER_MESSAGE_CHARS)
            "Full draft text restored. It exceeds the current Send limit; shorten it manually before Send and reattach any files. The saved copy remains."
            else if (record.attachments.isNotEmpty())
            "Draft text restored into this composer. Reattach the listed files before Send; saved references can expire. The saved copy remains."
            else "Draft text restored into this composer. Review the selected agent before Send. The saved copy remains."
        return true
    }

    /** Called only after the recovery dialog confirms the exact selected saved copy. */
    internal suspend fun deleteSavedExitDraft(record: com.baystudio.droide.core.ExitDraftRecord): Boolean {
        if (exitDraftOperationBusy.value || exitDraftLoading.value) {
            exitDraftError.value = "Draft recovery is still busy. Wait and try deleting this saved copy again."
            return false
        }
        val store = exitDraftStore
        if (store == null) {
            exitDraftError.value = "Saved draft storage is unavailable. Nothing was deleted."
            return false
        }
        exitDraftOperationBusy.value = true
        return try {
            recoveredExitDrafts.value = store.remove(record.identity)
            exitDraftError.value = null
            exitDraftNotice.value = "Selected saved copy deleted. The current composer is unchanged."
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            exitDraftError.value = "Could not confirm deletion of this saved copy. Keep Droide open and retry; the current composer is unchanged."
            false
        } finally { exitDraftOperationBusy.value = false }
    }

    /** Explicit approval applies only to the displayed failed snapshots, never future attachments. */
    internal fun allowReferenceOnlyExitDrafts(records: List<com.baystudio.droide.core.ExitDraftRecord>): Boolean {
        val failed = unconfirmedExitMediaDrafts.value.map { it.identity }.toSet()
        val selected = records.map { it.identity }.toSet()
        if (!exiting || exitDraftOperationBusy.value || selected.isEmpty() || !failed.containsAll(selected)) {
            exitDraftError.value = "The current Exit drafts changed. Review their attachment copies again before approving reference-only recovery."
            return false
        }
        referenceOnlyExitApprovals += selected
        exitDraftNotice.value = "Reference-only recovery approved for the selected Exit drafts. Original attachment bytes are not guaranteed. Retry Exit to save the text and references."
        return true
    }

    internal fun bindMediaConversation(root: File, conversation: AgentChatConversationKey) {
        val previous = mediaConversation
        if (mediaRoot == root && previous == conversation) return
        val firstSessionAdmission = mediaRoot == root && mediaSubmissionPending && previous != null &&
            previous.backendId == conversation.backendId && previous.sessionId == null && conversation.sessionId != null
        if (mediaRoot != null && !firstSessionAdmission) discardMediaDraft()
        mediaRoot = root
        mediaConversation = conversation
    }

    internal fun mediaDraftToken(): AgentMediaDraftToken = AgentMediaDraftToken(
        checkNotNull(mediaRoot) { "The conversation is not ready for attachments" }, mediaOwnerId, mediaGeneration,
    )

    internal fun isCurrentMediaDraft(token: AgentMediaDraftToken): Boolean = !exiting && scope.isActive &&
        token.root == mediaRoot && token.ownerId == mediaOwnerId && token.generation == mediaGeneration

    internal fun beginMediaSubmission(token: AgentMediaDraftToken) {
        if (isCurrentMediaDraft(token)) mediaSubmissionPending = true
    }

    internal fun finishMediaSubmission(token: AgentMediaDraftToken, accepted: Boolean) {
        if (!isCurrentMediaDraft(token)) return
        mediaSubmissionPending = false
        if (accepted) {
            attachments.value = emptyList()
            mediaImportError.value = null
            rotateMediaOwner()
        }
    }

    internal fun discardMediaDraft() {
        val oldRoot = mediaRoot
        val oldOwner = mediaOwnerId
        cancelMediaImport()
        attachments.value = emptyList()
        mediaImporting.value = false
        mediaImportError.value = null
        mediaSubmissionPending = false
        rotateMediaOwner()
        // A stale pending picker still owns its old token. Its result is released, never attached.
        if (oldRoot != null) parentScope.launch(Dispatchers.IO) {
            runCatching { ChatMediaStore(oldRoot).releaseOwner(oldOwner) }
        }
    }

    internal fun ownMediaImport(operation: AgentMediaImportOperation) {
        mediaImportOperation?.cancel(parentScope)
        mediaImportOperation = operation
        mediaImporting.value = true
        mediaImportError.value = null
    }

    internal fun finishMediaImport(operation: AgentMediaImportOperation) {
        if (mediaImportOperation !== operation) return
        mediaImportOperation = null
        mediaImporting.value = false
    }

    internal fun cancelMediaImport() {
        val operation = mediaImportOperation ?: return
        mediaImportOperation = null
        mediaImporting.value = false
        operation.cancel(parentScope)
    }

    internal fun removeMediaAttachment(index: Int) {
        val attachment = attachments.value.getOrNull(index) ?: return
        attachments.value = attachments.value.filterIndexed { i, _ -> i != index }
        val root = mediaRoot ?: return
        val owner = mediaOwnerId
        attachment.media?.let { ref -> parentScope.launch(Dispatchers.IO) {
            runCatching { ChatMediaStore(root).release(owner, ref) }
        } }
    }

    private fun rotateMediaOwner() {
        mediaOwnerId = "draft-${UUID.randomUUID()}"
        mediaGeneration++
    }

    internal fun chatPosition(key: AgentChatConversationKey): AgentChatPosition? = chatPositions[key]

    internal fun saveChatPosition(key: AgentChatConversationKey, position: AgentChatPosition) {
        if (!scope.isActive) return
        chatPositions.remove(key)
        chatPositions[key] = position
        while (chatPositions.size > 32) chatPositions.remove(chatPositions.keys.first())
    }

    internal fun forgetChatPosition(key: AgentChatConversationKey) { chatPositions.remove(key) }

    internal fun ownPreparation(job: Job) {
        preflightJob.value = job
        job.invokeOnCompletion {
            scope.launch {
                if (preflightJob.value === job) preflightJob.value = null
            }
        }
    }

    internal suspend fun closeForExit() {
        check(exiting) { "Preserve composer drafts before closing" }
        externalController.closeAfterExitCheckpoint()
        scope.coroutineContext[Job]?.cancel(); scope.coroutineContext[Job]?.join()
        // Do not release archived media owners or erase the retained draft on a failed later stage.
    }

    override fun close() {
        discardMediaDraft()
        chatPositions.clear()
        externalController.close()
        scope.cancel("Workspace replaced")
    }
}
