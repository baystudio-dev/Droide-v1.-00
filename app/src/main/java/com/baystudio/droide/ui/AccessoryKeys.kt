package com.baystudio.droide.ui

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

internal enum class AccessoryKeyProfile { EDITOR, TERMINAL }
internal enum class AccessoryInputTarget { NONE, EDITOR, TERMINAL_NATIVE, TERMINAL_DEVICE, TERMINAL_FALLBACK }

internal fun AccessoryInputTarget.keyProfile(): AccessoryKeyProfile? = when (this) {
    AccessoryInputTarget.EDITOR -> AccessoryKeyProfile.EDITOR
    AccessoryInputTarget.TERMINAL_NATIVE,
    AccessoryInputTarget.TERMINAL_DEVICE,
    AccessoryInputTarget.TERMINAL_FALLBACK -> AccessoryKeyProfile.TERMINAL
    AccessoryInputTarget.NONE -> null
}

internal class AccessoryInputFocusOwner internal constructor(
    val target: AccessoryInputTarget,
)

// One authoritative focus owner for the whole workbench accessory surface.

@Stable
internal class AccessoryInputFocusController {
    var activeTarget by mutableStateOf(AccessoryInputTarget.NONE)
        private set

    private var activeOwner by mutableStateOf<AccessoryInputFocusOwner?>(null)

    fun owner(target: AccessoryInputTarget): AccessoryInputFocusOwner = AccessoryInputFocusOwner(target)

    fun onFocusChanged(owner: AccessoryInputFocusOwner, focused: Boolean) {
        if (focused) {
            activeOwner = owner
            activeTarget = owner.target
        } else if (activeOwner === owner) {
            activeOwner = null
            activeTarget = AccessoryInputTarget.NONE
        }
    }

    fun clearIf(owner: AccessoryInputFocusOwner) {
        if (activeOwner === owner) {
            activeOwner = null
            activeTarget = AccessoryInputTarget.NONE
        }
    }

    fun owns(owner: AccessoryInputFocusOwner): Boolean = activeOwner === owner
}

@Stable
internal class AccessoryModifierController {
    private val policy = AccessoryModifierPolicy()
    private var snapshot by mutableStateOf(policy.snapshot)
    val ctrl: AccessoryModifierMode get() = snapshot.ctrl
    val alt: AccessoryModifierMode get() = snapshot.alt
    val shift: AccessoryModifierMode get() = snapshot.shift

    fun mode(modifier: AccessoryModifier): AccessoryModifierMode = snapshot.mode(modifier)
    fun tap(modifier: AccessoryModifier) { policy.tap(modifier); snapshot = policy.snapshot }
    fun tapAt(modifier: AccessoryModifier, uptimeMillis: Long, minTimeMillis: Long, timeoutMillis: Long) {
        policy.tapAt(modifier, uptimeMillis, minTimeMillis, timeoutMillis)
        snapshot = policy.snapshot
    }
    fun lock(modifier: AccessoryModifier) { policy.lock(modifier); snapshot = policy.snapshot }
    fun isActive(modifier: AccessoryModifier): Boolean = mode(modifier) != AccessoryModifierMode.OFF
    fun hasCtrlOrAlt(): Boolean = isActive(AccessoryModifier.CTRL) || isActive(AccessoryModifier.ALT)

    fun androidMetaState(extra: Int = 0): Int {
        var meta = extra
        if (isActive(AccessoryModifier.CTRL)) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (isActive(AccessoryModifier.ALT)) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (isActive(AccessoryModifier.SHIFT)) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        return meta
    }

    fun consumeArmed() { policy.consumeArmed(); snapshot = policy.snapshot }
    fun clear() { policy.clear(); snapshot = policy.snapshot }
}

internal fun accessoryKeysShouldBeActive(
    ownsInputFocus: Boolean,
    imeVisible: Boolean,
    imeGraceVisible: Boolean,
): Boolean = ownsInputFocus && (imeVisible || imeGraceVisible)

