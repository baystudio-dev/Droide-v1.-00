package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Authored UI suite. No networking; execution requires the real Android/Compose test lane. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsBugReportFormR75Test {
    @get:Rule val compose = createComposeRule()
    private val draft = mutableStateOf(BugReportDraft())
    private val sending = mutableStateOf(false)
    private val ready = mutableStateOf(true)
    private val accepted = mutableStateOf(false)
    private val preparing = mutableStateOf(false)
    private val notice = mutableStateOf("")
    private val errors = mutableStateOf(emptyMap<BugReportField, String>())
    private var sends = 0
    private var cancels = 0
    private var prepares = 0
    private var closes = 0
    private var privacyOpens = 0

    private fun mount(width: Int = 360, height: Int = 640, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                DroideTheme {
                    Box(Modifier.size(width.dp, height.dp)) {
                        SettingsBugReportForm(draft.value, errors.value, preparing.value, sending.value,
                            ready.value, accepted.value, notice.value, onEdit = { draft.value = it },
                            onSend = { sends++ }, onCancel = { cancels++ }, onPrepare = { prepares++ },
                            onClose = { closes++ }, onOpenPrivacy = { privacyOpens++ })
                    }
                }
            }
        }
    }

    @Test fun mountDoesNotSendAndKeepsRecipientHeaderConcise() {
        mount()
        compose.onNodeWithText("To BayStudio support").assertExists()
        compose.onNodeWithText("supportbaystudio@gmail.com").assertDoesNotExist()
        compose.onNodeWithText("Reports use droide.rekacipta.biz.id", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, sends); assertEquals(0, privacyOpens); assertEquals(BugReportDraft(), draft.value) }
    }
    @Test fun editingRequiredTitleUpdatesOnlyUserField() {
        mount()
        compose.onNodeWithText("Issue title *").performScrollTo().performTextInput("Editor freezes")
        compose.runOnIdle { assertEquals("Editor freezes", draft.value.title); assertEquals("", draft.value.device); assertEquals("", draft.value.email) }
    }
    @Test fun maximumTitleLengthDoesNotSilentlyTruncateAnOversizedReplacement() {
        mount()
        compose.onNodeWithText("Issue title *").performScrollTo().performTextReplacement("x".repeat(121))
        compose.runOnIdle { assertEquals("", draft.value.title) }
    }
    @Test fun categoryAndPriorityAreExplicitUserChoices() {
        mount()
        compose.onNodeWithText("Choose an area").performScrollTo().performClick()
        compose.onNodeWithText("Terminal / Linux").performClick()
        compose.onNodeWithText("Normal").performScrollTo().performClick()
        compose.onNodeWithText("Blocks my work").performClick()
        compose.runOnIdle { assertEquals(BugReportCategory.TERMINAL, draft.value.category); assertEquals(BugReportPriority.BLOCKING, draft.value.priority) }
    }
    @Test fun consentStartsUncheckedAndOnlyChangesWhenTapped() {
        mount()
        compose.onNode(isToggleable()).performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(draft.value.privacyAcknowledged); assertEquals(0, sends) }
    }
    @Test fun preparationBlocksSendAndShowsProgress() {
        preparing.value = true; ready.value = false
        mount()
        compose.onNodeWithText("Preparing secure form…").assertExists()
        compose.onNodeWithText("Send bug report").performScrollTo().assertIsNotEnabled()
    }
    @Test fun unavailableFormRequiresManualPreparation() {
        ready.value = false
        mount()
        compose.onNodeWithText("Prepare secure form").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, prepares); assertEquals(0, sends) }
    }
    @Test fun sendingDisablesFieldsAndExposesCancellation() {
        sending.value = true
        mount()
        compose.onNodeWithText("Issue title *").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Send bug report").assertDoesNotExist()
        compose.onNodeWithText("Cancel request").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, cancels); assertEquals(0, sends) }
    }
    @Test fun failedStatusDoesNotClearDraft() {
        draft.value = BugReportDraft(title = "Keep my report")
        notice.value = SettingsBugReportViewModel.UNKNOWN_MESSAGE
        mount()
        compose.onNodeWithText(SettingsBugReportViewModel.UNKNOWN_MESSAGE).assertExists()
        compose.runOnIdle { assertEquals("Keep my report", draft.value.title); assertEquals(0, sends) }
    }
    @Test fun validationErrorsAreVisibleBesideTheAffectedFields() {
        errors.value = mapOf(BugReportField.TITLE to "Use 8–120 characters.")
        mount()
        compose.onNodeWithText("Use 8–120 characters.").performScrollTo().assertIsDisplayed()
    }
    @Test fun acceptedStateHasNoSecondSendAndDoesNotClaimMailboxDelivery() {
        accepted.value = true
        notice.value = "BayStudio's server accepted the report for its email service. Mailbox delivery has not been confirmed."
        mount()
        compose.onNodeWithText(notice.value).assertExists()
        compose.onNodeWithText("Send bug report").assertDoesNotExist()
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { assertEquals(1, closes); assertEquals(0, sends) }
    }
    @Test fun compactLandscapeAndLargeFontKeepSendReachable() {
        mount(width = 640, height = 360, fontScale = 1.5f)
        compose.onNodeWithText("Send bug report").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Close bug report").assertIsDisplayed()
    }
    @Test fun privacyOpensOnlyOnExplicitTap() {
        mount()
        compose.runOnIdle { assertEquals(0, privacyOpens) }
        compose.onNodeWithText("Read website Privacy Policy (opens browser)").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, privacyOpens); assertEquals(0, sends) }
    }
}
