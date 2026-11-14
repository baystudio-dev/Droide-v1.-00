package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class OwnedProviderReplayWriterTest {
    private val disk = OwnedResponseContentStore.HeadroomAdmission({ 1L shl 40 }, 8192)
    private val owner = OwnedResponseContentStore.Owner("s", "r", "c")
    private val target = ProviderReplayTarget("provider", "model", ProviderReplayTarget.Protocol.OPENAI_RESPONSES, "https://example.invalid/v1")
    private fun drain(input: InputStream) { val buffer = ByteArray(4096); while (input.read(buffer) >= 0) {} }
    private inline fun fixture(block: (Path, OwnedResponseContentStore.Session) -> Unit) {
        val path = Files.createTempDirectory("owned-replay-test-")
        try { block(path, OwnedResponseContentStore(path, disk, 16).authorize(owner)) }
        finally { Files.walk(path).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    private fun content(session: OwnedResponseContentStore.Session, kind: OwnedResponseContentStore.Kind, text: String) =
        session.begin(kind).use { writer -> writer.append(text); writer.sealCompleted(::drain).content }
    private fun exactDocument(expected: String): (InputStream) -> Unit = { input ->
        val expectedBytes = expected.toByteArray(); var offset = 0
        val buffer = ByteArray(127)
        while (true) { val n = input.read(buffer); if (n < 0) break
            require(offset + n <= expectedBytes.size)
            repeat(n) { require(buffer[it] == expectedBytes[offset + it]) }; offset += n
        }
        require(offset == expectedBytes.size)
    }

    @Test fun publicScalarEscapesQuotesControlsAndPreservesUnicodeAcrossChunks() = fixture { _, session ->
        val text = "A\"\\\n\t\u0000🌏" + (0..31).map(Int::toChar).joinToString("")
        val stored = content(session, OwnedResponseContentStore.Kind.ANSWER, text)
        val admitted = OwnedNormalizedResponse.PublicText.admit(session, stored.handle)
        val output = ByteArrayOutputStream()
        OwnedProviderReplayWriter.writePublicText(session, admitted, output)
        val expected = "\"A\\\"\\\\\\u000a\\u0009\\u0000🌏" + (0..31).joinToString("") { "\\u00%02x".format(it) } + "\""
        assertEquals(expected, output.toString("UTF-8"))
        assertFalse(output.toString("UTF-8").contains(stored.handle.serialized))
        assertFalse(admitted.toString().contains(stored.handle.serialized))
    }

    @Test fun privateReplayPreservesExactSignedObjectBytesAndNeverHandles() = fixture { _, session ->
        val json = " {\n \"signature\" : \"s\\u0069g\", \"opaque\" : [1,2,{\"text\":\"🌏\"}] } "
        val stored = content(session, OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION, json)
        val admitted = OwnedNormalizedResponse.PrivateReplay.admit(session, stored.handle, target, exactDocument(json))
        val output = ByteArrayOutputStream()
        OwnedProviderReplayWriter.writePrivateObject(session, admitted, target, output)
        assertArrayEquals(json.toByteArray(), output.toByteArray())
        assertFalse(output.toString("UTF-8").contains(stored.handle.serialized))
        assertFalse(admitted.toString().contains(stored.handle.serialized))
        assertEquals(admitted, OwnedNormalizedResponse(target, null, admitted).privateReplay)
    }

    @Test fun privateReplayCannotChangeProviderModelProtocolOrEndpointBeforeOutput() = fixture { _, session ->
        val stored = content(session, OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION, "{}")
        val source = OwnedNormalizedResponse.PrivateReplay.admit(session, stored.handle, target, exactDocument("{}"))
        for (changed in listOf(target.copy(providerId = "other"), target.copy(modelId = "other"),
            target.copy(protocol = ProviderReplayTarget.Protocol.ANTHROPIC_MESSAGES), target.copy(endpointIdentity = "https://other.invalid"))) {
            val output = ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java) { OwnedProviderReplayWriter.writePrivateObject(session, source, changed, output) }
            assertEquals(0, output.size())
            assertThrows(IllegalArgumentException::class.java) { OwnedNormalizedResponse(changed, null, source) }
        }
    }

    @Test fun incompleteWrongKindAndCrossOwnerCannotBeAdmitted() = fixture { root, session ->
        val answer = content(session, OwnedResponseContentStore.Kind.ANSWER, "{}")
        assertThrows(IllegalStateException::class.java) { OwnedNormalizedResponse.PrivateReplay.admit(session, answer.handle, target, exactDocument("{}")) }
        val private = content(session, OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION, "{}")
        assertThrows(IllegalStateException::class.java) { OwnedNormalizedResponse.PublicText.admit(session, private.handle) }
        val partial = session.begin(OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION).use { it.append("{}"); it.sealIncomplete() }
        assertThrows(IllegalStateException::class.java) { OwnedNormalizedResponse.PrivateReplay.admit(session, partial.handle, target, exactDocument("{}")) }
        val other = OwnedResponseContentStore(root, disk).authorize(owner.copy(callId = "other"))
        assertThrows(IllegalStateException::class.java) { OwnedNormalizedResponse.PublicText.admit(other, answer.handle) }
    }

    @Test fun malformedStructureAndRejectedDuplicateSemanticsNeverProduceReplayReference() = fixture { _, session ->
        for (invalid in listOf("[1,2]", "{\"a\":", "{}{}", "{\"a\":NaN}")) {
            val stored = content(session, OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION, invalid)
            var semanticRan = false
            assertThrows(IllegalArgumentException::class.java) { OwnedNormalizedResponse.PrivateReplay.admit(session, stored.handle, target) { semanticRan = true; drain(it) } }
            assertFalse(semanticRan)
        }
        val duplicates = content(session, OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION, "{\"id\":1,\"id\":2}")
        // Structural validator deliberately accepts duplicates; trusted provider semantics must reject.
        assertThrows(IllegalArgumentException::class.java) { OwnedNormalizedResponse.PrivateReplay.admit(session, duplicates.handle, target) {
            drain(it); throw IllegalArgumentException("Duplicate native identity")
        } }
        val valid = content(session, OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION, "{}")
        assertThrows(IllegalStateException::class.java) { OwnedNormalizedResponse.PrivateReplay.admit(session, valid.handle, target) { it.read() } }
    }

    @Test fun corruptionAfterAdmissionFailsBeforeWritingAnyOutput() = fixture { root, session ->
        val stored = content(session, OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION, "{}")
        val admitted = OwnedNormalizedResponse.PrivateReplay.admit(session, stored.handle, target, exactDocument("{}"))
        Files.write(root.resolve(stored.handle.id + ".blob"), "[]".toByteArray(), StandardOpenOption.TRUNCATE_EXISTING)
        val output = ByteArrayOutputStream()
        assertThrows(IllegalStateException::class.java) { OwnedProviderReplayWriter.writePrivateObject(session, admitted, target, output) }
        assertEquals(0, output.size())
    }

    @Test fun cancellationBeforeEmissionWritesNothingAndMidEmissionNeverLooksComplete() = fixture { _, session ->
        val stored = content(session, OwnedResponseContentStore.Kind.ANSWER, "x".repeat(30_000))
        val source = OwnedNormalizedResponse.PublicText.admit(session, stored.handle)
        val output = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) { OwnedProviderReplayWriter.writePublicText(session, source, output) { throw CancellationException() } }
        assertEquals(0, output.size())
        assertThrows(CancellationException::class.java) { OwnedProviderReplayWriter.writePublicText(session, source, output) { if (output.size() > 0) throw CancellationException() } }
        assertEquals("\"", output.toString("UTF-8"))
    }

    @Test fun largePublicAndPrivateContentStreamWithoutWholeResponseStringOrByteArray() = fixture { _, session ->
        val chunk = "0123456789abcdef".repeat(512)
        for (private in listOf(false, true)) {
            val hash = MessageDigest.getInstance("SHA-256")
            fun expected(bytes: String) { hash.update(bytes.toByteArray()) }
            val kind = if (private) OwnedResponseContentStore.Kind.PRIVATE_CONTINUATION else OwnedResponseContentStore.Kind.ANSWER
            val stored = session.begin(kind).use { writer ->
                if (private) { writer.append("{\"opaque\":\""); expected("{\"opaque\":\"") } else expected("\"")
                repeat(1100) { writer.append(chunk); expected(chunk) }
                if (private) { writer.append("\"}"); expected("\"}") } else expected("\"")
                writer.sealCompleted(::drain).content
            }
            val actual = MessageDigest.getInstance("SHA-256"); var size = 0L
            val sink = object : OutputStream() {
                override fun write(value: Int) { actual.update(value.toByte()); size++ }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { actual.update(bytes, offset, length); size += length }
            }
            if (private) {
                // The generated fixture is known independently. This is serialization testing,
                // not a production provider schema implementation.
                val source = OwnedNormalizedResponse.PrivateReplay.admit(session, stored.handle, target, ::drain)
                OwnedProviderReplayWriter.writePrivateObject(session, source, target, sink)
            } else OwnedProviderReplayWriter.writePublicText(session, OwnedNormalizedResponse.PublicText.admit(session, stored.handle), sink)
            assertTrue(size > 8_000_000)
            assertArrayEquals(hash.digest(), actual.digest())
        }
    }
}