private val AccessoryCompactRowHeight = 44.dp
private val AccessoryComfortableRowHeight = 48.dp

// Portrait uses a fixed 2x7 two-bar deck so fourteen high-frequency keys never require
// horizontal scrolling.

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AccessoryKeysScaffold(
    focusOwner: AccessoryInputFocusOwner,
    focusController: AccessoryInputFocusController,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifiers: AccessoryModifierController,
    comfortable: Boolean = false,
    languageId: String? = null,
    onInsertText: (String) -> Unit,
    onInsertSnippet: (String) -> Unit = {},
    onKeyCode: (keyCode: Int, forcedMetaState: Int) -> Unit,
    modifier: Modifier = Modifier,
    showOverlayHandle: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val imeMotion = rememberAccessoryImeMotion()
    val profile = requireNotNull(focusOwner.target.keyProfile())
    val presented = rememberUpdatedState(LocalWorkspacePresented.current)
    val currentExpanded = rememberUpdatedState(expanded)
    val ownsInputFocus = presented.value && focusController.owns(focusOwner)
    val motionEnabled = rememberDroideMotionEnabled()
    val rowHeight = if (comfortable) AccessoryComfortableRowHeight else AccessoryCompactRowHeight
    val portraitFastDeck = !droidePhysicalLandscape()
    val bodyHeight = if (portraitFastDeck) rowHeight * 2 else rowHeight
    // Only explicit expansion/focus changes have a local clock. IME travel uses native pixels.
    val expansion = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = if (motionEnabled) tween(AccessoryMotionMillis) else snap(),
        label = "AccessoryKeysExpansion",
    )
    val ownership = animateFloatAsState(
        targetValue = if (ownsInputFocus) 1f else 0f,
        // Acquiring focus must not add a second entrance timer to the opening keyboard.
        animationSpec = if (ownsInputFocus || !motionEnabled) snap() else tween(AccessoryMotionMillis),
        label = "AccessoryKeysOwnership",
    )
    val keepSurface by remember(ownership, ownsInputFocus) { derivedStateOf { ownsInputFocus || ownership.value > 0f } }
    val allowHandle = remember(focusOwner, focusController, imeMotion) {
        { presented.value && focusController.owns(focusOwner) && imeMotion.allowsInput() }
    }
    val allowKeys = remember(allowHandle) { { allowHandle() && currentExpanded.value } }
    val keysEnabled by remember(allowKeys) { derivedStateOf { allowKeys() } }
    val handleEnabled by remember(allowHandle) { derivedStateOf { allowHandle() } }
    val deckFraction = { ownership.value * expansion.value * imeMotion.fraction() }
    val handleFraction = { ownership.value * imeMotion.fraction() }
    val accessoryImeInsetModifier =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) Modifier.imePadding() else Modifier
    Box(modifier.fillMaxSize().then(accessoryImeInsetModifier)) {
        Column(Modifier.fillMaxSize()) {
            content()

            if (keepSurface) {
                AccessoryRevealLayout(
                    fullHeight = bodyHeight,
                    fraction = deckFraction,
                    modifier = Modifier.fillMaxWidth().then(if (keysEnabled) Modifier else Modifier.clearAndSetSemantics {}),
                ) {
                    CompositionLocalProvider(LocalAccessoryInputAllowed provides allowKeys) {
                    AccessoryKeysBody(
                        profile = profile,
                        languageId = languageId,
                        modifiers = modifiers,
                        onInsertText = onInsertText,
                        onInsertSnippet = onInsertSnippet,
                        onKeyCode = onKeyCode,
                        rowHeight = rowHeight,
                        portraitFastDeck = portraitFastDeck,
                    )
                    }
                }
            }
        }

        if (keepSurface && showOverlayHandle) {

            AccessoryHandle(
                profile = profile,
                expanded = expanded,
                onClick = { onExpandedChange(!expanded) },
                inputAllowed = allowHandle,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset { IntOffset(0, -(bodyHeight.toPx() * deckFraction()).roundToInt()) }
                    .graphicsLayer { alpha = handleFraction() }
                    .then(if (handleEnabled) Modifier else Modifier.clearAndSetSemantics {})
                    .windowInsetsPadding(WindowInsets.safeGestures.only(WindowInsetsSides.End))
                    .padding(end = 4.dp)
                    .zIndex(10f),
            )
        }
    }
}

