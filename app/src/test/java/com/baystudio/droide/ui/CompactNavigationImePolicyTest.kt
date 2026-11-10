package com.baystudio.droide.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactNavigationImePolicyTest {
    @Test fun hiddenEndpointImmediatelyReturnsNavigation() {
        assertFalse(compactNavigationHiddenForIme(true, false, false))
    }
    @Test fun nativeClosingPixelsKeepNavigationHiddenAfterRawVisibilityDrops() {
        assertTrue(compactNavigationHiddenForIme(true, false, true))
    }
    @Test fun floatingKeyboardUsesRawVisibilityWithoutBottomInsets() {
        assertTrue(compactNavigationHiddenForIme(true, true, false))
    }
    @Test fun shownDockedKeyboardKeepsNavigationHidden() {
        assertTrue(compactNavigationHiddenForIme(true, true, true))
    }
    @Test fun nonTypingTargetNeverHidesNavigationForIme() {
        for (raw in listOf(false, true)) for (bottom in listOf(false, true)) {
            assertFalse(compactNavigationHiddenForIme(false, raw, bottom))
        }
    }
    @Test fun rapidHideShowHasNoGraceOrStaleCycleState() {
        repeat(20) {
            assertTrue(compactNavigationHiddenForIme(true, true, true))
            assertTrue(compactNavigationHiddenForIme(true, false, true))
            assertFalse(compactNavigationHiddenForIme(true, false, false))
            assertTrue(compactNavigationHiddenForIme(true, true, false))
            assertTrue(compactNavigationHiddenForIme(true, true, true))
        }
    }
    @Test fun resizeAndRotationDoNotReleaseNavigationWhileImeStillOccupiesSpace() {
        for (raw in listOf(true, false, true)) {
            assertTrue(compactNavigationHiddenForIme(true, raw, true))
        }
    }
    @Test fun focusLossReleasesNavigationWithoutWaitingForIme() {
        assertTrue(compactNavigationHiddenForIme(true, true, true))
        assertFalse(compactNavigationHiddenForIme(false, true, true))
    }
    @Test fun staleRawVisibilityIsNotGuessedAway() {
        // This policy does not claim to recognize an OEM's stale true floating-IME signal.
        assertTrue(compactNavigationHiddenForIme(true, true, false))
    }
}
