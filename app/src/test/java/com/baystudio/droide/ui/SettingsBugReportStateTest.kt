package com.baystudio.droide.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.baystudio.droide.core.BugReportCategory
import com.baystudio.droide.core.BugReportDraft
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Authored restoration checks; no transport setup is permitted without a registered app owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsBugReportStateTest {
    @Test fun restoredInterruptedSubmissionIsUncertainAndNeverResubmits() {
        val saved = SavedStateHandle(mapOf("sending" to true, "visible" to true, "title" to "Retained report", "category" to "EDITOR"))
        val model = SettingsBugReportViewModel(saved)
        try {
            assertTrue(model.visible)
            assertFalse(model.sending)
            assertFalse(model.ready)
            assertEquals("Retained report", model.draft.title)
            assertEquals(BugReportCategory.EDITOR, model.draft.category)
            assertEquals(SettingsBugReportViewModel.UNKNOWN_MESSAGE, model.notice)
            assertEquals(true, saved.get<Boolean>("unknown"))
            assertEquals(false, saved.get<Boolean>("sending"))
        } finally { model.release() }
    }
    @Test fun editingSavesOnlyExplicitBoundedFormFields() {
        val saved = SavedStateHandle()
        val model = SettingsBugReportViewModel(saved)
        try {
            model.edit(BugReportDraft(title = "My typed report", email = "me@example.com"))
            assertEquals("My typed report", saved.get<String>("title"))
            assertEquals("me@example.com", saved.get<String>("email"))
            assertEquals("", saved.get<String>("device"))
            assertFalse(saved.keys().any { it.contains("token") || it.contains("cookie") || it.contains("workspace") })
        } finally { model.release() }
    }
    @Test fun closeKeepsDraftAndDiscardClearsIt() {
        val model = SettingsBugReportViewModel(SavedStateHandle())
        try {
            model.edit(BugReportDraft(title = "Keep this draft"))
            model.closeKeepingDraft()
            assertFalse(model.visible)
            assertEquals("Keep this draft", model.draft.title)
            model.discardAndClose()
            assertEquals(BugReportDraft(), model.draft)
        } finally { model.release() }
    }
    @Test fun missingWorkOwnerCannotStartNetworking() {
        val model = SettingsBugReportViewModel(SavedStateHandle())
        try {
            model.open()
            assertTrue(model.visible)
            assertFalse(model.preparing)
            assertFalse(model.ready)
            assertFalse(model.sending)
            assertTrue(model.notice.contains("stopped"))
        } finally { model.release() }
    }
}