/** Terminal chrome owns this control so it cannot intercept touches on the last PTY row. */
@Composable
internal fun TerminalAccessoryHandle(
    focusOwner: AccessoryInputFocusOwner,
    focusController: AccessoryInputFocusController,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val imeMotion = rememberAccessoryImeMotion()
    val presented = rememberUpdatedState(LocalWorkspacePresented.current)
    val ownsInputFocus = presented.value && focusController.owns(focusOwner)
    val motionEnabled = rememberDroideMotionEnabled()
    val ownership = animateFloatAsState(if (ownsInputFocus) 1f else 0f,
        animationSpec = if (ownsInputFocus || !motionEnabled) snap() else tween(AccessoryMotionMillis),
        label = "TerminalAccessoryHandleOwnership")
    val keepSurface by remember(ownership, ownsInputFocus) { derivedStateOf { ownsInputFocus || ownership.value > 0f } }
    val allowInput = remember(focusOwner, focusController, imeMotion) {
        { presented.value && focusController.owns(focusOwner) && imeMotion.allowsInput() }
    }
    val inputEnabled by remember(allowInput) { derivedStateOf { allowInput() } }
    if (focusOwner.target.keyProfile() == AccessoryKeyProfile.TERMINAL && keepSurface) {
        AccessoryHandleRevealLayout(
            fraction = { ownership.value * imeMotion.fraction() },
            modifier = if (inputEnabled) Modifier else Modifier.clearAndSetSemantics {},
        ) {
            AccessoryHandle(AccessoryKeyProfile.TERMINAL, expanded, { onExpandedChange(!expanded) },
                Modifier.size(48.dp), inputAllowed = allowInput)
        }
    }
}

