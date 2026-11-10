package com.baystudio.droide.core

import java.nio.file.Files
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ThemeSelectionPersistenceTest {
    private inline fun fixture(block: (java.io.File) -> Unit) { val root = Files.createTempDirectory("r59-theme-").toFile(); try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) } }
    @Test fun absentReturnsNull() = fixture { assertNull(ThemeSelectionPersistence(it).read()) }
    @Test fun existingFormatAndUnknownFieldsRead() = fixture { root ->
        java.io.File(root, ".droide").mkdir(); java.io.File(root, ".droide/theme.json").writeText("""{"current":"Dracula","future":true}""")
        assertEquals("Dracula", ThemeSelectionPersistence(root).read())
    }
    @Test fun selectedNameSurvivesFreshStore() = fixture { root -> ThemeSelectionPersistence(root).write("Nord"); assertEquals("Nord", ThemeSelectionPersistence(root).read()) }
    @Test fun unicodeAndQuotesRoundTrip() = fixture { root -> val name = "Night \"biru\" 🌙"; ThemeSelectionPersistence(root).write(name); assertEquals(name, ThemeSelectionPersistence(root).read()) }
    @Test fun publicationFailurePreservesOldSelectionAndCleansTemporary() = fixture { root ->
        val store = ThemeSelectionPersistence(root); store.write("Old"); val old = java.io.File(root, ".droide/theme.json").readBytes()
        val failing = ThemeSelectionPersistence(root) { _, _ -> throw IOException("publication failed") }
        try { failing.write("New"); fail() } catch (e: IOException) { assertEquals("publication failed", e.message) }
        assertArrayEquals(old, java.io.File(root, ".droide/theme.json").readBytes()); assertEquals("Old", store.read())
        assertEquals(listOf("theme.json"), java.io.File(root, ".droide").list()!!.toList())
    }
    @Test fun parentFileFailureIsVisible() = fixture { root ->
        java.io.File(root, ".droide").writeText("keep")
        try { ThemeSelectionPersistence(root).write("New"); fail() } catch (_: IllegalStateException) {}
        assertEquals("keep", java.io.File(root, ".droide").readText())
    }
    @Test fun rejectsSymbolicSelectionWithoutTouchingTarget() = fixture { root ->
        java.io.File(root, ".droide").mkdir(); val target = java.io.File(root, "original"); target.writeText("keep")
        Files.createSymbolicLink(java.io.File(root, ".droide/theme.json").toPath(), target.toPath())
        try { ThemeSelectionPersistence(root).write("New"); fail() } catch (_: IllegalArgumentException) {}
        assertEquals("keep", target.readText())
    }
    @Test fun rejectsEscapingMetadataDirectory() = fixture { root -> fixture { outside ->
        Files.createSymbolicLink(java.io.File(root, ".droide").toPath(), outside.toPath())
        try { ThemeSelectionPersistence(root).write("New"); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(outside.list()!!.isEmpty())
    } }
    @Test fun malformedOrOversizedExistingFileRejectedExplicitly() = fixture { root ->
        java.io.File(root, ".droide").mkdir(); val file = java.io.File(root, ".droide/theme.json")
        listOf("", "{}", "[]", "{\"current\":3}", "x".repeat(16001)).forEach { text -> file.writeText(text); assertTrue(runCatching { ThemeSelectionPersistence(root).read() }.isFailure) }
    }
    @Test fun repeatedWritesDoNotLeaveTemporaryFiles() = fixture { root ->
        val store = ThemeSelectionPersistence(root); repeat(10) { store.write("Theme$it") }
        assertEquals("Theme9", store.read()); assertEquals(listOf("theme.json"), java.io.File(root, ".droide").list()!!.toList())
    }
    @Test fun invalidNameDoesNotReplaceExistingSelection() = fixture { root ->
        val store = ThemeSelectionPersistence(root); store.write("Old")
        listOf("", " ", "a".repeat(81)).forEach { assertTrue(runCatching { store.write(it) }.isFailure) }; assertEquals("Old", store.read())
    }
}
