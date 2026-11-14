package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// Transactional multi-file text patcher with full preflight validation and rollback.
object PatchTool {
    /** Same accepted UTF16 total as before, checked before adding another retained snapshot.
     * The just-read disk String may still exist transiently (per-file read limit unchanged).
     * This is not a bound on live-editor ownership, parser copies, or total process heap. */
    private class SnapshotBudget {
        private var retainedChars = 0L
        fun admit(text: String?) {
            val next = text?.length?.toLong() ?: 0L
            require(next <= 5_000_000L - retainedChars) {
                "Patch touches too much text for a safe mobile transaction"
            }
            retainedChars += next
        }
    }

    sealed interface Op {
        data class Add(val path: String, val content: String) : Op
        data class Update(
            val path: String, val search: String, val replace: String, val moveTo: String?,
            val hunks: List<CodexPatchHunk> = emptyList(), val canonical: Boolean = false,
        ) : Op
        data class Delete(val path: String) : Op
    }

    fun parse(patchText: String): List<Op> {
        require(patchText.length <= 2_000_000) { "Patch too large" }
        if (patchText.trimStart().startsWith("*** Begin Patch")) return CodexPatchParser.parse(patchText)
        val ops = mutableListOf<Op>()
        val lines = patchText.lines()
        var i = 0
        while (i < lines.size) {
            val h = lines[i].trim()
            when {
                h.startsWith("*** Add File:") -> {
                    val path = h.removePrefix("*** Add File:").trim()
                    require(path.isNotEmpty()) { "Add File tanpa path" }
                    val buf = StringBuilder(); i++
                    while (i < lines.size && !lines[i].startsWith("*** ")) { buf.appendLine(lines[i]); i++ }
                    ops += Op.Add(path, buf.toString().trimEnd('\n'))
                }
                h.startsWith("*** Update File:") -> {
                    val path = h.removePrefix("*** Update File:").trim(); require(path.isNotEmpty())
                    i++
                    var search: String? = null; var replace: String? = null; var moveTo: String? = null
                    while (i < lines.size && !lines[i].startsWith("*** Add File:") &&
                        !lines[i].startsWith("*** Update File:") && !lines[i].startsWith("*** Delete File:")) {
                        val l = lines[i]
                        when {
                            l.startsWith("*** Search:") -> {
                                val sb = StringBuilder(l.removePrefix("*** Search:").trimStart(' ')); i++
                                while (i < lines.size && !lines[i].startsWith("*** ")) { sb.appendLine(); sb.append(lines[i]); i++ }
                                search = sb.toString()
                            }
                            l.startsWith("*** Replace:") -> {
                                val sb = StringBuilder(l.removePrefix("*** Replace:").trimStart(' ')); i++
                                while (i < lines.size && !lines[i].startsWith("*** ")) { sb.appendLine(); sb.append(lines[i]); i++ }
                                replace = sb.toString()
                            }
                            l.startsWith("*** Move to:") -> { moveTo = l.removePrefix("*** Move to:").trim(); i++ }
                            l.isBlank() -> i++
                            else -> throw IllegalArgumentException("Baris tak dikenal di Update $path: $l")
                        }
                    }
                    require(!search.isNullOrEmpty() && replace != null) { "Update $path butuh Search + Replace" }
                    ops += Op.Update(path, search, replace, moveTo?.takeIf { it.isNotBlank() })
                }
                h.startsWith("*** Delete File:") -> {
                    val path = h.removePrefix("*** Delete File:").trim(); require(path.isNotEmpty())
                    ops += Op.Delete(path); i++
                }
                h.isEmpty() -> i++
                else -> throw IllegalArgumentException("Marker tak dikenal: $h")
            }
        }
        require(ops.isNotEmpty()) { "patchText kosong / tanpa operasi" }
        require(ops.size <= 100) { "Too many patch operations" }
        return ops
    }