@Composable
private fun AccessoryKeysBody(
    profile: AccessoryKeyProfile,
    languageId: String?,
    modifiers: AccessoryModifierController,
    onInsertText: (String) -> Unit,
    onInsertSnippet: (String) -> Unit,
    onKeyCode: (keyCode: Int, forcedMetaState: Int) -> Unit,
    rowHeight: androidx.compose.ui.unit.Dp,
    portraitFastDeck: Boolean,
    modifier: Modifier = Modifier,
) {
    val editorScroll = rememberSaveable(languageId, saver = androidx.compose.foundation.ScrollState.Saver) {
        androidx.compose.foundation.ScrollState(0)
    }
    val terminalScroll = rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) {
        androidx.compose.foundation.ScrollState(0)
    }
    val scrollState = if (profile == AccessoryKeyProfile.EDITOR) editorScroll else terminalScroll

    val bodyHeight = if (portraitFastDeck) rowHeight * 2 else rowHeight
    Box(
        modifier
            .fillMaxWidth()
            .height(bodyHeight)
            .background(DroideColors.Surface.copy(alpha = 0.98f)),
    ) {
        if (!portraitFastDeck) {
            AccessoryKeyRow(scrollState = scrollState, rowHeight = rowHeight) {
                when (profile) {
                    AccessoryKeyProfile.EDITOR -> RenderEditorAccessorySequence(
                        languageId = languageId,
                        modifiers = modifiers,
                        onInsertText = onInsertText,
                        onInsertSnippet = onInsertSnippet,
                        onKeyCode = onKeyCode,
                    )
                    AccessoryKeyProfile.TERMINAL -> terminalKeys.forEach { key ->
                        AccessoryControlKeyButton(key, modifiers, onInsertText, onKeyCode)
                    }
                }
            }
        } else {

            val portraitEntries: List<EditorFastEntry> = when (profile) {
                AccessoryKeyProfile.EDITOR -> editorPortraitEntries(languageId)
                AccessoryKeyProfile.TERMINAL -> terminalPortraitKeys.map(EditorFastEntry::Control)
            }
            Column(Modifier.fillMaxSize()) {
                portraitEntries.chunked(PortraitAccessoryColumns).take(2).forEach { row ->
                    AccessoryFastKeyRow(rowHeight = rowHeight) {
                        row.forEach { entry ->
                            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                when (entry) {
                                    is EditorFastEntry.Control -> AccessoryControlKeyButton(entry.key, modifiers, onInsertText, onKeyCode, compactFixed = true)
                                    is EditorFastEntry.Action -> EditorAccessoryActionButton(entry.action, modifiers, onInsertText, onInsertSnippet, compactFixed = true)
                                    is EditorFastEntry.Text -> EditorAccessoryTextButton(entry.text, modifiers, onInsertText, compactFixed = true)
                                }
                            }
                        }
                        repeat((PortraitAccessoryColumns - row.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderEditorAccessorySequence(
    languageId: String?,
    modifiers: AccessoryModifierController,
    onInsertText: (String) -> Unit,
    onInsertSnippet: (String) -> Unit,
    onKeyCode: (keyCode: Int, forcedMetaState: Int) -> Unit,
) {
    editorLeadingKeys.forEach { key ->
        AccessoryControlKeyButton(key, modifiers, onInsertText, onKeyCode)
    }
    val languageActions = LanguageAccessoryKeys.forLanguage(languageId)
    val priorityCount = LanguageAccessoryKeys.priorityForLanguage(languageId).size
    val priorityActions = languageActions.take(priorityCount)
    val overflowActions = languageActions.drop(priorityCount)
    val prioritySymbols = priorityCodingKeys(languageId)
    priorityActions.forEach { action ->
        EditorAccessoryActionButton(action, modifiers, onInsertText, onInsertSnippet)
    }
    prioritySymbols.forEach { text ->
        EditorAccessoryTextButton(text, modifiers, onInsertText)
    }
    overflowActions.forEach { action ->
        EditorAccessoryActionButton(action, modifiers, onInsertText, onInsertSnippet)
    }
    codingKeys.filterNot(prioritySymbols::contains).forEach { text ->
        EditorAccessoryTextButton(text, modifiers, onInsertText)
    }
    editorTrailingKeys.forEach { key ->
        AccessoryControlKeyButton(key, modifiers, onInsertText, onKeyCode)
    }
}

@Composable
private fun AccessoryFastKeyRow(
    rowHeight: androidx.compose.ui.unit.Dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .windowInsetsPadding(WindowInsets.safeGestures.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun EditorAccessoryActionButton(
    action: EditorAccessoryAction,
    modifiers: AccessoryModifierController,
    onInsertText: (String) -> Unit,
    onInsertSnippet: (String) -> Unit,
    compactFixed: Boolean = false,
) {
    AccessoryKey(
        label = action.label,
        contentDescription = action.contentDescription,
        compactFixed = compactFixed,
        onClick = {
            when (action) {
                is EditorAccessoryAction.Text -> onInsertText(action.text)
                is EditorAccessoryAction.Snippet -> onInsertSnippet(action.source)
            }
            modifiers.consumeArmed()
        },
    )
}

@Composable
private fun EditorAccessoryTextButton(
    text: String,
    modifiers: AccessoryModifierController,
    onInsertText: (String) -> Unit,
    compactFixed: Boolean = false,
) {
    AccessoryKey(
        label = text,
        contentDescription = "Insert $text",
        compactFixed = compactFixed,
        onClick = {
            onInsertText(text)
            modifiers.consumeArmed()
        },
    )
}

@Composable
private fun AccessoryControlKeyButton(
    key: AccessoryControlKey,
    modifiers: AccessoryModifierController,
    onInsertText: (String) -> Unit,
    onKeyCode: (keyCode: Int, forcedMetaState: Int) -> Unit,
    compactFixed: Boolean = false,
) {
    if (key.modifier != null) {
        ModifierKey(key, modifiers, compactFixed)
        return
    }

    fun invokePrimary() {
        if (key.insertText != null) onInsertText(key.insertText)
        else onKeyCode(requireNotNull(key.keyCode), key.forcedMetaState)
        modifiers.consumeArmed()
    }

    fun invokeLongPress() {
        when {
            key.longPressText != null -> onInsertText(key.longPressText)
            key.longPressKeyCode != null -> onKeyCode(key.longPressKeyCode, key.longPressMetaState)
            else -> return
        }
        modifiers.consumeArmed()
    }

    AccessoryKey(
        label = key.label,
        contentDescription = key.contentDescription,
        longPressDescription = key.longPressDescription,
        compactFixed = compactFixed,
        onClick = ::invokePrimary,
        onLongClick = if (key.longPressText != null || key.longPressKeyCode != null) ::invokeLongPress else null,
    )
}

@Composable
private fun AccessoryKeyRow(
    scrollState: androidx.compose.foundation.ScrollState,
    rowHeight: androidx.compose.ui.unit.Dp,
    content: @Composable RowScope.() -> Unit,
) {
    val allowInput = LocalAccessoryInputAllowed.current
    val inputEnabled by remember(allowInput) { derivedStateOf { allowInput() } }
    Box(Modifier.fillMaxWidth().height(rowHeight)) {
        Row(
            Modifier
                .fillMaxSize()
                .accessoryHorizontalScroll(scrollState, inputEnabled = inputEnabled)
                .windowInsetsPadding(WindowInsets.safeGestures.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )

        if (scrollState.value > 0) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(10.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(DroideColors.Surface.copy(alpha = 0.95f), Color.Transparent),
                        ),
                    ),
            )
        }
        if (scrollState.value < scrollState.maxValue) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(10.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, DroideColors.Surface.copy(alpha = 0.95f)),
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun ModifierKey(key: AccessoryControlKey, controller: AccessoryModifierController, compactFixed: Boolean = false) {
    val modifier = requireNotNull(key.modifier)
    val mode = controller.mode(modifier)
    val configuration = LocalViewConfiguration.current
    val state = when (mode) {
        AccessoryModifierMode.OFF -> "off"
        AccessoryModifierMode.ARMED -> "armed for next key"
        AccessoryModifierMode.LOCKED -> "locked"
    }
    AccessoryKey(
        label = key.label,
        contentDescription = key.contentDescription,
        selected = mode != AccessoryModifierMode.OFF,
        locked = mode == AccessoryModifierMode.LOCKED,
        stateDescription = state,
        compactFixed = compactFixed,
        // Foundation waits for its double-tap timeout before a single onClick when onDoubleClick is
        // supplied.
        onClick = { controller.tapAt(modifier, SystemClock.uptimeMillis(),
            configuration.doubleTapMinTimeMillis, configuration.doubleTapTimeoutMillis) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccessoryKey(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    locked: Boolean = false,
    stateDescription: String? = null,
    longPressDescription: String? = null,
    compactFixed: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val allowInput = LocalAccessoryInputAllowed.current
    val inputEnabled by remember(allowInput) { derivedStateOf { allowInput() } }
    val touchWidth = when {
        compactFixed -> 0.dp
        label.length <= 2 -> 40.dp
        label.length <= 4 -> 44.dp
        else -> 52.dp
    }
    val visualWidth = when {
        compactFixed -> 0.dp
        label.length <= 2 -> 36.dp
        label.length <= 4 -> 40.dp
        else -> 48.dp
    }
    Box(
        Modifier
            .fillMaxHeight()
            .then(if (compactFixed) Modifier.fillMaxWidth() else Modifier.widthIn(min = touchWidth))
            .focusProperties { canFocus = false }
            .semantics {
                this.contentDescription = if (longPressDescription == null) contentDescription else "$contentDescription; long press: $longPressDescription"
                if (stateDescription != null) this.stateDescription = stateDescription
            }

            .then(if (inputEnabled) Modifier.combinedClickable(
                onClick = { if (allowInput()) onClick() },
                onLongClick = onLongClick?.let { action -> { if (allowInput()) action() } },
            ) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        AccessoryVisualSurface(
            modifier = Modifier
                .height(36.dp)
                .then(if (compactFixed) Modifier.fillMaxWidth().padding(horizontal = 1.dp) else Modifier.widthIn(min = visualWidth)),
            shape = RoundedCornerShape(7.dp),
            color = when {
                locked -> DroideColors.Primary.copy(alpha = 0.34f)
                selected -> DroideColors.Primary.copy(alpha = 0.22f)
                else -> DroideColors.Surface3.copy(alpha = 0.88f)
            },
            contentColor = if (selected) DroideColors.Text else MaterialTheme.colorScheme.onSurface,
        ) {
            Box(Modifier.padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = label,
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (compactFixed) 12.sp else 13.sp,
                    fontWeight = if (locked) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccessoryHandle(
    profile: AccessoryKeyProfile,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    inputAllowed: () -> Boolean = { true },
) {
    val inputEnabled by remember(inputAllowed) { derivedStateOf { inputAllowed() } }
    Box(
        modifier
            .size(40.dp)
            .focusProperties { canFocus = false }
            .semantics {
                val name = if (profile == AccessoryKeyProfile.EDITOR) "editor keys" else "terminal keys"
                contentDescription = if (expanded) "Hide $name" else "Show $name"
            }
            .then(if (inputEnabled) Modifier.combinedClickable(
                onClick = { if (inputAllowed()) onClick() },
            ) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        AccessoryVisualSurface(
            Modifier.size(28.dp, 24.dp),
            shape = RoundedCornerShape(9.dp),
            color = DroideColors.Surface3.copy(alpha = 0.62f),
            contentColor = DroideColors.Text.copy(alpha = 0.92f),
            shadowElevation = 1.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

internal fun dispatchAccessoryKey(view: View, keyCode: Int, metaState: Int = 0): Boolean {
    val editorMeta = (view as? io.github.rosemoe.sora.widget.CodeEditor)?.keyMetaStates
    val normalized = KeyEvent.normalizeMetaState(metaState)
    val modifiers = if (editorMeta == null) emptyList() else buildList {
        if (normalized and KeyEvent.META_SHIFT_ON != 0 && !editorMeta.isShiftPressed)
            add(KeyEvent.KEYCODE_SHIFT_LEFT to KeyEvent.META_SHIFT_MASK)
        if (normalized and KeyEvent.META_ALT_ON != 0 && !editorMeta.isAltPressed)
            add(KeyEvent.KEYCODE_ALT_LEFT to KeyEvent.META_ALT_MASK)
    }
    try {

        modifiers.forEach { (key, _) ->
            editorMeta?.onKeyDown(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, key, 0, metaState))
        }
        val handledDown = view.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        val handledUp = view.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_UP, keyCode, 0, metaState))
        return handledDown || handledUp
    } finally {
        modifiers.asReversed().forEach { (key, mask) ->
            editorMeta?.onKeyUp(KeyEvent(0L, 0L, KeyEvent.ACTION_UP, key, 0, metaState and mask.inv()))
            editorMeta?.clearMetaStates(mask)
        }
    }
}

/** Decorative accessory pixels must not own a hit-test target. Material3's ordinary Surface
 * installs a pointerInput blocker even without onClick; input belongs to the enclosing key. */
@Composable
private fun AccessoryVisualSurface(
    modifier: Modifier,
    shape: Shape,
    color: Color,
    contentColor: Color,
    shadowElevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier.shadow(shadowElevation, shape, clip = false).background(color, shape).clip(shape),
            propagateMinConstraints = true,
        ) { content() }
    }
}
