package com.termux.view;

/** Exact pinned renderer formula without reflection, copied font metrics or a view-AAR change. */
public final class DroideViewportMetrics {
    private DroideViewportMetrics() {}
    public static int rows(TerminalRenderer renderer, int height) {
        if (renderer == null || renderer.mFontLineSpacing <= 0 || height <= 0) return 0;
        return Math.max(4, (height - renderer.mFontLineSpacingAndAscent) / renderer.mFontLineSpacing);
    }
}
