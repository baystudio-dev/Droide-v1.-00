package com.baystudio.droide.core

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicInteger
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
class LocalMirrorRetainedIntegrationTest {
    private class Fixture(val root: File) {
        val source = File(root, "source").apply { mkdir() }
        val copy = File(root, "working")
        val index = File(root, ".working-sync-baseline.tsv")
        val metadata = File(root, ".working-sync-authority")
        val store get() = MirrorMetadataAuthority(metadata, source, copy)
        suspend fun mirror(cache: WorkspaceFileFingerprintCache = WorkspaceFileFingerprintCache()) =
            LocalAgentWorkspaceMirror.prepareRetained(source, copy, cache)
        fun host(path: String, text: String) = File(source, path).apply { parentFile!!.mkdirs(); writeText(text) }
        fun cli(path: String, text: String) = File(copy, path).apply { parentFile!!.mkdirs(); writeText(text) }
        fun operations(): List<MirrorMetadataAuthority.Intent> {
            val result = mutableListOf<MirrorMetadataAuthority.Intent>()
            store.visitOperations { result += it }
            return result
        }
        fun retained(record: MirrorMetadataAuthority.Intent) = File(metadata, "retained/${record.retainedName}")
        fun stable(record: MirrorMetadataAuthority.Intent) = File(metadata, "retained/${record.stableName}")
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-retained-integration-").toFile()
        try { Fixture(root).block() } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun freshNestedProjectIsAvailableThroughTheMirrorApi() = fixture {
        host("src/nested/main.kt", "first")
        File(source, "empty/deep").mkdirs()
        val mirror = mirror()
        assertTrue(mirror.isPrepared)
        assertEquals("first", File(copy, "src/nested/main.kt").readText())
        assertTrue(File(copy, "empty/deep").isDirectory)
        assertEquals(setOf("src/nested/main.kt"), mirror.inventory().keys)
        assertEquals(mirror.baseline(), mirror.inventoryEntries())
        assertEquals(setOf("src", "src/nested", "empty", "empty/deep"), store.load().directoryHistory)
        assertTrue(operations().all { it.phase == MirrorMetadataAuthority.Phase.APPLIED })
    }

    @Test fun canonicalReplacementRetainsThePriorInodeAndIndependentCopy() = fixture {
        host("main.kt", "old")
        val mirror = mirror()
        host("main.kt", "new")
        mirror.refresh()
        assertEquals("new", File(copy, "main.kt").readText())
        val record = operations().single { it.before.type == MirrorMetadataAuthority.NodeType.FILE }
        assertEquals("old", retained(record).readText())
        assertEquals("old", stable(record).readText())
        assertNotEquals(Files.readAttributes(retained(record).toPath(), BasicFileAttributes::class.java).fileKey(),
            Files.readAttributes(stable(record).toPath(), BasicFileAttributes::class.java).fileKey())
        assertEquals(mirror.baseline(), mirror.inventoryEntries())
    }

    @Test fun canonicalDeletionRetainsRecoverableBytes() = fixture {
        host("gone", "old")
        val mirror = mirror()
        assertTrue(File(source, "gone").delete())
        mirror.refresh()
        assertFalse(File(copy, "gone").exists())
        assertTrue(mirror.baseline().isEmpty())
        val record = operations().single { it.kind == "delete" }
        assertEquals("old", retained(record).readText())
        assertEquals("old", stable(record).readText())
    }

    @Test fun removedDirectoryIsCapturedWholeWithoutChildDeleteIntents() = fixture {
        host("group/first", "first"); host("group/second", "second")
        File(source, "group/empty").mkdir()
        val mirror = mirror()
        PathSecurity.deleteTreeNoFollow(File(source, "group"))
        mirror.refresh()
        assertFalse(File(copy, "group").exists())
        val record = operations().single { it.kind == "delete" }
        assertEquals("group", record.path)
        assertEquals("first", File(retained(record), "first").readText())
        assertEquals("second", File(stable(record), "second").readText())
        assertTrue(File(stable(record), "empty").isDirectory)
        assertTrue(store.load().directoryHistory.isEmpty())
    }

