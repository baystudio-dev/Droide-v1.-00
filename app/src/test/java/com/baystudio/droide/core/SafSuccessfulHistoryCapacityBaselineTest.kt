package com.baystudio.droide.core

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Existing R23 APIs only. Four one-byte modeled writes, a three-operation cap, and cold reopen.
 * Uses fixture-owned host files/forces; no ContentResolver, provider, device, native or process work.
 * This source is staged, not executed. The expected old-source failure is the fourth begin.
 */
class SafSuccessfulHistoryCapacityBaselineTest {
    private val roots = arrayListOf<File>()

    @After fun cleanup() { roots.asReversed().forEach { PathSecurity.deleteTreeNoFollow(it) } }

    @Test(timeout = 30_000) fun fourCommittedWritesDoNotExhaustThreeSuccessfulHistorySlots(): Unit = runBlocking {
        val root = Files.createTempDirectory("saf-successful-history-capacity-").toFile().also { roots += it }
        val workspace = root.resolve("workspace").apply { check(mkdir()) }
        val directory = root.resolve("journal")
        val manifest = workspace.resolve(".droide/saf_manifest.json")
        val value = workspace.resolve("value.bin").apply { writeBytes(byteArrayOf(0)) }
        val sentinelBytes = byteArrayOf(0, -1, 127, 10, 42)
        val sentinel = workspace.resolve("sentinel.bin").apply { writeBytes(sentinelBytes) }
        val intended = root.resolve("intended.bin")
        val treeUri = "content://fixture/tree/root"
        val limits = SafJournalLimits(
            operations = 3,
            indexBytes = 64L * 1024,
            recordBytes = 16L * 1024,
            manifestBytes = 4L * 1024,
            aggregateMetadataBytes = 2L * 1024 * 1024,
            aggregatePayloadBytes = 2L * 1024 * 1024,
            actionsPerOperation = 1,
            evidenceFilesPerOperation = 2,
        )
        val reservation = SafJournalReservation(
            actions = 1,
            metadataBytes = 64L * 1024,
            payloadBytes = 64L * 1024,
            indexBytes = 8L * 1024,
            evidenceFiles = 2,
        )
        var manifestMoves = 0
        var modeledWrites = 0
        val storage = SafOperationStorage(
            forceDirectory = { file ->
                FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
            },
            forceFile = { file ->
                FileChannel.open(file.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
            },
            replaceAtomically = { source, destination ->
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                if (destination == manifest) manifestMoves++
                Unit
            },
        )
        val parent = SafOwnedDocument("$treeUri/document/root", "root", "root", SafOperationJournal.DIRECTORY_MIME, "tree")
        val document = SafOwnedDocument("$treeUri/document/value", "value", "value.bin", "application/octet-stream", "root")
        check(manifest.parentFile!!.mkdir())
        manifest.writeBytes(SafJournalCodec.encode(SafLegacyTrustedManifest.serializer(),
            SafLegacyTrustedManifest(entries = sortedMapOf(
                "sentinel.bin" to "f:${sentinelBytes.safDigest()}",
                "value.bin" to "f:${byteArrayOf(0).safDigest()}",
            ))))

        WorkspaceFileMutationLocks.withRoot(workspace) { guard ->
            val poll = { guard.requireRoot(workspace) }
            SafOperationJournal.create(workspace, treeUri, directory, storage, limits)
            repeat(4) { ordinal ->
                val journal = SafOperationJournal.open(workspace, treeUri, directory, storage, limits)
                val beforeValue = value.readBytes()
                val beforeManifest = manifest.readBytes()
                val next = byteArrayOf((ordinal + 1).toByte())
                intended.writeBytes(next)
                val failure = runCatching {
                    val operation = journal.begin(
                        SafOperationRequest(SafOperationKind.WRITE, listOf("value.bin")),
                        listOf("value.bin"), reservation, poll,
                        id = "00000000-0000-0000-0000-${(ordinal + 1).toString().padStart(12, '0')}",
                    )
                    val previous = journal.preserve(operation, value, poll)
                    val desired = journal.preserve(operation, intended, poll)
                    val action = journal.prepare(operation, SafEffectPlan(
                        SafEffectKind.WRITE_FILE, "value.bin", document, parent, null, previous, desired,
                    ), poll)
                    journal.attempting(action, poll)
                    value.writeBytes(next) // Deterministic host-file model of one returned provider write.
                    modeledWrites++
                    journal.returned(action, SafEffectReturn.COMPLETED, SafProviderReturn(SafProviderReturnShape.VOID), poll)
                    journal.verified(action, document, poll)
                    val publisher = SafDurableManifestCommit(journal)
                    publisher.plan(operation, mapOf("value.bin" to "f:${next.safDigest()}"), poll)
                    val result = publisher.publish(operation, poll)
                    assertTrue("Write ${ordinal + 1} must have an acknowledged manifest", result.acknowledged)
                    assertNull(result.retainedAcknowledgementFailure)
                }.exceptionOrNull()
                if (failure != null) {
                    assertArrayEquals("A rejected admission changed modeled provider content", beforeValue, value.readBytes())
                    assertArrayEquals("A rejected admission changed the visible manifest", beforeManifest, manifest.readBytes())
                }
                assertNull("Successful history alone exhausted capacity before write ${ordinal + 1}: $failure", failure)

                val reopened = SafOperationJournal.open(workspace, treeUri, directory, storage, limits)
                val summaries = reopened.summaries(poll)
                assertTrue("No uncertain operation was created", summaries.all { it.committed && !it.abandoned })
                assertTrue("Retained operation count exceeded the injected hard cap", summaries.size <= limits.operations)
                val trusted = SafDurableManifestCommit(reopened).trusted(poll)
                val image = SafJournalCodec.decode(SafManifestImage.serializer(), manifest.readBytes())
                assertEquals((ordinal + 1).toLong(), image.revision)
                assertEquals(image.revision, trusted.revision)
                assertEquals(image.entries, trusted.entries)
                assertArrayEquals(manifest.readBytes(), trusted.bytes)
                assertEquals("f:${next.safDigest()}", image.entries["value.bin"])
                assertArrayEquals(next, value.readBytes())
                assertArrayEquals(sentinelBytes, sentinel.readBytes())
            }
        }
        assertEquals(4, modeledWrites)
        assertEquals(4, manifestMoves)
        assertArrayEquals(sentinelBytes, sentinel.readBytes())
    }
}
