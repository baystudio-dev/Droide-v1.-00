package com.termux.view;

/** App-side bridge to the pinned v0.118.0 selection controller. No reflection or AAR change. The
 * coordinates are those used by the existing handles; this adds no second selection model. */
public final class DroideSelectionText {
    private DroideSelectionText() {}

    public static String getSelectedText(TerminalView view) {
        if (view == null || view.mEmulator == null || !view.isSelectingText()) return null;

        int[] selectors = new int[4];
        view.getTextSelectionCursorController().getSelectors(selectors);
        // Preserve the rendered selection without the upstream menu's additional trim(). TerminalBuffer
        // still omits trailing row spaces: this is not original process output.
        return view.mEmulator.getSelectedText(selectors[2], selectors[0], selectors[3], selectors[1]);
    }
}
