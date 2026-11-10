package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ElpHostForwardingTest {
    @Test fun unsupportedDeviceHostNeverFallsBackToGenericLaunch() = runBlocking {
        var genericCalls = 0
        val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.DEVICE_ADB
            override suspend fun resolveExecutable(command: String): String? { genericCalls++; return null }
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                genericCalls++; error("Generic launch is forbidden")
            }
        }
        val observation = ElpSelectionInspection("/source", "/source/main.erl", "/workspace/main.erl", emptyList(), null, null, "observed", guestWorkspaceRoot = "/workspace")
        assertNull(host.inspectElpSelection("/source", "/source/main.erl", ElpDeadline.after(1000)))
        assertNull(host.reviewedElpLaunchIdentity("elp", "/source", ElpDeadline.after(1000)))
        try { host.observeElpPaths(observation, listOf("/workspace"), ElpDeadline.after(1000)); fail("Unsupported guest observation was accepted") }
        catch (_: UnsupportedOperationException) { }
        assertEquals(0, genericCalls)
    }

    @Test fun sourceRootDecoratorRejectsCliMirrorAsAnalysisWorkspace() = runBlocking {
        val source = Files.createTempDirectory("elp-authored-source-host").toFile()
        val cache = Files.createTempDirectory("elp-authored-host-cache").toFile()
        val mirror = File(cache, "cli-mirror").apply { mkdir() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val host = LocalAgentWorkspaceProcessHost(source, cache, scope)
            try { host.inspectElpSelection(mirror.absolutePath, mirror.absolutePath, ElpDeadline.after(1000)); fail("Mirror root was admitted") }
            catch (_: IllegalArgumentException) { }
            try { host.reviewedElpLaunchIdentity("elp", mirror.absolutePath, ElpDeadline.after(1000)); fail("Mirror package root was admitted") }
            catch (_: IllegalArgumentException) { }
            val observation = ElpSelectionInspection(mirror.absolutePath, mirror.absolutePath, "/workspace", emptyList(), null, null, "observed", guestWorkspaceRoot = "/workspace")
            try { host.observeElpPaths(observation, listOf("/workspace"), ElpDeadline.after(1000)); fail("Mirror file observation was admitted") }
            catch (_: IllegalArgumentException) { }
        } finally { scope.cancel() }
    }

    @Test fun canceledLocalHostCannotInspectOrStartPreparation() = runBlocking {
        val source = Files.createTempDirectory("elp-authored-canceled-host").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val host = LocalLinuxProcessHost(source, scope)
        scope.cancel()
        try { host.inspectElpSelection(source.absolutePath, source.absolutePath, ElpDeadline.after(1000)); fail("Canceled host accepted inspection") }
        catch (_: CancellationException) { }
    }
}
