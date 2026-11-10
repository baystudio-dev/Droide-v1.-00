package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Review-only fixtures: never touch non-fixture sources or saved user data. */
class SessionCatalogIndependentReviewTest {
    private fun <T> workspace(block: suspend (File) -> T): T = runBlocking {
        val root = Files.createTempDirectory("catalog-independent-review-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun delayedRepairDoesNotOverwriteConcurrentSavedMetadata() = workspace { root ->
        val writer = SessionManager(root)
        val session = writer.create("Old title")
        val reader = SessionManager(root)
        val decoded = CountDownLatch(1)
        val release = CountDownLatch(1)
        val saveDone = CountDownLatch(1)
        val first = AtomicBoolean(true)
        root.resolve(".droide/sessions/${session.id}.meta").delete()
        reader.textEditIoProbe = { event, _ ->
            if (event == TextEditIo.SESSION_READ && first.compareAndSet(true, false)) {
                decoded.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        kotlinx.coroutines.coroutineScope {
            val scanned = async(Dispatchers.IO) { reader.listSummaries() }
            check(decoded.await(10, TimeUnit.SECONDS))
            val saved = async(Dispatchers.IO) {
                writer.save(session.copy(title = "New title"))
                saveDone.countDown()
            }
            // An unlocked legacy repair allows this save to complete before repair publishes. A correct
            // locked repair delays it until release. Both paths finish deterministically.
            val completedBeforeRepair = saveDone.await(1, TimeUnit.SECONDS)
            release.countDown()
            scanned.await()
            saved.await()
            println("REVIEW_CACHE_RACE save_completed_before_repair=$completedBeforeRepair")
            assertEquals("New title", writer.get(session.id)!!.title)
            assertEquals("New title", SessionManager(root).getSummary(session.id)!!.title)
        }
    }

    @Test fun primaryIdentityMismatchCannotDuplicateRowsOrOverwriteAnotherSidecar() = workspace { root ->
        val manager = SessionManager(root)
        val first = manager.create("Actual first")
        val second = manager.create("Actual second")
        val folder = root.resolve(".droide/sessions")
        val secondSidecar = folder.resolve("${second.id}.meta").readBytes()
        folder.resolve("${first.id}.meta").delete()
        val bogus = folder.resolve("${first.id}.json")
        val secondBody = folder.resolve("${second.id}.json").readText()
        bogus.writeText(secondBody.replace("Actual second", "Forged second"))
        val rows = manager.listSummaries()
        assertEquals("Duplicate LazyColumn keys break browser reachability", rows.size, rows.map { it.id }.toSet().size)
        assertEquals(listOf(second.id), rows.map { it.id })
        assertEquals("Actual second", rows.single().title)
        assertArrayEquals("Invalid body identity must not mutate another sidecar", secondSidecar, folder.resolve("${second.id}.meta").readBytes())
    }

    @Test fun replacedWorkspaceAncestorCannotReadOrRepairAnExternalConversation() = workspace { root ->
        val manager = SessionManager(root)
        manager.create("Original workspace")
        val outside = Files.createTempDirectory("catalog-independent-outside-").toFile()
        try {
            val external = SessionManager(outside).create("External private conversation")
            val externalPrimary = outside.resolve(".droide/sessions/${external.id}.json")
            val externalMeta = outside.resolve(".droide/sessions/${external.id}.meta")
            val originalBytes = externalPrimary.readBytes()
            check(externalMeta.delete())
            Files.move(root.resolve(".droide").toPath(), root.resolve("original-dot-droide").toPath())
            Files.createSymbolicLink(root.resolve(".droide").toPath(), outside.resolve(".droide").toPath())

            assertNull(runCatching { manager.getSummary(external.id) }.getOrNull())
            assertTrue(runCatching { manager.listSummaries() }.getOrNull().orEmpty().isEmpty())
            assertTrue(runCatching { manager.list() }.getOrNull().orEmpty().isEmpty())
            assertEquals(0, runCatching { manager.count() }.getOrDefault(0))
            assertFalse("Read-only discovery must not repair outside its bound workspace", externalMeta.exists())
            assertArrayEquals(originalBytes, externalPrimary.readBytes())
        } finally { PathSecurity.deleteTreeNoFollow(outside) }
    }

    @Test fun titleFromOversizedMetadataFallbackRemainsBoundedWithoutSourceMutation() = workspace { root ->
        val manager = SessionManager(root)
        val session = manager.create("Small")
        val hugeTitle = "T".repeat(500_000)
        manager.save(session.copy(title = hugeTitle))
        val source = root.resolve(".droide/sessions/${session.id}.json")
        val before = source.readBytes()
        val row = manager.listSummaries().single()
        assertTrue("Each retained catalog row must remain lightweight even when the sidecar cannot encode", row.title.length <= 1_024)
        assertArrayEquals(before, source.readBytes())
        assertEquals(hugeTitle, manager.get(session.id)!!.title)
    }
}
