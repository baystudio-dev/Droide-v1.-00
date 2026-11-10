package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
class CompactNavigationImeMotionTest {
    @get:Rule val compose = createComposeRule()

    private class Insets(var initial: Int) : WindowInsets {
        var bottom by mutableStateOf(initial)
        override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
        override fun getTop(density: Density) = 0
        override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
        override fun getBottom(density: Density) = bottom
    }

    @Test fun nativeInsetsRevealInSameFrameInsteadOfWaitingForHiddenBoundary() {
        val ime=Insets(300); val source=Insets(300); val target=Insets(300)
        val raw=mutableStateOf(true)
        compose.setContent { DroideTheme {
            val motion=rememberAccessoryImeMotion(ime,source,target,raw.value)
            CompactNavigationImeReveal(true, !raw.value && ime.bottom==0, motion, Modifier.testTag("bar")) {
                DroideCompactNavigation(2, enabled=!raw.value && ime.bottom==0) {}
            }
        } }
        assertEquals(0f,compose.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot.height)
        compose.runOnIdle { raw.value=false; target.bottom=0; ime.bottom=150 }
        val half=compose.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot.height
        assertTrue(half>0f)
        compose.runOnIdle { ime.bottom=0 }
        val full=compose.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot.height
        assertEquals(full/2f,half,1f)
        compose.onNodeWithText("Terminal").assertIsEnabled()
    }

    @Test fun keyboardClosingRevealsNavigationBeforeHiddenEndpointWithoutTimer() {
        val fraction = mutableStateOf(0f)
        val enabled = mutableStateOf(false)
        var clicks = 0
        compose.setContent { DroideTheme { Box(Modifier.size(360.dp,640.dp)) {
            CompactNavigationRevealLayout({ fraction.value }, enabled.value, Modifier.testTag("navigation")) {
                DroideCompactNavigation(2, enabled = enabled.value) { clicks++ }
            }
        } } }
        assertEquals(0f,compose.onNodeWithTag("navigation").fetchSemanticsNode().boundsInRoot.height)
        compose.runOnIdle { fraction.value = .5f }
        val partial = compose.onNodeWithTag("navigation").fetchSemanticsNode().boundsInRoot.height
        assertTrue(partial > 0f)
        compose.onNodeWithText("Terminal").assertDoesNotExist()
        compose.runOnIdle { fraction.value = 1f; enabled.value = true }
        val full = compose.onNodeWithTag("navigation").fetchSemanticsNode().boundsInRoot.height
        assertEquals(full / 2f, partial, 1f)
        compose.onNodeWithText("Terminal").assertIsSelected().performClick()
        assertEquals(1, clicks)
    }

    @Test fun nativeClosingGeometryDrivesRevealAndReversalWithoutExtraTimeline() {
        val controller = AccessoryImeMotionController()
        fun nav(current:Int, source:Int, target:Int, raw:Boolean) =
            1f-controller.sample(current,source,target,if(raw) 1f else 0f,raw)
        assertEquals(0f,nav(300,300,300,true),.001f)
        assertEquals(.25f,nav(225,300,0,false),.001f)
        assertEquals(.5f,nav(150,300,0,false),.001f)
        assertEquals(.5f,nav(150,150,300,true),.001f)
        assertEquals(.25f,nav(225,150,300,true),.001f)
        assertEquals(0f,nav(300,150,300,true),.001f)
        assertEquals(1f,nav(0,300,0,false),.001f)
        assertEquals(1f,nav(0,0,0,false),.001f)
    }

    @Test fun hiddenNavigationCannotDispatchSelection() {
        var clicks=0
        compose.setContent { DroideTheme { DroideCompactNavigation(2, enabled=false) { clicks++ } } }
        compose.onNodeWithText("Terminal").assertIsNotEnabled().performClick()
        assertEquals(0,clicks)
    }
}
