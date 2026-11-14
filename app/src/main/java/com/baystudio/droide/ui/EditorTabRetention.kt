package com.baystudio.droide.ui

import com.baystudio.droide.core.EditorRecoverySnapshot
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.runSuspendCatching
import kotlinx.coroutines.CancellationException

 
internal object EditorTabRetention {
    const val MAX_TABS = 32

     
    fun open(
        tabs: List<String>,
        path: String,
        activePath: String,
        allowExistingDirtyOverflow: Boolean = false,
        isDirty: (String) -> Boolean,
    ): List<String>? {
        if (path in tabs) return tabs
        if (tabs.size < MAX_TABS) return tabs + path
        val evicted = tabs.firstOrNull { it != activePath && !isDirty(it) }
            ?: tabs.firstOrNull { !isDirty(it) }
            ?: return if (allowExistingDirtyOverflow && isDirty(path)) tabs + path else null
        return tabs.filterNot { it == evicted } + path
    }

    fun planWorkspaceEdit(
        tabs: List<String>, targets: Set<String>, activePath: String, isDirty: (String) -> Boolean,
    ): List<String> {
        var planned = tabs
        for (path in targets) {
            planned = open(planned, path, activePath) { it in targets || isDirty(it) }
                ?: error("Unsaved editor tabs are full. Save or close a tab before applying this workspace edit.")
        }
        return planned
    }

    fun closeEvicted(tabs: List<String>, next: List<String>, state: EditorWorkspaceState, onClose: (String) -> Unit) {
        tabs.filterNot { it in next }.forEach { path ->
            onClose(path)
            state.forgetAtOrUnder(path)
        }
    }

    suspend fun restoreDocuments(
        snapshot: EditorRecoverySnapshot,
        files: FileRepository,
        state: EditorWorkspaceState,
        admission: EditorWorkspaceState.RecoveryAdmission = state.beginRecovery(),
    ): List<String> {
        fun requireCurrent() {
            if (!state.isRecoveryCurrent(admission)) throw CancellationException("Editor recovery owner changed")
        }
        requireCurrent()
        state.retainRecoveryConflicts(snapshot)
        val recovered = mutableListOf<String>()
        for (path in (snapshot.openFiles + snapshot.dirtyPaths).distinct()) {
            val exists = runSuspendCatching { files.exists(path) }.getOrDefault(false)
            requireCurrent()
            if (!exists && path !in snapshot.dirtyPaths) continue
            recovered += path
        }
        val visible = restore(snapshot.openFiles, recovered, snapshot.dirtyPaths, snapshot.activeFile)
        for (path in visible) {
            requireCurrent()
            val current = state.peek(path)
            val deferred = snapshot.deferredBuffers[path]
            val dirtyText = snapshot.dirtyBuffers[path]
            fun preserveConflictingRecovery() {
                if (deferred != null && !sameRecoveryBuffer(current?.pendingRecoveryBuffer, deferred)) {
                    state.retainRecoveryConflict(path, null, deferred)
                } else if (dirtyText != null && (current?.dirty != true || current?.content != dirtyText ||
                        current?.savedDiskVersion != snapshot.diskVersions[path])) {
                    state.retainRecoveryConflict(path, dirtyText, null, snapshot.diskVersions[path])
                }
            }
            // A checkpoint can finish loading after the user has edited this file,
            // or the same deferred buffer can already be retained after rotation.
            // The live buffer wins; do not overwrite it or abort other recoveries.
            if (!state.canRestoreDocument(admission, path) || current?.dirty == true) {
                preserveConflictingRecovery()
                continue
            }
            val doc = state.document(path)
            if (deferred != null) doc.restoreDeferred(deferred, snapshot.selections[path], path in snapshot.reviewFiles)
            else if (path in snapshot.dirtyBuffers) {
                val changeVersion = doc.changeVersion
                val viewRevision = doc.viewStateRevision
                doc.ensureLoaded(files)
                requireCurrent()
                if (state.peek(path) !== doc || doc.dirty || doc.changeVersion != changeVersion || doc.viewStateRevision != viewRevision) {
                    preserveConflictingRecovery()
                    continue
                }
                doc.restoreUnsaved(snapshot.dirtyBuffers.getValue(path), snapshot.diskVersions[path])
                doc.restoreViewState(snapshot.selections[path], path in snapshot.reviewFiles)
            } else doc.restoreViewState(snapshot.selections[path], path in snapshot.reviewFiles)
        }
        return visible
    }

    /** Recovery is allowed to add unsaved documents, never to navigate over a newer user choice. */
    internal fun recoveryNavigation(
        state: EditorWorkspaceState,
        admission: EditorWorkspaceState.RecoveryAdmission,
        current: EditorWorkspaceState.Navigation,
        recovered: EditorRecoverySnapshot,
        visible: List<String>,
        recoveredActivePath: String,
    ): EditorWorkspaceState.Navigation {
        check(state.isRecoveryCurrent(admission)) { "Editor recovery owner changed" }
        val unchanged = state.navigationRevision == admission.navigationRevision && current == admission.navigation
        if (unchanged && visible.isNotEmpty()) return EditorWorkspaceState.Navigation(
            visible, recoveredActivePath, if (recoveredActivePath.isNotBlank()) 1 else 0,
        )
        if (unchanged) return current
        val dirty = state.dirtyDocuments().mapTo(linkedSetOf()) { it.path }
        val candidates = (current.openFiles + visible.filter { it in recovered.dirtyPaths && it in dirty } + dirty).distinct()
        val cleanSlots = (MAX_TABS - dirty.size).coerceAtLeast(if (current.activeFile in candidates && current.activeFile !in dirty) 1 else 0)
        val keepClean = (listOf(current.activeFile) + candidates).filter { it in candidates && it !in dirty }.distinct().take(cleanSlots).toSet()
        return current.copy(openFiles = candidates.filter { it in dirty || it in keepClean })
    }

    fun restore(
        openTabs: List<String>, recoveredPaths: List<String>, dirtyPaths: Set<String>, activePath: String = "",
    ): List<String> {
        val available = recoveredPaths.toSet()
        val dirty = recoveredPaths.filter { it in dirtyPaths }.distinct()
        val cleanSlots = (MAX_TABS - dirty.size).coerceAtLeast(0)
        val visible = (openTabs + dirty).filter { it in available }.distinct()
        val clean = visible.filterNot { it in dirtyPaths }
        val keptClean = (listOf(activePath).filter { it in clean } + clean).distinct().take(cleanSlots).toSet()
        // Exceptional legacy recovery can exceed 32 tabs; every unsaved buffer must stay visible.
        return visible.filter { it in dirtyPaths || it in keptClean }
    }
}
