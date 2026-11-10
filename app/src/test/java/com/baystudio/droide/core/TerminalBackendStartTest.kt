package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class TerminalBackendStartTest {
    private fun session() = object : ITerminalSession {
        override val output = MutableStateFlow("")
        override fun start() {}
        override fun send(cmd: String) {}
        override suspend fun execOnce(cmd: String, timeoutMs: Long) = ExecResult(0, "", false)
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = ExecResult(0, "", false)
        override fun interrupt() {}
        override fun clear() {}
        override fun destroy() {}
    }

    @Test fun healthyNativeTerminalNeverStartsASecondShell() {
        val native = session()
        assertSame(native, TerminalBackendStart.start({ native }, { error("Duplicate shell") }))
    }

    @Test fun unavailableNativeLaunchStartsOneCommandChannelWithoutReplayingAnyInput() {
        var attempts = 0
        val fallback = session()
        assertSame(fallback, TerminalBackendStart.start({ throw NativePtyUnavailableException(java.io.IOException("PTY denied")) },
            { attempts++; fallback }))
        assertEquals(1, attempts)
    }

    @Test fun cancellationInvalidWorkspaceAndMemoryErrorsDoNotCreateFallbackWork() {
        for (failure in listOf(CancellationException("Cancelled"), IllegalArgumentException("Workspace invalid"), OutOfMemoryError("OOM"))) {
            val actual = runCatching { TerminalBackendStart.start({ throw failure }, { error("Unexpected fallback") }) }.exceptionOrNull()
            assertSame(failure, actual)
        }
    }

    @Test fun fallbackReceivesTheActualFailureWithABoundedExplanationWithoutRawMessages() {
        val failure = NativePtyUnavailableException(java.io.IOException("private command " + "x".repeat(10_000)))
        val fallback = session()
        assertSame(fallback, TerminalBackendStart.start({ throw failure }, { received ->
            assertSame(failure, received)
            assertTrue(received.commandModeReason.contains("IOException"))
            assertTrue(received.commandModeReason.contains("new Ubuntu tab"))
            assertFalse(received.commandModeReason.contains("private command"))
            assertTrue(received.commandModeReason.length < 300)
            fallback
        }))
    }

    @Test fun oneNativeFailureDoesNotDisableTheNextExplicitNativeAttempt() {
        var nativeStarts = 0
        var fallbackStarts = 0
        val native = session()
        val fallback = session()
        val startNative = {
            nativeStarts++
            if (nativeStarts == 1) throw NativePtyUnavailableException(java.io.IOException("Transient PTY failure"))
            native
        }
        val startPipe: (NativePtyUnavailableException) -> ITerminalSession = { fallbackStarts++; fallback }
        assertSame(fallback, TerminalBackendStart.start(startNative, startPipe))
        assertSame(native, TerminalBackendStart.start(startNative, startPipe))
        assertEquals(2, nativeStarts)
        assertEquals(1, fallbackStarts)
    }

    @Test fun aFailedFallbackPropagatesWithoutRetryingOrPublishingAnotherShell() {
        val failure = IllegalStateException("pipe failed")
        var attempts = 0
        val result = runCatching {
            TerminalBackendStart.start({ throw NativePtyUnavailableException(java.io.IOException("PTY failed")) }, {
                attempts++
                throw failure
            })
        }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(1, attempts)
    }
}
