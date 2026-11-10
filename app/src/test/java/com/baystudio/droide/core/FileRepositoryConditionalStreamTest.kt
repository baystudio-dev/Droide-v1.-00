package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Host filesystem regressions; not Android/PRoot or physical-device validation. */
class FileRepositoryConditionalStreamTest {
    private lateinit var root: File
    @Before fun setup() { root = Files.createTempDirectory("conditional-stream-").toFile() }
    @After fun cleanup() { PathSecurity.deleteTreeNoFollow(root) }

    @Test fun artifactLargerThanEditorLimitUpdatesByBytesAndNotifiesRepository() = runBlocking {
        val size = 21L * 1024 * 1024 + 17
        root.resolve("app.apk").outputStream().use { PatternStream(size, 17).copyTo(it, 64 * 1024) }
        val before = StreamFileVersion.capture(root, "app.apk")
        assertTrue(before.bytes > EditorLargeFilePolicy.MAX_EDITABLE_BYTES)
        val changes = mutableListOf<FileRepository.Mutation>()
        val repository = FileRepository(root, onMutation = { changes += it })
        assertEquals(size, repository.writeStream("app.apk", PatternStream(size, 29),
            expectedBytes = size, expectedVersion = before))
        val after = StreamFileVersion.capture(root, "app.apk")
        assertEquals(size, after.bytes)
        assertNotEquals(before.sha256, after.sha256)
        PatternStream(size, 29).use { expected ->
            root.resolve("app.apk").inputStream().use { actual ->
                val a = ByteArray(64 * 1024)
                val b = ByteArray(a.size)
                while (true) {
                    val count = expected.read(a)
                    assertEquals(count, actual.read(b))
                    if (count < 0) break
                    assertArrayEquals(a.copyOf(count), b.copyOf(count))
                }
            }
        }
        assertEquals(listOf(FileRepository.Mutation.Write("app.apk")), changes)
        assertNoStages()
    }

