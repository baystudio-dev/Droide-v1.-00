package com.baystudio.droide.ui

import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.baystudio.droide.core.CodeStyleProfile
import com.baystudio.droide.core.IndentStyle
import com.baystudio.droide.core.LspCompletionApplicationGuard
import com.baystudio.droide.core.LspCompletionCaret
import com.baystudio.droide.core.LspCompletionItem
import com.baystudio.droide.core.LspCompletionPolicy
import com.baystudio.droide.core.LspDiagnostic
import com.baystudio.droide.core.LspManager
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.CodeEditor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DroideCompletionThreadSafetyTest {
    private class Worker(val thread: Thread, val failure: AtomicReference<Throwable?>)

    private fun worker(action: () -> Unit): Worker {
        val failure = AtomicReference<Throwable?>()
        val thread = Thread({ try { action() } catch (error: Throwable) { failure.set(error) } }, "completion-read-test")
        thread.start()
        return Worker(thread, failure)
    }

    private fun awaitCondition(pumpMain: Boolean = true, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!condition() && System.nanoTime() < deadline) {
            if (pumpMain) ShadowLooper.idleMainLooper()
            Thread.sleep(2)
        }
        assertTrue("Bounded completion condition did not complete", condition())
    }

    private fun finish(work: Worker, pumpMain: Boolean = true) {
        try { awaitCondition(pumpMain) { !work.thread.isAlive } }
        finally {
            if (work.thread.isAlive) { work.thread.interrupt(); work.thread.join(1_000) }
        }
        assertFalse("Completion worker was left running", work.thread.isAlive)
        work.failure.get()?.let { throw AssertionError("Completion worker failed", it) }
        if (pumpMain) ShadowLooper.idleMainLooper()
    }

    private fun assertMain() = assertSame(Looper.getMainLooper(), Looper.myLooper())

    @Test fun workerUiReadRunsOnMainAndReturnsItsValue() {
        val value = AtomicReference<String?>()
        val work = worker { value.set(completionUiRead { assertMain(); "current" }) }
        finish(work)
        assertEquals("current", value.get())
    }

    @Test fun uiReadFailurePropagatesToWorkerWithoutEscapingMain() {
        val failure = IllegalStateException("capture failed")
        val caught = AtomicReference<Throwable?>()
        val work = worker {
            try { completionUiRead<String> { assertMain(); throw failure } }
            catch (error: Throwable) { caught.set(error) }
        }
        finish(work)
        assertSame(failure, caught.get())
    }

    @Test fun mainUiReadRunsInline() {
        assertMain()
        assertEquals("inline", completionUiRead { assertMain(); "inline" })
    }

    @Test fun timeoutDropsQueuedUiReadAndNeverRunsItLater() {
        val calls = AtomicInteger()
        val value = AtomicReference<String?>("not-finished")
        val work = worker { value.set(completionUiRead(40) { calls.incrementAndGet(); "late" }) }
        finish(work, pumpMain = false)
        assertNull(value.get())
        ShadowLooper.idleMainLooper()
        assertEquals(0, calls.get())
    }

    @Test fun interruptionRestoresFlagAndCancelsQueuedUiRead() {
        val calls = AtomicInteger()
        val restored = AtomicBoolean()
        val work = worker {
            assertNull(completionUiRead(2_000) { calls.incrementAndGet(); "late" })
            restored.set(Thread.currentThread().isInterrupted)
        }
        awaitCondition(pumpMain = false) { !ShadowLooper.shadowMainLooper().isIdle }
        work.thread.interrupt()
        finish(work, pumpMain = false)
        assertTrue(restored.get())
        ShadowLooper.idleMainLooper()
        assertEquals(0, calls.get())
    }

    @Test fun captureChecksBindingAndExactContentIdentityOnMain() {
        val editor = CodeEditor(RuntimeEnvironment.getApplication())
        try {
            editor.setText("same")
            val original = editor.text
            val current = AtomicBoolean(true)
            val checks = AtomicInteger()
            val capture = {
                assertMain()
                NativeCompletionEditorCapture(editor) { assertMain(); checks.incrementAndGet(); current.get() }
            }
            var result: NativeCompletionEditorCapture? = null
            finish(worker { result = captureCurrentNativeCompletionEditor(original, capture) })
            assertSame(editor, result?.owner)
            assertTrue(checks.get() > 0)
            editor.setText("same")
            assertNotSame(original, editor.text)
            finish(worker { result = captureCurrentNativeCompletionEditor(original, capture) })
            assertNull("Equal text in a replacement Content is a different owner", result)
            current.set(false)
            val replacement = editor.text
            finish(worker { result = captureCurrentNativeCompletionEditor(replacement, capture) })
            assertNull("Closed binding must be rejected", result)
        } finally { editor.release() }
    }

    @Test fun captureRejectsReleasedOwner() {
        val editor = CodeEditor(RuntimeEnvironment.getApplication())
        val content = editor.text
        editor.release()
        var result: NativeCompletionEditorCapture? = null
        finish(worker {
            result = captureCurrentNativeCompletionEditor(content) {
                assertMain()
                NativeCompletionEditorCapture(editor) { assertMain(); !editor.isReleased }
            }
        })
        assertNull(result)
    }

    private class Provider : LspManager {
        override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
        val beforePolicy = CountDownLatch(1)
        val continuePolicy = CountDownLatch(1)
        val requested = CountDownLatch(1)
        val continueResponse = CountDownLatch(1)
        val firstPolicy = AtomicBoolean(true)
        var capturedText: String? = null
        var capturedCaret: LspCompletionCaret? = null
        override suspend fun diagnose(relPath: String) = ""
        override suspend fun lsp(op: String, path: String, line: Int, col: Int) = ""
        override fun completionPolicy(path: String): LspCompletionPolicy {
            if (firstPolicy.compareAndSet(true, false)) {
                beforePolicy.countDown()
                check(continuePolicy.await(3, TimeUnit.SECONDS))
            }
            return LspCompletionPolicy.GRAPHQL_NATIVE
        }
        override suspend fun completionItems(path: String, line: Int, col: Int, triggerCharacter: String?, currentText: String?): List<LspCompletionItem> {
            check(Looper.myLooper() != Looper.getMainLooper())
            val source = requireNotNull(currentText)
            val caret = LspCompletionCaret(line, col)
            capturedText = source; capturedCaret = caret
            requested.countDown()
            check(continueResponse.await(3, TimeUnit.SECONDS))
            return listOf(LspCompletionItem("field", "field", editStartLine = 1, editStartColumn = 3,
                editEndLine = 1, editEndColumn = 5, applicationGuard = LspCompletionApplicationGuard(source, caret) { true },
                nativeOrigin = "graphql-local-schema", nativeBatch = 1L, nativeItemIdentity = "1:0"))
        }
    }

    private fun nativeRequest(
        beforeDebounce: (CodeEditor) -> Unit = {},
        duringProvider: (CodeEditor, AtomicBoolean) -> Unit = { _, _ -> },
        checkResult: (CodeEditor, CompletionPublisher, Provider) -> Unit,
    ) {
        val editor = CodeEditor(RuntimeEnvironment.getApplication())
        val provider = Provider()
        val ownerCurrent = AtomicBoolean(true)
        var running: Worker? = null
        try {
            editor.setText("{ f }"); editor.setSelection(0, 3)
            val source = editor.text
            val language = DroideCompletionLanguage("query.gql", "graphql", provider,
                CodeStyleProfile(IndentStyle.SPACES, 2, 2, 4, "test"), captureNativeCompletionEditor = {
                    assertMain()
                    NativeCompletionEditorCapture(editor) { assertMain(); ownerCurrent.get() && !editor.isReleased }
                })
            val publisher = CompletionPublisher(Handler(Looper.getMainLooper()), {}, Language.INTERRUPTION_LEVEL_STRONG)
            running = worker { language.requireAutoComplete(ContentReference(source), CharPosition(0, 3, 3), publisher, Bundle()) }
            awaitCondition { provider.beforePolicy.count == 0L }
            beforeDebounce(editor)
            provider.continuePolicy.countDown()
            awaitCondition { provider.requested.count == 0L }
            duringProvider(editor, ownerCurrent)
            provider.continueResponse.countDown()
            finish(running)
            checkResult(editor, publisher, provider)
        } finally {
            provider.continuePolicy.countDown(); provider.continueResponse.countDown()
            running?.let { if (it.thread.isAlive) { it.thread.interrupt(); it.thread.join(1_000) } }
            editor.release(); ShadowLooper.idleMainLooper()
        }
    }

    @Test fun postDebounceSnapshotUsesFreshUnsavedTextAndNativeCaret() = nativeRequest(
        beforeDebounce = { editor -> editor.text.replace(0, 0, 0, 5, "{ fi }"); editor.setSelection(0, 4) },
    ) { editor, publisher, provider ->
        assertEquals("{ fi }", provider.capturedText)
        assertEquals(LspCompletionCaret(1, 5), provider.capturedCaret)
        assertEquals(1, publisher.items.size)
        publisher.items.single().performCompletion(editor, editor.text, 0, 4)
        assertEquals("{ field }", editor.text.toString())
    }

    @Test fun heldCompletionRejectsReleasedOwnerAtApplication() = nativeRequest(
        beforeDebounce = { editor -> editor.text.replace(0, 0, 0, 5, "{ fi }"); editor.setSelection(0, 4) },
    ) { editor, publisher, _ ->
        assertEquals(1, publisher.items.size)
        val original = editor.text.toString()
        editor.release()
        publisher.items.single().performCompletion(editor, editor.text, 0, 4)
        assertEquals(original, editor.text.toString())
    }

    @Test fun textChangeWhileProviderRunsCannotPublishOldCompletion() = nativeRequest(
        beforeDebounce = { editor -> editor.text.replace(0, 0, 0, 5, "{ fi }"); editor.setSelection(0, 4) },
        duringProvider = { editor, _ -> editor.text.insert(0, 4, "x") },
    ) { editor, publisher, _ -> assertTrue(publisher.items.isEmpty()); assertEquals("{ fix }", editor.text.toString()) }

    @Test fun caretMoveWhileProviderRunsCannotPublishOldCompletion() = nativeRequest(
        beforeDebounce = { editor -> editor.text.replace(0, 0, 0, 5, "{ fi }"); editor.setSelection(0, 4) },
        duringProvider = { editor, _ -> editor.setSelection(0, 3) },
    ) { _, publisher, _ -> assertTrue(publisher.items.isEmpty()) }

    @Test fun retiredBindingWhileProviderRunsCannotPublishOldCompletion() = nativeRequest(
        beforeDebounce = { editor -> editor.text.replace(0, 0, 0, 5, "{ fi }"); editor.setSelection(0, 4) },
        duringProvider = { _, current -> current.set(false) },
    ) { _, publisher, _ -> assertTrue(publisher.items.isEmpty()) }
}
