package com.baystudio.droide.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SourceArtifactPublisherTest {
    private val hash = "a".repeat(64)
    private val owner = OwnedResponseContentStore.Owner("session", "run", "call")
    private class Disk : OwnedResponseContentStore.DiskAdmission {
        var fail = false
        override fun reserve(bytes: Long): AutoCloseable { if (fail) throw IOException("full"); return AutoCloseable {} }
    }
    private fun fixture(block: (Path, Path, Disk, OwnedResponseContentStore.Session, OwnedResponseContentStore.Handle) -> Unit) {
        val tmp = Files.createTempDirectory("artifact-source")
        try {
            val project = Files.createDirectory(tmp.resolve("project")); val private = Files.createDirectory(tmp.resolve("private"))
            val disk = Disk(); val store = OwnedResponseContentStore(Files.createDirectory(tmp.resolve("blobs")), disk)
            val session = store.authorize(owner)
            val handle = session.begin(OwnedResponseContentStore.Kind.TOOL_CONTENT).use { writer ->
                repeat(80) { writer.append("abc😀\r\n".repeat(1024)) }
                writer.sealCompleted { input -> val b = ByteArray(8192); while (input.read(b) >= 0) {} }.content.handle
            }
            block(project, private, disk, session, handle)
        } finally { tmp.toFile().deleteRecursively() }
    }
    @Test fun largePublishReopenAndNoDuplicate() = fixture { root, private, disk, session, handle ->
        val backend = SourceArtifactPublisher(root.toFile(), private, disk)
        val id = UUID.randomUUID().toString()
        val o = backend.prepare(id, owner, hash, " report\r\n ", session, handle)
        assertFalse(Files.exists(root.resolve(".droide")))
        assertTrue(o.bytes > 500_000)
        var approvals = 0
        val receipt = backend.publish(o, { approvals++ })
        assertTrue(receipt.durabilityConfirmed); assertEquals(2, approvals)
        val expected = "# report\n\n" + "abc😀\r\n".repeat(1024 * 80)
        assertArrayEquals(expected.toByteArray(), Files.readAllBytes(root.resolve(o.path)))
        val reopened = SourceArtifactPublisher(root.toFile(), private, disk)
        disk.fail = true // Recovery performs no disk allocation.
        val recovery = reopened.inspect(id, owner, hash) as SourceArtifactPublisher.Recovery.Published
        assertTrue(recovery.receipt.durabilityConfirmed)
        assertEquals(o.sha256, recovery.receipt.observation.sha256)
        assertEquals(o.path, backend.publish(o, { error("No fresh execution") }).observation.path)
        assertEquals(1L, Files.list(root.resolve(".droide/artifacts")).use { it.count() })
    }
    @Test fun deniedBeforeProjectSideEffectsAndIdentityMismatch() = fixture { root, private, disk, session, handle ->
        val backend = SourceArtifactPublisher(root.toFile(), private, disk); val id = UUID.randomUUID().toString()
        val o = backend.prepare(id, owner, hash, "original", session, handle)
        assertThrows(IllegalStateException::class.java) { backend.publish(o, { error("denied") }) }
        assertFalse(Files.exists(root.resolve(".droide")))
        assertThrows(IllegalStateException::class.java) { backend.prepare(id, owner, hash, "other", session, handle) }
        assertThrows(IllegalStateException::class.java) { backend.inspect(id, owner.copy(callId="other"), hash) }
    }
    @Test fun collisionNeverOverwritesAndDiskFailureDoesNotPublish() = fixture { root, private, disk, session, handle ->
        val b = SourceArtifactPublisher(root.toFile(), private, disk)
        val o = b.prepare(UUID.randomUUID().toString(), owner, hash, "note", session, handle)
        Files.createDirectories(root.resolve(".droide/artifacts")); Files.write(root.resolve(o.path), byteArrayOf(7))
        assertThrows(IllegalStateException::class.java) { b.publish(o, {}) }
        assertArrayEquals(byteArrayOf(7), Files.readAllBytes(root.resolve(o.path)))
        val p = b.prepare(UUID.randomUUID().toString(), owner, hash, "note", session, handle)
        disk.fail = true
        assertThrows(IOException::class.java) { b.publish(p, {}) }
        assertFalse(Files.exists(root.resolve(p.path)))
    }
    @Test fun exportFenceBeforeProjectMkdir() = fixture { root, private, disk, session, handle ->
        val b = SourceArtifactPublisher(root.toFile(), private, disk)
        val o = b.prepare(UUID.randomUUID().toString(), owner, hash, "note", session, handle)
        ExportWriterAdmission.beginCapture(root.toFile()).use {
            assertThrows(ExportCaptureBusyException::class.java) { b.publish(o, {}) }
            assertFalse(Files.exists(root.resolve(".droide")))
        }
    }
    @Test fun afterRenameDurabilityErrorCarriesAttributionWithoutRetry() = fixture { root, private, disk, session, handle ->
        val b = SourceArtifactPublisher(root.toFile(), private, disk, SourceArtifactPublisher.Publication { r, src, target, dst ->
            Files.move(r.resolve(src), target.resolve(dst)); AnchoredWorkspaceFiles.MoveReceipt(true, 5)
        })
        val o = b.prepare(UUID.randomUUID().toString(), owner, hash, "note", session, handle)
        val failure = assertThrows(SourceArtifactPublisher.PublicationFailure::class.java) { b.publish(o, {}) }
        assertNotNull(failure.receipt); assertFalse(failure.receipt!!.durabilityConfirmed)
        assertTrue(b.inspect(o.operationId, owner, hash) is SourceArtifactPublisher.Recovery.Published)
        assertFalse(b.publish(o, { error("must not repeat") }).durabilityConfirmed)
    }
    @Test fun substitutedStageNeverProducesReceipt() = fixture { root, private, disk, session, handle ->
        val b = SourceArtifactPublisher(root.toFile(), private, disk, SourceArtifactPublisher.Publication { r, src, target, dst ->
            Files.delete(r.resolve(src)); Files.write(r.resolve(src), byteArrayOf(9))
            Files.move(r.resolve(src), target.resolve(dst)); AnchoredWorkspaceFiles.MoveReceipt(true, 0)
        })
        val o = b.prepare(UUID.randomUUID().toString(), owner, hash, "note", session, handle)
        val f = assertThrows(SourceArtifactPublisher.PublicationFailure::class.java) { b.publish(o, {}) }
        assertNull(f.receipt)
        assertThrows(IllegalStateException::class.java) { b.inspect(o.operationId, owner, hash) }
    }
    @Test fun cancelledStageRetainedNeverAutomaticallyReexecuted() = fixture { root, private, disk, session, handle ->
        val b = SourceArtifactPublisher(root.toFile(), private, disk)
        val o = b.prepare(UUID.randomUUID().toString(), owner, hash, "note", session, handle)
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            b.publish(o, {}, { if (Files.exists(private.resolve(o.operationId).resolve("payload"))) throw java.util.concurrent.CancellationException() })
        }
        assertFalse(Files.exists(root.resolve(o.path)))
        assertThrows(IllegalStateException::class.java) { b.publish(o, {}) }
    }
    @Test fun publishedTamperFailsReadOnlyRecovery() = fixture { root, private, disk, session, handle ->
        val b = SourceArtifactPublisher(root.toFile(), private, disk)
        val o = b.prepare(UUID.randomUUID().toString(), owner, hash, "note", session, handle)
        b.publish(o, {})
        Files.write(root.resolve(o.path), byteArrayOf(8))
        assertTrue(b.inspect(o.operationId, owner, hash) is SourceArtifactPublisher.Recovery.Historical)
        assertArrayEquals(byteArrayOf(8), Files.readAllBytes(root.resolve(o.path)))
    }
    @Test fun historicalCommitProofSurvivesUserEditOrDeletionButCannotBeForged() = fixture { root, private, disk, session, handle ->
        val b = SourceArtifactPublisher(root.toFile(), private, disk)
        val o = b.prepare(UUID.randomUUID().toString(), owner, hash, "note", session, handle)
        assertThrows(Exception::class.java) { b.verify(SourceArtifactPublisher.Receipt(o, true, false)) }
        val published = b.publish(o, {})
        assertTrue(published.currentContentVerified)
        Files.write(root.resolve(o.path), byteArrayOf(1))
        val historical = b.verify(published)
        assertTrue(historical.durabilityConfirmed); assertFalse(historical.currentContentVerified)
        Files.delete(root.resolve(o.path))
        assertFalse(b.verify(published).currentContentVerified)
        assertTrue(b.inspect(o.operationId, owner, hash) is SourceArtifactPublisher.Recovery.Historical)
        assertThrows(IllegalStateException::class.java) { b.publish(o, {}) }
        assertFalse(Files.exists(root.resolve(o.path)))
        val other = b.prepare(UUID.randomUUID().toString(), owner, hash, "note", session, handle)
        assertThrows(Exception::class.java) { b.verify(SourceArtifactPublisher.Receipt(other, true, false)) }
    }

}
