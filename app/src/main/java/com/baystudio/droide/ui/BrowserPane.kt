package com.baystudio.droide.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.baystudio.droide.core.AgentBrowserController
import com.baystudio.droide.core.AgentBrowserDialogs
import com.baystudio.droide.core.BrowserInitializationDiagnostics
import com.baystudio.droide.core.BrowserInitializationDiagnostics.Phase
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver


@Composable
fun BrowserPane(
    url: String = "https://github.com",
    onUrlChange: (String) -> Unit = {},
    agentBrowser: AgentBrowserController,
    presented: Boolean = true,
    captureReady: Boolean = presented,
    onReviewAgentApproval: (() -> Unit)? = null,
    onUserFocus: () -> Unit = {},
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
) {
    key(agentBrowser) {
        WorkspaceBrowserPane(url, onUrlChange, agentBrowser, presented, captureReady, onReviewAgentApproval, onUserFocus,
            expanded = expanded, onExpandedChange = onExpandedChange)
    }
}

@Composable
internal fun WorkspaceBrowserPane(
    url: String,
    onUrlChange: (String) -> Unit,
    agentBrowser: AgentBrowserController,
    presented: Boolean = true,
    captureReady: Boolean = presented,
    onReviewAgentApproval: (() -> Unit)? = null,
    onUserFocus: () -> Unit = {},
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    createWebView: (Context) -> WebView = { WebView(it) },
) {
    val scope = rememberWorkspaceCoroutineScope()
    var inputUrl by rememberSaveable(url) { mutableStateOf(url) }
    var status by rememberSaveable { mutableStateOf("Ready") }
    var loading by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var browserBinding by remember { mutableStateOf<AgentBrowserController.Binding?>(null) }
    var browserError by remember { mutableStateOf<String?>(null) }
    var browserGeneration by remember { mutableIntStateOf(0) }
    var confirmStop by remember { mutableStateOf(false) }
    var urlFieldFocused by remember { mutableStateOf(false) }
    // Chrome resize/focus changes must not overwrite an unsent URL draft or republish the same page.
    var appliedNavigationUrl by rememberSaveable(agentBrowser) { mutableStateOf(url) }
    // Disclosure is a saved chrome preference, independent of expanded mode and retention.
    var keepActiveDisclosure by rememberSaveable { mutableStateOf(false) }
    var browserMenuOpen by remember { mutableStateOf(false) }
    val retention by agentBrowser.retention.collectAsState()
    val navigation by agentBrowser.navigation.collectAsState()
    val pendingDialog by agentBrowser.pendingDialog.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focusManager = LocalFocusManager.current
    val currentPresented by rememberUpdatedState(presented)
    val currentOnUserFocus by rememberUpdatedState(onUserFocus)

    fun showBrowserFailure(failure: Throwable) {
        browserError = failure.message ?: "Android System WebView is unavailable"
        status = "Blocked: $browserError"
        loading = false
    }

    fun releaseBrowser(host: BrowserViewHost, preserveState: Boolean) {
        val view = host.browser ?: return
        val binding = host.binding
        host.browser = null
        host.binding = null
        if (webView === view) { webView = null; browserBinding = null }
        var releaseFailure: Throwable? = null
        fun releaseStep(action: () -> Unit) {
            runUiCatching({ failure ->
                if (releaseFailure == null) releaseFailure = failure else releaseFailure?.addSuppressed(failure)
                showBrowserFailure(failure)
            }, action)
        }
        if (binding != null) releaseStep { agentBrowser.detach(binding, view, preserveState) }
        releaseStep { view.stopLoading() }
        releaseStep { host.removeView(view); view.removeAllViews() }
        releaseStep { view.destroy() }
        agentBrowser.reportNativeSurfaceReleased(view, releaseFailure)
    }

    fun navigate(raw: String, operation: String = "open", initialBinding: AgentBrowserController.Binding? = null) {
        if (!presented || retention.stopping || loading || webView == null || browserError != null) return
        loading = true
        scope.launch {
            status = "Loading…"
            try {
                if (initialBinding != null) agentBrowser.initializeDefaultPage(initialBinding, raw.trim())
                    else agentBrowser.execute(operation, url = raw.trim(), allowLoopback = true, waitMs = 350)
                val current = agentBrowser.currentUrl().ifBlank { raw.trim() }
                inputUrl = current
                if (appliedNavigationUrl != current) {
                    appliedNavigationUrl = current
                    onUrlChange(current)
                }
                status = agentBrowser.navigation.value.title.ifBlank { current }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                status = "Blocked: browser operation timed out"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                status = "Blocked: ${failure.message ?: "navigation failed"}"
            } catch (failure: LinkageError) {
                status = "Blocked: ${failure.message ?: "WebView unavailable"}"
            } finally { loading = false }
        }
    }

    fun changeKeepActive(enabled: Boolean) {
        runUiCatching(::showBrowserFailure) {
            if (!agentBrowser.setKeepActive(enabled) && !enabled) confirmStop = true
        }
    }

    val canNavigate = presented && !retention.stopping && !loading && webView != null && browserError == null
    val browserStatus = if (retention.stopping) "Stopping browser work…" else status

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val disclosureMaxHeight = minOf(maxHeight * .35f, 180.dp)
        Column(Modifier.fillMaxSize()) {
            // Keep one URL field and the normal 50/60dp chrome, growing only for larger text.
            val urlHeight = textControlHeight(MaterialTheme.typography.bodyMedium, 48.dp, 0.dp)
            BoxWithConstraints(Modifier.fillMaxWidth().height(maxOf(if (expanded) 50.dp else 60.dp, urlHeight + 2.dp)).testTag("browser-toolbar")) {
                val showDirectDisclosure = maxWidth >= 296.dp
                Row(Modifier.fillMaxSize().padding(horizontal = if (expanded || showDirectDisclosure) 8.dp else 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicTextField(
                        value = inputUrl,
                        onValueChange = { if (presented) onUserFocus(); inputUrl = it.take(2_000) },
                        modifier = Modifier.weight(1f).height(urlHeight).testTag("browser-url")
                            .semantics { contentDescription = "Browser URL" }
                            .onFocusChanged {
                                urlFieldFocused = it.isFocused
                                if (it.isFocused && presented) onUserFocus()
                            }
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
                        enabled = presented && !retention.stopping,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { navigate(inputUrl) }),
                        decorationBox = { innerTextField ->
                            Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                                if (inputUrl.isEmpty()) Text("https://…", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                innerTextField()
                            }
                        },
                    )
                    // Narrow sidebars keep Expand visible in the same toolbar row; disclosure
                    // moves to the menu instead of costing another 48dp of viewport height.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        // The same menu remains reachable in expanded mode, while loading, and on errors.
                        Box {
                            IconButton(onClick = { browserMenuOpen = true }, enabled = presented,
                                modifier = Modifier.size(48.dp).semantics { stateDescription = browserStatus }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Browser actions")
                            }
                            if (presented) DropdownMenu(expanded = presented && browserMenuOpen, onDismissRequest = { browserMenuOpen = false }) {
                                DropdownMenuItem(text = { Text("Back") }, enabled = canNavigate,
                                    leadingIcon = { Icon(Icons.Default.ArrowBack, contentDescription = null) },
                                    onClick = { browserMenuOpen = false; navigate(inputUrl, "back") })
                                DropdownMenuItem(text = { Text("Reload") }, enabled = canNavigate,
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                    onClick = { browserMenuOpen = false; navigate(inputUrl, "reload") })
                                DropdownMenuItem(text = { Text("Go") }, enabled = canNavigate,
                                    onClick = { browserMenuOpen = false; navigate(inputUrl) })
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text(if (expanded) "Exit expanded browser" else "Expand browser") },
                                    onClick = { browserMenuOpen = false; onExpandedChange(!expanded) })
                                if (!expanded && !showDirectDisclosure) DropdownMenuItem(
                                    text = { Text(if (keepActiveDisclosure) "Collapse Keep active controls" else "Show Keep active controls") },
                                    modifier = Modifier.testTag("browser-menu-disclosure").semantics {
                                        stateDescription = if (keepActiveDisclosure) "Expanded" else "Collapsed"
                                    },
                                    onClick = { browserMenuOpen = false; keepActiveDisclosure = !keepActiveDisclosure })
                                DropdownMenuItem(text = { Text("Keep active") },
                                    trailingIcon = { Text(if (retention.keepActive) "On" else "Off") },
                                    modifier = Modifier.testTag("browser-menu-keep-active").semantics {
                                        stateDescription = if (retention.keepActive) "On" else "Off"
                                    },
                                    enabled = presented && !retention.stopping,
                                    onClick = { browserMenuOpen = false; changeKeepActive(!retention.keepActive) })
                                onReviewAgentApproval?.let { review ->
                                    DropdownMenuItem(text = { Text("Review Agent permission") },
                                        onClick = { browserMenuOpen = false; review() })
                                }
                                HorizontalDivider()
                                Text(browserStatus, style = MaterialTheme.typography.labelSmall,
                                    color = if (browserStatus.startsWith("Blocked:")) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp)
                                        .testTag("browser-menu-status").semantics { contentDescription = "Browser status: $browserStatus" })
                            }
                        }
                        if (expanded) {
                            // Leaving expanded mode must never be gated by loading/stopping/renderer errors.
                            IconButton(onClick = { browserMenuOpen = false; onExpandedChange(false) },
                                modifier = Modifier.size(48.dp).semantics { stateDescription = "Expanded browser" }) {
                                Icon(Icons.Default.Close, contentDescription = "Exit expanded browser")
                            }
                        } else {
                            IconButton(onClick = { browserMenuOpen = false; onExpandedChange(true) }, enabled = presented,
                                modifier = Modifier.size(48.dp).semantics { stateDescription = "Normal browser" }) {
                                Icon(Icons.Default.Fullscreen, contentDescription = "Expand browser")
                            }
                            if (showDirectDisclosure) IconButton(onClick = { keepActiveDisclosure = !keepActiveDisclosure }, enabled = presented,
                                modifier = Modifier.size(48.dp).semantics {
                                    stateDescription = if (keepActiveDisclosure) "Expanded" else "Collapsed"
                                }) {
                                Icon(if (keepActiveDisclosure) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (keepActiveDisclosure) "Collapse Keep active controls" else "Show Keep active controls")
                            }
                        }
                    }
                }
            }
            if (!expanded) {
                Text(
                    browserStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (browserStatus.startsWith("Blocked:")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    maxLines = 1,
                )
                // Animate measured height, not alpha/translation: every frame returns real space
                // to the weighted native viewport. Compose honors Android's animator duration scale.
                AnimatedVisibility(
                    visible = keepActiveDisclosure,
                    enter = expandVertically(animationSpec = tween(220, easing = FastOutSlowInEasing), expandFrom = Alignment.Top),
                    exit = shrinkVertically(animationSpec = tween(220, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top),
                ) {
                    Row(Modifier.fillMaxWidth().heightIn(max = disclosureMaxHeight).verticalScroll(rememberScrollState())
                        .testTag("browser-keep-active-controls").padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Keep active", style = MaterialTheme.typography.labelMedium)
                            Text(if (retention.stopping) "Stopping browser work…" else "Uses more RAM. Runs only while Droide is visible.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = retention.keepActive, enabled = presented && !retention.stopping,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .semantics { contentDescription = "Keep active" },
                            onCheckedChange = ::changeKeepActive)
                    }
                }
            }
            val failure = browserError
            if (failure != null) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(failure, color = MaterialTheme.colorScheme.error)
                    if (!failure.startsWith("Browser released")) {
                        Text("Check that Android System WebView or Chrome is installed and enabled, then retry.", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { browserError = null; status = "Ready"; browserGeneration++ },
                        enabled = presented && !retention.stopping) { Text("Retry browser") }
                }
            } else key(browserGeneration) {
                AndroidView(
                    factory = { ctx ->
                        BrowserViewHost(ctx).also { host ->
                            val diagnostics = BrowserInitializationDiagnostics.create()
                            val ready = runUiCatching(::showBrowserFailure) {
                                val view = diagnostics.phase(Phase.CREATE) { createWebView(ctx) }
                                view.setOnFocusChangeListener { _, focused ->
                                    if (focused && currentPresented) currentOnUserFocus()
                                }
                                host.browser = view
                                diagnostics.providerAfterCreation(BrowserInitializationDiagnostics::currentProvider)
                                diagnostics.phase(Phase.CONFIGURE) { agentBrowser.configure(view,
                                    onExitRelease = { releaseBrowser(host, preserveState = false) }) { gone, message ->
                                    if (host.browser === gone) { host.browser = null; host.binding = null }
                                    if (webView === gone) { webView = null; browserBinding = null }
                                    if (message.startsWith("Browser released")) {
                                        agentBrowser.currentUrl().takeIf { it.isNotBlank() }?.let { current ->
                                            inputUrl = current
                                            onUrlChange(current)
                                        }
                                    }
                                    browserError = message
                                    status = "Blocked: $message"
                                    loading = false
                                } }
                                host.binding = diagnostics.phase(Phase.ATTACH) { agentBrowser.attach(view) }
                                browserBinding = host.binding
                                diagnostics.phase(Phase.MOUNT) {
                                    agentBrowser.setSurfaceStarted(requireNotNull(host.binding), view,
                                        lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                                    host.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                                    host.present(presented)
                                    agentBrowser.setSurfacePresented(requireNotNull(host.binding), view, presented, captureReady)
                                }
                                webView = view
                                true
                            }
                            if (ready != true) releaseBrowser(host, preserveState = false)
                        }
                    },
                    update = { host ->
                        runUiCatching(::showBrowserFailure) {
                            host.present(presented)
                            val view = host.browser
                            val binding = host.binding
                            if (view != null && binding != null) {
                                agentBrowser.setSurfacePresented(binding, view, presented, captureReady)
                            }
                        }
                    },
                    onRelease = { releaseBrowser(it, preserveState = true) },
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("browser-viewport"),
                )
            }
        }

    }

    DisposableEffect(lifecycle, webView, browserBinding) {
        val view = webView
        val binding = browserBinding
        val observer = LifecycleEventObserver { _, _ ->
            if (view != null && binding != null) {
                runUiCatching(::showBrowserFailure) {
                    agentBrowser.setSurfaceStarted(binding, view, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(navigation.url, navigation.title, navigation.loading, navigation.lastError, urlFieldFocused) {
        if (!urlFieldFocused && navigation.url.isNotBlank() && appliedNavigationUrl != navigation.url) {
            appliedNavigationUrl = navigation.url
            inputUrl = navigation.url
            onUrlChange(navigation.url)
        }
        if (browserError == null) status = navigation.lastError?.let { "Blocked: $it" }
            ?: if (navigation.loading) "Loading…" else navigation.title.ifBlank { navigation.url.ifBlank { "Ready" } }
    }

    LaunchedEffect(presented) {
        // A retained hidden browser must never leave a popup over another workspace pane.
        if (!presented) browserMenuOpen = false
        // Disabling the field normally clears its focus. Never clear a newly
        // focused editor or chat field merely because the browser became hidden.
        if (!presented && urlFieldFocused) focusManager.clearFocus(force = true)
    }

    LaunchedEffect(webView) {
        val view = webView ?: return@LaunchedEffect
        val binding = browserBinding ?: return@LaunchedEffect
        if (presented && runUiCatching(::showBrowserFailure) { view.copyBackForwardList().size == 0 } == true && url.isNotBlank()) {
            navigate(url, initialBinding = binding)
        }
    }

    if (confirmStop) AlertDialog(
        onDismissRequest = { confirmStop = false },
        title = { Text("Stop browser work?") },
        text = { Text("Turning off Keep active will stop current and queued browser operations. Actions the website already performed cannot be undone.") },
        confirmButton = { TextButton(onClick = {
            runUiCatching(::showBrowserFailure) {
                if (agentBrowser.setKeepActive(false, cancelActive = true)) confirmStop = false
            }
        }) { Text("Stop and turn off") } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Cancel") } },
    )

    pendingDialog?.let { dialog ->
        key(dialog.id) {
            // Human prompt input is transient UI state, never a saved Agent observation.
            var promptText by remember { mutableStateOf(dialog.defaultText.orEmpty()) }
            fun respond(accept: Boolean) {
                runUiCatching(::showBrowserFailure) {
                    agentBrowser.respondHumanDialog(dialog.id, accept, promptText.takeIf { dialog.type == "prompt" && accept })
                }
            }
            // Hiding the panel never answers or dismisses a native website dialog.
            // Keep the prompt draft here, outside the presented-only modal window.
            if (presented) AlertDialog(
                onDismissRequest = { respond(false) },
                title = { Text(when (dialog.type) {
                    "confirm" -> "Website confirmation"
                    "prompt" -> "Website prompt"
                    else -> "Website alert"
                }) },
                text = {
                    Column(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // The read-only website message owns vertical scrolling. Reserve a
                        // separately constrained answer field instead of measuring an editor
                        // inside the scroller's unbounded vertical content area.
                        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val origin = runCatching { dialog.sourceUrl?.let { java.net.URI(it) }?.let { uri ->
                                if (uri.host == null) "" else "${uri.scheme}://${uri.host}${if (uri.port == -1) "" else ":${uri.port}"}"
                            }.orEmpty() }.getOrDefault("")
                            Text(origin.ifBlank { "Unknown website origin" }, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                            Text(dialog.message)
                        }
                        if (dialog.type == "prompt") OutlinedTextField(
                            promptText,
                            { promptText = it.take(AgentBrowserDialogs.MAX_RESPONSE_CHARACTERS) },
                            label = { Text("Response") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        onReviewAgentApproval?.let { review ->
                            OutlinedButton(onClick = review) { Text("Review Agent permission") }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { respond(true) }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { respond(false) }) { Text("Cancel") } },
            )
        }
    }
}

private class BrowserViewHost(context: Context) : FrameLayout(context) {
    var browser: WebView? = null
    var binding: AgentBrowserController.Binding? = null

    fun present(presented: Boolean) {
        val visibility = if (presented) View.VISIBLE else View.INVISIBLE
        if (!presented) { clearFocus(); browser?.clearFocus() }
        this.visibility = visibility
        browser?.visibility = visibility
        descendantFocusability = if (presented) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = if (presented) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
}
