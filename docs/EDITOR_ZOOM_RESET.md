# Reset editor zoom

The editor toolbar has a small **Reset editor zoom** icon immediately after Workspace
Symbols. It uses the existing 34 dp control slot/20 dp icon and toolbar height. In narrow
panes it follows the toolbar's width limits (with extra room reserved for Markdown controls).
It is offered for a loaded text editor, including Markdown editor/split mode, not binary,
image, document or Markdown-only previews.

The action belongs to the current `ActiveEditorBridge` and native editor binding. It restores
`DefaultEditorTextSizeSp` (13 sp), respecting Android accessibility font scaling. It does not
write text, reset selection, replace the editor, request focus, show/hide the keyboard, alter
undo history, save the document or change the zoom of other tabs.

Changing font size necessarily changes pixel coordinates. `DroideCodeEditor` therefore
captures the logical character at the top reading edge plus fractional row/character offsets.
After Sora recomputes its layout, the same reading anchor is restored, including wrapped rows.
Offsets are clamped to valid document bounds; when a shorter rendered document no longer
fills the viewport, its boundary is the limit. The caret is deliberately not scrolled into
view. Reset at the default size is a no-op.

The optional anchor travels in the existing in-memory `EditorViewportSnapshot` if a tab is
switched while reset reflow is still pending. Normal viewport captures and tab restores keep
their existing pixel-offset behavior. Deferred caret reveal is fenced by the existing viewport
generation. A reset requested while Sora is already reflowing is queued on that view until its
layout-ready event. New direct editor input or view retirement cancels the queued request.
No timer, polling loop or new background producer is added to the app.

Implementation was checked against the bundled Sora 0.24.6 source (`CodeEditor.setTextSize`,
`setLayoutBusy`, layout coordinate APIs and pinch scaling). Native layout reconstruction remains
Sora's responsibility; Droide only retains/restores the reading position for this action.

Host validation covers real Compose toolbar geometry/click routing, zoom in/out reset, reading
anchor and selection preservation, undo, word wrap, tab restoration, already-default behavior,
font scaling, native editor scrolling/rendering and lifecycle boundaries. APK/device verification
is separate; host results do not certify physical keyboard/IME or OEM rendering behavior.

The word-wrap host test uses a test-only shadow for Sora's `CharSequenceIterator` boundary
contract. Its `previous()` returns the first character at the beginning instead of `DONE`,
which loops inside the host JDK line breaker (before any reset is requested). Android uses a
different line-breaker implementation. The shadow is scoped to this one host test; no Sora
library or runtime editor behavior is patched. Real Sora wrapping and font reflow are tested.
