package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Authored geometry contract, not device cutout/IME execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w800dp-h800dp")
class BrowserExpandedInsetsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun expandedContentReservesSideCutoutsBarsAndPermissionFooterExactlyOnce() {
        compose.setContent {
            Box(Modifier.size(400.dp, 600.dp).testTag("inset-root")) {
                Scaffold(
                    contentWindowInsets = expandedBrowserContentInsets(
                        WindowInsets(left = 4.dp, top = 24.dp, right = 6.dp, bottom = 20.dp),
                        WindowInsets(left = 36.dp, top = 12.dp, right = 30.dp, bottom = 4.dp),
                    ),
                    // Models the actual permission footer including its navigation-bar padding.
                    bottomBar = { Box(Modifier.fillMaxWidth().height(76.dp)) },
                ) { padding ->
                    Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()
                        .testTag("expanded-content"))
                }
            }
        }
        val root = compose.onNodeWithTag("inset-root").fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("expanded-content").fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertEquals(36.dp.toPx(), content.left - root.left, 1f)
            assertEquals(24.dp.toPx(), content.top - root.top, 1f)
            assertEquals(30.dp.toPx(), root.right - content.right, 1f)
            assertEquals(76.dp.toPx(), root.bottom - content.bottom, 1f)
        }
    }
}
