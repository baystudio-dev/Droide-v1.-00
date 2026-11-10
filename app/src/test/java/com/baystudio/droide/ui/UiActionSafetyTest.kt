package com.baystudio.droide.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UiActionSafetyTest {
    @Test fun synchronousClickReportsRecoverableFailure() {
        var failure: Throwable? = null
        val result = runUiCatching(onError = { failure = it }) { throw java.io.IOException("Cannot open file picker") }
        assertNull(result)
        assertEquals("Cannot open file picker", failure?.message)
    }

    @Test fun synchronousClickPreservesCancellation() {
        var reported = false
        val result = runCatching { runUiCatching(onError = { reported = true }) { throw CancellationException("screen closed") } }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertFalse(reported)
    }

    @Test fun unavailableNativeLibraryIsReportedButVmErrorsAreNotSwallowed() {
        var reported: Throwable? = null
        runUiCatching(onError = { reported = it }) { throw UnsatisfiedLinkError("PTY library unavailable") }
        assertTrue(reported is UnsatisfiedLinkError)
        val fatal = OutOfMemoryError("fatal")
        assertSame(fatal, runCatching { runUiCatching(onError = {}) { throw fatal } }.exceptionOrNull())
    }

    @Test fun userActionFailureIsReportedWithoutCancellingScreenScope() = runBlocking {
        var message: String? = null
        launchUiCatching(onError = { message = it.message }) { error("provider unavailable") }.join()
        assertEquals("provider unavailable", message)
        assertTrue(isActive)
    }

    @Test fun cancellationDoesNotShowAnError() = runBlocking {
        var reported = false
        val action = launchUiCatching(onError = { reported = true }) { throw CancellationException("closed screen") }
        action.join()
        assertTrue(action.isCancelled)
        assertFalse(reported)
        assertTrue(isActive)
    }
}
