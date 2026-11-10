package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentAudioTranscriptionTest {
    @get:Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private val config = AgentConfig(providerId = "openai", baseUrl = "https://api.openai.com/v1", apiKey = "test-transcription-secret", model = "unchanged-chat")
    private fun args() = buildJsonObject { put("path", "audio.wav"); put("destination", "transcript.txt"); put("model", "speech-model-current"); put("language", "id") }
    private fun context(root: File, mode: AgentMode = AgentMode.BUILD, current: () -> Boolean = { true }): AgentTools.Ctx {
        val b = agentWorkflowContext(root, mode = mode)
        return AgentTools.Ctx(b.files, b.terminal, b.git, b.approvals, b.questions, b.history, b.perms, mode, b.llm,
            config, root, b.todos, b.sessionId, b.lsp, appContext = RuntimeEnvironment.getApplication(), mediaRequestCurrent = current)
    }
    private suspend fun fixture(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("speech-tool-").toFile()
        root.resolve("audio.wav").writeBytes(mediaFixture("sample.wav"))
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun sendsExactAudioWithSeparateSpeechModelAndPersistsTranscriptWithoutChangingOriginal() = runBlocking { fixture { root ->
        val gates = mutableListOf<String>(); var requests = 0
        val tool = AgentAudioTranscription { provider, request ->
            requests++; assertEquals("openai", provider.id); assertEquals("/v1/audio/transcriptions", request.url.encodedPath)
            assertEquals("Bearer ${config.apiKey}", request.header("Authorization"))
            val buffer = Buffer(); request.body!!.writeTo(buffer); val bytes = buffer.readByteArray()
            val text = bytes.toString(Charsets.ISO_8859_1)
            assertTrue(text.contains("speech-model-current")); assertTrue(text.contains("RIFF")); assertFalse(text.contains("unchanged-chat"))
            200 to """{"text":"Selamat pagi, dunia."}""".toByteArray()
        }
        val result = tool.execute(args(), context(root)) { action, _ -> gates += action; null }
        assertEquals(listOf("read", "edit", "webfetch"), gates); assertEquals(1, requests)
        assertEquals("Selamat pagi, dunia.", root.resolve("transcript.txt").readText()); assertFalse(result.contains(config.apiKey))
        assertArrayEquals(mediaFixture("sample.wav"), root.resolve("audio.wav").readBytes())
    } }
    @Test fun permissionDenialModeAndExistingDestinationNeverUpload() = runBlocking { fixture { root ->
        val tool = AgentAudioTranscription { _, _ -> error("Must not upload") }
        assertTrue(runCatching { tool.execute(args(), context(root, AgentMode.REVIEW)) { _, _ -> null } }.isFailure)
        assertEquals("DENIED", tool.execute(args(), context(root)) { _, _ -> "DENIED" })
        root.resolve("transcript.txt").writeText("keep")
        assertTrue(runCatching { tool.execute(args(), context(root)) { _, _ -> null } }.isFailure)
        assertEquals("keep", root.resolve("transcript.txt").readText())
    } }
    @Test fun service500HasNoRetryNoRawBodyAndNoSuccessFile() = runBlocking { fixture { root ->
        var calls = 0
        val tool = AgentAudioTranscription { _, _ -> calls++; 500 to "private ${config.apiKey}".toByteArray() }
        val error = runCatching { tool.execute(args(), context(root)) { _, _ -> null } }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("HTTP 500")); assertFalse(error.message!!.contains(config.apiKey))
        assertEquals(1, calls); assertFalse(root.resolve("transcript.txt").exists())
    } }
    @Test fun retirementAfterPaidResponsePreventsPublicationWithoutRetry() = runBlocking { fixture { root ->
        var current = true; var calls = 0
        val tool = AgentAudioTranscription { _, _ -> calls++; current = false; 200 to """{"text":"finished"}""".toByteArray() }
        assertTrue(runCatching { tool.execute(args(), context(root, current = { current })) { _, _ -> null } }.isFailure)
        assertFalse(root.resolve("transcript.txt").exists()); assertEquals(1, calls)
    } }
}
