package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ChatMediaStoreTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j3ioAAAAASUVORK5CYII=")
    private val config = AgentConfig(providerId = "openai", model = "gpt-4o")

    @Test fun mediaUsesExternalAppStorageAndVirtualAliasesWithoutAddingFilesToTheProject() = runBlocking {
        val fixture = Files.createTempDirectory("chat-media-app-storage-").toFile()
        try {
            val filesDir = fixture.resolve("files").apply { mkdir() }
            val root = filesDir.resolve("projects/default").apply { mkdirs() }
            root.resolve("source.kt").writeText("keep source")
            val store = ChatMediaStore(root)
            assertFalse(store.hasManifest())
            assertFalse(store.storageDirectory.exists())
            assertEquals(filesDir.resolve("chat-media"), ChatMediaStoragePaths.baseDirectory(root))
            val ref = store.import("draft", "image.png", "image/png", ByteArrayInputStream(png))
            val actual = store.file(ref)!!
            assertFalse(PathSecurity.contains(root, actual))
            assertTrue(actual.toPath().startsWith(filesDir.resolve("chat-media").toPath()))
            assertTrue(ref.path.startsWith(".droide/chat-media/blobs/"))
            assertFalse(root.resolve(ref.path).exists())
            assertEquals(actual, store.resolvePath(ref.path))
            assertEquals(ref.path, store.aliasForFile(actual))
            assertEquals(listOf("source.kt"), root.list()!!.toList())
            store.releaseOwner("draft")
            assertEquals("keep source", root.resolve("source.kt").readText())
        } finally { PathSecurity.deleteTreeNoFollow(fixture) }
    }

    @Test fun nestedSubagentMediaAlsoLeavesTheOutermostCanonicalProjectsTree() = runBlocking {
        val fixture = Files.createTempDirectory("chat-media-nested-app-storage-").toFile()
        try {
            val filesDir = fixture.resolve("files").apply { mkdir() }
            val project = filesDir.resolve("projects/main").apply { mkdirs() }
            val child = project.resolve(".droide/subagents/child/projects/nested").apply { mkdirs() }
            val mainStore = ChatMediaStore(project)
            val childStore = ChatMediaStore(child)
            val ref = childStore.import("s-child", "frame.png", "image/png", ByteArrayInputStream(png))
            assertEquals(filesDir.resolve("chat-media"), ChatMediaStoragePaths.baseDirectory(child))
            assertFalse(PathSecurity.contains(project, childStore.storageDirectory))
            assertNotEquals(mainStore.storageDirectory, childStore.storageDirectory)
            assertFalse(child.resolve(ref.path).exists())
            assertTrue(childStore.resolvePath(ref.path)!!.isFile)
            assertNull(mainStore.resolvePath(ref.path)) // Parent handoff must re-own/import, not reinterpret child refs.
            childStore.releaseOwner("s-child")
        } finally { PathSecurity.deleteTreeNoFollow(fixture) }
    }

    @Test fun linkedWorkspaceAncestryFailsBeforeCreatingExternalMediaStorage() = runBlocking {
        val fixture = Files.createTempDirectory("chat-media-workspace-link-").toFile()
        try {
            val target = fixture.resolve("real-project").apply { mkdir() }
            val alias = fixture.resolve("linked-project")
            Files.createSymbolicLink(alias.toPath(), target.toPath())
            assertTrue(runCatching { ChatMediaStore(alias) }.isFailure)
            assertTrue(target.listFiles()!!.isEmpty())
        } finally { PathSecurity.deleteTreeNoFollow(fixture) }
    }

    @Test fun copiesPreserveOriginalsAndLastOwnerDeletionReclaimsOriginalAndDerivatives() = runBlocking {
        val root = Files.createTempDirectory("chat-media-owners-").toFile()
        try {
            val original = root.resolve("project-photo.png").apply { writeBytes(png) }
            val store = ChatMediaStore(root)
            val ref = original.inputStream().use { store.import("draft-first", "project-photo.png", "image/png", it) }
            val derivative = store.importDerivative(ref, "model-image.png", ByteArrayInputStream(png))
            assertEquals(ref, store.findByPath(store.aliasForFile(derivative)))
            store.retain("s-parent", listOf(ref))
            store.releaseOwner("draft-first")
            store.copyOwner("s-parent", "s-child")
            store.releaseOwner("s-parent")
            assertArrayEquals(png, store.file(ref)!!.readBytes())
            assertEquals(derivative, store.derivativeFile(ref, "model-image.png"))
            store.releaseOwner("s-child")
            assertNull(store.file(ref))
            assertFalse(derivative.exists())
            assertFalse(ChatMediaStoragePaths.physicalPath(root, ref.path).parentFile.exists())
            assertArrayEquals(png, original.readBytes())
        } finally { cleanupFixture(root) }
    }

    @Test fun retainAndExpiryNeverExtendCreationBasedRetention() = runBlocking {
        val root = Files.createTempDirectory("chat-media-expiry-").toFile()
        var now = 10_000L
        try {
            val store = ChatMediaStore(root, { now })
            val ref = store.import("draft", "hello.txt", "text/plain", ByteArrayInputStream("hello".toByteArray()))
            now += ChatMediaStore.RETENTION_MS - 1
            store.retain("s-session", listOf(ref))
            val manifest = store.storageDirectory.resolve("index.json")
            assertTrue(manifest.setLastModified(1_000L))
            store.retain("s-session", listOf(ref))
            assertEquals("Unchanged ownership must not rewrite the index", 1_000L, manifest.lastModified())
            assertEquals(10_000L + ChatMediaStore.RETENTION_MS, store.nextExpiry())
            assertNotNull(store.file(ref))
            now++
            assertNull(store.file(ref))
            assertEquals(1, store.cleanupExpired(now))
            assertFalse(ChatMediaStoragePaths.physicalPath(root, ref.path).exists())
            assertTrue(store.ownerRefs("s-session").isEmpty())
            assertNull(store.nextExpiry())
            store.retain("s-session", listOf(ref))
            assertTrue(store.ownerRefs("s-session").isEmpty())
        } finally { cleanupFixture(root) }
    }

    @Test fun nativeSaveForkAndDeleteShareOnlyExplicitMediaOwnership() = runBlocking {
        val root = Files.createTempDirectory("chat-media-native-fork-").toFile()
        try {
            val store = ChatMediaStore(root)
            val ref = store.import("draft", "image.png", "image/png", ByteArrayInputStream(png))
            val manager = SessionManager(root)
            val session = manager.create("Original")
            val exact = listOf(buildJsonObject { put("role", "user"); put("content", "inspect") })
            manager.save(session.copy(messages = listOf(AgentMessage("user", "inspect", media = listOf(ref))),
                apiHistory = exact, forkHistory = exact, forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION))
            store.releaseOwner("draft")
            val child = manager.fork(session.id)!!
            assertEquals(ref, child.messages.single().media.single())
            manager.delete(session.id)
            assertNotNull(store.file(ref))
            assertEquals(listOf(ref), store.ownerRefs(child.id))
            manager.delete(child.id)
            assertNull(store.file(ref))
        } finally { cleanupFixture(root) }
    }

    @Test fun failedSessionFileRemovalDoesNotReleaseItsMedia() = runBlocking {
        val root = Files.createTempDirectory("chat-media-delete-failure-").toFile()
        try {
            val store = ChatMediaStore(root)
            val ref = store.import("draft", "image.png", "image/png", ByteArrayInputStream(png))
            val manager = SessionManager(root)
            val session = manager.create()
            manager.save(session.copy(messages = listOf(AgentMessage("user", "inspect", media = listOf(ref)))))
            store.releaseOwner("draft")
            val sessionFile = root.resolve(".droide/sessions/${session.id}.json")
            assertTrue(sessionFile.delete())
            assertTrue(sessionFile.mkdir())
            sessionFile.resolve("cannot-delete-nonempty-directory").writeText("keep")
            assertTrue(runCatching { manager.delete(session.id) }.isFailure)
            assertNotNull(store.file(ref))
            assertEquals(listOf(ref), store.ownerRefs(session.id))
        } finally { cleanupFixture(root) }
    }

    @Test fun corruptOrMissingIndexPreservesBlobsAndNeverWipesTheWorkspace() = runBlocking {
        val root = Files.createTempDirectory("chat-media-corruption-").toFile()
        try {
            val original = root.resolve("source.txt").apply { writeText("untouched source") }
            val store = ChatMediaStore(root)
            val ref = store.import("draft", "hello.txt", "text/plain", ByteArrayInputStream("hello".toByteArray()))
            val index = store.storageDirectory.resolve("index.json")
            val saved = index.readText()
            index.writeText("{broken")
            assertTrue(runCatching { store.cleanupExpired(Long.MAX_VALUE) }.exceptionOrNull()!!.message!!.contains("preserved"))
            assertEquals("hello", ChatMediaStoragePaths.physicalPath(root, ref.path).readText())
            assertEquals("untouched source", original.readText())
            index.writeText(saved + "\n")
            assertNotNull(store.file(ref))
            assertTrue(index.delete())
            assertTrue(runCatching { ChatMediaStore(root).cleanupExpired(Long.MAX_VALUE) }.isFailure)
            assertTrue(ChatMediaStoragePaths.physicalPath(root, ref.path).isFile)
        } finally { cleanupFixture(root) }
    }

    @Test fun symlinksAndForgedRefsCannotExposeOrDeleteOutsideFiles() = runBlocking {
        val root = Files.createTempDirectory("chat-media-paths-").toFile()
        val outside = Files.createTempDirectory("chat-media-outside-").toFile()
        try {
            val store = ChatMediaStore(root)
            val ref = store.import("draft", "hello.txt", "text/plain", ByteArrayInputStream("hello".toByteArray()))
            val victim = outside.resolve("victim.txt").apply { writeText("do not delete") }
            assertNull(store.file(ref.copy(path = "../victim.txt")))
            assertTrue(runCatching { store.retain("s-owner", listOf(ref.copy(path = "../victim.txt"))) }.isFailure)
            assertTrue(runCatching { store.importDerivative(ref, "../../victim.txt", ByteArrayInputStream(png)) }.isFailure)
            assertTrue(ChatMediaStoragePaths.physicalPath(root, ref.path).delete())
            Files.createSymbolicLink(ChatMediaStoragePaths.physicalPath(root, ref.path).toPath(), victim.toPath())
            assertTrue(runCatching { store.file(ref) }.isFailure)
            store.releaseOwner("draft")
            assertEquals("do not delete", victim.readText())
            assertFalse(Files.exists(ChatMediaStoragePaths.physicalPath(root, ref.path).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        } finally { cleanupFixture(root); PathSecurity.deleteTreeNoFollow(outside) }
    }

    @Test fun symlinkStorageDirectoryRejectsCleanupWithoutFollowingItsTarget() = runBlocking {
        val root = Files.createTempDirectory("chat-media-dir-link-").toFile()
        val outside = Files.createTempDirectory("chat-media-linked-dir-").toFile()
        try {
            val base = ChatMediaStoragePaths.baseDirectory(root).apply { mkdirs() }
            val storage = java.io.File(base, ChatMediaStoragePaths.workspaceKey(root))
            Files.createSymbolicLink(storage.toPath(), outside.toPath())
            val victim = outside.resolve("keep.png").apply { writeBytes(png) }
            assertTrue(runCatching { ChatMediaStore(root).cleanupExpired(Long.MAX_VALUE) }.isFailure)
            assertArrayEquals(png, victim.readBytes())
        } finally { cleanupFixture(root); PathSecurity.deleteTreeNoFollow(outside) }
    }

    @Test fun quotasFailBeforePublicationAndIncludeDerivedBytes() = runBlocking {
        val root = Files.createTempDirectory("chat-media-quota-").toFile()
        try {
            val store = ChatMediaStore(root, limits = ChatMediaStore.Limits(fileBytes = 8, cacheBytes = 12, entries = 2, derivativeBytes = 4))
            assertTrue(runCatching { store.import("draft", "large.bin", "application/octet-stream", ByteArrayInputStream(ByteArray(9))) }.isFailure)
            assertTrue(store.ownerRefs("draft").isEmpty())
            assertTrue(store.storageDirectory.resolve("staging").listFiles()!!.isEmpty())
            val ref = store.import("draft", "small.bin", "application/octet-stream", ByteArrayInputStream(ByteArray(8)))
            store.importDerivative(ref, "preview.bin", ByteArrayInputStream(ByteArray(4)))
            assertTrue(runCatching { store.import("draft", "another.bin", "application/octet-stream", ByteArrayInputStream(byteArrayOf(1))) }.isFailure)
            store.releaseOwner("draft")
            assertNotNull(store.import("draft-new", "after-cleanup.bin", "application/octet-stream", ByteArrayInputStream(ByteArray(8))))
        } finally { cleanupFixture(root) }
    }

    @Test fun entryAndDerivativeLimitsNeverPartiallyGrantAnOwner() = runBlocking {
        val root = Files.createTempDirectory("chat-media-entry-limit-").toFile()
        try {
            val store = ChatMediaStore(root, limits = ChatMediaStore.Limits(fileBytes = 8, cacheBytes = 64, entries = 1, derivativeBytes = 4))
            val ref = store.import("draft", "first.bin", "application/octet-stream", ByteArrayInputStream(byteArrayOf(1)))
            assertTrue(runCatching { store.import("draft", "second.bin", "application/octet-stream", ByteArrayInputStream(byteArrayOf(2))) }.isFailure)
            assertTrue(runCatching { store.retain("s-owner", listOf(ref, ref.copy(id = "bad"))) }.isFailure)
            assertTrue(store.ownerRefs("s-owner").isEmpty())
            repeat(8) { store.importDerivative(ref, "frame-$it.bin", ByteArrayInputStream(byteArrayOf(1))) }
            assertTrue(runCatching { store.importDerivative(ref, "frame-8.bin", ByteArrayInputStream(byteArrayOf(1))) }.isFailure)
            assertFalse(ChatMediaStoragePaths.physicalPath(root, ref.path).parentFile.resolve("frame-8.bin").exists())
        } finally { cleanupFixture(root) }
    }

    @Test fun cancellationRemovesPartialCopiesAndKeepsCallerInputOwnership() = runBlocking {
        val root = Files.createTempDirectory("chat-media-cancel-").toFile()
        try {
            var closed = false
            val input = object : InputStream() {
                override fun read(): Int = throw CancellationException("fixture cancelled")
                override fun close() { closed = true }
            }
            val store = ChatMediaStore(root)
            assertTrue(runCatching { store.import("draft", "file.bin", "application/octet-stream", input) }.exceptionOrNull() is CancellationException)
            assertFalse(closed)
            assertTrue(store.ownerRefs("draft").isEmpty())
            assertTrue(store.storageDirectory.resolve("staging").listFiles()!!.isEmpty())
        } finally { cleanupFixture(root) }
    }

    @Test fun cleanupReclaimsAbandonedPartialFilesWithoutDeletingUnknownFiles() = runBlocking {
        val root = Files.createTempDirectory("chat-media-abandoned-partial-").toFile()
        try {
            val store = ChatMediaStore(root)
            store.cleanupExpired()
            val staging = store.storageDirectory.resolve("staging")
            val abandoned = staging.resolve("m-00000000-0000-0000-0000-000000000000.part").apply { writeBytes(png) }
            val unknown = staging.resolve("keep-user-note.txt").apply { writeText("keep") }
            assertEquals(0, store.cleanupExpired())
            assertFalse(abandoned.exists())
            assertEquals("keep", unknown.readText())
        } finally { cleanupFixture(root) }
    }

    @Test fun savedPendingAttachmentsAndExpiredHistoricalResavesKeepSessionMetadataUsable() = runBlocking {
        val root = Files.createTempDirectory("chat-media-pending-lifecycle-").toFile()
        try {
            val store = ChatMediaStore(root)
            val current = store.import("draft", "image.png", "image/png", ByteArrayInputStream(png))
            val manager = SessionManager(root)
            val session = manager.create()
            val pending = AgentPendingInput("i-queued", "look", AgentPromptDelivery.QUEUE, "openai", "gpt-4o", media = listOf(current))
            manager.save(session.copy(pendingInputs = listOf(pending)))
            store.releaseOwner("draft")
            val queuedOwner = ChatMediaStore.pendingOwner(session.id, pending.id)
            assertEquals(listOf(current), store.ownerRefs(queuedOwner))
            assertTrue(store.ownerRefs(session.id).isEmpty())
            val previous = System.currentTimeMillis() - ChatMediaStore.RETENTION_MS - 10_000
            val oldStore = ChatMediaStore(root, { previous })
            val expired = oldStore.import("old-draft", "old.png", "image/png", ByteArrayInputStream(png))
            store.cleanupExpired()
            manager.save(session.copy(messages = listOf(AgentMessage("user", "previous photo", media = listOf(expired))), pendingInputs = listOf(pending)))
            assertEquals(expired, manager.get(session.id)!!.messages.single().media.single())
            assertEquals(listOf(current), store.ownerRefs(queuedOwner))
            assertTrue(store.ownerRefs(session.id).isEmpty())
            assertNotNull(store.file(current))
        } finally { cleanupFixture(root) }
    }

    @Test fun deletedOwnerCannotBeResurrectedByAnInFlightImportFromAnotherStoreInstance() = runBlocking {
        val root = Files.createTempDirectory("chat-media-delete-race-").toFile()
        try {
            val entered = CountDownLatch(1)
            val continueRead = CountDownLatch(1)
            var delivered = false
            val input = object : InputStream() {
                override fun read(): Int = error("buffered read expected")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (delivered) return -1
                    entered.countDown()
                    check(continueRead.await(5, TimeUnit.SECONDS))
                    delivered = true
                    buffer[offset] = 1
                    return 1
                }
            }
            val first = ChatMediaStore(root)
            val pending = async(Dispatchers.IO) { runCatching { first.import("s-deleted", "file.bin", "application/octet-stream", input) } }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                ChatMediaStore(root).cleanupExpired()
                assertEquals(1, first.storageDirectory.resolve("staging").listFiles()!!.size)
                ChatMediaStore(root).releaseOwner("s-deleted")
            } finally { continueRead.countDown() }
            assertTrue(pending.await().isFailure)
            assertTrue(first.ownerRefs("s-deleted").isEmpty())
            assertTrue(first.storageDirectory.resolve("blobs").listFiles()!!.isEmpty())
        } finally { cleanupFixture(root) }
    }

    @Test fun managedOriginalAndVideoFrameReuseOwnershipEvenWithoutAnExplicitStageOwner() = runBlocking {
        val root = Files.createTempDirectory("chat-media-vision-reuse-").toFile()
        try {
            val store = ChatMediaStore(root)
            val image = store.import("draft", "image.png", "image/png", ByteArrayInputStream(png))
            val staged = VisionSupport.stage(root, listOf(image.path), config, "s-active").single()
            assertEquals(image.path, staged.path)
            assertEquals(image.id, staged.mediaId)
            assertEquals(image.expiresAt, staged.expiresAt)
            val video = store.import("s-active", "clip.mp4", "video/mp4", ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            val frame = store.importDerivative(video, "frame-0.png", ByteArrayInputStream(png))
            val frameRef = VisionSupport.stage(root, listOf(store.aliasForFile(frame)), config).single()
            assertEquals(video.id, frameRef.mediaId)
            assertEquals(video.expiresAt, frameRef.expiresAt)
            assertEquals(2, store.ownerRefs("s-active").size)
            assertFalse(root.resolve(".droide/vision").exists())
            assertTrue(VisionSupport.materializeHistory(listOf(VisionSupport.userMessage("see frame", listOf(frameRef), 1_000)), root).single().toString().contains("data:image/png;base64,"))
        } finally { cleanupFixture(root) }
    }

    @Test fun expiredManagedImagesProjectHonestPlaceholdersWhileLegacyMissingImagesStillFail() = runBlocking {
        val root = Files.createTempDirectory("chat-media-vision-expired-").toFile()
        try {
            val oldClock = System.currentTimeMillis() - ChatMediaStore.RETENTION_MS - 10_000
            val store = ChatMediaStore(root, { oldClock })
            val ref = store.import("s-old", "old.png", "image/png", ByteArrayInputStream(png))
            val image = AgentImageRef(ref.path, sha(png), "image/png", png.size.toLong(), ref.id, ref.expiresAt)
            val history = listOf(VisionSupport.userMessage("old image", listOf(image), 1_000))
            val before = history.single().toString()
            assertTrue(VisionSupport.materializeHistory(history, root).single().toString().contains("expired after seven days"))
            store.cleanupExpired(System.currentTimeMillis())
            assertTrue(VisionSupport.materializeHistory(history, root).single().toString().contains("Image data is not included"))
            assertEquals(before, history.single().toString())
            val missing = AgentImageRef(".droide/vision/missing.png", sha(png), "image/png", png.size.toLong())
            assertTrue(runCatching { VisionSupport.materializeHistory(listOf(VisionSupport.userMessage("missing", listOf(missing), 1_000)), root) }.isFailure)
        } finally { cleanupFixture(root) }
    }

    @Test fun removedManagedCopiesBecomeUnavailableButTamperedLiveCopiesStillFail() = runBlocking {
        val root = Files.createTempDirectory("chat-media-vision-tamper-").toFile()
        try {
            val store = ChatMediaStore(root)
            val ref = store.import("s-active", "image.png", "image/png", ByteArrayInputStream(png))
            val staged = VisionSupport.stage(root, listOf(ref.path), config).single()
            val history = listOf(VisionSupport.userMessage("inspect", listOf(staged), 1_000))
            ChatMediaStoragePaths.physicalPath(root, ref.path).writeBytes(png.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            assertTrue(runCatching { VisionSupport.materializeHistory(history, root) }.isFailure)
            store.releaseOwner("s-active")
            val request = VisionSupport.materializeHistory(history, root).single().toString()
            assertTrue(request.contains("unavailable"))
            assertFalse(request.contains("data:image/png;base64,"))
        } finally { cleanupFixture(root) }
    }

    @Test fun wholeRequestVisualBudgetKeepsNewestFourImagesWithoutChangingExactHistory() = runBlocking {
        val root = Files.createTempDirectory("chat-media-image-budget-").toFile()
        try {
            val refs = (0..5).map { index ->
                val path = "image-$index.png"
                root.resolve(path).writeBytes(png + index.toByte())
                VisionSupport.stage(root, listOf(path), config).single()
            }
            val history = refs.map { VisionSupport.userMessage("image", listOf(it), 1_000) }
            val exact = history.toString()
            val request = VisionSupport.materializeHistory(history, root)
            assertEquals(4, imageCount(request))
            assertTrue(request[0].toString().contains("most recent images"))
            assertTrue(request[1].toString().contains("most recent images"))
            assertTrue(request.last().toString().contains("data:image/png;base64,"))
            assertEquals(exact, history.toString())
        } finally { cleanupFixture(root) }
    }

    @Test fun wholeRequestVisualBudgetAlsoCapsTotalBytesAcrossSeparateTurns() = runBlocking {
        val root = Files.createTempDirectory("chat-media-image-byte-budget-").toFile()
        try {
            val refs = (0..2).map { index ->
                val bytes = ByteArray(3 * 1024 * 1024)
                png.copyInto(bytes)
                bytes[bytes.lastIndex] = index.toByte()
                val path = "image-$index.png"
                root.resolve(path).writeBytes(bytes)
                VisionSupport.stage(root, listOf(path), config).single()
            }
            val request = VisionSupport.materializeHistory(refs.map { VisionSupport.userMessage("image", listOf(it), 1_000) }, root)
            assertEquals(2, imageCount(request))
            assertTrue(request.first().toString().contains("8 MiB visual context budget"))
        } finally { cleanupFixture(root) }
    }

    private fun cleanupFixture(root: java.io.File) {
        // Delete only this fixture's hashed backing tree, never the shared external base.
        val storage = java.io.File(ChatMediaStoragePaths.baseDirectory(root), ChatMediaStoragePaths.workspaceKey(root))
        PathSecurity.deleteTreeNoFollow(storage)
        PathSecurity.deleteTreeNoFollow(root)
    }

    private fun imageCount(messages: List<JsonObject>): Int = messages.sumOf { message ->
        (message["content"] as? JsonArray).orEmpty().count { it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" }
    }
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
