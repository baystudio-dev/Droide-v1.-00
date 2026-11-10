package com.baystudio.droide.core

import android.os.Looper
import android.webkit.WebView
import java.lang.ref.WeakReference

/** Process-global timers are paused only while every Activity is hidden, never for a pane change. */
internal class BrowserApplicationTimers(private val reportFailure: (Throwable) -> Unit = {}) {
    private val views = mutableListOf<WeakReference<WebView>>()
    private var owner: WeakReference<WebView>? = null
    @Volatile private var visible = true
    @Volatile private var suspensionPossible = false
    private var pauseConfirmed = false
    val readyForActions: Boolean get() = visible && !suspensionPossible

    /** One explicit operation may retry a failed native resume; never poll for readiness. */
    fun requireForegroundReady(view: WebView) {
        checkMainThread()
        check(visible && liveViews().any { it === view }) { "Browser timer owner is not visible or attached" }
        if (suspensionPossible) resume(view)
        check(!suspensionPossible) {
            "Browser could not resume its JavaScript timers. Retry the browser operation when ready; no page action was performed."
        }
    }

    fun setApplicationVisible(value: Boolean) {
        checkMainThread()
        visible = value
        val available = liveViews().lastOrNull() ?: return
        if (visible) resume(available) else pause(available)
    }

    fun attach(view: WebView) {
        checkMainThread()
        if (liveViews().none { it === view }) views += WeakReference(view)

        if (suspensionPossible && owner?.get() == null) resume(view)
        if (visible) resume(view) else pause(view)
    }

    fun detach(view: WebView, rendererGone: Boolean = false) {
        checkMainThread()
        views.removeAll { it.get() == null || it.get() === view }
        if (owner?.get() !== view) return
        val replacement = liveViews().lastOrNull()
        if (replacement != null) resume(replacement)
        else if (!rendererGone) resume(view)
        // Never call through or retain a destroyed renderer. A failed resume remains pending and is
        // retried via the next attached live view.
        owner = null
        pauseConfirmed = false
        if (!visible && replacement != null) pause(replacement)
    }

    fun close() {
        checkMainThread()
        visible = true
        liveViews().lastOrNull()?.let(::resume)
        views.clear()
        owner = null
    }

    private fun liveViews(): List<WebView> {
        views.removeAll { it.get() == null }
        return views.mapNotNull { it.get() }
    }

    private fun pause(view: WebView) {
        if (pauseConfirmed && owner?.get() != null) return
        owner = WeakReference(view)
        suspensionPossible = true
        pauseConfirmed = false
        try {
            view.pauseTimers()
            pauseConfirmed = true
        } catch (failure: Exception) { reportFailure(failure) }
        catch (failure: LinkageError) { reportFailure(failure) }
    }

    private fun resume(view: WebView) {
        if (!suspensionPossible) return
        try {
            view.resumeTimers()
            suspensionPossible = false
            pauseConfirmed = false
            owner = null
        } catch (failure: Exception) { pauseConfirmed = false; reportFailure(failure) }
        catch (failure: LinkageError) { pauseConfirmed = false; reportFailure(failure) }
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper()) {
        "Browser timer ownership requires the UI thread"
    }
}
