package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ProposedFileContentTest {
    @Test fun exactPagesAndExportRetainWholeProposal() = fixture { session ->
        val text = "text 🌏\n".repeat(40_000)
        val source = source(session, text)
        val review = ProposedFileContent.admit(session, source) {}
        val combined = StringBuilder()
        var page = 0
        while (true) {
            val value = review.readPage(page++)
            combined.append(value.text)
            if (!value.hasNext) break
        }
        assertEquals(text, combined.toString())
        val bytes = ByteArrayOutputStream(); review.export(bytes)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), bytes.toByteArray())
        assertFalse(review.toString().contains(source.handle.serialized))
    }
    @Test fun ownerRevocationBeforeDestinationPreflightRejects() = fixture { session ->
        var revoked = false
        val review = ProposedFileContent.admit(session, source(session, "hello")) { if (revoked) throw CancellationException() }
        revoked = true
        assertThrows(CancellationException::class.java) { review.verify() }
        val bytes = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) { review.export(bytes) }
        assertEquals(0, bytes.size())
    }
    @Test fun uiCancellationInterruptsIntegrityScanWithoutRevokingRunOwner() = fixture { session ->
        val review = ProposedFileContent.admit(session, source(session, "x".repeat(1_000_000))) {}
        var checks = 0
        assertThrows(CancellationException::class.java) {
            review.verify { if (++checks == 8) throw CancellationException("UI closed") }
        }
        assertEquals(8, checks)
        review.verify() // Independent run owner remains authorized.
    }
    @Test fun foreignOwnerAndWrongKindRejected() = fixture { session ->
        val source = source(session, "hello", OwnedResponseContentStore.Kind.ANSWER)
        assertThrows(IllegalArgumentException::class.java) { ProposedFileContent.admit(session, source) {} }
    }
    @Test fun changedDescriptorCannotBeReviewed() = fixture { session ->
        val good = source(session, "hello")
        val forged = StreamedFileArgumentExtractor.Source(good.handle, good.bytes + 1, good.utf16Units, good.sha256, good.kind)
        assertThrows(IllegalStateException::class.java) { ProposedFileContent.admit(session, forged) {} }
    }
    private fun source(session: OwnedResponseContentStore.Session, text: String,
        kind: OwnedResponseContentStore.Kind = OwnedResponseContentStore.Kind.TOOL_CONTENT): StreamedFileArgumentExtractor.Source {
        val c = session.begin(kind).use { writer ->
            writer.append(text)
            writer.sealCompleted { input -> val b = ByteArray(1024); while(input.read(b) >= 0) {} }.content
        }
        return StreamedFileArgumentExtractor.Source(c.handle, c.byteLength, c.utf16Length, c.sha256, c.kind)
    }
    private fun fixture(body: (OwnedResponseContentStore.Session) -> Unit) {
        val root = Files.createTempDirectory("proposed-content-")
        try {
            val store = OwnedResponseContentStore(root, OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0),inlineThreshold=32)
            body(store.authorize(OwnedResponseContentStore.Owner("session","run","call")))
        } finally { root.toFile().deleteRecursively() }
    }
}
