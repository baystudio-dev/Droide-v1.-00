package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real AgentService advertisement, captured owner, approvals, dispatch and history. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalDeletedFolderAgentServiceTest {
    @get:org.junit.Rule val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    @org.junit.Before fun openAuthoredApplicationLaunch() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        DevelopmentForegroundSessionRegistry.closeForExit().getOrThrow()
        DevelopmentForegroundSessionRegistry.openForEpoch(ApplicationWorkAdmission.reopenFromUser())
    }

    @Test(timeout = 45_000) fun serviceAdvertisesAndExecutesTheOwnedDiscoverInspectArchiveRoute() = fixture(true) { agent, http, root, token ->
        http.response = { number, _, _ -> when (number) {
            1 -> call(http, "local_deleted_folders", buildJsonObject { put("action", "list") })
            2 -> call(http, "local_deleted_folders", tokenArgs(token) { put("action", "inspect") })
            3 -> call(http, "archive_deleted_folder", tokenArgs(token) { put("destination", "rescued.tar") })
            else -> reply(http)
        } }
        runBlocking { withTimeout(30_000) { agent.chat("Recover the deleted src folder to rescued.tar", http.config, maxTurns = 5) } }
        assertEquals(agent.messages.value.joinToString("\n") { it.content }, 4, http.requests.size)
        val names = AgentRuntimePrompt.toolNames(http.requests.first().getValue("tools").jsonArray)
        assertTrue("local_deleted_folders" in names); assertTrue("archive_deleted_folder" in names)
        val listed = result(http.requests[1]); assertTrue(listed.getValue("items").jsonArray.any { it.jsonObject["operation"] == JsonPrimitive(token.operation) })
        val inspected = result(http.requests[2]); assertEquals(JsonPrimitive(token.preparedSha256), inspected.getValue("item").jsonObject["prepared_sha256"])
        val archived = result(http.requests[3]); assertEquals(JsonPrimitive(true), archived["committed"])
        assertEquals(JsonPrimitive(false), archived["historical"])
        assertEquals(JsonPrimitive(true), archived["receipt_durability_reconfirmed"])
        assertTrue(root.resolve("rescued.tar").length() > 512)
        assertEquals("source bytes", retained(root, token).resolve("nested/child.txt").readText())
        assertFalse(root.resolve("src").exists())
        assertNull(agent.persistenceError.value)
        val saved = runBlocking { requireNotNull(agent.sessions.get(requireNotNull(agent.currentSessionId))) }
        assertEquals(3, saved.apiHistory.count { it["role"] == JsonPrimitive("tool") })
    }

    @Test(timeout = 45_000) fun serviceWithoutArchiveStorageStillAdvertisesAndRunsReadDiscovery() = fixture(false) { agent, http, _, token ->
        http.response = { number, _, _ -> if (number == 1)
            call(http, "local_deleted_folders", buildJsonObject { put("action", "list") }) else reply(http) }
        runBlocking { withTimeout(30_000) { agent.chat("List locally deleted folders", http.config, maxTurns = 3) } }
        assertEquals(agent.messages.value.joinToString("\n") { it.content }, 2, http.requests.size)
        val names = AgentRuntimePrompt.toolNames(http.requests.first().getValue("tools").jsonArray)
        assertTrue("local_deleted_folders" in names); assertFalse("archive_deleted_folder" in names)
        assertTrue(result(http.requests[1]).getValue("items").jsonArray.any { it.jsonObject["operation"] == JsonPrimitive(token.operation) })
    }

    @Test(timeout = 45_000) fun cacheContainedInWorkspaceHidesArchiveButKeepsReadDiscovery() = fixture(true,
        cacheInsideWorkspace = true) { agent, http, _, token ->
        http.response = { number, _, _ -> if (number == 1)
            call(http, "local_deleted_folders", buildJsonObject { put("action", "list") }) else reply(http) }
        runBlocking { withTimeout(30_000) { agent.chat("List locally deleted folders", http.config, maxTurns = 3) } }
        assertEquals(agent.messages.value.joinToString("\n") { it.content }, 2, http.requests.size)
        val names = AgentRuntimePrompt.toolNames(http.requests.first().getValue("tools").jsonArray)
        assertTrue("local_deleted_folders" in names); assertFalse("archive_deleted_folder" in names)
        assertTrue(result(http.requests[1]).getValue("items").jsonArray.any { it.jsonObject["operation"] == JsonPrimitive(token.operation) })
    }

    @Test(timeout = 45_000) fun changingServiceModeWhileArchiveApprovalWaitsCannotPublish() = fixture(true,
        listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("edit", "rescued.tar", PermEffect.ASK))) { agent, http, root, token ->
        http.response = { number, _, _ -> if (number == 1)
            call(http, "archive_deleted_folder", tokenArgs(token) { put("destination", "rescued.tar") }) else reply(http) }
        runBlocking { withTimeout(30_000) {
            val work = async(Dispatchers.Default) { agent.chat("Recover deleted src to rescued.tar", http.config, maxTurns = 3) }
            try {
                val approval = requireNotNull(agent.approvals.pending.first { it?.action == "edit" })
                assertEquals("rescued.tar", approval.resource)
                assertEquals(agent.currentSessionId, approval.approvalSessionId)
                agent.setMode(AgentMode.PLAN)
                agent.approvals.resolve(approval.id, ApprovalResolution.ALLOW_ONCE)
                try { work.await() } catch (_: CancellationException) { /* Captured owner was revoked by mode change. */ }
                assertFalse(root.resolve("rescued.tar").exists())
                assertEquals("source bytes", retained(root, token).resolve("nested/child.txt").readText())
                assertTrue(agent.streamState.value.parts.none { it.toolName == "archive_deleted_folder" && it.state == AgentStreamPartState.COMPLETED })
            } finally { agent.approvals.resolve(ApprovalResolution.DENY); work.cancelAndJoin() }
        } }
    }

    private fun fixture(withStorage: Boolean, rules: List<PermRule> = listOf(PermRule("*", "*", PermEffect.ALLOW)),
        cacheInsideWorkspace: Boolean = false,
        block: (AgentService, ProviderStreamFixture, File, LocalDirectoryDeleteCustody.Token) -> Unit) {

        val root = Files.createTempDirectory("f160-agent-service-").toFile()
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
