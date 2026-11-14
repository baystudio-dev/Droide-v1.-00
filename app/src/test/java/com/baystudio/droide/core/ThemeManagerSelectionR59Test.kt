package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Actual ThemeManager integration: requires project Compose/serialization dependencies; not a host-store substitute. */
class ThemeManagerSelectionR59Test {
    private inline fun fixture(block: (java.io.File) -> Unit) { val root = Files.createTempDirectory("theme-manager-r59-").toFile(); try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) } }
    @Test fun failureDoesNotPublishUnsavedCurrentTheme() = fixture { root ->
        val manager = ThemeManager(root); val before = manager.current(); val next = manager.list().first { it.name != before.name }
        java.io.File(root, ".droide/theme.json").mkdirs() // A directory cannot be replaced with the theme file.
        assertTrue(runCatching { manager.select(next.name) }.isFailure)
        assertEquals(before.name, manager.current().name)
    }
    @Test fun successfulSelectionRestoresThroughRealManager() = fixture { root ->
        val manager = ThemeManager(root); val next = manager.list().first { it.name != manager.current().name }
        manager.select(next.name); assertEquals(next.name, manager.current().name); assertEquals(next.name, ThemeManager(root).current().name)
    }
    @Test fun unavailableThemeIsExplicitAndDoesNotPersist() = fixture { root ->
        val manager = ThemeManager(root); val before = manager.current().name
        assertTrue(runCatching { manager.select("missing-r59-theme") }.isFailure)
        assertEquals(before, manager.current().name); assertFalse(java.io.File(root, ".droide/theme.json").exists())
    }
}
