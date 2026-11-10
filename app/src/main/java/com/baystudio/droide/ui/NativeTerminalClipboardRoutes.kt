package com.baystudio.droide.ui

import android.graphics.Rect
import android.view.ActionMode
import android.view.InputDevice
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View

/** Keeps the pinned native selection menu and its Callback2 geometry; replaces only Copy. */
internal class NativeTerminalSelectionCallback(
    private val upstream: ActionMode.Callback,
    private val isCurrent: () -> Boolean,
    private val isSelecting: () -> Boolean,
    private val selectedText: () -> String?,
    private val copy: (String) -> Boolean,
    private val stopSelection: () -> Unit,
) : ActionMode.Callback2() {
    private var destroyed = false
    private var copied = false

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean =
        !destroyed && isCurrent() && upstream.onCreateActionMode(mode, menu)

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
        !destroyed && isCurrent() && upstream.onPrepareActionMode(mode, menu)

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        // Stale menus must not copy, paste into a replacement session, or reopen its context menu.
        if (destroyed || !isCurrent() || !isSelecting()) return true
        if (item.itemId != PINNED_ACTION_COPY) return upstream.onActionItemClicked(mode, item)
        if (copied) return true
        val text = selectedText() ?: return true
        if (!copy(text)) return true
        copied = true

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

        private const val PINNED_ACTION_COPY = 1
    }
}

/** Consume only the native middle-button paste route. */

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
