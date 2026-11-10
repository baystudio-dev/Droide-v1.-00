package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalMirrorCopyStagingTest {
    @Test fun cancelledCopyRetainsItsBytesWithoutChangingTheLiveFileOrBaseline() = fixture {
        val baseline = index.readBytes()
        sourceFile.writeText("new host bytes")
        val mirror = mirror { throw CancellationException("stop during stage verification") }
        val failure = runCatching { mirror.refresh() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("agreed", workingFile.readText())
        assertEquals("new host bytes", candidates().single().readText())
        assertArrayEquals(baseline, index.readBytes())
        assertTrue(mirror.metadataNotice.orEmpty().contains(candidates().single().absolutePath))
    }

    @Test fun replacedStageIsNeverDeletedByFailureCleanup() = fixture {
        sourceFile.writeText("new host bytes")
        val moved = File(root, "original-stage")
        val failure = runCatching { mirror { stage ->
            Files.move(stage.toPath(), moved.toPath())
            stage.writeText("unpublished competing stage")
        }.refresh() }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("unpublished competing stage", candidates().single().readText())
        assertEquals("new host bytes", moved.readText())
        assertEquals("agreed", workingFile.readText())
    }

    @Test fun replacedStageSymlinkPreservesItsTargetAndItsName() = fixture {
        sourceFile.writeText("new host bytes")
        val outside = File(root, "outside").apply { writeText("outside bytes") }
        val failure = runCatching { mirror { stage ->
            Files.move(stage.toPath(), File(root, "original-stage").toPath())
            Files.createSymbolicLink(stage.toPath(), outside.toPath())
        }.refresh() }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("outside bytes", outside.readText())
        assertTrue(Files.isSymbolicLink(candidates().single().toPath()))
        assertEquals("agreed", workingFile.readText())
    }

    @Test fun concurrentDestinationKeepsBothCliAndPreparedHostBytesWithANotice() = fixture {
        sourceFile.writeText("new host bytes")
        val mirror = mirror { workingFile.writeText("concurrent CLI bytes") }
        mirror.refresh()
        assertEquals("concurrent CLI bytes", workingFile.readText())
        assertEquals("new host bytes", candidates().single().readText())
        assertTrue(mirror.metadataNotice.orEmpty().contains(candidates().single().absolutePath))
        assertEquals(hash("agreed"), mirror.baseline().getValue("file").sha256)
    }

    @Test fun concurrentNewDestinationUsesNoReplaceAndPreservesThePreparedCopy() = fixture {
        sourceFile.delete(); workingFile.delete()
        LocalAgentWorkspaceMirror(source, working).refresh()
        sourceFile.writeText("new host bytes")
        val mirror = mirror { workingFile.writeText("new CLI file") }
        mirror.refresh()
        assertEquals("new CLI file", workingFile.readText())
        assertEquals("new host bytes", candidates().single().readText())
        assertFalse(mirror.baseline().containsKey("file"))
        assertTrue(mirror.metadataNotice.orEmpty().contains("concurrently"))
    }

    @Test fun existingRetainedStageIsNotReusedOrChangedByALaterSuccessfulCopy() = fixture {
        val retained = File(working, ".droide-tmp-11111111-1111-4111-8111-111111111111").apply { writeText("retained work") }
        val before = Files.readAttributes(retained.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        sourceFile.writeText("later host bytes")
        LocalAgentWorkspaceMirror(source, working).refresh()
        assertEquals("later host bytes", workingFile.readText())
        assertEquals("retained work", retained.readText())
        assertEquals(before.fileKey(), Files.readAttributes(retained.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
        assertEquals(before.lastModifiedTime(), Files.getLastModifiedTime(retained.toPath()))
        assertEquals(listOf(retained), candidates())
    }

    @Test fun retainedStageCapacityFailsBeforeAnotherFileIsCreated() = fixture {
        repeat(RetainedWorkPolicy.MAX_ENTRIES) { index ->
            File(working, ".droide-tmp-${java.util.UUID(0L, index.toLong())}").writeText("retained-$index")
        }
        val before = candidates().associate { it.name to it.readText() }
        sourceFile.writeText("new host bytes")
        val failure = runCatching { LocalAgentWorkspaceMirror(source, working).refresh() }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("retained", ignoreCase = true))
        assertEquals(before, candidates().associate { it.name to it.readText() })
        assertEquals("agreed", workingFile.readText())
    }

    @Test fun successfulCreateAndReplaceLeaveNoStagingFiles() = fixture {
        sourceFile.writeText("replacement")
        File(source, "new").writeText("new file")
        LocalAgentWorkspaceMirror(source, working).refresh()
        assertEquals("replacement", workingFile.readText())
        assertEquals("new file", File(working, "new").readText())
        assertTrue(candidates().isEmpty())
    }

    @Test fun successfulCopyPreservesExecutableMode() = fixture {
        sourceFile.writeText("#!/bin/sh\nexit 0\n")
        assertTrue(sourceFile.setExecutable(true, true))
        val mirror = LocalAgentWorkspaceMirror(source, working)
        mirror.refresh()
        assertTrue(workingFile.canExecute())
        assertEquals(493, mirror.baseline().getValue("file").mode)
        assertTrue(candidates().isEmpty())
    }

    @Test fun emptyCopyIsPublishedAndLeavesNoStagingFiles() = fixture {
        sourceFile.writeBytes(byteArrayOf())
        LocalAgentWorkspaceMirror(source, working).refresh()
        assertTrue(workingFile.isFile)
        assertEquals(0L, workingFile.length())
        assertTrue(candidates().isEmpty())
    }

    @Test fun nestedCopyRetainsOnlyItsOwnInterruptedStage() = fixture {
        sourceFile.delete(); workingFile.delete()
        LocalAgentWorkspaceMirror(source, working).refresh()
        File(source, "nested").mkdir()
        File(source, "nested/file").writeText("nested host bytes")
        val mirror = mirror { throw CancellationException("stop nested copy") }
        assertTrue(runCatching { mirror.refresh() }.exceptionOrNull() is CancellationException)
        assertFalse(File(working, "nested/file").exists())
        val stage = File(working, "nested").listFiles()!!.single()
        assertTrue(RetainedWorkPolicy.isCandidateName(stage.name))
        assertEquals("nested host bytes", stage.readText())
        assertTrue(candidates().isEmpty())
    }

    @Test fun manySuccessfulCopiesDoNotConsumeTheRetainedStageQuota() = fixture {
        repeat(RetainedWorkPolicy.MAX_ENTRIES * 2) { File(source, "file-$it").writeText("bytes-$it") }
        LocalAgentWorkspaceMirror(source, working).refresh()
        repeat(RetainedWorkPolicy.MAX_ENTRIES * 2) { assertEquals("bytes-$it", File(working, "file-$it").readText()) }
        assertTrue(candidates().isEmpty())
    }

    private data class Fixture(val root: File, val source: File, val working: File) {
        val sourceFile get() = File(source, "file")
        val workingFile get() = File(working, "file")
        val index get() = File(root, ".mirror-sync-baseline.tsv")
        fun candidates() = working.listFiles()!!.filter { RetainedWorkPolicy.isCandidateName(it.name) }.sortedBy { it.name }
        fun mirror(action: (File) -> Unit): LocalAgentWorkspaceMirror {
            var called = false
            return LocalAgentWorkspaceMirror(source, working, WorkspaceFileFingerprintCache(maxEntries = 0, onHashRead = { path, _ ->
                if (!called && RetainedWorkPolicy.isCandidateName(path.fileName.toString())) {
                    called = true
                    action(path.toFile())
                }
            }))
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-copy-stage-").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            val working = File(root, "mirror")
            File(source, "file").writeText("agreed")
            LocalAgentWorkspaceMirror(source, working).refresh()
            Fixture(root, source, working).block()
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun hash(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