    @Test fun directoryReplacementRetainsTheCompleteTree() = fixture {
        host("item/child", "child")
        val mirror = mirror()
        PathSecurity.deleteTreeNoFollow(File(source, "item"))
        host("item", "replacement")
        mirror.refresh()
        assertEquals("replacement", File(copy, "item").readText())
        val record = operations().single { it.before.type == MirrorMetadataAuthority.NodeType.DIRECTORY }
        assertEquals("child", File(retained(record), "child").readText())
        assertEquals("child", File(stable(record), "child").readText())
        assertEquals(setOf("item"), mirror.baseline().keys)
        assertTrue(store.load().directoryHistory.isEmpty())
    }

    @Test fun fileReplacementByDirectoryCopiesItsChildren() = fixture {
        host("item", "old")
        val mirror = mirror()
        assertTrue(File(source, "item").delete())
        host("item/child", "new")
        mirror.refresh()
        assertEquals("new", File(copy, "item/child").readText())
        val record = operations().single { it.before.type == MirrorMetadataAuthority.NodeType.FILE }
        assertEquals("old", stable(record).readText())
        assertEquals(setOf("item/child"), mirror.baseline().keys)
        assertEquals(setOf("item"), store.load().directoryHistory)
    }

    @Test fun unpublishedCliEditsStayUsableAndAreNotRepublishedToTheHost() = fixture {
        host("file", "base")
        val mirror = mirror()
        cli("file", "CLI")
        repeat(2) { mirror.refresh() }
        assertEquals("CLI", File(copy, "file").readText())
        assertEquals("base", File(source, "file").readText())
        assertNotEquals(mirror.baseline(), mirror.inventoryEntries())
        assertTrue(mirror.metadataNotice.orEmpty().contains("CLI edit"))
        WorkspaceFileMutationLocks.withRoot(copy) { guard ->
            MirrorWorkingCopyMutations(source, copy, store).requireUbuntuLocalConsistency(guard)
        }
    }

    @Test fun simultaneousHostAndCliEditsBecomePersistentConflict() = fixture {
        host("file", "base")
        val mirror = mirror()
        cli("file", "CLI"); host("file", "host")
        mirror.refresh()
        assertEquals(setOf("file"), store.load().conflicts)
        mirror().refresh()
        assertEquals("CLI", File(copy, "file").readText())
        assertEquals("host", File(source, "file").readText())
        assertEquals(1, operations().size)
    }

    @Test fun cliDeletionIsNotRecreatedAndCanConvergeWhenHostAlsoDeletes() = fixture {
        host("file", "base")
        val mirror = mirror()
        assertTrue(File(copy, "file").delete())
        mirror.refresh()
        assertFalse(File(copy, "file").exists())
        assertEquals(setOf("file"), mirror.baseline().keys)
        assertTrue(File(source, "file").delete())
        mirror.refresh()
        assertTrue(mirror.baseline().isEmpty())
        host("file", "later")
        mirror.refresh()
        assertEquals("later", File(copy, "file").readText())
    }

    @Test fun cliDirectoryDeletionDoesNotResurrectItsDescendants() = fixture {
        host("dir/child", "base")
        val mirror = mirror()
        PathSecurity.deleteTreeNoFollow(File(copy, "dir"))
        host("dir/addition", "host")
        repeat(2) { mirror.refresh() }
        assertFalse(File(copy, "dir").exists())
        assertEquals(setOf("dir/child"), mirror.baseline().keys)
        assertTrue(mirror.metadataNotice.orEmpty().contains("CLI deletion"))
    }

    @Test fun cliRenameAndAdditionRemainVisibleAfterRestart() = fixture {
        host("before", "base")
        mirror()
        Files.move(File(copy, "before").toPath(), File(copy, "after").toPath())
        cli("new", "CLI")
        val restarted = mirror()
        assertEquals(setOf("after", "new"), restarted.inventoryEntries().keys)
        assertFalse(File(copy, "before").exists())
        assertFalse(File(source, "after").exists())
        assertFalse(File(source, "new").exists())
    }

    @Test fun convergedFilesAdvanceBaselineBeforeTheNextHostEdit() = fixture {
        host("file", "base")
        val mirror = mirror()
        cli("file", "same"); host("file", "same")
        mirror.refresh()
        assertEquals(mirror.inventoryEntries(), mirror.baseline())
        host("file", "next")
        mirror.refresh()
        assertEquals("next", File(copy, "file").readText())
    }

