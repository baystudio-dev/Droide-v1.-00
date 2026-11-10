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
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual store/filesystem with host AES-GCM; does not certify AndroidKeyStore/device recovery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorStateStoreConflictTest {
    @Test fun currentAndOlderVersionsUseDistinctEncryptedSlotsAndLoadLazily() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("src/main.kt" to "new live 日本語"),
            conflicts = mapOf("src/main.kt" to "older recovered 😀")))
        assertEquals(2, f.blobs.listFiles()!!.size)
        assertEquals("Root admission and manifest commit both sync their directory", 2, f.syncedDirectories.count { it == f.root })
        assertEquals("Each distinct conflict/live blob is synchronized before the manifest", 2, f.syncedDirectories.count { it == f.blobs })
        f.blobs.listFiles()!!.forEach { blob ->
            val bytes = blob.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(bytes.contains("new live")); assertFalse(bytes.contains("older recovered"))
        }
        val before = f.cipher.decryptions
        val loaded = f.store.load()!!
        assertEquals("Only the manifest is decrypted during load", before + 1, f.cipher.decryptions)
        assertTrue(loaded.dirtyBuffers.isEmpty()); assertTrue(loaded.conflictBuffers.isEmpty())
        assertEquals(setOf("src/main.kt"), loaded.dirtyPaths)
        assertEquals(setOf("src/main.kt"), loaded.conflictPaths)
        assertEquals("new live 日本語", loaded.deferredBuffers.getValue("src/main.kt").readText())
        assertEquals("older recovered 😀", loaded.deferredConflictBuffers.getValue("src/main.kt").readText())
    } }

    @Test fun deferredOlderVersionSurvivesLiveSaveClosedTabsAndNewStoreInstance() = fixture { f -> runBlocking {
        val project = File(f.context.filesDir, "projects/${f.id}").apply { mkdirs() }
        val source = File(project, "main.kt").apply { writeText("new user save") }
        try {
            f.store.save(snapshot(dirty = mapOf("main.kt" to "older recovery")))
            val old = f.store.load()!!.deferredBuffers.getValue("main.kt")
            val encryptedOld = File(f.blobs, old.blobName).readBytes()
            val reads = f.cipher.decryptions
            f.store.save(snapshot(dirty = mapOf("main.kt" to "new unsaved edit"),
                deferredConflicts = mapOf("main.kt" to old)))
            assertEquals("Saving a deferred conflict decrypts only the old manifest", reads + 1, f.cipher.decryptions)
            assertArrayEquals(encryptedOld, File(f.blobs, old.blobName).readBytes())
            val loaded = f.store.load()!!
            assertNotEquals(loaded.deferredBuffers.getValue("main.kt").blobName,
                loaded.deferredConflictBuffers.getValue("main.kt").blobName)
            f.store.save(snapshot(deferredConflicts = loaded.deferredConflictBuffers))
            val reopened = EditorStateStore(f.context, f.id, f.cipher, f.directorySync).load()!!
            assertTrue(reopened.openFiles.isEmpty()); assertTrue(reopened.dirtyPaths.isEmpty())
            assertEquals("older recovery", reopened.deferredConflictBuffers.getValue("main.kt").readText())
            assertEquals("new user save", source.readText())
            assertEquals(1, f.blobs.listFiles()!!.size)
        } finally { PathSecurity.deleteTreeNoFollow(project) }
    } }

    @Test fun versionFourReferencesMigrateToConflictWithoutLoadingTheirPayload() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("old.txt" to "kept from v4")))
        val current = f.manifest()
        val legacy = JsonObject(current.toMutableMap().apply {
            put("version", JsonPrimitive(4)); remove("preserved"); remove("retiring"); remove("conflicts")
            put("dirty", JsonArray(current.getValue("dirty").jsonArray.map { entry ->
                JsonObject(entry.jsonObject.filterKeys { it != "max_bytes" })
            }))
        })
        f.writeManifest(legacy)
        val reads = f.cipher.decryptions
        val loaded = f.store.load()!!
        assertEquals(reads + 1, f.cipher.decryptions)
        f.store.save(snapshot(deferredConflicts = loaded.deferredBuffers))
        assertEquals(reads + 2, f.cipher.decryptions)
        assertEquals(7, f.manifest().getValue("version").jsonPrimitive.int)
        assertEquals("kept from v4", f.store.load()!!.deferredConflictBuffers.getValue("old.txt").readText())
    } }

    @Test fun legacyLargeLazyCheckpointRemainsReadableWhenNewSaveBudgetRejectsMigration() = fixture { f -> runBlocking {
        f.store.save(snapshot())
        val legacy = buildJsonObject {
            put("version", 4); put("active", "large1.txt")
            put("open", buildJsonArray { add("large1.txt") })
            put("dirty", buildJsonArray {
                repeat(3) { index ->
                    val id = "${UUID.randomUUID()}.bin"
                    // Sparse ciphertext placeholders deliberately cannot decrypt: successful load must inspect
                    // bounded metadata only, not eagerly read 45 MiB.
                    RandomAccessFile(File(f.blobs, id), "rw").use { it.setLength(15L * 1024 * 1024 + 28) }
                    add(buildJsonObject {
                        put("path", "large${index + 1}.txt"); put("blob", id); put("sha256", "0".repeat(64))
                    })
                }
            })
        }
        f.writeManifest(legacy)
        val prior = f.file.readBytes()
        val reads = f.cipher.decryptions
        val loaded = f.store.load()!!
        assertEquals(3, loaded.deferredBuffers.size)
        assertEquals(reads + 1, f.cipher.decryptions)
        assertTrue(runCatching { f.store.save(snapshot(deferred = loaded.deferredBuffers)) }.isFailure)
        assertArrayEquals(prior, f.file.readBytes())
        assertEquals(3, f.blobs.listFiles()!!.size)
        assertEquals(3, f.store.load()!!.deferredBuffers.size)
    } }

    @Test fun combinedConflictLimitsRejectWithoutReplacingPriorRecovery() = fixture { f -> runBlocking {
        f.store.save(snapshot(dirty = mapOf("main.kt" to "preserved")))
        val prior = f.file.readBytes()
        val many = (1..129).associate { "file$it.kt" to "" }
        assertTrue(runCatching { f.store.save(snapshot(dirty = many, conflicts = many)) }.isFailure)
        assertArrayEquals(prior, f.file.readBytes())
        val loaded = f.store.load()!!.deferredBuffers.getValue("main.kt")
        // A real encrypted blob with conservative lazy size metadata exercises the combined budget
        // without allocating 40 MiB merely to test integer accounting.
        fun bounded(path: String) = EditorRecoveryBuffer(path, loaded.blobName, loaded.contentSha256,
            f.store, 20L * 1024 * 1024) { error("Size admission must stay lazy") }
        assertTrue(runCatching { f.store.save(snapshot(dirty = mapOf("one-byte" to "x"),
            deferred = mapOf("large-live" to bounded("large-live")),
            deferredConflicts = mapOf("large-conflict" to bounded("large-conflict")))) }.isFailure)
        assertArrayEquals(prior, f.file.readBytes())
        assertEquals("preserved", f.store.load()!!.deferredBuffers.getValue("main.kt").readText())
        assertEquals(1, f.blobs.listFiles()!!.size)
    } }

    @Test fun duplicateSlotAndForeignOwnerCannotDestroyExistingConflict() = fixture { f -> runBlocking {
        f.store.save(snapshot(conflicts = mapOf("main.kt" to "old conflict")))
        val reference = f.store.load()!!.deferredConflictBuffers.getValue("main.kt")
        val prior = f.file.readBytes()
        assertTrue(runCatching { f.store.save(snapshot(conflicts = mapOf("main.kt" to "replacement"),
            deferredConflicts = mapOf("main.kt" to reference))) }.isFailure)
        assertArrayEquals(prior, f.file.readBytes())
        val foreign = EditorStateStore(f.context, f.id, f.cipher, f.directorySync).load()!!.deferredConflictBuffers.getValue("main.kt")
        assertTrue(runCatching { f.store.save(snapshot(deferredConflicts = mapOf("main.kt" to foreign))) }.isFailure)
        assertArrayEquals(prior, f.file.readBytes())
        assertEquals("old conflict", f.store.load()!!.deferredConflictBuffers.getValue("main.kt").readText())
    } }

    @Test fun conflictPayloadRetainsAuthenticatedEncryptionAndChecksumValidation() = fixture { f -> runBlocking {
        f.store.save(snapshot(conflicts = mapOf("secret.txt" to "original")))
        val loaded = f.store.load()!!.deferredConflictBuffers.getValue("secret.txt")
        val blob = File(f.blobs, loaded.blobName)
        val original = blob.readBytes()
        blob.writeBytes(original.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        assertTrue(runCatching { loaded.readText() }.isFailure)
        blob.writeBytes(f.cipher.encrypt("tampered".toByteArray(), "${f.id}\u0000secret.txt".toByteArray()))
        assertTrue(runCatching { loaded.readText() }.isFailure)
        blob.writeBytes(original)
        assertEquals("original", loaded.readText())
    } }

    @Test fun aliasedStorageComponentsFailClosedWithoutTouchingForeignFiles() {
        for (component in listOf("metadata", "root", "blobs", "manifest")) fixture { f -> runBlocking {
            f.store.save(snapshot(conflicts = mapOf("secret.txt" to "owned recovery")))
            val foreign = File(f.context.filesDir, "foreign-project").apply { mkdir() }
            val sentinel = File(foreign, "dependency.bin").apply { writeText("never delete") }
            val chosen = when (component) {
                "metadata" -> f.root.parentFile
                "root" -> f.root
                "blobs" -> f.blobs
                else -> f.file
            }
            val saved = File(f.context.filesDir, "saved-storage")
            Files.move(chosen.toPath(), saved.toPath())
            Files.createSymbolicLink(chosen.toPath(), if (component == "manifest") sentinel.toPath() else foreign.toPath())
            try {
                // A cold Activity creates the store synchronously. Corrupt metadata must fail through handled
                // load/save, never through that construction path.
                val reopened = EditorStateStore(f.context, f.id, f.cipher, f.directorySync)
                assertTrue("cold load rejects $component alias", runCatching { reopened.load() }.isFailure)
                assertTrue("cold save rejects $component alias", runCatching { reopened.save(snapshot()) }.isFailure)
                assertTrue("load rejects $component alias", runCatching { f.store.load() }.isFailure)
                assertTrue("save rejects $component alias", runCatching { f.store.save(snapshot()) }.isFailure)
                assertTrue("clear rejects $component alias", runCatching { f.store.clear() }.isFailure)
                assertEquals("never delete", sentinel.readText())
                assertEquals(listOf("dependency.bin"), foreign.list()!!.toList())
            } finally {
                Files.delete(chosen.toPath())
                Files.move(saved.toPath(), chosen.toPath())
            }
            assertEquals("owned recovery", f.store.load()!!.deferredConflictBuffers.getValue("secret.txt").readText())
        } }
    }

    @Test fun corruptRecoveryDirectoryDoesNotThrowWhileConstructingEditorStore() = fixture { f -> runBlocking {
        f.root.parentFile.mkdirs()
        f.root.writeText("corrupt metadata directory")
        val reopened = EditorStateStore(f.context, f.id, f.cipher, f.directorySync)
        assertTrue(runCatching { reopened.load() }.isFailure)
        assertTrue(runCatching { reopened.save(snapshot()) }.isFailure)
        assertEquals("corrupt metadata directory", f.root.readText())
    } }

    @Test fun cleanupPreservesUnknownChildrenAndDoesNotFollowBlobSymlink() = fixture { f -> runBlocking {
        f.store.save(snapshot(conflicts = mapOf("secret.txt" to "owned")))
        val foreign = File(f.context.filesDir, "dependency").apply { writeText("keep dependency") }
        val unknown = File(f.blobs, "user-notes.txt").apply { writeText("keep notes") }
        val childDirectory = File(f.blobs, "other-cache").apply { mkdir() }
        val nested = File(childDirectory, "data").apply { writeText("keep nested") }
        val symlink = File(f.blobs, "${UUID.randomUUID()}.bin")
        Files.createSymbolicLink(symlink.toPath(), foreign.toPath())
        f.store.save(snapshot())
        f.store.clear()
        assertEquals("keep notes", unknown.readText())
        assertEquals("keep nested", nested.readText())
        assertEquals("keep dependency", foreign.readText())
        assertTrue(Files.isSymbolicLink(symlink.toPath()))
        assertTrue(f.blobs.isDirectory)
        assertFalse(f.file.exists())
    } }

    @Test fun openedHiddenCopyCheckpointsBothAuthenticatedPathsWithoutSavingProjectFile() = fixture { f -> runBlocking {
        f.store.save(snapshot(conflicts = mapOf("original.txt" to "older recovered 日本語")))
        val loaded = requireNotNull(f.store.load())
        val state = com.baystudio.droide.ui.EditorWorkspaceState()
        state.retainRecoveryConflicts(loaded)
        val project = File(f.files, "project").apply { mkdir() }
        val action = com.baystudio.droide.ui.EditorRecoveryCopies(state, FileRepository(project))
        action.prepare(state.retainedCopies().single(), "copy.txt").open()
        f.store.save(requireNotNull(state.recoverySnapshot(listOf("copy.txt"), "copy.txt")))
        val restored = requireNotNull(f.store.load())
        assertEquals("older recovered 日本語", restored.deferredConflictBuffers.getValue("original.txt").readText())
        assertEquals("older recovered 日本語", restored.deferredBuffers.getValue("copy.txt").readText())
        assertEquals(DiskFileVersion.fromText(null), restored.diskVersions["copy.txt"])
        assertEquals(setOf("original.txt"), restored.conflictPaths)
        assertEquals(setOf("copy.txt"), restored.dirtyPaths)
        assertFalse(File(project, "copy.txt").exists())
        assertEquals(2, f.blobs.listFiles()!!.size)
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
        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(aad)
            return cipher.iv + cipher.doFinal(plain)
        }
        override fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray {
            decryptions++
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
        // Robolectric ShadowLinux cannot open directory descriptors. Exercise real host filesystem sync
        // here; the public production constructor still uses Android Os.
        val directorySync: (File) -> Unit = { directory ->
            check(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
            FileChannel.open(directory.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
            syncedDirectories += directory
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
