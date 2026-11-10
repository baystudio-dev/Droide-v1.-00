package com.baystudio.droide.ui

import android.app.Application
import android.os.Looper
import com.baystudio.droide.core.CodeStyleProfile
import com.baystudio.droide.core.IndentStyle
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.layout.Layout
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EditorLifecycleBoundaryTest {
    private fun editor() = DroideCodeEditor(RuntimeEnvironment.getApplication(), "plaintext",
        codeStyle = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "test")).apply {
        setText("unsaved document\nsecond line")
        layout(0, 0, 360, 640)
    }

    @Test fun resizeFailureIsReportedOnceAndPreservesDocument() {
        val editor = editor()
        val failures = mutableListOf<Throwable>()
        editor.onEditorFailure = { failures += it }
        val field = CodeEditor::class.java.getDeclaredField("layout").apply { isAccessible = true }
        val original = field.get(editor)
        field.set(editor, Proxy.newProxyInstance(Layout::class.java.classLoader, arrayOf(Layout::class.java)) { _, method, args ->
            if (method.name == "getLayoutHeight") throw IllegalStateException("Rejected resize layout")
            method.invoke(original, *(args ?: emptyArray()))
        })
        try {
            editor.layout(0, 0, 720, 180)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Rejected resize layout", failures.single().message)
            assertFalse(editor.acceptsFeatureUpdates)
            assertEquals("unsaved document\nsecond line", editor.text.toString())
            editor.layout(0, 0, 360, 640)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, failures.size)
        } finally { field.set(editor, original); editor.release() }
    }

    @Test fun removeCallbacksCancelsAllPostsOfSameRunnable() {
        val editor = editor()
        var runs = 0
        val action = Runnable { runs++ }
        try {
            editor.postInLifecycle(action)
            editor.postDelayedInLifecycle(action, 100)
            editor.removeCallbacks(action)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            assertEquals("Canceled editor work must never run", 0, runs)
        } finally { editor.release() }
    }

    @Test fun releasedEditorDoesNotReenterNativeLayout() {
        val editor = editor()
        editor.release()
        val field = CodeEditor::class.java.getDeclaredField("layout").apply { isAccessible = true }
        val original = field.get(editor)
        var calls = 0
        field.set(editor, Proxy.newProxyInstance(Layout::class.java.classLoader, arrayOf(Layout::class.java)) { _, method, args ->
            calls++
            method.invoke(original, *(args ?: emptyArray()))
        })
        try {
            editor.layout(0, 0, 720, 180)
            assertEquals("Detached/released view must not restart layout work", 0, calls)
        } finally { field.set(editor, original) }
    }

    @Test fun releaseDropsPendingCapturesAndDoesNotCancelOtherEditors() {
        val first = editor()
        val second = editor()
        var runs = 0
        val action = Runnable { runs++ }
        repeat(200) { first.postDelayedInLifecycle(action, 60_000) }
        second.postDelayedInLifecycle(action, 100)
        first.release()
        assertEquals(0, first.pendingEditorCallbackCount)
        assertFalse(first.postInLifecycle(action))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertEquals(1, runs)
        second.release()
        assertEquals(0, second.pendingEditorCallbackCount)
    }

    @Test fun runnableCanRescheduleAndBeCancelledByOriginalIdentity() {
        val editor = editor()
        var runs = 0
        lateinit var action: Runnable
        action = Runnable { runs++; editor.postDelayedInLifecycle(action, 100) }
        try {
            editor.postInLifecycle(action)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            assertEquals(2, runs)
            editor.removeCallbacks(action)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(2, runs)
        } finally { editor.release() }
    }

    @Test fun closingCallbackOwnerWhileWorkerPostsLeavesNoRetainedWork() {
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            repeat(20) {
                val queue = EditorCallbackQueue()
                val start = java.util.concurrent.CountDownLatch(1)
                val submitted = worker.submit {
                    start.await()
                    repeat(200) { queue.post(Runnable {}, 60_000) { it.run() } }
                }
                start.countDown()
                queue.close()
                submitted.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(0, queue.pendingCount)
                assertFalse(queue.post(Runnable {}) { it.run() })
            }
        } finally { worker.shutdownNow() }
    }

    @Test fun fatalMemoryErrorIsNotConvertedIntoRecoverableSurfaceFailure() {
        val editor = editor()
        val failures = mutableListOf<Throwable>()
        editor.onEditorFailure = { failures += it }
        val field = CodeEditor::class.java.getDeclaredField("layout").apply { isAccessible = true }
        val original = field.get(editor)
        val fatal = OutOfMemoryError("fault injection only")
        field.set(editor, Proxy.newProxyInstance(Layout::class.java.classLoader, arrayOf(Layout::class.java)) { _, method, args ->
            if (method.name == "getLayoutHeight") throw fatal
            method.invoke(original, *(args ?: emptyArray()))
        })
        try {
            try { editor.layout(0, 0, 720, 180); fail("Fatal VM error must propagate") }
            catch (caught: OutOfMemoryError) { assertSame(fatal, caught) }
            assertTrue(failures.isEmpty())
        } finally { field.set(editor, original); editor.release() }
    }

    @Test fun repeatedPortraitLandscapeAndZeroViewportRetainUnsavedText() {
        val editor = editor()
        val failures = mutableListOf<Throwable>()
        editor.onEditorFailure = { failures += it }
        try {
            repeat(100) {
                editor.layout(0, 0, 0, 0)
                editor.layout(0, 0, 720, 72)
                editor.layout(0, 0, 360, 640)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(failures.toString(), failures.isEmpty())
            assertEquals("unsaved document\nsecond line", editor.text.toString())
        } finally { editor.release() }
    }
}
