package com.baystudio.droide.ui

import android.graphics.Rect
import android.view.ActionMode
import android.view.InputDevice
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View

/** Keeps native selection geometry and adds explicit actions for the complete link target. */
internal class NativeTerminalSelectionCallback(
    private val upstream: ActionMode.Callback,
    private val isCurrent: () -> Boolean,
    private val isSelecting: () -> Boolean,
    private val selectedText: () -> String?,
    private val copy: (String) -> Boolean,
    private val stopSelection: () -> Unit,
    private val selectedLink: () -> String? = { null },
    private val openLink: (String) -> Boolean = { false },
) : ActionMode.Callback2() {
    private var destroyed = false
    private var copied = false
    private var link: String? = null

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        if (destroyed || !isCurrent() || !upstream.onCreateActionMode(mode, menu)) return false
        menu.add(0, ACTION_COPY_LINK, 0, "Copy link")
        menu.add(0, ACTION_OPEN_LINK, 1, "Open link")
        updateLink(menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        if (destroyed || !isCurrent()) return false
        val changed = upstream.onPrepareActionMode(mode, menu)
        return updateLink(menu) || changed
    }

    private fun updateLink(menu: Menu): Boolean {
        val current = selectedLink()
        val changed = current != link
        link = current
        menu.findItem(ACTION_COPY_LINK)?.isVisible = current != null
        menu.findItem(ACTION_OPEN_LINK)?.isVisible = current != null
        return changed
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        // Stale menus must not copy, paste into a replacement session, or reopen its context menu.
        if (destroyed || !isCurrent() || !isSelecting()) return true
        if (item.itemId == ACTION_COPY_LINK || item.itemId == ACTION_OPEN_LINK) {
            val target = link?.takeIf { it == selectedLink() } ?: return true
            if (copied) return true
            val succeeded = if (item.itemId == ACTION_COPY_LINK) copy(target) else openLink(target)
            if (succeeded) { copied = true; stopSelection() }
            return true
        }
        if (item.itemId != PINNED_ACTION_COPY) return upstream.onActionItemClicked(mode, item)
        if (copied) return true
        val text = selectedText() ?: return true
        if (!copy(text)) return true
        copied = true
        // Preserve the controller's normal handles/copyModeChanged lifecycle (including its guard).
        stopSelection()
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        destroyed = true
        upstream.onDestroyActionMode(mode)
    }

    override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
        if (upstream is ActionMode.Callback2) upstream.onGetContentRect(mode, view, outRect)
        else super.onGetContentRect(mode, view, outRect)
    }

    companion object {
        internal const val ACTION_COPY_LINK = 0xD001
        internal const val ACTION_OPEN_LINK = 0xD002
        // TextSelectionCursorController in the unchanged terminal-view-v0.118.0.aar.
        private const val PINNED_ACTION_COPY = 1
    }
}

/**
 * Consume only the native middle-button paste route. Android can report the same press through
 * touch DOWN and generic BUTTON_PRESS, followed by many MOVE events; admit one paste in total.
 * Selection gestures, other buttons, hover and wheel events keep their existing dispatch.
 */
internal class NativeTerminalMiddleMousePaste {
    private var pressed = false
    private var touchOwned = false
    private var deviceId = -1
    private var downTime = Long.MIN_VALUE

    fun handle(event: MotionEvent, selecting: Boolean, paste: () -> Unit): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        val action = event.actionMasked
        if (action !in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP,
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE)) return false
        val middle = event.isButtonPressed(MotionEvent.BUTTON_TERTIARY)
        val changedMiddle = event.actionButton == MotionEvent.BUTTON_TERTIARY
        val matching = deviceId == event.deviceId
        if (action == MotionEvent.ACTION_CANCEL) {
            val consume = matching && touchOwned
            if (matching) { pressed = false; touchOwned = false }
            return consume && !selecting
        }
        if (action == MotionEvent.ACTION_UP) {
            val consume = matching && touchOwned || middle
            if (matching) { pressed = false; touchOwned = false }
            return consume && !selecting
        }
        if (action == MotionEvent.ACTION_BUTTON_RELEASE && changedMiddle) {
            val consume = matching && pressed
            if (matching) pressed = false
            return consume && !selecting
        }
        // Preserve upstream's right-button precedence and active selection gestures.
        if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) return false
        if (action == MotionEvent.ACTION_BUTTON_PRESS && !changedMiddle) return false
        if (!middle && !(action == MotionEvent.ACTION_BUTTON_PRESS && changedMiddle)) {
            if (action == MotionEvent.ACTION_DOWN && matching) {
                pressed = false
                touchOwned = false
            }
            return false
        }
        if (action == MotionEvent.ACTION_DOWN) {
            // A fresh DOWN retires any missing UP/CANCEL, but not its companion BUTTON_PRESS.
            if (!matching || downTime != event.downTime) pressed = false
            touchOwned = !selecting
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_BUTTON_PRESS) {
            val first = !matching || !pressed
            deviceId = event.deviceId
            downTime = event.downTime
            pressed = true
            if (first && !selecting) paste()
        }
        // A MOVE received without its DOWN must not initiate or replay a paste.
        return !selecting
    }
}
