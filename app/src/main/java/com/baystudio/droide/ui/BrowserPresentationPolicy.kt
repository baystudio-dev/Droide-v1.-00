package com.baystudio.droide.ui

/** Presentation only: these decisions never own a renderer, session, or Keep Active setting. */
internal enum class WorkspaceBrowserSlot { MAIN, SIDEBAR, EXPANDED }

internal fun selectedWorkspaceBrowserSlot(
    browserExpanded: Boolean,
    normalSlot: WorkspaceBrowserSlot?,
): WorkspaceBrowserSlot? = if (browserExpanded) WorkspaceBrowserSlot.EXPANDED else normalSlot

internal enum class ExpandedBrowserBackAction { NONE, HIDE_KEYBOARD, EXIT_EXPANDED }

/** Search is a retained normal pane, not a modal above the expanded browser. */
internal fun workspaceSearchConsumesBack(browserExpanded: Boolean, showSearch: Boolean): Boolean =
    showSearch && !browserExpanded

internal fun expandedBrowserBackAction(expanded: Boolean, imeVisible: Boolean): ExpandedBrowserBackAction = when {
    !expanded -> ExpandedBrowserBackAction.NONE
    imeVisible -> ExpandedBrowserBackAction.HIDE_KEYBOARD
    else -> ExpandedBrowserBackAction.EXIT_EXPANDED
}
