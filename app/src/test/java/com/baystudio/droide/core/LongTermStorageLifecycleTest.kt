package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** Repeated lifecycle and storage checks, not a simulation of elapsed years on Android. */
class LongTermStorageLifecycleTest {
    private fun entry(index: Int): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", "$index:" + "x".repeat(1_024))
    }

    private fun <T> workspace(block: suspend (File) -> T): T = runBlocking {
        val root = Files.createTempDirectory("droide-longevity-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun repeatedCheckpointsAndManagerRecreationRetainExactHistoryWithoutDuplicatePages() = workspace { root ->
        var manager = SessionManager(root)
        val session = manager.create("Repeated checkpoint")
        var history = AgentConversationHistory(manager.historyLedger)
        val expected = (0 until 200).map(::entry)
        repeat(expected.size) { index ->
            history.append(expected[index])
            manager.save(session.copy(forkHistory = history.completeExactOrNull()!!,
                forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION))
            if ((index + 1) % 20 == 0) {
                manager = SessionManager(root)
                val restored = manager.get(session.id)!!
                assertEquals(expected.take(index + 1), restored.forkHistory)
                history = AgentConversationHistory(manager.historyLedger)
                history.load(restored)
            }
        }
        val restored = manager.get(session.id)!!.forkHistory as SessionHistoryLedger.Snapshot
        val pages = File(root, ".droide/sessions/.history-pages").listFiles()!!.filter { it.extension == "json" }
        assertEquals(expected, restored)
        assertEquals(restored.pages.map { it.sha256 + ".json" }.toSet(), pages.map { it.name }.toSet())
        assertEquals(restored.pages.sumOf { it.bytes }, pages.sumOf { it.length() })
        assertTrue(restored.cachedPageCount <= 2)
        assertTrue(File(root, ".droide/sessions/${session.id}.json").length() < 64_000)
        println("LONGEVITY_STORAGE checkpoint_cycles=200 manager_recreations=10 pages=${pages.size} bytes=${pages.sumOf { it.length() }}")
    }

    @Test fun deletingParentPreservesSharedForkAndReportsRetainedPagesAfterLastDeletion() = workspace { root ->
        val manager = SessionManager(root)
        val parent = manager.create("Retained fork")
        val history = AgentConversationHistory(manager.historyLedger)
        val expected = (0 until 64).map(::entry)
        expected.forEach { item ->
            history.append(item)
            manager.save(parent.copy(messages = listOf(AgentMessage("user", "Keep exact history")),
                apiHistory = listOf(item), forkHistory = history.completeExactOrNull()!!,
                forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION))
        }
        val child = manager.fork(parent.id)!!
        manager.delete(parent.id)
        assertNull(manager.get(parent.id))
        assertEquals(expected, SessionManager(root).get(child.id)!!.forkHistory)
        manager.delete(child.id)
        assertNull(manager.get(child.id))
        val retained = File(root, ".droide/sessions/.history-pages").listFiles().orEmpty().filter { it.extension == "json" }
        // Observation only: a future reference-aware collector may reclaim these pages.
        println("LONGEVITY_STORAGE remaining_sessions=${manager.count()} retained_pages=${retained.size} retained_page_bytes=${retained.sumOf { it.length() }}")
    }

    @Test fun listingManySessionsKeepsOlderSavedDataReadableAcrossRecreation() = workspace { root ->
        val manager = SessionManager(root)
        val first = manager.create("First saved session")
        repeat(511) { manager.create("Session $it") }
        val reopened = SessionManager(root)
        assertEquals("First saved session", reopened.get(first.id)!!.title)
        assertEquals(512, reopened.listSummaries().size)
        assertTrue(reopened.listSummaries().any { it.id == first.id })
        assertEquals(512, reopened.count())
        val sessions = File(root, ".droide/sessions").listFiles()!!.filter { it.extension == "json" }
        assertEquals(512, sessions.size)
        assertTrue(File(root, ".droide/sessions").listFiles()!!.none { ".tmp-" in it.name })
        println("LONGEVITY_STORAGE saved_sessions=${sessions.size} listed_sessions=${reopened.count()} session_json_bytes=${sessions.sumOf { it.length() }}")
    }

    @Test fun repeatedCacheLeaseCancellationReleasesArtifactsForPruning() = workspace { root ->
        val cache = File(root, "cache").apply { mkdirs() }
        val artifact = File(cache, "artifact.zip")
        repeat(200) { cycle ->
            artifact.writeText("verified-$cycle")
            try {
                TrustedArtifactCache.withLease(artifact) {
                    TrustedArtifactCache.withLease(artifact) {
                        TrustedArtifactCache.prune(cache, 0, emptySet())
                        assertEquals("verified-$cycle", artifact.readText())
                        throw CancellationException("Audit cancellation")
                    }
                }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            TrustedArtifactCache.prune(cache, 0, emptySet())
            assertFalse("Lease must be released on cycle $cycle", artifact.exists())
        }
        assertEquals(0, cache.listFiles()!!.size)
        println("LONGEVITY_STORAGE cache_cancellation_cycles=200 remaining_artifacts=0")
    }

    @Test fun restartingEditHistoryPreservesRecoveryInsteadOfTreatingItAsDisposableCache() = workspace { root ->
        val file = File(root, "main.txt").apply { writeText("original") }
        repeat(64) { cycle ->
            val files = FileRepository(root)
            val history = EditHistory(files)
            val before = files.readTextForEdit(file.name)
            history.snapshot(file.name, before)
            files.writeText(file.name, "edit-$cycle")
            history.commitLast(mapOf(file.name to "edit-$cycle"))
        }
        val backups = File(root, ".droide/backups").listFiles()!!.filter { it.extension == "bak" }
        assertTrue(backups.any { it.readText() == "original" })
        assertEquals("edit-63", file.readText())
        println("LONGEVITY_STORAGE edit_history_instances=64 retained_backups=${backups.size} retained_backup_bytes=${backups.sumOf { it.length() }}")
    }
}
