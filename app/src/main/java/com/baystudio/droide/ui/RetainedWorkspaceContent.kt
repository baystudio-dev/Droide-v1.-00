package com.baystudio.droide.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Pinned Material3 predates default display-cutout protection; retain normal-mode defaults. */
internal fun expandedBrowserContentInsets(systemBars: WindowInsets, displayCutout: WindowInsets): WindowInsets =
    systemBars.union(displayCutout)

/** Keep pane state and UI coroutine owners alive while the browser occupies their rectangle. */

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun RetainedWorkspaceContent(presented: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWorkspacePresented provides presented) {
        Box(Modifier.fillMaxSize()
            .focusProperties { enter = { if (presented) FocusRequester.Default else FocusRequester.Cancel } }
            .focusGroup()
            .then(if (presented) Modifier else Modifier.clearAndSetSemantics { })
            .layout { measurable, constraints ->
                val child = measurable.measure(constraints)
                layout(child.width, child.height) {
                    if (presented) child.placeRelative(0, 0)
                }
            }) { content() }
    }
}
