package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

 
class AgentWorkspaceMutation(
    private val files: FileRepository,
    private val documents: WorkspaceDocumentAuthority? = null,
) {
    sealed interface Target {
        val path: String
        val content: String

        data class Live(
            override val path: String,
            val snapshot: WorkspaceDocumentSnapshot,
        ) : Target {
            override val content: String get() = snapshot.content
        }

        data class Disk(
            override val path: String,
            val existed: Boolean,
            override val content: String,
        ) : Target
    }

    suspend fun inspect(path: String): Target {
        val normalized = canonicalRelativePath(path)
        val live = documents?.snapshot(normalized)
            ?.takeIf { it.loaded && it.kind == WorkspaceDocumentKind.TEXT }
        if (live != null) return Target.Live(normalized, live)
        val existed = files.exists(normalized)
        val current = if (existed) files.readTextForEdit(normalized) else ""
        return Target.Disk(normalized, existed, current)
    }

    suspend fun replaceLive(
        target: Target.Live,
        expected: WorkspaceDocumentVersion,
        content: String,
        reason: String,
    ): WorkspaceDocumentMutationResult = documents?.replaceText(target.path, expected, content, reason)
        ?: WorkspaceDocumentMutationResult.NotLive

    fun expectedVersion(args: JsonObject): WorkspaceDocumentVersion? {
        val revision = args["expected_revision"]?.jsonPrimitive?.intOrNull
        val changeVersion = args["expected_change_version"]?.jsonPrimitive?.intOrNull
        val rawIdentity = args["expected_document_id"]
        require(rawIdentity == null || rawIdentity is JsonPrimitive && rawIdentity.isString) {
            "expected_document_id must be a string copied from read_file document_id"
        }
        val documentId = (rawIdentity as? JsonPrimitive)?.contentOrNull
        // Legacy counter-only requests must read again; never attach today's identity.
        if (revision == null || changeVersion == null || documentId == null) return null
        return WorkspaceDocumentVersion(revision, changeVersion, documentId)
    }

    fun expectsLiveDocument(args: JsonObject): Boolean =
        listOf("expected_document_id", "expected_revision", "expected_change_version").any(args::containsKey)

    fun liveDocumentUnavailable(path: String): String =
        "ERROR: LIVE_DOCUMENT_CHANGED path=$path. The expected live editor is no longer available. " +
            "Disk was not changed. Run read_file and review the current document before editing."

    fun versionRequired(target: Target.Live): String =
        "ERROR: LIVE_DOCUMENT_VERSION_REQUIRED path=${target.path} document_id=${target.snapshot.documentId ?: "unavailable"} " +
            "revision=${target.snapshot.revision} change_version=${target.snapshot.changeVersion}. " +
            "Run read_file and retry with expected_document_id + expected_revision + expected_change_version " +
            "so replacement documents and unsaved user edits cannot be overwritten."

    fun versionConflict(path: String, current: WorkspaceDocumentSnapshot): String =
        "ERROR: DOCUMENT_CHANGED path=$path current_document_id=${current.documentId ?: "unavailable"} " +
            "current_revision=${current.revision} current_change_version=${current.changeVersion}. " +
            "The live editor document changed; re-run read_file and recompute the edit."

    suspend fun finalizeLive(
        history: EditHistory,
        lsp: LspManager,
        undo: EditHistory.PendingLive,
        apply: suspend () -> WorkspaceDocumentMutationResult,
        path: String,
        missingVerb: String,
        requireSaveCurrent: () -> Unit = {},
        success: (WorkspaceDocumentMutationResult.Applied) -> String,
    ): String {
        val result = try { apply() } catch (t: Throwable) { history.discardLive(undo); throw t }
        return when (result) {
            is WorkspaceDocumentMutationResult.Applied -> {
                history.commitLive(undo, EditHistory.LiveTransition.from(result))
                val sync = runSuspendCatching { lsp.didChange(path, result.after.content) }
                val saved = AgentEditPersistence.save(documents, listOf(result), requireSaveCurrent)
                success(result) + saved + if (sync.isFailure)
                    "\nWARNING: LSP sync deferred: ${sync.exceptionOrNull()?.message ?: "unknown error"}" else ""
            }
            is WorkspaceDocumentMutationResult.Conflict -> { history.discardLive(undo); versionConflict(path, result.current) }
            is WorkspaceDocumentMutationResult.ReadOnly -> { history.discardLive(undo); "ERROR: live editor document is read-only: $path" }
            WorkspaceDocumentMutationResult.NotLive -> { history.discardLive(undo); "ERROR: live editor document disappeared before $missingVerb: $path; re-run read_file." }
        }
    }

    private fun canonicalRelativePath(path: String): String {
        val root = files.root.canonicalFile
        val target = PathSecurity.resolveWithin(root, path).canonicalFile
        return root.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/')
    }
}
