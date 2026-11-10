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
class MirrorSqliteRollbackAuthorityTest {
    private fun fixture(action: (File, File, File, MirrorMetadataAuthority) -> Unit) {
        val root = Files.createTempDirectory("sqlite-authority-review-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            val working = File(root, "working")
            val directory = File(root, "authority")
            val authority = MirrorMetadataAuthority.createForNewWorkingCopy(directory, source, working)
            action(source, working, directory, authority)
        } finally { root.deleteRecursively() }
    }

    @Test fun explicitCaptureNeverAdmitsOriginalJournalOrChangesDatabase() = fixture { _, _, directory, authority ->
        val database = File(directory, "state.sqlite")
        val before = database.readBytes()
        val journal = File(directory, "state.sqlite-journal").apply { writeText("unresolved evidence") }
        assertNotNull(runCatching { authority.load() }.exceptionOrNull())
        val review = authority.captureSqliteRollbackEvidence {}
        assertArrayEquals(before, database.readBytes())
        assertEquals("unresolved evidence", journal.readText())
        assertArrayEquals(before, File(review.evidenceDirectory, "state.sqlite").readBytes())
        assertNotNull(runCatching { authority.load() }.exceptionOrNull())
        assertNotNull(runCatching { authority.requireNoPendingProcessOwners() }.exceptionOrNull())
    }

    @Test fun wrongWorkspaceBindingCannotCreateEvidence() = fixture { source, working, directory, _ ->
        File(directory, "state.sqlite-journal").writeText("evidence")
        val other = File(source.parentFile, "other-source").apply { mkdir() }
        val mismatched = MirrorMetadataAuthority(directory, other, working)
        assertNotNull(runCatching { mismatched.captureSqliteRollbackEvidence {} }.exceptionOrNull())
        assertFalse(directory.list()!!.any { it.startsWith("sqlite-review-") })
    }

    @Test fun noJournalAfterCommitDoesNotCreateReview() = fixture { _, _, directory, authority ->
        val before = authority.load()
        assertNotNull(runCatching { authority.captureSqliteRollbackEvidence {} }.exceptionOrNull())
        assertEquals(before, authority.load())
        assertFalse(directory.list()!!.any { it.startsWith("sqlite-review-") })
    }
}
