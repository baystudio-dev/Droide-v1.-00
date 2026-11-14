package com.baystudio.droide.ui

/**
 * The compact navigation returns at the observed hidden IME endpoint. The separate
 * orientation/pane-stability grace must not retain an invisible typing surface here.
 * A closing native IME still occupies space while its current inset is positive;
 * a floating IME instead relies on raw visibility. No duration or prediction is used.
 */
internal fun compactNavigationHiddenForIme(
    typingTarget: Boolean,
    rawImeVisible: Boolean,
    imeBottomPresent: Boolean,
): Boolean = typingTarget && (rawImeVisible || imeBottomPresent)
