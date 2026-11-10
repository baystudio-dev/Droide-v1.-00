package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LocalMirrorTypeHistoryTest {
    private data class Fixture(val root: File, val source: File, val copy: File) {
        val index get() = File(root, ".mirror-sync-baseline.tsv")
        fun mirror() = LocalAgentWorkspaceMirror(source, copy)
        suspend fun agreeDirectory() {
            File(source, "item").writeText("old file")
            mirror().refresh()
            for (tree in listOf(source, copy)) {
                assertTrue(File(tree, "item").delete())
                assertTrue(File(tree, "item").mkdir())
            }
            mirror().refresh()
        }
        fun rewriteBody(body: String) {
            val header = index.readText().substringBefore('\n').split('\t')
            val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
                .joinToString("") { "%02x".format(it) }
            index.writeText("${header[0]}\t${header[1]}\t$hash\n$body")
        }
        suspend fun expectRejected() {
            val before = index.readBytes()
            val failure = runCatching { mirror().refresh() }.exceptionOrNull()
            assertNotNull("Malformed history must fail closed", failure)
            assertTrue(failure is IllegalArgumentException || failure is IllegalStateException)
            assertArrayEquals(before, index.readBytes())
            assertTrue(File(source, "item").isDirectory)
            assertTrue(File(copy, "item").isDirectory)
        }
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-type-history-").toFile()
        try { Fixture(root, File(root, "source").apply { mkdir() }, File(root, "mirror")).block() }
        finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun cancelledRefreshRetainsDirectoryHistoryBeforeUnrelatedFileCopy() = fixture {
        File(source, "item").writeText("old file")
        File(source, "zz.txt").writeText("old sibling")
        var armed = false
        var observations = 0
        var intercepted = false
        val mirror = LocalAgentWorkspaceMirror(source, copy, WorkspaceFileFingerprintCache(maxEntries = 0,
            onHashRead = { path, _ ->
                if (armed && path == File(copy, "zz.txt").toPath() && ++observations == 2) {
                    assertTrue(index.readText().contains("\nD\titem\n"))
                    assertTrue(File(copy, "item").delete())
                    assertTrue(File(source, "item").delete())
                    File(source, "item").writeText("host changed after observation")
                    intercepted = true
                    throw CancellationException("injected interruption after history publication")
                }
            }))
        mirror.refresh()
        for (tree in listOf(source, copy)) {
            assertTrue(File(tree, "item").delete()); assertTrue(File(tree, "item").mkdir())
        }
        File(source, "zz.txt").writeText("new sibling")
        armed = true
        assertTrue(runCatching { mirror.refresh() }.exceptionOrNull() is CancellationException)
        assertTrue(intercepted)
        mirror().refresh()
        assertFalse(File(copy, "item").exists())
        assertEquals("host changed after observation", File(source, "item").readText())
        assertEquals("new sibling", File(copy, "zz.txt").readText())
    }

    @Test fun directoryHistoryKeepsADeletedDirectoryAbsentWhenHostStillHasDirectory() = fixture {
        agreeDirectory()
        assertTrue(File(copy, "item").delete())
        File(source, "item/new.txt").writeText("host addition")
        repeat(2) { mirror().refresh() }
        assertFalse("Do not recreate a pending CLI directory deletion", File(copy, "item").exists())
        assertEquals("host addition", File(source, "item/new.txt").readText())
        assertTrue(index.readText().contains("\nD\titem\n"))
    }

    @Test fun parentDeletedDuringSiblingPublicationIsNotRecreatedForTheNextFile() = fixture {
        File(source, "item").mkdir()
        File(source, "item/child").writeText("old child")
        File(source, "zz.txt").writeText("old sibling")
        var armed = false
        var observations = 0
        var deleted = false
        val mirror = LocalAgentWorkspaceMirror(source, copy, WorkspaceFileFingerprintCache(maxEntries = 0,
            onHashRead = { path, _ ->
                if (armed && path == File(copy, "zz.txt").toPath() && ++observations == 2) {
                    assertTrue(File(copy, "item/child").delete())
                    assertTrue(File(copy, "item").delete())
                    deleted = true
                }
            }))
        mirror.refresh()
        File(source, "item/child").writeText("new child")
        File(source, "zz.txt").writeText("new sibling")
        armed = true
        val failure = runCatching { mirror.refresh() }.exceptionOrNull()
        assertTrue(deleted)
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("parent directory disappeared"))
        assertFalse(File(copy, "item").exists())
        mirror().refresh()
        assertFalse("Retained child history must preserve the deleted parent after restart", File(copy, "item").exists())
        assertEquals("new child", File(source, "item/child").readText())
        assertEquals("new sibling", File(copy, "zz.txt").readText())
    }

    @Test fun bothSidesAbsentCanConvergeBeforeALaterNewHostFile() = fixture {
        agreeDirectory()
        assertTrue(File(source, "item").delete()); assertTrue(File(copy, "item").delete())
        mirror().refresh()
        assertFalse(index.readText().contains("D\titem\n"))
        File(source, "item").writeText("subsequent host addition")
        mirror().refresh()
        assertEquals("subsequent host addition", File(copy, "item").readText())
    }

    @Test fun sourceOnlyEmptyDirectoryTransitionRetainsHistoryForLaterCliDeletion() = fixture {
        File(source, "item").writeText("old file")
        val mirror = mirror()
        mirror.refresh()
        assertTrue(File(source, "item").delete()); assertTrue(File(source, "item").mkdir())
        mirror.refresh()
        assertTrue(File(copy, "item").isDirectory)
        assertTrue(File(copy, "item").delete())
        assertTrue(File(source, "item").delete())
        File(source, "item").writeText("later host file")
        mirror().refresh()
        assertFalse("A host-only file-to-empty-directory transition must retain the pending CLI deletion", File(copy, "item").exists())
        assertEquals("later host file", File(source, "item").readText())
    }

    @Test fun identicalRecreatedFilesCanBecomeTheNewFileBaseline() = fixture {
        agreeDirectory()
        for (tree in listOf(source, copy)) {
            assertTrue(File(tree, "item").delete())
            File(tree, "item").writeText("agreed new file")
        }
        mirror().refresh()
        assertEquals(setOf("item"), mirror().baseline().keys)
        assertFalse(index.readText().contains("D\titem\n"))
        File(source, "item").writeText("next host edit")
        mirror().refresh()
        assertEquals("next host edit", File(copy, "item").readText())
    }

    @Test fun typedHistoryDoesNotRewriteWarmMetadataOrGuestManifest() = fixture {
        agreeDirectory()
        val guest = File(copy, ".droide-sync-manifest.tsv").apply { writeText("CLI-owned notes") }
        val bytes = index.readBytes()
        val attributes = Files.readAttributes(index.toPath(), BasicFileAttributes::class.java)
        val guestAttributes = Files.readAttributes(guest.toPath(), BasicFileAttributes::class.java)
        repeat(3) {
            val mirror = mirror()
            mirror.refresh()
            assertTrue(mirror.metadataNotice.orEmpty().contains("preserved"))
            assertArrayEquals(bytes, index.readBytes())
            assertEquals(attributes.fileKey(), Files.readAttributes(index.toPath(), BasicFileAttributes::class.java).fileKey())
            assertEquals(attributes.lastModifiedTime(), Files.getLastModifiedTime(index.toPath()))
            assertEquals("CLI-owned notes", guest.readText())
            assertEquals(guestAttributes.fileKey(), Files.readAttributes(guest.toPath(), BasicFileAttributes::class.java).fileKey())
        }
    }

    @Test fun missingTypedMetadataDoesNotRecreateCliDeletion() = fixture {
        agreeDirectory()
        assertTrue(File(copy, "item").delete())
        assertTrue(File(source, "item").delete())
        File(source, "item").writeText("host file")
        assertTrue(index.delete())
        assertNotNull(runCatching { mirror().refresh() }.exceptionOrNull())
        assertFalse(index.exists())
        assertFalse(File(copy, "item").exists())
        assertEquals("host file", File(source, "item").readText())
    }

    @Test fun typedRowsCannotBeTruncatedEvenToAValidLookingFileOnlyBody() = fixture {
        agreeDirectory()
        index.writeText(index.readText().substringBefore('\n') + "\n")
        expectRejected()
    }

    @Test fun duplicateDirectoryRowsFailClosedWithAValidChecksum() = fixture {
        agreeDirectory(); rewriteBody("D\titem\nD\titem\n"); expectRejected()
    }

    @Test fun directoryCountIsBoundedBeforeAllRowsAreCollected() = fixture {
        agreeDirectory()
        rewriteBody(buildString { repeat(40_001) { append("D\tdir-$it\n") } })
        expectRejected()
    }

    @Test fun fileCountRemainsBoundedInsideTypedHistory() = fixture {
        agreeDirectory()
        val hash = "a".repeat(64)
        rewriteBody(buildString {
            append("D\titem\n")
            repeat(20_001) { append("$hash\t420\tfile-$it\n") }
        })
        expectRejected()
    }

    @Test fun fileAndDirectoryAtSamePathFailClosedWithAValidChecksum() = fixture {
        agreeDirectory(); rewriteBody("${"a".repeat(64)}\t420\titem\nD\titem\n"); expectRejected()
    }

    @Test fun traversalDirectoryHistoryCannotBeAdmitted() = fixture {
        agreeDirectory(); rewriteBody("D\t../outside\n"); expectRejected()
    }

    @Test fun aFileCannotBeAnAncestorOfDirectoryHistory() = fixture {
        agreeDirectory(); rewriteBody("${"a".repeat(64)}\t420\tparent\nD\tparent/child\n"); expectRejected()
    }

    @Test fun unknownTypedRowCannotBeIgnoredAsAnEmptyBaseline() = fixture {
        agreeDirectory(); rewriteBody("UNKNOWN\titem\n"); expectRejected()
    }

    @Test fun unknownFutureHeaderFailsClosed() = fixture {
        agreeDirectory()
        index.writeText(index.readText().replace("DROIDE-LOCAL-BASELINE-2", "DROIDE-LOCAL-BASELINE-3"))
        expectRejected()
    }

    @Test fun metadataBindingCannotBeReusedByAnotherWorkingCopy() = fixture {
        agreeDirectory()
        val other = File(root, "other").apply { mkdir() }
        File(other, "item").mkdir()
        val otherIndex = File(root, ".other-sync-baseline.tsv").apply { writeBytes(index.readBytes()) }
        val before = otherIndex.readBytes()
        assertNotNull(runCatching { LocalAgentWorkspaceMirror(source, other).refresh() }.exceptionOrNull())
        assertArrayEquals(before, otherIndex.readBytes())
        assertTrue(File(other, "item").isDirectory)
    }

    @Test fun authorityImporterRejectsUnknownHistoryWithoutCreatingAnAuthority() = fixture {
        agreeDirectory()
        index.writeText(index.readText().replace("DROIDE-LOCAL-BASELINE-2", "DROIDE-LOCAL-BASELINE-3"))
        val before = index.readBytes()
        val failure = runCatching { LocalMirrorAuthorityBootstrap(source, copy).prepare() }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("newer host baseline"))
        assertArrayEquals(before, index.readBytes())
        assertFalse(File(root, ".mirror-sync-authority").exists())
        assertTrue(File(source, "item").isDirectory)
        assertTrue(File(copy, "item").isDirectory)
    }

    @Test fun directorySymlinkReplacementDoesNotTouchOutsideFiles() = fixture {
        agreeDirectory()
        val outside = File(root, "outside").apply { mkdir() }
        val preserved = File(outside, "file").apply { writeText("outside") }
        assertTrue(File(source, "item").delete())
        Files.createSymbolicLink(File(source, "item").toPath(), outside.toPath())
        mirror().refresh()
        assertEquals("outside", preserved.readText())
        assertTrue(File(copy, "item").isDirectory)
        assertFalse(File(copy, "item/file").exists())
        assertTrue(index.readText().contains("D\titem\n"))
    }
}
