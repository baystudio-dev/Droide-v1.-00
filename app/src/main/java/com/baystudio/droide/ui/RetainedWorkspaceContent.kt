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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Pinned Material3 predates default display-cutout protection; retain normal-mode defaults. */
internal fun expandedBrowserContentInsets(systemBars: WindowInsets, displayCutout: WindowInsets): WindowInsets =
    systemBars.union(displayCutout)

/** Retain pane owners; outgoing pixels may remain visible while input and accessibility are gated. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun RetainedWorkspaceContent(
    presented: Boolean,
    drawWhileHidden: Boolean = false,
    modifier: Modifier = Modifier.fillMaxSize(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalWorkspacePresented provides presented,
        LocalWorkspaceExitVisible provides (!presented && drawWhileHidden)) {
        Box(modifier
            .focusProperties { enter = { if (presented) FocusRequester.Default else FocusRequester.Cancel } }
            .focusGroup()
            .then(if (presented) Modifier else Modifier.clearAndSetSemantics { })
            .then(if (presented || !drawWhileHidden) Modifier else Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            })
            .layout { measurable, constraints ->
                val child = measurable.measure(constraints)
                layout(child.width, child.height) {
                    if (presented || drawWhileHidden) child.placeRelative(0, 0)
                }
            }) { content() }
    }
}
