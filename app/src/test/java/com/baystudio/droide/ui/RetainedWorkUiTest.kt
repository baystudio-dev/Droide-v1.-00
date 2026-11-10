package com.baystudio.droide.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.RetainedWorkCandidate
import com.baystudio.droide.core.RetainedWorkInventory
import com.baystudio.droide.core.RetainedWorkKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RetainedWorkUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var mainWindowRoot: View

    @Test fun manualFolderReviewShowsPartialStatusAndOffersNoMutationAction() {
        var directory by mutableStateOf("")
        var visible by mutableStateOf(false)
        var inspections = 0
        var closes = 0
        val candidate = RetainedWorkCandidate(".droide-tmp-11111111-1111-4111-8111-111111111111",
            RetainedWorkKind.FOREIGN, null, "This directory entry has no verified write baseline")
        val inventory = RetainedWorkInventory("", listOf(candidate), false, "Inspection budget reached")
        compose.setContent {
            mainWindowRoot = LocalView.current.rootView
            DroideTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    if (visible) RetainedWorkDialog(
                        RetainedWorkUiState(open = true, directory = directory, inventory = inventory),
                        onDirectory = { directory = it }, onInspect = { inspections++ }, onClose = { closes++ })
                }
            }
        }
        compose.runOnIdle { visible = true }
        traverseNativeDialogWindow()
        compose.runOnIdle { assertEquals(0, inspections) }
        compose.onNodeWithText("Partial inspection. Other candidates may be present.").performScrollTo()
        traverseNativeDialogWindow()
        compose.onNodeWithText("Partial inspection. Other candidates may be present.").assertExists()
        compose.onNodeWithText("Entry is not verified as this write's working file; keep it for review.").performScrollTo()
        traverseNativeDialogWindow()
        compose.onNodeWithText("Entry is not verified as this write's working file; keep it for review.").assertExists()
        compose.onNodeWithText("Delete").assertDoesNotExist()
        compose.onNodeWithText("Restore").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        val folder = compose.onNodeWithTag("retained-work-folder")
        folder.performScrollTo()
        traverseNativeDialogWindow()
        folder.performSemanticsAction(SemanticsActions.RequestFocus)
        traverseNativeDialogWindow()
        folder.performTextInput("src")
        traverseNativeDialogWindow()
        compose.onNodeWithTag("retained-work-inspect").performScrollTo()
        traverseNativeDialogWindow()
        compose.onNodeWithTag("retained-work-inspect").performClick()
        traverseNativeDialogWindow()
        compose.runOnIdle { assertEquals("src", directory); assertEquals(1, inspections) }
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    // SDK 35 modal selection requires real owner layout; require stable normal idling afterwards.
    private fun traverseNativeDialogWindow() {
        assertTrue("Automatic Compose idling stays enabled", compose.mainClock.autoAdvance)
        var quietFrames = 0
        var modalSeen = false
        val samples = mutableListOf<String>()
        repeat(20) { frame ->
            compose.mainClock.advanceTimeByFrame()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.runOnUiThread {
                val environment = ReflectionHelpers.getField<Any>(compose, "environment")
                val registry = ReflectionHelpers.getField<Any>(environment, "composeRootRegistry")
                val idler = ReflectionHelpers.getField<Any>(environment, "composeIdlingResource")
                ReflectionHelpers.callInstanceMethod<Boolean>(idler, "isIdleNow")
                val owners: Set<Any> = ReflectionHelpers.callInstanceMethod(registry, "getRegisteredComposeRoots")
                var layouts = 0
                var modals = 0
                for (owner in owners) {
                    val view = ReflectionHelpers.callInstanceMethod<View>(owner, "getView")
                    if (view.rootView !== mainWindowRoot && view.isAttachedToWindow && view.isShown &&
                        view.windowVisibility == View.VISIBLE) {
                        modals++
                        if (ReflectionHelpers.callInstanceMethod<Boolean>(owner, "getHasPendingMeasureOrLayout")) {
                            layouts++
                            ReflectionHelpers.callInstanceMethod<Void>(view, "measureAndLayoutForTest")
                        }
                    }
                }
                modalSeen = modalSeen || modals > 0
                val idle = ReflectionHelpers.callInstanceMethod<Boolean>(idler, "isIdleNow")
                quietFrames = if (modals > 0 && layouts == 0 && idle) quietFrames + 1 else 0
                samples += "$frame:modals=$modals,layouts=$layouts,idle=$idle"
            }
        }
        println("RETAINED_DIALOG_TRAVERSAL " + samples.joinToString(";"))
        assertTrue("The real modal owner must register: $samples", modalSeen)
        assertTrue("Dialog must remain idle for five frames without manual layout: $samples", quietFrames >= 5)
    }
}
