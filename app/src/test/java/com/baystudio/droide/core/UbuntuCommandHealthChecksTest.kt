package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UbuntuCommandHealthChecksTest {
    private val commands = linkedMapOf("python" to listOf("/usr/bin/python3", "--version"),
        "debugpy" to listOf("/usr/bin/python3", "-m", "debugpy", "--version"))

    @Test fun allExactProbesShareOnePreparationAndHaveFiniteBudgets() = runBlocking {
        var preparations = 0
        val executed = mutableListOf<List<String>>()
        UbuntuCommandHealthChecks.verify(commands, { preparations++ }, { argv, limit, timeout ->
            assertEquals(64_000, limit); assertEquals(60_000L, timeout)
            executed += argv; ExecResult(0, "version", false)
        })
        assertEquals(1, preparations); assertEquals(commands.values.toList(), executed)
    }
    @Test fun failureStopsRemainingProbesAndRetainsUsefulTail() = runBlocking {
        var calls = 0
        try {
            UbuntuCommandHealthChecks.verify(commands, {}, { _, _, _ -> calls++; ExecResult(9, "useful failure", false) })
            fail("Failed probe accepted")
        } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("python")); assertTrue(expected.message!!.contains("useful failure")) }
        assertEquals(1, calls)
    }
    @Test fun evenZeroExitCannotHideATimedOutProbe() = runBlocking {
        try {
            UbuntuCommandHealthChecks.verify(commands, {}, { _, _, _ -> ExecResult(0, "timeout", true) })
            fail("Timeout accepted")
        } catch (_: IllegalStateException) { }
    }
    @Test fun cancellationDoesNotStartAnotherProbe() = runBlocking {
        var calls = 0
        try {
            UbuntuCommandHealthChecks.verify(commands, {}, { _, _, _ -> calls++; throw CancellationException("stop") })
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }
    @Test fun emptyBatchDoesNotPrepareGuest() = runBlocking {
        UbuntuCommandHealthChecks.verify(emptyMap(), { fail("Prepared empty batch") }, { _, _, _ -> error("Executed empty batch") })
    }
}
