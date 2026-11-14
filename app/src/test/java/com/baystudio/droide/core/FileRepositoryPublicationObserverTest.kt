package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Publication integration regression source; host execution is coordinated separately.
 * Does not execute Android native publication, real provider work, or power-loss testing.
 */
class FileRepositoryPublicationObserverTest {
    private lateinit var root: File
    @Before fun setup() { root = Files.createTempDirectory("stream-observer-test-").toFile() }
    @After fun cleanup() { PathSecurity.deleteTreeNoFollow(root) }

    @Test fun mandatoryAttemptObserverFailurePreventsCanonicalPublication() = runBlocking {
        var published = false
        val repo = FileRepository(root, newFilePublisher = NewProjectFilePublisher { _, _ -> published = true })
        val observer = object : StreamPublicationObserver {
            override fun beforePublish(preparation: StreamPublicationPreparation) { throw IOException("intent force failed") }
            override fun onPublished(receipt: StreamPublicationReceipt) { fail("Commit callback after failed intent") }
        }
        try {
            repo.writeStream("asset.bin", byteArrayOf(1, 2, 3).inputStream(), requireAbsent = true,
                requireExistingParent = true, publicationObserver = observer)
            fail("Expected failed intent")
        } catch (_: IOException) { }
        assertFalse(published)
        assertFalse(root.resolve("asset.bin").exists())
    }

    @Test fun exactReceiptPrecedesSyncAndContainsActualStageBytesIdentity() = runBlocking {
        val bytes = "actual stage bytes".toByteArray()
        val events = mutableListOf<String>()
        var captured: StreamPublicationPreparation? = null
        val repo = FileRepository(root, onMutation = { events += "sync" },
            newFilePublisher = NewProjectFilePublisher { stage, target ->
                events += "publish"
                assertArrayEquals(bytes, stage.readBytes())
                assertEquals(ProjectCopyEvidence.identity(stage).key, captured!!.stageIdentity)
                Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            })
        val observer = object : StreamPublicationObserver {
            override fun beforePublish(preparation: StreamPublicationPreparation) {
                events += "intent"
                captured = preparation
                assertEquals(root.canonicalPath, preparation.canonicalRoot)
                assertEquals(ProjectCopyEvidence.root(root).second, preparation.rootIdentity)
                assertEquals("asset.bin", preparation.relativePath)
                assertEquals(bytes.size.toLong(), preparation.bytes)
                assertEquals(ProjectCopyEvidence.sha256(bytes), preparation.sha256)
            }
            override fun onPublished(receipt: StreamPublicationReceipt) {
                events += "receipt"
                assertEquals(captured, receipt.preparation)
                assertEquals(captured!!.stageIdentity, receipt.destinationIdentity)
                assertEquals(ProjectCopyEvidence.identity(root.resolve("asset.bin")).key, receipt.destinationIdentity)
            }
        }
        assertEquals(bytes.size.toLong(), repo.writeStream("asset.bin", bytes.inputStream(),
            requireAbsent = true, requireExistingParent = true, publicationObserver = observer))
        assertEquals(listOf("intent", "publish", "receipt", "sync"), events)
    }

    @Test fun postpublicationJournalFailureRetainsCanonicalBytesAndReportsUncertainty() = runBlocking {
        val bytes = "preserve published data".toByteArray()
        val repo = FileRepository(root)
        val observer = object : StreamPublicationObserver {
            override fun beforePublish(preparation: StreamPublicationPreparation) { }
            override fun onPublished(receipt: StreamPublicationReceipt) { throw IOException("receipt force failed") }
        }
        try {
            repo.writeStream("asset.bin", bytes.inputStream(), requireAbsent = true,
                requireExistingParent = true, publicationObserver = observer)
            fail("Expected uncertain publication")
        } catch (uncertain: StreamPublicationUncertainException) {
            assertTrue(uncertain.publicationReturned)
            assertEquals("asset.bin", uncertain.relativePath)
            assertEquals(bytes.size.toLong(), uncertain.bytes)
        }
        assertArrayEquals(bytes, root.resolve("asset.bin").readBytes())
    }

