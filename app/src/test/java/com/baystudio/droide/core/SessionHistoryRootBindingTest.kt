package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** Tiny local-only controls; no provider, terminal, process or generated history. */
class SessionHistoryRootBindingTest {
    private val entries = listOf(buildJsonObject { put("role", "user"); put("content", "a") })

    @Test fun replacedWorkspaceIsRejectedBeforeCreatingMetadataAndFreshLedgerCanOwnIt() = withContainer { container ->
        val root = container.resolve("work").apply { check(mkdir()) }
        val ledger = SessionHistoryLedger(root)
        Files.move(root.toPath(), container.resolve("original").toPath())
        check(root.mkdir())
        val sentinel = root.resolve("sentinel.txt").apply { writeText("replacement") }

        assertTrue(runCatching { ledger.writePage(entries) }.isFailure)
        assertEquals("replacement", sentinel.readText())
        assertFalse(root.resolve(".droide").exists())
        assertFalse(container.resolve("original/.droide").exists())

        val fresh = SessionHistoryLedger(root)
        val page = fresh.writePage(entries)
        assertEquals(entries, fresh.snapshot(listOf(page), emptyList()))
        assertTrue(runCatching { ledger.snapshot(listOf(page), emptyList()).single() }
            .exceptionOrNull() is SessionHistoryCorruptionException)
        assertEquals("replacement", sentinel.readText())
    }

    @Test fun missingOriginalRootAllowsMetadataReadsButNeverRebindsToLaterDirectory() = withContainer { container ->
        val root = container.resolve("later")
        val manager = SessionManager(root)
        assertNull(manager.get("s-absent"))
        assertFalse(root.exists())

        check(root.mkdir())
        val fresh = SessionManager(root)
        val session = fresh.create("Ordinary metadata remains readable")
        assertEquals(session.title, manager.get(session.id)?.title)
        val sessionFile = root.resolve(".droide/sessions/${session.id}.json")
        val before = sessionFile.readBytes()
        assertTrue(runCatching { manager.historyLedger.writePage(entries) }.isFailure)
        assertArrayEquals(before, sessionFile.readBytes())
        assertFalse(root.resolve(".droide/sessions/.history-pages").exists())

        val page = fresh.historyLedger.writePage(entries)
        assertEquals(entries, fresh.historyLedger.snapshot(listOf(page), emptyList()))
    }

    @Test fun nonDirectoryOriginalBindingCannotBeAdoptedAfterItBecomesADirectory() = withContainer { container ->
        val root = container.resolve("work").apply { writeText("not a directory") }
        val ledger = SessionHistoryLedger(root)
        check(root.delete())
        check(root.mkdir())

        assertTrue(runCatching { ledger.writePage(entries) }.isFailure)
        assertFalse(root.resolve(".droide").exists())
        val fresh = SessionHistoryLedger(root)
        assertEquals(entries, fresh.snapshot(listOf(fresh.writePage(entries)), emptyList()))
    }

    @Test fun ordinaryDirectoryMetadataChangesKeepTheOriginalWorkspaceBindingValid() = withContainer { container ->
        val root = container.resolve("work").apply { check(mkdir()) }
        val ledger = SessionHistoryLedger(root)
        val original = ProjectCopyEvidence.identity(root)
        root.resolve("sibling.txt").writeText("ordinary sibling")
        check(root.setLastModified(root.lastModified() + 2_000))
        val changed = ProjectCopyEvidence.identity(root)
        assertEquals(original.key, changed.key)
        assertNotEquals(original.modified, changed.modified)

        val page = ledger.writePage(entries)
        assertEquals(entries, ledger.snapshot(listOf(page), emptyList()))
    }

    private fun withContainer(block: suspend (File) -> Unit) = runBlocking {
        val container = Files.createTempDirectory("history-root-binding-").toFile()
        try { block(container) } finally { PathSecurity.deleteTreeNoFollow(container) }
    }
}