    private suspend fun preflight(ops: List<Op>, files: FileRepository): Map<String, String?> {
        val touched = linkedSetOf<String>()
        val before = linkedMapOf<String, String?>()
        val budget = SnapshotBudget()
        suspend fun capture(path: String): String? {
            files.resolveChecked(path) 
            if (path !in before) {
                val value = if (files.exists(path)) files.readTextForEdit(path) else null
                budget.admit(value)
                before[path] = value
            }
            return before[path]
        }
        for (op in ops) when (op) {
            is Op.Add -> {
                require(touched.add(op.path)) { "Path touched more than once: ${op.path}" }
                require(capture(op.path) == null) { "Add target already exists: ${op.path}" }
            }
            is Op.Update -> {
                require(touched.add(op.path)) { "Path touched more than once: ${op.path}" }
                val old = capture(op.path) ?: throw IllegalArgumentException("Update target missing: ${op.path}")
                updatedText(old, op)
                op.moveTo?.let { dst ->
                    require(dst != op.path) { "Move destination equals source: $dst" }
                    require(touched.add(dst)) { "Path touched more than once: $dst" }
                    require(capture(dst) == null) { "Move destination already exists: $dst" }
                }
            }
            is Op.Delete -> {
                require(touched.add(op.path)) { "Path touched more than once: ${op.path}" }
                require(capture(op.path) != null) { "Delete target missing: ${op.path}" }
            }
        }
        require(before.values.filterNotNull().sumOf { it.length.toLong() } <= 5_000_000L) { "Patch touches too much text for a safe mobile transaction" }
        return before
    }

    // Applying later must match this snapshot.
    suspend fun reviewSnapshot(ops: List<Op>, files: FileRepository): Map<String, String?> = preflight(ops, files)

    fun previewReviewed(ops: List<Op>, before: Map<String, String?>): String = buildString {
        for (op in ops) when (op) {
            is Op.Add -> { appendLine("+++ ADD ${op.path}"); appendLine(op.content.take(3_000)) }
            is Op.Update -> {
                val old = before[op.path]
                if (old == null) {
                    appendLine("--- ERROR: File ${op.path} not found or is empty")
                    continue
                }
                val updated = updatedText(old, op)
                appendLine("--- UPDATE ${op.path}" + (op.moveTo?.let { " -> $it" } ?: ""))
                appendLine(DiffUtil.unified(old, updated))
            }
            is Op.Delete -> appendLine("--- DELETE ${op.path}")
        }
    }.take(12_000)

    suspend fun preview(ops: List<Op>, files: FileRepository): String =
        previewReviewed(ops, reviewSnapshot(ops, files))

    data class WorkspaceReview(
        val before: Map<String, String?>,
        val liveDocuments: Map<String, WorkspaceDocumentSnapshot>,
    )

    private suspend fun preflightWorkspace(
        ops: List<Op>,
        files: FileRepository,
        documents: WorkspaceDocumentAuthority?,
    ): WorkspaceReview {
        val touched = linkedSetOf<String>()
        val before = linkedMapOf<String, String?>()
        val budget = SnapshotBudget()
        val live = linkedMapOf<String, WorkspaceDocumentSnapshot>()

        suspend fun capture(path: String): String? {
            files.resolveChecked(path)
            if (path in before) return before[path]
            documents?.snapshot(path)?.takeIf { it.loaded }?.let { snapshot ->
                if (snapshot.kind == WorkspaceDocumentKind.TEXT) {
                    budget.admit(snapshot.content)
                    live[path] = snapshot
                    before[path] = snapshot.content
                    return snapshot.content
                }
                live[path] = snapshot
            }
            val value = if (files.exists(path)) files.readTextForEdit(path) else null
            budget.admit(value)
            before[path] = value
            return value
        }

        for (op in ops) when (op) {
            is Op.Add -> {
                require(touched.add(op.path)) { "Path touched more than once: ${op.path}" }
                val old = capture(op.path)
                require(old == null && op.path !in live) { "Add target already exists: ${op.path}" }
            }
            is Op.Update -> {
                require(touched.add(op.path)) { "Path touched more than once: ${op.path}" }
                val old = capture(op.path) ?: throw IllegalArgumentException("Update target missing: ${op.path}")
                updatedText(old, op)
                op.moveTo?.let { dst ->
                    require(op.path !in live) {
                        "Move in apply_patch requires the source to be closed in the editor: ${op.path}. " +
                            "Rename/move the path separately so live editor identity cannot be lost."
                    }
                    require(dst != op.path) { "Move destination equals source: $dst" }
                    require(touched.add(dst)) { "Path touched more than once: $dst" }
                    val destination = capture(dst)
                    require(destination == null && dst !in live) { "Move destination already exists: $dst" }
                }
            }
            is Op.Delete -> {
                require(touched.add(op.path)) { "Path touched more than once: ${op.path}" }
                require(capture(op.path) != null || op.path in live) { "Delete target missing: ${op.path}" }
                require(live[op.path]?.dirty != true) {
                    "Delete conflicts with unsaved editor buffer: ${op.path}. Save/discard it before deleting."
                }
            }
        }
        require(before.values.filterNotNull().sumOf { it.length.toLong() } <= 5_000_000L) {
            "Patch touches too much text for a safe mobile transaction"
        }
        return WorkspaceReview(before, live)
    }

