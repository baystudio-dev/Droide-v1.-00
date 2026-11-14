package com.baystudio.droide.core

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real encrypted filesystem lifecycle; host AES-GCM, not AndroidKeyStore/device certification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorStateStoreEvidenceLifecycleTest {
    @Test fun sameLiveContentRepairsRecoveryAndPreservesDamagedCiphertext() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "live content")))
        val old = f.store.load()!!.deferredBuffers.getValue("a.txt")
        val blob = File(f.blobs, old.blobName)
        val damaged = blob.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        blob.writeBytes(damaged)
        f.store.save(snapshot(dirty = mapOf("a.txt" to "live content")))
        val reopened = EditorStateStore(f.context, f.id, f.cipher, f.directorySync)
        assertEquals("live content", reopened.load()!!.deferredBuffers.getValue("a.txt").readText())
        assertArrayEquals(damaged, blob.readBytes())
        assertEquals(1, f.manifest().getValue("preserved").jsonArray.size)
    } }

    @Test fun changedLiveContentDoesNotDiscardDamagedPreviousBlob() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "old text")))
        val blob = File(f.blobs, f.store.load()!!.deferredBuffers.getValue("a.txt").blobName)
        val damaged = byteArrayOf(1, 2, 3)
        blob.writeBytes(damaged)
        f.store.save(snapshot(dirty = mapOf("a.txt" to "new live text")))
        assertEquals("new live text", f.store.load()!!.deferredBuffers.getValue("a.txt").readText())
        assertTrue("Previous ciphertext must remain", blob.isFile)
        assertArrayEquals(damaged, blob.readBytes())
    } }

    @Test fun clearPreservesDamagedEvidenceButAllowsEmptyWorkspacePersistence() = fixture { f -> runBlocking {
        f.store.save(snapshot(conflicts = mapOf("a.txt" to "retained text")))
        val blob = File(f.blobs, f.store.load()!!.deferredConflictBuffers.getValue("a.txt").blobName)
        blob.writeBytes(byteArrayOf(4, 5, 6))
        f.store.clear()
        assertTrue("Clear must not remove unreadable evidence", blob.isFile)
        val empty = EditorStateStore(f.context, f.id, f.cipher, f.directorySync).load()!!
        assertTrue(empty.dirtyPaths.isEmpty()); assertTrue(empty.conflictPaths.isEmpty())
        f.store.clear()
        assertArrayEquals(byteArrayOf(4, 5, 6), blob.readBytes())
    } }

    @Test fun orphanCiphertextSurvivesSaveAndClear() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "healthy")))
        val orphan = File(f.blobs, "${UUID.randomUUID()}.bin")
        orphan.writeBytes(byteArrayOf(7, 8, 9))
        f.store.save(snapshot(dirty = mapOf("a.txt" to "healthy")))
        assertTrue("Unknown orphan cannot be swept", orphan.isFile)
        f.store.clear()
        assertArrayEquals(byteArrayOf(7, 8, 9), orphan.readBytes())
    } }

    @Test fun conflictRepairAndDroppedCorruptPathsSurviveFutureSaves() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "live"), conflicts = mapOf("b.txt" to "older")))
        val loaded = f.store.load()!!
        val oldA = File(f.blobs, loaded.deferredBuffers.getValue("a.txt").blobName).apply { writeBytes(byteArrayOf(1)) }
        val oldB = File(f.blobs, loaded.deferredConflictBuffers.getValue("b.txt").blobName).apply { writeBytes(byteArrayOf(2)) }
        f.store.save(snapshot(conflicts = mapOf("b.txt" to "older")))
        assertEquals("older", f.store.load()!!.deferredConflictBuffers.getValue("b.txt").readText())
        assertTrue(oldA.exists()); assertTrue(oldB.exists())
        f.store.save(snapshot(dirty = mapOf("next.txt" to "next")))
        f.store.clear()
        val reopened = EditorStateStore(f.context, f.id, f.cipher, f.directorySync)
        assertTrue(reopened.load()!!.dirtyPaths.isEmpty())
        reopened.clear()
        assertEquals(2, f.manifest().getValue("preserved").jsonArray.size)
        assertArrayEquals(byteArrayOf(1), oldA.readBytes()); assertArrayEquals(byteArrayOf(2), oldB.readBytes())
    } }

    @Test fun checksumMismatchAndEmptyCiphertextCanBeRepairedWithoutUnlink() = fixture { f -> runBlocking {
        for (path in listOf("wrong.txt", "empty.txt")) {
            f.store.save(snapshot(dirty = mapOf(path to "expected")))
            val old = File(f.blobs, f.store.load()!!.deferredBuffers.getValue(path).blobName)
            val evidence = if (path == "empty.txt") byteArrayOf()
                else f.cipher.encrypt("different".toByteArray(), "${f.id}\u0000$path".toByteArray())
            old.writeBytes(evidence)
            f.store.save(snapshot(dirty = mapOf(path to "expected")))
            assertEquals("expected", f.store.load()!!.deferredBuffers.getValue(path).readText())
            assertArrayEquals(evidence, old.readBytes())
        }
        assertEquals(2, f.manifest().getValue("preserved").jsonArray.size)
    } }

    @Test fun healthyRetirementStillCleansAndDeferredRetentionStaysLazy() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "old")))
        val old = f.store.load()!!.deferredBuffers.getValue("a.txt")
        val before = f.cipher.decryptions
        f.store.save(snapshot(deferred = mapOf("a.txt" to old)))
        assertEquals("Only the manifest is decrypted for retained known-hash references", before + 1, f.cipher.decryptions)
        f.store.save(snapshot(dirty = mapOf("a.txt" to "new")))
        assertFalse(File(f.blobs, old.blobName).exists())
        assertTrue(f.manifest().getValue("preserved").jsonArray.isEmpty())
        f.store.clear()
        assertFalse(f.file.exists())
        assertTrue(!f.blobs.exists() || f.blobs.listFiles()!!.isEmpty())
    } }

    @Test fun malformedLedgerNeverPermitsLoadSaveOrClearCleanup() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "safe")))
        val orphan = File(f.blobs, "${UUID.randomUUID()}.bin").apply { writeBytes(byteArrayOf(9)) }
        f.store.save(snapshot(dirty = mapOf("a.txt" to "safe")))
        val good = f.manifest(); val entry = good.getValue("preserved").jsonArray.single().jsonObject
        val corruptions = listOf(
            JsonObject(good.toMutableMap().apply { remove("preserved") }),
            JsonObject(good.toMutableMap().apply { put("version", JsonPrimitive(6)) }),
            JsonObject(good.toMutableMap().apply { put("preserved", JsonArray(listOf(entry, entry))) }),
            *listOf("bytes" to JsonPrimitive(-1), "sha256" to JsonPrimitive("not-a-sha256"),
                "blob" to JsonPrimitive("../escape.bin"), "reason" to JsonPrimitive("unknown"),
                "path" to JsonPrimitive("invented.txt"), "max_bytes" to JsonPrimitive(1)).map { (key, value) ->
                JsonObject(good.toMutableMap().apply {
                    put("preserved", JsonArray(listOf(JsonObject(entry.toMutableMap().apply { put(key, value) }))))
                })
            }.toTypedArray(),
        )
        val payloads = f.blobs.listFiles()!!.associate { it.name to it.readBytes() }
        for (bad in corruptions) {
            f.writeManifest(bad); val manifest = f.file.readBytes()
            assertTrue(runCatching { f.store.load() }.isFailure)
            assertTrue(runCatching { f.store.save(snapshot()) }.isFailure)
            assertTrue(runCatching { f.store.clear() }.isFailure)
            assertArrayEquals(manifest, f.file.readBytes())
            payloads.forEach { (name, bytes) -> assertArrayEquals(bytes, File(f.blobs, name).readBytes()) }
        }
        assertTrue(orphan.exists())
    } }

    @Test fun symlinkedPreservedEvidenceFailsBeforeDeletingOtherData() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "safe")))
        val orphan = File(f.blobs, "${UUID.randomUUID()}.bin").apply { writeBytes(byteArrayOf(1)) }
        f.store.save(snapshot(dirty = mapOf("a.txt" to "safe")))
        val manifest = f.file.readBytes()
        val live = f.store.load()!!.deferredBuffers.getValue("a.txt")
        Files.delete(orphan.toPath())
        val foreign = File(f.files, "foreign").apply { writeBytes(byteArrayOf(1)) }
        Files.createSymbolicLink(orphan.toPath(), foreign.toPath())
        assertTrue(runCatching { f.store.save(snapshot()) }.isFailure)
        assertTrue(runCatching { f.store.load() }.isFailure)
        assertArrayEquals(manifest, f.file.readBytes()); assertTrue(File(f.blobs, live.blobName).exists())
        assertArrayEquals(byteArrayOf(1), foreign.readBytes()); assertTrue(Files.isSymbolicLink(orphan.toPath()))
    } }

    @Test fun orphanEntryAndCiphertextBudgetsFailWithoutPublication() {
        for (byteLimit in listOf(false, true)) fixture { f -> runBlocking {
            f.store.save(snapshot(dirty = mapOf("a.txt" to "safe")))
            val manifest = f.file.readBytes()
            if (byteLimit) repeat(4) {
                RandomAccessFile(File(f.blobs, "${UUID.randomUUID()}.bin"), "rw").use { it.setLength(21L * 1024 * 1024) }
            } else repeat(257) { File(f.blobs, "${UUID.randomUUID()}.bin").writeBytes(byteArrayOf(1)) }
            val names = f.blobs.list()!!.toSet()
            assertTrue(runCatching { f.store.save(snapshot(dirty = mapOf("a.txt" to "next"))) }.isFailure)
            assertTrue(runCatching { f.store.clear() }.isFailure)
            assertArrayEquals(manifest, f.file.readBytes()); assertEquals(names, f.blobs.list()!!.toSet())
        } }
    }

    @Test fun fullLedgerStillAllowsNormalClearAndSubsequentSave() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "safe")))
        repeat(256) { File(f.blobs, "${UUID.randomUUID()}.bin").writeBytes(byteArrayOf(1)) }
        f.store.save(snapshot(dirty = mapOf("a.txt" to "safe")))
        f.store.clear()
        assertEquals(256, f.blobs.listFiles()!!.size)
        f.store.save(snapshot(dirty = mapOf("b.txt" to "next")))
        assertEquals("next", f.store.load()!!.deferredBuffers.getValue("b.txt").readText())
        assertEquals(256, f.manifest().getValue("preserved").jsonArray.size)
    } }

    @Test fun legacyFormatsMigrateAndOversizedV4RetirementDoesNotReadPayloads() {
        for (version in 1..6) fixture { f -> runBlocking {
            f.root.mkdirs(); f.blobs.mkdirs()
            if (version <= 3) f.writeManifest(buildJsonObject {
                put("version", version); put("active", "a.txt"); put("open", buildJsonArray { add("a.txt") })
                put("dirty", buildJsonArray { add(buildJsonObject { put("path", "a.txt"); put("content", "legacy") }) })
            }) else {
                f.store.save(snapshot(dirty = mapOf("a.txt" to "legacy")))
                f.writeManifest(JsonObject(f.manifest().toMutableMap().apply {
                    put("version", JsonPrimitive(version)); remove("preserved"); remove("retiring")
                    if (version == 4) remove("conflicts")
                }))
            }
            val old = f.store.load()!!
            f.store.save(old)
            assertEquals(7, f.manifest().getValue("version").jsonPrimitive.int)
            assertEquals("legacy", f.store.load()!!.deferredBuffers.getValue("a.txt").readText())
        } }
        fixture { f -> runBlocking {
            f.root.mkdirs(); f.blobs.mkdirs()
            val entries = (1..10).map { index ->
                val name = "${UUID.randomUUID()}.bin"
                RandomAccessFile(File(f.blobs, name), "rw").use { it.setLength(20L * 1024 * 1024) }
                buildJsonObject { put("path", "$index.txt"); put("blob", name); put("sha256", "0".repeat(64)) }
            }
            f.writeManifest(buildJsonObject { put("version", 4); put("dirty", JsonArray(entries)) })
            val before = f.cipher.decryptions; val manifest = f.file.readBytes()
            assertTrue(runCatching { f.store.clear() }.isFailure)
            assertEquals(before + 1, f.cipher.decryptions)
            assertArrayEquals(manifest, f.file.readBytes()); assertEquals(10, f.blobs.listFiles()!!.size)
        } }
    }

    @Test fun cancellationDuringVerificationCannotBecomePreservationOrCleanup() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "old")))
        val manifest = f.file.readBytes(); val names = f.blobs.list()!!.toSet()
        f.cipher.beforeDecrypt = { aad -> if (0.toByte() in aad) throw kotlinx.coroutines.CancellationException("cancel verification") }
        val failure = runCatching { f.store.save(snapshot(dirty = mapOf("a.txt" to "new"))) }.exceptionOrNull()
        f.cipher.beforeDecrypt = null
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertArrayEquals(manifest, f.file.readBytes()); assertEquals(names, f.blobs.list()!!.toSet())
    } }

    @Test fun blobAndManifestFailuresRetainStagedGenerationsForColdRetry() {
        for (fault in listOf("blob-sync", "manifest-encrypt", "manifest-sync")) fixture { f -> runBlocking {
            f.store.save(snapshot(dirty = mapOf("a.txt" to "old")))
            val oldManifest = f.file.readBytes(); val oldBlob = f.blobs.listFiles()!!.single()
            var rootSyncs = 0
            f.beforeSync = { dir ->
                if (fault == "blob-sync" && dir == f.blobs) throw java.io.IOException("blob sync")
                if (dir == f.root && ++rootSyncs == 2 && fault == "manifest-sync") throw java.io.IOException("manifest sync")
            }
            f.cipher.beforeEncrypt = { aad -> if (fault == "manifest-encrypt" && aad.contentEquals(f.id.toByteArray())) throw java.io.IOException("manifest encrypt") }
            assertTrue(runCatching { f.store.save(snapshot(dirty = mapOf("a.txt" to "new"))) }.isFailure)
            f.beforeSync = null; f.cipher.beforeEncrypt = null
            assertTrue(oldBlob.exists()); assertEquals(2, f.blobs.listFiles()!!.size)
            if (fault != "manifest-sync") assertArrayEquals(oldManifest, f.file.readBytes())
            val reopened = EditorStateStore(f.context, f.id, f.cipher, f.directorySync)
            reopened.save(snapshot(dirty = mapOf("a.txt" to "new")))
            assertEquals("new", reopened.load()!!.deferredBuffers.getValue("a.txt").readText())
            assertEquals(if (fault == "manifest-sync") 0 else 1, f.manifest().getValue("preserved").jsonArray.size)
        } }
    }

    @Test fun changedRetirementCandidateIsNotDeletedAndBecomesAccountedOnRetry() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "old")))
        val old = f.blobs.listFiles()!!.single(); var roots = 0
        f.beforeSync = { dir -> if (dir == f.root && ++roots == 2) old.writeBytes(byteArrayOf(77)) }
        assertTrue(runCatching { f.store.save(snapshot(dirty = mapOf("a.txt" to "new"))) }.isFailure)
        f.beforeSync = null
        assertArrayEquals(byteArrayOf(77), old.readBytes())
        val reopened = EditorStateStore(f.context, f.id, f.cipher, f.directorySync)
        assertEquals("new", reopened.load()!!.deferredBuffers.getValue("a.txt").readText())
        reopened.save(snapshot(dirty = mapOf("a.txt" to "new")))
        assertEquals(1, f.manifest().getValue("preserved").jsonArray.size)
        assertArrayEquals(byteArrayOf(77), old.readBytes())
    } }

    @Test fun clearSyncFailureLeavesPayloadsAndRetryCompletesRetirement() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "old")))
        val old = f.blobs.listFiles()!!.single(); val bytes = old.readBytes()
        f.beforeSync = { dir -> if (dir == f.root) throw java.io.IOException("clear sync") }
        assertTrue(runCatching { f.store.clear() }.isFailure)
        f.beforeSync = null
        assertArrayEquals(bytes, old.readBytes())
        f.store.clear()
        assertNull(f.store.load()); assertFalse(old.exists())
    } }

    @Test fun invalidDeferredBaselineIsRejectedBeforeAnyFreshBlobWrite() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "old")))
        val old = f.store.load()!!.deferredBuffers.getValue("a.txt")
        val manifest = f.file.readBytes(); val names = f.blobs.list()!!.toSet()
        val invalid = EditorRecoverySnapshot(emptyList(), "", mapOf("new.txt" to "new"),
            deferredConflictBuffers = mapOf("a.txt" to old), conflictDiskVersions = mapOf("a.txt" to DiskFileVersion.fromText("wrong baseline")))
        repeat(3) { assertTrue(runCatching { f.store.save(invalid) }.isFailure) }
        assertArrayEquals(manifest, f.file.readBytes()); assertEquals(names, f.blobs.list()!!.toSet())
    } }

    @Test fun separateStoreInstancesShareTheInProcessIoGate() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "old")))
        val other = EditorStateStore(f.context, f.id, f.cipher, f.directorySync)
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        f.cipher.beforeEncrypt = { aad -> if (0.toByte() in aad) {
            entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } }
        val writer = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
            f.store.save(snapshot(dirty = mapOf("a.txt" to "new")))
        }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertNull("Another store instance cannot admit retirement during this project's IO", other.tryRetireWrites())
        } finally { release.countDown(); f.cipher.beforeEncrypt = null }
        writer.await()
        assertEquals("new", other.load()!!.deferredBuffers.getValue("a.txt").readText())
    } }

    @Test fun maximumUnpublishedBatchCanBeRetriedWithoutLosingAnyCiphertext() = fixture { f -> runBlocking {
        val buffers = (1..256).associate { "$it.txt" to "live-$it" }
        f.cipher.beforeEncrypt = { aad -> if (aad.contentEquals(f.id.toByteArray())) throw java.io.IOException("before manifest") }
        assertTrue(runCatching { f.store.save(snapshot(dirty = buffers)) }.isFailure)
        f.cipher.beforeEncrypt = null
        assertFalse(f.file.exists()); assertEquals(256, f.blobs.listFiles()!!.size)
        val staged = f.blobs.listFiles()!!.associate { it.name to it.readBytes() }
        val reopened = EditorStateStore(f.context, f.id, f.cipher, f.directorySync)
        reopened.save(snapshot(dirty = buffers))
        val loaded = reopened.load()!!
        for ((path, content) in buffers) assertEquals(content, loaded.deferredBuffers.getValue(path).readText())
        assertEquals(256, f.manifest().getValue("preserved").jsonArray.size)
        reopened.clear()
        staged.forEach { (name, bytes) -> assertArrayEquals(bytes, File(f.blobs, name).readBytes()) }
        assertTrue(reopened.load()!!.dirtyPaths.isEmpty())
    } }

    @Test fun maximumHealthyRetirementBatchResumesFromDurableReceipts() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = (1..256).associate { "$it.txt" to "live-$it" }))
        var roots = 0
        f.beforeSync = { dir -> if (dir == f.root && ++roots == 2) throw java.io.IOException("after receipt publication") }
        assertTrue(runCatching { f.store.save(snapshot()) }.isFailure)
        f.beforeSync = null
        assertEquals(256, f.blobs.listFiles()!!.size)
        assertEquals(256, f.manifest().getValue("retiring").jsonArray.size)
        EditorStateStore(f.context, f.id, f.cipher, f.directorySync).clear()
        assertFalse(f.file.exists()); assertTrue(!f.blobs.exists() || f.blobs.listFiles()!!.isEmpty())
    } }

    @Test fun receiptValidationRejectsTamperingAndActiveOverlapBeforeCleanup() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "old")))
        f.store.save(snapshot(dirty = mapOf("a.txt" to "new")))
        val good = f.manifest(); val entry = good.getValue("retiring").jsonArray.single().jsonObject
        val active = good.getValue("dirty").jsonArray.single().jsonObject.getValue("blob")
        val badRoots = mutableListOf(JsonObject(good.toMutableMap().apply { remove("retiring") }))
        for (bad in listOf(
            JsonArray(listOf(entry, entry)),
            JsonArray(listOf(JsonObject(entry.toMutableMap().apply { put("bytes", JsonPrimitive(0)) }))),
            JsonArray(listOf(JsonObject(entry.toMutableMap().apply { put("sha256", JsonPrimitive("bad")) }))),
            JsonArray(listOf(JsonObject(entry.toMutableMap().apply { put("blob", active) }))),
        )) badRoots += JsonObject(good.toMutableMap().apply { put("retiring", bad) })
        val payloads = f.blobs.listFiles()!!.associate { it.name to it.readBytes() }
        for (bad in badRoots) {
            f.writeManifest(bad); val bytes = f.file.readBytes()
            assertTrue(runCatching { f.store.load() }.isFailure)
            assertTrue(runCatching { f.store.save(snapshot()) }.isFailure)
            assertTrue(runCatching { f.store.clear() }.isFailure)
            assertArrayEquals(bytes, f.file.readBytes())
            payloads.forEach { (name, content) -> assertArrayEquals(content, File(f.blobs, name).readBytes()) }
        }
    } }

    @Test fun missingRetirementLeafStillRequiresDirectorySyncBeforeReceiptIsForgotten() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "old")))
        f.store.save(snapshot(dirty = mapOf("a.txt" to "new")))
        val before = f.file.readBytes(); val names = f.blobs.list()!!.toSet()
        f.beforeSync = { dir -> if (dir == f.blobs) throw java.io.IOException("retirement directory sync") }
        assertTrue(runCatching { f.store.save(snapshot(dirty = mapOf("a.txt" to "new"))) }.isFailure)
        f.beforeSync = null
        assertArrayEquals(before, f.file.readBytes()); assertEquals(names, f.blobs.list()!!.toSet())
        f.store.save(snapshot(dirty = mapOf("a.txt" to "new")))
        assertTrue(f.manifest().getValue("retiring").jsonArray.isEmpty())
    } }

    @Test fun failedClearPublicationMustBeSyncedBeforeRetryCanRetireItsPayload() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "old")))
        val blob = f.blobs.listFiles()!!.single(); val bytes = blob.readBytes(); var roots = 0
        f.beforeSync = { dir -> if (dir == f.root && ++roots == 2) throw java.io.IOException("clear publication sync") }
        assertTrue(runCatching { f.store.clear() }.isFailure)
        assertArrayEquals(bytes, blob.readBytes())
        f.beforeSync = { dir -> if (dir == f.root) throw java.io.IOException("cannot confirm prior publication") }
        assertTrue(runCatching { f.store.clear() }.isFailure)
        assertArrayEquals(bytes, blob.readBytes())
        f.beforeSync = null
        f.store.clear(); assertFalse(blob.exists()); assertFalse(f.file.exists())
    } }

    @Test fun malformedLegacyInlineEvidenceIsNotOverwrittenOrCleared() {
        for (version in 1..3) fixture { f -> runBlocking {
            f.root.mkdirs(); f.blobs.mkdirs()
            val valid = buildJsonObject { put("path", "a.txt"); put("content", "retained") }
            for (entries in listOf(JsonArray(listOf(buildJsonObject { put("path", "a.txt") })), JsonArray(listOf(valid, valid)))) {
                f.writeManifest(buildJsonObject { put("version", version); put("dirty", entries) })
                val original = f.file.readBytes()
                assertTrue(runCatching { f.store.load() }.isFailure)
                assertTrue(runCatching { f.store.save(snapshot(dirty = mapOf("next.txt" to "new"))) }.isFailure)
                assertTrue(runCatching { f.store.clear() }.isFailure)
                assertArrayEquals(original, f.file.readBytes()); assertTrue(f.blobs.listFiles()!!.isEmpty())
            }
        } }
    }

    @Test fun hierarchyAdmissionSyncsAncestorsAndRetriesBeforePublishingPayloads() = fixture { f -> runBlocking {
        f.beforeSync = { dir -> if (dir == f.files) throw java.io.IOException("ancestor sync") }
        assertTrue(runCatching { f.store.save(snapshot(dirty = mapOf("a.txt" to "live"))) }.isFailure)
        assertFalse(f.file.exists()); assertTrue(f.blobs.listFiles()!!.isEmpty())
        f.beforeSync = null; f.syncedDirectories.clear()
        f.store.save(snapshot(dirty = mapOf("a.txt" to "live")))
        val order = f.syncedDirectories.toList()
        assertTrue(order.indexOf(f.files) < order.indexOf(f.root.parentFile))
        assertTrue(order.indexOf(f.root.parentFile) < order.indexOf(f.root))
        assertTrue(order.indexOf(f.root) < order.indexOf(f.blobs))
        assertEquals("live", f.store.load()!!.deferredBuffers.getValue("a.txt").readText())
    } }

    @Test fun admittedEvidenceCurrentAndPendingGenerationsFitRetryInspectionBudget() = fixture { f -> runBlocking {
        val size = 20 * 1024 * 1024 - 1024
        val old = "x".repeat(size); val current = "y".repeat(size)
        f.store.save(snapshot(dirty = mapOf("a.txt" to old, "b.txt" to old)))
        repeat(3) { RandomAccessFile(File(f.blobs, "${UUID.randomUUID()}.bin"), "rw").use { file -> file.setLength(21L * 1024 * 1024) } }
        f.store.save(snapshot(dirty = mapOf("a.txt" to old, "b.txt" to old)))
        var roots = 0
        f.beforeSync = { dir -> if (dir == f.root && ++roots == 2) throw java.io.IOException("after new manifest") }
        assertTrue(runCatching { f.store.save(snapshot(dirty = mapOf("a.txt" to current, "b.txt" to current))) }.isFailure)
        f.beforeSync = null
        // 63 MiB evidence + about40 MiB current + about40 MiB retirement is valid,
        // and must not be trapped by a smaller combined inspection budget.
        assertEquals(2, f.manifest().getValue("retiring").jsonArray.size)
        val reopened = EditorStateStore(f.context, f.id, f.cipher, f.directorySync)
        reopened.save(snapshot(dirty = mapOf("a.txt" to current, "b.txt" to current)))
        assertEquals(current, reopened.load()!!.deferredBuffers.getValue("a.txt").readText())
        assertTrue(f.manifest().getValue("retiring").jsonArray.isEmpty())
        assertEquals(3, f.manifest().getValue("preserved").jsonArray.size)
        reopened.clear()
        assertTrue(reopened.load()!!.dirtyPaths.isEmpty())
    } }

    @Test fun unrelatedChangedArchiveDoesNotBlockHealthyLiveRecoveryOrCheckpoint() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "healthy")))
        val orphan = File(f.blobs, "${UUID.randomUUID()}.bin").apply { writeBytes(byteArrayOf(1, 2)) }
        f.store.save(snapshot(dirty = mapOf("a.txt" to "healthy")))
        val ledger = f.manifest().getValue("preserved")
        orphan.writeBytes(byteArrayOf(8, 9, 10))
        repeat(3) { index ->
            val reopened = EditorStateStore(f.context, f.id, f.cipher, f.directorySync)
            assertEquals(if (index == 0) "healthy" else "updated-${index - 1}", reopened.load()!!.deferredBuffers.getValue("a.txt").readText())
            val issue = reopened.archivedEvidenceIssues.value.single()
            assertEquals(orphan.name, issue.blobName); assertFalse(issue.missing)
            assertEquals(2L, issue.expectedBytes); assertEquals(3L, issue.observedBytes)
            assertNotEquals(issue.expectedSha256, issue.observedSha256)
            reopened.save(snapshot(dirty = mapOf("a.txt" to "updated-$index")))
            assertEquals(listOf(issue), reopened.archivedEvidenceIssues.value)
            assertEquals(ledger, f.manifest().getValue("preserved"))
            assertArrayEquals(byteArrayOf(8,9,10), orphan.readBytes())
        }
    } }

    @Test fun missingArchiveRetainsOriginalRecordWhileHealthyDraftSurvives() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "healthy")))
        val orphan = File(f.blobs, "${UUID.randomUUID()}.bin").apply { writeBytes(byteArrayOf(1)) }
        f.store.save(snapshot(dirty = mapOf("a.txt" to "healthy")))
        val ledger = f.manifest().getValue("preserved"); Files.delete(orphan.toPath())
        val loaded = f.store.load()!!
        assertEquals("healthy", loaded.deferredBuffers.getValue("a.txt").readText())
        assertTrue(f.store.archivedEvidenceIssues.value.single().missing)
        repeat(3) { f.store.save(loaded); assertTrue(f.store.archivedEvidenceIssues.value.single().missing) }
        assertFalse(orphan.exists()); assertEquals(ledger, f.manifest().getValue("preserved"))
        f.store.clear()
        assertEquals(ledger, f.manifest().getValue("preserved")); assertFalse(orphan.exists())
        val reopened = EditorStateStore(f.context, f.id, f.cipher, f.directorySync)
        assertTrue(reopened.load()!!.dirtyPaths.isEmpty()); assertTrue(reopened.archivedEvidenceIssues.value.single().missing)
    } }

    @Test fun archivedMismatchNeverAuthorizesACorruptLiveReference() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "healthy")))
        val ref = f.store.load()!!.deferredBuffers.getValue("a.txt")
        val live = File(f.blobs, ref.blobName)
        // Authenticated but inconsistent manifest: preserved and live membership overlap.
        val m = f.manifest()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(live.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        f.writeManifest(JsonObject(m.toMutableMap().apply { put("preserved", buildJsonArray { add(buildJsonObject {
            put("blob", ref.blobName); put("bytes", live.length()); put("sha256", hash); put("reason", "orphan")
            put("path", JsonNull); put("expected_sha256", JsonNull); put("max_bytes", JsonNull)
        }) }) }))
        live.writeBytes(byteArrayOf(7, 8))
        val before = f.file.readBytes()
        assertTrue(runCatching { f.store.load() }.isFailure)
        assertTrue(runCatching { ref.readText() }.isFailure)
        assertTrue(runCatching { f.store.save(snapshot(deferred = mapOf("a.txt" to ref))) }.isFailure)
        assertTrue(runCatching { f.store.save(snapshot(dirty = mapOf("next.txt" to "new"))) }.isFailure)
        assertTrue(runCatching { f.store.clear() }.isFailure)
        assertArrayEquals(before, f.file.readBytes()); assertArrayEquals(byteArrayOf(7,8), live.readBytes())
        Files.delete(live.toPath())
        assertTrue(runCatching { f.store.load() }.isFailure)
        assertTrue(runCatching { f.store.save(snapshot(dirty = mapOf("next.txt" to "new"))) }.isFailure)
        assertTrue(runCatching { f.store.clear() }.isFailure)
        assertArrayEquals(before, f.file.readBytes()); assertFalse(live.exists())
    } }

    @Test fun cancelledCheckpointWithQuarantinedArchivePreservesPriorManifestAndRetries() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "healthy")))
        val orphan = File(f.blobs, "${UUID.randomUUID()}.bin").apply { writeBytes(byteArrayOf(1)) }
        f.store.save(snapshot(dirty = mapOf("a.txt" to "healthy"))); orphan.writeBytes(byteArrayOf(2))
        val before = f.file.readBytes(); val ledger = f.manifest().getValue("preserved")
        f.cipher.beforeEncrypt = { throw kotlinx.coroutines.CancellationException("cancel checkpoint") }
        assertTrue(runCatching { f.store.save(snapshot(dirty = mapOf("a.txt" to "next"))) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        f.cipher.beforeEncrypt = null
        assertArrayEquals(before, f.file.readBytes()); assertArrayEquals(byteArrayOf(2), orphan.readBytes())
        f.store.save(snapshot(dirty = mapOf("a.txt" to "next")))
        assertEquals("next", f.store.load()!!.deferredBuffers.getValue("a.txt").readText())
        assertEquals(ledger, f.manifest().getValue("preserved")); assertEquals(1, f.store.archivedEvidenceIssues.value.size)
    } }

    @Test fun enlargedArchivePlusNewlyPreservedLiveDamageCannotEvadeCombinedBudget() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("a.txt" to "healthy")))
        val live = File(f.blobs, f.store.load()!!.deferredBuffers.getValue("a.txt").blobName)
        val archives = (1..3).map { File(f.blobs, "${UUID.randomUUID()}.bin").apply { writeBytes(byteArrayOf(1)) } }
        f.store.save(snapshot(dirty = mapOf("a.txt" to "healthy")))
        archives.forEach { RandomAccessFile(it, "rw").use { file -> file.setLength(21L * 1024 * 1024) } }
        RandomAccessFile(live, "rw").use { it.setLength(2L * 1024 * 1024) }
        val before = f.file.readBytes(); val names = f.blobs.list()!!.toSet()
        assertTrue(runCatching { f.store.save(snapshot(dirty = mapOf("a.txt" to "new"))) }.isFailure)
        assertArrayEquals(before, f.file.readBytes()); assertEquals(names, f.blobs.list()!!.toSet())
        assertEquals(2L * 1024 * 1024, live.length())
        archives.forEach { assertEquals(21L * 1024 * 1024, it.length()) }
    } }

    private fun snapshot(
        dirty: Map<String, String> = emptyMap(), conflicts: Map<String, String> = emptyMap(),
        deferred: Map<String, EditorRecoveryBuffer> = emptyMap(),
        deferredConflicts: Map<String, EditorRecoveryBuffer> = emptyMap(),
    ) = EditorRecoverySnapshot(emptyList(), "", dirty, deferredBuffers = deferred,
        conflictBuffers = conflicts, deferredConflictBuffers = deferredConflicts)

    private class HostCipher : EditorRecoveryCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        var decryptions = 0
        var beforeEncrypt: ((ByteArray) -> Unit)? = null
        var beforeDecrypt: ((ByteArray) -> Unit)? = null
        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            beforeEncrypt?.invoke(aad)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(aad)
            return cipher.iv + cipher.doFinal(plain)
        }
        override fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray {
            decryptions++
            beforeDecrypt?.invoke(aad)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            cipher.updateAAD(aad)
            return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
        }
    }

    private class Fixture(base: Context) {
        val files = File(base.filesDir, "store-fixture-${UUID.randomUUID()}").apply { mkdir() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
        }
        val id = "recovery-test-${UUID.randomUUID()}"
        val cipher = HostCipher()
        val syncedDirectories = CopyOnWriteArrayList<File>()
        var beforeSync: ((File) -> Unit)? = null
        // Robolectric ShadowLinux cannot open directory descriptors. Exercise real host
        // filesystem sync here; the public production constructor still uses Android Os.
        val directorySync: (File) -> Unit = { directory ->
            check(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
            FileChannel.open(directory.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
            syncedDirectories += directory
            beforeSync?.invoke(directory)
        }
        val store = EditorStateStore(context, id, cipher, directorySync)
        val root = File(context.filesDir, ".droide/editor-recovery")
        val file = File(root, "$id.bin")
        val blobs = File(root, "$id.blobs")
        fun manifest() = Json.parseToJsonElement(cipher.decrypt(file.readBytes(), id.toByteArray()).toString(Charsets.UTF_8)).jsonObject
        fun writeManifest(value: JsonObject) = file.writeBytes(cipher.encrypt(value.toString().toByteArray(), id.toByteArray()))
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val f = Fixture(RuntimeEnvironment.getApplication())
        try { block(f) } finally { PathSecurity.deleteTreeNoFollow(f.files) }
    }
}
