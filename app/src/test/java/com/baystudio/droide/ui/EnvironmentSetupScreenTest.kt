package com.baystudio.droide.ui

import android.app.Application
import android.widget.EditText
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.baystudio.droide.core.*
import kotlinx.coroutines.*
import org.junit.After
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
class EnvironmentSetupScreenTest {
    @get:Rule val compose = createComposeRule()
    private var snapshotView: android.view.View? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun close() { scope.cancel() }

    @Test fun existingEnvironmentNeverFlashesTheInstallScreenDuringInspection() {
        val inspected = CompletableDeferred<Unit>()
        val controller = EnvironmentSetupController(scope, true,
            inspect = { inspected.await(); EnvironmentSetupInspection(true) },
            install = { error("Already installed") }, defer = {})
        compose.setContent { DroideTheme { EnvironmentSetupHost(controller, true) {
            androidx.compose.material3.Text("Workspace ready")
        } } }
        compose.onNodeWithText("Ubuntu 24.04").assertDoesNotExist()
        compose.onNodeWithText("Install environment").assertDoesNotExist()
        compose.runOnIdle { inspected.complete(Unit) }; compose.waitForIdle()
        compose.onNodeWithText("Workspace ready").assertIsDisplayed()
        compose.onNodeWithText("Ubuntu 24.04").assertDoesNotExist()
    }

    @Test fun touchInstallStartsOnlyOnceAndDoesNotMountTheEditorUntilReady() {
        var installed = false; var calls = 0; var mounts = 0
        val completed = CompletableDeferred<Unit>()
        val controller = EnvironmentSetupController(scope, true,
            inspect = { EnvironmentSetupInspection(installed) },
            install = { progress -> calls++; progress(EnvironmentSetupProgress(EnvironmentSetupPhase.DOWNLOADING, 10, 40))
                completed.await(); installed = true }, defer = {})
        compose.setContent { DroideTheme {
            snapshotView = LocalView.current
            EnvironmentSetupHost(controller, true) { DisposableEffect(Unit) { mounts++; onDispose {} } }
        } }
        assertEquals(0, mounts); assertEquals(0, calls)
        capture("portrait-ready")
        compose.onNodeWithText("Install environment").performTouchInput { click() }
        compose.onNodeWithText("Downloading Ubuntu…").assertIsDisplayed()
        capture("portrait-downloading")
        compose.runOnIdle { repeat(5) { controller.installFromUser(); controller.show() } }
        assertEquals(1, calls); assertEquals(0, mounts)
        compose.runOnIdle { completed.complete(Unit) }; compose.waitForIdle()
        assertEquals(1, mounts); assertEquals(1, calls)
        compose.onNodeWithText("Install environment").assertDoesNotExist()
    }

    @Test fun deferAndReopenRetainEditorDraftWhileHidingItsInputAndSemantics() {
        var deferred = false; var mounts = 0; var releases = 0; var editor: EditText? = null
        val controller = EnvironmentSetupController(scope, true,
            inspect = { EnvironmentSetupInspection(false, deferred) }, install = { error("Must require a user click") },
            defer = { deferred = true })
        compose.setContent { DroideTheme { EnvironmentSetupHost(controller, true) {
            val presented = LocalWorkspacePresented.current
            AndroidView(factory = { context -> EditText(context).apply {
                mounts++; editor = this; setText("My existing draft"); setSelection(5)
            } }, update = {
                it.isFocusable = presented; it.isFocusableInTouchMode = presented
                if (!presented) it.clearFocus()
            }, onRelease = { releases++ }, modifier = Modifier.fillMaxSize().testTag("retained-editor"))
        } } }
        compose.onNodeWithText("Not now").performTouchInput { click() }; compose.waitForIdle()
        assertTrue(deferred); assertEquals(1, mounts)
        val original = editor
        compose.runOnIdle { controller.show() }; compose.waitForIdle()
        compose.onNodeWithTag("retained-editor").assertDoesNotExist()
        assertFalse(editor!!.isFocusable)
        compose.onNodeWithText("Not now").performTouchInput { click() }; compose.waitForIdle()
        assertSame(original, editor); assertEquals(0, releases); assertEquals(1, mounts)
        assertEquals("My existing draft", editor!!.text.toString()); assertEquals(5, editor!!.selectionStart)
    }

    @Test @Config(qualifiers = "w800dp-h360dp-mdpi")
    fun landscapeLargeTextKeepsBothConsentActionsVisibleAndUsable() {
        var accepted = 0; var declined = 0
        compose.setContent { DroideTheme {
            snapshotView = LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) {
                EnvironmentSetupScreen(EnvironmentSetupState(EnvironmentSetupProgress(EnvironmentSetupPhase.AVAILABLE)),
                    { accepted++ }, { declined++ }, {})
            }
        } }
        compose.onNodeWithText("Install environment").assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithText("Not now").assertIsDisplayed().performTouchInput { click() }
        capture("landscape-large-text")
        assertEquals(1, accepted); assertEquals(1, declined)
    }

    @Test fun exitTakesOverImmediatelyEvenWhenInstallationIsStillRunning() {
        var allowed by mutableStateOf(true)
        val controller = EnvironmentSetupController(scope, true,
            inspect = { EnvironmentSetupInspection(false) }, install = { awaitCancellation() }, defer = {})
        compose.setContent { DroideTheme { EnvironmentSetupHost(controller, allowed) {
            androidx.compose.material3.Text("Closing Droide", Modifier.testTag("exit"))
        } } }
        compose.onNodeWithText("Install environment").performTouchInput { click() }
        compose.runOnIdle { allowed = false }; compose.waitForIdle()
        compose.onNodeWithTag("exit").assertIsDisplayed()
        compose.onNodeWithText("Pause installation").assertDoesNotExist()
    }

    private fun capture(name: String) {
        val directory = System.getProperty("droide.setupScreenshots") ?: return
        compose.runOnIdle {
            val view = requireNotNull(snapshotView).rootView
            val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(image))
            java.io.File(directory).apply { mkdirs() }.resolve("$name.png").outputStream().use {
                check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            image.recycle()
        }
    }
}
