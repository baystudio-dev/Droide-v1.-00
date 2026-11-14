package com.baystudio.droide.ui

import android.view.HapticFeedbackConstants
import android.view.KeyEvent

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.baystudio.droide.core.DevicePtySessionHandle
import com.baystudio.droide.core.CodingKeyboardMode
import com.baystudio.droide.core.ITerminalSession
import com.baystudio.droide.core.DroideThemeSnapshot
import com.baystudio.droide.core.TerminalThemeAuthority
import com.baystudio.droide.core.NativeTerminalGeometrySession
import com.baystudio.droide.core.NativePtySessionHandle
import com.baystudio.droide.core.TerminalCommandActivitySession
import com.baystudio.droide.core.TerminalManager
import com.baystudio.droide.core.TerminalSession
import com.baystudio.droide.core.TerminalCommandHistory
import com.baystudio.droide.core.TerminalCommandDraft
import kotlinx.coroutines.launch

 
@Composable
internal fun TerminalPane(
    session: ITerminalSession,
    profile: TerminalManager.Profile,
    onOpenLinux: (() -> Unit)? = null,
    theme: DroideThemeSnapshot,
    accessoryKeysComfortable: Boolean = false,
    accessoryInputFocus: AccessoryInputFocusController,
    accessoryKeysExpanded: Boolean,
    typingFocusMode: Boolean = false,
    codingKeyboardMode: CodingKeyboardMode = CodingKeyboardMode.DEFAULT,
    onAccessoryKeysExpandedChange: (Boolean) -> Unit,
) {
    // A native view's IME client/focus callbacks capture its session-specific owners.
    // Recreate the view when switching tabs, while keeping the PTY session alive.
    key(session) {
        when (session) {
            is NativePtySessionHandle -> NativePtyTerminalPane(session, profile, onOpenLinux, theme, accessoryKeysComfortable, accessoryInputFocus, accessoryKeysExpanded, typingFocusMode, codingKeyboardMode, onAccessoryKeysExpandedChange)
            is DevicePtySessionHandle -> DevicePtyTerminalPane(session, profile, onOpenLinux, theme, accessoryKeysComfortable, accessoryInputFocus, accessoryKeysExpanded, typingFocusMode, codingKeyboardMode, onAccessoryKeysExpandedChange)
            else -> PipeTerminalPane(session, profile, onOpenLinux, accessoryKeysComfortable, accessoryInputFocus, accessoryKeysExpanded, typingFocusMode, codingKeyboardMode, onAccessoryKeysExpandedChange)
        }
    }
}

