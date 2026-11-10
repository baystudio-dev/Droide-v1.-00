package com.baystudio.droide.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.baystudio.droide.core.ProviderConnectionBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
class ProviderCredentialKeyboardTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: View
    private var density = 1f
    private var receivedImeBottom = 0

    private fun showForm(providerId: String, width: Int = 360, height: Int = 560) {
        compose.setContent {
            val context = LocalContext.current
            val backend = androidx.compose.runtime.remember { ProviderConnectionBackend(context) }
            val currentView = LocalView.current
            val currentDensity = LocalDensity.current
            val imeBottom = WindowInsets.ime.getBottom(currentDensity)
            SideEffect {
                view = currentView
                density = currentDensity.density
                receivedImeBottom = imeBottom
            }
            DroideTheme {
                Box(Modifier.width(width.dp).height(height.dp)) {
                    ConnectSheet(
                        connectionBackend = backend,
                        initialProviderId = providerId,
                        onConnected = { _, _ -> error("Typing must not initiate a provider connection") },
                        onDismiss = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun dispatchIme(heightDp: Int, verifyReceiver: Boolean = true) {
        val pixels = (heightDp * density).roundToInt()
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(
                view,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, pixels))
                    .setVisible(WindowInsetsCompat.Type.ime(), heightDp > 0)
                    .build(),
            )
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        if (verifyReceiver) assertEquals("The layout must receive the simulated keyboard insets", pixels, receivedImeBottom)
    }

    private fun credential() = compose.onNode(hasSetTextAction() and hasText("API key"))

    private fun assertAboveKeyboard(visibleHeightDp: Int) {
        credential().assertIsDisplayed().assertIsFocused()
        val bounds = credential().getUnclippedBoundsInRoot()
        assertTrue("Credential field clipped by keyboard: $bounds", bounds.bottom.value <= visibleHeightDp + 1f)
        assertTrue("Credential field clipped above viewport: $bounds", bounds.top.value >= 0f)
        assertTrue("Credential field must retain its full input height: $bounds", bounds.bottom.value - bounds.top.value >= 50f)
    }

    @Test fun portraitApiKeyFollowsKeyboardOpenResizeAndDismissWithoutLosingInput() {
        showForm("gemini")
        credential().performScrollTo().performClick().performTextInput("unit-test-secret")
        dispatchIme(260)
        assertAboveKeyboard(300)
        dispatchIme(320)
        assertAboveKeyboard(240)
        credential().performImeAction()
        credential().assertIsNotFocused()
        dispatchIme(0)

        credential().assertTextContains("•".repeat("unit-test-secret".length))
        compose.onNodeWithText("Connect & validate").performScrollTo().assertIsDisplayed().assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "w720dp-h360dp-land")
    fun landscapeDetailPaneCanScrollFocusedCredentialAboveTheKeyboard() {
        showForm("openai", width = 720, height = 320)
        credential().performScrollTo().performClick().performTextInput("unit-test-secret")
        dispatchIme(150)
        assertAboveKeyboard(170)
        dispatchIme(0)
        compose.onNodeWithText("Connect & validate").performScrollTo().assertIsDisplayed().assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun compactPortraitKeepsAnthropicCredentialAndConnectActionReachable() {
        showForm("anthropic", width = 320, height = 440)
        credential().performScrollTo().performClick()
        dispatchIme(230)
        assertAboveKeyboard(210)
        credential().performImeAction()
        credential().assertIsNotFocused()
        dispatchIme(0)
        compose.onNodeWithText("Connect & validate").performScrollTo().assertIsDisplayed()
    }

    @Test fun modalProviderFormOpensExpandedAndConsumesKeyboardInsetsOnce() {
        compose.setContent {
            val context = LocalContext.current
            val backend = androidx.compose.runtime.remember { ProviderConnectionBackend(context) }
            val currentDensity = LocalDensity.current
            SideEffect { density = currentDensity.density }
            DroideTheme {
                ProviderConnectionSheet(
                    connectionBackend = backend,
                    initialProviderId = "gemini",
                    onConnected = { _, _ -> error("Typing must not initiate a provider connection") },
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(700)
        val rootHeight = compose.onAllNodes(isRoot()).fetchSemanticsNodes().maxOf { it.boundsInRoot.height / density }
        val header = compose.onNodeWithText("Connect provider").getUnclippedBoundsInRoot()
        assertTrue("Provider form must not start at the half-height anchor: $header", header.top.value < rootHeight * .3f)

        fun composeView(candidate: View): View? {
            if (candidate.javaClass.simpleName == "AndroidComposeView") return candidate
            if (candidate is ViewGroup) {
                for (index in 0 until candidate.childCount) composeView(candidate.getChildAt(index))?.let { return it }
            }
            return null
        }
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        view = requireNotNull(composeView(requireNotNull(dialog.window).decorView))
        credential().performScrollTo().performClick().performTextInput("unit-test-secret")
        // Dispatch to the sheet's own dialog root. Material3 consumes IME space outside the form, so a
        // second application would collapse the viewport.
        dispatchIme(260, verifyReceiver = false)
        assertAboveKeyboard((rootHeight - 260).toInt())
        credential().performImeAction()
        dispatchIme(0, verifyReceiver = false)
        compose.onNodeWithText("Connect & validate").performScrollTo().assertIsDisplayed().assertIsEnabled()
    }
}
