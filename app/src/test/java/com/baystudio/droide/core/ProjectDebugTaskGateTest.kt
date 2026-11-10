package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectDebugTaskGateTest {
    private fun result(exit: Int = 0, timedOut: Boolean = false) = TaskResult(IdeTask("build", listOf("make"), File(".")), exit, "compiler diagnostic", timedOut, 1)

    @Test fun successfulBuildCompletesBeforeDebuggerStarts() = runBlocking {
        val events = mutableListOf<String>()
        assertEquals("started", ProjectDebugTaskGate.start("build", { events += it; result() }, { events += "debug"; "started" }))
        assertEquals(listOf("build", "debug"), events)
    }

    @Test fun failingTimedOutOrMissingBuildNeverLaunchesAnOldExecutable() = runBlocking {
        for (outcome in listOf(result(2), result(0, true))) {
            var launched = false
            val failure = runCatching { ProjectDebugTaskGate.start("build", { outcome }, { launched = true }) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message!!.contains("compiler diagnostic"))
            assertFalse(launched)
        }
        var launched = false
        assertTrue(runCatching { ProjectDebugTaskGate.start("missing", { error("Unknown task") }, { launched = true }) }.isFailure)
        assertFalse(launched)
    }

    @Test fun cancellationDuringBuildIsPreservedAndLaunchWithoutPrerequisiteDoesNotRunATask() = runBlocking {
        var launched = false
        try { ProjectDebugTaskGate.start("build", { throw CancellationException("stop") }, { launched = true }); fail("Cancellation lost") }
        catch (_: CancellationException) { }
        assertFalse(launched)
        assertEquals("ready", ProjectDebugTaskGate.start(null, { error("No prerequisite") }, { "ready" }))
    }

    @Test fun longPrerequisiteIdentityReachesExecutionUnchangedBeforeLaunch() = runBlocking {
        val label = "  " + "build".repeat(60) + " final  "
        val events = mutableListOf<String>()
        assertEquals("started", ProjectDebugTaskGate.start(label,
            { events += it; result() }, { events += "debug"; "started" }))
        assertEquals(listOf(label, "debug"), events)
    }

    @Test fun blankPrerequisiteRemainsRejectedWithoutExecutionOrLaunch() = runBlocking {
        var executed = false
        var launched = false
        val failure = runCatching { ProjectDebugTaskGate.start("   ",
            { executed = true; result() }, { launched = true }) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertFalse(executed)
        assertFalse(launched)
    }
}
