package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Focused tests for the selected C integrity hunks, without FileRepository/native dependencies. */
class ChatMediaIntegrityRecoveryTest {
    private lateinit var sandbox: File
    private lateinit var root: File
    private lateinit var store: ChatMediaStore
    private val bytes = "import-authenticated original content".toByteArray()

    @Before fun setup() {
        sandbox = Files.createTempDirectory("media-integrity-recovery-").toFile()
        root = sandbox.resolve("files/projects/work").apply { mkdirs() }
        store = ChatMediaStore(root)
    }
    @After fun cleanup() { PathSecurity.deleteTreeNoFollow(sandbox) }

    @Test fun sameLengthMutationAfterImportFailsDigestAtConsumedEof() = runBlocking {
        val ref = store.import("session", "asset.bin", "application/octet-stream", bytes.inputStream())
        val original = store.file(ref)!!
        original.writeBytes(ByteArray(bytes.size) { 97 })
        val input = store.openOwnedOriginal("session", ref)
        input.use {
            assertEquals("import", input.integrityOrigin)
            fails { input.readBytes() }
        }
        assertArrayEquals(ByteArray(bytes.size) { 97 }, original.readBytes())
    }

    @Test fun sameLengthMutationAfterOpeningStillFailsStreamedDigest() = runBlocking {
        val ref = store.import("session", "asset.bin", "application/octet-stream", bytes.inputStream())
        val original = store.file(ref)!!
        val input = store.openOwnedOriginal("session", ref)
        original.writeBytes(ByteArray(bytes.size) { 98 })
        input.use { fails { it.readBytes() } }
        assertArrayEquals(ByteArray(bytes.size) { 98 }, original.readBytes())
    }

    @Test fun legacyDigestMigrationIsLabeledCurrentSnapshotNotHistoricalAuthenticity() = runBlocking {
        val ref = store.import("session", "asset.bin", "application/octet-stream", bytes.inputStream())
        val manifest = store.storageDirectory.resolve("index.json")
        val index = Json.parseToJsonElement(manifest.readText()).jsonObject
        val entries = index.getValue("entries").jsonObject
        val entry = entries.getValue(ref.id).jsonObject
        val legacyEntry = JsonObject(entry.filterKeys { it != "originalSha256" && it != "integrityOrigin" })
        manifest.writeText(JsonObject(index + ("entries" to JsonObject(entries + (ref.id to legacyEntry)))).toString())
        store.file(ref)!!.writeBytes(ByteArray(bytes.size) { 99 })
        store.openOwnedOriginal("session", ref).use { input ->
            assertEquals("legacy_snapshot", input.integrityOrigin)
            assertArrayEquals(ByteArray(bytes.size) { 99 }, input.readBytes())
        }
        val upgraded = Json.parseToJsonElement(manifest.readText()).jsonObject
            .getValue("entries").jsonObject.getValue(ref.id).jsonObject
        assertTrue(upgraded.containsKey("originalSha256"))
        assertEquals("\"legacy_snapshot\"", upgraded.getValue("integrityOrigin").toString())
        store.openOwnedOriginal("session", ref).use { input ->
            assertEquals("legacy_snapshot", input.integrityOrigin)
            assertArrayEquals(ByteArray(bytes.size) { 99 }, input.readBytes())
        }
    }

    @Test fun zeroReadAtImportIsNotEndOfFileAndDigestCoversCompleteSource() = runBlocking {
        val stream = object : ByteArrayInputStream(bytes) {
            var first = true
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (first) { first = false; return 0 }
                return super.read(buffer, offset, length)
            }
        }
        val ref = store.import("session", "asset.bin", "application/octet-stream", stream)
        assertEquals(bytes.size.toLong(), ref.bytes)
        store.openOwnedOriginal("session", ref).use { assertArrayEquals(bytes, it.readBytes()) }
    }

    @Test fun repeatedZeroReadsFailBoundedlyInsteadOfSpinningOrCommittingEmptyContent() = runBlocking {
        var reads = 0
        val stream = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { reads++; return 0 }
        }
        try {
            store.import("session", "asset.bin", "application/octet-stream", stream)
            fail("Expected stalled-source rejection")
        } catch (_: IllegalArgumentException) { }
        assertEquals(17, reads)
        assertTrue(store.ownerRefs("session").isEmpty())
    }

    @Test fun revokedOwnerCannotOpenMediaButAnAlreadyOwnedDescriptorRetainsItsOriginalBytes() = runBlocking {
        val ref = store.import("session", "asset.bin", "application/octet-stream", bytes.inputStream())
        val opened = store.openOwnedOriginal("session", ref)
        store.releaseOwner("session")
        opened.use { assertArrayEquals(bytes, it.readBytes()) }
        try { store.openOwnedOriginal("session", ref); fail("Expected removed-owner rejection") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun truncatedOrGrowingConsumedOriginalCannotProduceAValidDigest() {
        for (changed in listOf(bytes.dropLast(1).toByteArray(), bytes + byteArrayOf(1))) {
            val input = ChatMediaStore.IntegrityCheckedOriginal(changed.inputStream(), bytes.size.toLong(),
                ProjectCopyEvidence.sha256(bytes), "import")
            input.use { fails { it.readBytes() } }
        }
    }

    private fun fails(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return }
        throw AssertionError("Expected integrity rejection")
    }
}
