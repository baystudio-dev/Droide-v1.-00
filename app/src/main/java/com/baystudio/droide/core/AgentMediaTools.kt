package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Native media tools keep temporary chat copies separate from explicitly saved project assets. */
internal object AgentMediaTools {
    private val videoMimeTypes = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime",
        "webm" to "video/webm", "mkv" to "video/x-matroska", "3gp" to "video/3gpp",
    )

    suspend fun viewVideo(
        args: JsonObject,
        c: AgentTools.Ctx,
        permission: suspend (String, String) -> String?,
    ): String {
        val path = requestedPath(args)
        val owner = owner(c)
        require(c.appContext != null) { "Video processing is unavailable in this Agent context" }
        val deliver = requireNotNull(c.onImageReady) { "Image delivery is unavailable in this Agent context" }
        require(VisionSupport.providerAcceptsImageUrl(c.llmConfig)) { "Selected model does not support image input" }
        permission("read", path)?.let { return it }
        return withContext(Dispatchers.IO) {
            val source = source(c.workDir, path, owner)
            val type = videoMimeTypes[source.extension.lowercase()]
                ?: throw IllegalArgumentException("Unsupported video type; use MP4, M4V, MOV, WebM, MKV or 3GP")
            val store = ChatMediaStore(c.workDir)
            val ref = noFollowInput(source).use { store.import(owner, source.name, type, it) }
            try {
                c.onProgress(AgentToolProgress(detail = "Preparing bounded video frames"))
                val prepared = ChatMediaProcessing.prepareModelImages(c.workDir, listOf(ref), owner)
                require(prepared.imagePaths.isNotEmpty()) { "No video frame could be decoded" }
                currentCoroutineContext().ensureActive()
                val images = VisionSupport.stage(c.workDir, prepared.imagePaths, c.llmConfig, ownerId = owner)
                currentCoroutineContext().ensureActive()
                deliver(images)
                "VIDEO_FRAMES_ATTACHED " + buildJsonObject {
                    put("media_id", ref.id)
                    put("path", ref.path)
                    put("frame_count", images.size)
                    put("audio_analyzed", false)
                    put("description", prepared.description)
                    put("evidence", "Sampled image data will be delivered after this tool-result group; unsampled video and audio are unverified.")
                }
            } catch (failure: Throwable) {
                releaseAfterFailure(store, owner, ref, failure)
                throw failure
            }
        }
    }

    suspend fun sendMedia(
        args: JsonObject,
        c: AgentTools.Ctx,
        permission: suspend (String, String) -> String?,
    ): String {
        val path = requestedPath(args)
        val referenceValue = args["reference_only"]
        val referenceOnly = if (referenceValue == null || referenceValue == JsonNull) false else
            requireNotNull((referenceValue as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull) {
                "reference_only must be a boolean"
            }
        if (referenceOnly) {
            require(AgentSessionResults.safePath(path) && AgentWorkspacePathPolicy.visible(path)) {
                "Result references require a visible project-relative file"
            }
        }
        val owner = owner(c)
        val publish = requireNotNull(c.onMediaReady) { "Conversation attachment delivery is unavailable in this Agent context" }
        permission("read", path)?.let { return it }
        permission("edit", "chat_media:$owner:$path")?.let { return it }
        return withContext(Dispatchers.IO) {
            if (referenceOnly) {
                val metadata = AgentWorkspaceArtifacts.requireRegular(c.workDir, File(c.workDir, path))
                currentCoroutineContext().ensureActive()
                c.onProgress(AgentToolProgress(producedFiles = listOf(path)))
                return@withContext "PROJECT_RESULT " + buildJsonObject {
                    put("path", path); put("bytes", metadata.size()); put("scope", "current_conversation")
                    put("reference_only", true); put("content_copied", false)
                    put("note", "Available in /results. Opening or exporting uses the current project file; this is not a frozen copy.")
                }
            }
            val source = source(c.workDir, path, owner)
            val type = mimeType(source)
            val store = ChatMediaStore(c.workDir)
            c.onProgress(AgentToolProgress(detail = "Preparing conversation attachment"))
            val ref = noFollowInput(source).use { store.import(owner, source.name, type, it) }
            try {
                currentCoroutineContext().ensureActive()
                // The Agent service buffers this durable reference and publishes after successful tool completion.
                publish(ref)
                "MEDIA_SENT " + buildJsonObject {
                    put("id", ref.id)
                    put("path", ref.path)
                    put("name", ref.name)
                    put("media_type", ref.mediaType)
                    put("bytes", ref.bytes)
                    put("expires_at", ref.expiresAt)
                    put("scope", "current_conversation")
                    put("original_preserved", true)
                }
            } catch (failure: Throwable) {
                releaseAfterFailure(store, owner, ref, failure)
                throw failure
            }
        }
    }

    suspend fun saveMediaToProject(
        args: JsonObject,
        c: AgentTools.Ctx,
        permission: suspend (String, String) -> String?,
    ): String {
        val path = requestedPath(args)
        val owner = owner(c)
        val runId = c.mediaRunId?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("An admitted user-requested run is required for saving media")
        check(c.mediaRequestCurrent()) { "This media request is no longer current" }
        val destination = (args["destination"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("Missing or invalid string arg: destination")
        val store = ChatMediaStore(c.workDir)
        val ref = withContext(Dispatchers.IO) {
            require(c.files.root.canonicalFile == c.workDir.canonicalFile) { "Project media destination does not match the active workspace" }
            ProjectCopyEvidence.validateDestination(destination)
            require(ExplorerFilePolicy.requireUserTarget(c.files.root, destination) == destination)
            store.requireOwnedOriginal(owner, path)
        }
        permission("read", ref.path)?.let { return it }
        permission("edit", destination)?.let { return it }
        check(c.mediaRequestCurrent()) { "This media request is no longer current" }
        val operationSource = "$owner:${ref.id}"
        c.mediaCopyAttempts.begin(operationSource, destination)?.let { return it }
        var lease: ProjectCopyJournal.Lease? = null
        var integrityOrigin = "unverified"
        var syncPending = false
        fun record(result: String, completed: Boolean = false): String {
            c.mediaCopyAttempts.record(operationSource, destination, result, completed)
            return result
        }
        fun saved(receipt: StreamPublicationReceipt, synchronization: String, historical: Boolean = false,
            error: Exception? = null): String = mediaCopyReceipt(ref, lease?.intent?.id, receipt,
                synchronization, integrityOrigin, historical, error)
        val bytes = try {
            require(c.documentAuthority?.snapshot(destination)?.dirty != true) {
                "Save or close the unsaved editor document before creating this asset"
            }
            val historical: ProjectCopyJournal.Record? = withContext(Dispatchers.IO) {
                val journal = c.files.copyRecoveryJournal()
                try {
                    lease = journal.begin(
                        ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.AGENT_MEDIA, owner, runId, ref.id), destination,
                        requestAdmittedAtNanos = c.mediaRequestAdmittedAtNanos, planStage = true)
                    null
                } catch (committed: ProjectCopyJournal.AlreadyCommitted) {
                    committed.record // Trusted begin only; later callbacks cannot forge this receipt.
                }
            }
            if (historical != null) return record(mediaCopyReceipt(ref, historical.intent.id, requireNotNull(historical.receipt),
                "not_reverified", "unverified", historical = true))
            val admitted = requireNotNull(lease)
            ChatMediaProjectAssets.save(store, owner, ref, c.files, destination,
                publicationObserver = object : StreamPublicationObserver, StreamStagePlan {
                    override val plannedStagePath: String? get() = admitted.plannedStagePath
                    override fun beforePublish(preparation: StreamPublicationPreparation) {
                        check(c.mediaRequestCurrent()) { "This media request is no longer current" }
                        admitted.beforePublish(preparation)
                    }
                    override fun onPublished(receipt: StreamPublicationReceipt) = admitted.onPublished(receipt)
                },
                beforePublication = {
                    check(c.mediaRequestCurrent()) { "This media request is no longer current" }
                    // Late read-only admission narrows the long-copy window. The authority has no
                    // atomic editor/publication reservation; postcommit reconciliation keeps dirty buffers.
                    require(c.documentAuthority?.snapshot(destination)?.dirty != true) {
                        "An unsaved editor document appeared; choose another asset path"
                    }
                },
                onIntegrityReady = { integrityOrigin = it },
                onProgress = { progress ->
                    val detail = when (progress.phase) {
                        StreamWritePhase.PREPARING -> "Preparing project asset"
                        StreamWritePhase.COPYING -> "Saving asset · ${progress.completedBytes} / ${ref.bytes} bytes"
                        StreamWritePhase.COMMITTING -> "Finishing project asset"
                        StreamWritePhase.SYNCHRONIZING -> "Updating workspace"
                    }
                    c.onProgress(AgentToolProgress(detail = detail))
                })
        } catch (committed: ProjectFileSyncException) {
            val receipt = lease?.committedReceipt
            // Preserve E's exact typed-receipt checks and additionally require this transaction's
            // mandatory canonical receipt. An identical preMutation exception is not a commit.
            if (receipt != null && committed.relativePath == destination && committed.bytes == ref.bytes &&
                receipt.preparation.relativePath == destination && receipt.preparation.bytes == ref.bytes) {
                syncPending = true
                committed.bytes
            } else if (receipt != null) {
                return record(saved(receipt, "failed", error = committed))
            } else {
                return record(mediaCopyFailure(destination, lease, committed))
            }
        } catch (cancelled: CancellationException) {
            val receipt = lease?.committedReceipt
            record(if (receipt != null) saved(receipt, "not_observed")
                else mediaCopyFailure(destination, lease, cancelled))
            throw cancelled
        } catch (failure: Exception) {
            // Cleanup, downstream observer or source-close failures cannot erase an exact commit.
            val receipt = lease?.committedReceipt
            return record(if (receipt != null) saved(receipt, "failed", error = failure)
                else mediaCopyFailure(destination, lease, failure))
        } finally {
            lease?.close()
        }
        val receipt = requireNotNull(lease?.committedReceipt) { "The copy completed without its exact durable receipt" }
        val finalReceipt = record(saved(receipt, if (syncPending) "pending" else "completed"), completed = !syncPending)
        try {
            c.onProgress(AgentToolProgress(detail = "Saved $destination" + if (syncPending) " · workspace sync pending" else "", fileChanges = listOf(
                AgentFileChange(destination, "Added project asset: ${ref.mediaType}, $bytes bytes. Independent of chat media expiry."),
            )))
        } catch (cancelled: CancellationException) {
            throw cancelled // Receipt/ledger already recorded; cancellation still propagates.
        } catch (_: Exception) { /* A detached display cannot revoke a completed canonical receipt. */ }
        return finalReceipt
    }

    private fun mediaCopyReceipt(ref: ChatMediaRef, transactionId: String?, receipt: StreamPublicationReceipt,
        synchronization: String, integrityOrigin: String, historical: Boolean = false, error: Exception? = null): String =
        (when {
            historical -> "MEDIA_SAVE_RECEIPT_REPLAY "
            error != null -> "ERROR: MEDIA_SAVED_LOCALLY_SYNC_FAILED "
            else -> "MEDIA_SAVED_TO_PROJECT "
        }) + buildJsonObject {
            transactionId?.let { put("transaction_id", it) }
            put("publication_id", receipt.preparation.publicationId)
            put("media_id", ref.id)
            put("path", receipt.preparation.relativePath)
            put("bytes", receipt.preparation.bytes)
            put("committed_bytes", receipt.preparation.bytes)
            put("sha256", receipt.preparation.sha256)
            put("media_type", ref.mediaType)
            put("retention", "project")
            put("independent_copy", true)
            put("chat_copy_preserved", true)
            put("local_commit_completed", true)
            put("synchronization", synchronization)
            put("sync_pending", synchronization != "completed")
            put("historical_receipt", historical)
            put("current_file_reverified", false)
            put("no_new_copy", historical)
            put("retryable", false)
            put("do_not_recopy", true)
            put("integrity_basis", integrityOrigin)
            put("historical_original_digest_available", integrityOrigin == "import")
            error?.let { put("error_type", it.javaClass.simpleName); put("detail", it.message.orEmpty().take(240)) }
            if (synchronization != "completed") put("note", "The local commit was recorded. Current bytes were not reverified. " +
                "Workspace synchronization may need attention; do not retry the copy automatically.")
        }

    private fun mediaCopyFailure(destination: String, lease: ProjectCopyJournal.Lease?, failure: Exception): String {
        val uncertain = lease?.isUncertain == true || failure is StreamPublicationUncertainException || failure is ProjectCopyJournal.Blocked
        return (if (uncertain) "ERROR: MEDIA_COPY_OUTCOME_UNKNOWN " else "ERROR: MEDIA_COPY_FAILED ") + buildJsonObject {
            put("path", destination)
            lease?.intent?.id?.let { put("transaction_id", it) }
            if (failure is ProjectCopyJournal.Blocked) failure.transactionId?.let { put("transaction_id", it) }
            put("outcome", if (uncertain) "unknown" else "no_canonical_receipt")
            put("retryable", false)
            put("do_not_recopy", true)
            put("chat_copy_preserved", true)
            put("error_type", failure.javaClass.simpleName)
            put("detail", failure.message.orEmpty().take(500))
            put("next_action", if (uncertain) "Open media recovery, inspect the transaction, and decide whether to allow a NEW manual copy. " +
                "Matching current bytes cannot establish the old outcome."
                else "Inspect this failure before a new user-requested run. Parent folders may already exist; existing data was preserved.")
        }
    }

    private fun requestedPath(args: JsonObject): String =
        (args["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing or invalid string arg: path")

    private fun owner(c: AgentTools.Ctx): String =
        c.sessionId?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("A durable conversation is required for media tools")

    /** Reject a foreign virtual alias before showing an approval for a resource this session cannot read. */
    internal fun requireManagedOwnership(workDir: File, path: String, owner: String?) {
        if (!path.startsWith(".droide/chat-media/blobs/")) return
        val store = ChatMediaStore(workDir)
        val ref = requireNotNull(store.findByPath(path)) { "Attachment is expired or unavailable; choose the file again." }
        require(owner != null && store.ownerRefs(owner).any { it.id == ref.id }) {
            "This attachment does not belong to the active conversation"
        }
    }

    internal fun source(workDir: File, path: String, owner: String): File {
        val file = if (path.startsWith(".droide/chat-media/blobs/")) {
            val store = ChatMediaStore(workDir)
            val ref = requireNotNull(store.findByPath(path)) { "Attachment is expired or unavailable; choose the file again." }
            require(store.ownerRefs(owner).any { it.id == ref.id }) { "This attachment does not belong to the active conversation" }
            requireNotNull(store.resolvePath(path)) { "Attachment is expired or unavailable; choose the file again." }
        } else PathSecurity.resolveWithin(workDir, path)
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Media source must be a workspace file or registered attachment" }
        require(file.length() in 1..ChatMediaStore.MAX_FILE_BYTES) { "Media source must be nonempty and at most 64 MiB" }
        return file
    }

    internal fun noFollowInput(file: File) = Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)

    internal fun mimeType(file: File): String {
        val head = noFollowInput(file).use { input ->
            val bytes = ByteArray(12)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                if (read == 0) continue
                count += read
            }
            bytes.copyOf(count)
        }
        VisionSupport.sniff(head)?.let { return it }
        videoMimeTypes[file.extension.lowercase()]?.let { return it }
        return when (file.extension.lowercase()) {
            "png", "jpg", "jpeg", "gif", "webp" -> "application/octet-stream" // A suffix alone is not image evidence.
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "doc" -> "application/msword"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a", "aac" -> "audio/" + if (file.extension.equals("m4a", true)) "mp4" else "aac"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "json" -> "application/json"
            "html", "htm" -> "text/html"
            "csv" -> "text/csv"
            "md", "markdown" -> "text/markdown"
            "txt", "kt", "java", "js", "ts", "jsx", "tsx", "py", "rs", "c", "cpp", "cc", "h", "hpp", "sh", "yaml", "yml", "xml" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private suspend fun releaseAfterFailure(store: ChatMediaStore, owner: String, ref: ChatMediaRef, failure: Throwable) {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                store.release(owner, ref)
            } catch (cleanup: Exception) {
                if (cleanup !== failure && cleanup !is CancellationException) failure.addSuppressed(cleanup)
            }
        }
    }
}
