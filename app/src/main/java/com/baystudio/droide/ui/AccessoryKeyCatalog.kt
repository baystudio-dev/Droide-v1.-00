package com.baystudio.droide.ui

import android.view.KeyEvent

internal data class AccessoryControlKey(
    val label: String,
    val keyCode: Int? = null,
    val modifier: AccessoryModifier? = null,
    val insertText: String? = null,
    val forcedMetaState: Int = 0,
    val longPressKeyCode: Int? = null,
    val longPressText: String? = null,
    val longPressMetaState: Int = 0,
    val contentDescription: String = label,
    val longPressDescription: String? = null,
)

internal val codingKeys = listOf(
    "{", "}", "(", ")", "[", "]", "<", ">", "=", ";", ":", ",", ".", "\"", "'", "`", "/", "\\", "|", "&", "!", "_", "-", "+", "*", "%", "#", "~", "?", "\$", "@", "^",
)

internal fun priorityCodingKeys(languageId: String?): List<String> = when (languageId?.lowercase()) {
    "html", "xml" -> listOf("<", ">", "\"", "'", "=", "/")
    "css", "scss", "less" -> listOf("{", "}", ":", ";", "(", ")")
    "python" -> listOf("(", ")", ":", "=", "[", "]")
    "shell", "powershell", "batch" -> listOf("\$", "|", "&", "/", "-", "_")
    "json" -> listOf("{", "}", "[", "]", ":", ",")
    "yaml" -> listOf(":", "-", "|", ">", "[", "]")
    "markdown" -> listOf("#", "*", "_", "[", "]", "(")
    "sql" -> listOf("(", ")", ",", ".", "=", ";")
    "toml", "ini", "properties" -> listOf("=", "[", "]", ".", "#", ";")
    "graphql" -> listOf("{", "}", "(", ")", ":", "!")
    "latex" -> listOf("\\", "{", "}", "\$", "_", "^")
    "vue", "svelte", "react" -> listOf("<", ">", "{", "}", "(", ")")
    "javascript", "typescript", "kotlin", "java", "go", "rust", "c", "cpp", "csharp",
    "php", "swift", "dart", "scala", "zig", "solidity", "objectivec" ->
        listOf("(", ")", "{", "}", "=", ";")
    else -> listOf("(", ")", "{", "}", "=", ";")
}

internal val editorLeadingKeys = listOf(
    AccessoryControlKey("Tab", KeyEvent.KEYCODE_TAB),
)
internal val editorTrailingKeys = listOf(
    AccessoryControlKey(
        "←", KeyEvent.KEYCODE_DPAD_LEFT,
        longPressKeyCode = KeyEvent.KEYCODE_MOVE_HOME,
        contentDescription = "Left arrow", longPressDescription = "Home",
    ),
    AccessoryControlKey(
        "↑", KeyEvent.KEYCODE_DPAD_UP,
        longPressKeyCode = KeyEvent.KEYCODE_PAGE_UP,
        contentDescription = "Up arrow", longPressDescription = "Page up",
    ),
    AccessoryControlKey(
        "↓", KeyEvent.KEYCODE_DPAD_DOWN,
        longPressKeyCode = KeyEvent.KEYCODE_PAGE_DOWN,
        contentDescription = "Down arrow", longPressDescription = "Page down",
    ),
    AccessoryControlKey(
        "→", KeyEvent.KEYCODE_DPAD_RIGHT,
        longPressKeyCode = KeyEvent.KEYCODE_MOVE_END,
        contentDescription = "Right arrow", longPressDescription = "End",
    ),
    AccessoryControlKey("Esc", KeyEvent.KEYCODE_ESCAPE, contentDescription = "Escape"),
)

