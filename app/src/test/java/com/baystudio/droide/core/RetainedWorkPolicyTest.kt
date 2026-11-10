package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RetainedWorkPolicyTest {
    @Test fun onlyExactCanonicalStageNamesAreCandidates() {
        assertTrue(RetainedWorkPolicy.isCandidateName(stage(1)))
        for (name in listOf("ordinary.kt", ".droide-tmp-", stage(1).uppercase(), "x${stage(1)}", "${stage(1)}/child", "${stage(1)}-copy"))
            assertFalse(name, RetainedWorkPolicy.isCandidateName(name))
    }

    @Test fun thirtyOneCandidatesAdmitOneExclusiveStageAndThirtyTwoRejectBeforeAction() = fixture { root ->
        repeat(31) { root.resolve(stage(it)).writeText("retained-$it") }
        RetainedWorkPolicy.creating(root) { files -> files.createNew(stage(31)).close() }
        var invoked = false
        val failure = assertThrows(IOException::class.java) {
            RetainedWorkPolicy.creating(root) { invoked = true; it.createNew(stage(32)).close() }
        }
        assertFalse(invoked)
        assertTrue(failure.message.orEmpty().contains(root.path))
        assertTrue(failure.message.orEmpty().contains("32"))
        assertTrue(failure.message.orEmpty().contains("First names:"))
        assertEquals(32, root.listFiles()!!.size)
        repeat(31) { assertEquals("retained-$it", root.resolve(stage(it)).readText()) }
        assertEquals(0L, root.resolve(stage(31)).length())
    }

    @Test fun directoryAndSymlinkCandidatesConsumeSlotsWithoutFollowingThem() = fixture { root ->
        val outside = Files.createTempFile("retained-outside-", ".bin").toFile()
        try {
            outside.writeText("private external bytes")
            repeat(30) { root.resolve(stage(it)).writeText("retained-$it") }
            root.resolve(stage(30)).mkdir()
            Files.createSymbolicLink(root.resolve(stage(31)).toPath(), outside.toPath())
            var invoked = false
            assertThrows(IOException::class.java) {
                RetainedWorkPolicy.creating(root) { invoked = true; it.createNew(stage(32)).close() }
            }
            assertFalse(invoked)
            assertTrue(root.resolve(stage(30)).isDirectory)
            assertTrue(Files.isSymbolicLink(root.resolve(stage(31)).toPath()))
            assertEquals("private external bytes", outside.readText())
        } finally { outside.delete() }
    }

    @Test fun concurrentApplicationAdmissionsCannotOvershootTheParentLimit() = fixture { root ->
        repeat(25) { root.resolve(stage(it)).writeText("existing-$it") }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val work = (25 until 41).map { index -> pool.submit<Boolean> {
                check(start.await(5, TimeUnit.SECONDS))
                try {
                    RetainedWorkPolicy.creating(root) { files -> files.createNew(stage(index)).close() }
                    true
                } catch (_: IOException) { false }
            } }
            start.countDown()
            assertEquals(7, work.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(32, root.listFiles()!!.count { RetainedWorkPolicy.isCandidateName(it.name) })
            repeat(25) { assertEquals("existing-$it", root.resolve(stage(it)).readText()) }
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun explicitInspectionReadsOnlyTheSelectedDirectoryAndLeavesBytesUnchanged() = runBlocking {
        fixture { root ->
            val nested = root.resolve("nested-日本").apply { mkdir() }
            val original = root.resolve(stage(1)).apply { writeText("root candidate") }
            val child = nested.resolve(stage(2)).apply { writeText("child candidate") }
            val rootIdentity = ProjectCopyEvidence.identity(original)
            val childIdentity = ProjectCopyEvidence.identity(child)
            val atRoot = RetainedWorkPolicy.inspect(root, "")
            val atChild = RetainedWorkPolicy.inspect(root, nested.name)
            assertTrue(atRoot.complete)
            assertTrue(atChild.complete)
            assertNull(atRoot.problem)
            assertNull(atChild.problem)
            assertEquals(listOf(stage(1)), atRoot.candidates.map { it.path })
            assertEquals(listOf("${nested.name}/${stage(2)}"), atChild.candidates.map { it.path })
            assertEquals(RetainedWorkKind.PRESENT, atRoot.candidates.single().kind)
            assertEquals("root candidate".length.toLong(), atRoot.candidates.single().bytes)
            assertEquals(rootIdentity, ProjectCopyEvidence.identity(original))
            assertEquals(childIdentity, ProjectCopyEvidence.identity(child))
            assertEquals("root candidate", original.readText())
            assertEquals("child candidate", child.readText())
        }
    }

    @Test fun inspectionClassifiesForeignTypesAndReportsLargeRegularSizeWithoutReadingContent() = runBlocking {
        fixture { root ->
            val outside = Files.createTempFile("retained-inspection-target-", ".bin").toFile()
            try {
                outside.writeText("outside bytes")
                Files.createSymbolicLink(root.resolve(stage(1)).toPath(), outside.toPath())
                root.resolve(stage(2)).mkdir()
                root.resolve(stage(2)).resolve("kept.txt").writeText("directory contents")
                val size = 512L * 1024 * 1024 + 1
                FileChannel.open(root.resolve(stage(3)).toPath(), CREATE_NEW, WRITE).use {
                    it.position(size - 1); it.write(ByteBuffer.wrap(byteArrayOf(0))); it.force(true)
                }
                val inventory = RetainedWorkPolicy.inspect(root, "")
                assertTrue(inventory.complete)
                assertNull(inventory.problem)
                val byPath = inventory.candidates.associateBy { it.path }
                assertEquals(RetainedWorkKind.FOREIGN, byPath.getValue(stage(1)).kind)
                assertEquals(RetainedWorkKind.FOREIGN, byPath.getValue(stage(2)).kind)
                assertEquals(RetainedWorkKind.PRESENT, byPath.getValue(stage(3)).kind)
                assertEquals(size, byPath.getValue(stage(3)).bytes)
                assertEquals("outside bytes", outside.readText())
                assertEquals("directory contents", root.resolve(stage(2)).resolve("kept.txt").readText())
                assertEquals(size, root.resolve(stage(3)).length())
            } finally { outside.delete() }
        }
    }

    @Test fun missingAliasedOrTraversalDirectoriesReturnAnExplicitIncompleteResult() = runBlocking {
        fixture { root ->
            root.resolve("actual").mkdir()
            root.resolve("actual").resolve(stage(1)).writeText("keep")
            Files.createSymbolicLink(root.resolve("alias").toPath(), root.resolve("actual").toPath())
            for (directory in listOf("missing", "alias", "actual/..", "../escape", root.absolutePath, "actual/./")) {
                val inventory = RetainedWorkPolicy.inspect(root, directory)
                assertFalse(directory, inventory.complete)
                assertNotNull(directory, inventory.problem)
                assertTrue(directory, inventory.candidates.isEmpty())
            }
            assertEquals("keep", root.resolve("actual").resolve(stage(1)).readText())
        }
    }

    @Test fun candidateLimitPreservesSixtyFourResultsAndReportsTheOmission() = runBlocking {
        fixture { root ->
            repeat(65) { root.resolve(stage(it)).writeText("candidate-$it") }
            val inventory = RetainedWorkPolicy.inspect(root, "")
            assertFalse(inventory.complete)
            assertTrue(inventory.problem.orEmpty().contains("64"))
            assertEquals(64, inventory.candidates.size)
            assertTrue(inventory.candidates.all { it.kind == RetainedWorkKind.PRESENT })
            repeat(65) { assertEquals("candidate-$it", root.resolve(stage(it)).readText()) }
        }
    }

    @Test fun oversizedDirectoryFailsAdmissionAndReturnsAnExplicitPartialInspection() = runBlocking {
        fixture { root ->
            repeat(RetainedWorkPolicy.MAX_SCAN + 1) { Files.createFile(root.resolve("ordinary-$it").toPath()) }
            var invoked = false
            val failure = assertThrows(IOException::class.java) {
                RetainedWorkPolicy.creating(root) { invoked = true; it.createNew(stage(1)).close() }
            }
            assertFalse(invoked)
            assertTrue(failure.message.orEmpty().contains(RetainedWorkPolicy.MAX_SCAN.toString()))
            val inventory = RetainedWorkPolicy.inspect(root, "")
            assertFalse(inventory.complete)
            assertNotNull(inventory.problem)
            assertTrue(inventory.problem.orEmpty().contains(RetainedWorkPolicy.MAX_SCAN.toString()))
            assertTrue(inventory.candidates.isEmpty())
            assertEquals(RetainedWorkPolicy.MAX_SCAN + 1, root.listFiles()!!.size)
        }
    }

    private fun stage(index: Int) = ".droide-tmp-${index.toString(16).padStart(8, '0')}-1111-4111-8111-${index.toString(16).padStart(12, '0')}"

    private inline fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("retained-work-policy-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
