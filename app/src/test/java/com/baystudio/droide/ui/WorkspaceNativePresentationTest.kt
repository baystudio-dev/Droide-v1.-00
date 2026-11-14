package com.baystudio.droide.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.CodeStyleProfile
import com.baystudio.droide.core.DevicePtySessionHandle
import com.baystudio.droide.core.DeviceTerminalEmulator
import com.baystudio.droide.core.ExecResult
import com.baystudio.droide.core.IndentStyle
import com.baystudio.droide.core.TerminalManager
import com.baystudio.droide.core.ThemeManager
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** R48 source-only coverage, authored but not runtime-verified in this checkpoint. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceNativePresentationTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val terminalMemoryBudget = com.termux.terminal.TerminalMemoryBudgetTestRule()
    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** Match the production PTY decoder's off-main ownership without launching a process. */
    private fun DeviceTerminalEmulator.appendFromWorker(text: String) =
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Default) { appendText(text) }

    private data class Presentation(
        val visibility: Int,
        val accessibility: Int,
        val focusable: Int,
        val touchFocusable: Boolean,
        val descendantFocusability: Int?,
    )

    private fun presentation(view: View) = Presentation(view.visibility,
        view.importantForAccessibility, view.focusable, view.isFocusableInTouchMode,
        (view as? ViewGroup)?.descendantFocusability)

    private fun assertHidden(view: View) {
        assertEquals(View.INVISIBLE, view.visibility)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, view.importantForAccessibility)
        assertFalse(view.isFocusable)
        assertFalse(view.isFocusableInTouchMode)
        assertFalse(view.hasFocus())
        if (view is ViewGroup) assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, view.descendantFocusability)
    }

    @Test fun animatedExitKeepsPixelsButBlocksNativeInputAndRestoresExactPresentation() {
        val holder = FrameLayout(context)
        val editor = EditText(context).apply { setText("unsaved draft"); setSelection(4) }
        holder.addView(editor)
        val original = presentation(holder)
        val gate = WorkspaceNativePresentationGate()
        gate.update(holder, presented = false, drawWhileHidden = true)
        assertEquals(View.VISIBLE, holder.visibility)
        assertFalse(holder.isFocusable)
        assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, holder.descendantFocusability)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, holder.importantForAccessibility)
        gate.update(holder, presented = false, drawWhileHidden = false)
        assertHidden(holder)
        gate.update(holder, presented = true)
        assertEquals(original, presentation(holder))
        assertEquals("unsaved draft", editor.text.toString())
        assertEquals(4, editor.selectionStart)
    }

    @Test fun restoresExactOriginalValuesIncludingAutoFocusabilityAndNonvisibleViews() {
        for (view in listOf(View(context), FrameLayout(context))) {
            for (visibility in listOf(View.VISIBLE, View.INVISIBLE, View.GONE)) {
                view.visibility = visibility
                view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                view.isFocusableInTouchMode = false
                view.focusable = View.FOCUSABLE_AUTO
                (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                val original = presentation(view)
                val gate = WorkspaceNativePresentationGate()
                gate.update(view, presented = false)
                assertHidden(view)
                gate.update(view, presented = true)
                assertEquals(original, presentation(view))
            }
        }
    }

    @Test fun repeatedHiddenUpdatesKeepTheFirstSnapshotAndVisibleUpdatesDoNotClobberOwners() {
        val view = FrameLayout(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        val original = presentation(view)
        val gate = WorkspaceNativePresentationGate()
        repeat(4) {
            gate.update(view, presented = false)
            assertHidden(view)
        }
        // A later owner update must neither reopen the hidden surface nor replace
        // the snapshot with gated values on the next AndroidView update.
        view.visibility = View.VISIBLE
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        view.isFocusableInTouchMode = true
        view.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        gate.update(view, presented = false)
        assertHidden(view)
        gate.update(view, presented = true)
        assertEquals(original, presentation(view))

        view.visibility = View.GONE
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        view.isFocusableInTouchMode = false
        view.focusable = View.NOT_FOCUSABLE
        val ownerUpdate = presentation(view)
        gate.update(view, presented = true)
        assertEquals(ownerUpdate, presentation(view))
        gate.update(view, presented = false)
        gate.update(view, presented = true)
        assertEquals(ownerUpdate, presentation(view))
    }

    @Test fun replacementViewAndLateReleaseNeverUseAnotherViewsSnapshot() {
        val gate = WorkspaceNativePresentationGate()
        val old = FrameLayout(context).apply {
            visibility = View.GONE
            isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val replacement = View(context).apply {
            isFocusableInTouchMode = false
            focusable = View.FOCUSABLE_AUTO
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val originalReplacement = presentation(replacement)
        gate.update(old, presented = false)
        gate.update(replacement, presented = true)
        assertEquals(originalReplacement, presentation(replacement))
        gate.update(replacement, presented = false)
        gate.forget(old)
        gate.update(replacement, presented = true)
        assertEquals(originalReplacement, presentation(replacement))
        assertHidden(old)

        gate.update(replacement, presented = false)
        gate.forget(replacement)
        gate.update(replacement, presented = true)
        assertHidden(replacement)
    }

    @Test fun hiddenHolderBlocksNewChildFocusAndRoutesKeysToPresentedSiblingWithoutDetach() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val root = FrameLayout(activity.get())
            val holder = FrameLayout(activity.get())
            val gate = WorkspaceNativePresentationGate()
            gate.update(holder, presented = false)
            var childKeys = 0
            var presentedKeys = 0
            var detaches = 0
            val child = EditText(activity.get()).apply {
                setText("unsaved workspace buffer")
                isFocusableInTouchMode = true
                setOnKeyListener { _, _, _ -> childKeys++; true }
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = Unit
                    override fun onViewDetachedFromWindow(v: View) { detaches++ }
                })
            }
            val childState = presentation(child)
            holder.addView(child, FrameLayout.LayoutParams(300, 200))
            val presented = EditText(activity.get()).apply {
                isFocusableInTouchMode = true
                setOnKeyListener { _, _, _ -> presentedKeys++; true }
            }
            root.addView(holder, FrameLayout.LayoutParams(300, 200))
            root.addView(presented, FrameLayout.LayoutParams(300, 200))
            activity.get().setContentView(root)
            activity.windowFocusChanged(true)
            assertFalse(child.requestFocus())
            assertTrue(presented.requestFocus())
            repeat(3) {
                gate.update(holder, presented = false)
                assertFalse(child.requestFocus())
                assertTrue(root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
                gate.update(holder, presented = true)
                assertTrue("Showing must not request focus back from the presented input", presented.hasFocus())
            }
            assertEquals(0, childKeys)
            assertEquals(3, presentedKeys)
            assertEquals(childState, presentation(child))
            assertEquals("unsaved workspace buffer", child.text.toString())
            assertSame(holder, child.parent)
            assertTrue(child.isAttachedToWindow)
            assertEquals(0, detaches)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun editorKeepsItsOwnerAndUnsavedBufferAcrossPresentationChanges() {
        var presented by mutableStateOf(true)
        var updateTick by mutableIntStateOf(0)
        var mounted by mutableStateOf(true)
        val editors = mutableListOf<DroideCodeEditor>()
        var releases = 0
        val style = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "test")
        compose.setContent {
            DroideTheme {
                CompositionLocalProvider(LocalWorkspacePresented provides presented) {
                    if (mounted) EditorAndroidSurface(
                        createEditor = { DroideCodeEditor(it, "plaintext", codeStyle = style).also(editors::add) },
                        initializeEditor = { it.setText("unsaved editor buffer") },
                        updateEditor = {
                            updateTick
                        },
                        onReleaseEditor = { releases++ },
                    )
                }
            }
        }
        lateinit var original: DroideCodeEditor
        lateinit var holder: View
        lateinit var originalPresentation: Presentation
        compose.runOnIdle {
            original = editors.single()
            holder = original.parent as View
            originalPresentation = presentation(holder)
        }
        repeat(3) {
            compose.runOnIdle { presented = false }
            compose.runOnIdle { updateTick++ }
            compose.runOnIdle {
                assertHidden(holder)
                // Observe the settled presentation after Compose applies its local.
                // A live outer-state read inside AndroidView.update can run before
                // the CompositionLocal's next value has reached that callback.
                assertFalse(original.requestFocus())
                assertFalse(original.isReleased)
                assertTrue(original.isAttachedToWindow)
                assertEquals("unsaved editor buffer", original.text.toString())
                assertEquals(0, releases)
                presented = true
            }
            compose.runOnIdle {
                assertSame(original, editors.single())
                assertEquals(originalPresentation, presentation(holder))
            }
        }
        compose.runOnIdle { presented = false }
        compose.runOnIdle { mounted = false }
        compose.runOnIdle {
            assertEquals(1, releases)
            assertTrue(original.isReleased)
        }
    }

    private class RetainedPtySession : DevicePtySessionHandle {
        override val output = MutableStateFlow("")
        val input = mutableListOf<String>()
        var destroyed = 0
        override val terminalEmulator = DeviceTerminalEmulator({ input += it.decodeToString() }, 40, 24)
        override val terminalTitle = terminalEmulator.title
        override val workingDirectory = terminalEmulator.workingDirectory
        override val profileLabel = "retained test PTY"
        override val status = MutableStateFlow("Ready")
        override fun start() = Unit
        override fun send(cmd: String) { input += cmd }
        override fun writeRaw(text: String) { input += text }
        override fun sendKeyCode(keyCode: Int, modifiers: Int) = Unit
        override fun resize(columns: Int, rows: Int) { terminalEmulator.resize(columns, rows) }
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() { destroyed++ }
        override suspend fun refreshWorkspace() = Unit
        override suspend fun execOnce(cmd: String, timeoutMs: Long) = ExecResult(0, "", false)
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = ExecResult(0, "", false)
    }

    private fun terminalViews(view: View): List<DeviceTerminalView> = when (view) {
        is DeviceTerminalView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { terminalViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun initiallyHiddenTerminalCannotFocusAndKeepsSessionAndOutputWhenShownAgain() {
        val root = Files.createTempDirectory("workspace-native-presentation-").toFile()
        try {
            val session = RetainedPtySession()
            val theme = ThemeManager(root).snapshot()
            val focus = AccessoryInputFocusController()
            var presented by mutableStateOf(false)
            lateinit var composeView: View
            compose.setContent {
                val view = LocalView.current
                SideEffect { composeView = view }
                DroideTheme {
                    CompositionLocalProvider(LocalWorkspacePresented provides presented) {
                        Box(Modifier.width(360.dp).height(500.dp)) {
                            TerminalPane(session, TerminalManager.Profile.LOCAL_QEMU, theme = theme,
                                accessoryInputFocus = focus, accessoryKeysExpanded = false,
                                onAccessoryKeysExpandedChange = {})
                        }
                    }
                }
            }
            lateinit var original: DeviceTerminalView
            compose.runOnIdle {
                original = terminalViews(composeView).single()
                assertHidden(original)
                assertFalse(original.requestFocus())
                assertEquals(AccessoryInputTarget.NONE, focus.activeTarget)
                session.terminalEmulator.appendFromWorker("retained output\r\n")
            }
            repeat(3) {
                compose.runOnIdle { presented = true }
                compose.runOnIdle {
                    assertSame(original, terminalViews(composeView).single())
                    assertEquals(View.VISIBLE, original.visibility)
                    assertTrue(original.isFocusable)
                    assertTrue(original.isFocusableInTouchMode)
                    assertTrue(original.requestFocus())
                    presented = false
                }
                compose.runOnIdle {
                    assertHidden(original)
                    assertTrue(original.isAttachedToWindow)
                    assertEquals(AccessoryInputTarget.NONE, focus.activeTarget)
                    assertTrue(session.terminalEmulator.snapshotText().contains("retained output"))
                    assertTrue(session.input.isEmpty())
                    assertEquals(0, session.destroyed)
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
