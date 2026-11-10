package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class FileRepositoryRetainedWorkTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(20)
    private val payload = "complete repository payload\n".toByteArray()

    @Test fun nativePublicationConsumesItsStageWithoutInventingARetentionNotice() = runBlocking {
        withProject { root ->
            val repository = FileRepository(root, newFilePublisher = NewProjectFilePublisher.native())
            assertEquals(payload.size.toLong(), repository.writeStream("saved.bin", payload.inputStream(), requireAbsent = true))
            assertArrayEquals(payload, root.resolve("saved.bin").readBytes())
            assertTrue(stages(root).isEmpty())
            assertTrue(repository.retainedWork.value.notices.isEmpty())
            val inventory = repository.inspectRetainedWork()
            assertTrue(inventory.complete)
            assertNull(inventory.problem)
            assertTrue(inventory.candidates.isEmpty())
        }
    }

    @Test fun successfulHardLinkPublicationReturnsSuccessAndReportsItsRemainingAlias() = runBlocking {
        withProject { root ->
            val repository = hardLinkRepository(root)
            assertEquals(payload.size.toLong(), repository.writeStream("saved.bin", payload.inputStream(), requireAbsent = true))
            val stage = stages(root).single()
            val notice = repository.retainedWork.value.notices.single()
            assertEquals("saved.bin", notice.target)
            assertEquals(stage.name, notice.candidate.path)
            assertEquals(RetainedWorkKind.PRESENT, notice.candidate.kind)
            assertEquals(payload.size.toLong(), notice.candidate.bytes)
            assertTrue(notice.historicalWriteReturned)
            assertArrayEquals(payload, stage.readBytes())
            assertArrayEquals(payload, root.resolve("saved.bin").readBytes())
            assertTrue(Files.isSameFile(stage.toPath(), root.resolve("saved.bin").toPath()))
        }
    }

    @Test fun partialInputFailureKeepsTheOriginalThrowableAndReportsTheOnlyPartialPayload() = runBlocking {
        withProject { root ->
            root.resolve("user.txt").writeText("existing user work")
            val failure = IOException("single-use input stopped")
            val prefix = "already received bytes".toByteArray()
            val input = FailingInput(prefix, failure)
            val repository = hardLinkRepository(root)
            val actual = runCatching { repository.writeStream("incomplete.bin", input, requireAbsent = true) }.exceptionOrNull()
            assertSame(failure, actual)
            assertEquals(1, input.closeCalls)
            assertFalse(root.resolve("incomplete.bin").exists())
            assertEquals("existing user work", root.resolve("user.txt").readText())
            val stage = stages(root).single()
            val notice = repository.retainedWork.value.notices.single()
            assertArrayEquals(prefix, stage.readBytes())
            assertEquals("incomplete.bin", notice.target)
            assertEquals(stage.name, notice.candidate.path)
            assertEquals(prefix.size.toLong(), notice.candidate.bytes)
            assertFalse(notice.historicalWriteReturned)
        }
    }

    @Test fun cancelledInputKeepsCancellationIdentityAndItsReceivedBytes() = runBlocking {
        withProject { root ->
            val stopped = CancellationException("user requested stop")
            val prefix = "bytes before stop".toByteArray()
            val input = FailingInput(prefix, stopped)
            val repository = hardLinkRepository(root)
            val actual = runCatching { repository.writeStream("cancelled.bin", input, requireAbsent = true) }.exceptionOrNull()
            assertSame(stopped, actual)
            assertEquals(1, input.closeCalls)
            assertFalse(root.resolve("cancelled.bin").exists())
            val stage = stages(root).single()
            assertArrayEquals(prefix, stage.readBytes())
            val notice = repository.retainedWork.value.notices.single()
            assertEquals(stage.name, notice.candidate.path)
            assertEquals("cancelled.bin", notice.target)
            assertFalse(notice.historicalWriteReturned)
        }
    }

    @Test fun synchronizationFailurePreservesItsCauseAndTheHistoricalLocalWrite() = runBlocking {
        withProject { root ->
            val failure = IOException("mirror unavailable")
            val repository = hardLinkRepository(root) { mutation ->
                if (mutation is FileRepository.Mutation.Write) throw failure
            }
            val actual = runCatching {
                repository.writeStream("saved.bin", payload.inputStream(), requireAbsent = true)
            }.exceptionOrNull()
            assertTrue(actual is ProjectFileSyncException)
            assertSame(failure, actual!!.cause)
            assertArrayEquals(payload, root.resolve("saved.bin").readBytes())
            val stage = stages(root).single()
            assertArrayEquals(payload, stage.readBytes())
            val notice = repository.retainedWork.value.notices.single()
            assertEquals("saved.bin", notice.target)
            assertEquals(stage.name, notice.candidate.path)
            assertTrue(notice.historicalWriteReturned)
        }
    }

    @Test fun aLaterCanonicalWriterSurvivesWithoutReclassifyingTheOlderReceiptAsCurrent() = runBlocking {
        withProject { root ->
            val replacement = root.resolve("replacement.bin").apply { writeText("newer user bytes") }
            val failure = IOException("synchronization lost after another writer")
            var receipt: StreamPublicationReceipt? = null
            val observer = object : StreamPublicationObserver {
                override fun beforePublish(preparation: StreamPublicationPreparation) {}
                override fun onPublished(value: StreamPublicationReceipt) { receipt = value }
            }
            val repository = hardLinkRepository(root) { mutation ->
                if (mutation is FileRepository.Mutation.Write) {
                    Files.move(replacement.toPath(), root.resolve("saved.bin").toPath(), REPLACE_EXISTING)
                    throw failure
                }
            }
            val actual = runCatching {
                repository.writeStream("saved.bin", payload.inputStream(), requireAbsent = true, publicationObserver = observer)
            }.exceptionOrNull()
            assertSame(failure, actual)
            val stage = stages(root).single()
            val historical = requireNotNull(receipt)
            assertEquals(historical.destinationIdentity, ProjectCopyEvidence.identity(stage).key)
            assertNotEquals(historical.destinationIdentity, ProjectCopyEvidence.identity(root.resolve("saved.bin")).key)
            assertArrayEquals(payload, stage.readBytes())
            assertEquals("newer user bytes", root.resolve("saved.bin").readText())
            assertTrue(repository.retainedWork.value.notices.single().historicalWriteReturned)
        }
    }

    @Test fun aForeignStageReplacementIsReportedWithoutDeletingOrClaimingItsBytes() = runBlocking {
        withProject { root ->
            val retained = root.resolve("original-stage")
            var originalIdentity: String? = null
            val repository = hardLinkRepository(root) { mutation ->
                if (mutation is FileRepository.Mutation.Write) {
                    val stage = stages(root).single()
                    originalIdentity = ProjectCopyEvidence.identity(stage).key
                    Files.move(stage.toPath(), retained.toPath())
                    stage.writeText("foreign replacement")
                }
            }
            assertEquals(payload.size.toLong(), repository.writeStream("saved.bin", payload.inputStream(), requireAbsent = true))
            val foreign = stages(root).single()
            assertEquals("foreign replacement", foreign.readText())
            assertArrayEquals(payload, retained.readBytes())
            assertArrayEquals(payload, root.resolve("saved.bin").readBytes())
            assertEquals(originalIdentity, ProjectCopyEvidence.identity(retained).key)
            assertNotEquals(originalIdentity, ProjectCopyEvidence.identity(foreign).key)
            val notice = repository.retainedWork.value.notices.single()
            assertEquals(foreign.name, notice.candidate.path)
            assertEquals(RetainedWorkKind.FOREIGN, notice.candidate.kind)
            assertTrue(notice.historicalWriteReturned)
        }
    }

    @Test fun acknowledgingAnOldTokenCannotClearNewerNoticesOrAnyRetainedFile() = runBlocking {
        withProject { root ->
            val repository = hardLinkRepository(root)
            repository.writeStream("first.bin", payload.inputStream(), requireAbsent = true)
            val old = repository.retainedWork.value
            repository.writeStream("second.bin", payload.inputStream(), requireAbsent = true)
            val current = repository.retainedWork.value
            assertNotEquals(old.token, current.token)
            val before = fileSnapshot(root)
            repository.acknowledgeRetainedWork(old.token)
            assertSame(current, repository.retainedWork.value)
            assertEquals(before, fileSnapshot(root))
            repository.acknowledgeRetainedWork(current.token)
            assertTrue(repository.retainedWork.value.notices.isEmpty())
            assertEquals(0L, repository.retainedWork.value.omitted)
            assertEquals(before, fileSnapshot(root))
            assertEquals(2, stages(root).size)
        }
    }

    @Test fun noticeBoundsDoNotDiscardThePayloadsOmittedFromTheNotificationSample() = runBlocking {
        withProject { root ->
            val repository = hardLinkRepository(root)
            val expected = (0 until 10).associate { "saved-$it.bin" to "payload-$it".toByteArray() }
            expected.forEach { (name, bytes) -> repository.writeStream(name, bytes.inputStream(), requireAbsent = true) }
            val snapshot = repository.retainedWork.value
            assertEquals(8, snapshot.notices.size)
            assertEquals(2L, snapshot.omitted)
            assertEquals((2 until 10).map { "saved-$it.bin" }.toSet(), snapshot.notices.map { it.target }.toSet())
            assertTrue(snapshot.notices.all { it.historicalWriteReturned })
            assertEquals(10, stages(root).size)
            expected.forEach { (name, bytes) -> assertArrayEquals(bytes, root.resolve(name).readBytes()) }
            assertEquals(expected.values.map { it.toList() }.toSet(), stages(root).map { it.readBytes().toList() }.toSet())
            val before = fileSnapshot(root)
            val inventory = repository.inspectRetainedWork()
            assertTrue(inventory.complete)
            assertEquals(10, inventory.candidates.size)
            assertEquals(before, fileSnapshot(root))
            assertSame(snapshot, repository.retainedWork.value)
        }
    }

    @Test fun aFreshRepositoryCanInspectPreviousCandidatesWithExplicitPerFolderCoverage() = runBlocking {
        withProject { root ->
            root.resolve("nested").mkdir()
            val previous = hardLinkRepository(root)
            previous.writeStream("root.bin", payload.inputStream(), requireAbsent = true)
            previous.writeStream("nested/old.bin", payload.inputStream(), requireAbsent = true)
            val before = fileSnapshot(root)
            val reopened = FileRepository(root)
            assertTrue(reopened.retainedWork.value.notices.isEmpty())
            val top = reopened.inspectRetainedWork()
            assertTrue(top.complete)
            assertEquals("", top.directory)
            assertEquals(1, top.candidates.size)
            assertTrue(top.candidates.none { '/' in it.path })
            val nested = reopened.inspectRetainedWork("nested")
            assertTrue(nested.complete)
            assertEquals("nested", nested.directory)
            assertEquals(1, nested.candidates.size)
            assertTrue(nested.candidates.single().path.startsWith("nested/"))
            assertEquals(payload.size.toLong(), nested.candidates.single().bytes)
            assertEquals(before, fileSnapshot(root))
            assertTrue(reopened.retainedWork.value.notices.isEmpty())
        }
    }

    @Test fun textStagingWithInsufficientHeadroomPreservesOriginalBeforeCreatingAnyCandidate() = runBlocking {
        withProject { root ->
            val original = root.resolve("existing.txt").apply { writeText("original work") }
            val identity = ProjectCopyEvidence.identity(original)
            val repository = FileRepository(object : File(root.path) {
                override fun getUsableSpace(): Long = 1L
            })
            val failure = runCatching { repository.writeText("existing.txt", "replacement") }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure!!.message.orEmpty().contains("free storage"))
            assertEquals("original work", original.readText())
            assertEquals(identity, ProjectCopyEvidence.identity(original))
            assertTrue(stages(root).isEmpty())
            assertTrue(repository.retainedWork.value.notices.isEmpty())
        }
    }

    private fun hardLinkRepository(root: File, onMutation: (suspend (FileRepository.Mutation) -> Unit)? = null) =
        FileRepository(root, onMutation = onMutation,
            newFilePublisher = NewProjectFilePublisher { source, target -> Files.createLink(target.toPath(), source.toPath()) })

    private fun stages(root: File): List<File> = root.listFiles()!!.filter { RetainedWorkPolicy.isCandidateName(it.name) }

    private fun fileSnapshot(root: File) = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).invariantSeparatorsPath to
            (ProjectCopyEvidence.identity(it).key to ProjectCopyEvidence.sha256(it.readBytes()))
    }

    private class FailingInput(private val prefix: ByteArray, private val failure: Throwable) : InputStream() {
        private var offset = 0
        var closeCalls = 0
            private set
        override fun read(): Int {
            if (offset == prefix.size) throw failure
            return prefix[offset++].toInt() and 255
        }
        override fun read(bytes: ByteArray, start: Int, length: Int): Int {
            require(start >= 0 && length >= 0 && start <= bytes.size && length <= bytes.size - start)
            if (length == 0) return 0
            if (offset == prefix.size) throw failure
            val count = minOf(length, prefix.size - offset)
            prefix.copyInto(bytes, start, offset, offset + count)
            offset += count
            return count
        }
        override fun close() { closeCalls++ }
    }

    private inline fun withProject(block: (File) -> Unit) {
        val root = Files.createTempDirectory("repository-retained-work-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