    @Test fun bilateralFileToDirectoryTransitionKeepsDeletionHistory() = fixture {
        host("item", "base")
        val mirror = mirror()
        for (root in listOf(source, copy)) {
            assertTrue(File(root, "item").delete()); assertTrue(File(root, "item").mkdir())
        }
        mirror.refresh()
        assertTrue(mirror.baseline().isEmpty())
        assertEquals(setOf("item"), store.load().directoryHistory)
        assertTrue(File(copy, "item").delete())
        assertTrue(File(source, "item").delete()); host("item", "new")
        mirror.refresh()
        assertFalse(File(copy, "item").exists())
    }

    @Test fun unknownEmptyChildBlocksDirectoryReplacement() = fixture {
        host("item/known", "base")
        val mirror = mirror()
        File(copy, "item/unknown").mkdir()
        PathSecurity.deleteTreeNoFollow(File(source, "item")); host("item", "new")
        mirror.refresh()
        assertEquals("base", File(copy, "item/known").readText())
        assertTrue(File(copy, "item/unknown").isDirectory)
        assertEquals(setOf("item"), store.load().conflicts)
    }

    @Test fun conflictOnWholeDirectoryDeletionDoesNotDeleteCleanSiblings() = fixture {
        host("item/clean", "base"); host("item/edited", "base")
        val mirror = mirror()
        cli("item/edited", "CLI")
        PathSecurity.deleteTreeNoFollow(File(source, "item"))
        mirror.refresh()
        assertEquals("base", File(copy, "item/clean").readText())
        assertEquals("CLI", File(copy, "item/edited").readText())
        assertEquals(listOf("item"), operations().filter { it.kind == "delete" }.map { it.path })
        assertEquals(setOf("item"), store.load().conflicts)
    }

    @Test fun conflictOnParentConversionDoesNotWriteItsChildren() = fixture {
        host("item", "base")
        val mirror = mirror()
        cli("item", "CLI")
        assertTrue(File(source, "item").delete()); host("item/child", "host")
        mirror.refresh()
        assertEquals("CLI", File(copy, "item").readText())
        assertFalse(File(copy, "item/child").exists())
        assertEquals(setOf("item"), store.load().conflicts)
    }

    @Test fun missingAttachmentConflictRemainsProtected() = fixture {
        val mirror = mirror()
        mirror.preserveAttachmentConflict("attachment")
        host("attachment", "host")
        mirror.refresh()
        assertFalse(File(copy, "attachment").exists())
        assertEquals(setOf("attachment"), store.load().conflicts)
        assertTrue(mirror.baseline().isEmpty())
    }

    @Test fun oldManifestIsPreservedDuringImportAndLaterRefreshes() = fixture {
        host("file", "base")
        val legacy = LocalAgentWorkspaceMirror(source, copy)
        legacy.refresh()
        val manifest = File(copy, ".droide-sync-manifest.tsv").apply { writeText("user notes") }
        val stamp = Files.getLastModifiedTime(manifest.toPath())
        val mirror = mirror()
        host("file", "updated"); mirror.refresh()
        assertEquals("updated", File(copy, "file").readText())
        assertEquals("user notes", manifest.readText())
        assertEquals(stamp, Files.getLastModifiedTime(manifest.toPath()))
        assertTrue(mirror.metadataNotice.orEmpty().contains("manifest preserved"))
    }

    @Test fun oldConstructorDoesNotEnrollRetainedPublication() = fixture {
        host("file", "base")
        LocalAgentWorkspaceMirror(source, copy).refresh()
        assertFalse(metadata.exists())
        assertTrue(index.readText().startsWith("DROIDE-LOCAL-BASELINE-"))
    }

    @Test fun legacyReaderRefusesAnEnrolledAuthorityWithoutChangingFiles() = fixture {
        host("file", "base"); mirror()
        cli("file", "CLI")
        val before = index.readBytes()
        assertNotNull(runCatching { LocalAgentWorkspaceMirror(source, copy).refresh() }.exceptionOrNull())
        assertArrayEquals(before, index.readBytes())
        assertEquals("CLI", File(copy, "file").readText())
    }

    @Test fun missingEstablishedLinkFailsClosedForReadAndRefresh() = fixture {
        host("file", "base")
        val mirror = mirror()
        cli("file", "CLI"); assertTrue(index.delete())
        assertNotNull(runCatching { mirror.refresh() }.exceptionOrNull())
        assertNotNull(runCatching { mirror.baseline() }.exceptionOrNull())
        assertNotNull(runCatching { mirror.inventoryEntries() }.exceptionOrNull())
        assertFalse(index.exists())
        assertEquals("CLI", File(copy, "file").readText())
    }

