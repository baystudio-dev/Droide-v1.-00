package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MirrorSqliteCaptureAuthorityPublicationTest {
    private fun fixture(action: (File, File, File) -> Unit) {
        val root = Files.createTempDirectory("sqlite-capture-authority-publication-").toFile()
        try {
            val source = root.resolve("source").apply { mkdir() }
            val working = root.resolve("working")
            val directory = root.resolve("authority")
            MirrorMetadataAuthority.createForNewWorkingCopy(directory, source, working)
            directory.resolve("state.sqlite-journal").writeText("unresolved evidence")
            action(source, working, directory)
        } finally { root.deleteRecursively() }
    }
    private fun authority(source: File, working: File, directory: File, checkpoint: (String) -> Unit = {}) =
        MirrorMetadataAuthority(directory, source, working, processCheckpoint = {
            check(it != "metadata-before-database-open") { "Capture must not open authoritative SQLite" }
            checkpoint(it)
        })

    @Test fun publicCaptureRejectsLateJournalMutationThenAllowsFreshRetry() = fixture { source, working, directory ->
        var hit = false
        val failure = runCatching {
            authority(source, working, directory) {
                if (it == "sqlite-review-after-receipt") {
                    hit = true; directory.resolve("state.sqlite-journal").appendText(" newer")
                }
            }.captureSqliteRollbackEvidence {}
        }.exceptionOrNull()
        assertTrue(hit); assertNotNull("Production action must not report stale capture as successful", failure)
        val retained = directory.listFiles()!!.single { it.name.startsWith("sqlite-review-") }
        val bytes = retained.listFiles()!!.associate { it.name to it.readBytes().toList() }
        val next = authority(source, working, directory).captureSqliteRollbackEvidence {}
        assertNotEquals(retained, next.evidenceDirectory)
        assertEquals("unresolved evidence newer", next.evidenceDirectory.resolve("state.sqlite-journal").readText())
        assertEquals(bytes, retained.listFiles()!!.associate { it.name to it.readBytes().toList() })
        assertNotNull(runCatching { MirrorMetadataAuthority(directory, source, working).load() }.exceptionOrNull())
    }
    @Test fun publicCaptureRejectsLateWalAndPreservesAllEvidence() = fixture { source, working, directory ->
        var hit = false
        assertNotNull(runCatching {
            authority(source, working, directory) {
                if (it == "sqlite-review-after-receipt") {
                    hit = true; directory.resolve("state.sqlite-wal").writeText("late WAL evidence")
                }
            }.captureSqliteRollbackEvidence {}
        }.exceptionOrNull())
        assertTrue(hit)
        assertEquals("late WAL evidence", directory.resolve("state.sqlite-wal").readText())
        val retained = directory.listFiles()!!.single { it.name.startsWith("sqlite-review-") }
        assertTrue(retained.resolve("receipt").isFile)
        assertEquals("unresolved evidence", retained.resolve("state.sqlite-journal").readText())
    }
}
