package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.baystudio.droide.core.CodeStyleProfile
import com.baystudio.droide.core.IndentStyle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EditorAndroidSurfaceTest {
    @get:Rule val compose = createComposeRule()
    private val style = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "test")

    @Test fun initializationFailureIsVisibleAndRetryCreatesFreshEditor() {
        val views = mutableListOf<DroideCodeEditor>()
        val released = mutableListOf<DroideCodeEditor>()
        compose.setContent {
            DroideTheme {
                EditorAndroidSurface(
                    createEditor = { DroideCodeEditor(it, "plaintext", codeStyle = style).also(views::add) },
                    initializeEditor = {
                        if (views.size == 1) throw IllegalStateException("Initialization rejected")
                        it.setText("document still available")
                    },
                    updateEditor = {},
                    onReleaseEditor = { released += it },
                )
            }
        }
        compose.onNodeWithText("Editor could not display this file: Initialization rejected").assertExists()
        compose.runOnIdle { assertTrue(views.single().isReleased); assertEquals(views, released) }
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(2, views.size)
            assertFalse(views.last().isReleased)
            assertEquals("document still available", views.last().text.toString())
        }
    }

    @Test fun updateFailureSnapshotsUnsavedTextAfterDetachAndRetryRestoresIt() {
        val views = mutableListOf<DroideCodeEditor>()
        val snapshots = mutableListOf<String>()
        var failUpdate by mutableStateOf(false)
        var durableText = "original"
        compose.setContent {
            DroideTheme {
                EditorAndroidSurface(
                    createEditor = { DroideCodeEditor(it, "plaintext", codeStyle = style).also(views::add) },
                    initializeEditor = { it.setText(durableText) },
                    updateEditor = { if (failUpdate) throw IllegalStateException("Update rejected") },
                    onReleaseEditor = {
                        assertFalse("Detach before releasing editor resources", it.isAttachedToWindow)
                        durableText = it.text.toString()
                        snapshots += durableText
                    },
                )
            }
        }
        compose.runOnIdle {
            views.single().setSelection(0, 0)
            views.single().commitText("unsaved ", false)
            failUpdate = true
        }
        compose.onNodeWithText("Editor could not display this file: Update rejected").assertExists()
        compose.runOnIdle {
            assertTrue(views.single().isReleased)
            assertEquals(listOf("unsaved original"), snapshots)
            failUpdate = false
        }
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals("unsaved original", views.last().text.toString()) }
    }
    @Test fun detachingFailedEditorStillReleasesItAndRetainsUnsavedBuffer() {
        var visible by mutableStateOf(true)
        lateinit var view: DroideCodeEditor
        var retained = ""
        compose.setContent {
            DroideTheme {
                if (visible) EditorAndroidSurface(
                    createEditor = { DroideCodeEditor(it, "plaintext", codeStyle = style).also { view = it } },
                    initializeEditor = { editor ->
                        editor.setText("unsaved text")
                        editor.subscribeEvent(io.github.rosemoe.sora.event.EditorAttachStateChangeEvent::class.java) { event, _ ->
                            if (!event.isAttachedToWindow) throw IllegalStateException("Detached listener rejected")
                        }
                    },
                    updateEditor = {},
                    onReleaseEditor = { retained = it.text.toString() },
                )
            }
        }
        compose.runOnIdle { visible = false }
        compose.runOnIdle {
            assertTrue(view.isReleased)
            assertEquals(0, view.pendingEditorCallbackCount)
            assertEquals("unsaved text", retained)
        }
    }

    @Test fun rejectedRecoveryTextDoesNotGetReplacedByTheEmptyNativeBuffer() {
        val state = EditorWorkspaceState()
        val document = state.document("last.txt")
        val recovered = "header".repeat(1_500) + '\u0000' + "unsaved tail"
        document.restoreUnsaved(recovered)
        lateinit var view: DroideCodeEditor
        compose.setContent {
            DroideTheme {
                EditorAndroidSurface(
                    createEditor = { DroideCodeEditor(it, "plaintext", codeStyle = style).also { view = it } },
                    initializeEditor = {
                        // Match EditorPane: publish native ownership only after setText succeeds.
                        it.setText(document.content)
                        it.nativeCapture = { document.captureNativeText(it.text.toString()) }
                    },
                    updateEditor = {},
                    onReleaseEditor = { it.nativeCapture?.invoke() },
                )
            }
        }
        compose.onNodeWithText("Editor could not display this file: Binary file cannot be displayed as text. Original data was preserved.").assertExists()
        compose.runOnIdle {
            assertTrue(view.isReleased)
            assertTrue(document.dirty)
            assertEquals(recovered, document.content)
            assertEquals(recovered, state.recoverySnapshot(listOf("last.txt"), "last.txt")?.dirtyBuffers?.get("last.txt"))
        }
    }

}
