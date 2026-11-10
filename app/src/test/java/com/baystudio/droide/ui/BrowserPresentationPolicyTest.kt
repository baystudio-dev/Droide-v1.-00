package com.baystudio.droide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPresentationPolicyTest {
    @Test fun hiddenSearchDoesNotConsumeExpandedBackOrMutateRestoredPreference() {
        val savedSearch = true
        assertFalse(workspaceSearchConsumesBack(true, savedSearch))
        assertEquals(ExpandedBrowserBackAction.EXIT_EXPANDED, expandedBrowserBackAction(true, false))
        assertTrue(savedSearch)
    }
    @Test fun presentedSearchKeepsNormalBackBehavior() {
        assertTrue(workspaceSearchConsumesBack(false, true))
        assertFalse(workspaceSearchConsumesBack(false, false))
    }
    @Test fun expandedOverridesMain() = assertEquals(WorkspaceBrowserSlot.EXPANDED,
        selectedWorkspaceBrowserSlot(true, WorkspaceBrowserSlot.MAIN))
    @Test fun expandedOverridesSidebar() = assertEquals(WorkspaceBrowserSlot.EXPANDED,
        selectedWorkspaceBrowserSlot(true, WorkspaceBrowserSlot.SIDEBAR))
    @Test fun expandedOverridesSuppressedNormalSurface() = assertEquals(WorkspaceBrowserSlot.EXPANDED,
        selectedWorkspaceBrowserSlot(true, null))
    @Test fun exitRestoresMainSelection() = assertEquals(WorkspaceBrowserSlot.MAIN,
        selectedWorkspaceBrowserSlot(false, WorkspaceBrowserSlot.MAIN))
    @Test fun exitRestoresSidebarSelection() = assertEquals(WorkspaceBrowserSlot.SIDEBAR,
        selectedWorkspaceBrowserSlot(false, WorkspaceBrowserSlot.SIDEBAR))
    @Test fun normalHiddenRemainsHidden() = assertNull(selectedWorkspaceBrowserSlot(false, null))
    @Test fun repeatedTransitionsDoNotOverwriteOriginalSelection() {
        for (original in listOf(WorkspaceBrowserSlot.MAIN, WorkspaceBrowserSlot.SIDEBAR, null)) {
            repeat(100) {
                assertEquals(WorkspaceBrowserSlot.EXPANDED, selectedWorkspaceBrowserSlot(true, original))
                assertEquals(original, selectedWorkspaceBrowserSlot(false, original))
            }
        }
    }
    @Test fun backDismissesKeyboardBeforeExiting() = assertEquals(ExpandedBrowserBackAction.HIDE_KEYBOARD,
        expandedBrowserBackAction(true, true))
    @Test fun backExitsWhenKeyboardHidden() = assertEquals(ExpandedBrowserBackAction.EXIT_EXPANDED,
        expandedBrowserBackAction(true, false))
    @Test fun normalModeDoesNotCaptureBackWithKeyboard() = assertEquals(ExpandedBrowserBackAction.NONE,
        expandedBrowserBackAction(false, true))
    @Test fun normalModeDoesNotCaptureBackWithoutKeyboard() = assertEquals(ExpandedBrowserBackAction.NONE,
        expandedBrowserBackAction(false, false))
}
