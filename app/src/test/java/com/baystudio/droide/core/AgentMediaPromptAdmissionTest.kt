package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AgentMediaPromptAdmissionTest {
    private val config = AgentConfig(providerId = "openai", model = "gpt-4o", apiKey = "fixture-not-a-secret")
    @Before fun certify() { ProviderModelCertificationRegistry.certify(ProviderRegistry.byId("openai"), listOf(config.model)) }
    @After fun revoke() { ProviderModelCertificationRegistry.revoke("openai") }

    @Test fun mediaSteerIsDurableAndPromotionKeepsItsOriginalReferences() = runBlocking {
        val root = Files.createTempDirectory("droide-media-admit").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sessions = SessionManager(root)
            val session = sessions.create()
            val store = ChatMediaStore(root)
            val ref = ByteArrayInputStream("file context".toByteArray()).use { store.import("draft-fixture", "notes.txt", "text/plain", it) }
            val coordinator = AgentPromptCoordinator(sessions, 100_000, 100)
            var lease = 0L
            coordinator.submit(scope, "first", config, AgentPromptDelivery.STEER, 5, { session.id }, { lease = it }, reportError = {}).let { checkNotNull(it).join() }
            assertTrue(coordinator.markRunReady(lease))
            var accepted = false
            coordinator.submit(scope, "review attachment", config, AgentPromptDelivery.STEER, 5,
                { session.id }, { error("Must not recreate the running session") }, reportError = {},
                onAccepted = { accepted = true }, media = listOf(ref)).let { checkNotNull(it).join() }
            assertTrue(accepted)
            assertEquals(listOf(ref), sessions.get(session.id)!!.pendingInputs.single().media)
            store.releaseOwner("draft-fixture")
            assertNotNull(store.file(ref))
            val promoted = coordinator.promoteAtBoundary(lease, false, config, 2, 100_000)!!
            assertEquals(listOf(ref), promoted.userMessages.single().media)
            assertEquals("review attachment", promoted.userMessages.single().content)
        } finally { scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun acceptanceWaitsForDurableMediaCommitAndFailureKeepsTheDraftCallbackUncalled() = runBlocking {
        val root = Files.createTempDirectory("droide-media-save-failure").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = AgentPromptCoordinator(SessionManager(root), 100_000, 100)
            val ready = CompletableDeferred<Unit>()
            var accepted = false
            var error: String? = null
            val job = coordinator.submit(scope, "inspect", config, AgentPromptDelivery.STEER, 5,
                { null }, { ready.completeExceptionally(IllegalStateException("media commit failed")) },
                reportError = { error = it }, onAccepted = { ready.await(); accepted = true })!!
            job.join()
            assertFalse(accepted)
            assertTrue(error.orEmpty().contains("media commit failed"))
        } finally { scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun deletedSessionRejectsQueuedAdmissionWithoutPublishingOrReleasingDraft() = runBlocking {
        val fixture = Files.createTempDirectory("media-admission-deleted-").toFile()
        val root = fixture.resolve("files/projects/project").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sessions = SessionManager(root)
            val session = sessions.create()
            val store = ChatMediaStore(root)
            val ref = ByteArrayInputStream("draft".toByteArray()).use { store.import("draft-survivor", "notes.txt", "text/plain", it) }
            val coordinator = AgentPromptCoordinator(sessions, 100_000, 100)
            val lease = coordinator.reserveDirectRun()
            assertTrue(coordinator.markRunReady(lease))
            sessions.delete(session.id)
            var accepted = false
            var failure: String? = null
            coordinator.submit(scope, "Inspect", config, AgentPromptDelivery.QUEUE, 5, { session.id },
                { error("Must not start another run") }, reportError = { failure = it }, onAccepted = { accepted = true }, media = listOf(ref))!!.join()
            assertFalse(accepted)
            assertTrue(coordinator.pendingInputs.value.isEmpty())
            assertTrue(failure.orEmpty().contains("removed"))
            assertEquals(listOf(ref), store.ownerRefs("draft-survivor"))
            assertNotNull(store.file(ref))
        } finally { scope.cancel(); PathSecurity.deleteTreeNoFollow(fixture) }
    }

    @Test fun oldMessageAndPendingInputJsonRemainReadableWithNoMedia() {
        val message = Json.decodeFromString(AgentMessage.serializer(), """{"role":"assistant","content":"hello"}""")
        val pending = Json.decodeFromString(AgentPendingInput.serializer(), """{"id":"old","text":"go","delivery":"QUEUE","providerId":"openai","modelId":"gpt-4o"}""")
        assertTrue(message.media.isEmpty())
        assertTrue(pending.media.isEmpty())
    }
}
