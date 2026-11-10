package com.baystudio.droide.ui

import android.os.SystemClock
import android.text.Editable
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection

/** Owns the IME's temporary editable, never a copy of terminal screen/history. */

internal class NativeTerminalInputConnection(
    target: View,
    private val lease: TerminalImeOwner.Lease,
    private val commitToTerminal: (CharSequence) -> Boolean,
    private val dispatchToTerminal: (KeyEvent) -> Boolean,
) : BaseInputConnection(target, true) {
    // Also fences newer BaseInputConnection editing overloads against a retired owner.
    override fun getEditable(): Editable? = if (lease.isCurrent()) super.getEditable() else null

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text == null || !lease.isCurrent()) return false
        if (!super.commitText(text, newCursorPosition)) return false
        return flushEditable()
    }

    override fun finishComposingText(): Boolean {
        if (!lease.isCurrent()) return false
        if (!super.finishComposingText()) return false
        return flushEditable()
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
        lease.deliver { super.setComposingText(text, newCursorPosition) }

    override fun setComposingRegion(start: Int, end: Int): Boolean =
        lease.deliver { super.setComposingRegion(start, end) }

    override fun setSelection(start: Int, end: Int): Boolean =
        lease.deliver { super.setSelection(start, end) }

    override fun performEditorAction(actionCode: Int): Boolean {
        if (!finishComposingText()) return false
        return sendPair(KeyEvent.KEYCODE_ENTER)
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
            !finishComposingText()) return false
        return lease.deliver { dispatchToTerminal(event) }
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength < 0 || afterLength < 0 || !lease.isCurrent()) return false
        // Pending local composition belongs to Android's editable, not the PTY.
        if (!editable.isNullOrEmpty()) return super.deleteSurroundingText(beforeLength, afterLength)
        return deleteTerminalKeys(beforeLength, afterLength)
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength < 0 || afterLength < 0 || !lease.isCurrent()) return false
        if (!editable.isNullOrEmpty()) return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        return deleteTerminalKeys(beforeLength, afterLength)
    }

    override fun closeConnection() {
        // Base close calls finishComposingText: revoke first so late cleanup cannot write.
        lease.close()
        super.getEditable()?.clear()
        super.closeConnection()
    }

    private fun flushEditable(): Boolean {
        val text = editable?.toString().orEmpty()
        if (text.isEmpty()) return lease.isCurrent()
        val delivered = lease.deliver { commitToTerminal(text) }
        if (delivered) editable?.clear()
        return delivered
    }

    private fun deleteTerminalKeys(before: Int, after: Int): Boolean =
        lease.deleteKeys(before, after, { sendPair(KeyEvent.KEYCODE_DEL) }, { sendPair(KeyEvent.KEYCODE_FORWARD_DEL) })

    private fun sendPair(keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD)
        val accepted = sendKeyEvent(down)
        // UP is cleanup/modifier release, never a second textual key press.
        if (lease.isCurrent()) sendKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
        return accepted
    }
}
