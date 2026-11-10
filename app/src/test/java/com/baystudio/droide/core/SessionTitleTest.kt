package com.baystudio.droide.core

import android.app.Application
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SessionTitleTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()
    private val config = AgentConfig(providerId = "openai", model = "gpt-4o-mini", reasoningEffort = ReasoningEffort.HIGH)
    private fun response(title: String, reason: String = "stop") = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            put("finish_reason", reason)
            put("message", buildJsonObject { put("role", "assistant"); put("content", title) })
        }) })
    }.toString()
    private fun session(text: String = "Tolong perbaiki tombol export PDF bang") = DroideSession("s-title-test", text.take(40),
        messages = listOf(AgentMessage("user", text)), apiHistory = listOf(buildJsonObject { put("role", "user"); put("content", text) }))
    private fun fixture(test: suspend CoroutineScope.(SessionManager) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("title-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { test(scope, SessionManager(root)) }
        finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun greetingWaitsForTopicAndManualNamesArePreserved() {
        val hello = session("pagi bang")
        assertNull(SessionTitlePolicy.seed(hello))
        val next = hello.copy(messages = hello.messages + AgentMessage("assistant", "Pagi!") + AgentMessage("user", "Buat aplikasi skor voli"))
        assertEquals("Buat aplikasi skor voli", SessionTitlePolicy.seed(next)?.topic)
        assertNull(SessionTitlePolicy.seed(next.copy(title = "Judul pribadi")))
        assertNull(SessionTitlePolicy.seed(next.copy(parentId = "parent")))
        assertNull(SessionTitlePolicy.seed(next.copy(titleGenerationAttempted = true)))
    }
    @Test fun tinyRequestContainsNoAgentInstructionsToolsHistoryOrAttachments() {
        val seed = SessionTitlePolicy.seed(session("Perbaiki export PDF\n[Attached context]\nprivate document"))!!
        val request = SessionTitlePolicy.request(config, seed)
        assertEquals(2, request["messages"]!!.jsonArray.size)
        assertFalse(request.toString().contains("private document"))
        assertFalse(request.containsKey("tools"))
        assertFalse(request.toString().contains("TASK SCOPE"))
        assertFalse(request.toString().contains("high"))
        assertTrue(request.toString().toByteArray().size < 2400)
        assertTrue(request["max_tokens"]!!.jsonPrimitive.int <= 2048)
    }
    @Test fun unicodeInputIsByteBoundedWithoutSplittingCharacters() {
        val text = "修复 😀 ".repeat(5000)
        val seed = SessionTitlePolicy.seed(session(text))!!
        assertTrue(seed.topic.toByteArray().size <= SessionTitlePolicy.MAX_TOPIC_BYTES)
        assertFalse(seed.topic.last().isHighSurrogate())
        assertEquals(seed.topic, String(seed.topic.toByteArray()))
    }
    @Test fun invalidOrTruncatedResponsesCannotBecomeTitles() {
        assertNull(SessionTitlePolicy.parse(response("partial", "length")))
        assertNull(SessionTitlePolicy.parse(response("first\nsecond")))
        assertNull(SessionTitlePolicy.parse(response("x".repeat(65))))
        assertNull(SessionTitlePolicy.parse("not JSON"))
        assertEquals("Perbaikan export PDF", SessionTitlePolicy.parse(response("\"Perbaikan export PDF\"")))
    }
    @Test fun generatedTitleIsDurableAndDoesNotEnterConversation() = fixture { manager ->
        val original = session(); manager.save(original)
        var calls = 0
        val controller = SessionTitleController(manager, this, { _, body ->
            calls++; assertFalse(body.contains("apiHistory")); response("Perbaikan export PDF")
        })
        controller.schedule(original.id, config)!!.join()
        val saved = manager.get(original.id)!!
        assertEquals("Perbaikan export PDF", saved.title)
        assertEquals(original.messages, saved.messages)
        assertEquals(original.apiHistory, saved.apiHistory)
        assertTrue(saved.titleGenerationAttempted)
        assertEquals(1L, manager.titleRevision.value)
        // A fresh controller, like a restored workspace, does not issue another request.
        SessionTitleController(manager, this, { _, _ -> calls++; error("duplicate") }).schedule(original.id, config)!!.join()
        assertEquals(1, calls)
    }
    @Test fun manualRenameDuringRequestWinsWithoutLosingNewMessages() = fixture { manager ->
        val original = session(); manager.save(original)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val controller = SessionTitleController(manager, this, { _, _ -> started.complete(Unit); release.await(); response("Automatic title") })
        val job = controller.schedule(original.id, config)!!; started.await()
        manager.update(original.id) { it!!.copy(title = "Nama pilihan saya", messages = it.messages + AgentMessage("user", "Keep this")) }
        release.complete(Unit); job.join()
        assertEquals("Nama pilihan saya", manager.get(original.id)!!.title)
        assertEquals(2, manager.get(original.id)!!.messages.size)
    }
    @Test fun deletionDuringRequestDoesNotRecreateSession() = fixture { manager ->
        val original = session(); manager.save(original)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val controller = SessionTitleController(manager, this, { _, _ -> started.complete(Unit); release.await(); response("Automatic title") })
        val job = controller.schedule(original.id, config)!!; started.await()
        manager.delete(original.id); release.complete(Unit); job.join()
        assertNull(manager.get(original.id))
    }
    @Test fun failureHasNoAutomaticRetryEvenAfterReload() = fixture { manager ->
        val original = session(); manager.save(original); var calls = 0
        repeat(2) {
            SessionTitleController(manager, this, { _, _ -> calls++; error("offline") }).schedule(original.id, config)!!.join()
        }
        assertEquals(1, calls); assertEquals(original.title, manager.get(original.id)!!.title)
    }
    @Test fun timeoutAndCancellationDoNotBlockChatOrPublish() = fixture { manager ->
        val original = session(); manager.save(original)
        val controller = SessionTitleController(manager, this, { _, _ -> delay(5000); response("Late title") }, timeoutMs = 100)
        withTimeout(2000) { controller.schedule(original.id, config)!!.join() }
        assertEquals(original.title, manager.get(original.id)!!.title)
    }
    @Test fun duplicateSchedulingHasOnlyOneRequest() = fixture { manager ->
        val original = session(); manager.save(original)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val controller = SessionTitleController(manager, this, { _, _ -> calls++; started.complete(Unit); release.await(); response("Export PDF") })
        val job = controller.schedule(original.id, config)!!; started.await()
        assertNull(controller.schedule(original.id, config)); release.complete(Unit); job.join(); assertEquals(1, calls)
    }
    @Test fun previousSessionJsonDecodesWithoutMigration() {
        val old = Json.encodeToString(session()).replace(",\"titleGenerationAttempted\":false", "")
        assertFalse(Json.decodeFromString<DroideSession>(old).titleGenerationAttempted)
    }
}
