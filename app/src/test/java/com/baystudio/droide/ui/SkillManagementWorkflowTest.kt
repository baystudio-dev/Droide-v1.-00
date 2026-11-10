package com.baystudio.droide.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.util.ReflectionHelpers
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkillManagementWorkflowTest {
    @get:Rule val applicationWork: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val root = Files.createTempDirectory("skills-ui-").toFile()
    private val management = SkillManagement(FileRepository(root))
    @After fun cleanup() { PathSecurity.deleteTreeNoFollow(root) }

    private fun mount() {
        compose.setContent { DroideTheme { Surface {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { SkillManagementSection(management, "workspace") }
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Refresh skills").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun traverseNativeDialogWindow() {
        assertTrue("Automatic Compose idling stays enabled", compose.mainClock.autoAdvance)
        var idleAfterTraversal = false
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.runOnUiThread {
                val environment = ReflectionHelpers.getField<Any>(compose, "environment")
                val registry = ReflectionHelpers.getField<Any>(environment, "composeRootRegistry")
                val idler = ReflectionHelpers.getField<Any>(environment, "composeIdlingResource")
                ReflectionHelpers.callInstanceMethod<Boolean>(idler, "isIdleNow")
                val owners: Set<Any> = ReflectionHelpers.callInstanceMethod(registry, "getRegisteredComposeRoots")
                for (owner in owners) {
                    val ownerView = ReflectionHelpers.callInstanceMethod<View>(owner, "getView")
                    if (ownerView.rootView !== compose.activity.window.decorView.rootView && ownerView.isAttachedToWindow && ownerView.isShown &&
                        ownerView.windowVisibility == View.VISIBLE &&
                        ReflectionHelpers.callInstanceMethod<Boolean>(owner, "getHasPendingMeasureOrLayout")) {
                        ReflectionHelpers.callInstanceMethod<Void>(ownerView, "measureAndLayoutForTest")
                    }
                }
                idleAfterTraversal = ReflectionHelpers.callInstanceMethod<Boolean>(idler, "isIdleNow")
            }
        }
        assertTrue("Native dialog layout and the normal Compose clock must settle", idleAfterTraversal)
    }

    @Test fun createAndOpenSkillThroughSettingsOnNarrowScreen() {
        mount()
        compose.waitUntil(5000) { compose.onAllNodesWithText("No skills in this scope yet.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Create skill").performClick()
        traverseNativeDialogWindow()
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.waitUntil(10000) { root.resolve(".droide/skills/my-skill/SKILL.md").isFile }
        compose.waitUntil(5000) { compose.onAllNodesWithText("my-skill", substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("my-skill", substring = false).performScrollTo().performClick()
        traverseNativeDialogWindow()
        compose.onNodeWithText("Remove skill", substring = false).assertExists()
        compose.onNodeWithText("Cancel", substring = false).performClick()
    }

    @Test fun concurrentFileChangeKeepsTheDraftOpenAndDoesNotOverwriteUserContent() {
        runBlocking { management.save(skillMarkdown(), "workspace") }
        mount()
        compose.waitUntil(5000) { compose.onAllNodesWithText("review-code", substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("review-code", substring = false).performScrollTo().performClick()
        traverseNativeDialogWindow()
        val input = compose.onAllNodes(hasSetTextAction()).onLast()
        input.performSemanticsAction(SemanticsActions.RequestFocus)
        traverseNativeDialogWindow()
        input.performTextReplacement(skillMarkdown(body = "Unsaved dialog draft"))
        traverseNativeDialogWindow()
        root.resolve(".droide/skills/review-code/SKILL.md").writeText(skillMarkdown(body = "Newer file edit"))
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Skill already exists or changed", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(root.resolve(".droide/skills/review-code/SKILL.md").readText().contains("Newer file edit"))
        compose.onNodeWithText("Unsaved dialog draft", substring = true).assertExists()
    }
}
