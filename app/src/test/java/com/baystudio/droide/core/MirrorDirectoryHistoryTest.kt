package com.baystudio.droide.core

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MirrorDirectoryHistoryTest {
    @Test fun typedImportRetainsHostMetadataAndDoesNotTouchGuestFiles() = fixture {
        typed("D\titem\n${hash("base")}\t420\tfile\n")
        val original = index.readBytes()
        val guest = File(working, ".droide-sync-manifest.tsv").apply { writeText("guest-owned") }
        val store = bootstrap().prepare()
        assertEquals(mapOf("file" to entry("base")), store.load().baseline)
        assertArrayEquals(original, File(store.directory, "legacy-baseline.tsv").readBytes())
        assertEquals("guest-owned", guest.readText())
        assertEquals("CLI edit", File(working, "file").readText())
        assertDeletedDirectoryStaysDeleted()
    }

    @Test fun ordinaryConflictCommitKeepsDirectoryDeletionHistoryAcrossRestart() = fixture {
        typed()
        val store = bootstrap().prepare()
        val before = store.load()
        store.commit(before.revision, before.baseline, setOf("unrelated"))
        assertDeletedDirectoryStaysDeleted()
        assertEquals(setOf("unrelated"), bootstrap().prepare().load().conflicts)
    }

    @Test fun ordinaryCommitCannotTurnKnownDirectoryIntoFileWithoutTypedCompletion() = fixture {
        typed()
        val store = bootstrap().prepare()
        val before = store.load()
        assertNotNull(runCatching { store.commit(before.revision, mapOf("item" to entry("host")), emptySet()) }.exceptionOrNull())
        assertEquals(before, store.load())
        assertDeletedDirectoryStaysDeleted()
    }

    @Test fun directoryOnlyBaselineCannotBeLostDuringBootstrapRecovery() = fixture {
        typed()
        assertNotNull(runCatching { bootstrap { if (it == "legacy-baseline-retired") error("interrupted") }.prepare() }.exceptionOrNull())
        val retained = File(bootstrap().directory, "legacy-baseline.tsv")
        val saved = File(root, "saved-baseline")
        Files.move(retained.toPath(), saved.toPath())
        assertNotNull(runCatching { bootstrap().prepare() }.exceptionOrNull())
        assertFalse(index.exists())
        assertTrue(saved.isFile)
        assertEquals("CLI edit", File(working, "file").readText())
    }

    @Test fun typedImportResumesAfterEveryBootstrapCheckpoint() {
        for (boundary in listOf("authority-prepared", "legacy-baseline-retired", "link-staged", "link-published", "linked")) {
            fixture {
                typed()
                assertNotNull(runCatching { bootstrap { if (it == boundary) error("interrupted at $boundary") }.prepare() }.exceptionOrNull())
                assertDeletedDirectoryStaysDeleted()
                assertDeletedDirectoryStaysDeleted()
            }
        }
    }

    @Test fun changingOnlyDirectoryHistoryAfterImportIsDetected() = fixture {
        typed()
        val failure = runCatching {
            bootstrap { if (it == "authority-prepared") typed("D\tother\n") }.prepare()
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(index.readText().contains("D\tother\n"))
        assertFalse(File(bootstrap().directory, "legacy-baseline.tsv").exists())
        assertEquals("CLI edit", File(working, "file").readText())
    }

    @Test fun emptyTypedHistoryCanBeImportedWithoutInventingAFile() = fixture {
        typed("")
        val state = bootstrap().prepare().load()
        assertTrue(state.baseline.isEmpty())
        assertTrue(state.conflicts.isEmpty())
        assertEquals(state, bootstrap().prepare().load())
    }

    @Test fun malformedAndConflictingTypedRowsNeverCreateAuthority() {
        for (body in listOf("D\titem\nD\titem\n", "D\t../outside\n", "D\titem\tother\n",
            "D\titem\n${hash("base")}\t420\titem\n", "D\tfile/child\n${hash("base")}\t420\tfile\n")) {
            fixture {
                typed(body)
                val before = index.readBytes()
                assertNotNull(runCatching { bootstrap().prepare() }.exceptionOrNull())
                assertFalse(bootstrap().directory.exists())
                assertArrayEquals(before, index.readBytes())
            }
        }
    }

    @Test fun corruptTypedChecksumAndUnknownFormatFailClosed() {
        for (version in listOf("DROIDE-LOCAL-BASELINE-2", "DROIDE-LOCAL-BASELINE-3")) {
            fixture {
                typed()
                index.writeText(index.readText().replace("DROIDE-LOCAL-BASELINE-2", version) + "D\tlate\n")
                val before = index.readBytes()
                assertNotNull(runCatching { bootstrap().prepare() }.exceptionOrNull())
                assertArrayEquals(before, index.readBytes())
                assertFalse(bootstrap().directory.exists())
            }
        }
    }

    @Test fun missingDirectoryRowsFailClosedInsteadOfResurrectingCliDeletion() = fixture {
        typed()
        val store = bootstrap().prepare()
        database(store).use { it.execSQL("DELETE FROM baseline_directories") }
        assertNotNull(runCatching { bootstrap().prepare() }.exceptionOrNull())
        assertFalse(File(working, "item").exists())
        assertEquals("CLI edit", File(working, "file").readText())
    }

    @Test fun changingSchemaVersionCannotHideExistingTypedHistory() = fixture {
        typed()
        val store = bootstrap().prepare()
        database(store).use { it.version = 1 }
        assertNotNull(runCatching { bootstrap().prepare() }.exceptionOrNull())
        assertFalse(File(working, "item").exists())
    }

    @Test fun typedNoOpDoesNotRewriteDatabaseOrHostLink() = fixture {
        typed()
        val store = bootstrap().prepare()
        val paths = listOf(index, File(store.directory, "state.sqlite"))
        val bytes = paths.map(File::readBytes)
        val stamps = paths.map { Files.readAttributes(it.toPath(), BasicFileAttributes::class.java) }
        repeat(3) {
            val current = bootstrap().prepare().load()
            assertEquals(current, store.commit(current.revision, current.baseline, current.conflicts))
        }
        paths.forEachIndexed { i, path ->
            assertArrayEquals(bytes[i], path.readBytes())
            val after = Files.readAttributes(path.toPath(), BasicFileAttributes::class.java)
            assertEquals(stamps[i].fileKey(), after.fileKey())
            assertEquals(stamps[i].lastModifiedTime(), after.lastModifiedTime())
        }
    }

    @Test fun publishingDirectoryRecordsAncestryBeforeCliRemovesIt() = fixture {
        typed("")
        File(working, "file").delete()
        val store = bootstrap().prepare()
        val emptyDirectory = File(source, "item").apply { mkdir() }
        val directory = MirrorWorkingCopyMutations.directoryNode(emptyDirectory, EmptyCoroutineContext)
        val results = apply(store, MirrorMetadataAuthority.Proposal("item", "create", MirrorMetadataAuthority.Node.absent, directory))
        assertTrue(results.single().applied)
        assertTrue(File(working, "item").delete())
        assertDeletedDirectoryStaysDeleted()
    }

    @Test fun knownEmptyDirectoryCanBeReplacedWithoutLosingItsRetainedVersion() = fixture {
        typed()
        File(working, "item").mkdir()
        File(source, "item").writeText("host")
        val store = bootstrap().prepare()
        val directory = MirrorWorkingCopyMutations.directoryNode(File(working, "item"), EmptyCoroutineContext)
        val result = apply(store, MirrorMetadataAuthority.Proposal("item", "directory", directory, MirrorMetadataAuthority.Node.file(entry("host")))).single()
        assertTrue(result.applied)
        assertTrue(result.retained!!.isDirectory)
        assertTrue(result.stable!!.isDirectory)
        assertEquals("host", File(working, "item").readText())
        assertEquals(entry("host"), bootstrap().prepare().load().baseline["item"])
    }

    @Test fun unknownEmptyChildPreventsWholeDirectoryReplacement() = fixture {
        typed()
        val directory = File(working, "item").apply { mkdir() }
        File(directory, "unpublished-child").mkdir()
        File(source, "item").writeText("host")
        val store = bootstrap().prepare()
        val node = MirrorWorkingCopyMutations.directoryNode(directory, EmptyCoroutineContext)
        val result = apply(store, MirrorMetadataAuthority.Proposal("item", "directory", node, MirrorMetadataAuthority.Node.file(entry("host")))).single()
        assertFalse(result.applied)
        assertTrue(File(directory, "unpublished-child").isDirectory)
        assertTrue(store.load().conflicts.contains("item"))
    }

    @Test fun deletedAncestorWithOnlyDirectoryHistoryIsNotRecreated() = fixture {
        typed("D\tparent/child\n")
        val store = bootstrap().prepare()
        File(source, "parent").writeText("host")
        val failure = runCatching { apply(store, MirrorMetadataAuthority.Proposal("parent", "create",
            MirrorMetadataAuthority.Node.absent, MirrorMetadataAuthority.Node.file(entry("host")))) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("CLI deletion"))
        assertFalse(File(working, "parent").exists())
        assertTrue(store.operations().isEmpty())
    }

    private data class Fixture(val root: File, val source: File, val working: File) {
        val index get() = File(root, ".working-sync-baseline.tsv")
        fun bootstrap(hook: (String) -> Unit = {}) = LocalMirrorAuthorityBootstrap(source, working, hook)
        fun typed(body: String = "D\titem\n") {
            val binding = hash("${source.canonicalPath}\n${key(source)}\n${key(working)}")
            index.writeText("DROIDE-LOCAL-BASELINE-2\t$binding\t${hash(body)}\n$body")
        }
        fun database(store: MirrorMetadataAuthority): SQLiteDatabase = SQLiteDatabase.openDatabase(
            File(store.directory, "state.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        suspend fun apply(store: MirrorMetadataAuthority, proposal: MirrorMetadataAuthority.Proposal) =
            WorkspaceFileMutationLocks.withRoot(working) { guard ->
                MirrorWorkingCopyMutations(source, working, store).apply(guard, store.load().revision, listOf(proposal))
            }
        suspend fun assertDeletedDirectoryStaysDeleted() {
            val store = bootstrap().prepare()
            File(source, "item").apply { if (!exists()) writeText("host") }
            val failure = runCatching { apply(store, MirrorMetadataAuthority.Proposal("item", "create",
                MirrorMetadataAuthority.Node.absent, MirrorMetadataAuthority.Node.file(entry("host")))) }.exceptionOrNull()
            assertTrue(failure.toString(), failure is IllegalStateException)
            assertTrue(failure!!.message.orEmpty().contains("CLI deletion"))
            assertFalse(File(working, "item").exists())
            assertTrue(store.operations().none { it.phase == MirrorMetadataAuthority.Phase.PREPARED })
        }
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-directory-history-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            val working = File(root, "working").apply { mkdir() }
            File(working, "file").writeText("CLI edit")
            Fixture(root, source, working).block()
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    companion object {
        private fun key(file: File) = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey().toString()
        private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun entry(text: String) = WorkspaceSyncManifest.Entry(hash(text), 420)
    }
}
