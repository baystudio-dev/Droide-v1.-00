package com.termux.view;

/**
 * App-side bridge to the pinned v0.118.0 selection controller. No reflection or AAR change.
 * The coordinates are those used by the existing handles; this adds no second selection model.
 */
public final class DroideSelectionText {
    private DroideSelectionText() {}

    public static String getSelectedLink(TerminalView view) {
        if (view == null || view.mEmulator == null || !view.isSelectingText()) return null;
        int[] selectors = new int[4];
        view.getTextSelectionCursorController().getSelectors(selectors);
        com.baystudio.droide.core.TerminalLinks links = com.baystudio.droide.core.TerminalLinks.INSTANCE;
        String first = links.atCell(view.mEmulator, selectors[2], selectors[0], true);
        String last = links.atCell(view.mEmulator, selectors[3], selectors[1], true);
        return first != null && first.equals(last) ? first : null;
    }

    public static String getSelectedText(TerminalView view) {
        if (view == null || view.mEmulator == null || !view.isSelectingText()) return null;
        // Controller order is row start/end, column start/end, unlike the emulator arguments.
        int[] selectors = new int[4];
        view.getTextSelectionCursorController().getSelectors(selectors);
        // Preserve the rendered selection without the upstream menu's additional trim().
        // TerminalBuffer still omits trailing row spaces: this is not original process output.
        return view.mEmulator.getSelectedText(selectors[2], selectors[0], selectors[3], selectors[1]);
    }
}
