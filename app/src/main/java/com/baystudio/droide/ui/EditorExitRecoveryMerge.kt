package com.baystudio.droide.ui

import com.baystudio.droide.core.DiskFileVersion
import com.baystudio.droide.core.EditorRecoveryBuffer
import com.baystudio.droide.core.EditorRecoverySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class EditorExitRecoveryMerge(
    val checkpoint: EditorRecoverySnapshot,
    val retained: EditorRecoverySnapshot?,
)

/** Exit does not need to finish mounting/restoring editors. */

internal suspend fun mergeUnrestoredExitRecovery(
    live: EditorRecoverySnapshot,
    previous: EditorRecoverySnapshot?,
): EditorExitRecoveryMerge = withContext(Dispatchers.Default) {
    if (previous == null) return@withContext EditorExitRecoveryMerge(live, null)
    val dirty = recoveryPayloads(previous, conflict = false).toMutableMap()
    val conflicts = recoveryPayloads(previous, conflict = true).toMutableMap()
    suspend fun retainConflict(path: String, payload: ExitRecoveryPayload) {
        val existing = conflicts[path]
        check(existing == null || existing.matches(payload)) {
            "Another recovery copy already exists for $path; previous recovery data was preserved"
        }
        if (existing == null) conflicts[path] = payload
    }
    for ((path, payload) in recoveryPayloads(live, conflict = true)) retainConflict(path, payload)
    for ((path, payload) in recoveryPayloads(live, conflict = false)) {
        val older = dirty[path]
        if (older != null && !older.matches(payload)) retainConflict(path, older)
        dirty[path] = payload
    }
    val open = (live.openFiles + previous.openFiles).distinct().take(32)
    val relevant = (open + dirty.keys).toSet()
    val checkpoint = live.copy(
        openFiles = open,
        activeFile = live.activeFile.ifEmpty { previous.activeFile },
        dirtyBuffers = dirty.texts(), deferredBuffers = dirty.references(), diskVersions = dirty.baselines(),
        conflictBuffers = conflicts.texts(), deferredConflictBuffers = conflicts.references(),
        conflictDiskVersions = conflicts.baselines(),
        selections = (previous.selections + live.selections).filterKeys { it in relevant },
        reviewFiles = (previous.reviewFiles + live.reviewFiles).filterTo(linkedSetOf()) { it in relevant },
    )
    val retained = checkpoint.copy(
        dirtyBuffers = checkpoint.dirtyBuffers - live.dirtyPaths,
        deferredBuffers = checkpoint.deferredBuffers - live.dirtyPaths,
        diskVersions = checkpoint.diskVersions - live.dirtyPaths,
    )
    EditorExitRecoveryMerge(checkpoint, retained)
}

private data class ExitRecoveryPayload(
    val text: String?,
    val reference: EditorRecoveryBuffer?,
    val diskVersion: DiskFileVersion?,
) {
    suspend fun matches(other: ExitRecoveryPayload): Boolean {
        if (diskVersion != other.diskVersion) return false
        if (text != null && other.text != null) return text == other.text
        if (reference != null && other.reference != null && sameRecoveryBuffer(reference, other.reference)) return true
        // Only colliding paths need bodies. Deferred files elsewhere remain lazy and keep their exact
        // store/blob ownership; never hydrate every recovery file on Exit.
        return (text ?: checkNotNull(reference).readText()) ==
            (other.text ?: checkNotNull(other.reference).readText())
    }
}

private fun recoveryPayloads(snapshot: EditorRecoverySnapshot, conflict: Boolean): Map<String, ExitRecoveryPayload> {
    val texts = if (conflict) snapshot.conflictBuffers else snapshot.dirtyBuffers
    val references = if (conflict) snapshot.deferredConflictBuffers else snapshot.deferredBuffers
    val versions = if (conflict) snapshot.conflictDiskVersions else snapshot.diskVersions
    return (texts.keys + references.keys).associateWith { path ->
        ExitRecoveryPayload(texts[path], references[path], versions[path] ?: references[path]?.diskVersion)
    }
}

private fun Map<String, ExitRecoveryPayload>.texts() = mapNotNull { (path, p) -> p.text?.let { path to it } }.toMap()
private fun Map<String, ExitRecoveryPayload>.references() = mapNotNull { (path, p) -> p.reference?.let { path to it } }.toMap()
private fun Map<String, ExitRecoveryPayload>.baselines() = mapNotNull { (path, p) -> p.diskVersion?.let { path to it } }.toMap()