    @Test fun unchangedExistingBinaryPublishesWithoutChangingExecutablePermissions() = runBlocking {
        val file = root.resolve("script")
        file.writeBytes(byteArrayOf(0, 1, 2))
        val permissions = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ)
        Files.setPosixFilePermissions(file.toPath(), permissions)
        val version = StreamFileVersion.capture(root, "script")
        FileRepository(root).writeStream("script", byteArrayOf(0, 4, 5).inputStream(), expectedVersion = version)
        assertArrayEquals(byteArrayOf(0, 4, 5), file.readBytes())
        assertEquals(permissions, Files.getPosixFilePermissions(file.toPath()))
    }

    @Test fun readOnlyExistingArtifactIsRejectedBeforeReadingInput() = runBlocking {
        val file = root.resolve("asset.glb").apply { writeText("original") }
        Files.setPosixFilePermissions(file.toPath(), setOf(PosixFilePermission.OWNER_READ))
        val version = StreamFileVersion.capture(root, file.name)
        var reads = 0
        expectRejected {
            FileRepository(root).writeStream(file.name, object : InputStream() {
                override fun read(): Int { reads++; return -1 }
            }, expectedVersion = version)
        }
        assertEquals(0, reads)
        assertEquals("original", file.readText())
        assertNoStages()
    }

    @Test fun competingModificationDuringCopyPreservesCompetitorAndRetainsOwnProposal() = runBlocking {
        val file = root.resolve("asset.glb").apply { writeText("old") }
        val version = StreamFileVersion.capture(root, file.name)
        val source = inputWithFirstRead { file.writeText("competitor") }
        val repository = FileRepository(root)
        expectRejected { repository.writeStream(file.name, source, expectedVersion = version) }
        assertEquals("competitor", file.readText())
        assertRetainedProposal(byteArrayOf(0, 1, 2, 3), repository, file.name)
    }

    @Test fun absentTokenNeverReplacesFileCreatedDuringCopyEvenWithoutRequireAbsentFlag() = runBlocking {
        val file = root.resolve("preview.png")
        val version = StreamFileVersion.capture(root, file.name)
        assertFalse(version.existed)
        val repository = FileRepository(root)
        expectRejected {
            repository.writeStream(file.name, inputWithFirstRead { file.writeText("user") },
                expectedVersion = version)
        }
        assertEquals("user", file.readText())
        assertRetainedProposal(byteArrayOf(0, 1, 2, 3), repository, file.name)
    }

    @Test fun publicationObserverCannotReplaceExpectedTargetUnnoticed() = runBlocking {
        val file = root.resolve("asset.zip").apply { writeText("old") }
        val version = StreamFileVersion.capture(root, file.name)
        expectRejected {
            FileRepository(root).writeStream(file.name, byteArrayOf(0, 1, 2).inputStream(),
                expectedVersion = version, publicationObserver = observer { file.writeText("competitor") })
        }
        assertEquals("competitor", file.readText())
        assertRetainedProposal(byteArrayOf(0, 1, 2))
    }

    @Test fun publicationObserverCreatingAbsentTargetIsRejected() = runBlocking {
        val file = root.resolve("preview.png")
        val version = StreamFileVersion.capture(root, file.name)
        expectRejected {
            FileRepository(root).writeStream(file.name, byteArrayOf(0, 1, 2).inputStream(),
                requireAbsent = true, expectedVersion = version,
                publicationObserver = observer { file.writeText("competitor") })
        }
        assertEquals("competitor", file.readText())
        assertRetainedProposal(byteArrayOf(0, 1, 2))
    }

    @Test fun sameLengthEditWithRestoredMtimeIsStillAConflict() = runBlocking {
        val file = root.resolve("asset.glb").apply { writeText("old") }
        val modified = Files.getLastModifiedTime(file.toPath())
        val version = StreamFileVersion.capture(root, file.name)
        expectRejected {
            FileRepository(root).writeStream(file.name, "agent".byteInputStream(), expectedVersion = version,
                publicationObserver = observer {
                    file.writeText("new")
                    Files.setLastModifiedTime(file.toPath(), modified)
                })
        }
        assertEquals("new", file.readText())
        assertRetainedProposal("agent".toByteArray())
    }

    @Test fun tokenCannotAuthorizeAnotherPath() = runBlocking {
        root.resolve("first.png").writeText("one")
        val version = StreamFileVersion.capture(root, "first.png")
        expectRejected {
            FileRepository(root).writeStream("second.png", byteArrayOf(0).inputStream(), expectedVersion = version)
        }
        assertFalse(root.resolve("second.png").exists())
        assertNoStages()
    }

    @Test fun editorBecomingDirtyAtPublicationIsRecheckedWithoutStartingAnotherTransaction() = runBlocking {
        val file = root.resolve("open.txt").apply { writeText("disk") }
        val version = StreamFileVersion.capture(root, file.name)
        var dirty = false
        var admissionChecks = 0
        var begins = 0
        val participant = object : WorkspaceMutationParticipant {
            override suspend fun begin(guard: WorkspaceMutationGuard, mutation: FileRepository.Mutation): WorkspaceMutationSession {
                begins++
                return object : WorkspaceMutationSession(guard, mutation) {}
            }
        }
        val repository = FileRepository(root, beforeMutation = {
            admissionChecks++
            check(!dirty) { "Unsaved editor changes" }
        }, newFilePublisher = NewProjectFilePublisher.platform, participant = participant)
        expectRejected {
            repository.writeStream(file.name, "agent".byteInputStream(), expectedVersion = version,
                publicationObserver = observer { dirty = true })
        }
        assertEquals(1, begins)
        assertEquals(2, admissionChecks)
        assertEquals("disk", file.readText())
        assertRetainedProposal("agent".toByteArray())
    }

    @Test fun guardedDeletePreservesFileChangedByAdmissionCallback() = runBlocking {
        val file = root.resolve("asset.glb").apply { writeText("old") }
        val version = StreamFileVersion.capture(root, file.name)
        val repository = FileRepository(root, beforeMutation = { file.writeText("user") })
        expectRejected { repository.deleteFileIfUnchanged(file.name, version) }
        assertEquals("user", file.readText())
        assertNoStages()
    }

    @Test fun guardedDeletePreservesFileChangedByTransactionPreparation() = runBlocking {
        val file = root.resolve("asset.glb").apply { writeText("old") }
        val version = StreamFileVersion.capture(root, file.name)
        val participant = object : WorkspaceMutationParticipant {
            override suspend fun begin(guard: WorkspaceMutationGuard, mutation: FileRepository.Mutation) =
                object : WorkspaceMutationSession(guard, mutation) {
                    override fun beforeLocalAttempt(preparation: WorkspaceLocalPreparation) {
                        if (preparation.effect == WorkspaceLocalEffect.DELETE_STAGE) file.writeText("new")
                    }
                }
        }
        val repository = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, participant = participant)
        expectRejected { repository.deleteFileIfUnchanged(file.name, version) }
        assertEquals("new", file.readText())
        assertNoStages()
    }

    @Test fun unchangedLargeBinaryCanBeDeletedWithOrdinaryRepositoryNotification() = runBlocking {
        val file = root.resolve("old.apk")
        file.outputStream().use { PatternStream(21L * 1024 * 1024, 5).copyTo(it) }
        val version = StreamFileVersion.capture(root, file.name)
        val changes = mutableListOf<FileRepository.Mutation>()
        assertTrue(FileRepository(root, onMutation = { changes += it }).deleteFileIfUnchanged(file.name, version))
        assertFalse(file.exists())
        assertEquals(listOf(FileRepository.Mutation.Delete(file.name)), changes)
        assertNoStages()
    }

    @Test fun cancellationBeforePublicationPreservesOriginalAndRetainsCopiedProposal() = runBlocking {
        val file = root.resolve("app.apk").apply { writeText("original") }
        val version = StreamFileVersion.capture(root, file.name)
        val prepared = CompletableDeferred<Unit>()
        val wait = CompletableDeferred<Unit>()
        val repository = FileRepository(root)
        val job = async {
            repository.writeStream(file.name, PatternStream(1024 * 1024, 13), expectedVersion = version,
                beforePublication = { prepared.complete(Unit); wait.await() })
        }
        prepared.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals("original", file.readText())
        assertRetainedProposal(PatternStream(1024 * 1024, 13).use { it.readBytes() }, repository, file.name)
    }

    @Test fun captureRejectsSymlinksAndDirectoryReplacementBeforePublication() = runBlocking {
        root.resolve("real").mkdir()
        Files.createSymbolicLink(root.resolve("alias").toPath(), root.resolve("real").toPath())
        expectRejected { StreamFileVersion.capture(root, "alias/file.png") }
        val file = root.resolve("real/asset.png").apply { writeText("original") }
        val version = StreamFileVersion.capture(root, "real/asset.png")
        expectRejected {
            FileRepository(root).writeStream("real/asset.png", byteArrayOf(0).inputStream(),
                expectedVersion = version, publicationObserver = observer {
                    root.resolve("real").renameTo(root.resolve("retained"))
                    root.resolve("real").mkdir()
                    root.resolve("real/asset.png").writeText("competitor")
                })
        }
        assertEquals("competitor", file.readText())
        assertEquals("original", root.resolve("retained/asset.png").readText())
        // A stage in a moved parent is deliberately retained: cleanup never follows a replacement
        // parent.
    }

    private fun observer(before: () -> Unit) = object : StreamPublicationObserver {
        override fun beforePublish(preparation: StreamPublicationPreparation) = before()
        override fun onPublished(receipt: StreamPublicationReceipt) { fail("Rejected publication emitted a receipt") }
    }

    private fun inputWithFirstRead(action: () -> Unit) = object : ByteArrayInputStream(byteArrayOf(0, 1, 2, 3)) {
        var first = true
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (first) { first = false; action() }
            return super.read(bytes, offset, length)
        }
    }

    private suspend fun expectRejected(action: suspend () -> Unit): Exception {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { return failure }
        throw AssertionError("Expected publication to reject competing workspace state")
    }

    private fun assertNoStages() {
        assertTrue(root.walkTopDown().none { it.name.startsWith(".droide-tmp-") })
    }

    private fun assertRetainedProposal(bytes: ByteArray) {
        val retained = root.walkTopDown().filter { it.isFile && it.name.startsWith(".droide-tmp-") }.toList()
        assertEquals(1, retained.size)
        assertArrayEquals(bytes, retained.single().readBytes())
    }

    private fun assertRetainedProposal(bytes: ByteArray, repository: FileRepository, target: String) {
        assertRetainedProposal(bytes)
        val retained = root.walkTopDown().single { it.isFile && it.name.startsWith(".droide-tmp-") }
        val notice = repository.retainedWork.value.notices.single()
        assertEquals(target, notice.target)
        assertEquals(retained.relativeTo(root).invariantSeparatorsPath, notice.candidate.path)
        assertEquals(RetainedWorkKind.PRESENT, notice.candidate.kind)
        assertEquals(bytes.size.toLong(), notice.candidate.bytes)
        assertFalse(notice.historicalWriteReturned)
    }

    private class PatternStream(private val length: Long, private val seed: Int) : InputStream() {
        private var position = 0L
        override fun read(): Int = if (position >= length) -1 else ((position++ * 31 + seed) and 0xff).toInt()
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            if (position >= length) return -1
            val size = minOf(count.toLong(), length - position).toInt()
            repeat(size) { bytes[offset + it] = ((position++ * 31 + seed) and 0xff).toByte() }
            return size
        }
    }
}
