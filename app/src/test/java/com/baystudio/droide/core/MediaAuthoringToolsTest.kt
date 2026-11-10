package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

internal class MediaTestTerminal : ITerminalSession {
    override val output = MutableStateFlow("")
    var calls = 0
    var after: () -> Unit = {}
    var timeout = false
    override fun start() = Unit
    override fun send(cmd: String) = error("No human shell input")
    override suspend fun execOnce(cmd: String, timeoutMs: Long) = error("Use argv")
    override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult {
        calls++
        if (timeout) return ExecResult(124, "", true)
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        val result = ExecResult(process.waitFor(), text, false)
        after(); return result
    }
    override fun interrupt() = error("Never interrupt human shell")
    override fun clear() = Unit
    override fun destroy() = Unit
}
internal fun mediaWorker() = File(System.getProperty("droide.source", "."), "app/src/main/assets/agent-media/worker.py").readText()
internal fun mediaFixture(name: String) = File(System.getProperty("droide.source", "."), "app/src/test/resources/media/$name").readBytes()
internal fun mediaSha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

class MediaAuthoringToolsTest {
    @get:Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private suspend fun fixture(block: suspend (File, MediaTestTerminal, MediaAuthoringTools) -> Unit) {
        val root = Files.createTempDirectory("media-tools-").toFile(); val terminal = MediaTestTerminal()
        try { block(root, terminal, MediaAuthoringTools(FileRepository(root), terminal, ::mediaWorker)) }
        finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private suspend fun call(tools: MediaAuthoringTools, kind: String, args: JsonObject, mode: AgentMode = AgentMode.BUILD): JsonObject =
        Json.parseToJsonElement(tools.execute(kind, args, mode) { _, _ -> null }).jsonObject

    @Test fun nativeDocxReadPagesWithoutLaunchingTerminalAndBuiltinReadLeavesNoRecoveryFolder() = runBlocking { fixture { root, terminal, tools ->
        root.resolve("input.docx").writeBytes(mediaFixture("sample.docx"))
        val first = call(tools, "document", buildJsonObject { put("operation", "read"); put("path", "input.docx") }, AgentMode.PLAN)
        assertTrue(first.toString(), first["text"]!!.jsonPrimitive.content.contains("Hello world")); assertEquals(0, terminal.calls)
        assertEquals(mediaSha(mediaFixture("sample.docx")), first["source_sha256"]!!.jsonPrimitive.content)
        assertTrue(root.resolve(".droide/media-jobs").listFiles().orEmpty().isEmpty())
    } }

    @Test fun pdfInspectionAndReorderingUseRealWorkerAndPublishOnlyNewFile() = runBlocking { fixture { root, terminal, tools ->
        val source = mediaFixture("sample.pdf"); root.resolve("file.pdf").writeBytes(source)
        val inspected = call(tools, "document", buildJsonObject { put("operation", "inspect"); put("path", "file.pdf") })
        val result = call(tools, "document", buildJsonObject {
            put("operation", "pdf_pages"); put("path", "file.pdf"); put("destination", "out/reordered.pdf")
            put("expected_sha256", inspected["source_sha256"]!!); put("pages", buildJsonArray { add(2); add(1) })
        })
        assertEquals(JsonPrimitive(true), result["persisted_to_project"]); assertTrue(root.resolve("out/reordered.pdf").isFile)
        assertArrayEquals(source, root.resolve("file.pdf").readBytes()); assertEquals(2, terminal.calls)
    } }

    @Test fun modeAndPermissionDenialNeverLaunchWorkerOrCreateOutput() = runBlocking { fixture { root, terminal, tools ->
        val args = buildJsonObject { put("operation", "create_docx"); put("destination", "out.docx"); put("content", "test") }
        assertTrue(tools.execute("document", args, AgentMode.REVIEW) { _, _ -> null }.startsWith("DENIED"))
        val actions = mutableListOf<String>()
        assertEquals("DENIED", tools.execute("document", args, AgentMode.BUILD) { action, _ -> actions += action; "DENIED" })
        assertEquals(listOf("edit"), actions); assertEquals(0, terminal.calls); assertFalse(root.resolve("out.docx").exists())
    } }

    @Test fun staleShaExistingFileAndTraversalDoNotExecuteOrOverwrite() = runBlocking { fixture { root, terminal, tools ->
        root.resolve("file.pdf").writeBytes(mediaFixture("sample.pdf"))
        for (target in listOf("../escape.pdf", ".droide/secret.pdf", "file.pdf", "new.pdf")) {
            val attempt = runCatching { call(tools, "document", buildJsonObject {
                put("operation", "pdf_pages"); put("path", "file.pdf"); put("destination", target)
                put("expected_sha256", "0".repeat(64)); put("pages", buildJsonArray { add(1) })
            }) }
            assertTrue(target, attempt.isFailure)
        }
        assertEquals(0, terminal.calls); assertFalse(root.resolve("new.pdf").exists())
    } }

    @Test fun retirementAfterWorkerCompletionBlocksPublication() = runBlocking { fixture { root, terminal, tools ->
        var current = true; terminal.after = { current = false }
        val attempt = runCatching { tools.execute("document", buildJsonObject {
            put("operation", "create_docx"); put("content", "draft"); put("destination", "out.docx")
        }, AgentMode.BUILD, current = { check(current) { "Retired" } }) { _, _ -> null } }
        assertTrue(attempt.isFailure); assertFalse(root.resolve("out.docx").exists()); assertEquals(1, terminal.calls)
    } }

    @Test fun timeoutRetainsOnlyPrivateStageAndDoesNotPublish() = runBlocking { fixture { root, terminal, tools ->
        terminal.timeout = true
        assertTrue(runCatching { call(tools, "document", buildJsonObject {
            put("operation", "create_docx"); put("content", "draft"); put("destination", "out.docx")
        }) }.isFailure)
        assertFalse(root.resolve("out.docx").exists()); assertEquals(1, root.resolve(".droide/media-jobs").listFiles()!!.size)
    } }

    @Test fun mediaWorkParticipatesInExportFenceAndReleasesItAfterCompletion() = runBlocking { fixture { root, terminal, tools ->
        terminal.after = { assertTrue(ExportWriterAdmission.observe(root).writers.any { it.label == "Media authoring" }) }
        val args = buildJsonObject { put("operation", "create_docx"); put("content", "draft"); put("destination", "out.docx") }
        ExportWriterAdmission.withQuiescentCapture(root) {
            assertTrue(runCatching { call(tools, "document", args) }.exceptionOrNull() is ExportCaptureBusyException)
        }
        assertEquals(0, terminal.calls)
        assertEquals(JsonPrimitive(true), call(tools, "document", args)["ok"])
        assertTrue(ExportWriterAdmission.observe(root).writers.isEmpty())
    } }

    @Test fun ffmpegEditingCreatesWavWithOriginalPreserved() = runBlocking { fixture { root, _, tools ->
        val source = mediaFixture("sample.wav"); root.resolve("input.wav").writeBytes(source)
        val inspected = call(tools, "audio", buildJsonObject { put("operation", "inspect"); put("path", "input.wav") })
        assertEquals(JsonPrimitive(false), inspected["speech_transcribed"])
        val result = call(tools, "audio", buildJsonObject {
            put("operation", "edit"); put("path", "input.wav"); put("destination", "clip.wav"); put("end_seconds", .1)
            put("expected_sha256", inspected["source_sha256"]!!); put("sample_rate", 8000); put("channels", 1)
        })
        assertEquals(JsonPrimitive(true), result["ok"]); assertTrue(root.resolve("clip.wav").length() > 44)
        assertArrayEquals(source, root.resolve("input.wav").readBytes())
    } }
}
