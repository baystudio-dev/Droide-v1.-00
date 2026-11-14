package com.baystudio.droide.ui

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

/** Whole production catalog execution. KeyEvent contributes compile-time SDK constants only. */
class AccessoryKeyCatalogR73Test {
    private val directions = listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
    private val languages = LanguageAccessoryKeys.supportedLanguageIds().toList() + listOf(null, "unknown", "PYTHON")

    @Test fun everyLanguagePortraitDeckHasAllFourRealArrowKeysInFinalSlots() {
        for (language in languages) {
            val entries = editorPortraitEntries(language)
            assertEquals("$language must retain the compact two by seven deck", 14, entries.size)
            assertEquals("$language must expose all directions without scrolling", directions,
                entries.takeLast(4).map { (it as EditorFastEntry.Control).key.keyCode })
            assertEquals("$language must not duplicate arrows", directions.sorted(),
                entries.filterIsInstance<EditorFastEntry.Control>().mapNotNull { it.key.keyCode }
                    .filter { it in directions }.sorted())
        }
    }

    @Test fun allProfilesKeepTabAndTheirPriorityActionsAndSymbolsAheadOfNavigation() {
        for (language in languages) {
            val entries = editorPortraitEntries(language)
            assertEquals(KeyEvent.KEYCODE_TAB, (entries.first() as EditorFastEntry.Control).key.keyCode)
            val priority = LanguageAccessoryKeys.priorityForLanguage(language).take(3)
            assertEquals(priority, entries.filterIsInstance<EditorFastEntry.Action>().take(priority.size).map { it.action })
            assertEquals(priorityCodingKeys(language), entries.filterIsInstance<EditorFastEntry.Text>()
                .take(priorityCodingKeys(language).size).map { it.text })
        }
    }

    @Test fun portraitAndLandscapeUseIdenticalNavigationCodesAndLongPressAlternatives() {
        val nav = editorPortraitEntries("kotlin").takeLast(4).map { (it as EditorFastEntry.Control).key }
        assertEquals(editorTrailingKeys.filter { it.keyCode in directions }, nav)
        assertEquals(listOf(KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MOVE_END), nav.map { it.longPressKeyCode })
        assertEquals(listOf("Left arrow", "Up arrow", "Down arrow", "Right arrow"), nav.map { it.contentDescription })
    }

    @Test fun terminalDeckPreservesAllFourteenKeysAndForcedControlC() {
        assertEquals(listOf("Esc", "Ctrl", "Alt", "Tab", "C-c", "/", "-", "←", "↓", "↑", "→", "Home", "End", "Del"),
            terminalPortraitKeys.map { it.label })
        val interrupt = terminalPortraitKeys.single { it.label == "C-c" }
        assertEquals(KeyEvent.KEYCODE_C, interrupt.keyCode)
        assertEquals(KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON, interrupt.forcedMetaState)
        assertEquals(terminalPortraitKeys + terminalOverflowKeys, terminalKeys)
    }
}
