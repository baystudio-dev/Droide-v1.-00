package com.baystudio.droide.core

import android.app.Application
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Controlled UI-preflight boundary plus real Service/Coordinator/Ctx/tool dispatch; source only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AgentMediaOriginalSendAdmissionTest {
    @org.junit.Before fun openAuthoredUserLaunch() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }

    @org.junit.After fun closeAuthoredUserLaunch() {
        ApplicationWorkAdmission.closeForExit()
    }

    @Test fun sendAcceptedBeforeDelayedPreflightCannotConsumeGrantButNewSendCan() = delayedPreflight(unknownAdmission = false)

    @Test fun explicitlyUnknownOriginalSendCannotGainAuthorityAtSubmitEntry() = delayedPreflight(unknownAdmission = true)

    private fun delayedPreflight(unknownAdmission: Boolean) = runBlocking {
        val fixtureRoot = Files.createTempDirectory("agent-send-admission-").toFile()
        val root = fixtureRoot.resolve("files/projects/project").apply { mkdirs() }
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { http -> runBlocking {
                val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)),
                    PermissionPolicyDocument(rules = listOf(PermRule("read", ".droide/chat-media/blobs/*", PermEffect.ALLOW))))
                val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root), perms = permissions,
                    maxSubagentDepth = 0, appContext = RuntimeEnvironment.getApplication())
                val preflightEntered = CompletableDeferred<Unit>()
                val releasePreflight = CompletableDeferred<Unit>()
                var delayedSend: Deferred<Unit>? = null
                try {
                    val session = agent.sessions.create(); agent.loadSession(session)
                    val ref = ChatMediaStore(root).import(session.id, "original.bin", "application/octet-stream", byteArrayOf(1).inputStream())
                    val journal = ProjectCopyJournal(root)
                    val owner = ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.AGENT_MEDIA, session.id, "old-run", ref.id)
                    val old = journal.begin(owner, "asset.bin")
                    old.beforePublish(StreamPublicationPreparation(UUID.randomUUID().toString(), root.canonicalPath,
                        ProjectCopyEvidence.root(root).second, "asset.bin", 1, ProjectCopyEvidence.sha256(byteArrayOf(1)), "old-stage"))
                    old.close()
                    http.response = { turn, _, _ ->
                        if (turn % 2 == 1) {
                            val call = buildJsonObject {
                                put("index", 0); put("id", "save-$turn"); put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", "save_media_to_project")
                                    put("arguments", buildJsonObject { put("path", ref.path); put("destination", "asset.bin") }.toString())
                                })
                            }
                            http.event("""{"choices":[{"index":0,"delta":{"tool_calls":[${call}]},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                        } else http.event("""{"choices":[{"index":0,"delta":{"content":"Finished"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                    }

                    val originalSendAdmission = if (unknownAdmission) null else System.nanoTime()
                    delayedSend = async {
                        preflightEntered.complete(Unit)
                        releasePreflight.await()
                        submitAndJoin(agent, http.config, originalSendAdmission)
                    }
                    preflightEntered.await()
                    assertFalse(agent.busy.value)
                    val access = agent.openMediaRecovery()
                    try { access.acknowledge(access.inspect(old.intent.id).token) } finally { access.close() }
                    assertFalse(root.resolve("asset.bin").exists())
                    releasePreflight.complete(Unit)
                    withTimeout(20_000) { delayedSend!!.await() }
                    assertEquals(2, http.requests.size)
                    val refused = lastToolResult(http.requests[1])
                    assertTrue(refused, refused.contains("MEDIA_COPY_OUTCOME_UNKNOWN"))
                    assertFalse(root.resolve("asset.bin").exists())
                    // A genuinely separate Send after the grant may consume its one-shot permission.
                    withTimeout(20_000) { submitAndJoin(agent, http.config, System.nanoTime()) }
                    assertEquals(4, http.requests.size)
                    val saved = lastToolResult(http.requests[3])
                    assertTrue(saved, saved.contains("MEDIA_SAVED_TO_PROJECT"))
                    assertArrayEquals(byteArrayOf(1), root.resolve("asset.bin").readBytes())
                    val records = journal.recovery(ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.AGENT_MEDIA, session.id)).records
                    assertEquals(ProjectCopyJournal.Status.UNKNOWN, records.single { it.intent.id == old.intent.id }.status)
                    assertEquals(1, records.count { it.status == ProjectCopyJournal.Status.COMMITTED })
                } finally {
                    releasePreflight.complete(Unit)
                    delayedSend?.cancelAndJoin()
                    agent.shutdown()
                }
            } }
        } finally { PathSecurity.deleteTreeNoFollow(fixtureRoot) }
    }

    private suspend fun CoroutineScope.submitAndJoin(agent: AgentService, config: AgentConfig, acceptedAt: Long?) {
        val submitted = checkNotNull(agent.submitAsync(this, "Save the original media as asset.bin", config, maxTurns = 2,
            requestAdmittedAtNanos = acceptedAt))
        submitted.join()
        // submitAsync returns its admission job; the owned direct run is installed before that job
        // completes.
        val run = AgentService::class.java.getDeclaredField("job").apply { isAccessible = true }.get(agent) as? Job
        checkNotNull(run) { "Expected the admitted direct run" }.join()
        assertNull(agent.persistenceError.value)
    }
    private fun lastToolResult(request: JsonObject): String = request.getValue("messages").jsonArray
        .map { it.jsonObject }.last { it["role"]?.jsonPrimitive?.content == "tool" }
        .getValue("content").jsonPrimitive.content
}
