package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalLinkTextTest {
    @get:Rule val compose = createComposeRule()

    private fun tapCharacter(text: String, index: Int) {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithText(text)
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val position = layouts.single().getBoundingBox(index).center
        node.performTouchInput { click(position) }
    }

    @Test fun wrappedPipeOutputOpensExactUrlAndUsesUpdatedOutputAfterRecomposition() {
        val first = "https://accounts.example.test/authorize?scope=openid%20email&state=A%2BB%2FC%3D"
        val second = "http://localhost:3000/callback?code=Second_42&state=unchanged"
        var output by mutableStateOf("Authorize: $first\nEnter code:")
        compose.setContent { MaterialTheme { Box(Modifier.width(240.dp)) { TerminalLinkText(output) } } }
        tapCharacter(output, output.indexOf("state=") + 8)
        val app = shadowOf(RuntimeEnvironment.getApplication())
        assertEquals(first, app.nextStartedActivity.dataString)
        compose.runOnIdle { output = "Ready: $second\n" }
        tapCharacter(output, output.indexOf("callback") + 2)
        assertEquals(second, app.nextStartedActivity.dataString)
        assertNull(app.nextStartedActivity)
    }

    @Test fun blankSpaceBesideUrlDoesNotActivateItsNearestCharacter() {
        val text = "https://example.test/a"
        compose.setContent { MaterialTheme { TerminalLinkText(text, Modifier.width(340.dp)) } }
        compose.onNodeWithText(text).performTouchInput { click(Offset(width - 2f, 5f)) }
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test fun draggingOutputScrollsWithoutOpeningUrl() {
        var scrollPosition = -1
        val text = ("https://example.test/path\n" + "ordinary output\n").repeat(40)
        compose.setContent { MaterialTheme {
            val scroll = rememberScrollState()
            val position = scroll.value
            SideEffect { scrollPosition = position }
            Box(Modifier.width(300.dp).height(160.dp).testTag("output-scroll").verticalScroll(scroll)) {
                TerminalLinkText(text)
            }
        } }
        compose.onNodeWithTag("output-scroll").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue("Gesture must remain owned by scrolling", scrollPosition > 0)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }
}