    @Test fun zeroReadIsNotEofAndRepeatedZeroReadsAreBounded() = runBlocking {
        val bytes = "complete source".toByteArray()
        val occasional = object : ByteArrayInputStream(bytes) {
            var first = true
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (first) { first = false; return 0 }
                return super.read(buffer, offset, length)
            }
        }
        val repo = FileRepository(root)
        assertEquals(bytes.size.toLong(), repo.writeStream("complete.bin", occasional, requireAbsent = true))
        assertArrayEquals(bytes, root.resolve("complete.bin").readBytes())
        var reads = 0
        val stalled = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { reads++; return 0 }
        }
        try { repo.writeStream("stalled.bin", stalled, requireAbsent = true); fail("Expected stalled-source refusal") }
        catch (_: IOException) { }
        catch (_: IllegalArgumentException) { }
        assertTrue(reads in 1..32)
        assertFalse(root.resolve("stalled.bin").exists())
    }

    @Test fun publisherThatChangesCanonicalPathThenThrowsIsReportedAsUncertain() = runBlocking {
        var committed = false
        val repo = FileRepository(root, newFilePublisher = NewProjectFilePublisher { stage, target ->
            Files.move(stage.toPath(), target.toPath())
            throw IOException("publication response lost")
        })
        val observer = object : StreamPublicationObserver {
            override fun beforePublish(preparation: StreamPublicationPreparation) { }
            override fun onPublished(receipt: StreamPublicationReceipt) { committed = true }
        }
        try { repo.writeStream("asset.bin", byteArrayOf(9).inputStream(), requireAbsent = true,
            publicationObserver = observer); fail("Expected uncertain publication") }
        catch (failure: StreamPublicationUncertainException) { assertFalse(failure.publicationReturned) }
        assertFalse(committed)
        assertArrayEquals(byteArrayOf(9), root.resolve("asset.bin").readBytes())
    }

    @Test fun dataForceFailurePreventsAdmissionAndPublication() = runBlocking {
        var admitted = false
        val failure = IOException("data force failed")
        val repo = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform,
            streamDurability = object : StreamPublicationDurability {
                override fun forceFile(channel: java.nio.channels.FileChannel) { throw failure }
                override fun forceDirectory(directory: File) { fail("No parent force before successful data force") }
            })
        val observer = object : StreamPublicationObserver {
            override fun beforePublish(preparation: StreamPublicationPreparation) { admitted = true }
            override fun onPublished(receipt: StreamPublicationReceipt) { fail("No committed receipt") }
        }
        try { repo.writeStream("asset.bin", byteArrayOf(1).inputStream(), requireAbsent = true,
            publicationObserver = observer); fail("Expected failed data durability") }
        catch (actual: IOException) { assertSame(failure, actual) }
        assertFalse(admitted)
        assertFalse(root.resolve("asset.bin").exists())
        assertRetainedProposal(repo, byteArrayOf(1))
    }

    @Test fun parentForceFailurePreservesCanonicalDataAndWithholdsCommitReceipt() = runBlocking {
        var committed = false
        val repo = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform,
            streamDurability = object : StreamPublicationDurability {
                override fun forceFile(channel: java.nio.channels.FileChannel) = channel.force(true)
                override fun forceDirectory(directory: File) { throw IOException("parent force failed") }
            })
        val observer = object : StreamPublicationObserver {
            override fun beforePublish(preparation: StreamPublicationPreparation) { }
            override fun onPublished(receipt: StreamPublicationReceipt) { committed = true }
        }
        try { repo.writeStream("asset.bin", byteArrayOf(7).inputStream(), requireAbsent = true,
            publicationObserver = observer); fail("Expected uncertain publication") }
        catch (failure: StreamPublicationUncertainException) { assertTrue(failure.publicationReturned) }
        assertFalse(committed)
        assertArrayEquals(byteArrayOf(7), root.resolve("asset.bin").readBytes())
    }

    @Test fun lateAdmissionGuardCanCancelAfterCopyBeforeJournalOrPublication() = runBlocking {
        var beforePublish = false
        val observer = object : StreamPublicationObserver {
            override fun beforePublish(preparation: StreamPublicationPreparation) { beforePublish = true }
            override fun onPublished(receipt: StreamPublicationReceipt) { fail("No committed receipt") }
        }
        val repository = FileRepository(root)
        val stopped = CancellationException("stale run")
        try { repository.writeStream("asset.bin", byteArrayOf(2).inputStream(), requireAbsent = true,
            publicationObserver = observer, beforePublication = { throw stopped })
            fail("Expected cancellation") } catch (actual: CancellationException) { assertSame(stopped, actual) }
        assertFalse(beforePublish)
        assertFalse(root.resolve("asset.bin").exists())
        assertRetainedProposal(repository, byteArrayOf(2))
    }

    @Test fun sourceCloseFailureDoesNotEraseAlreadyPublishedReceipt() = runBlocking {
        var receipt: StreamPublicationReceipt? = null
        val input = object : ByteArrayInputStream(byteArrayOf(3)) {
            override fun close() { throw IOException("source close failed") }
        }
        val observer = object : StreamPublicationObserver {
            override fun beforePublish(preparation: StreamPublicationPreparation) { }
            override fun onPublished(value: StreamPublicationReceipt) { receipt = value }
        }
        try { FileRepository(root).writeStream("asset.bin", input, requireAbsent = true,
            publicationObserver = observer); fail("Expected close failure") } catch (_: IOException) { }
        assertNotNull(receipt)
        assertArrayEquals(byteArrayOf(3), root.resolve("asset.bin").readBytes())
    }

    @Test fun replacingStageNameDuringCopyNeverLetsCleanupDeleteForeignData() = runBlocking {
        val bytes = ByteArray(5 * 1024 * 1024) { 11 }
        var replacement: File? = null
        val moved = root.resolve("externally-retained-stage.bin")
        try {
            FileRepository(root).writeStream("asset.bin", bytes.inputStream(), requireAbsent = true,
                onProgress = { progress ->
                    if (progress.phase == StreamWritePhase.COPYING && progress.completedBytes > 0 && replacement == null) {
                        val stage = root.listFiles()!!.single { it.name.startsWith(".droide-tmp-") }
                        Files.move(stage.toPath(), moved.toPath())
                        stage.writeText("foreign replacement must survive")
                        replacement = stage
                        throw CancellationException("cancel after external stage replacement")
                    }
                })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertNotNull(replacement)
        assertEquals("foreign replacement must survive", replacement!!.readText())
        assertTrue(moved.isFile)
        assertFalse(root.resolve("asset.bin").exists())
    }
    private fun assertRetainedProposal(repository: FileRepository, bytes: ByteArray) {
        val stage = root.listFiles()!!.single { it.name.startsWith(".droide-tmp-") }
        assertArrayEquals(bytes, stage.readBytes())
        assertEquals(listOf(stage.name), root.listFiles()!!.map { it.name })
        val notice = repository.retainedWork.value.notices.single()
        assertEquals("asset.bin", notice.target)
        assertEquals(stage.name, notice.candidate.path)
        assertEquals(RetainedWorkKind.PRESENT, notice.candidate.kind)
        assertEquals(bytes.size.toLong(), notice.candidate.bytes)
        assertFalse(notice.historicalWriteReturned)
    }

}