@Composable
private fun NativePtyTerminalPane(
    session: NativePtySessionHandle,
    profile: TerminalManager.Profile,
    onOpenLinux: (() -> Unit)?,
    theme: DroideThemeSnapshot,
    accessoryKeysComfortable: Boolean,
    accessoryInputFocus: AccessoryInputFocusController,
    accessoryKeysExpanded: Boolean,
    typingFocusMode: Boolean,
    codingKeyboardMode: CodingKeyboardMode,
    onAccessoryKeysExpandedChange: (Boolean) -> Unit,
) {
    val workspacePresented = LocalWorkspacePresented.current
    val exitVisible = LocalWorkspaceExitVisible.current
    val presentationGate = remember(session) { WorkspaceNativePresentationGate() }
    var viewRef by remember(session) { mutableStateOf<DroideNativeTerminalView?>(null) }
    val geometrySession = session as? NativeTerminalGeometrySession
    val geometry = geometrySession?.geometry?.collectAsState()?.value
    val displayedPty by session.displayedPtySession.collectAsState()
    // Reading this flow triggers rebinding; the live getter avoids a stale composition target.
    displayedPty
    val accessoryModifiers = remember(session) { AccessoryModifierController() }
    val accessoryFocusOwner = remember(session) { accessoryInputFocus.owner(AccessoryInputTarget.TERMINAL_NATIVE) }
    DisposableEffect(session) {
        session.setScreenUpdateListener {
            viewRef?.let { view ->
                // Rebind on the same main-Looper callback as ownership changes. Waiting
                // for recomposition would leave a short input window targeting the old PTY.
                val target = session.nativePtySession
                if (view.currentSession !== target) view.attachSession(target)
                view.onScreenUpdated()
            }
        }
        session.setBellListener {
            val view = viewRef
            if (view != null) view.post {
                if (viewRef === view && view.isAttachedToWindow) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
        }
        onDispose {
            session.setScreenUpdateListener(null)
            session.setBellListener(null)
            accessoryModifiers.clear()
            accessoryInputFocus.clearIf(accessoryFocusOwner)
            runCatching { viewRef?.setTerminalCursorBlinkerState(false, false) }
            viewRef = null
        }
    }

    AccessoryKeysScaffold(
        focusOwner = accessoryFocusOwner,
        focusController = accessoryInputFocus,
        expanded = accessoryKeysExpanded,
        onExpandedChange = onAccessoryKeysExpandedChange,
        modifiers = accessoryModifiers,
        comfortable = accessoryKeysComfortable,
        showOverlayHandle = false,
        onInsertText = { text ->
            val view = viewRef
            if (view != null) {
                text.codePoints().forEach { codePoint ->
                    view.inputCodePoint(
                        codePoint,
                        accessoryModifiers.isActive(AccessoryModifier.CTRL),
                        accessoryModifiers.isActive(AccessoryModifier.ALT),
                    )
                }
                view.requestFocus()
            } else {
                session.writeRaw(text)
            }
        },
        onKeyCode = { keyCode, forcedMetaState ->
            viewRef?.let { view ->
                dispatchAccessoryKey(view, keyCode, accessoryModifiers.androidMetaState(forcedMetaState))
                view.requestFocus()
            }
        },
    ) {
        TerminalSessionHeader(session, profile, typingFocusMode, onOpenLinux,
            accessoryHandle = { TerminalAccessoryHandle(accessoryFocusOwner, accessoryInputFocus,
                accessoryKeysExpanded, onAccessoryKeysExpandedChange) })

        // Keep the touch gutter outside the Android surface. Both rendering and
        // PTY resize must use the same smaller viewport; View padding would not
        // change upstream Termux's cell geometry.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(theme.ui.background)) {
          val bottomClearance = bottomTouchClearance(maxHeight)
          AndroidView(
            modifier = Modifier.fillMaxSize().padding(bottom = bottomClearance),
            factory = { context ->
                val view = DroideNativeTerminalView(context)
                viewRef = view
                view.viewportChanged = { owner, size -> geometrySession?.reportViewport(owner, size) }
                view.copySelection = session::copySelection
                val viewClient = DroideTerminalViewClient(
                    context, { view.terminalView.takeIf { viewRef === view } }, NativeTerminalTextSize.DEFAULT_SP, accessoryModifiers,
                    inputViewProvider = { view.takeIf { viewRef === view } },
                    onCommandInput = { (session as? TerminalCommandActivitySession)?.noteCommandInput() },
                    onGeometryChanged = view::reportViewport,
                )
                view.apply {
                    applyKeyboardMode(codingKeyboardMode)
                    setTerminalViewClient(viewClient)
                    setTextSize(nativeTerminalTextSizePixels(context, NativeTerminalTextSize.DEFAULT_SP))
                    setTerminalCursorBlinkerRate(600)
                    TerminalThemeAuthority.apply(session, theme.terminal)
                    attachSession(session.nativePtySession)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    inputFocusChanged = { focused ->
                        if (viewRef === this) {
                            accessoryInputFocus.onFocusChanged(accessoryFocusOwner, focused)
                            if (!focused) accessoryModifiers.clear()
                        }
                    }
                    presentationGate.update(this, workspacePresented, exitVisible)
                    if (workspacePresented) requestFocus()
                }
                viewRef = view
                view
            },
            update = { view ->
                presentationGate.update(view, workspacePresented, exitVisible)
                viewRef = view
                view.applyKeyboardMode(codingKeyboardMode)
                TerminalThemeAuthority.apply(session, theme.terminal)
                if (view.currentSession !== session.nativePtySession) view.attachSession(session.nativePtySession)
                view.updateGeometry(geometry)
                view.terminalView.invalidate()
                accessoryInputFocus.onFocusChanged(accessoryFocusOwner, view.hasFocus())
            },
            onRelease = { view ->
                presentationGate.forget(view)
                view.release()
                if (viewRef === view) viewRef = null
            },
          )
        }

    }
}