    suspend fun reviewWorkspaceSnapshot(
        ops: List<Op>,
        files: FileRepository,
        documents: WorkspaceDocumentAuthority?,
    ): WorkspaceReview = preflightWorkspace(ops, files, documents)

    fun previewWorkspaceReviewed(ops: List<Op>, review: WorkspaceReview): String =
        previewReviewed(ops, review.before)

    suspend fun applyWorkspaceReviewed(
        ops: List<Op>,
        files: FileRepository,
        history: EditHistory,
        documents: WorkspaceDocumentAuthority?,
        lsp: LspManager,
        reviewed: WorkspaceReview,
        requireSaveCurrent: () -> Unit = {},
    ): String {
        val current = preflightWorkspace(ops, files, documents)
        require(sameReviewedWorkspaceMutation(current.before, current.liveDocuments, reviewed.before, reviewed.liveDocuments)) {
            "Patch target changed after review. Re-run the patch so the user can review the current editor/workspace state."
        }
        return applyPrepared(ops, files, history, reviewed.before, documents, lsp, reviewed.liveDocuments, requireSaveCurrent)
    }

    suspend fun applyReviewed(
        ops: List<Op>,
        files: FileRepository,
        history: EditHistory,
        reviewed: Map<String, String?>,
    ): String {
        val current = preflight(ops, files)
        require(current == reviewed) {
            "Patch target changed after review. Re-run the patch so the user can review the current file state."
        }
        return applyPrepared(ops, files, history, reviewed)
    }

    suspend fun apply(ops: List<Op>, files: FileRepository, history: EditHistory): String =
        applyPrepared(ops, files, history, preflight(ops, files))

