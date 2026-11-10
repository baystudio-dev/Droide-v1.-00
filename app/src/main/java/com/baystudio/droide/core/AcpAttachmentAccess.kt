package com.baystudio.droide.core

import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Every operation rechecks the conversation's original; a lease never extends its ownership or
 * seven-day TTL. */
internal object AcpAttachmentAccess {
    private val leases = ConcurrentHashMap<String, Lease>()
    private val workspacePattern = Regex("[A-Za-z0-9._-]{1,160}")
    private val leasePattern = Regex("[a-f0-9-]{36}")

    class Lease internal constructor(
        internal val workspaceId: String,
        internal val token: String,
        private val store: ChatMediaStore,
        private val ownerId: String,
        private val refs: Map<String, ChatMediaRef>,
        private val files: FileRepository,
        private val editable: Boolean,
        parentJob: Job,
        private val permission: suspend (String, String) -> Boolean,
        private val saved: suspend (String) -> Unit,
        private val destinationAvailable: suspend (String) -> Unit,
        private val requestAdmittedAtNanos: Long?,
        private val copyJournal: ProjectCopyJournal,
    ) : Closeable {
        private val lifetime = SupervisorJob(parentJob)
        private val scope = CoroutineScope(Dispatchers.IO + lifetime)
        private val operations = Mutex()
        // Bind stable turn identity without persisting the live CLI capability bearer.
        private val journalRunId = ProjectCopyEvidence.sha256(("droide-acp-attachment-turn-v1\u0000" + token).toByteArray(Charsets.UTF_8))
        internal val active: Boolean get() = lifetime.isActive

        /** Compact, typed metadata only. Names are quoted data, never executable shell text. */
        fun promptReceipt(current: List<ChatMediaRef>): String = buildString {
            append("[Droide CLI attachment access]\n")
            append("Attached originals remain in private chat storage; their managed paths and droide-media URIs are not shell files.\n")
            current.distinctBy { it.id }.take(4).forEach { ref ->
                require(refs[ref.id] == ref) { "Attachment is not owned by this CLI conversation" }
                append(buildJsonObject {
                    put("id", ref.id); put("name", ref.name); put("media_type", ref.mediaType)
                    put("bytes", ref.bytes); put("expires_at_ms", ref.expiresAt)
                }).append('\n')
            }
            append("For complete text beyond an excerpt: droide attachment read $token <attachment-id> [next_offset]\n")
            if (editable) {
                append("When the user asks to copy/use a file in the project: droide attachment save $token <attachment-id> <new-project-relative-path>\n")
                append("Save supports every original file type, including ZIP, PDF, documents, source files, images and videos. It streams exact bytes and never overwrites. Use the returned project path in later commands. Do not reconstruct binary files from excerpts or base64.\n")
            } else append("This is a read-only turn; saving attachments requires Build mode.\n")
            append("These commands use this active turn's lease only; previous turn leases are invalid. Conversation deletion/expiry revokes access. An explicitly saved project copy is permanent and independent of chat cleanup. Do not save every attachment automatically.\n")
            append("[/Droide CLI attachment access]")
        }

        internal suspend fun execute(command: String, id: String, argument: String?): String {
            require(active) { "CLI attachment access ended; send another message in this conversation" }
            val operation = scope.async {
                operations.withLock {
                    currentCoroutineContext().ensureActive()
                    val ref = requireNotNull(refs[id]) { "Attachment is not part of this CLI conversation" }
                    require(store.requireOwnedOriginal(ownerId, ref.path) == ref) { "Attachment changed; choose it again" }
                    when (command) {
                        "read" -> {
                            val offset = argument?.toIntOrNull() ?: if (argument == null) 0 else error("Invalid attachment text offset")
                            require(offset >= 0) { "Attachment text offset must be nonnegative" }
                            require(permission("read", ref.path)) { "Attachment read denied" }
                            currentCoroutineContext().ensureActive()
                            AgentMediaTextReader.read(files.root, ref.path, null, null, offset, ownerId)
                        }
                        "save" -> save(ref, requireNotNull(argument) { "Missing project-relative destination" })
                        else -> error("Unknown attachment command")
                    }
                }
            }
            return try { operation.await() } finally { operation.cancel() }
        }

        private suspend fun save(ref: ChatMediaRef, destination: String): String {
            require(editable) { "Saving an attachment requires Build mode" }
            ChatMediaProjectAssets.destination(files.root, destination)
            destinationAvailable(destination)
            require(permission("read", ref.path)) { "Attachment read denied" }
            require(permission("edit", destination)) { "Project attachment save denied" }
            currentCoroutineContext().ensureActive()
            requireActiveTurn()
            destinationAvailable(destination)
            var copy: ProjectCopyJournal.Lease? = null
            var syncPending = false
            var syncDetail: String? = null
            try {
                // The derived turn identity is fixed at bind. Null original admission cannot consume a recovery
                // permit; neither tool invocation nor retry mints fresh authority.
                copy = try {
                    copyJournal.begin(ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.AGENT_MEDIA,
                        ownerId, journalRunId, ref.id), destination, requestAdmittedAtNanos, planStage = true)
                } catch (historical: ProjectCopyJournal.AlreadyCommitted) {
                    // Only the trusted admission call can supply a historical journal receipt. Copy/observer
                    // callbacks below cannot substitute an embedded foreign record.
                    return receipt(ref, historical.record.intent.id, requireNotNull(historical.record.receipt),
                        syncPending = true, available = false, historical = true)
                }
                val admitted = requireNotNull(copy)
                ChatMediaProjectAssets.save(store, ownerId, ref, files, destination,
                    publicationObserver = object : StreamPublicationObserver, StreamStagePlan {
                        override val plannedStagePath: String? get() = admitted.plannedStagePath
                        override fun beforePublish(preparation: StreamPublicationPreparation) {
                            requireActiveTurn()
                            admitted.beforePublish(preparation)
                        }
                        // Publication may finish under NonCancellable after Stop. Record its exact receipt even when the
                        // turn has just ended; never erase the commit.
                        override fun onPublished(receipt: StreamPublicationReceipt) = admitted.onPublished(receipt)
                    },
                    beforePublication = {
                        currentCoroutineContext().ensureActive()
                        requireActiveTurn()
                        destinationAvailable(destination)
                        currentCoroutineContext().ensureActive()
                        requireActiveTurn()
                    })
            } catch (committed: ProjectFileSyncException) {
                val exact = copy?.committedReceipt
                if (exact == null) throw outcomeFailure(destination, copy, committed)
                // Preserve the typed path/byte contract, but never accept it without this transaction's actual
                // mandatory publication receipt.
                syncPending = true
                if (committed.relativePath != destination || committed.bytes != ref.bytes ||
                    exact.preparation.relativePath != destination || exact.preparation.bytes != ref.bytes) {
                    syncDetail = "Postcommit synchronization reported inconsistent path/byte metadata; the exact local receipt is retained."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled // The journal, not a successful coroutine return, owns commit truth.
            } catch (failure: Exception) {
                if (copy?.committedReceipt == null) throw outcomeFailure(destination, copy, failure)
                syncPending = true
                syncDetail = "Postcommit cleanup or synchronization failed; the exact local receipt is retained."
            } finally { copy?.close() }
            val exact = requireNotNull(copy?.committedReceipt) { "Attachment save completed without its exact publication receipt" }
            // A detached mirror cannot remove the asset or revoke the recorded canonical commit.
            val available = try { saved(destination); true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            return receipt(ref, requireNotNull(copy).intent.id, exact, syncPending, available, syncDetail = syncDetail)
        }

        private fun requireActiveTurn() {
            if (!active) throw CancellationException("CLI attachment turn ended before publication")
        }

        private fun outcomeFailure(destination: String, copy: ProjectCopyJournal.Lease?, failure: Exception): Exception {
            if (copy?.isUncertain != true && failure !is StreamPublicationUncertainException && failure !is ProjectCopyJournal.Blocked)
                return failure
            return IllegalStateException("ATTACHMENT_COPY_OUTCOME_UNKNOWN " + buildJsonObject {
                put("path", destination)
                (if (failure is ProjectCopyJournal.Blocked) failure.transactionId else copy?.intent?.id)?.let { put("transaction_id", it) }
                put("outcome", "unknown"); put("retryable", false); put("do_not_recopy", true)
                put("recovery_controls_available", false)
                put("detail", failure.message.orEmpty().take(500))
                put("next_action", "Preserve the project destination and do not retry automatically. " +
                    "ACP attachment recovery controls are not available for this conversation. " +
                    "Current matching bytes cannot establish the earlier outcome.")
            }, failure)
        }

        private fun receipt(ref: ChatMediaRef, transactionId: String, exact: StreamPublicationReceipt,
            syncPending: Boolean, available: Boolean, historical: Boolean = false, syncDetail: String? = null): String =
            (if (historical) "ATTACHMENT_SAVE_RECEIPT_REPLAY " else "ATTACHMENT_SAVED_TO_PROJECT ") + buildJsonObject {
                put("path", exact.preparation.relativePath); put("bytes", exact.preparation.bytes); put("media_type", ref.mediaType)
                put("transaction_id", transactionId); put("publication_id", exact.preparation.publicationId)
                put("sha256", exact.preparation.sha256); put("local_commit_completed", true)
                put("historical_receipt", historical); put("current_file_reverified", false); put("no_new_copy", historical)
                put("retryable", false); put("do_not_recopy", true)
                put("retention", "project"); put("independent_copy", true)
                put("sync_pending", syncPending); put("cli_workspace_ready", available)
                val notes = buildList {
                    if (historical) add("This is an earlier commit receipt. No file was copied or checked now; CLI projection was not refreshed.")
                    else {
                        if (syncPending) add("The original file is saved in the project. Workspace synchronization is pending; use this saved path and do not retry the copy.")
                        if (!available) add("CLI projection failed or has conflicting work; an existing CLI path is preserved across refresh. Export or explicitly reconcile that work before removing the conflicting path.")
                    }
                    syncDetail?.let { add(it) }
                }
                if (notes.isNotEmpty()) put("note", notes.joinToString(" "))
            }

        override fun close() {
            leases.remove(token, this)
            scope.cancel()
        }

        suspend fun finish() {
            close()
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { lifetime.join() }
        }
    }

    suspend fun bind(
        workspaceId: String,
        store: ChatMediaStore,
        ownerId: String,
        files: FileRepository,
        mode: WorkspaceAgentMode,
        turnJob: Job? = null,
        permission: suspend (String, String) -> Boolean,
        saved: suspend (String) -> Unit,
        destinationAvailable: suspend (String) -> Unit = {},
        requestAdmittedAtNanos: Long? = null,
        copyJournal: ProjectCopyJournal = files.copyRecoveryJournal(),
    ): Lease? {
        require(workspacePattern.matches(workspaceId)) { "Invalid attachment workspace identity" }
        val refs = store.ownerRefs(ownerId).associateBy { it.id }
        if (refs.isEmpty()) return null
        val lifetime = requireNotNull(turnJob ?: currentCoroutineContext()[Job]) { "CLI attachment access requires a running turn" }
        currentCoroutineContext().ensureActive()
        require(leases.size < 32) { "Too many active CLI attachment turns" }
        val lease = Lease(workspaceId, UUID.randomUUID().toString(), store, ownerId, refs, files,
            mode == WorkspaceAgentMode.EDIT, lifetime, permission, saved, destinationAvailable, requestAdmittedAtNanos, copyJournal)
        leases[lease.token] = lease
        lifetime.invokeOnCompletion { lease.close() }
        return lease
    }

    suspend fun execute(workspaceId: String?, args: List<String>): String {
        require(args.size in 3..4 && args[0] in setOf("read", "save") &&
            (args[0] != "save" || args.size == 4)) {
            "Usage: droide attachment [read lease attachment-id [offset]|save lease attachment-id project-relative-path]"
        }
        require(args.all { it.length <= 512 && it.none { char -> char.code < 32 || char.code == 127 } }) { "Invalid attachment argument" }
        val token = args[1]
        require(leasePattern.matches(token)) { "Invalid attachment lease" }
        val lease = requireNotNull(leases[token]?.takeIf { it.workspaceId == workspaceId && it.active }) {
            "CLI attachment access is unavailable in this workspace or this turn has ended"
        }
        return lease.execute(args[0], args[2], args.getOrNull(3))
    }
}
