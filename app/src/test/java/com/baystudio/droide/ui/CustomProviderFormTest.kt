package com.baystudio.droide.ui

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.baystudio.droide.core.ProviderConnectionBackend
import com.baystudio.droide.core.ProviderRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
class CustomProviderFormTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: View
    private var density = 1f
    @After fun clear() { ProviderRegistry.installUserConfigured(emptyList()) }

    private fun show(width: Int = 360, height: Int = 560) {
        compose.setContent {
            val context = LocalContext.current
            val backend = remember { ProviderConnectionBackend(context) }
            val currentView = LocalView.current
            val currentDensity = LocalDensity.current
            WindowInsets.ime.getBottom(currentDensity)
            SideEffect { view = currentView; density = currentDensity.density }
            DroideTheme {
                Box(Modifier.width(width.dp).height(height.dp)) {
                    ConnectSheet(backend, onConnected = { _, _ -> error("Saving a definition must not select an unvalidated model") }, onDismiss = {})
                }
            }
        }
    }

    @Test fun customEndpointRemainsVisibleWhenKeyboardShrinksPortraitViewport() {
        show()
        compose.onNodeWithText("Add custom provider").performScrollTo().performClick()
        val field = compose.onNode(hasSetTextAction() and hasText("API base URL"))
        field.performScrollTo().performClick().performTextInput("https://api.example.com/v1")
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, (260 * density).roundToInt()))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build())
        }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(700); compose.waitForIdle()
        field.assertIsFocused().assertIsDisplayed()
        val bounds = field.getUnclippedBoundsInRoot()
        assertTrue("Endpoint clipped by IME: $bounds", bounds.bottom.value <= 301f)
        assertTrue("Endpoint clipped at top: $bounds", bounds.top.value >= 0f)
    }

    @Test @Config(qualifiers = "w800dp-h360dp-land")
    fun landscapeFormRemainsScrollableAndProtocolCanBeChanged() {
        show(800, 320)
        compose.onNodeWithText("Add custom provider").assertIsDisplayed().performClick()
        compose.onNodeWithText("OpenAI Chat Completions").performScrollTo().performClick()
        compose.onNodeWithText("Anthropic Messages").performClick()
        compose.onNodeWithText("Anthropic Messages").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save & connect").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun addProviderIsDiscoverableAndSavingOpensCredentialForm() {
        show()
        compose.onNodeWithText("Add custom provider").performScrollTo().performClick()
        compose.onNodeWithText("Provider name").performScrollTo().performTextInput("Company API")
        compose.onNodeWithText("Provider ID").performScrollTo().performTextInput("company")
        compose.onNodeWithText("API base URL").performScrollTo().performTextInput("https://api.example.com/v1")
        compose.onNodeWithText("Save & connect").performScrollTo().performClick()
        compose.waitUntil(10000) { ProviderRegistry.findById("user.company") != null }
        compose.onNodeWithText("API key").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connect & validate").performScrollTo().assertIsNotEnabled()
        assertEquals("Company API", ProviderRegistry.findById("user.company")!!.name)
    }

    @Test fun malformedEndpointKeepsFormAndDoesNotCreateProvider() {
        show()
        compose.onNodeWithText("Add custom provider").performScrollTo().performClick()
        compose.onNodeWithText("Provider name").performScrollTo().performTextInput("Bad")
        compose.onNodeWithText("Provider ID").performScrollTo().performTextInput("bad")
        compose.onNodeWithText("API base URL").performScrollTo().performTextInput("http://remote.example/v1")
        compose.onNodeWithText("Save & connect").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Use HTTPS for a remote provider, or HTTP for localhost.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Use HTTPS for a remote provider, or HTTP for localhost.").performScrollTo().assertIsDisplayed()
        assertNull(ProviderRegistry.findById("user.bad"))
    }
}
