package com.baystudio.droide.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.security.MessageDigest

// The language server may propose edits, but it never receives mutation authority.

class AgentLspEditActions(
    private val files: FileRepository,
    private val lsp: LspManager,
    private val history: EditHistory,
    private val documents: WorkspaceDocumentAuthority?,
    private val codeStyleDefaults: suspend () -> CodeStyleDefaults = { CodeStyleDefaults() },
) {
    data class Prepared(
        val title: String,
        val workspaceEdit: LspWorkspaceEdit,
        val before: Map<String, String>,
        val liveVersions: Map<String, WorkspaceDocumentVersion>,
        val after: Map<String, String>,
        val preview: String,
        val permissionResource: String,
        val fileChanges: List<AgentFileChange> = emptyList(),
    )

    suspend fun listActions(path: String, line: Int, col: Int): String {
        files.resolveChecked(path)
        val actions = lsp.codeActionsAt(path, line, col)
        if (actions.isEmpty()) return "No LSP code actions available at $path:$line:$col."
        return buildString {
            append("LSP_CODE_ACTIONS path=").append(path).append(" line=").append(line).append(" col=").append(col).append('\n')
            actions.take(60).forEachIndexed { index, action ->
                append(index + 1).append(". ").append(action.title.take(240))
                action.kind?.let { append(" · ").append(it.take(120)) }
                if (action.preferred) append(" · preferred")
                action.disabledReason?.let { append(" · disabled: ").append(it.take(240)) }
                if (action.edit != null) append(" · transparent edit")
                else if (!action.rawJson.isNullOrBlank()) append(" · resolve required")
                action.commandTitle?.let { append(" · command=").append(it.take(160)) }
                append('\n')
            }
        }.take(12_000)
    }

    suspend fun prepareCodeAction(path: String, line: Int, col: Int, title: String): Prepared {
        require(title.isNotBlank()) { "Code-action title is required" }
        files.resolveChecked(path)
        val candidates = lsp.codeActionsAt(path, line, col).filter { it.title == title }
        require(candidates.size == 1) {
            when {
                candidates.isEmpty() -> "Code action is no longer available: $title"
                else -> "Code-action title is ambiguous at the current location; list actions again and choose a unique title"
            }
        }
        val resolved = lsp.resolveCodeAction(path, candidates.single())
        require(resolved.disabledReason == null) { resolved.disabledReason ?: "Code action is disabled" }
        val edit = resolved.edit ?: error(
            if (resolved.commandTitle != null) {
                "Code action requires opaque server command '${resolved.commandTitle}'. Agent mutation is limited to transparent WorkspaceEdit payloads."
            } else "Language server returned no transparent workspace edit"
        )
        return prepare("Code action: ${resolved.title}", edit)
    }

    suspend fun prepareFormat(path: String): Prepared {
        files.resolveChecked(path)
        // Style detection is not edit ownership: prepare() still captures/revalidates the server
        // proposal against the current live/disk document afterwards.
        val defaults = codeStyleDefaults()
        val live = documents?.snapshot(path)?.takeIf { it.loaded }
        val style = withContext(Dispatchers.IO) {
            val content = if (live != null) {
                require(live.kind == WorkspaceDocumentKind.TEXT && live.editable) { "Document is not editable: $path" }
                live.content
            } else files.readTextForEdit(path)
            CodeStyleAuthority(files.root).resolve(path, LanguageRegistry.forFile(path)?.id ?: "plaintext", content, defaults)
        }
        val edit = lsp.formatDocument(path, LspFormattingOptions.fromCodeStyle(style)) ?: error("Language server does not provide document formatting for $path")
        require(edit.edits.isNotEmpty()) { "Document is already formatted or formatter returned no edits" }
        return prepare("Format document: $path", edit)
    }

    suspend fun apply(prepared: Prepared, requireSaveCurrent: () -> Unit = {}): String {
        require(prepared.after.isNotEmpty()) { "Workspace edit has no effective text changes" }
        requireCurrent(prepared.workspaceEdit)

        for ((path, expectedText) in prepared.before) {
            val live = documents?.snapshot(path)?.takeIf { it.loaded }
            if (live != null) {
                require(live.kind == WorkspaceDocumentKind.TEXT && live.editable) { "Live document is not Agent-editable: $path" }
                require(live.content == expectedText && live.version == prepared.liveVersions[path]) {
                    "Live document changed after LSP review: $path. Re-run the action."
                }
            } else {
                require(files.exists(path)) { "Workspace edit target disappeared after review: $path" }
                require(files.readTextForEdit(path) == expectedText) { "Workspace edit target changed after review: $path. Re-run the action." }
            }
        }

        val pending = history.prepareBatch(prepared.title, prepared.before)
        data class AppliedLive(val path: String, val before: String, val transition: EditHistory.LiveTransition)
        val appliedLive = mutableListOf<AppliedLive>()
        val persistence = mutableListOf<WorkspaceDocumentMutationResult.Applied>()
        val appliedDisk = mutableListOf<String>()
        val uncertainDisk = linkedSetOf<String>()
        val syncPending = mutableListOf<String>()
        val output = try {
            // Preparation can suspend. Admit the exact captured owner again immediately before the first
            // mutation; our own writes may then invalidate its capture.
            requireCurrent(prepared.workspaceEdit)
            for ((path, updated) in prepared.after) {
                val expectedVersion = prepared.liveVersions[path]
                if (expectedVersion != null) {
                    val authority = documents ?: error("Editor authority unavailable for live LSP edit: $path")
                    when (val result = authority.replaceText(path, expectedVersion, updated, prepared.title)) {
                        is WorkspaceDocumentMutationResult.Applied -> {
                            persistence += result
                            appliedLive += AppliedLive(path, result.before.content, EditHistory.LiveTransition.from(result))
                            runSuspendCatching { lsp.didChange(path, result.after.content) }
                        }
                        is WorkspaceDocumentMutationResult.Conflict -> error("Live document changed during LSP edit: $path")
                        is WorkspaceDocumentMutationResult.ReadOnly -> error("Live document became read-only: $path")
                        WorkspaceDocumentMutationResult.NotLive -> error("Live document disappeared during LSP edit: $path")
                    }
                } else {
                    documents?.validatePersistedMutation(FileRepository.Mutation.Write(path, updated))
                    uncertainDisk += path
                    try {
                        files.writeTextIfUnchanged(path, updated, DiskFileVersion.fromText(prepared.before.getValue(path)))
                    } catch (saved: ProjectFileSyncException) {
                        require(saved.relativePath == path && saved.bytes == updated.toByteArray(Charsets.UTF_8).size.toLong()) {
                            "Unrelated synchronization failure during LSP write: $path"
                        }
                        syncPending += path
                    }
                    appliedDisk += path
                    uncertainDisk -= path
                }
            }
            history.commitBatch(pending, appliedLive.associate { it.path to it.transition }, prepared.after)
            buildString {
                append("LSP_EDIT_APPLIED title=").append(prepared.title.take(240)).append('\n')
                append("files=").append(prepared.after.size).append('\n')
                append("live_buffers=").append(appliedLive.size).append('\n')
                append("direct_disk_files=").append(appliedDisk.size).append('\n')
                if (syncPending.isNotEmpty()) {
                    append("sync_pending=true paths=").append(syncPending.joinToString(", ")).append('\n')
                    append("note=Canonical files were saved; refresh synchronization rather than repeating the edit.\n")
                }
                append("status=applied_reviewed_workspace_edit")
            }
        } catch (t: Throwable) {
            if (t !is Exception) throw t // Keep recovery backups and the original fatal error.
            val rollbackErrors = mutableListOf<String>()
            val remainingLive = appliedLive.associate { it.path to it.transition }.toMutableMap()
            val remainingDisk = appliedDisk.associateWith { prepared.after.getValue(it) }.toMutableMap()
            var rollbackInterruption: Throwable? = null
            withContext(NonCancellable) {
                for ((path, oldText, transition) in appliedLive.asReversed()) {
                    runSuspendCatching {
                        when (val rollback = documents?.replaceText(path, transition.after, oldText, "Rollback ${prepared.title}")) {
                            is WorkspaceDocumentMutationResult.Applied -> {
                                history.recordLiveRollback(pending, path, transition, rollback)
                                remainingLive.remove(path)
                                try { lsp.didChange(path, rollback.after.content) }
                                catch (interrupted: CancellationException) { rollbackInterruption = interrupted }
                                catch (fatal: Error) { rollbackInterruption = fatal }
                                catch (notification: Exception) {
                                    rollbackErrors += "$path: rollback LSP synchronization pending: ${notification.message}"
                                }
                            }
                            null, WorkspaceDocumentMutationResult.NotLive -> error("Editor disappeared")
                            is WorkspaceDocumentMutationResult.Conflict -> error("Newer editor change preserved")
                            is WorkspaceDocumentMutationResult.ReadOnly -> error("Buffer became read-only")
                        }
                    }.exceptionOrNull()?.let { rollbackErrors += "$path: ${it.message ?: "rollback failed"}" }
                    if (rollbackInterruption != null) break
                }
                for (path in appliedDisk.asReversed()) {
                    if (rollbackInterruption != null) break
                    runSuspendCatching {
                        try {
                            files.writeTextIfUnchanged(path, prepared.before.getValue(path),
                                DiskFileVersion.fromText(prepared.after.getValue(path)))
                        } catch (saved: ProjectFileSyncException) {
                            // The rollback itself reached disk. Its downstream sync status must not turn it into another
                            // blind rewrite of a newer file.
                            require(saved.relativePath == path &&
                                saved.bytes == prepared.before.getValue(path).toByteArray(Charsets.UTF_8).size.toLong())
                            syncPending += path
                        }
                        remainingDisk.remove(path)
                    }
                        .exceptionOrNull()?.let { rollbackErrors += "$path: ${it.message ?: "rollback failed"}" }
                }
                val remaining = remainingLive.keys + remainingDisk.keys
                if (remaining.isNotEmpty()) history.commitBatch(
                    pending.copy(snapshots = pending.snapshots.filter { it.path in remaining }),
                    remainingLive, remainingDisk,
                )
                history.discardBatch(pending.copy(snapshots = pending.snapshots.filter {
                    it.path !in remaining && it.path !in uncertainDisk
                }))
            }
            rethrowLiveRollbackInterruption(t, rollbackInterruption)
            val suffix = buildString {
                if (rollbackErrors.isNotEmpty()) append("; rollback notes: ").append(rollbackErrors.joinToString(" | ").take(1_000))
                if (uncertainDisk.isNotEmpty()) append("; failed target was not overwritten; recovery backups retained: ")
                    .append(pending.snapshots.filter { it.path in uncertainDisk }.joinToString { "${it.path} -> ${it.backup.path}" }.take(1_000))
                if (syncPending.isNotEmpty()) append("; synchronization pending: ").append(syncPending.distinct().joinToString())
            }
            val outcome = if (rollbackErrors.isEmpty() && uncertainDisk.isEmpty()) "rolled back"
                else "not fully rolled back; newer workspace data was preserved"
            throw IllegalStateException("LSP workspace edit $outcome: ${t.message}$suffix", t)
        }
        return output + AgentEditPersistence.save(documents, persistence, requireSaveCurrent)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun requireCurrent(edit: LspWorkspaceEdit) {
        check(edit.applicationGuard?.invoke() != false) {
            "Language-server analysis changed after the request. Re-run the action."
        }
    }

    private suspend fun prepare(title: String, edit: LspWorkspaceEdit): Prepared {
        requireCurrent(edit)
        require(edit.edits.isNotEmpty()) { "Language server returned no edits" }
        require(edit.fileCount in 1..32) { "Language server edit touches too many files (${edit.fileCount})" }
        val grouped = edit.edits.groupBy { it.path }
        val before = linkedMapOf<String, String>()
        val liveVersions = linkedMapOf<String, WorkspaceDocumentVersion>()
        val after = linkedMapOf<String, String>()
        var totalChars = 0L
        for ((path, edits) in grouped) {
            files.resolveChecked(path)
            val live = documents?.snapshot(path)?.takeIf { it.loaded }
            val current = if (live != null) {
                require(live.kind == WorkspaceDocumentKind.TEXT && live.editable) { "Workspace edit targets a non-editable live document: $path" }
                liveVersions[path] = live.version
                live.content
            } else {
                require(files.exists(path)) { "Workspace edit target does not exist: $path" }
                files.readTextForEdit(path)
            }
            edit.expectedContent[path]?.let { expected ->
                require(current == expected) { "Document changed since the LSP request: $path. Re-run the action." }
            }
            totalChars += current.length.toLong()
            require(totalChars <= 5_000_000L) { "Language server edit touches too much source text for one mobile transaction" }
            val updated = TextEditApplier.apply(current, edits.map { it.range })
            if (updated != current) {
                before[path] = current
                after[path] = updated
            }
        }
        require(after.isNotEmpty()) { "Language server edit has no effective text changes" }
        val perFile = (32_000 / after.size.coerceAtLeast(1)).coerceIn(256, AgentFileChanges.MAX_PREVIEW_BYTES)
        val changes = after.map { (path, updated) -> AgentFileChanges.preview(path, before.getValue(path), updated, limit = perFile) }
        val preview = changes.joinToString("\n") { it.diff }.take(12_000)
        val resource = "lsp-edit:${after.keys.sorted().joinToString(",")}:${sha256(preview)}"
        requireCurrent(edit)
        return Prepared(title, edit, before, liveVersions, after, preview, resource, changes)
    }
}
