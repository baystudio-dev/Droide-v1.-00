package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AgentMediaOwnershipRegressionTest {
    private val fixture = Files.createTempDirectory("media-ownership-regression-").toFile()
    private val root = File(fixture, "files/projects/project").apply { mkdirs() }
    private val store = ChatMediaStore(root)
    private suspend fun media(owner: String, bytes: String = "media bytes") =
        ByteArrayInputStream(bytes.toByteArray()).use { store.import(owner, "result.txt", "text/plain", it) }
    @After fun cleanup() { assertTrue(PathSecurity.deleteTreeNoFollow(fixture)) }

    @Test fun expiryAndSessionDeletionKeepSourceDependenciesBuildsAndOriginalDownloadsByteExact() = runBlocking {
        val paths = listOf("src/main.kt", "node_modules/native/addon.node", ".venv/lib/module.so", "target/release/app",
            "build/app.apk", ".gradle/cache.bin", ".cxx/output.o", "downloads/original.mp4", ".git/config")
        val protected = paths.associateWith { path ->
            File(root, path).apply { parentFile.mkdirs(); writeBytes(("untouched:$path").toByteArray()) }.readBytes()
        }
        val runtime = File(fixture, "files/toolchains/ubuntu/usr/bin/python3").apply { parentFile.mkdirs(); writeText("runtime stays") }
        val first = media("s-expired")
        val original = store.file(first)!!
        store.importDerivative(first, "frame-0.jpg", ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        assertFalse(PathSecurity.contains(root, original))
        assertEquals(1, store.cleanupExpired(first.expiresAt))
        assertFalse(original.exists())
        val second = media("s-delete")
        assertTrue(store.releaseDeletedOwner("s-delete"))
        assertNull(store.file(second))
        protected.forEach { (path, bytes) -> assertArrayEquals(path, bytes, File(root, path).readBytes()) }
        assertEquals("runtime stays", runtime.readText())
    }

    @Test fun corruptIndexKeepsDeletionIntentAndReclaimsOnlyDeletedAndQueuedOwnersAfterRepair() = runBlocking {
        val ref = media("s-delete")
        val queued = media(ChatMediaStore.pendingOwner("s-delete", "in-one"))
        val survivor = media("s-other")
        store.retain("s-fork", listOf(ref))
        val index = File(store.storageDirectory, "index.json")
        val good = index.readBytes()
        index.writeText("corrupt index")
        assertFalse(store.releaseDeletedOwner("s-delete"))
        assertTrue(File(store.storageDirectory, "deleted-owners.json").isFile)
        index.writeBytes(good)
        val reopened = ChatMediaStore(root)
        reopened.cleanupExpired()
        assertTrue(reopened.ownerRefs("s-delete").isEmpty())
        assertTrue(reopened.ownerRefs(ChatMediaStore.pendingOwner("s-delete", "in-one")).isEmpty())
        assertNull(reopened.file(queued))
        assertNotNull(reopened.file(ref))
        assertNotNull(reopened.file(survivor))
        assertFalse(File(store.storageDirectory, "deleted-owners.json").exists())
    }

    @Test fun cleanupKeepsUnknownFilesAndNestedProjectsEvenInsideARegisteredBlobDirectory() = runBlocking {
        val ref = media("s-expired")
        val original = store.file(ref)!!
        val directory = original.parentFile
        val note = File(directory, "user-note.txt").apply { writeText("keep note") }
        val dependency = File(directory, "imported-project/node_modules/native/addon.node").apply {
            parentFile.mkdirs(); writeText("keep dependency")
        }
        store.cleanupExpired(ref.expiresAt)
        assertFalse(original.exists())
        assertEquals("keep note", note.readText())
        assertEquals("keep dependency", dependency.readText())
        // Retry has exactly the same narrow scope after the ownership entry has been retired.
        ChatMediaStore(root).cleanupExpired(ref.expiresAt + 1)
        assertEquals("keep note", note.readText())
        assertEquals("keep dependency", dependency.readText())
    }

    @Test fun removingPendingInputDeletesOnlyItsCopiesAndLeavesDeliveredTranscriptMedia() = runBlocking {
        val sessions = SessionManager(root)
        val session = sessions.create()
        val delivered = media(session.id)
        val pending = media("draft-pending")
        val input = AgentPendingInput("in-one", "inspect", AgentPromptDelivery.QUEUE, "openai", "gpt-4o", media = listOf(pending))
        sessions.save(session.copy(messages = listOf(AgentMessage("assistant", "saved", media = listOf(delivered))), pendingInputs = listOf(input)))
        store.releaseOwner("draft-pending")
        assertEquals(listOf(delivered), store.ownerRefs(session.id))
        assertEquals(listOf(pending), store.ownerRefs(ChatMediaStore.pendingOwner(session.id, input.id)))
        sessions.savePendingInputs(session.id, emptyList())
        assertTrue(sessions.get(session.id)!!.pendingInputs.isEmpty())
        assertNull(store.file(pending))
        assertNotNull(store.file(delivered))
    }

    @Test fun nativeSessionDeletionCompletesEvenWhenMediaIndexCannotBeRead() = runBlocking {
        val sessions = SessionManager(root)
        val session = sessions.create()
        val ref = media(session.id)
        sessions.save(session.copy(messages = listOf(AgentMessage("user", "attached", media = listOf(ref)))))
        val index = File(store.storageDirectory, "index.json")
        val good = index.readBytes()
        index.writeText("broken")
        sessions.delete(session.id)
        assertNull(sessions.get(session.id))
        index.writeBytes(good)
        ChatMediaStore(root).cleanupExpired()
        assertNull(store.file(ref))
    }

    @Test fun rollbackReclaimsOnlyNewUnpublishedOutputsAndKeepsPreviousAndCommittedCards() = runBlocking {
        val old = media("s-live", "previous card")
        val publication = AgentMediaPublication.begin(root, "s-live")
        val committed = media("s-live", "completed card")
        val cancelled = media("s-live", "cancelled output")
        listOf(old, committed, cancelled).forEach(publication::produced)
        publication.commit(listOf(committed), emptyList())
        publication.discardUnpublished()
        assertNull(store.file(cancelled))
        assertEquals(setOf(old.id, committed.id), store.ownerRefs("s-live").map { it.id }.toSet())
        assertEquals("previous card", store.file(old)!!.readText())
        assertEquals("completed card", store.file(committed)!!.readText())
    }

    @Test fun modelReceiptKeepsReadableAliasAndExplicitVideoEvidenceLimits() = runBlocking {
        val video = ByteArrayInputStream(byteArrayOf(1, 2)).use { store.import("s-live", "clip.mp4", "video/mp4", it) }
        val receipt = AgentMediaPromptReceipt.forModel("What happens?", listOf(video), emptyList())
        assertTrue(receipt.contains(video.path))
        assertTrue(receipt.contains("read_file"))
        assertTrue(receipt.contains("audio and unsampled intervals have not been analyzed"))
        assertTrue(receipt.contains("next_offset"))
        assertTrue(receipt.endsWith("What happens?"))
    }
}
