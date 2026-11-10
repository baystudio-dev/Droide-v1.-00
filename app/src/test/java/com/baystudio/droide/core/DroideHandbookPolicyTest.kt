package com.baystudio.droide.core

import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class DroideHandbookPolicyTest {
    private fun assetRoot(): File = File(System.getProperty("droide.source", "."))
        .resolve("app/src/main/assets/agent-handbook").let { if (it.isDirectory) it else File("src/main/assets/agent-handbook") }
    private fun document() = assetRoot().resolve("about droide.md").readText()

    @Test fun fixedSelectionRejectsPathsUrlsCaseChangesAndUnknownSections() {
        for (value in listOf("", "all", "Identity", "../identity", "/identity", "https://example.com/", "identity\nterminal")) {
            assertThrows(IllegalArgumentException::class.java) { DroideHandbookPolicy.validateSelection(value, null) }
        }
        assertEquals(19, DroideHandbookPolicy.sections.size)
    }

    @Test fun screenshotIdsAreFixedAndMustMatchTheSection() {
        assertEquals(3, DroideHandbookPolicy.screenshots.size)
        assertNotNull(DroideHandbookPolicy.validateSelection("editor", "editor"))
        assertNotNull(DroideHandbookPolicy.validateSelection("terminal", "terminal_opencode"))
        assertNotNull(DroideHandbookPolicy.validateSelection("screenshots", "terminal_packages"))
        for (id in listOf("../editor", "editor.jpg", "https://example.com/image.jpg", "")) {
            assertThrows(IllegalArgumentException::class.java) { DroideHandbookPolicy.validateSelection("editor", id) }
        }
        assertThrows(IllegalArgumentException::class.java) { DroideHandbookPolicy.validateSelection("identity", "editor") }
        assertThrows(IllegalArgumentException::class.java) { DroideHandbookPolicy.validateSelection("editor", "terminal_packages") }
    }

    @Test fun readsOnlyTheRequestedHeadingAndNeverReturnsOtherSections() {
        val doc = "frontmatter\n## 1. Identity\nBayStudio\n## 2. Navigation\nControls\n"
        assertEquals("## 1. Identity\nBayStudio", DroideHandbookPolicy.readSection(StringReader(doc), "identity"))
        assertEquals("## 2. Navigation\nControls", DroideHandbookPolicy.readSection(StringReader(doc), "navigation"))
    }

    @Test fun absentOrDuplicateSectionFailsWithoutWholeDocumentFallback() {
        for (doc in listOf("not a handbook", "## 2. Navigation\nControls", "## 1. A\none\n## 1. B\ntwo")) {
            assertThrows(IllegalArgumentException::class.java) { DroideHandbookPolicy.readSection(StringReader(doc), "identity") }
        }
    }

    @Test fun oversizedCorpusAndSectionFailRatherThanSilentlyTruncating() {
        assertThrows(IllegalArgumentException::class.java) {
            DroideHandbookPolicy.readSection(StringReader("x".repeat(DroideHandbookPolicy.MAX_DOCUMENT_CHARS + 1)), "identity")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DroideHandbookPolicy.readSection(StringReader("## 1. Identity\n" + "x".repeat(DroideHandbookPolicy.MAX_SECTION_CHARS)), "identity")
        }
    }

    @Test fun allBundledSectionsArePresentBoundedAndDistinct() {
        val doc = document()
        val contents = DroideHandbookPolicy.sections.keys.map { section ->
            DroideHandbookPolicy.readSection(StringReader(doc), section).also {
                assertTrue(it.length in 1..DroideHandbookPolicy.MAX_SECTION_CHARS)
                assertEquals(1, Regex("(?m)^## ").findAll(it).count())
            }
        }
        assertEquals(contents.size, contents.toSet().size)
        assertTrue(doc.contains(DroideHandbookPolicy.SOURCE_REFERENCE))
        assertTrue(doc.contains("screenshot_build: unverified"))
        assertTrue(doc.contains("Local external agents can use the IDE bridge"))
        assertTrue(doc.contains("droide ide call droide_help"))
    }

    @Test fun indexAndNumberedHeadingsCoverEveryAdvertisedSectionExactlyOnce() {
        val doc = document()
        val index = DroideHandbookPolicy.readSection(StringReader(doc), "index")
        val indexed = Regex("(?m)^- `([a-z_]+)`:").findAll(index).map { it.groupValues[1] }.toList()
        assertEquals(DroideHandbookPolicy.sections.keys, indexed.toSet())
        assertEquals(indexed.toSet().size, indexed.size)
        val headings = Regex("(?m)^## ([0-9]+)\\. ").findAll(doc).map { it.groupValues[1].toInt() }.toList()
        assertEquals(DroideHandbookPolicy.sections.values.toList(), headings)
        for (section in listOf("architecture", "diagnostics", "troubleshooting_agent", "troubleshooting_runtime", "lifecycle", "release", "integrations")) {
            val text = DroideHandbookPolicy.readSection(StringReader(doc), section)
            assertTrue(text.startsWith("## ${DroideHandbookPolicy.sections.getValue(section)}. "))
            assertEquals(1, Regex("(?m)^## ").findAll(text).count())
            assertNull(DroideHandbookPolicy.validateSelection(section, null))
            assertThrows(IllegalArgumentException::class.java) { DroideHandbookPolicy.validateSelection(section, "editor") }
        }
    }

    @Test fun verifiedIdentityAndCurrentControlsAreProductFocused() {
        val doc = document()
        val identity = DroideHandbookPolicy.readSection(StringReader(doc), "identity")
        assertTrue(identity.contains("BayStudio")); assertTrue(identity.contains("BayZov"))
        val terminal = DroideHandbookPolicy.readSection(StringReader(doc), "terminal")
        assertTrue(terminal.contains("Find / Export retained history")); assertTrue(terminal.contains("Export snapshot…"))
        assertTrue(terminal.contains("64 KiB")); assertTrue(terminal.contains("Acquire manual WakeLock"))
        assertTrue(identity.contains("Bayu Rohmatullah"))
        assertTrue(identity.contains("```text"))
        assertTrue(identity.contains("Native Android workbench"))
        assertTrue(identity.contains("approved sync"))
        assertTrue(doc.contains("Answer in the user's language"))
        assertTrue(doc.contains("language: English"))
        assertTrue(doc.contains("LGPL-3.0-only"))
        assertTrue(doc.contains("Third-party components retain their own licenses"))
        assertFalse(Regex("\\b(?:R|F)[0-9]{2,}\\b").containsMatchIn(doc))
        for (name in listOf("vscode", "visual studio code", "antigravity", "claude code", "opencode")) {
            assertFalse(doc.lowercase().contains(name))
        }
    }

    @Test fun screenshotBytesMatchApprovedOriginalsAndNoPrivateExtrasAreBundled() {
        val files = assetRoot().walkTopDown().filter { it.isFile }.map { it.relativeTo(assetRoot()).invariantSeparatorsPath }.toSet()
        assertEquals(setOf("about droide.md", "assets/editor-kotlin.jpg", "assets/terminal-opencode.jpg", "assets/terminal-packages.jpg"), files)
        for (entry in DroideHandbookPolicy.screenshots.values) {
            val bytes = assetRoot().parentFile.resolve(entry.asset).readBytes()
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(entry.sha256, sha)
            assertTrue(bytes.size in 1..(4 * 1024 * 1024))
            assertTrue(entry.caption.contains("Capture date and app build unknown"))
        }
    }

    @Test fun textGuideDoesNotEmbedImagesOrForceMediaLoading() {
        val images = Regex("!\\[[^]]*]\\(([^)]+)\\)").findAll(document()).map { it.groupValues[1] }.toSet()
        assertTrue(images.isEmpty())
    }

    @Test fun groupTextBudgetIsBoundedAndFailedReservationDoesNotConsumeIt() {
        val budget = DroideHandbookBudget()
        budget.reserve(4_000)
        assertThrows(IllegalArgumentException::class.java) { budget.reserve(4_001) }
        budget.reserve(4_000)
        assertThrows(IllegalArgumentException::class.java) { budget.reserve(1) }
        assertThrows(IllegalArgumentException::class.java) { DroideHandbookBudget().reserve(DroideHandbookPolicy.MAX_RESULT_CHARS + 1) }
        assertThrows(IllegalArgumentException::class.java) { DroideHandbookBudget().reserve(0) }
    }

    @Test fun concurrentReservationsCannotExceedGroupBudget() {
        val budget = DroideHandbookBudget()
        val start = CountDownLatch(1)
        val accepted = AtomicInteger()
        val threads = (1..12).map { Thread {
            start.await()
            try { budget.reserve(1_000); accepted.incrementAndGet() } catch (_: IllegalArgumentException) { }
        }.apply { start() } }
        start.countDown(); threads.forEach { it.join() }
        assertEquals(8, accepted.get())
    }

    @Test fun bootstrapIsTinyAndHasNoManualOrNetworkRequest() {
        assertTrue(DroideHandbookPolicy.BOOTSTRAP.length < 500)
        assertTrue(DroideHandbookPolicy.BOOTSTRAP.contains("user's language"))
        assertTrue(DroideHandbookPolicy.BOOTSTRAP.contains("ordinary coding"))
        assertTrue(DroideHandbookPolicy.BOOTSTRAP.contains("Never auto-fetch"))
        assertFalse(DroideHandbookPolicy.BOOTSTRAP.contains("BayStudio"))
        assertFalse(DroideHandbookPolicy.BOOTSTRAP.contains("https://"))
    }
}
