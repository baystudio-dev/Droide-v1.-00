package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real AgentService advertisement, captured owner, approvals, dispatch and history. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35], manifest = Config.NONE)
class LocalDeletedFolderAgentServiceF161Test {
    @get:org.junit.Rule val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    @org.junit.Before fun openAuthoredApplicationLaunch() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        DevelopmentForegroundSessionRegistry.closeForExit().getOrThrow()
        DevelopmentForegroundSessionRegistry.openForEpoch(ApplicationWorkAdmission.reopenFromUser())
    }


    @Test(timeout = 45_000) fun serviceSurfacesCaptureBeforePublishingAndKeepsTheToolRunning() = fixture(true) { agent, http, root, token ->
        retained(root, token).resolve("large.bin").outputStream().use { out -> repeat(192) { out.write(ByteArray(65536) { 91 }) } }
        val observed = java.util.concurrent.CopyOnWriteArrayList<Triple<String, AgentStreamPartState, Boolean>>()
        http.response = { number, _, _ -> if (number == 1)
            call(http, "archive_deleted_folder", tokenArgs(token) { put("destination", "progress.tar") }) else reply(http) }
        runBlocking { withTimeout(30_000) {
            val watch = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                agent.streamState.collect { stream -> stream.parts.filter { it.toolName == "archive_deleted_folder" }.forEach { p ->
                    observed += Triple(p.detail, p.state, root.resolve("progress.tar").exists())
                } }
            }
            try { agent.chat("Recover the deleted src folder to progress.tar", http.config, maxTurns = 3) }
            finally { watch.cancelAndJoin() }
        } }
        println("F161_SERVICE_PROGRESS ${observed.joinToString(" | ")}")
        assertEquals(agent.messages.value.joinToString("\n") { it.content }, 2, http.requests.size)
        val names = AgentRuntimePrompt.toolNames(http.requests.first().getValue("tools").jsonArray)
        assertTrue("archive_deleted_folder" in names)
        assertTrue("Actual AgentService must surface capture before publication: $observed", observed.any { it.first.contains("Reading retained folder", true) && !it.third && it.second != AgentStreamPartState.COMPLETED })
        assertFalse("Staged verification cannot complete the tool", observed.any { it.first.contains("Archive checked", true) && !it.third && it.second == AgentStreamPartState.COMPLETED })
        val output = result(http.requests[1]); assertEquals(JsonPrimitive(true), output["committed"])
        assertTrue(root.resolve("progress.tar").isFile)
        assertFalse("No observed completion may precede actual publication", observed.any { it.second == AgentStreamPartState.COMPLETED && !it.third })
        assertNull(agent.persistenceError.value)
    }
    private fun fixture(withStorage: Boolean, rules: List<PermRule> = listOf(PermRule("*", "*", PermEffect.ALLOW)),
        cacheInsideWorkspace: Boolean = false,
        block: (AgentService, ProviderStreamFixture, File, LocalDirectoryDeleteCustody.Token) -> Unit) {
        // Preserve fixtures and archive/evidence files for independent inspection.
        val root = Files.createTempDirectory("f161-agent-service-").toFile()
        root.resolve("src/nested").mkdirs(); root.resolve("src/nested/child.txt").writeText("source bytes")
        root.resolve("src/.hidden").writeText("hidden bytes"); root.resolve("src/empty").mkdirs()
        assertTrue(runBlocking { FileRepository(root).delete("src") })
        val token = requireNotNull(LocalDirectoryDeleteCustody(root).inspectDirectory(LocalDirectoryDeleteCustody.HOME)
            .candidates.single().directoryDeletion?.token)
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { http ->
            val context: android.content.Context? = if (!withStorage) null else if (cacheInsideWorkspace)
                object : android.content.ContextWrapper(RuntimeEnvironment.getApplication()) {
                    override fun getCacheDir(): File = root
                } else RuntimeEnvironment.getApplication()
            val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
                perms = PermissionEngine(rules, PermissionPolicyDocument(rules = listOf(PermRule("read", ".droide/*", PermEffect.ALLOW)))), maxSubagentDepth = 0,
                appContext = context)
            try { block(agent, http, root, token) } finally { agent.shutdown() }
        }
    }
    private fun retained(root: File, token: LocalDirectoryDeleteCustody.Token) = root.resolve("${LocalDirectoryDeleteCustody.HOME}/${token.operation}/original")
    private fun tokenArgs(token: LocalDirectoryDeleteCustody.Token, extra: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("operation", token.operation); put("prepared_sha256", token.preparedSha256); extra()
    }
    private fun result(request: JsonObject): JsonObject {
        val text = request.getValue("messages").jsonArray.map { it.jsonObject }
            .last { it["role"] == JsonPrimitive("tool") }.getValue("content").jsonPrimitive.content
        assertEquals(text, AgentToolEvidenceState.SUCCEEDED, AgentToolResultSemantics.parseAnnotation(text)?.state)
        return Json.parseToJsonElement(AgentToolResultSemantics.stripAnnotation(text)).jsonObject
    }
    private fun call(http: ProviderStreamFixture, name: String, args: JsonObject): String {
        val calls = buildJsonArray { add(buildJsonObject {
            put("index", 0); put("id", "folder-$name-${args["action"] ?: "archive"}"); put("type", "function")
            put("function", buildJsonObject { put("name", name); put("arguments", args.toString()) })
        }) }
        return http.event("""{"choices":[{"index":0,"delta":{"tool_calls":$calls},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
    }
    private fun reply(http: ProviderStreamFixture) = http.event("""{"choices":[{"index":0,"delta":{"content":"Finished"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
}
