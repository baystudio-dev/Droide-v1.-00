package com.baystudio.droide.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.widget.FrameLayout
import android.view.View
import com.baystudio.droide.core.CodeStyleProfile
import com.baystudio.droide.core.IndentStyle
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w640dp-h480dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorCompletionViewportTest {
    private fun fixture(block: (DroideCodeEditor, FrameLayout, DroideEditorAutoCompletion) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = FrameLayout(activity).apply { clipChildren = true }
        val editor = DroideCodeEditor(activity, "kotlin", codeStyle = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "fixture"))
        root.addView(editor, FrameLayout.LayoutParams(600, 380).apply { leftMargin = 18; topMargin = 12 })
        activity.setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(440, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 640, 440)
        editor.setLineNumberEnabled(false)
        val completion = editor.getComponent(EditorAutoCompletion::class.java) as DroideEditorAutoCompletion
        completion.setEnabledAnimation(false)
        editor.setEditorLanguage(object : EmptyLanguage() {
            override fun requireAutoComplete(content: ContentReference, position: CharPosition, publisher: CompletionPublisher, extraArguments: Bundle) {
                publisher.addItems((0..11).map { SimpleCompletionItem("symbol-$it", "local completion fixture", 2, "symbol") })
            }
        })
        try { block(editor, root, completion) }
        finally { editor.release(); controller.pause().stop().destroy(); ShadowLooper.idleMainLooper() }
    }

    private fun awaitPopup(completion: DroideEditorAutoCompletion) {
        completion.requireCompletion()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!completion.isShowing && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
        assertTrue("Production completion publisher must display the real PopupWindow", completion.isShowing)
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
    }

    private fun assertActualBounds(editor: DroideCodeEditor, completion: DroideEditorAutoCompletion) {
        val p = requireNotNull(completion.placement)
        val v = requireNotNull(completion.visibleViewport())
        assertTrue(p.left >= v.left && p.top >= v.top)
        assertTrue(p.left + p.width <= v.right && p.top + p.height <= v.bottom)
        assertEquals(p.width, completion.popup.width)
        assertEquals(p.height, completion.popup.height)
        val origin = IntArray(2); val location = IntArray(2)
        editor.getLocationOnScreen(origin); completion.popup.contentView.getLocationOnScreen(location)
        assertEquals(p.left, location[0] - origin[0])
        assertEquals(p.top, location[1] - origin[1])
    }

    @Test fun realRightEdgePopupPreservesUsableWidthAndSelectingItInsertsTheSameCandidate() = fixture { editor, _, completion ->
        editor.setText("W")
        val characterWidth = editor.getCharOffsetX(0, 1) - editor.getCharOffsetX(0, 0)
        assertTrue("The mounted editor must measure a glyph", characterWidth > 0)
        val prefix = "W".repeat(kotlin.math.ceil(editor.width / characterWidth).toInt() + 16)
        editor.setText(prefix + "sy")
        editor.setSelection(0, prefix.length + 2)
        assertTrue("Exercise the clipped right edge, including horizontal document scroll", editor.getCharOffsetX(0, prefix.length + 2) > editor.width - 100)
        awaitPopup(completion)
        assertEquals(320, completion.popup.width)
        assertActualBounds(editor, completion)
        assertTrue(completion.select(0))
        assertEquals(prefix + "symbol", editor.text.toString())
        assertFalse(completion.isShowing)
        assertFalse(completion.observesViewport)
    }

    @Test fun bottomCaretUsesAboveSpaceAndDoesNotScrollTheDocumentToPositionSuggestions() = fixture { editor, _, completion ->
        assertTrue(editor.rowHeight > 0)
        val lastVisibleLine = (editor.height / editor.rowHeight - 2).coerceAtLeast(1)
        editor.setText((0..lastVisibleLine).joinToString("\n") { "sy" })
        editor.setSelection(lastVisibleLine, 2)
        val offset = editor.offsetY
        awaitPopup(completion)
        assertTrue(requireNotNull(completion.placement).above)
        assertEquals(offset, editor.offsetY)
        assertActualBounds(editor, completion)
    }

    @Test fun parentClippingAndVisibleLayoutResizeRepositionAnAlreadyShowingPopup() = fixture { editor, root, completion ->
        editor.setText("sy")
        editor.setSelection(0, 2)
        awaitPopup(completion)
        root.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 300, 180)
        root.viewTreeObserver.dispatchOnGlobalLayout()
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        assertTrue(completion.isShowing)
        assertTrue(completion.popup.width < 320)
        assertActualBounds(editor, completion)
    }

    @Test fun hideDisableDetachAndReleaseRemoveViewportObservationAndNeverShowAnEmptyList() = fixture { editor, root, completion ->
        editor.setText("sy"); editor.setSelection(0, 2)
        awaitPopup(completion)
        assertTrue(completion.observesViewport)
        completion.hide()
        assertFalse(completion.observesViewport)
        awaitPopup(completion)
        completion.setEnabled(false)
        assertFalse(completion.observesViewport)
        assertFalse(completion.isShowing)
        completion.setEnabled(true)
        awaitPopup(completion)
        root.removeView(editor)
        assertFalse(completion.observesViewport)
        assertFalse(completion.isShowing)
        editor.release()
        root.viewTreeObserver.dispatchOnGlobalLayout()
        assertEquals(0, editor.pendingEditorCallbackCount)
    }
}
