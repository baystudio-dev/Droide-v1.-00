package com.baystudio.droide.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.compositionLocalOf

/** Presentation can change without disposing the retained workspace or its native owners. */
internal val LocalWorkspacePresented = compositionLocalOf { true }
internal val LocalWorkspaceExitVisible = compositionLocalOf { false }

/** Gates one AndroidView owner without detaching it or changing its editor/session state. */
internal class WorkspaceNativePresentationGate {
    private data class HiddenState(
        val view: View,
        val visibility: Int = view.visibility,
        val accessibility: Int = view.importantForAccessibility,
        val focusable: Int = view.focusable,
        val focusableInTouchMode: Boolean = view.isFocusableInTouchMode,
        val descendantFocusability: Int? = (view as? ViewGroup)?.descendantFocusability,
    )

    private var hidden: HiddenState? = null

    fun update(view: View, presented: Boolean, drawWhileHidden: Boolean = false) {
        if (presented) {
            val original = hidden?.takeIf { it.view === view } ?: return
            hidden = null
            view.importantForAccessibility = original.accessibility
            // Restore the integer focusability too, including FOCUSABLE_AUTO. Setting
            // touch-mode focusability first avoids its implicit FOCUSABLE side effect.
            view.isFocusableInTouchMode = original.focusableInTouchMode
            view.focusable = original.focusable
            original.descendantFocusability?.let { (view as ViewGroup).descendantFocusability = it }
            view.visibility = original.visibility
            return
        }

        // Repeated hidden updates must never snapshot the already-gated values. A
        // replacement AndroidView gets its own snapshot, never the previous view's.
        if (hidden?.view !== view) hidden = HiddenState(view)
        view.visibility = if (drawWhileHidden) requireNotNull(hidden).visibility else View.INVISIBLE
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        view.isFocusableInTouchMode = false
        view.isFocusable = false
        view.clearFocus()
    }

    /** Release owns its usual cleanup; forgetting must not restore or touch that view. */
    fun forget(view: View) {
        if (hidden?.view === view) hidden = null
    }
}
