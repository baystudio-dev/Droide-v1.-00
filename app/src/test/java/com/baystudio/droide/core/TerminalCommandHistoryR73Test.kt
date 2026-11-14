package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class TerminalCommandHistoryR73Test {
    private val draft = TerminalCommandDraft("echo 🙂 draft", 7, 2)
    private fun empty() = TerminalCommandDraft.atEnd("")

    @Test fun emptyHistoryLeavesTheCurrentDraftAlone() {
        val history = TerminalCommandHistory()
        assertNull(history.previous(draft))
        assertNull(history.next())
    }

    @Test fun upBrowsesNewestToOldestAndClampsWithoutDuplicatingCommands() {
        val history = TerminalCommandHistory()
        history.submitted("one"); history.submitted("two"); history.submitted("three")
        assertEquals("three", history.previous(draft)?.text)
        assertEquals("two", history.previous(empty())?.text)
        repeat(20) { assertEquals("one", history.previous(empty())?.text) }
    }

    @Test fun downMovesForwardThenRestoresExactDraftAndReversedSelection() {
        val history = TerminalCommandHistory()
        history.submitted("one"); history.submitted("two")
        history.previous(draft); history.previous(empty())
        assertEquals(TerminalCommandDraft.atEnd("two"), history.next())
        assertEquals(draft, history.next())
        assertNull(history.next())
    }

    @Test fun editingARecalledCommandStartsANewDraftWithoutMutatingHistory() {
        val history = TerminalCommandHistory()
        history.submitted("one")
        history.previous(draft)
        history.endBrowsing()
        val edited = TerminalCommandDraft("one edited", 2, 4)
        assertEquals("one", history.previous(edited)?.text)
        assertEquals(edited, history.next())
    }

    @Test fun submissionEndsBrowsingAndConsecutiveDuplicatesAreNotAdded() {
        val history = TerminalCommandHistory()
        history.submitted("one"); history.submitted("two")
        history.previous(draft)
        history.submitted("two")
        assertNull(history.next())
        assertEquals("two", history.previous(empty())?.text)
        assertEquals("one", history.previous(empty())?.text)
    }

    @Test fun blankOrOversizedEntriesAreNeverStoredOrTruncated() {
        val history = TerminalCommandHistory(maxEntries = 3, maxTextUnits = 5)
        history.submitted("  "); history.submitted("abcdef")
        assertNull(history.previous(draft))
        history.submitted("12345")
        assertEquals("12345", history.previous(draft)?.text)
    }

    @Test fun entryCountAndUtf16BudgetsEvictOldestWholeCommands() {
        val history = TerminalCommandHistory(maxEntries = 2, maxTextUnits = 6)
        history.submitted("aa"); history.submitted("bb"); history.submitted("cc")
        assertEquals("cc", history.previous(draft)?.text)
        assertEquals("bb", history.previous(empty())?.text)
        history.submitted("🙂🙂")
        assertEquals("🙂🙂", history.previous(draft)?.text)
        assertEquals("cc", history.previous(empty())?.text)
        assertEquals("cc", history.previous(empty())?.text)
    }

    @Test fun rapidUpDownNeverOverwritesTheDraftOrLeaksAcrossSessions() {
        val first = TerminalCommandHistory(); val other = TerminalCommandHistory()
        first.submitted("first-session"); other.submitted("other-session")
        repeat(100) {
            assertEquals("first-session", first.previous(draft)?.text)
            assertEquals(draft, first.next())
        }
        assertEquals("other-session", other.previous(empty())?.text)
        assertEquals(empty(), other.next())
    }

    @Test fun paneRetirementEndsBrowsingButKeepsLiveSessionEntries() {
        val history = TerminalCommandHistory()
        history.submitted("one"); history.previous(draft)
        history.endBrowsing()
        assertNull(history.next())
        assertEquals("one", history.previous(empty())?.text)
        assertEquals(empty(), history.next())
    }

    @Test fun rejectedOrThrowingSubmissionNeverEntersHistory() {
        val history = TerminalCommandHistory()
        assertFalse(history.submit("rejected") { false })
        assertNull(history.previous(draft))
        assertThrows(IllegalStateException::class.java) { history.submit("failed") { error("failed admission") } }
        assertNull(history.previous(draft))
        assertTrue(history.submit("accepted") { it == "accepted" })
        assertEquals("accepted", history.previous(draft)?.text)
    }

    @Test fun rejectedSubmissionPreservesAnExistingBrowseAndItsExactUnicodeDraft() {
        val history = TerminalCommandHistory()
        history.submitted("echo 日本語 🙂"); history.submitted("second")
        assertEquals("second", history.previous(draft)?.text)
        assertFalse(history.submit("rejected") { false })
        assertThrows(IllegalStateException::class.java) { history.submit("failed") { error("failed admission") } }
        assertEquals("echo 日本語 🙂", history.previous(empty())?.text)
        assertEquals("second", history.next()?.text)
        assertEquals(draft, history.next())
    }

    @Test fun closedHistoryCannotRetainOrReviveCommands() {
        val history = TerminalCommandHistory()
        history.submitted("one"); history.previous(draft); history.close(); history.submitted("late")
        assertNull(history.previous(draft))
        assertNull(history.next())
    }
}
