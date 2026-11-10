package com.baystudio.droide.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.baystudio.droide.core.LspManager
import com.baystudio.droide.core.runSuspendCatching
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Pane disposal leaves workspace documents open. Only an ineligible document closes LSP sync. */
@Composable
internal fun EditorLspDocumentEffect(document: EditorDocument, path: String, lsp: LspManager) {
    var lastVersion by remember(document, path, lsp) { mutableStateOf<Pair<Int, Int>?>(null) }
    var mayBeOpen by remember(document, path, lsp) { mutableStateOf(false) }
    LaunchedEffect(document, document.revision, document.changeVersion, document.loaded,
        document.kind, document.performanceMode, lsp, path) {
        val enabled = document.fullIntelligence
        val version = document.revision to document.changeVersion
        if (enabled && lastVersion == version) return@LaunchedEffect
        val action = editorLspTransition(mayBeOpen, enabled, path, document.content)
        if (action != null) {
            // Managers may register text before suspending to notify a server. Remember that ownership even
            // when recomposition cancels the first open midway.
            if (enabled) mayBeOpen = true
            lastVersion = null
            runSuspendCatching {
                lsp.applyEditorLspAction(action)
                currentCoroutineContext().ensureActive()
            }.onSuccess {
                mayBeOpen = enabled
                lastVersion = if (enabled) version else null
            }
        }
    }
}

internal sealed interface EditorLspAction {
    data class Open(val path: String, val content: String) : EditorLspAction
    data class Change(val path: String, val content: String) : EditorLspAction
    data class Close(val path: String) : EditorLspAction
}

/** Both persisted mutations and live editing must retire LSP state when sync becomes disabled. */
internal fun editorLspTransition(
    wasEnabled: Boolean,
    enabled: Boolean,
    path: String,
    content: String,
): EditorLspAction? = when {
    wasEnabled && enabled -> EditorLspAction.Change(path, content)
    wasEnabled -> EditorLspAction.Close(path)
    enabled -> EditorLspAction.Open(path, content)
    else -> null
}

internal suspend fun LspManager.applyEditorLspAction(action: EditorLspAction) {
    when (action) {
        is EditorLspAction.Open -> didOpen(action.path, action.content)
        is EditorLspAction.Change -> didChange(action.path, action.content)
        is EditorLspAction.Close -> didClose(action.path)
    }
}
