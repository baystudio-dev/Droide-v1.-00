package com.baystudio.droide.ui

import android.content.Context
import android.util.Log
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView

/** Own the Android view until AndroidView has actually removed it from the hierarchy. */
@Composable
internal fun EditorAndroidSurface(
    modifier: Modifier = Modifier,
    createEditor: (Context) -> DroideCodeEditor,
    initializeEditor: (DroideCodeEditor) -> Unit,
    updateEditor: (DroideCodeEditor) -> Unit,
    onReleaseEditor: (DroideCodeEditor) -> Unit,
) {
    val workspacePresented = LocalWorkspacePresented.current
    val exitVisible = LocalWorkspaceExitVisible.current
    var failure by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val currentRelease by rememberUpdatedState(onReleaseEditor)
    fun reportFailure(error: Throwable) {
        Log.e("DroideEditor", "Editor surface failed", error)
        failure = error.message ?: error::class.java.simpleName
    }
    fun release(editor: DroideCodeEditor) {
        editor.onEditorFailure = null
        try {
            runUiCatching(onError = ::reportFailure) { currentRelease(editor) }
        } finally {
            runUiCatching(onError = ::reportFailure) { editor.release() }
        }
    }

    val error = failure
    if (error != null) {
        EditorLoadError("Editor could not display this file: $error", onRetry = {
            failure = null
            attempt++
        })
    } else key(attempt) {
        val presentationGate = remember { WorkspaceNativePresentationGate() }
        AndroidView(
            modifier = modifier.fillMaxSize().clipToBounds(),
            factory = { context ->
                FrameLayout(context).apply {
                    clipChildren = true
                    clipToPadding = true
                    // Gate the holder before creating/initializing its child: a
                    // retained hidden workspace must not acquire native input focus.
                    presentationGate.update(this, workspacePresented, exitVisible)
                    var editor: DroideCodeEditor? = null
                    val initialized = runUiCatching(onError = ::reportFailure) {
                        createEditor(context).also { created ->
                            editor = created
                            created.onEditorFailure = ::reportFailure
                            initializeEditor(created)
                            addView(created, FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                            ))
                        }
                    }
                    if (initialized == null) editor?.let(::release)
                }
            },
            onRelease = { holder ->
                presentationGate.forget(holder)
                val editor = holder.getChildAt(0) as? DroideCodeEditor
                holder.removeAllViews()
                editor?.let(::release)
            },
            update = { holder ->
                presentationGate.update(holder, workspacePresented, exitVisible)
                val editor = holder.getChildAt(0) as? DroideCodeEditor
                if (editor != null && !editor.isReleased) {
                    runUiCatching(onError = ::reportFailure) { updateEditor(editor) }
                }
            },
        )
    }
}
