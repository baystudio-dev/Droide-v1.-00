package com.baystudio.droide.ui

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ProviderFormRequestFenceTest {
    @Test fun currentProviderAndAuthResultIsAccepted() {
        val fence = ProviderFormRequestFence()
        val request = fence.begin("provider-a", "api-key")
        assertEquals(listOf("a-model"), fence.currentResult(request, "provider-a", "api-key", Result.success(listOf("a-model")))?.getOrThrow())
    }

    @Test fun providerAResultCannotPopulateProviderBAfterNavigation() {
        val fence = ProviderFormRequestFence()
        val request = fence.begin("provider-a", "api-key")
        assertNull(fence.currentResult(request, "provider-b", "api-key", Result.success(listOf("a-model"))))
        assertEquals("provider-a", request.providerId)
    }

    @Test fun sameProviderDifferentAuthRejectsOldResult() {
        val fence = ProviderFormRequestFence()
        val request = fence.begin("provider-a", "api-key")
        assertNull(fence.currentResult(request, "provider-a", "device-oauth", Result.success("old credential models")))
        assertEquals("api-key", request.authMethodId)
    }

    @Test fun navigationAwayAndBackDoesNotReviveOldResult() {
        val fence = ProviderFormRequestFence()
        val request = fence.begin("provider-a", "api-key")
        fence.invalidate()
        assertNull(fence.currentResult(request, "provider-a", "api-key", Result.success("old models")))
    }

    @Test fun closeAndReopenCannotAcceptOldFormResult() {
        val oldForm = ProviderFormRequestFence()
        val request = oldForm.begin("provider-a", "api-key")
        oldForm.invalidate()
        val reopened = ProviderFormRequestFence()
        reopened.begin("provider-a", "api-key")
        assertNull(oldForm.currentResult(request, "provider-a", "api-key", Result.success("old models")))
        assertNull(reopened.currentResult(request, "provider-a", "api-key", Result.success("old models")))
    }

    @Test fun latestRequestWinsEvenForSameProviderAndAuth() {
        val fence = ProviderFormRequestFence()
        val first = fence.begin("provider-a", "api-key")
        val latest = fence.begin("provider-a", "api-key")
        assertNull(fence.currentResult(first, "provider-a", "api-key", Result.success("old models")))
        assertEquals("new models", fence.currentResult(latest, "provider-a", "api-key", Result.success("new models"))?.getOrThrow())
    }

    @Test fun disconnectInvalidatesOlderRefreshAndRetainsCapturedProvider() {
        val fence = ProviderFormRequestFence()
        val refresh = fence.begin("provider-a", "api-key")
        val disconnect = fence.begin("provider-a", "api-key")
        assertNull(fence.currentResult(refresh, "provider-a", "api-key", Result.success("old models")))
        assertNotNull(fence.currentResult(disconnect, "provider-a", "api-key", Result.success(Unit)))
        fence.invalidate()
        assertFalse(fence.isCurrent(disconnect, "provider-a", "api-key"))
        assertEquals("provider-a", disconnect.providerId)
    }

    @Test fun staleFailureCannotReplaceCurrentFormStatus() {
        val fence = ProviderFormRequestFence()
        val request = fence.begin("provider-a", "api-key")
        val failure = IllegalStateException("provider-a failed")
        assertSame(failure, fence.currentResult(request, "provider-a", "api-key", Result.failure<Unit>(failure))?.exceptionOrNull())
        fence.invalidate()
        assertNull(fence.currentResult(request, "provider-b", "api-key", Result.failure<Unit>(failure)))
    }

    @Test fun cancellationPropagatesForCurrentAndStaleRequests() {
        val fence = ProviderFormRequestFence()
        val request = fence.begin("provider-a", "api-key")
        val cancelled = CancellationException("cancelled")
        for (invalidate in listOf(false, true)) {
            if (invalidate) fence.invalidate()
            try {
                fence.currentResult(request, "provider-a", "api-key", Result.failure<Unit>(cancelled))
                fail("Cancellation must propagate rather than become a banner")
            } catch (actual: CancellationException) {
                assertSame(cancelled, actual)
            }
        }
    }
}
