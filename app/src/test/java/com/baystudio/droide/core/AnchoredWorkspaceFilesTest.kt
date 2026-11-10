package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AnchoredWorkspaceFilesTest {
    @Test fun childDirectoryCapabilitiesKeepIndependentDescriptorsAndRejectSubstitutedRoots() = fixture { root, work, _ ->
        val nested = File(work, "nested").apply { mkdir() }
        val parent = AnchoredWorkspaceFiles(work)
        val child = parent.openDirectory("nested")
        assertEquals(parent.directoryKey("nested"), child.directoryKey())
        parent.close()
        child.use {
            it.createNew("kept").use { output -> output.write("original".toByteArray()); output.force(420) }
            Files.move(nested.toPath(), File(root, "retained-child").toPath())
            nested.mkdir()
            assertNotNull(runCatching { it.createNew("wrong").close() }.exceptionOrNull())
        }
        assertEquals("original", File(root, "retained-child/kept").readText())
        assertTrue(nested.listFiles()!!.isEmpty())
    }

    @Test fun openingASymlinkDirectoryCannotGrantAnotherRootOrLeakBorrowedDescriptors() = fixture { root, work, _ ->
        val outside = File(root, "outside").apply { mkdir() }
        File(outside, "secret").writeText("private")
        Files.createSymbolicLink(File(work, "alias").toPath(), outside.toPath())
        fun descriptors() = Files.newDirectoryStream(File("/proc/self/fd").toPath()).use { it.count() }
        AnchoredWorkspaceFiles(work).use { files ->
            val before = descriptors()
            repeat(100) { assertNotNull(runCatching { files.openDirectory("alias").close() }.exceptionOrNull()) }
            assertTrue(descriptors() <= before + 2)
            files.openDirectory().use { assertEquals(files.directoryKey(), it.directoryKey()) }
        }
        assertEquals("private", File(outside, "secret").readText())
    }

    @Test fun anOpenedOutputForcesItsActualParentAfterThatParentWasRenamed() = fixture { root, work, _ ->
        val parent = File(work, "parent").apply { mkdir() }
        val outside = File(root, "outside").apply { mkdir() }
        AnchoredWorkspaceFiles(work).use { files -> files.createNew("parent/file").use { output ->
            Files.move(parent.toPath(), File(root, "retired-parent").toPath())
            Files.createSymbolicLink(parent.toPath(), outside.toPath())
            output.write("preserved".toByteArray())
            output.force(420)
        } }
        assertEquals("preserved", File(root, "retired-parent/file").readText())
        assertFalse(File(outside, "file").exists())
    }

    @Test fun confinedFileCreationIsExclusiveAndForcesTheOpenedInodeAndMode() = fixture { _, work, _ ->
        AnchoredWorkspaceFiles(work).use { files ->
            files.createNew("file").use { output ->
                output.write("new bytes".toByteArray())
                output.force(493)
                assertTrue(output.observe().executable)
                assertEquals(9L, output.observe().attributes.size())
            }
            files.forceDirectory()
            try { files.createNew("file").close(); fail("Existing file was overwritten") }
            catch (_: FileAlreadyExistsException) { }
            assertEquals("new bytes", files.openRead("file").use { it.bufferedReader().readText() })
        }
    }

    @Test fun directoryCreationNeverReplacesAnEntryOrFollowsAParentLink() = fixture { root, work, _ ->
        val outside = File(root, "outside").apply { mkdir() }
        File(work, "file").writeText("CLI")
        Files.createSymbolicLink(File(work, "alias").toPath(), outside.toPath())
        AnchoredWorkspaceFiles(work).use { files ->
            assertTrue(files.createDirectory("new").durable)
            assertFalse(files.createDirectory("file").published)
            assertEquals(17, files.createDirectory("file").error)
            assertFalse(files.createDirectory("alias/escaped").published)
        }
        assertEquals("CLI", File(work, "file").readText())
        assertFalse(File(outside, "escaped").exists())
    }

    @Test fun descriptorReadsAreBoundedAndClosedInputsCannotReadReusedDescriptors() = fixture { _, work, _ ->
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        File(work, "file").writeBytes(bytes)
        AnchoredWorkspaceFiles(work).use { source ->
            val input = source.openRead("file")
            input.use {
                assertEquals(bytes.size.toLong(), it.observe().attributes.size())
                assertEquals(0, it.read(ByteArray(1), 1, 0))
                assertArrayEquals(bytes, it.readBytes())
                assertEquals(-1, it.read())
            }
            source.openRead("file").use {
                try { input.read(); fail("Closed input read another file descriptor") } catch (_: IllegalStateException) { }
                assertEquals(bytes[0].toInt() and 255, it.read())
            }
        }
    }

    @Test fun enumerationDoesNotFollowSymlinkDirectoriesAndClassifiesLinksWithoutRemovingThem() = fixture { root, work, _ ->
        val outside = File(root, "outside").apply { mkdir() }
        File(outside, "secret").writeText("private")
        File(work, "regular").writeText("public")
        File(work, "nested").mkdir()
        Files.createSymbolicLink(File(work, "alias").toPath(), outside.toPath())
        AnchoredWorkspaceFiles(work).use { source ->
            val names = linkedSetOf<String>()
            source.forEachChild { names += it }
            assertEquals(setOf("regular", "nested", "alias"), names)
            assertEquals(AnchoredWorkspaceFiles.Kind.FILE, source.kind("regular"))
            assertEquals(AnchoredWorkspaceFiles.Kind.DIRECTORY, source.kind("nested"))
            assertEquals(AnchoredWorkspaceFiles.Kind.SYMLINK, source.kind("alias"))
            assertEquals(AnchoredWorkspaceFiles.Kind.ABSENT, source.kind("absent"))
            try { source.forEachChild("alias") { fail("Symlink directory traversed") }; fail("Symlink accepted") }
            catch (_: FileSystemException) { }
        }
        assertTrue(Files.isSymbolicLink(File(work, "alias").toPath()))
        assertEquals("private", File(outside, "secret").readText())
    }

    @Test fun pinnedFingerprintRejectsConcurrentWritesAndDoesNotCacheFailedObservation() = fixture { _, work, _ ->
        val file = File(work, "file").apply { writeBytes(ByteArray(80_000) { 1 }) }
        var mutate = true
        val fingerprints = WorkspaceFileFingerprintCache(onHashRead = { _, _ ->
            if (mutate) { mutate = false; file.appendText("concurrent bytes") }
        })
        AnchoredWorkspaceFiles(work).use { source ->
            try {
                fingerprints.read(source, "file", file.toPath(), kotlin.coroutines.EmptyCoroutineContext)
                fail("Concurrent write accepted")
            } catch (_: IllegalStateException) { }
            val recovered = fingerprints.read(source, "file", file.toPath(), kotlin.coroutines.EmptyCoroutineContext)
            assertEquals(WorkspaceFileFingerprintCache(maxEntries = 0).read(file.toPath(), kotlin.coroutines.EmptyCoroutineContext).sha256,
                recovered.sha256)
        }
    }

    @Test fun durableNestedMoveDistinguishesPublicationFromFailureWithoutReplacingAnything() = fixture { _, work, recovery ->
        File(work, "nested").mkdir(); File(recovery, "slot").mkdir()
        File(work, "nested/file").writeText("retained")
        AnchoredWorkspaceFiles(work).use { source -> AnchoredWorkspaceFiles(recovery).use { target ->
            val receipt = source.moveNoReplaceDurable("nested/file", target, "slot/file")
            assertTrue(receipt.published)
            assertTrue(receipt.durable)
            assertEquals(0, receipt.error)
            assertEquals("retained", File(recovery, "slot/file").readText())
        } }
    }

    @Test fun durableMoveCollisionReportsNotPublishedAndKeepsBothVersions() = fixture { _, work, recovery ->
        File(work, "file").writeText("source")
        File(recovery, "file").writeText("destination")
        AnchoredWorkspaceFiles(work).use { source -> AnchoredWorkspaceFiles(recovery).use { target ->
            val receipt = source.moveNoReplaceDurable("file", target, "file")
            assertFalse(receipt.published)
            assertFalse(receipt.durable)
            assertEquals(17, receipt.error)
        } }
        assertEquals("source", File(work, "file").readText())
        assertEquals("destination", File(recovery, "file").readText())
    }

    private fun fixture(block: (File, File, File) -> Unit) {
        val root = Files.createTempDirectory("droide-anchored-").toFile()
        try { block(root, File(root, "work").apply { mkdir() }, File(root, "recovery").apply { mkdir() }) }
        finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun unicodeMoveUsesOneNoReplacePublicationAndRetainsOriginalBytes() = fixture { _, work, recovery ->
        File(work, "🧩 名字.kt").writeText("unpublished CLI bytes")
        AnchoredWorkspaceFiles(work).use { source -> AnchoredWorkspaceFiles(recovery).use { target ->
            source.moveNoReplace("🧩 名字.kt", target, "retained.kt")
            source.forceDirectory(); target.forceDirectory()
            assertFalse(File(work, "🧩 名字.kt").exists())
            assertEquals("unpublished CLI bytes", File(recovery, "retained.kt").readText())
        } }
    }

    @Test fun existingTargetIsNeverReplaced() = fixture { _, work, recovery ->
        File(work, "file").writeText("CLI version")
        File(recovery, "file").writeText("existing recovery version")
        AnchoredWorkspaceFiles(work).use { source -> AnchoredWorkspaceFiles(recovery).use { target ->
            try { source.moveNoReplace("file", target, "file"); fail("Destination was overwritten") }
            catch (_: FileAlreadyExistsException) { }
        } }
        assertEquals("CLI version", File(work, "file").readText())
        assertEquals("existing recovery version", File(recovery, "file").readText())
    }

    @Test(timeout = 15_000) fun concurrentPublishersHaveExactlyOneWinnerWithoutClobberingTheOtherSource() = fixture { _, work, recovery ->
        AnchoredWorkspaceFiles(work).use { source -> AnchoredWorkspaceFiles(recovery).use { target ->
            val executor = Executors.newFixedThreadPool(2)
            try { repeat(100) { iteration ->
                val first = "first-$iteration"; val second = "second-$iteration"; val destination = "winner-$iteration"
                File(work, first).writeText(first); File(work, second).writeText(second)
                val ready = CountDownLatch(2); val start = CountDownLatch(1)
                val contenders = listOf(first, second).map { name -> executor.submit<Boolean> {
                    ready.countDown(); assertTrue(start.await(3, TimeUnit.SECONDS))
                    try { source.moveNoReplace(name, target, destination); true }
                    catch (_: FileAlreadyExistsException) { false }
                } }
                assertTrue(ready.await(3, TimeUnit.SECONDS)); start.countDown()
                val results = contenders.map { it.get(3, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it })
                val winner = if (results[0]) first else second
                val loser = if (results[0]) second else first
                assertEquals(winner, File(recovery, destination).readText())
                assertEquals(loser, File(work, loser).readText())
                assertFalse(File(work, winner).exists())
            } } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)) }
        } }
    }

    @Test fun intermediateSourceSymlinkCannotMoveExternalFiles() = fixture { root, work, recovery ->
        val outside = File(root, "outside").apply { mkdir() }
        File(outside, "file").writeText("external")
        Files.createSymbolicLink(File(work, "alias").toPath(), outside.toPath())
        AnchoredWorkspaceFiles(work).use { source -> AnchoredWorkspaceFiles(recovery).use { target ->
            try { source.moveNoReplace("alias/file", target, "retained"); fail("Source symlink was traversed") }
            catch (_: FileSystemException) { }
        } }
        assertEquals("external", File(outside, "file").readText())
        assertFalse(File(recovery, "retained").exists())
    }

    @Test fun intermediateDestinationSymlinkCannotWriteOutsideTheRoot() = fixture { root, work, recovery ->
        val outside = File(root, "outside").apply { mkdir() }
        File(work, "file").writeText("CLI")
        Files.createSymbolicLink(File(recovery, "alias").toPath(), outside.toPath())
        AnchoredWorkspaceFiles(work).use { source -> AnchoredWorkspaceFiles(recovery).use { target ->
            try { source.moveNoReplace("file", target, "alias/file"); fail("Destination symlink was traversed") }
            catch (_: FileSystemException) { }
        } }
        assertEquals("CLI", File(work, "file").readText())
        assertFalse(File(outside, "file").exists())
    }

    @Test fun readsRejectLeafAndIntermediateSymlinks() = fixture { root, work, _ ->
        val outside = File(root, "outside").apply { mkdir() }
        File(outside, "secret").writeText("external")
        Files.createSymbolicLink(File(work, "alias").toPath(), outside.toPath())
        Files.createSymbolicLink(File(work, "leaf").toPath(), File(outside, "secret").toPath())
        AnchoredWorkspaceFiles(work).use { source ->
            for (relative in listOf("alias/secret", "leaf")) {
                try { source.read(relative) { _, _ -> fail("Symlink was read") }; fail("Symlink was accepted") }
                catch (_: FileSystemException) { }
            }
        }
        assertEquals("external", File(outside, "secret").readText())
    }

    @Test fun anOpenedInputKeepsItsInodeWhenTheLiveNameIsReplaced() = fixture { _, work, _ ->
        val file = File(work, "file").apply { writeText("opened bytes") }
        AnchoredWorkspaceFiles(work).use { source ->
            source.read("file") { input, _ ->
                Files.move(file.toPath(), File(work, "retained").toPath())
                file.writeText("replacement bytes")
                assertEquals("opened bytes", input.bufferedReader().readText())
            }
        }
        assertEquals("opened bytes", File(work, "retained").readText())
        assertEquals("replacement bytes", file.readText())
    }

    @Test fun directoryRetirementPreservesNewChildrenAndSymlinksAsAWhole() = fixture { root, work, recovery ->
        val directory = File(work, "dir").apply { mkdir() }
        File(directory, "known").writeText("known")
        File(directory, "pending").writeText("unpublished")
        val outside = File(root, "outside").apply { writeText("external") }
        Files.createSymbolicLink(File(directory, "link").toPath(), outside.toPath())
        AnchoredWorkspaceFiles(work).use { source -> AnchoredWorkspaceFiles(recovery).use { target ->
            source.moveNoReplace("dir", target, "retained-dir")
        } }
        assertEquals("unpublished", File(recovery, "retained-dir/pending").readText())
        assertTrue(Files.isSymbolicLink(File(recovery, "retained-dir/link").toPath()))
        assertEquals("external", outside.readText())
    }

    @Test fun aReplacedRootIsRejectedBeforeUsingItsNames() = fixture { root, work, recovery ->
        File(work, "file").writeText("original")
        AnchoredWorkspaceFiles(work).use { source -> AnchoredWorkspaceFiles(recovery).use { target ->
            Files.move(work.toPath(), File(root, "old-work").toPath())
            assertTrue(work.mkdir()); File(work, "file").writeText("foreign")
            try { source.moveNoReplace("file", target, "file"); fail("Replacement root was accepted") }
            catch (_: IllegalStateException) { }
        } }
        assertEquals("original", File(root, "old-work/file").readText())
        assertEquals("foreign", File(work, "file").readText())
        assertFalse(File(recovery, "file").exists())
    }

    @Test fun closedTargetDoesNotLeakBorrowedSourceDescriptors() = fixture { _, work, recovery ->
        File(work, "file").writeText("CLI")
        val target = AnchoredWorkspaceFiles(recovery).apply { close() }
        fun descriptors() = Files.newDirectoryStream(File("/proc/self/fd").toPath()).use { it.count() }
        AnchoredWorkspaceFiles(work).use { source ->
            val before = descriptors()
            repeat(250) {
                try { source.moveNoReplace("file", target, "file"); fail("Closed target was accepted") }
                catch (_: IllegalStateException) { }
            }
            assertTrue(descriptors() <= before + 2)
        }
        assertEquals("CLI", File(work, "file").readText())
    }

    @Test fun invalidRelativePathsCannotEscapeTheCapability() = fixture { _, work, recovery ->
        File(work, "file").writeText("CLI")
        AnchoredWorkspaceFiles(work).use { source -> AnchoredWorkspaceFiles(recovery).use { target ->
            for (path in listOf("", "/absolute", "../file", "a/../file", "a//file", "./file", "a\\file", "bad\u0000file")) {
                try { source.moveNoReplace(path, target, "file"); fail("Invalid source path accepted") }
                catch (_: IllegalArgumentException) { }
            }
        } }
        assertEquals("CLI", File(work, "file").readText())
    }
}