    private suspend fun applyPrepared(
        ops: List<Op>,
        files: FileRepository,
        history: EditHistory,
        before: Map<String, String?>,
        documents: WorkspaceDocumentAuthority? = null,
        lsp: LspManager? = null,
        liveDocuments: Map<String, WorkspaceDocumentSnapshot> = emptyMap(),
        requireSaveCurrent: () -> Unit = {},
    ): String {
        val pending = history.prepareBatch("patch ${ops.size} operation(s)", before)
        data class AppliedLive(val path: String, val before: String, val transition: EditHistory.LiveTransition)
        val appliedLive = mutableListOf<AppliedLive>()
        val persistence = mutableListOf<WorkspaceDocumentMutationResult.Applied>()
        val appliedDisk = linkedMapOf<String, String?>()
        val uncertainDisk = linkedSetOf<String>()
        val syncPending = linkedSetOf<String>()

        suspend fun deleteObserved(path: String, expected: DiskFileVersion, settled: (Boolean) -> Unit) {
            val observation = files.prepareDeleteObservation(path, expected)
            try {
                check(files.deleteFileIfUnchanged(observation)) { "Delete failed: $path" }
            } catch (cancelled: CancellationException) {
                // A cancellation can arrive after the owned local move, including on
                // the IO dispatcher return. Account for that exact result before the
                // surrounding transaction rolls back; cancellation is never success.
                if (observation.ownedCurrent) settled(observation.synchronizationFailed)
                throw cancelled
            } catch (failure: Exception) {
                if (!observation.ownedCurrent || !observation.synchronizationFailed) throw failure
                settled(true)
                return
            }
            check(observation.ownedCurrent) {
                "Patch deletion publication was not verified for $path; recovery backup retained"
            }
            settled(false)
        }

        suspend fun write(path: String, content: String) {
            uncertainDisk += path
            try {
                files.writeTextIfUnchanged(path, content, DiskFileVersion.fromText(before[path]))
            } catch (saved: ProjectFileSyncException) {
                if (files.resolveChecked(saved.relativePath) != files.resolveChecked(path) ||
                    saved.bytes != content.toByteArray(Charsets.UTF_8).size.toLong()) throw saved
                // The reviewed write is already canonical. Do not replay it or undo it merely
                // because its provider/editor synchronization failed after publication.
                syncPending += path
            }
            appliedDisk[path] = content
            uncertainDisk -= path
        }
        suspend fun delete(path: String) {
            uncertainDisk += path
            deleteObserved(path, DiskFileVersion.fromText(before[path])) { pendingSync ->
                appliedDisk[path] = null
                uncertainDisk -= path
                if (pendingSync) syncPending += path
            }
        }

        val output = try {
            for (op in ops) when (op) {
                is Op.Add -> write(op.path, op.content)
                is Op.Update -> {
                    val old = before[op.path] ?: error("Update target missing: ${op.path}")
                    val updated = updatedText(old, op)
                    if (op.moveTo != null) {
                        // Each side has its own reviewed CAS token. A late source change must
                        // neither be moved over a destination nor deleted after it was copied.
                        write(op.moveTo, updated)
                        delete(op.path)
                    } else {
                        val live = liveDocuments[op.path]?.takeIf { it.kind == WorkspaceDocumentKind.TEXT && it.loaded }
                        if (live == null) write(op.path, updated)
                        else {
                            val authority = documents ?: error("Live editor authority disappeared for ${op.path}")
                            when (val result = authority.replaceText(op.path, live.version, updated, "Agent patch")) {
                                is WorkspaceDocumentMutationResult.Applied -> {
                                    persistence += result
                                    appliedLive += AppliedLive(op.path, old, EditHistory.LiveTransition.from(result))
                                    runSuspendCatching { lsp?.didChange(op.path, result.after.content) }
                                }
                                is WorkspaceDocumentMutationResult.Conflict -> error("Live document changed during patch: ${op.path}")
                                is WorkspaceDocumentMutationResult.ReadOnly -> error("Live document is read-only: ${op.path}")
                                WorkspaceDocumentMutationResult.NotLive -> error("Live document disappeared during patch: ${op.path}")
                            }
                        }
                    }
                }
                is Op.Delete -> delete(op.path)
            }
            history.commitBatch(pending, appliedLive.associate { it.path to it.transition }, appliedDisk)
            "OK: ${ops.size} operasi patch diterapkan secara transaksional" +
                if (syncPending.isEmpty()) "" else "; saved locally; synchronization pending: ${syncPending.joinToString()}"
        } catch (failure: Throwable) {
            val rollbackErrors = mutableListOf<String>()
            val remainingLive = appliedLive.associate { it.path to it.transition }.toMutableMap()
            val remainingDisk = appliedDisk.toMutableMap()
            var restored = 0
            var rollbackInterruption: Throwable? = null
            withContext(NonCancellable) {
                for (live in appliedLive.asReversed()) {
                    runSuspendCatching {
                        val authority = documents ?: error("Editor authority unavailable")
                        when (val result = authority.replaceText(live.path, live.transition.after, live.before, "Rollback Agent patch")) {
                            is WorkspaceDocumentMutationResult.Applied -> {
                                history.recordLiveRollback(pending, live.path, live.transition, result)
                                remainingLive.remove(live.path)
                                restored++
                                try { lsp?.didChange(live.path, result.after.content) }
                                catch (interrupted: CancellationException) { rollbackInterruption = interrupted }
                                catch (fatal: Error) { rollbackInterruption = fatal }
                                catch (notification: Exception) {
                                    rollbackErrors += "${live.path}: rollback LSP synchronization pending: ${notification.message}"
                                }
                            }
                            is WorkspaceDocumentMutationResult.Conflict -> error("User edited buffer during rollback")
                            is WorkspaceDocumentMutationResult.ReadOnly -> error("Buffer became read-only")
                            WorkspaceDocumentMutationResult.NotLive -> error("Buffer disappeared")
                        }
                    }.exceptionOrNull()?.let { rollbackErrors += "${live.path}: ${it.message ?: "rollback failed"}" }
                    if (rollbackInterruption != null) break
                }
                for ((path, after) in appliedDisk.entries.toList().asReversed()) {
                    if (rollbackInterruption != null) break
                    var completed = false
                    try {
                        val expected = DiskFileVersion.fromText(after)
                        val original = before[path]
                        if (original == null) {
                            deleteObserved(path, expected) { pendingSync ->
                                remainingDisk.remove(path)
                                restored++
                                completed = true
                                if (pendingSync) syncPending += path
                            }
                        } else {
                            try {
                                files.writeTextIfUnchanged(path, original, expected)
                            } catch (saved: ProjectFileSyncException) {
                                if (files.resolveChecked(saved.relativePath) != files.resolveChecked(path) ||
                                    saved.bytes != original.toByteArray(Charsets.UTF_8).size.toLong()) throw saved
                                syncPending += path
                            }
                            remainingDisk.remove(path)
                            restored++
                            completed = true
                        }
                    } catch (interrupted: CancellationException) {
                        rollbackInterruption = interrupted
                        if (!completed) rollbackErrors += "$path: ${interrupted.message ?: "rollback cancelled"}"
                    } catch (fatal: Error) {
                        rollbackInterruption = fatal
                        rollbackErrors += "$path: ${fatal.message ?: "rollback failed"}"
                    } catch (failure: Throwable) {
                        rollbackErrors += "$path: ${failure.message ?: "rollback failed"}"
                    }
                }
                // Preserve guarded undo for committed changes that could not be rolled back.
                // A failing/ambiguous operation retains its backup for manual recovery; it is
                // never assigned a fabricated successful-after token or blindly overwritten.
                val remaining = remainingLive.keys + remainingDisk.keys
                if (remaining.isNotEmpty()) history.commitBatch(
                    pending.copy(snapshots = pending.snapshots.filter { it.path in remaining }),
                    remainingLive, remainingDisk,
                )
                history.discardBatch(pending.copy(snapshots = pending.snapshots.filter {
                    it.path !in remaining && it.path !in uncertainDisk
                }))
            }
            rethrowLiveRollbackInterruption(failure, rollbackInterruption)
            val details = buildString {
                append("Patch failed: ").append(failure.message ?: failure::class.java.simpleName)
                append("; ").append(restored).append(" committed target(s) restored")
                if (rollbackErrors.isNotEmpty()) append("; partial rollback preserved newer state: ")
                    .append(rollbackErrors.joinToString(" | ").take(1_000))
                if (uncertainDisk.isNotEmpty()) append("; failed target state was not overwritten; recovery backups retained: ")
                    .append(pending.snapshots.filter { it.path in uncertainDisk }.joinToString { "${it.path} -> ${it.backup.name}" }.take(1_000))
                if (syncPending.isNotEmpty()) append("; synchronization pending: ").append(syncPending.joinToString())
            }
            throw IllegalStateException(details, failure)
        }
        return output + AgentEditPersistence.save(documents, persistence, requireSaveCurrent)
    }
    internal fun updatedText(text: String, op: Op.Update): String {
        if (op.canonical) return CodexPatchParser.apply(text, op.hunks, op.path)
        val count = countOccurrences(text, op.search)
        require(count == 1) { "Search in ${op.path} must match exactly once (found $count)" }
        return text.replaceFirst(op.search, op.replace)
    }

    private fun countOccurrences(text: String, needle: String): Int {
        require(needle.isNotEmpty()) { "Search must not be empty" }
        var count = 0
        var from = 0
        while (true) {
            val i = text.indexOf(needle, from)
            if (i < 0) return count
            count++
            from = i + needle.length
        }
    }

}
