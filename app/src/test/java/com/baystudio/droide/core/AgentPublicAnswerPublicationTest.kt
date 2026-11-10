package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AgentPublicAnswerPublicationTest {
    @Test fun blankAnswerDoesNotAttachRefToFallback() {
        val result=AgentPublicAnswerPublication.complete(" ".repeat(150_000),100_000,
            {error("No archive for blank fallback")},"run","item",{})
        assertNull(result.reference)
        assertEquals(100_000,result.display.length)
    }
    @Test fun inlineDoesNotOpenArchive() {
        val result = AgentPublicAnswerPublication.complete("small", 100_000,
            archive = { error("Unexpected archive") }, runId="run", itemId="item", checkOwner={})
        assertEquals("small", result.display)
        assertNull(result.reference)
    }
    @Test fun overDisplayLimitRetainsExactTextIncludingSplitSurrogates() {
        val root=Files.createTempDirectory("public-publication-")
        try {
            val store=OwnedResponseContentStore(root, OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0),inlineThreshold=32)
            val archive=AgentPublicContentArchive(store,"session",previewChars=512)
            val original="a".repeat(8191)+"🌏"+"0123456789".repeat(30_000)
            var checks=0
            val result=AgentPublicAnswerPublication.complete(original,100_000,{archive},"run","item",{checks++})
            assertTrue(checks>30)
            assertTrue(result.display.length<=512)
            val bytes=ByteArrayOutputStream()
            archive.export(requireNotNull(result.reference),bytes)
            assertArrayEquals(original.toByteArray(Charsets.UTF_8),bytes.toByteArray())
        } finally {root.toFile().deleteRecursively()}
    }
    @Test fun staleOwnerNeverOpensArchiveEvenForSmallAnswer() {
        assertThrows(CancellationException::class.java) {
            AgentPublicAnswerPublication.complete("small",100_000,{error("Must not open")},"run","item",{throw CancellationException()})
        }
    }
    @Test fun cancellationDuringCaptureNeverPublishesReference() {
        val root=Files.createTempDirectory("public-publication-cancel-")
        try {
            val archive=AgentPublicContentArchive(OwnedResponseContentStore(root, OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0),inlineThreshold=32),"session")
            var checks=0
            assertThrows(CancellationException::class.java) {
                AgentPublicAnswerPublication.complete("x".repeat(120_000),100_000,{archive},"run","item",{if(++checks==3) throw CancellationException()})
            }
            assertEquals(3,checks)
        } finally {root.toFile().deleteRecursively()}
    }
}
