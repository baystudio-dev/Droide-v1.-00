package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baystudio.droide.core.AgentTextPresentation
import com.baystudio.droide.core.AgentMessage
import com.baystudio.droide.core.AgentStreamPart
import com.baystudio.droide.core.AgentStreamPartKind
import com.baystudio.droide.core.AgentStreamPartState
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentFontReadabilityTest {
    @get:Rule val compose = createComposeRule()
    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }

    @Test fun composerAndUserTextScaleWithAndroidWithoutChangingNativeAnswerTypography() {
        var scale by mutableFloatStateOf(1f)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, scale)) { DroideTheme {
            Column(Modifier.fillMaxWidth()) {
                AgentComposerInput("Draft", "font", emptyList(), { emptyList() }, { true }, {}, {},
                    "Ask Droide", 120.dp, false, {})
                AgentUserMessage("User message", emptyList(), File("."))
                AgentPublicTextContent("Native answer", AgentTextPresentation.ANSWER, "answer", emptyList())
                AgentPublicTextContent("CLI answer", AgentTextPresentation.RESPONSE, "cli", emptyList())
            }
        } } }
        assertEquals(16.sp, layout(compose.onNode(hasSetTextAction())).layoutInput.style.fontSize)
        val user = layout(compose.onNodeWithText("User message"))
        assertEquals(14.sp, user.layoutInput.style.fontSize)
        assertEquals(14.sp, layout(compose.onNodeWithText("Native answer")).layoutInput.style.fontSize)
        assertEquals(14.sp, layout(compose.onNodeWithText("CLI answer")).layoutInput.style.fontSize)
        compose.runOnIdle { scale = 2f }
        val enlarged = layout(compose.onNodeWithText("User message"))
        assertTrue(enlarged.size.height > user.size.height)
        assertFalse(enlarged.didOverflowHeight)
        assertFalse(layout(compose.onNode(hasSetTextAction())).didOverflowHeight)
    }

    @Test fun composerPlaceholderUsesTheSameReadableSizeAndHiddenPaneCannotAcceptInput() {
        var presented by mutableStateOf(true)
        compose.setContent { CompositionLocalProvider(LocalWorkspacePresented provides presented) { DroideTheme {
            AgentComposerInput("", "placeholder", emptyList(), { emptyList() }, { true }, {}, {},
                "Ask Droide", 120.dp, false, {})
        } } }
        assertEquals(16.sp, layout(compose.onNodeWithText("Ask Droide", useUnmergedTree = true)).layoutInput.style.fontSize)
        compose.onNode(hasSetTextAction()).performClick().assertIsFocused()
        compose.runOnIdle { presented = false }
        compose.onNodeWithText("Ask Droide").assertIsNotEnabled()
    }

    @Test fun oldExternalStreamHistoryGetsReadableTextWithoutRewritingTheSavedRecord() {
        val part = AgentStreamPart("old", 0, AgentStreamPartKind.TEXT, AgentStreamPartState.COMPLETED,
            text = "Old CLI response")
        val message = AgentMessage("assistant", "", streamPart = part)
        compose.setContent { DroideTheme {
            AgentTranscriptItem(message, false, File("."), legacyAssistantPresentation = AgentTextPresentation.RESPONSE)
        } }
        assertEquals(14.sp, layout(compose.onNodeWithText("Old CLI response")).layoutInput.style.fontSize)
        assertEquals(AgentTextPresentation.LEGACY, part.textPresentation)
        assertEquals("Old CLI response", part.text)
    }
}
