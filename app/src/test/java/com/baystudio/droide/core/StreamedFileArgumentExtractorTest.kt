package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class StreamedFileArgumentExtractorTest {
    private val disk = OwnedResponseContentStore.HeadroomAdmission({ 1L shl 40 }, 8192)
    private val owner = OwnedResponseContentStore.Owner("session", "run", "call")
    private fun drain(input: InputStream) { val b = ByteArray(8192); while (input.read(b) >= 0) {} }
    private inline fun fixture(block: (Path, OwnedResponseContentStore.Session) -> Unit) {
        val p = Files.createTempDirectory("file-args-test-")
        try { block(p, OwnedResponseContentStore(p, disk, 32).authorize(owner)) }
        finally { Files.walk(p).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    private fun args(session: OwnedResponseContentStore.Session, text: String) = session.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use {
        it.append(text); it.sealCompleted(::drain).content.handle
    }
    private fun text(session: OwnedResponseContentStore.Session, source: StreamedFileArgumentExtractor.Source): String =
        session.openPages(source.handle).use { pages -> buildString { while (true) append(pages.readPage(23) ?: break) } }
    private fun countMeta(root: Path): Long = Files.list(root).use { it.filter { p -> p.toString().endsWith(".meta") }.count() }
    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c -> when {
            c == '"' || c == '\\' -> { append('\\'); append(c) }
            c.code < 32 -> append("\\u%04x".format(c.code))
            else -> append(c)
        } }; append('"')
    }

    @Test fun writeAndEditSchemasProduceExactOwnedScalarSourcesAndCasMetadata() = fixture { _, session ->
        val raw = args(session, """{"content":"line\\one\n\uD83C\uDF0F","expected_change_version":8,"path":"src/a.kt","expected_document_id":"doc:1","expected_revision":7}""")
        val prepared = StreamedFileArgumentExtractor.extract(session, raw, StreamedFileArgumentExtractor.Tool.WRITE_FILE, "call1")
        assertEquals("src/a.kt", prepared.requestedPath)
        assertEquals("line\\one\n🌏", text(session, prepared.content!!))
        assertEquals(StreamedFileArgumentExtractor.ExpectedDocument("doc:1", 7, 8), prepared.expectedDocument)
        assertEquals(OwnedResponseContentStore.Kind.TOOL_CONTENT, prepared.content.kind)
        prepared.originalArguments.verify(session); prepared.content.verify(session)
        val edit = StreamedFileArgumentExtractor.extract(session,
            args(session, """{"replace":"","search":"old","path":"a"}"""), StreamedFileArgumentExtractor.Tool.EDIT_FILE, "call2")
        assertEquals("old", text(session, edit.search!!)); assertEquals("", text(session, edit.replacement!!))
        assertNull(edit.content); assertNull(edit.expectedDocument)
    }

    @Test fun duplicateDecodedKeysAndTrailingDataRejectBeforeAnyDerivedPublication() = fixture { root, session ->
        val invalid = listOf(
            """{"path":"a","content":"one","path":"b"}""",
            """{"path":"a","content":"one","pa\u0074h":"b"}""",
            """{"path":"a","content":"one","content":"two"}""",
            """{"path":"a","content":"one"}{}""",
            """{"path":"a","content":"one",}""",
            """{"path":"a","content":"one","expected_revision":1,"expected_revision":2}""",
        )
        for (json in invalid) {
            val raw = args(session, json); val before = countMeta(root)
            assertThrows(StreamedFileArgumentExtractor.ExtractionFailure::class.java) {
                StreamedFileArgumentExtractor.extract(session, raw, StreamedFileArgumentExtractor.Tool.WRITE_FILE, "call")
            }
            assertEquals(before, countMeta(root))
        }
    }

    @Test fun schemaTypesUnknownFieldsCountersAndIncompleteCasRejectExplicitly() = fixture { root, session ->
        val invalid = listOf(
            "{}", "[]", "null", """{"path":"a"}""", """{"content":"x"}""",
            """{"path":1,"content":"x"}""", """{"path":"a","content":null}""",
            """{"path":"a","content":[]}""", """{"path":"a","content":"x","extra":{}}""",
            """{"path":"a","content":"x","expected_revision":1}""",
            """{"path":"a","content":"x","expected_document_id":"d","expected_revision":-1,"expected_change_version":1}""",
            """{"path":"a","content":"x","expected_document_id":"d","expected_revision":2147483648,"expected_change_version":1}""",
            """{"path":"a","content":"x","expected_document_id":"d","expected_revision":"1","expected_change_version":1}""",
            """{"path":"a","content":"x","expected_document_id":"d","expected_revision":1.0,"expected_change_version":1}""",
            """{"path":"a","content":"x","expected_document_id":"d","expected_revision":01,"expected_change_version":1}""",
            """{"path":"a","content":"x","expected_document_id":"d","expected_revision":1e0,"expected_change_version":1}""",
            """{"path":"a","content":"x","expected_document_id":"invalid/id","expected_revision":1,"expected_change_version":1}""",
            """{"path":"\u0000","content":"x"}""",
        )
        for (json in invalid) {
            val raw = args(session, json); val before = countMeta(root)
            assertThrows(StreamedFileArgumentExtractor.ExtractionFailure::class.java) {
                StreamedFileArgumentExtractor.extract(session, raw, StreamedFileArgumentExtractor.Tool.WRITE_FILE, "call")
            }
            assertEquals(before, countMeta(root))
        }
        val raw = args(session, """{"path":"a","search":"","replace":"x"}""")
        assertThrows(StreamedFileArgumentExtractor.ExtractionFailure::class.java) {
            StreamedFileArgumentExtractor.extract(session, raw, StreamedFileArgumentExtractor.Tool.EDIT_FILE, "call")
        }
    }

    @Test fun allEscapesAndUnicodeFragmentBoundariesAreDecodedWithoutWholeScalar() = fixture { _, session ->
        val value = "x".repeat(2047) + "🌏" + "\"\\/\b\u000c\n\r\t" + "漢字"
        val encoded = quote(value).replace("🌏", "\\ud83c\\udf0f").replace("/", "\\/")
        val prepared = StreamedFileArgumentExtractor.extract(session,
            args(session, "{\"path\":\"a\",\"content\":$encoded}"), StreamedFileArgumentExtractor.Tool.WRITE_FILE, "call")
        assertEquals(value, text(session, prepared.content!!))
        assertEquals(value.length.toLong(), prepared.content.utf16Units)
    }

    @Test fun malformedStringEscapesAndSurrogatesNeverPublishDerivedContent() = fixture { root, session ->
        for (value in listOf("\\q", "\\uZZZZ", "\\uD800", "\\uDC00", "\\uD800x", "\\uD800\\uD800", "literal\nnewline")) {
            val raw = args(session, "{\"path\":\"a\",\"content\":\"$value\"}")
            val before = countMeta(root)
            assertThrows(StreamedFileArgumentExtractor.ExtractionFailure::class.java) {
                StreamedFileArgumentExtractor.extract(session, raw, StreamedFileArgumentExtractor.Tool.WRITE_FILE, "call")
            }
            assertEquals(before, countMeta(root))
        }
    }

    @Test fun largeWriteContentPassesFormerLimitsWithExactStreamingDigest() = fixture { _, session ->
        val chunk = "abcdefgh01234567".repeat(512)
        val hash = MessageDigest.getInstance("SHA-256")
        val raw = session.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use { writer ->
            writer.append("{\"path\":\"large.txt\",\"content\":\"")
            repeat(1100) { writer.append(chunk); hash.update(chunk.toByteArray()) }
            writer.append("\"}"); writer.sealCompleted(::drain).content.handle
        }
        val prepared = StreamedFileArgumentExtractor.extract(session, raw, StreamedFileArgumentExtractor.Tool.WRITE_FILE, "large-call")
        val content = prepared.content!!
        assertTrue(content.bytes > 8_000_000)
        assertEquals(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, content.sha256)
        content.verify(session)
    }

    @Test fun editSearchAndReplacementCanExceedLegacyPlannerLimitWithoutExecutingEdit() = fixture { _, session ->
        val search = "s".repeat(200_000); val replace = "r".repeat(300_000)
        val raw = args(session, "{\"path\":\"a\",\"search\":${quote(search)},\"replace\":${quote(replace)}}")
        val prepared = StreamedFileArgumentExtractor.extract(session, raw, StreamedFileArgumentExtractor.Tool.EDIT_FILE, "edit")
        assertEquals(200_000L, prepared.search!!.bytes); assertEquals(300_000L, prepared.replacement!!.bytes)
        assertEquals(search, text(session, prepared.search)); assertEquals(replace, text(session, prepared.replacement))
    }

    @Test fun randomValidStringsRoundTripThroughIndependentEncoder() = fixture { _, session ->
        val random = java.util.Random(7917)
        val symbols = listOf("a", "\n", "\u0000", "\"", "\\", "🌏", "漢", "é", "\t", "\r", "/")
        repeat(100) { index ->
            val expected = buildString { repeat(300) { append(symbols[random.nextInt(symbols.size)]) } }
            val raw = args(session, "{\"content\":${quote(expected)},\"path\":\"a\"}")
            val prepared = StreamedFileArgumentExtractor.extract(session, raw, StreamedFileArgumentExtractor.Tool.WRITE_FILE, "call$index")
            assertEquals(expected, text(session, prepared.content!!))
        }
    }

    @Test fun incompleteWrongKindAndOtherOwnerCannotEnterExtractor() = fixture { root, session ->
        val partial = session.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use { it.append("{}"); it.sealIncomplete() }
        assertThrows(IllegalStateException::class.java) { StreamedFileArgumentExtractor.extract(session, partial.handle, StreamedFileArgumentExtractor.Tool.WRITE_FILE, "call") }
        val wrong = session.begin(OwnedResponseContentStore.Kind.ANSWER).use { it.append("{}"); it.sealCompleted(::drain).content }
        assertThrows(IllegalArgumentException::class.java) { StreamedFileArgumentExtractor.extract(session, wrong.handle, StreamedFileArgumentExtractor.Tool.WRITE_FILE, "call") }
        val raw = args(session, """{"path":"a","content":"x"}""")
        val other = OwnedResponseContentStore(root, disk).authorize(owner.copy(callId = "other"))
        assertThrows(IllegalStateException::class.java) { StreamedFileArgumentExtractor.extract(other, raw, StreamedFileArgumentExtractor.Tool.WRITE_FILE, "call") }
    }

    @Test fun cancellationAfterFinalSealReturnsRecoveryNotPreparedCandidate() = fixture { root, session ->
        val raw = args(session, """{"path":"a","content":"finished scalar"}""")
        val before = countMeta(root)
        val failure = assertThrows(StreamedFileArgumentExtractor.ExtractionCancelled::class.java) {
            StreamedFileArgumentExtractor.extract(session, raw, StreamedFileArgumentExtractor.Tool.WRITE_FILE, "call") {
                if (countMeta(root) > before) throw CancellationException()
            }
        }
        assertEquals(1, failure.recoveryHandles.size)
        assertEquals(OwnedResponseContentStore.Kind.TOOL_CONTENT, session.open(failure.recoveryHandles.single()).kind)
        assertEquals(before + 1, countMeta(root))
    }

    @Test fun cancellationBetweenFieldSealsReturnsRetainedArtifactsWithoutPreparedResult() = fixture { root, session ->
        val raw = args(session, """{"path":"a","search":"before","replace":"after"}""")
        val before = countMeta(root)
        val failure = assertThrows(StreamedFileArgumentExtractor.ExtractionCancelled::class.java) {
            StreamedFileArgumentExtractor.extract(session, raw, StreamedFileArgumentExtractor.Tool.EDIT_FILE, "call") {
                if (countMeta(root) > before) throw CancellationException()
            }
        }
        assertEquals(1, failure.recoveryHandles.size)
        val retained = session.open(failure.recoveryHandles.single())
        assertEquals(OwnedResponseContentStore.Kind.TOOL_CONTENT, retained.kind)
        assertEquals(before + 1, countMeta(root))
        // Retained scalar bytes are not a prepared tool call or execution permission.
        assertEquals(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS, session.openCompleted(raw).content.kind)
    }

    @Test fun cancellationDuringExtractionDoesNotPublishAnyPreparedField() = fixture { root, session ->
        val raw = args(session, "{\"path\":\"a\",\"content\":\"${"x".repeat(200_000)}\"}")
        val before = countMeta(root); var checks = 0
        assertThrows(CancellationException::class.java) {
            StreamedFileArgumentExtractor.extract(session, raw, StreamedFileArgumentExtractor.Tool.WRITE_FILE, "call") {
                if (++checks > 8) throw CancellationException()
            }
        }
        assertEquals(before, countMeta(root))
    }
}
