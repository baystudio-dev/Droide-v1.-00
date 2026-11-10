package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After

class AgentPromptAdmissionWorkflowTest {
    @Before fun certifyTestSelection() { ProviderModelCertificationRegistry.certify(ProviderRegistry.byId("openai"), listOf("gpt-4o")) }
    @After fun revokeTestSelection() { ProviderModelCertificationRegistry.revoke("openai") }
    @Test fun failedImagePreparationKeepsComposerAcceptanceCallbackPending() = runBlocking {
        val root = Files.createTempDirectory("droide-prompt-image-reject-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = AgentPromptCoordinator(SessionManager(root, LlmClient()), 100_000, 100)
            val error = CompletableDeferred<String>()
            var accepted = false; var started = false
            coordinator.submit(scope, "inspect image", AgentConfig(providerId = "openai", model = "gpt-4o", apiKey = "test"),
                AgentPromptDelivery.STEER, 5, { null }, { started = true },
                prepareImages = { error("Unsupported image") },
                reportError = { if (it != null) error.complete(it) }, onAccepted = { accepted = true })
            assertTrue(withTimeout(5_000) { error.await() }.contains("Unsupported image"))
            assertFalse(accepted); assertFalse(started)
        } finally { scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun acceptedPromptNotifiesComposerOnlyAfterItHasAReservedRun() = runBlocking {
        val root = Files.createTempDirectory("droide-prompt-admission-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = AgentPromptCoordinator(SessionManager(root, LlmClient()), 100_000, 100)
            val accepted = CompletableDeferred<Boolean>()
            var started = false
            coordinator.submit(scope, "hello", AgentConfig(providerId = "openai", model = "gpt-4o", apiKey = "test"),
                AgentPromptDelivery.STEER, 5, { null }, { started = true },
                reportError = {}, onAccepted = { accepted.complete(started) })
            assertTrue(withTimeout(5_000) { accepted.await() })
        } finally { scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }
}