@Composable
private fun DevicePtyTerminalPane(
    session: DevicePtySessionHandle,
    profile: TerminalManager.Profile,
    onOpenLinux: (() -> Unit)?,
    theme: DroideThemeSnapshot,
    accessoryKeysComfortable: Boolean,
    accessoryInputFocus: AccessoryInputFocusController,
    accessoryKeysExpanded: Boolean,
    typingFocusMode: Boolean,
    codingKeyboardMode: CodingKeyboardMode,
    onAccessoryKeysExpandedChange: (Boolean) -> Unit,
) {
    val workspacePresented = LocalWorkspacePresented.current
    val exitVisible = LocalWorkspaceExitVisible.current
    val presentationGate = remember(session) { WorkspaceNativePresentationGate() }
    val status by session.status.collectAsState()
    var viewRef by remember(session) { mutableStateOf<DeviceTerminalView?>(null) }
    val accessoryModifiers = remember(session) { AccessoryModifierController() }
    val accessoryFocusOwner = remember(session) { accessoryInputFocus.owner(AccessoryInputTarget.TERMINAL_DEVICE) }
    DisposableEffect(session) {
        onDispose {
            accessoryModifiers.clear()
            accessoryInputFocus.clearIf(accessoryFocusOwner)
            viewRef = null
        }
    }

    AccessoryKeysScaffold(
        focusOwner = accessoryFocusOwner,
        focusController = accessoryInputFocus,
        expanded = accessoryKeysExpanded,
        onExpandedChange = onAccessoryKeysExpandedChange,
        modifiers = accessoryModifiers,
        comfortable = accessoryKeysComfortable,
        showOverlayHandle = false,
        onInsertText = { text -> viewRef?.sendAccessoryText(text) ?: session.writeRaw(text) },
        onKeyCode = { keyCode, forcedMetaState ->
            viewRef?.sendAccessoryKey(keyCode, forcedMetaState) ?: session.sendKeyCode(keyCode)
        },
    ) {
        TerminalSessionHeader(session, profile, typingFocusMode, onOpenLinux, status,
            accessoryHandle = { TerminalAccessoryHandle(accessoryFocusOwner, accessoryInputFocus,
                accessoryKeysExpanded, onAccessoryKeysExpandedChange) })

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(theme.ui.background)) {
          val bottomClearance = bottomTouchClearance(maxHeight)
          AndroidView(
            modifier = Modifier.fillMaxSize().padding(bottom = bottomClearance),
            factory = { context ->
                DeviceTerminalView(context).apply {
                    applyKeyboardMode(codingKeyboardMode)
                    TerminalThemeAuthority.apply(session, theme.terminal)
                    applyTheme(theme.terminal)
                    attachSession(session)
                    this.accessoryModifiers = accessoryModifiers
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setOnFocusChangeListener { _, focused ->
                        accessoryInputFocus.onFocusChanged(accessoryFocusOwner, focused)
                        if (!focused) accessoryModifiers.clear()
                    }
                    presentationGate.update(this, workspacePresented, exitVisible)
                    if (workspacePresented) requestFocus()
                    viewRef = this
                }
            },
            update = { view ->
                presentationGate.update(view, workspacePresented, exitVisible)
                view.applyKeyboardMode(codingKeyboardMode)
                TerminalThemeAuthority.apply(session, theme.terminal)
                view.applyTheme(theme.terminal)
                view.attachSession(session)
                view.accessoryModifiers = accessoryModifiers
                viewRef = view
                accessoryInputFocus.onFocusChanged(accessoryFocusOwner, view.hasFocus())
            },
            onRelease = { view ->
                presentationGate.forget(view)
                view.release()
            },
          )
        }

    }
}

