package com.baystudio.droide.ui

/** The separate orientation/pane-stability grace must not retain an invisible typing surface
 * here. */
internal fun compactNavigationHiddenForIme(
    typingTarget: Boolean,
    rawImeVisible: Boolean,
    imeBottomPresent: Boolean,
): Boolean = typingTarget && (rawImeVisible || imeBottomPresent)