    @Test fun replacedAuthorityLinkCannotResetMetadata() = fixture {
        host("file", "base")
        val mirror = mirror()
        index.writeText("foreign metadata")
        assertNotNull(runCatching { mirror.refresh() }.exceptionOrNull())
        assertNotNull(runCatching { mirror() }.exceptionOrNull())
        assertEquals("foreign metadata", index.readText())
        assertEquals("base", File(copy, "file").readText())
    }

    @Test fun corruptBaselineFailsClosed() = fixture {
        host("file", "base")
        val mirror = mirror()
        SQLiteDatabase.openDatabase(File(metadata, "state.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE baseline SET sha256=?", arrayOf("0".repeat(64)))
        }
        assertNotNull(runCatching { mirror.refresh() }.exceptionOrNull())
        assertNotNull(runCatching { mirror.baseline() }.exceptionOrNull())
        assertEquals("base", File(copy, "file").readText())
    }

    @Test fun pendingIntentBlocksRestartedMirrorAndDoesNotResetWorkingCopy() = fixture {
        host("file", "base")
        val mirror = mirror()
        val entry = mirror.baseline().getValue("file")
        store.prepare("file", "delete", MirrorMetadataAuthority.Node.file(entry), MirrorMetadataAuthority.Node.absent)
        cli("file", "CLI")
        assertNotNull(runCatching { mirror() }.exceptionOrNull())
        assertNotNull(runCatching { mirror.inventory() }.exceptionOrNull())
        assertNotNull(runCatching { mirror.preserveAttachmentConflict("another") }.exceptionOrNull())
        assertEquals("CLI", File(copy, "file").readText())
        assertEquals(1, operations().count { it.phase == MirrorMetadataAuthority.Phase.PREPARED })
    }

    @Test fun symlinkWorkingEntryCannotChangeAnExternalFile() = fixture {
        host("file", "base")
        val mirror = mirror()
        val external = File(root, "external").apply { writeText("private") }
        assertTrue(File(copy, "file").delete())
        Files.createSymbolicLink(File(copy, "file").toPath(), external.toPath())
        host("file", "host")
        mirror.refresh()
        assertEquals("private", external.readText())
        assertTrue(Files.isSymbolicLink(File(copy, "file").toPath()))
    }

    @Test fun symlinkInsideRemovedDirectoryIsPreservedWithoutChildDeletes() = fixture {
        host("item/known", "base")
        val mirror = mirror()
        val external = File(root, "external").apply { writeText("private") }
        Files.createSymbolicLink(File(copy, "item/link").toPath(), external.toPath())
        PathSecurity.deleteTreeNoFollow(File(source, "item"))
        mirror.refresh()
        assertEquals("base", File(copy, "item/known").readText())
        assertTrue(Files.isSymbolicLink(File(copy, "item/link").toPath()))
        assertEquals("private", external.readText())
    }

    @Test fun deletedWorkingRootIsNeverRecreatedFromMetadata() = fixture {
        host("file", "base")
        val mirror = mirror()
        val saved = File(root, "saved")
        Files.move(copy.toPath(), saved.toPath())
        assertNotNull(runCatching { mirror.refresh() }.exceptionOrNull())
        assertNotNull(runCatching { mirror() }.exceptionOrNull())
        assertFalse(copy.exists())
        assertEquals("base", File(saved, "file").readText())
    }

    @Test fun warmRefreshDoesNotRewriteMetadataCreateIntentsOrRehashPayloads() = fixture {
        host("nested/file", "base")
        val reads = AtomicInteger()
        val mirror = mirror(WorkspaceFileFingerprintCache(onHashRead = { _, _ -> reads.incrementAndGet() }))
        kotlinx.coroutines.delay(3_100)
        mirror.refresh()
        val paths = listOf(index, File(metadata, "state.sqlite"))
        val bytes = paths.map(File::readBytes)
        val stamps = paths.map { Files.getLastModifiedTime(it.toPath()) }
        val initial = operations()
        reads.set(0)
        repeat(3) { mirror.refresh() }
        assertEquals(0, reads.get())
        assertEquals(initial, operations())
        paths.forEachIndexed { i, path ->
            assertArrayEquals(bytes[i], path.readBytes())
            assertEquals(stamps[i], Files.getLastModifiedTime(path.toPath()))
        }
    }
}
