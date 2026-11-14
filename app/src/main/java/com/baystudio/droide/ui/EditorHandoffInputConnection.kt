package com.baystudio.droide.ui

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.TextAttribute
import androidx.annotation.RequiresApi

/**
 * External IME gate only. Sora keeps its real EditorInputConnection internally.
 * In particular, reject keys before BaseInputConnection can route them through IMM
 * to another focused ViewRoot. Keys already forwarded before admission cannot be recalled.
 */
internal fun editorHandoffInputConnection(
    editor: DroideCodeEditor,
    delegate: InputConnection,
): InputConnection = when {
    Build.VERSION.SDK_INT >= 34 -> EditorHandoffInputConnection34(editor, delegate)
    Build.VERSION.SDK_INT >= 33 -> EditorHandoffInputConnection33(editor, delegate)
    Build.VERSION.SDK_INT >= 31 -> EditorHandoffInputConnection31(editor, delegate)
    else -> EditorHandoffInputConnection(editor, delegate)
}

private open class EditorHandoffInputConnection(
    protected val editor: DroideCodeEditor,
    delegate: InputConnection,
) : InputConnectionWrapper(delegate, false) {
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
        editor.acceptsMutations && super.commitText(text, newCursorPosition)

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
        editor.acceptsMutations && super.setComposingText(text, newCursorPosition)

    override fun setComposingRegion(start: Int, end: Int): Boolean =
        editor.acceptsMutations && super.setComposingRegion(start, end)

    override fun finishComposingText(): Boolean =
        editor.acceptsMutations && super.finishComposingText()

    override fun setSelection(start: Int, end: Int): Boolean =
        editor.acceptsMutations && super.setSelection(start, end)

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        editor.acceptsMutations && super.deleteSurroundingText(beforeLength, afterLength)

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        editor.acceptsMutations && super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)

    override fun commitCompletion(text: CompletionInfo?): Boolean =
        editor.acceptsMutations && super.commitCompletion(text)

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean =
        editor.acceptsMutations && super.commitCorrection(correctionInfo)

    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean =
        editor.acceptsMutations && super.commitContent(inputContentInfo, flags, opts)

    override fun beginBatchEdit(): Boolean = editor.acceptsMutations && super.beginBatchEdit()

    override fun endBatchEdit(): Boolean = editor.acceptsMutations && super.endBatchEdit()

    override fun sendKeyEvent(event: KeyEvent?): Boolean =
        editor.acceptsMutations && super.sendKeyEvent(event)

    override fun performEditorAction(editorAction: Int): Boolean =
        editor.acceptsMutations && super.performEditorAction(editorAction)

    override fun performContextMenuAction(id: Int): Boolean =
        editor.acceptsMutations && super.performContextMenuAction(id)

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean =
        editor.acceptsMutations && super.performPrivateCommand(action, data)

    override fun clearMetaKeyStates(states: Int): Boolean =
        editor.acceptsMutations && super.clearMetaKeyStates(states)

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean =
        editor.acceptsFeatureUpdates && super.requestCursorUpdates(cursorUpdateMode)

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
        if (editor.acceptsFeatureUpdates) super.getExtractedText(request, flags) else null

    override fun closeConnection() {
        // Admission already finished composition and refused undrained batches. A late
        // close must not reset the real delegate while the exact lease is tentative.
        if (editor.acceptsMutations) super.closeConnection()
    }
}

@RequiresApi(31)
private open class EditorHandoffInputConnection31(
    editor: DroideCodeEditor,
    delegate: InputConnection,
) : EditorHandoffInputConnection(editor, delegate) {
    override fun setImeConsumesInput(imeConsumesInput: Boolean): Boolean =
        editor.acceptsFeatureUpdates && super.setImeConsumesInput(imeConsumesInput)

    override fun performSpellCheck(): Boolean =
        editor.acceptsFeatureUpdates && super.performSpellCheck()
}

// New parameter types stay out of the minSdk29 class. InputConnectionWrapper forwards
// these overloads directly to its target, bypassing our old-method overrides.
@RequiresApi(33)
private open class EditorHandoffInputConnection33(
    editor: DroideCodeEditor,
    delegate: InputConnection,
) : EditorHandoffInputConnection31(editor, delegate) {
    override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
        editor.acceptsMutations && super.commitText(text, newCursorPosition, textAttribute)

    override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
        editor.acceptsMutations && super.setComposingText(text, newCursorPosition, textAttribute)

    override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?): Boolean =
        editor.acceptsMutations && super.setComposingRegion(start, end, textAttribute)

    override fun requestCursorUpdates(cursorUpdateMode: Int, cursorUpdateFilter: Int): Boolean =
        editor.acceptsFeatureUpdates && super.requestCursorUpdates(cursorUpdateMode, cursorUpdateFilter)
}

@RequiresApi(34)
private class EditorHandoffInputConnection34(
    editor: DroideCodeEditor,
    delegate: InputConnection,
) : EditorHandoffInputConnection33(editor, delegate) {
    override fun replaceText(
        start: Int,
        end: Int,
        text: CharSequence,
        newCursorPosition: Int,
        textAttribute: TextAttribute?,
    ): Boolean = editor.acceptsMutations && super.replaceText(start, end, text, newCursorPosition, textAttribute)

    // Pinned Sora 0.24.6 and BaseInputConnection do not implement handwriting or
    // text-bounds operations. InputConnection's defaults report unsupported (preview
    // returns false); they do not mutate the editor. Reads/snapshots remain delegated.
    // This gate is not a contract for arbitrary delegates or retained raw Content.
}