@Composable
private fun PipeTerminalPane(
    session: ITerminalSession,
    profile: TerminalManager.Profile,
    onOpenLinux: (() -> Unit)?,
    accessoryKeysComfortable: Boolean,
    accessoryInputFocus: AccessoryInputFocusController,
    accessoryKeysExpanded: Boolean,
    typingFocusMode: Boolean,
    codingKeyboardMode: CodingKeyboardMode,
    onAccessoryKeysExpandedChange: (Boolean) -> Unit,
) {
    val output by session.output.collectAsState()
    var cmd by rememberSaveable(session, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val commandHistory = remember(session) { (session as? TerminalSession)?.commandHistory ?: TerminalCommandHistory() }
    var inputFocused by remember(session) { mutableStateOf(false) }
    val accessoryModifiers = remember(session) { AccessoryModifierController() }
    val accessoryFocusOwner = remember(session) { accessoryInputFocus.owner(AccessoryInputTarget.TERMINAL_FALLBACK) }
    val scrollState = key(session) { rememberScrollState() }
    val scope = rememberWorkspaceCoroutineScope()

    var actionError by remember(session) { mutableStateOf<String?>(null) }
    var followOutput by remember(session) { mutableStateOf(true) }
    var appScrolls by remember(session) { mutableIntStateOf(0) }
    var lastAppScrollValue by remember(session) { mutableIntStateOf(-1) }
    suspend fun scrollToLatest() {
        appScrolls++
        try {
            withFrameNanos { }
            lastAppScrollValue = scrollState.maxValue
            scrollState.scrollTo(lastAppScrollValue)
            // Keep the app ownership through the frame which publishes this
            // position; a keyboard resize must not turn it into a user scroll.
            withFrameNanos { }
        } finally { appScrolls-- }
    }
    LaunchedEffect(scrollState) {
        var previous: Triple<Int, Int, Boolean>? = null
        snapshotFlow { Triple(scrollState.value, scrollState.maxValue, scrollState.isScrollInProgress) }.collect { now ->
            val old = previous
            if (old != null && appScrolls == 0 && (now.first != lastAppScrollValue || !followOutput) &&
                now.first != old.first && (now.third || now.second == old.second)) {
                // Only genuine movement changes the reader's intent. Layout
                // clamping and delayed app scroll observations retain it.
                followOutput = scrollState.maxValue - now.first <= 2
            }
            previous = now
        }
    }
    LaunchedEffect(scrollState) {
        scrollState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> followOutput = false
                is DragInteraction.Stop, is DragInteraction.Cancel ->
                    followOutput = scrollState.maxValue - scrollState.value <= 2
            }
        }
    }
    // Keyboard/deck resize can change the tail position without adding output.
    // Keep following only when the reader had not deliberately scrolled away.
    LaunchedEffect(output, followOutput, scrollState.maxValue) {
        if (followOutput && !scrollState.isScrollInProgress) {
            scrollToLatest()
        }
    }
    LaunchedEffect(inputFocused) {
        accessoryInputFocus.onFocusChanged(accessoryFocusOwner, inputFocused)
        if (!inputFocused) accessoryModifiers.clear()
    }
    DisposableEffect(session) {
        onDispose {
            commandHistory.endBrowsing()
            accessoryModifiers.clear()
            accessoryInputFocus.clearIf(accessoryFocusOwner)
        }
    }

    fun submit() {
        if (cmd.text.isBlank()) return
        runUiCatching(onError = { actionError = it.message ?: "Could not send command" }) {
            val accepted = if (session is TerminalSession) session.submitCommandInput(cmd.text) else {
                session.send(cmd.text)
                commandHistory.submitted(cmd.text)
                true // Generic fallback interfaces provide no stronger delivery receipt.
            }
            if (!accepted) {
                actionError = "Command was not sent. Your draft has been kept."
                return@runUiCatching
            }
            cmd = TextFieldValue("")
            actionError = null
        }
    }

    fun insertText(text: String) {
        commandHistory.endBrowsing()
        val start = cmd.selection.min.coerceIn(0, cmd.text.length)
        val end = cmd.selection.max.coerceIn(start, cmd.text.length)
        val next = cmd.text.replaceRange(start, end, text)
        cmd = TextFieldValue(next, TextRange(start + text.length))
    }

    fun sendControlKey(keyCode: Int, forcedMetaState: Int) {
        if ((forcedMetaState and KeyEvent.META_CTRL_ON) != 0 && keyCode == KeyEvent.KEYCODE_C) {
            runUiCatching(onError = { actionError = it.message ?: "Could not interrupt command" }) { session.interrupt(); actionError = null }
            return
        }
        fun recall(value: TerminalCommandDraft?) {
            value ?: return
            cmd = TextFieldValue(value.text, TextRange(value.selectionStart.coerceIn(0, value.text.length),
                value.selectionEnd.coerceIn(0, value.text.length)))
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> recall(commandHistory.previous(
                TerminalCommandDraft(cmd.text, cmd.selection.start, cmd.selection.end)))
            KeyEvent.KEYCODE_DPAD_DOWN -> recall(commandHistory.next())
            KeyEvent.KEYCODE_TAB -> insertText("\t")
            KeyEvent.KEYCODE_DPAD_LEFT -> cmd = cmd.copy(selection = TextRange((cmd.selection.min - 1).coerceAtLeast(0)))
            KeyEvent.KEYCODE_DPAD_RIGHT -> cmd = cmd.copy(selection = TextRange((cmd.selection.max + 1).coerceAtMost(cmd.text.length)))
            KeyEvent.KEYCODE_MOVE_HOME -> cmd = cmd.copy(selection = TextRange(0))
            KeyEvent.KEYCODE_MOVE_END -> cmd = cmd.copy(selection = TextRange(cmd.text.length))
            KeyEvent.KEYCODE_DEL -> {
                commandHistory.endBrowsing()
                val start = cmd.selection.min
                val end = cmd.selection.max
                when {
                    start != end -> cmd = TextFieldValue(cmd.text.removeRange(start, end), TextRange(start))
                    start > 0 -> cmd = TextFieldValue(cmd.text.removeRange(start - 1, start), TextRange(start - 1))
                }
            }
            KeyEvent.KEYCODE_PAGE_UP -> scope.launch { scrollState.animateScrollTo((scrollState.value - 240).coerceAtLeast(0)) }
            KeyEvent.KEYCODE_PAGE_DOWN -> scope.launch { scrollState.animateScrollTo((scrollState.value + 240).coerceAtMost(scrollState.maxValue)) }
        }
    }

    AccessoryKeysScaffold(
        focusOwner = accessoryFocusOwner,
        focusController = accessoryInputFocus,
        expanded = accessoryKeysExpanded,
        onExpandedChange = onAccessoryKeysExpandedChange,
        modifiers = accessoryModifiers,
        comfortable = accessoryKeysComfortable,
        showOverlayHandle = false,
        onInsertText = ::insertText,
        onKeyCode = ::sendControlKey,
    ) {
        TerminalSessionHeader(session, profile, typingFocusMode, onOpenLinux,
            accessoryHandle = { TerminalAccessoryHandle(accessoryFocusOwner, accessoryInputFocus,
                accessoryKeysExpanded, onAccessoryKeysExpandedChange) })
        actionError?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error, ownerKey = session) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
          val bottomClearance = bottomTouchClearance(maxHeight)
          Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(scrollState)
              .padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = bottomClearance)) {
            TerminalLinkText(
                output.ifBlank { "$ Droide fallback terminal\n" },
            )
          }
          if (!followOutput && scrollState.maxValue > 0) TextButton(
              onClick = { scope.launch { scrollToLatest(); followOutput = true } },
              modifier = Modifier.align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.surface).padding(end = 6.dp),
          ) { Text("Latest output") }
        }
        CodingKeyboardInput(codingKeyboardMode) {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = cmd,
                    onValueChange = {
                        if (it.text != cmd.text) commandHistory.endBrowsing()
                        cmd = it
                    },
                    modifier = Modifier.weight(1f).onFocusChanged { inputFocused = it.isFocused },
                    placeholder = { Text("command") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Send,
                        autoCorrectEnabled = codingKeyboardMode.allowsWordCorrections(),
                    ),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                IconButton(onClick = { submit() }) { Icon(Icons.Default.Send, "Run") }
            }
        }
    }
}
