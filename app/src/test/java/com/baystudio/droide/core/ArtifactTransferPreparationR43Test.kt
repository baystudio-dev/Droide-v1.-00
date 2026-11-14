package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ArtifactTransferPreparationR43Test {
    @Test fun admissionDoesNotInvokeNetworkValidationInsideTheStateStore() = withStore { store ->
        val descriptor = object : TrustedArtifactDescriptor by spec() {
            override fun validateDescriptor(): Unit = error("Network resolution must not run during transfer admission")
        }
        val queued = store.ensure(descriptor)
        assertEquals(ArtifactTransferState.QUEUED, queued.state)
        assertEquals(queued, store.read(queued.id))
        assertEquals(ArtifactTransferState.CANCELED, store.markCanceled(queued.id, "Stop")?.state)
    }

    @Test fun queuedAndClaimedWorkersHaveTruthfulPreparationDetails() = withStore { store ->
        val queued = store.ensure(spec())
        assertTrue(queued.detail.contains("Queued", ignoreCase = true))
        val running = checkNotNull(store.markRunning(queued.id, queued.requestIdentity, "b".repeat(32)))
        assertTrue(running.detail.contains("Preparing", ignoreCase = true))
        assertFalse(running.detail.contains("Downloaded"))
        store.markFailed(queued.id, "b".repeat(32), "fixture retry")
        val retry = store.ensure(spec())
        assertTrue(retry.detail.contains("Queued", ignoreCase = true))
        assertNotEquals(queued.requestIdentity, retry.requestIdentity)
    }

    @Test fun admissionRejectsUnsafeLiteralTargetsWithoutResolvingHostnames() = withStore { store ->
        for (url in listOf("http://example.com/a", "https://127.0.0.1/a", "https://10.0.0.1/a",
            "https://[::1]/a", "https://user:password@example.com/a", "https://example.com:70000/a")) {
            try {
                store.ensure(spec().copy(url = url))
                fail("Unsafe descriptor admitted: $url")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(ArtifactTransferState.QUEUED, store.ensure(spec()).state)
    }

    private fun spec() = TrustedArtifactSpec("offline-fixture", "https://offline-transfer.invalid/archive.bin",
        "a".repeat(64), "archive.bin", 1024L, 100L)

    private inline fun withStore(block: (ArtifactTransferStore) -> Unit) {
        val root = Files.createTempDirectory("droide-r43-preparation-").toFile()
        try { block(ArtifactTransferStore(root, 1, "a".repeat(32))) }
        finally { root.deleteRecursively() }
    }
}
