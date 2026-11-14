package com.baystudio.droide.core

import kotlinx.coroutines.flow.first

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.SystemClock
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsResult
import android.webkit.JsPromptResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class BrowserSurfaceChangedException(message: String) : IllegalStateException(message)

private class BrowserSurfaceInvalidatedCancellation(message: String) : CancellationException(message)

class AgentBrowserController internal constructor(
    private val projectRoot: File,
    private val applicationTimers: BrowserApplicationTimers? = null,
) {
    data class Status(
        val attached: Boolean = false,
        val loading: Boolean = false,
        val url: String = "",
        val title: String = "",
        val lastError: String? = null,
        val console: List<String> = emptyList(),
    )

    data class Binding internal constructor(val generation: Long)

    /** Opaque document/renderer ownership captured before permission approval can suspend. */
    class DocumentIdentity internal constructor(
        internal val leaseToken: Any?,
        internal val pageGeneration: Long,
        val url: String,
        internal val authorityKey: String,
    )

    data class ObservationSource(val url: String, val document: DocumentIdentity)

    /** Only these captured sources may authorize this immutable output. Never resample currentUrl. */
    data class Observation internal constructor(
        val payload: String,
        val sources: List<ObservationSource>,
        internal val media: ChatMediaRef? = null,
        internal val mediaOwnerId: String? = null,
        internal val hasUnknownSource: Boolean = false,
        internal val outputConsent: BrowserOutputConsent.Receipt? = null,
    )

    /** Privately frozen native diagnostics, never substituted with a newer deque after ASK. */
    class FrozenConsoleSnapshot internal constructor(
        internal val identity: Any,
        internal val document: DocumentIdentity,
        internal val observation: Observation,
        internal val start: Int,
        internal val end: Int,
    )

    /** Opaque admission ticket for queued tools, including their permission/hook waits. */
    class ToolWork internal constructor(internal val epoch: Long) {
        internal val invalidated = CompletableDeferred<String>()
        internal var started = false
        internal var released = false
        internal var executingCalls = 0
    }

    private class ToolWorkContext(val ticket: ToolWork) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ToolWorkContext>
    }

    /** A restrictive request-scoped predicate; never a reusable browser grant. */
    private class DispatchAuthority(val check: () -> Unit) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<DispatchAuthority>
    }

    /** Runtime-only user choice. A new process never starts a hidden browser implicitly. */
    data class RetentionState(
        val keepActive: Boolean = false,
        val activeOperations: Int = 0,
        val attached: Boolean = false,
        val presented: Boolean = false,
        val loading: Boolean = false,
        val stopping: Boolean = false,
        val captureReady: Boolean = false,
    )

    private data class ViewLease(
        val generation: Long,
        val view: WebView,
        val invalidated: CompletableDeferred<String> = CompletableDeferred(),
        val authorityId: String = java.util.UUID.randomUUID().toString(),
    )

    private data class PageIdentity(val generation: Long, val url: String)

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var openGeneration = 0L
    private var viewGeneration = 0L
    private var pageGeneration = 0L
    private var navigationAssignedGeneration = -1L
    private var browserScale: Float? = null
    private var applicationVisible = true
    private var surfaceStarted = true
    private var surfacePresented = false
    private var surfaceCaptureReady = false
    private var keepActive = false
    private var exitRequested = false
    private val ownedExitViews = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<WebView, Boolean>())
    private val exitSurfaceReleasers = java.util.IdentityHashMap<WebView, () -> Unit>()
    private var nativeReleaseFailure: Throwable? = null
    private val nativeReleaseRevision = MutableStateFlow(0L)

    /** Exact native destruction receipt, separate from logical detach/history capture. */
    fun reportNativeSurfaceReleased(view: WebView, failure: Throwable?) = synchronized(lock) {
        checkMainThread()
        if (!ownedExitViews.contains(view)) return@synchronized
        if (failure == null) {
            ownedExitViews.remove(view)
            exitSurfaceReleasers.remove(view)
        } else nativeReleaseFailure = failure
        nativeReleaseRevision.value += 1L
    }

    /** Release the original UI owners directly, including when no Compose frame will run. */
    fun releaseNativeSurfacesForExit() {
        checkMainThread()
        check(exitRequested)
        val releases = synchronized(lock) { ownedExitViews.map { view ->
            checkNotNull(exitSurfaceReleasers[view]) { "Browser surface has no Exit release owner" }
        } }
        OwnedResourceCleanup.run(*releases.toTypedArray()).getOrThrow()
    }

    suspend fun awaitNativeSurfacesReleasedForExit() {
        checkMainThread()
        check(exitRequested)
        nativeReleaseRevision.first {
            synchronized(lock) {
                check(nativeReleaseFailure == null) { "Browser native cleanup could not be confirmed; Exit remains paused" }
                ownedExitViews.isEmpty()
            }
        }
    }
    private var activeOperations = 0
    private var stopping = false
    private var stopTransitionInProgress = false
    private val toolWork = linkedSetOf<ToolWork>()
    private val retentionState = MutableStateFlow(RetentionState())
    val retention: StateFlow<RetentionState> = retentionState.asStateFlow()
    private var nativePaused = false
    private var appliedNativePause: Boolean? = false
    private var operationEpoch = 0L
    private var surfaceUnavailable: Pair<WebView, (WebView, String) -> Unit>? = null
    private val resourcePolicy = BrowserResourcePolicy()
    private var webView: WebView? = null
    private var viewLease: ViewLease? = null
    private var navigationState: Bundle? = null
    private var navigationLoopbackOrigin: String? = null
    private var requestOpen: (() -> Unit)? = null
    private var status = Status()
    private val navigationUpdates = MutableStateFlow(status)
    val navigation: StateFlow<Status> = navigationUpdates.asStateFlow()
    private data class ConsoleEntry(val line: String, val sourceUrl: String?)
    private val consoleLines = ArrayDeque<ConsoleEntry>()
    @Volatile private var allowLoopbackRequests = false
    @Volatile private var loopbackRequestOrigin: String? = null
    private val executeMutex = Mutex()
    private val dialogs = AgentBrowserDialogs()
    val pendingDialog: StateFlow<AgentBrowserDialogs.Dialog?> = dialogs.pending
    private val workspacePreview = WorkspaceHtmlPreview(projectRoot)

    fun htmlPreviewUrl(path: String): String = workspacePreview.urlFor(path)

    fun bindOpenRequest(open: () -> Unit): Binding = synchronized(lock) {
        openGeneration += 1L
        requestOpen = open
        Binding(openGeneration)
    }

    fun unbindOpenRequest(binding: Binding) = synchronized(lock) {
        if (binding.generation == openGeneration) requestOpen = null
    }

    private fun publishRetentionLocked() {
        navigationUpdates.value = status
        val outstanding = activeOperations + toolWork.count { it.executingCalls == 0 }
        retentionState.value = RetentionState(keepActive, outstanding, webView != null,
            webView != null && surfacePresented, status.loading, stopping,
            webView != null && surfacePresented && surfaceCaptureReady)
    }

    fun requestStopForExit() {
        checkMainThread()
        exitRequested = true
        setKeepActive(false, cancelActive = true)
    }

    suspend fun stopForExit() {
        requestStopForExit()
        retentionState.first { it.activeOperations == 0 && !it.stopping }
    }

    /** False means the caller must obtain confirmation before stopping browser work. */
    fun setKeepActive(enabled: Boolean, cancelActive: Boolean = false): Boolean {
        checkMainThread()
        return synchronized(lock) {
            if (enabled) {
                check(!exitRequested) { "Browser is stopped for Exit" }
                if (stopping) return@synchronized false
                keepActive = true
                publishRetentionLocked()
                return@synchronized true
            }
            if (stopping) return@synchronized true
            val busy = activeOperations > 0 || toolWork.isNotEmpty() || status.loading || pendingDialog.value != null
            if (busy && !cancelActive) return@synchronized false
            keepActive = false
            if (busy) {
                // Advance before invalidating ownership: queued operations must never
                // acquire a new lease and replay after the user chose Stop.
                operationEpoch++
                stopping = true
                stopTransitionInProgress = true
                toolWork.toList().forEach { ticket ->
                    ticket.invalidated.complete("Browser work was stopped by the user; this queued tool was not replayed")
                    if (!ticket.started) {
                        ticket.released = true
                        toolWork.remove(ticket)
                    }
                }
                viewLease?.invalidated?.complete("Browser work was stopped by the user; inspect the page before retrying")
                dialogs.dismiss()
                webView?.let { view ->
                    try { view.stopLoading() }
                    catch (failure: Exception) { status = status.copy(lastError = "Browser stop failed: ${failure.javaClass.simpleName}") }
                    catch (failure: LinkageError) { status = status.copy(lastError = "Browser stop failed: ${failure.javaClass.simpleName}") }
                }
                status = status.copy(loading = false)
                stopTransitionInProgress = false
                finishStoppingLocked()
            }
            publishRetentionLocked()
            true
        }
    }

    /** Presentation changes never replace a lease or pause a foreground retained page. */
    fun setSurfacePresented(binding: Binding, view: WebView, presented: Boolean, captureReady: Boolean = presented) {
        checkMainThread()
        synchronized(lock) {
            if (binding.generation != viewGeneration || webView !== view) return
            surfacePresented = presented
            surfaceCaptureReady = presented && captureReady
            publishRetentionLocked()
        }
    }

    private fun finishStoppingLocked() {
        if (!stopping || stopTransitionInProgress || activeOperations != 0 || toolWork.any { it.started }) return
        stopping = false
        // Every old operation, including capture restoration, has left its finally.
        // A paused view gets its next lease only through the normal resume path.
        webView?.takeIf { !nativePaused && applicationVisible && surfaceStarted }?.let {
            viewLease = ViewLease(viewGeneration, it)
        }
    }

    private fun finishOperation(ticket: ToolWork?) = synchronized(lock) {
        check(activeOperations > 0)
        activeOperations--
        ticket?.let { check(it.executingCalls > 0); it.executingCalls-- }
        finishStoppingLocked()
        publishRetentionLocked()
    }

    fun registerToolWork(): ToolWork = registerToolWorkBatch(1).single()

    fun registerToolWorkBatch(count: Int): List<ToolWork> = synchronized(lock) {
        check(!exitRequested && ApplicationWorkAdmission.isOpen) { "Browser is stopped for Exit" }
        require(count >= 0) { "Browser ticket count cannot be negative" }
        List(count) {
            ToolWork(operationEpoch).also { ticket ->
                if (stopping) {
                    ticket.released = true
                    ticket.invalidated.complete("Browser work is stopping; wait for cleanup before retrying")
                } else toolWork += ticket
            }
        }.also {
            publishRetentionLocked()
        }
    }

    fun releaseToolWork(ticket: ToolWork) {
        synchronized(lock) {
            if (ticket.released) return
            ticket.released = true
            toolWork.remove(ticket)
            finishStoppingLocked()
            publishRetentionLocked()
        }
    }

    private fun requireToolWorkLocked(ticket: ToolWork) {
        if (stopping || ticket.released || ticket.invalidated.isCompleted || ticket.epoch != operationEpoch || ticket !in toolWork) {
            throw BrowserSurfaceChangedException("Browser tool was stopped or its surface changed while queued; inspect the page before retrying. It was not replayed.")
        }
    }

    suspend fun <T> withToolWork(ticket: ToolWork, block: suspend () -> T): T {
        try {
            synchronized(lock) {
                requireToolWorkLocked(ticket)
                check(!ticket.started) { "Browser tool ticket is already in use" }
                ticket.started = true
                publishRetentionLocked()
            }
            return withContext(ToolWorkContext(ticket)) {
                coroutineScope {
                    val toolJob = currentCoroutineContext().job
                    val stopObserver = launch(start = CoroutineStart.UNDISPATCHED) {
                        toolJob.cancel(BrowserSurfaceInvalidatedCancellation(ticket.invalidated.await()))
                    }
                    try {
                        synchronized(lock) { requireToolWorkLocked(ticket) }
                        block()
                    } finally { stopObserver.cancel() }
                }
            }
        } catch (changed: BrowserSurfaceInvalidatedCancellation) {
            currentCoroutineContext().ensureActive()
            throw BrowserSurfaceChangedException(changed.message ?: "Browser tool was stopped")
        } finally { releaseToolWork(ticket) }
    }

    /** Called by the process owner even when no browser pane currently exists. */
    fun setApplicationVisible(visible: Boolean) {
        checkMainThread()
        synchronized(lock) {
            if (applicationVisible && !visible && webView == null) operationEpoch++
            applicationVisible = visible
            applyLifecycleLocked()
        }
    }

    /** A replaced pane's lifecycle callback cannot pause its successor. */
    fun setSurfaceStarted(binding: Binding, view: WebView, started: Boolean) {
        checkMainThread()
        synchronized(lock) {
            if (binding.generation != viewGeneration || webView !== view) return
            surfaceStarted = started
            applyLifecycleLocked()
        }
    }

    private fun applyLifecycleLocked() {
        val view = webView ?: return
        val pause = !applicationVisible || !surfaceStarted
        if (nativePaused == pause && appliedNativePause == pause) return
        if (pause && !nativePaused) {
            operationEpoch++
            // Invalidate only operation ownership: keep document identity, forms, native
            // pending dialogs, local approval and cookies across HOME/login-app switches.
            viewLease?.invalidated?.complete("Browser paused while Droide is not visible; return to Droide before retrying")
        }
        if (pause) nativePaused = true
        try {
            if (pause) view.onPause() else view.onResume()
            appliedNativePause = pause
            if (status.lastError?.startsWith("Browser lifecycle failed:") == true) status = status.copy(lastError = null)
            if (!pause && nativePaused) {
                if (!stopping) viewLease = ViewLease(viewGeneration, view)
                nativePaused = false
            }
        } catch (failure: Exception) {
            appliedNativePause = null
            nativePaused = true
            viewLease?.invalidated?.complete("Browser lifecycle failed; retry reopening the browser")
            status = status.copy(lastError = "Browser lifecycle failed: ${failure.javaClass.simpleName}")
        } catch (failure: LinkageError) {
            appliedNativePause = null
            nativePaused = true
            viewLease?.invalidated?.complete("Browser lifecycle failed; retry reopening the browser")
            status = status.copy(lastError = "Browser lifecycle failed: ${failure.javaClass.simpleName}")
        }
        // Native onPause is best effort. Global JavaScript timers are managed separately
        // by the process owner only when every Activity is hidden.
        publishRetentionLocked()
    }

    /** Supplemental on Android 14+: old low-memory callbacks are no longer reliable. */
    fun onMemoryPressure(level: Int) {
        checkMainThread()
        synchronized(lock) {
            if (!resourcePolicy.isPressureSignal(level)) return
            val critical = resourcePolicy.onMemoryPressure(level, SystemClock.elapsedRealtime())
            consoleLines.clear()
            status = status.copy(console = emptyList())
            if (!critical) return
            // Release saved history without inspecting/serializing it under pressure.
            navigationState = null
            navigationLoopbackOrigin = null
            val view = webView ?: return
            if (applicationVisible && surfaceStarted && surfacePresented) return
            val message = "Browser released while hidden to reduce memory. Reopen when ready; unsaved page input may have been lost."
            val callback = surfaceUnavailable?.takeIf { it.first === view }?.second
            val generation = viewGeneration
            operationEpoch++
            invalidateViewLocked(message)
            webView = null
            surfacePresented = false
            surfaceUnavailable = null
            status = status.copy(attached = false, loading = false, lastError = message)
            publishRetentionLocked()
            applicationTimers?.detach(view)
            recoverableRendererCleanup(generation) { view.stopLoading() }
            recoverableRendererCleanup(generation) { (view.parent as? ViewGroup)?.removeView(view) }
            destroyRetiredRenderer(view, generation)
            callback?.invoke(view, message)
        }
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper()) {
        "Browser lifecycle requires the UI thread"
    }

    fun attach(view: WebView): Binding = synchronized(lock) {
        checkMainThread()
        check(!exitRequested) { "Browser is stopped for Exit" }
        if (webView != null && webView !== view) operationEpoch++
        // Responsive placement may attach its new AndroidView before releasing the old
        // one. Capture the still-live history here: stale detach must not touch the new
        // lease later, and cannot be relied on to save the previous surface's history.
        webView?.takeIf { it !== view && isCurrentViewLocked(it) }?.let { previous ->
            navigationState = null
            navigationLoopbackOrigin = null
            try {
                val localOrigin = reusableLoopbackOrigin(previous)
                val saved = Bundle()
                if (previous.saveState(saved)?.size?.let { it > 0 } == true) {
                    navigationState = saved
                    navigationLoopbackOrigin = localOrigin
                }
            } catch (_: Exception) {
                // History is best effort. A broken old renderer must not block the new
                // browser surface or leave a stale navigation bundle to restore.
            } catch (_: LinkageError) {
                // Android System WebView can fail independently of the new instance.
            }
        }
        invalidateViewLocked("Browser surface was replaced; retry on the active page")
        viewGeneration += 1L
        pageGeneration += 1L
        webView = view
        surfaceStarted = true
        surfacePresented = true
        surfaceCaptureReady = true
        nativePaused = false
        appliedNativePause = false
        viewLease = ViewLease(viewGeneration, view).also {
            if (stopping) it.invalidated.complete("Browser work is stopping; wait for cleanup before retrying")
        }
        status = status.copy(attached = true, loading = false, url = "", title = "", lastError = null)
        // Publish the new lease before restoreState: synchronous restoration callbacks belong
        // to this surface, while callbacks from the replaced surface are already stale.
        var attached = false
        try {
            navigationState?.let { saved ->
                val localOrigin = navigationLoopbackOrigin
                // Reuse only the already-approved origin belonging to this saved
                // workspace history. Restore callbacks cannot gain other local origins.
                loopbackRequestOrigin = localOrigin
                allowLoopbackRequests = localOrigin != null
                if (view.restoreState(saved) != null) {
                    navigationState = null
                    navigationLoopbackOrigin = null
                }
                if (localOrigin == null || loopbackOrigin(view.url.orEmpty()) != localOrigin) {
                    allowLoopbackRequests = false
                    loopbackRequestOrigin = null
                }
            }
            status = status.copy(url = view.url.orEmpty(), title = view.title.orEmpty())
            applyLifecycleLocked()
            applicationTimers?.attach(view)
            attached = true
            Binding(viewGeneration)
        } finally {
            if (!attached) {
                applicationTimers?.detach(view)
                if (surfaceUnavailable?.first === view) surfaceUnavailable = null
                invalidateViewLocked("Browser surface could not be restored; retry reopening the browser")
                webView = null
                surfacePresented = false
                status = status.copy(attached = false, loading = false)
            }
            publishRetentionLocked()
        }
    }

    fun detach(binding: Binding, view: WebView, preserveState: Boolean = true) = synchronized(lock) {
        checkMainThread()
        try {
            if (binding.generation == viewGeneration && webView === view) {
                operationEpoch++
                val localOrigin = reusableLoopbackOrigin(view)
                invalidateViewLocked("Browser surface was closed; retry after reopening the browser")
                try {
                    if (preserveState) {
                        navigationState = null
                        navigationLoopbackOrigin = null
                        val saved = Bundle()
                        if (view.saveState(saved)?.size?.let { it > 0 } == true) {
                            navigationState = saved
                            navigationLoopbackOrigin = localOrigin
                        }
                    }
                } finally {
                    webView = null
                    surfacePresented = false
                    if (surfaceUnavailable?.first === view) surfaceUnavailable = null
                    status = status.copy(attached = false, loading = false)
                }
            }
        } finally {
            // Even a stale/replaced pane owns its timer registration until destruction.
            applicationTimers?.detach(view)
            if (surfaceUnavailable?.first === view) surfaceUnavailable = null
            publishRetentionLocked()
        }
    }

    fun configure(view: WebView, onRendererGone: (WebView, String) -> Unit = { _, _ -> }) =
        configure(view, onExitRelease = null, onRendererGone = onRendererGone)

    fun configure(view: WebView, onExitRelease: (() -> Unit)?,
                  onRendererGone: (WebView, String) -> Unit = { _, _ -> }) {
        checkMainThread()
        synchronized(lock) {
            check(!exitRequested && ApplicationWorkAdmission.isOpen) { "Browser is stopped for Exit" }
            ownedExitViews.add(view)
            onExitRelease?.let { exitSurfaceReleasers[view] = it }
        }
        val configuredView = view
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            blockNetworkLoads = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        // Preserve the visible browser at the app's priority; let Android reclaim a
        // hidden renderer instead of protecting it as IMPORTANT indefinitely.
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
        if (android.os.Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                synchronized(lock) {
                    if (view !== configuredView || !ownsNativeRendererLocked(view)) return
                    status = status.copy(title = title.orEmpty().take(512)); publishRetentionLocked()
                }
            }
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean =
                showDialog(configuredView, view, "alert", message, null, result, url)

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean =
                showDialog(configuredView, view, "confirm", message, null, result, url)

            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean =
                showDialog(configuredView, view, "prompt", message, defaultValue, result, url)

            override fun onPermissionRequest(request: PermissionRequest) {
                // Agent browsing never gains camera/mic/device capabilities implicitly.
                request.deny()
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, false, false)
            }
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                // Bound before concatenation: hostile pages may log multi-megabyte strings.
                recordConsole(configuredView, "${message.messageLevel()}: ${message.message().take(960)} @${message.lineNumber()}", message.sourceId())
                return true
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                synchronized(lock) {
                    if (view !== configuredView || !ownsNativeRendererLocked(view) || view.url != url) return
                    if (status.url != url.orEmpty()) {
                        pageGeneration += 1L
                        status = status.copy(url = url.orEmpty(), title = view.title.orEmpty())
                        publishRetentionLocked()
                    }
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (view !== configuredView || !isCurrentView(view)) return true
                if (!request.isForMainFrame) return false
                val raw = request.url.toString()
                if (!quickTopLevelAllowed(raw, allowLoopbackRequests)) {
                    recordError(view, "Blocked navigation outside public HTTPS/approved loopback: ${request.url.host.orEmpty()}")
                    return true
                }
                return false
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                view !== configuredView || !isCurrentView(view) || !quickTopLevelAllowed(url, allowLoopbackRequests)

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (view !== configuredView || !isCurrentView(view)) return blockedRequest()
                val url = request.url.toString()
                if (workspacePreview.owns(url)) {
                    val resource = workspacePreview.open(url, request.method,
                        request.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value)
                    return WebResourceResponse(resource.mime, resource.encoding, resource.status,
                        resource.reason, resource.headers, resource.body)
                }
                // POST/XHR/fetch must not become a private-network escape hatch just because only GET was preflighted.

                return if (networkRequestAllowed(url, allowLoopbackRequests, request.isForMainFrame)) {
                    null
                } else {
                    blockedRequest()
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                synchronized(lock) {
                    if (view !== configuredView || !ownsNativeRendererLocked(view)) return
                    dialogs.dismiss()
                    pageGeneration += 1L
                    navigationAssignedGeneration = viewGeneration
                    consoleLines.clear()
                    status = status.copy(console = emptyList())
                    if (loopbackRequestOrigin != null && loopbackRequestOrigin != loopbackOrigin(url.orEmpty())) {
                        // A restored local page's trust does not follow navigation to
                        // another origin, including a public page.
                        allowLoopbackRequests = false
                        loopbackRequestOrigin = null
                    }
                    status = status.copy(loading = true, url = url.orEmpty(), title = "", lastError = null)
                    publishRetentionLocked()
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                synchronized(lock) {
                    if (view !== configuredView || !ownsNativeRendererLocked(view)) return
                    // A delayed finish from a superseded navigation must not pair the
                    // current renderer title with an old cached source URL.
                    if (view.url.orEmpty() != url.orEmpty()) return
                    status = status.copy(loading = false, url = url.orEmpty(), title = view.title.orEmpty())
                    publishRetentionLocked()
                }
            }

            override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
                synchronized(lock) {
                    if (view !== configuredView || !ownsNativeRendererLocked(view)) return
                    browserScale = newScale.takeIf { it.isFinite() && it > 0f }
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (view === configuredView && request.isForMainFrame && view.url == request.url.toString()) {
                    recordError(view, "${error.description} (${error.errorCode}) ${request.url}")
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (view === configuredView && request.isForMainFrame && view.url == request.url.toString() && response.statusCode >= 400) {
                    recordError(view, "HTTP ${response.statusCode} ${request.url}")
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val message = if (detail.didCrash()) "Browser renderer stopped unexpectedly. Retry to reopen the browser."
                    else "Android stopped the browser renderer. Retry to reopen the browser."
                val activeGeneration = synchronized(lock) {
                    if (view === configuredView && webView === view) {
                        val generation = viewGeneration
                        operationEpoch++
                        invalidateViewLocked(message)
                        webView = null
                        surfacePresented = false
                        if (surfaceUnavailable?.first === view) surfaceUnavailable = null
                        navigationState = null
                        navigationLoopbackOrigin = null
                        status = status.copy(attached = false, loading = false, lastError = message)
                        publishRetentionLocked()
                        generation
                    } else null
                }
                // A dead renderer must be removed and destroyed, never saved or used again.
                applicationTimers?.detach(view, rendererGone = true)
                recoverableRendererCleanup(activeGeneration) { (view.parent as? ViewGroup)?.removeView(view) }
                destroyRetiredRenderer(view, activeGeneration)
                if (activeGeneration != null) onRendererGone(view, message)
                return true
            }
        }
        surfaceUnavailable = view to onRendererGone
    }

    private fun recoverableRendererCleanup(generation: Long?, block: () -> Unit) {
        try { block() }
        catch (failure: Exception) { recordCleanupError(generation, failure) }
        catch (failure: LinkageError) { recordCleanupError(generation, failure) }
    }

    private fun destroyRetiredRenderer(view: WebView, generation: Long?) {
        var failure: Throwable? = null
        try { view.destroy() }
        catch (error: Exception) { failure = error; recordCleanupError(generation, error) }
        catch (error: LinkageError) { failure = error; recordCleanupError(generation, error) }
        // A renderer gone/low-memory callback can clear the pane's view before disposal.
        // Record its actual destruction here, otherwise Exit waits forever for that pane.
        reportNativeSurfaceReleased(view, failure)
    }

    private fun recordCleanupError(generation: Long?, failure: Throwable) = synchronized(lock) {
        if (generation != null && viewGeneration == generation && webView == null) {
            status = status.copy(lastError = "Browser cleanup failed: ${failure.message ?: "WebView unavailable"}".take(1_000), loading = false)
        }
    }

    private fun blockedRequest() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked by Droide",
        emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))

    // Native document callbacks must track same-URL reloads even while Stop is
    // canceling an operation. Otherwise capture cleanup could scroll a new page.
    // This is identity only: it never admits actions, network requests or dialogs.
    private fun ownsNativeRendererLocked(view: WebView): Boolean = webView === view &&
        viewLease?.let { it.view === view && it.generation == viewGeneration } == true

    private fun isCurrentViewLocked(view: WebView): Boolean = !stopping && ownsNativeRendererLocked(view) &&
        viewLease?.let { it.view === view && (nativePaused || !it.invalidated.isCompleted) } == true

    private fun isCurrentView(view: WebView): Boolean = synchronized(lock) { isCurrentViewLocked(view) }

    private fun invalidateViewLocked(reason: String) {
        dialogs.dismiss()
        viewLease?.invalidated?.complete(reason)
        viewLease = null
        allowLoopbackRequests = false
        loopbackRequestOrigin = null
        browserScale = null
        consoleLines.clear()
        status = status.copy(console = emptyList())
    }

    private fun requireActiveLease(lease: ViewLease) = synchronized(lock) {
        if (stopping || viewLease !== lease || lease.invalidated.isCompleted || nativePaused || !isCurrentViewLocked(lease.view)) {
            throw BrowserSurfaceInvalidatedCancellation("Browser surface changed; retry on the active page")
        }
    }

    private fun showDialog(configured: WebView, view: WebView, type: String, message: String,
        defaultText: String?, result: JsResult, sourceUrl: String?): Boolean = synchronized(lock) {
        if (view !== configured || !isCurrentViewLocked(view)) {
            completeNativeDialog(view, result, accept = false, promptText = null)
            return true
        }
        val owner = AgentBrowserDialogs.Owner(viewGeneration, pageGeneration, view.url.orEmpty().ifBlank { status.url })
        dialogs.show(owner, type, message, defaultText, sourceUrl) { accept, promptText ->
            completeNativeDialog(view, result, accept, promptText)
        }
        true
    }

    private fun completeNativeDialog(view: WebView, result: JsResult, accept: Boolean, promptText: String?): Boolean =
        try {
            if (!accept) result.cancel()
            else if (result is JsPromptResult) result.confirm(requireNotNull(promptText))
            else result.confirm()
            true
        } catch (failure: Exception) {
            recordError(view, "Browser dialog could not be completed: ${failure.javaClass.simpleName}")
            false
        } catch (failure: LinkageError) {
            recordError(view, "Browser dialog could not be completed: ${failure.javaClass.simpleName}")
            false
        }

    /** The visible human dialog is independent of Agent approval and its execution mutex. */
    fun respondHumanDialog(id: String, accept: Boolean, promptText: String? = null): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Human browser dialog responses require the UI thread" }
        return synchronized(lock) {
            val view = webView ?: return@synchronized false
            val owner = dialogs.owner() ?: return@synchronized false
            if (pendingDialog.value?.id != id) return@synchronized false
            if (!isCurrentViewLocked(view) || owner.viewGeneration != viewGeneration || owner.pageGeneration != pageGeneration ||
                owner.url != view.url.orEmpty().ifBlank { status.url }) {
                dialogs.dismiss()
                return@synchronized false
            }
            if (!dialogs.respond(id, owner, accept, promptText)) {
                error("Android could not complete the browser dialog; inspect browser status or reopen it")
            }
            true
        }
    }

    private fun dialogWatch(lease: ViewLease): AgentBrowserDialogs.Watch = synchronized(lock) {
        requireActiveLease(lease)
        dialogs.watch(lease.generation, pageGeneration)
    }

    private fun documentIdentityLocked(lease: ViewLease? = viewLease, url: String = status.url): DocumentIdentity =
        DocumentIdentity(lease, pageGeneration, url, "${lease?.authorityId ?: "unmounted"}:$pageGeneration")

    /** Read-only admission metadata: never mounts, resumes, evaluates or dismisses. */
    internal suspend fun sampleCurrentUrlForPreflight(): String = onMainResult {
        synchronized(lock) { webView?.url.orEmpty().ifBlank { status.url } }
    }

    /** Known callback sources in this requested page only; not frame attestation. */
    internal fun cachedConsoleSourceUrls(start: Int, count: Int): List<String> = synchronized(lock) {
        val entries = consoleLines.toList()
        val snapshot = AgentBrowserConsole.snapshot(status.url, status.title, entries.map { it.line }, start, count)
        val end = requireNotNull(snapshot["end_line"]?.jsonPrimitive?.contentOrNull?.toIntOrNull())
        entries.drop(start).take(end - start).mapNotNull { it.sourceUrl }.distinct()
    }

    internal suspend fun freezeConsole(start: Int, count: Int, expected: DocumentIdentity): FrozenConsoleSnapshot = onMainResult {
        synchronized(lock) {
            require(expected.leaseToken != null) { "Console review requires an existing browser document" }
            requireReviewIdentity(expected)
            require(webView?.url.orEmpty().ifBlank { status.url } == expected.url) { "Browser URL changed before console review" }
            val entries = consoleLines.toList()
            val snapshot = AgentBrowserConsole.snapshot(status.url, status.title, entries.map { it.line }, start, count)
            val end = requireNotNull(snapshot["end_line"]?.jsonPrimitive?.contentOrNull?.toIntOrNull())
            val returned = entries.drop(start).take(end - start)
            val observation = Observation(AgentWebTools.bounded(snapshot), buildList {
                add(ObservationSource(expected.url, expected))
                returned.mapNotNull { it.sourceUrl }.distinct().filter { it != expected.url }
                    .forEach { add(ObservationSource(it, expected)) }
            }, hasUnknownSource = returned.any { it.sourceUrl == null })
            FrozenConsoleSnapshot(Any(), expected, observation, start, end)
        }
    }

    suspend fun captureReviewIdentity(): DocumentIdentity = onMainResult {
        synchronized(lock) {
            val view = webView
            val actual = view?.url.orEmpty().ifBlank { status.url }
            // History API / same-document URL changes need not emit onPageStarted.
            // Reconcile on the UI thread so their stale cached URL cannot permanently
            // reject fresh tools, or relabel old diagnostics as belonging to a new route.
            if (view != null && actual != status.url) {
                pageGeneration++
                dialogs.dismiss()
                consoleLines.clear()
                status = status.copy(url = actual, title = view.title.orEmpty(), lastError = null, console = emptyList())
                publishRetentionLocked()
            }
            documentIdentityLocked(url = actual)
        }
    }

    fun isReviewIdentityCurrent(expected: DocumentIdentity): Boolean = synchronized(lock) {
        viewLease === expected.leaseToken && pageGeneration == expected.pageGeneration &&
            status.url == expected.url && viewLease?.invalidated?.isCompleted != true
    }

    private fun requireReviewIdentity(expected: DocumentIdentity) = synchronized(lock) {
        if (!isReviewIdentityCurrent(expected)) {
            throw BrowserSurfaceChangedException("Browser document changed during permission review; inspect the current page and request fresh approval. No action was performed.")
        }
    }

    private fun dialogObservation(lease: ViewLease, dialog: AgentBrowserDialogs.Dialog?, operation: String? = null): Observation =
        synchronized(lock) {
            requireActiveLease(lease)
            val owner = dialogs.owner()
            if (dialog != null) require(owner != null && owner.viewGeneration == lease.generation &&
                owner.pageGeneration == pageGeneration && pendingDialog.value?.id == dialog.id) {
                "Browser dialog changed while observing it; retry the active page"
            }
            val document = documentIdentityLocked(lease, dialog?.url ?: status.url)
            Observation(dialogs.observation(dialog, operation), buildList {
                add(ObservationSource(document.url, document))
                dialog?.sourceUrl?.takeIf { it != document.url }?.let { add(ObservationSource(it, document)) }
            }, hasUnknownSource = dialog != null && dialog.sourceUrl == null)
        }

    private suspend fun withDialogObservation(lease: ViewLease, operation: String, block: suspend () -> Observation): Observation {
        if (operation in setOf("status", "console")) return block()
        return supervisorScope {
            val watch = dialogWatch(lease)
            watch.dialog?.let { return@supervisorScope dialogObservation(lease, it, operation) }
            val completed = java.util.concurrent.atomic.AtomicReference<Observation?>()
            var delivered: Observation? = null
            val action = async(start = CoroutineStart.UNDISPATCHED) { block().also(completed::set) }
            try {
                select<Observation> {
                    watch.changed.onAwait {
                        val pending = dialogWatch(lease).dialog
                        if (pending != null) dialogObservation(lease, pending, operation) else action.await()
                    }
                    action.onAwait { result ->
                        val pending = dialogWatch(lease).dialog
                        if (pending != null) dialogObservation(lease, pending, operation) else result
                    }
                }.also { delivered = it }
            } finally {
                action.cancel()
                withContext(NonCancellable) {
                    action.join()
                    completed.get()?.takeIf { it !== delivered }?.let { discardObservation(it) }
                }
            }
        }
    }

    private suspend fun executeDialogControl(operation: String, id: String?, resolution: String?, dialogText: String?,
        expectedCurrentUrl: String?, allowLoopback: Boolean, reviewedLoopbackOrigin: String?, admissionEpoch: Long,
        expectedDocument: DocumentIdentity?, expectedDialog: AgentBrowserDialogs.Dialog?): Observation {
        requestSurface(admissionEpoch)
        val lease = awaitView(admissionEpoch)
        requireAdmission(admissionEpoch)
        return withLease(lease) {
            onMain(lease) { applicationTimers?.requireForegroundReady(lease.view) }
            val reviewedDocument = onMainResult(lease) { synchronized(lock) {
                expectedDocument?.let(::requireReviewIdentity)
                documentIdentityLocked(lease, lease.view.url.orEmpty().ifBlank { status.url })
            } }
            val approvedPage = reviewedDocument.url
            if (expectedCurrentUrl != null) require(approvedPage == expectedCurrentUrl) {
                "Browser page changed after permission review; retry on the active page"
            }
            validateTopLevel(approvedPage, allowLoopback)
            expectedDialog?.sourceUrl?.takeIf { it != approvedPage }?.let { source ->
                validateTopLevel(source, AgentRepairCompletionPolicy.classifyBrowserTarget(source) == AgentRepairCompletionPolicy.BrowserTargetClass.LOOPBACK_HTTP)
            }
            onMainResult(lease) {
                synchronized(lock) {
                    requireReviewIdentity(reviewedDocument)
                    require(lease.view.url.orEmpty().ifBlank { status.url } == approvedPage) {
                        "Browser page changed while reviewing its dialog; retry on the active page"
                    }
                    if (reviewedLoopbackOrigin != null) {
                        val reviewed = loopbackOrigin(reviewedLoopbackOrigin)
                        require(allowLoopback && reviewed != null && reviewed == loopbackOrigin(approvedPage)) {
                            "Reviewed localhost origin does not match the active browser page"
                        }
                        allowLoopbackRequests = true
                        loopbackRequestOrigin = reviewed
                    }
                    val watch = dialogs.watch(lease.generation, pageGeneration)
                    if (operation == "dialog") return@synchronized dialogObservation(lease, watch.dialog)
                    require(resolution in setOf("accept", "dismiss")) { "dialog_respond text must be accept or dismiss" }
                    val dialogId = requireNotNull(id?.takeIf { it.isNotBlank() }) { "dialog_respond requires target dialog id" }
                    if (expectedDialog != null) require(watch.dialog == expectedDialog && dialogId == expectedDialog.id) {
                        "Browser dialog source or identity changed after review; inspect it again"
                    }
                    require(respondHumanDialog(dialogId, resolution == "accept", dialogText)) { "Browser dialog changed or was already answered; inspect the pending dialog again" }
                    Observation(buildJsonObject {
                        put("success", true); put("kind", "browser_dialog_response"); put("resolved_id", dialogId)
                        put("resolution", resolution); put("url", approvedPage)
                        put("note", "Only this dialog was answered. Inspect the page to verify the resulting behavior; the original operation was not replayed.")
                    }.toString(), (listOf(approvedPage) + listOfNotNull(expectedDialog?.sourceUrl)).distinct()
                        .map { ObservationSource(it, reviewedDocument) })
                }
            }
        }
    }

    /** The operation remains a child of its caller; surface loss cancels its own scope promptly. */
    private suspend fun <T> withLease(lease: ViewLease, block: suspend () -> T): T {
        try {
            return coroutineScope {
                val operationJob = currentCoroutineContext().job
                val invalidationObserver = launch(start = CoroutineStart.UNDISPATCHED) {
                    operationJob.cancel(BrowserSurfaceInvalidatedCancellation(lease.invalidated.await()))
                }
                try {
                    requireActiveLease(lease)
                    block()
                } finally {
                    invalidationObserver.cancel()
                }
            }
        } catch (changed: BrowserSurfaceInvalidatedCancellation) {
            // Closing a browser tab must not cancel the Agent turn. External cancellation
            // still propagates; only our surface-scoped cancellation becomes a tool error.
            currentCoroutineContext().ensureActive()
            throw BrowserSurfaceChangedException(changed.message ?: "Browser surface changed; retry on the active page")
        }
    }

    suspend fun execute(
        operation: String,
        url: String? = null,
        target: String? = null,
        text: String? = null,
        submit: Boolean = false,
        waitMs: Long = 500,
        allowLoopback: Boolean = false,
        expectedCurrentUrl: String? = null,
        start: Int = 0,
        count: Int = 80,
        query: String = "",
        elementStart: Int = 0,
        elementCount: Int = 30,
        dialogText: String? = null,
        fullPage: Boolean = false,
        reviewedLoopbackOrigin: String? = null,
        mediaOwnerId: String? = null,
    ): String = executeObserved(operation, url, target, text, submit, waitMs, allowLoopback,
        expectedCurrentUrl, start, count, query, elementStart, elementCount, dialogText, fullPage,
        reviewedLoopbackOrigin, mediaOwnerId, stageMediaForReview = false).payload

    /** UI initialization is conditional at dispatch, not when its coroutine is queued. */
    suspend fun initializeDefaultPage(binding: Binding, url: String): String =
        executeObserved("open", url = url, allowLoopback = true, waitMs = 350,
            initialBinding = binding).payload

    suspend fun executeObserved(
        operation: String,
        url: String? = null,
        target: String? = null,
        text: String? = null,
        submit: Boolean = false,
        waitMs: Long = 500,
        allowLoopback: Boolean = false,
        expectedCurrentUrl: String? = null,
        start: Int = 0,
        count: Int = 80,
        query: String = "",
        elementStart: Int = 0,
        elementCount: Int = 30,
        dialogText: String? = null,
        fullPage: Boolean = false,
        reviewedLoopbackOrigin: String? = null,
        mediaOwnerId: String? = null,
        expectedDocument: DocumentIdentity? = null,
        initialBinding: Binding? = null,
        stageMediaForReview: Boolean = true,
        expectedDialog: AgentBrowserDialogs.Dialog? = null,
        validateAuthority: (() -> Unit)? = null,
        outputConsent: BrowserOutputConsent? = null,
        frozenConsole: FrozenConsoleSnapshot? = null,
    ): Observation {
        if (validateAuthority != null) return withContext(DispatchAuthority(validateAuthority)) {
            executeObserved(operation, url, target, text, submit, waitMs, allowLoopback, expectedCurrentUrl,
                start, count, query, elementStart, elementCount, dialogText, fullPage, reviewedLoopbackOrigin,
                mediaOwnerId, expectedDocument, initialBinding, stageMediaForReview, expectedDialog,
                outputConsent = outputConsent, frozenConsole = frozenConsole)
        }
        currentCoroutineContext()[DispatchAuthority]?.check?.invoke()
        // Status is a cached observation: it must not mount a renderer, wait behind a
        // capture, start a page, or mutate localhost grants merely to inspect readiness.
        if (operation == "status") return synchronized(lock) {
            expectedDocument?.let(::requireReviewIdentity)
            if (expectedCurrentUrl != null) require(status.url == expectedCurrentUrl) {
                "Browser page changed after permission review; retry on the active page"
            }
            statusObservation()
        }
        val ticket = currentCoroutineContext()[ToolWorkContext]?.ticket
        val admissionEpoch = synchronized(lock) {
            check(applicationVisible) { "Browser requires Droide to be visible. Use web search/text fetching for background research, or return to Droide." }
            check(!stopping) { "Browser work is stopping; wait for cleanup before retrying" }
            ticket?.let(::requireToolWorkLocked)
            activeOperations++
            ticket?.let { it.executingCalls++ }
            publishRetentionLocked()
            operationEpoch
        }
        try {
            // Dialog response must remain available while a renderer action is paused by
            // JS; it must never wait for that action's executeMutex to be released.
            if (operation in setOf("dialog", "dialog_respond")) {
                return executeDialogControl(operation, target, text, dialogText, expectedCurrentUrl, allowLoopback, reviewedLoopbackOrigin, admissionEpoch, expectedDocument, expectedDialog)
            }
            return executeMutex.withLock {
                currentCoroutineContext()[DispatchAuthority]?.check?.invoke()
                requireAdmission(admissionEpoch)
                // A stale pane initializer must not reveal a replacement renderer either.
                if (initialBinding != null && synchronized(lock) {
                    initialBinding.generation != viewGeneration || navigationAssignedGeneration == viewGeneration ||
                        !surfacePresented || status.loading || status.url.let { it.isNotBlank() && it != "about:blank" }
                }) {
                    return@withLock Observation("{\"success\":true,\"initialization_skipped\":true}", emptyList())
                }
                if (frozenConsole != null) {
                    require(operation == "console" && outputConsent != null) { "Frozen console requires exact output consent" }
                    return@withLock onMainResult {
                        requireAdmission(admissionEpoch)
                        requireReviewIdentity(frozenConsole.document)
                        require(webView?.url.orEmpty().ifBlank { status.url } == frozenConsole.document.url) { "Browser URL changed after console review" }
                        outputConsent.consume("console", frozenConsole.document, consoleIdentity = frozenConsole.identity)
                        outputConsent.complete(frozenConsole.observation)
                    }
                }
                val capture = operation == "screenshot" || operation in setOf("click", "check", "key", "hover", "swipe", "drag") || submit
                val requestedLease = requestSurface(admissionEpoch, requirePresented = capture)
                val lease = awaitView(admissionEpoch, expectedLease = requestedLease.takeIf { capture })
                requireAdmission(admissionEpoch)
                withLease(lease) {
                    if (capture) awaitPresented(lease, admissionEpoch)
                    onMain(lease) { applicationTimers?.requireForegroundReady(lease.view) }
                    val view = lease.view
                    val initialize = onMainResult(lease) {
                        synchronized(lock) {
                            expectedDocument?.let(::requireReviewIdentity)
                            if (initialBinding != null && (initialBinding.generation != lease.generation ||
                                navigationAssignedGeneration == lease.generation || status.loading ||
                                view.url.orEmpty().let { it.isNotBlank() && it != "about:blank" } ||
                                view.copyBackForwardList().size > 0)) false
                            else {
                                if (operation in setOf("open", "navigate")) navigationAssignedGeneration = lease.generation
                                true
                            }
                        }
                    }
                    if (!initialize) return@withLease Observation("{\"success\":true,\"initialization_skipped\":true}", emptyList())
                    val targetPage = if (operation in setOf("open", "navigate")) url.orEmpty() else onMainResult(lease) { view.url.orEmpty() }
                    // Local project HTML cannot acquire private loopback access through the UI's browser defaults.
                    synchronized(lock) {
                        requireActiveLease(lease)
                        val reviewed = reviewedLoopbackOrigin?.let(::loopbackOrigin)
                        if (reviewedLoopbackOrigin != null) require(allowLoopback && reviewed != null && reviewed == loopbackOrigin(targetPage)) {
                            "Reviewed localhost origin does not match the browser target"
                        }
                        allowLoopbackRequests = allowLoopback && !workspacePreview.owns(targetPage)
                        // Human operations keep the original loopback behavior. Agent calls
                        // can bind their reviewed origin without breaking cross-port API
                        // resources from the approved frontend page.
                        loopbackRequestOrigin = if (allowLoopbackRequests) reviewed else null
                    }
                    var approvedCurrentPage: String? = null
                    var approvedPageIdentity: PageIdentity? = null
                    if (operation !in setOf("open", "navigate")) {
                        val page = onMainResult(lease) { synchronized(lock) {
                            expectedDocument?.let(::requireReviewIdentity)
                            PageIdentity(pageGeneration, view.url.orEmpty())
                        } }
                        val actual = page.url
                        if (expectedCurrentUrl != null) require(actual == expectedCurrentUrl) {
                            "Browser page changed after permission review; retry on the active page"
                        }
                        if (operation != "status") validateTopLevel(actual, allowLoopback)
                        approvedCurrentPage = actual
                        approvedPageIdentity = page
                    }
                    withDialogObservation(lease, operation) {
                        when (operation) {
                            "status" -> synchronized(lock) { statusObservation() }
                            "console" -> {
                                val current = onMainResult(lease) {
                                    require(view.url.orEmpty() == approvedCurrentPage) {
                                        "Browser page changed while reading console; retry on the active page"
                                    }
                                    synchronized(lock) {
                                        requirePageIdentity(lease, requireNotNull(approvedPageIdentity))
                                        val entries = consoleLines.toList()
                                        val currentStatus = status.copy(console = entries.map { it.line }).also {
                                            require(it.url == approvedCurrentPage) { "Browser navigation is not settled; retry console" }
                                        }
                                        Triple(currentStatus, documentIdentityLocked(lease), entries)
                                    }
                                }
                                val snapshot = AgentBrowserConsole.snapshot(current.first.url, current.first.title, current.first.console, start, count)
                                val end = requireNotNull(snapshot["end_line"]?.jsonPrimitive?.contentOrNull?.toIntOrNull())
                                val returned = current.third.drop(start).take(end - start)
                                Observation(AgentWebTools.bounded(snapshot), buildList {
                                    add(ObservationSource(current.first.url, current.second))
                                    returned.mapNotNull { it.sourceUrl }.distinct().filter { it != current.first.url }
                                        .forEach { add(ObservationSource(it, current.second)) }
                                }, hasUnknownSource = returned.any { it.sourceUrl == null })
                            }
                            "open", "navigate" -> {
                                val raw = requireNotNull(url?.trim()?.takeIf { it.isNotEmpty() }) { "browser open requires url" }
                                validateTopLevel(raw, allowLoopback)
                                onMain(lease) { view.loadUrl(raw) }
                                awaitSettled(lease, waitMs.coerceAtLeast(250))
                                readPage(lease)
                            }
                            "read", "find", "links" -> readPage(lease, operation, start, count, query, elementStart, elementCount,
                                expectedPage = approvedPageIdentity)
                            in AgentBrowserInteraction.operations -> performInteraction(lease, requireNotNull(approvedPageIdentity),
                                operation, target.orEmpty(), text, submit, waitMs)
                            "back" -> {
                                onMain(lease) {
                                    requirePageIdentity(lease, requireNotNull(approvedPageIdentity))
                                    if (view.canGoBack()) view.goBack()
                                }
                                awaitSettled(lease, waitMs.coerceAtLeast(250)); readPage(lease)
                            }
                            "reload" -> {
                                onMain(lease) {
                                    requirePageIdentity(lease, requireNotNull(approvedPageIdentity))
                                    view.reload()
                                }
                                awaitSettled(lease, waitMs.coerceAtLeast(250)); readPage(lease)
                            }
                            "wait" -> {
                                delay(waitMs.coerceIn(50, 10_000)); readPage(lease)
                            }
                            "screenshot" -> screenshot(lease, fullPage, mediaOwnerId, approvedPageIdentity, stageMediaForReview, outputConsent)
                            else -> Observation("ERROR: unsupported browser operation: $operation", emptyList())
                        }
                    }
                }
            }
        } finally { finishOperation(ticket) }
    }

    private fun requireAdmission(epoch: Long): Unit = synchronized(lock) {
        check(applicationVisible && !stopping && epoch == operationEpoch) {
            "Browser operation was stopped or queued before the app paused; inspect the current page before retrying. It was not replayed."
        }
    }

    private suspend fun requestSurface(admissionEpoch: Long, requirePresented: Boolean = false): ViewLease? =
        onMainResult {
            var requestedLease: ViewLease? = null
            val open = synchronized(lock) {
                requireAdmission(admissionEpoch)
                val lease = viewLease?.takeUnless { it.invalidated.isCompleted || nativePaused }
                requestedLease = lease
                val current = lease?.view
                // Keep a browser that is already visible in its present region. Requesting
                // the sidebar again would move its owner and invalidate this operation.
                if (current != null && isCurrentViewLocked(current) &&
                    current.isAttachedToWindow && current.windowVisibility == android.view.View.VISIBLE &&
                    ((surfacePresented && (!requirePresented || surfaceCaptureReady) && current.isShown) ||
                        (!requirePresented && keepActive))) {
                    return@onMainResult lease
                }
                requestOpen
            } ?: error("Integrated browser UI is not bound. Open the active workspace before using browser automation.")
            open()
            requestedLease
        }

    private suspend fun awaitView(admissionEpoch: Long, expectedLease: ViewLease? = null): ViewLease {
        repeat(30) {
            synchronized(lock) {
                requireAdmission(admissionEpoch)
                if (expectedLease != null && (viewLease !== expectedLease || expectedLease.invalidated.isCompleted)) {
                    throw BrowserSurfaceChangedException("Browser surface changed while opening capture; retry on the active page")
                }
                viewLease?.takeUnless { it.invalidated.isCompleted || nativePaused }
            }?.let { return it }
            delay(100)
        }
        error("Integrated browser surface did not become available")
    }

    /** Revealing a retained renderer is asynchronous; never capture its stale hidden layout. */
    private suspend fun awaitPresented(lease: ViewLease, admissionEpoch: Long) {
        var previousSize: Pair<Int, Int>? = null
        repeat(30) {
            requireAdmission(admissionEpoch)
            val size = onMainResult(lease) {
                synchronized(lock) {
                    val view = lease.view
                    if (surfacePresented && surfaceCaptureReady && view.isAttachedToWindow && view.isShown &&
                        view.visibility == android.view.View.VISIBLE && view.windowVisibility == android.view.View.VISIBLE &&
                        view.isLaidOut && !view.isLayoutRequested && view.width > 0 && view.height > 0) {
                        view.width to view.height
                    } else null
                }
            }
            if (size != null && size == previousSize) return
            previousSize = size
            delay(50)
        }
        error("Browser capture requires a visible, laid-out browser surface; open Web and retry")
    }

    private suspend fun performInteraction(lease: ViewLease, page: PageIdentity, operation: String,
        target: String, text: String?, submit: Boolean, waitMs: Long): Observation {
        val options = currentCoroutineContext()[BrowserActionOptions] ?: BrowserActionOptions()
        val authority = currentCoroutineContext()[DispatchAuthority]?.check
        val source = interactionSource(lease, page)
        val actions = AgentBrowserActions(lease.view,
            evaluate = { script -> eval(lease, script, page) },
            dispatch = { block -> onMain(lease) {
                requirePageIdentity(lease, page); authority?.invoke()
                check(surfacePresented && surfaceCaptureReady) { "Browser is not ready for input" }
                block()
            } },
            cancel = { block -> onMain(lease) { block() } })
        val result = actions.execute(operation, target, text, submit, options)
        if (result["ok"]?.jsonPrimitive?.booleanOrNull == true && operation != "wait_for") delay(waitMs.coerceIn(50, 2_000))
        return interactionResult(result.toString(), readPage(lease), source)
    }

    private suspend fun awaitSettled(lease: ViewLease, minWaitMs: Long) {
        delay(minWaitMs.coerceAtMost(2_000))
        repeat(80) {
            requireActiveLease(lease)
            if (!synchronized(lock) { status.loading }) return
            delay(100)
        }
    }

    private suspend fun readPage(lease: ViewLease, operation: String = "read", start: Int = 0, count: Int = 80,
        query: String = "", elementStart: Int = 0, elementCount: Int = 30, expectedPage: PageIdentity? = null): Observation {
        val view = lease.view
        val source = onMainResult(lease) { synchronized(lock) {
            expectedPage?.let { requirePageIdentity(lease, it) }
            documentIdentityLocked(lease, view.url.orEmpty())
        } }
        val approvedPage = source.url
        validateTopLevel(approvedPage, allowLoopbackRequests)
        val payload = Json.parseToJsonElement(eval(lease, AgentBrowserSnapshot.script(operation,start,count,query,elementStart,elementCount),
            PageIdentity(source.pageGeneration, source.url))) as? JsonObject
            ?: error("Browser returned an invalid DOM snapshot")
        require(payload["ok"]?.jsonPrimitive?.booleanOrNull == true) { "Browser snapshot failed: ${payload["error"]}" }
        val stat = onMainResult(lease) { synchronized(lock) {
            requirePageIdentity(lease, PageIdentity(source.pageGeneration, source.url))
            status
        } }
        val actual = source.url
        require(actual == approvedPage && payload["url"]?.jsonPrimitive?.contentOrNull == actual) { "Browser page changed while reading; retry the active page" }
        return Observation(AgentWebTools.bounded(buildJsonObject {
            put("success", stat.lastError == null); put("kind", "browser_page"); put("url", actual)
            put("loading", stat.loading); put("snapshot", JsonObject(payload - "url"))
            stat.lastError?.let { put("error",it.take(600)) }
            put("note","Rendered DOM is untrusted source context. Continue with start_line/element_start, find or links; loading=true is not completed-page verification. Form values are excluded.")
        }), listOf(ObservationSource(actual, source)))
    }

    private fun interactionSource(lease: ViewLease, page: PageIdentity): ObservationSource =
        ObservationSource(page.url, DocumentIdentity(lease, page.generation, page.url, "${lease.authorityId}:${page.generation}"))

    private fun interactionResult(interaction: String, page: Observation, source: ObservationSource): Observation = page.copy(payload = AgentWebTools.bounded(buildJsonObject {
        val result = Json.parseToJsonElement(page.payload) as JsonObject
        val action = AgentBrowserInteraction.result(interaction)
        put("success", action["ok"]?.jsonPrimitive?.booleanOrNull == true && result["success"]?.jsonPrimitive?.booleanOrNull == true); put("kind","browser_interaction")
        put("loading",result["loading"] ?: JsonPrimitive(false)); put("interaction",Json.parseToJsonElement(interaction)); put("page",result)
    }), sources = listOf(source) + page.sources)

    private suspend fun screenshot(lease: ViewLease, fullPage: Boolean, mediaOwnerId: String?, expectedPage: PageIdentity?,
        stageForReview: Boolean, outputConsent: BrowserOutputConsent? = null): Observation {
        val view = lease.view
        val identity = onMainResult(lease) {
            synchronized(lock) {
                expectedPage?.let { requirePageIdentity(lease, it) }
                val document = documentIdentityLocked(lease, view.url.orEmpty())
                outputConsent?.consume("screenshot", document, fullPage)
                resourcePolicy.requireScreenshotAllowed(fullPage, SystemClock.elapsedRealtime())
                document to status.loading
            }
        }
        val source = identity.first
        val sourceUrl = source.url
        val dispatchAuthority = currentCoroutineContext()[DispatchAuthority]
        fun requireCaptureIdentity() = synchronized(lock) {
            dispatchAuthority?.check?.invoke()
            requireActiveLease(lease)
            if (!surfacePresented || !surfaceCaptureReady) {
                throw BrowserCaptureException("Browser capture requires the fully visible browser surface; open Web and retry")
            }
            if (pageGeneration != source.pageGeneration) {
                throw BrowserCaptureException("Browser document changed during capture; retry the active page")
            }
        }
        fun requireRestoreIdentity() = synchronized(lock) {
            // Backgrounding cancels operation ownership while retaining this
            // document. Restore its scroll without reactivating work; never touch
            // a replacement view, same-URL reload, or destroyed surface.
            if (webView !== view || viewGeneration != lease.generation || pageGeneration != source.pageGeneration) {
                throw BrowserCaptureException("Browser document changed before capture cleanup")
            }
        }
        validateTopLevel(sourceUrl, allowLoopbackRequests)
        var capturedFile: File? = null
        var managed: ChatMediaRef? = null
        // Agent captures cannot belong to the conversation before their captured
        // source passes read policy. Human/UI calls retain their existing ownership.
        val owner = mediaOwnerId?.takeIf { !stageForReview && it.isNotBlank() }
            ?: "browser-capture-${java.util.UUID.randomUUID()}"
        val captureJob = currentCoroutineContext().job
        val scratch = AgentMediaScratch(projectRoot, "browser")
        var committed = false
        try {
            val result = AgentBrowserCapture.withCapture(
                view, fullPage, sourceUrl,
                checkLease = ::requireCaptureIdentity,
                scaleOnMain = { synchronized(lock) { browserScale?.toDouble() } },
                checkRestoreIdentity = ::requireRestoreIdentity,
            ) { capture ->
                val bitmap = capture.bitmap
                withContext(Dispatchers.IO) {
                    val file = scratch.create("png")
                    capturedFile = file
                    try {
                        FileOutputStream(file).use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 92,
                            LimitedOutputStream(out, VisionSupport.MAX_IMAGE_BYTES))) }
                    } catch (failure: OutOfMemoryError) {
                        throw BrowserCaptureException("Not enough memory to encode browser capture; retry a viewport capture", failure)
                    }
                }
                if (onMainResult(lease) { view.url.orEmpty() } != sourceUrl) {
                    error("Browser page changed during capture; retry the active page")
                }
                val file = requireNotNull(capturedFile)
                buildJsonObject {
                    put("success", true); put("kind", "browser_screenshot"); put("path", file.absolutePath)
                    put("width", bitmap.width); put("height", bitmap.height); put("url", sourceUrl)
                    put("loading", identity.second)
                    put("full_page_requested", fullPage); put("capture_mode", if (fullPage) "vertical_page" else "viewport")
                    put("complete", capture.complete); put("horizontal_coverage", "viewport")
                    put("source_width", capture.plan.viewportWidth); put("viewport_height", capture.plan.viewportHeight)
                    put("source_height", capture.plan.sourceHeight); put("final_source_height", capture.finalSourceHeight)
                    put("captured_left", capture.plan.captureLeft); put("captured_top", capture.plan.startY)
                    put("captured_source_height", capture.capturedSourceHeight); put("tiles", capture.tilesDrawn)
                    put("incomplete_reasons", JsonArray(capture.incompleteReasons.map(::JsonPrimitive)))
                    put("note", if (fullPage)
                        "Bounded vertical capture at viewport width. complete refers to the observed vertical extent; horizontal overflow and future lazy content are excluded. Fixed/sticky overlays may repeat and live content can change. Use view_image with an image-capable model; capture alone is not interpretation."
                        else "Visible Android WebView viewport. Use view_image on this path with an image-capable model; capture alone is not interpretation.")
                }.toString()
            }
            requireCaptureIdentity()
            currentCoroutineContext().ensureActive()
            // Capture releases its bitmap/restores the viewport before disk import and index publication.
            val store = ChatMediaStore(projectRoot)
            managed = requireNotNull(capturedFile).inputStream().use { store.import(owner, "browser-screenshot.png", "image/png", it) }
            requireCaptureIdentity()
            currentCoroutineContext().ensureActive()
            val published = JsonObject(Json.parseToJsonElement(result).jsonObject +
                ("path" to JsonPrimitive(requireNotNull(managed).path)))
            // Top-level identity is not exhaustive frame provenance. Only this exact
            // Agent output can carry the special one-call uncertainty consent.
            val observation = Observation(published.toString(), listOf(ObservationSource(sourceUrl, source)), managed, owner,
                hasUnknownSource = true)
            val reviewed = outputConsent?.complete(observation) ?: observation
            committed = true
            return reviewed
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (!committed || !captureJob.isActive) managed?.let { ChatMediaStore(projectRoot).release(owner, it) }
                capturedFile?.delete()
                scratch.close()
            }
        }
    }

    fun currentUrl(): String = synchronized(lock) { status.url }

    /** Remove unpublished captures after denied/cancelled result review. Never publish their path. */
    suspend fun retainObservationMedia(observation: Observation, owner: String) {
        val media = observation.media ?: return
        withContext(Dispatchers.IO) { ChatMediaStore(projectRoot).retain(owner, listOf(media)) }
    }

    suspend fun discardObservation(observation: Observation, additionalOwner: String? = null) {
        val media = observation.media ?: return
        val owner = observation.mediaOwnerId ?: return
        withContext(NonCancellable + Dispatchers.IO) {
            val store = ChatMediaStore(projectRoot)
            try { additionalOwner?.takeIf { it != owner }?.let { store.release(it, media) } }
            finally { store.release(owner, media) }
        }
    }

    private fun statusObservation(): Observation {
        val document = documentIdentityLocked()
        val dialog = pendingDialog.value
        return Observation(renderStatus(), buildList {
            if (document.url.isNotBlank()) add(ObservationSource(document.url, document))
            dialog?.sourceUrl?.takeIf { it != document.url }?.let { add(ObservationSource(it, document)) }
        }, hasUnknownSource = dialog != null && dialog.sourceUrl == null)
    }

    private fun renderStatus(): String {
        val s = synchronized(lock) { status }
        val lifecycle = synchronized(lock) {
            Triple(applicationVisible, nativePaused || !applicationVisible,
                applicationVisible && !nativePaused && !stopping && (surfacePresented || keepActive) &&
                    viewLease?.invalidated?.isCompleted == false &&
                    applicationTimers?.readyForActions != false)
        }
        return AgentWebTools.bounded(buildJsonObject {
            put("success",true); put("kind","browser_status"); put("attached",s.attached); put("loading",s.loading)
            put("application_visible", lifecycle.first); put("paused", lifecycle.second)
            put("ready_for_actions", lifecycle.third)
            val retention = retentionState.value
            put("keep_active", retention.keepActive); put("presented", retention.presented)
            put("capture_ready", retention.captureReady); put("active_operations", retention.activeOperations)
            put("stopping", retention.stopping)
            put("url",s.url); put("title",s.title); s.lastError?.let { put("last_error",it) }
            pendingDialog.value?.let { put("pending_dialog", dialogs.description(it)) }
        })
    }

    private suspend fun validateTopLevel(raw: String, allowLoopback: Boolean) {
        require(raw.length in 1..8_192) { "Browser URL must contain 1..8192 characters" }
        if (workspacePreview.owns(raw)) {
            withContext(Dispatchers.IO) { workspacePreview.requirePage(raw) }
            return
        }
        val uri = URI(raw)
        require(uri.userInfo == null && uri.host != null && uri.port in -1..65535) { "Invalid browser URL" }
        if (isLoopback(uri.host)) {
            require(allowLoopback) { "Loopback browser navigation requires explicit browser_local permission" }
            require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "Loopback browser requires HTTP(S)" }
            return
        }
        
        val preflight = URI(uri.scheme, uri.userInfo, uri.host, uri.port, uri.path, uri.query, null).toString()
        withContext(Dispatchers.IO) { NetworkSecurity.validatePublicHttpsTarget(preflight) }
    }

    private fun quickTopLevelAllowed(raw: String, allowLoopback: Boolean): Boolean = runCatching {
        if (workspacePreview.owns(raw)) {
            workspacePreview.requirePage(raw)
            return@runCatching true
        }
        val uri = URI(raw)
        if (isLoopback(uri.host)) {
            loopbackAllowed(raw, allowLoopback, topLevel = true) && (uri.scheme.equals("http", true) || uri.scheme.equals("https", true))
        } else uri.scheme.equals("https", true) && uri.userInfo == null && uri.host != null
    }.getOrDefault(false)

    private fun networkRequestAllowed(raw: String, allowLoopback: Boolean, topLevel: Boolean = false): Boolean = runCatching {
        val uri = URI(raw)
        val host = uri.host ?: return@runCatching false
        if (isLoopback(host)) return@runCatching loopbackAllowed(raw, allowLoopback, topLevel) && (topLevel || isLoopback(URI(currentUrl()).host)) && uri.userInfo == null && (uri.scheme.equals("http", true) || uri.scheme.equals("https", true))
        if (!uri.scheme.equals("https", true)) return@runCatching false
        NetworkSecurity.validatePublicHttpsTarget(raw)
        true
    }.getOrDefault(false)

    private fun isLoopback(host: String?): Boolean = AgentRepairCompletionPolicy.isLiteralLoopback(host)

    private fun loopbackOrigin(raw: String): String? = runCatching {
        val uri = URI(raw)
        val scheme = uri.scheme?.lowercase() ?: return@runCatching null
        val host = uri.host?.lowercase() ?: return@runCatching null
        if (scheme !in setOf("http", "https") || !isLoopback(host) || uri.userInfo != null || uri.port !in -1..65535) return@runCatching null
        val port = if (uri.port == -1) { if (scheme == "http") 80 else 443 } else uri.port
        "$scheme://$host:$port"
    }.getOrNull()

    private fun reusableLoopbackOrigin(view: WebView): String? =
        if (!allowLoopbackRequests) null else loopbackOrigin(view.url.orEmpty())
            ?.takeIf { loopbackRequestOrigin == null || loopbackRequestOrigin == it }

    private fun loopbackAllowed(raw: String, requested: Boolean, topLevel: Boolean): Boolean = synchronized(lock) {
        requested && allowLoopbackRequests &&
            (loopbackRequestOrigin == null || loopbackRequestOrigin ==
                loopbackOrigin(if (topLevel) raw else status.url))
    }

    private fun recordConsole(view: WebView, line: String, sourceId: String?): Unit = synchronized(lock) {
        if (!isCurrentViewLocked(view)) return
        val source = runCatching {
            if (sourceId == null || sourceId.length !in 1..8_192 || sourceId.any { it.code < 32 || it.code == 127 }) return@runCatching null
            val uri = URI(sourceId)
            sourceId.takeIf { (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
                uri.host != null && uri.rawUserInfo == null && uri.port in -1..65535 }
        }.getOrNull()
        // Native sourceId is diagnostic provenance, not a frame/document token.
        // Unknown or relative IDs never inherit the top page's read permission.
        consoleLines.addLast(ConsoleEntry(line.take(1_000), source))
        while (consoleLines.size > 40) consoleLines.removeFirst()
    }

    private fun recordError(view: WebView, message: String): Unit = synchronized(lock) {
        if (!isCurrentViewLocked(view)) return
        status = status.copy(lastError = message.take(1_000), loading = false)
        publishRetentionLocked()
    }

    private fun requirePageIdentity(lease: ViewLease, expected: PageIdentity) {
        checkMainThread()
        synchronized(lock) {
            requireActiveLease(lease)
            if (pageGeneration != expected.generation || lease.view.url.orEmpty() != expected.url) {
                throw BrowserSurfaceChangedException("Browser document changed after permission review; inspect the current page before retrying. No action was performed.")
            }
        }
    }

    private suspend fun eval(lease: ViewLease, script: String, expectedPage: PageIdentity? = null): String = withTimeout(10_000) { suspendCancellableCoroutine { cont ->
        main.post {
            if (!cont.isActive) return@post
            runCatching {
                requireActiveLease(lease)
                expectedPage?.let { requirePageIdentity(lease, it) }
                cont.context[DispatchAuthority]?.check?.invoke()
                lease.view.evaluateJavascript(script) { raw ->
                    if (!cont.isActive) return@evaluateJavascript
                    try { requireActiveLease(lease) }
                    catch (changed: BrowserSurfaceInvalidatedCancellation) {
                        if (cont.isActive) cont.resumeWith(Result.failure(changed))
                        return@evaluateJavascript
                    }
                    val unwrapped = runCatching {
                        val element = Json.parseToJsonElement(raw ?: "null")
                        if (element is JsonPrimitive && element.isString) element.contentOrNull ?: raw else raw
                    }.getOrDefault(raw ?: "null")
                    cont.resume(unwrapped)
                }
            }.onFailure { if (cont.isActive) cont.resumeWith(Result.failure(it)) }
        }
    } }

    private suspend fun onMain(lease: ViewLease? = null, block: () -> Unit) = withTimeout(10_000) { suspendCancellableCoroutine { cont ->
        main.post {
            if (!cont.isActive) return@post
            runCatching { lease?.let(::requireActiveLease); cont.context[DispatchAuthority]?.check?.invoke(); block() }.fold(
                onSuccess = { if (cont.isActive) cont.resume(Unit) },
                onFailure = { if (cont.isActive) cont.resumeWith(Result.failure(it)) },
            )
        }
    } }

    private suspend fun <T> onMainResult(lease: ViewLease? = null, onDiscard: (T) -> Unit = {}, block: () -> T): T = withTimeout(10_000) { suspendCancellableCoroutine { cont ->
        main.post {
            if (!cont.isActive) return@post
            runCatching { lease?.let(::requireActiveLease); cont.context[DispatchAuthority]?.check?.invoke(); block() }.fold(
                onSuccess = { value ->
                    if (cont.isActive) cont.resume(value, onCancellation = { _, discarded, _ -> onDiscard(discarded) })
                    else onDiscard(value)
                },
                onFailure = { if (cont.isActive) cont.resumeWith(Result.failure(it)) },
            )
        }
    } }

}
