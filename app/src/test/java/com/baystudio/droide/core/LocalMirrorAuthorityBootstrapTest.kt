package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
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
class LocalMirrorAuthorityBootstrapTest {
    private class Interrupted : Error("injected bootstrap interruption")
    private data class Fixture(val root: File, val source: File, val working: File) {
        val index get() = File(root, ".working-sync-baseline.tsv")
        val conflicts get() = File(root, ".working-attachment-conflicts.txt")
        fun bootstrap(hook: (String) -> Unit = {}) = LocalMirrorAuthorityBootstrap(source, working, hook)
        fun legacy(): Map<String, WorkspaceSyncManifest.Entry> {
            working.mkdir()
            File(working, "file").writeText("unpublished CLI")
            val baseline = mapOf("file" to entry("base"))
            index.writeText(WorkspaceSyncManifest.serialize(baseline))
            return baseline
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-authority-bootstrap-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            File(source, "file").writeText("canonical")
            Fixture(root, source, File(root, "working")).block()
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun freshBootstrapLinksWithoutCopyingSourceFilesOrRewritingUnchangedMetadata() = fixture {
        val store = bootstrap().prepare()
        assertEquals(MirrorMetadataAuthority.BootstrapPhase.LINKED, store.load().bootstrapPhase)
        assertTrue(store.load().baseline.isEmpty())
        assertFalse(File(working, "file").exists())
        assertEquals("canonical", File(source, "file").readText())
        val database = File(store.directory, "state.sqlite")
        val before = listOf(index, database).map { Files.readAttributes(it.toPath(), BasicFileAttributes::class.java) }
        assertEquals(store.load(), bootstrap().prepare().load())
        listOf(index, database).forEachIndexed { number, file ->
            val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            assertEquals(before[number].fileKey(), after.fileKey())
            assertEquals(before[number].lastModifiedTime(), after.lastModifiedTime())
        }
        assertFalse(File(working, ".droide-sync-manifest.tsv").exists())
    }

    @Test fun validLegacyHostMetadataIsRetainedAndGuestManifestDoesNotBecomeAuthority() = fixture {
        val expected = legacy()
        val raw = index.readBytes()
        conflicts.writeText("pending.bin\n")
        val guest = File(working, ".droide-sync-manifest.tsv").apply { writeText("foreign guest metadata") }
        val store = bootstrap().prepare()
        assertEquals(expected, store.load().baseline)
        assertEquals(setOf("pending.bin"), store.load().conflicts)
        assertArrayEquals(raw, File(store.directory, "legacy-baseline.tsv").readBytes())
        assertEquals("pending.bin\n", File(store.directory, "legacy-conflicts.txt").readText())
        assertEquals("foreign guest metadata", guest.readText())
        assertEquals("unpublished CLI", File(working, "file").readText())
        assertEquals("canonical", File(source, "file").readText())
    }

    @Test fun actualPreviousFramedBaselineImportsWithoutRecreatingDeletedCliFiles() = fixture {
        val mirror = LocalAgentWorkspaceMirror(source, working)
        mirror.refresh()
        val baseline = mirror.baseline()
        assertTrue(File(working, "file").delete())
        val oldBytes = index.readBytes()
        val store = bootstrap().prepare()
        assertEquals(baseline, store.load().baseline)
        assertFalse(File(working, "file").exists())
        assertArrayEquals(oldBytes, File(store.directory, "legacy-baseline.tsv").readBytes())
    }

    @Test fun aLostEstablishedLinkFailsClosedInsteadOfPublishingItAgain() = fixture {
        val store = bootstrap().prepare()
        File(working, "pending").writeText("unpublished")
        assertTrue(index.delete())
        assertNotNull(runCatching { bootstrap().prepare() }.exceptionOrNull())
        assertFalse(index.exists())
        assertEquals("unpublished", File(working, "pending").readText())
        assertEquals(MirrorMetadataAuthority.BootstrapPhase.LINKED, store.load().bootstrapPhase)
    }

    @Test fun aLostEstablishedDatabaseIsNotRecreatedAndACliDeletionStaysDeleted() = fixture {
        val store = bootstrap().prepare()
        store.commit(store.load().revision, mapOf("file" to entry("base")), emptySet())
        File(working, "file").writeText("base")
        assertTrue(File(working, "file").delete())
        val database = File(store.directory, "state.sqlite")
        assertTrue(database.delete())
        assertNotNull(runCatching { bootstrap().prepare() }.exceptionOrNull())
        assertFalse(database.exists())
        assertFalse(File(working, "file").exists())
    }

    @Test fun replacingALinkWithValidLegacyTextNeverDowngradesAnEstablishedAuthority() = fixture {
        val baseline = legacy()
        val store = bootstrap().prepare()
        val changed = WorkspaceSyncManifest.serialize(baseline)
        index.writeText(changed)
        assertNotNull(runCatching { bootstrap().prepare() }.exceptionOrNull())
        assertEquals(changed, index.readText())
        assertEquals(baseline, store.load().baseline)
        assertEquals("unpublished CLI", File(working, "file").readText())
    }

    @Test fun losingTheWholeAuthorityDoesNotTurnTheEstablishedLinkIntoLegacyImport() = fixture {
        val store = bootstrap().prepare()
        val retained = File(root, "preserved-authority")
        Files.move(store.directory.toPath(), retained.toPath())
        assertNotNull(runCatching { bootstrap().prepare() }.exceptionOrNull())
        assertFalse(store.directory.exists())
        assertTrue(File(retained, "state.sqlite").isFile)
    }

    @Test fun anExistingCopyWithoutHostMetadataIsNotResetOrAdoptedFromTheGuest() = fixture {
        working.mkdir()
        File(working, "pending").writeText("unpublished")
        File(working, ".droide-sync-manifest.tsv").writeText(WorkspaceSyncManifest.serialize(mapOf("file" to entry("canonical"))))
        assertNotNull(runCatching { bootstrap().prepare() }.exceptionOrNull())
        assertEquals("unpublished", File(working, "pending").readText())
        assertFalse(index.exists())
        assertFalse(bootstrap().directory.exists())
    }

    @Test fun interruptionAfterLegacyRetirementResumesOnlyMetadataAndKeepsAllCliBytes() = fixture {
        val baseline = legacy()
        val old = index.readBytes()
        conflicts.writeText("pending.bin\n")
        assertTrue(runCatching { bootstrap { if (it == "legacy-baseline-retired") throw Interrupted() }.prepare() }.exceptionOrNull() is Interrupted)
        assertFalse(index.exists())
        assertEquals("unpublished CLI", File(working, "file").readText())
        val recovered = bootstrap().prepare()
        assertEquals(baseline, recovered.load().baseline)
        assertEquals(setOf("pending.bin"), recovered.load().conflicts)
        assertArrayEquals(old, File(recovered.directory, "legacy-baseline.tsv").readBytes())
        assertEquals("unpublished CLI", File(working, "file").readText())
    }

    @Test fun interruptionAfterLinkPublicationCompletesWithoutReplacingThePublishedLink() = fixture {
        legacy()
        assertTrue(runCatching { bootstrap { if (it == "link-published") throw Interrupted() }.prepare() }.exceptionOrNull() is Interrupted)
        val before = Files.readAttributes(index.toPath(), BasicFileAttributes::class.java)
        val store = bootstrap().prepare()
        assertEquals(MirrorMetadataAuthority.BootstrapPhase.LINKED, store.load().bootstrapPhase)
        assertEquals(before.fileKey(), Files.readAttributes(index.toPath(), BasicFileAttributes::class.java).fileKey())
        assertEquals(before.lastModifiedTime(), Files.getLastModifiedTime(index.toPath()))
    }

    @Test fun aConcurrentLinkCreationIsPreservedByTheActualNoReplacePrimitive() = fixture {
        val failure = runCatching { bootstrap { if (it == "link-staged") index.writeText("foreign concurrent file") }.prepare() }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("foreign concurrent file", index.readText())
        assertEquals("canonical", File(source, "file").readText())
        assertFalse(File(working, "file").exists())
        assertNotNull(runCatching { bootstrap().prepare() }.exceptionOrNull())
        assertEquals("foreign concurrent file", index.readText())
    }

    @Test fun replacingLegacyMetadataBetweenInspectionAndRetirementPreservesBothVersions() = fixture {
        legacy()
        val original = index.readBytes()
        val moved = File(root, "external-original-metadata")
        val failure = runCatching { bootstrap { if (it == "before-legacy-baseline-retire") {
            Files.move(index.toPath(), moved.toPath())
            index.writeText("external replacement")
        } }.prepare() }.exceptionOrNull()
        assertNotNull(failure)
        assertArrayEquals(original, moved.readBytes())
        assertEquals("external replacement", index.readText())
        assertEquals("unpublished CLI", File(working, "file").readText())
    }

    @Test fun symlinkHostMetadataCannotExposeOrModifyOutsideBytes() = fixture {
        working.mkdir()
        File(working, "pending").writeText("unpublished")
        val outside = File(root, "outside").apply { writeText("private") }
        Files.createSymbolicLink(index.toPath(), outside.toPath())
        assertNotNull(runCatching { bootstrap().prepare() }.exceptionOrNull())
        assertEquals("private", outside.readText())
        assertTrue(Files.isSymbolicLink(index.toPath()))
        assertEquals("unpublished", File(working, "pending").readText())
    }

    companion object {
        private fun entry(value: String) = WorkspaceSyncManifest.Entry(MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }, 420)
    }
}
