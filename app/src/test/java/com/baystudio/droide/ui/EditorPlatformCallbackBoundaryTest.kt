package com.baystudio.droide.ui

import android.app.Application
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.baystudio.droide.core.CodeStyleProfile
import com.baystudio.droide.core.IndentStyle
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.layout.Layout
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class)
class EditorPlatformCallbackBoundaryTest {
    private fun withBrokenLayout(block: (DroideCodeEditor, MutableList<Throwable>, () -> Int) -> Unit) {
        val editor = DroideCodeEditor(RuntimeEnvironment.getApplication(), "plaintext",
            codeStyle = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "test"))
        editor.setText("unsaved text\nsecond line")
        editor.layout(0, 0, 360, 640)
        val failures = mutableListOf<Throwable>()
        editor.onEditorFailure = { failures += it }
        val field = CodeEditor::class.java.getDeclaredField("layout").apply { isAccessible = true }
        val original = field.get(editor)
        var queries = 0
        field.set(editor, Proxy.newProxyInstance(Layout::class.java.classLoader, arrayOf(Layout::class.java)) { _, method, args ->
            if (method.name == "getLayoutHeight" || method.name == "getLayoutWidth") {
                queries++
                throw IllegalStateException("Layout unavailable")
            }
            method.invoke(original, *(args ?: emptyArray()))
        })
        try { block(editor, failures) { queries } }
        finally { field.set(editor, original); editor.release() }
    }

    @Test fun accessibilityQueryReportsLayoutFailureAndRetainsTheUnsavedBuffer() = withBrokenLayout { editor, failures, _ ->
        val info = editor.createAccessibilityNodeInfo()
        assertFalse(info.isScrollable)
        assertFalse(info.isEditable)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Layout unavailable", failures.single().message)
        assertEquals("unsaved text\nsecond line", editor.text.toString())
    }

    @Test fun accessibilityEventCannotEscapeTheSurfaceFailureBoundary() = withBrokenLayout { editor, failures, _ ->
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED)
        editor.onInitializeAccessibilityEvent(event)
        assertFalse(event.isScrollable)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Layout unavailable", failures.single().message)
    }

    @Test fun frameworkScrollQueriesFailOnceAndStopTouchingTheBrokenLayout() = withBrokenLayout { editor, failures, queries ->
        assertFalse(editor.canScrollVertically(1))
        assertFalse(editor.canScrollHorizontally(1))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, failures.size)
        assertEquals(1, queries())
    }

    @Test fun failedAndReleasedEditorsAreInertToLatePlatformQueries() = withBrokenLayout { editor, failures, queries ->
        editor.reportSurfaceFailure(IllegalStateException("First failure"))
        shadowOf(Looper.getMainLooper()).idle()
        repeat(2) {
            editor.createAccessibilityNodeInfo()
            editor.onInitializeAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
            assertFalse(editor.canScrollVertically(1))
            assertFalse(editor.canScrollHorizontally(-1))
            editor.release()
        }
        assertEquals(0, queries())
        assertEquals("First failure", failures.single().message)
    }

    @Test fun textReplacementRejectsLateNulBeforeChangingTheNativeBuffer() {
        val editor = DroideCodeEditor(RuntimeEnvironment.getApplication(), "plaintext",
            codeStyle = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "test"))
        try {
            editor.setText("unsaved original")
            assertThrows(IllegalArgumentException::class.java) { editor.setText("x".repeat(8_192) + '\u0000') }
            assertEquals("unsaved original", editor.text.toString())
            editor.setText("é漢字\uD83D\uDE00\t\r\n")
            assertEquals("é漢字\uD83D\uDE00\t\r\n", editor.text.toString())
        } finally { editor.release() }
    }

    @Test fun healthyEditorKeepsItsTextAndAccessibilityActions() {
        val editor = DroideCodeEditor(RuntimeEnvironment.getApplication(), "plaintext",
            codeStyle = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "test"))
        try {
            editor.setText("accessible text")
            editor.layout(0, 0, 360, 640)
            val info = editor.createAccessibilityNodeInfo()

            assertEquals(editor.isEditable, info.isEditable)
            assertEquals("accessible text", info.text.toString())
            assertTrue(info.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_COPY })
            editor.setEditable(false)
            assertFalse(editor.createAccessibilityNodeInfo().isEditable)
            assertTrue(editor.acceptsFeatureUpdates)
        } finally { editor.release() }
    }
}