internal val terminalPortraitKeys = listOf(

    AccessoryControlKey("Esc", KeyEvent.KEYCODE_ESCAPE, contentDescription = "Escape"),
    AccessoryControlKey("Ctrl", modifier = AccessoryModifier.CTRL, contentDescription = "Control modifier"),
    AccessoryControlKey("Alt", modifier = AccessoryModifier.ALT, contentDescription = "Alt modifier"),
    AccessoryControlKey("Tab", KeyEvent.KEYCODE_TAB),
    AccessoryControlKey("C-c", KeyEvent.KEYCODE_C, forcedMetaState = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON, contentDescription = "Control C"),
    AccessoryControlKey("/", insertText = "/", longPressText = "\\", contentDescription = "Slash", longPressDescription = "Backslash"),
    AccessoryControlKey("-", insertText = "-", longPressText = "|", contentDescription = "Hyphen", longPressDescription = "Pipe"),

    AccessoryControlKey(
        "←", KeyEvent.KEYCODE_DPAD_LEFT,
        longPressKeyCode = KeyEvent.KEYCODE_MOVE_HOME,
        contentDescription = "Left arrow", longPressDescription = "Home",
    ),
    AccessoryControlKey(
        "↓", KeyEvent.KEYCODE_DPAD_DOWN,
        longPressKeyCode = KeyEvent.KEYCODE_PAGE_DOWN,
        contentDescription = "Down arrow", longPressDescription = "Page down",
    ),
    AccessoryControlKey(
        "↑", KeyEvent.KEYCODE_DPAD_UP,
        longPressKeyCode = KeyEvent.KEYCODE_PAGE_UP,
        contentDescription = "Up arrow", longPressDescription = "Page up",
    ),
    AccessoryControlKey(
        "→", KeyEvent.KEYCODE_DPAD_RIGHT,
        longPressKeyCode = KeyEvent.KEYCODE_MOVE_END,
        contentDescription = "Right arrow", longPressDescription = "End",
    ),
    AccessoryControlKey("Home", KeyEvent.KEYCODE_MOVE_HOME),
    AccessoryControlKey("End", KeyEvent.KEYCODE_MOVE_END),
    AccessoryControlKey("Del", KeyEvent.KEYCODE_DEL, contentDescription = "Delete backward"),
)

internal val terminalOverflowKeys = listOf(
    AccessoryControlKey("Shift", modifier = AccessoryModifier.SHIFT, contentDescription = "Shift modifier"),
    AccessoryControlKey("|", insertText = "|", contentDescription = "Pipe"),
    AccessoryControlKey("~", insertText = "~", contentDescription = "Tilde"),
    AccessoryControlKey("PgUp", KeyEvent.KEYCODE_PAGE_UP, contentDescription = "Page up"),
    AccessoryControlKey("PgDn", KeyEvent.KEYCODE_PAGE_DOWN, contentDescription = "Page down"),
)

internal val terminalKeys = terminalPortraitKeys + terminalOverflowKeys

internal const val PortraitAccessoryColumns = 7
internal const val PortraitAccessorySlots = PortraitAccessoryColumns * 2
internal const val EditorPortraitNavigationSlots = 4

internal sealed interface EditorFastEntry {
    data class Control(val key: AccessoryControlKey) : EditorFastEntry
    data class Action(val action: EditorAccessoryAction) : EditorFastEntry
    data class Text(val text: String) : EditorFastEntry
}

internal fun editorPortraitEntries(languageId: String?): List<EditorFastEntry> {
    val priorityActions = LanguageAccessoryKeys.priorityForLanguage(languageId).take(3)
    val overflowActions = LanguageAccessoryKeys.overflowForLanguage(languageId)
    val prioritySymbols = priorityCodingKeys(languageId)
    val contentSlots = PortraitAccessorySlots - EditorPortraitNavigationSlots

    val content = buildList<EditorFastEntry> {
        add(EditorFastEntry.Control(editorLeadingKeys.first()))
        priorityActions.forEach { add(EditorFastEntry.Action(it)) }
        prioritySymbols.forEach { add(EditorFastEntry.Text(it)) }
        overflowActions.forEach { add(EditorFastEntry.Action(it)) }
        codingKeys.filterNot(prioritySymbols::contains).forEach { add(EditorFastEntry.Text(it)) }
    }.take(contentSlots)

    val navigation = listOf(
        EditorFastEntry.Control(editorTrailingKeys.first { it.label == "←" }),
        EditorFastEntry.Control(editorTrailingKeys.first { it.label == "↑" }),
        EditorFastEntry.Control(editorTrailingKeys.first { it.label == "↓" }),
        EditorFastEntry.Control(editorTrailingKeys.first { it.label == "→" }),
    )
    return (content + navigation).take(PortraitAccessorySlots)
}
