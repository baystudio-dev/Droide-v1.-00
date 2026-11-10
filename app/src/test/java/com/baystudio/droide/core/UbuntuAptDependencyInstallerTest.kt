package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UbuntuAptDependencyInstallerTest {
    private val id = "1234567890abcdef12345678"
    private class Fixture {
        val installed = mutableSetOf<String>()
        val calls = mutableListOf<List<String>>()
        val marks = mutableListOf<List<String>>()
        val space = mutableListOf<Long>()
        val cleanup = mutableListOf<String>()
        val messages = mutableListOf<String>()
        var fresh = false
        var updates = 0
        var invalidations = 0
        var marked = false
        var audit = ""
        var configureFailures = 0
        var intercept: (suspend (List<String>) -> ExecResult?)? = null
        var guard: (Long) -> Unit = {}
        var cleanupFailure: Exception? = null
        val metadata = object : UbuntuAptMetadataState {
            override fun isFresh() = fresh
            override fun refreshed() { fresh = true; updates++ }
            override fun invalidate() { fresh = false; invalidations++ }
        }
        val installer = UbuntuAptDependencyInstaller(
            UbuntuAptCommandRunner({ argv, _, _, _ ->
                calls += argv
                intercept?.invoke(argv) ?: when {
                    argv.first() == "dpkg-query" -> ExecResult(if (argv.drop(3).all { it in installed }) 0 else 1,
                        argv.drop(3).filter { it in installed }.joinToString("\n") { "$it\tarm64\tinstall ok installed" }, false)
                    argv == listOf("dpkg", "--audit") -> ok(audit)
                    argv == listOf("dpkg", "--configure", "-a") -> {
                        if (configureFailures-- > 0) ExecResult(1, "dependency problems prevent configuration", false)
                        else { audit = ""; ok() }
                    }
                    argv.last().contains("printf 'download=%s") -> ok("download=512\ninstalled_kib=2\n")
                    argv.last().contains("--no-download") -> {
                        installed += argv.last().substringAfter("install ").substringBefore(';').trim().split(' ')
                        ok()
                    }
                    else -> ok()
                }
            }), metadata,
            requireSpace = { bytes, _ -> space += bytes; guard(bytes) },
            layerMarked = { marked }, markLayer = { _, packages -> marked = true; marks += packages },
            cleanup = { cleanup += it; cleanupFailure?.let { error -> throw error } },
        )
        suspend fun ensure(packages: List<String>, id: String) = installer.ensure(packages, id, messages::add)
        fun mutatingCalls() = calls.filterNot { it.first() == "dpkg-query" || it == listOf("dpkg", "--audit") }
    }
    private companion object { fun ok(output: String = "") = ExecResult(0, output, false) }

    @Test fun packagesInstalledInTerminalAreReusedWithoutMarkerOrAnyAptWork() = runBlocking {
        val f = Fixture(); f.installed += setOf("git", "curl")
        f.ensure(listOf("git", "curl"), id)
        assertTrue(f.mutatingCalls().isEmpty())
        assertEquals(listOf(listOf("git", "curl")), f.marks)
        assertTrue(f.space.isEmpty()); assertEquals(0, f.updates)
        assertTrue(f.messages.any { "no download or reinstall" in it })
    }

    @Test fun repeatedInstallUsesRealPackageStateWithoutRefreshingOrRewritingMarker() = runBlocking {
        val f = Fixture(); f.ensure(listOf("git"), id)
        assertEquals(1, f.updates); assertEquals(1, f.marks.size)
        f.calls.clear(); f.ensure(listOf("git"), id)
        assertEquals(1, f.calls.size); assertEquals("dpkg-query", f.calls.single().first())
        assertEquals(1, f.updates); assertEquals(1, f.marks.size)
    }

    @Test fun overlappingExtensionsInstallOnlyMissingRootPackagesAndShareIndexes() = runBlocking {
        val f = Fixture(); f.installed += "ca-certificates"
        f.ensure(listOf("ca-certificates", "git"), id)
        f.ensure(listOf("ca-certificates", "curl"), "abcdef1234567890abcdef12")
        assertEquals(1, f.updates)
        val installs = f.calls.filter { it.last().contains("--no-download") }
        assertEquals(2, installs.size)
        assertTrue(installs.none { it.last().substringAfter("install ").substringBefore(';').contains("ca-certificates") })
        assertTrue(f.calls.none { it == listOf("dpkg", "--configure", "-a") })
    }

    @Test fun removedPackageIsNotTrustedJustBecauseItsLayerHasAMarker() = runBlocking {
        val f = Fixture(); f.marked = true; f.ensure(listOf("git"), id)
        assertTrue(f.calls.any { it.last().contains("--no-download") })
        assertTrue("git" in f.installed)
    }

    @Test fun unsuccessfulAuthenticatedUpdateNeverPublishesIndexCheckpointOrLayer() = runBlocking {
        val f = Fixture(); f.intercept = { argv -> if (argv.last().endsWith(" update")) ExecResult(100, "signature verification failed", false) else null }
        try { f.ensure(listOf("git"), id); fail("Unsigned update was accepted") } catch (_: IllegalStateException) {}
        assertFalse(f.fresh); assertEquals(0, f.updates); assertTrue(f.marks.isEmpty())
        assertFalse(f.calls.any { "--download-only" in it.last() })
        assertEquals(listOf(id), f.cleanup)
    }

    @Test fun staleCachedArchiveRefreshesAndReplansOnceBeforePublishingSuccess() = runBlocking {
        val f = Fixture(); f.fresh = true; var downloads = 0
        f.intercept = { argv -> if ("--download-only" in argv.last() && downloads++ == 0) ExecResult(100, "404 Not Found", false) else null }
        f.ensure(listOf("git"), id)
        assertEquals(2, downloads); assertEquals(1, f.invalidations); assertEquals(1, f.updates)
        assertEquals(2, f.calls.count { "--print-uris" in it.last() })
        assertEquals(listOf(id, id), f.cleanup); assertEquals(1, f.marks.size)
    }

    @Test fun aRepeatedMissingArchiveFailsWithoutAnUpdateLoop() = runBlocking {
        val f = Fixture(); f.fresh = true
        f.intercept = { argv -> if ("--download-only" in argv.last()) ExecResult(100, "404 Not Found", false) else null }
        try { f.ensure(listOf("git"), id); fail("Missing archive did not fail") } catch (_: IllegalStateException) {}
        assertEquals(2, f.calls.count { "--download-only" in it.last() })
        assertEquals(1, f.updates); assertTrue(f.marks.isEmpty())
    }

    @Test fun storageGuardStopsBeforeAnyPackageDownloadAndCleansItsPlan() = runBlocking {
        val f = Fixture(); f.guard = { throw IllegalStateException("Not enough space") }
        try { f.ensure(listOf("git"), id); fail("Storage guard bypassed") } catch (_: IllegalStateException) {}
        assertEquals(listOf(512L + 2048L + FoundryUbuntuGuestEnvironmentSpec.LAYER_TRANSACTION_OVERHEAD_BYTES), f.space)
        assertFalse(f.calls.any { "--download-only" in it.last() }); assertTrue(f.marks.isEmpty())
        assertEquals(listOf(id), f.cleanup)
    }

    @Test fun cancellationDuringDownloadCleansCacheWithoutStartingConfigureOrRepair() = runBlocking {
        val f = Fixture(); val started = CompletableDeferred<Unit>()
        f.intercept = { argv -> if ("--download-only" in argv.last()) { started.complete(Unit); awaitCancellation() } else null }
        val task = launch { f.ensure(listOf("git"), id) }
        withTimeout(2000) { started.await(); task.cancelAndJoin() }
        assertTrue(task.isCancelled); assertEquals(listOf(id), f.cleanup)
        assertFalse(f.calls.any { it == listOf("dpkg", "--configure", "-a") || "-f install" in it.last() })
        assertTrue(f.marks.isEmpty())
    }

    @Test fun interruptedConfigurationIsFinishedOnlyWhenReadonlyAuditReportsIt() = runBlocking {
        val f = Fixture(); f.audit = "git is unpacked but not configured"
        f.ensure(listOf("git"), id)
        assertEquals(1, f.calls.count { it == listOf("dpkg", "--configure", "-a") })
        assertFalse(f.calls.any { "-f install" in it.last() })
    }

    @Test fun genuineBrokenDependenciesHaveAStoragePlanBeforeRepair() = runBlocking {
        val f = Fixture(); f.audit = "git is unpacked but not configured"; f.configureFailures = 1
        f.ensure(listOf("git"), id)
        val plan = f.calls.indexOfFirst { "-f -s" in it.last() }
        val repair = f.calls.indexOfFirst { "-f install" in it.last() }
        assertTrue(plan >= 0 && repair > plan)
        assertTrue(f.space.size >= 3); assertEquals(1, f.updates)
        assertEquals(2, f.calls.count { it == listOf("dpkg", "--configure", "-a") })
    }

    @Test fun configureDeadlineDoesNotLaunchAnotherRepairTransaction() = runBlocking {
        val f = Fixture(); f.audit = "pending configuration"
        f.intercept = { argv -> if (argv == listOf("dpkg", "--configure", "-a")) ExecResult(124, "timed out", true) else null }
        try { f.ensure(listOf("git"), id); fail("Timeout accepted") } catch (_: IllegalStateException) {}
        assertFalse(f.calls.any { it.last().contains("apt-get") }); assertTrue(f.marks.isEmpty())
    }

    @Test fun cleanupErrorPreservesTheOriginalFailureForDiagnosis() = runBlocking {
        val f = Fixture(); f.guard = { error("No free storage") }; f.cleanupFailure = IllegalStateException("Cleanup failed")
        try { f.ensure(listOf("git"), id); fail("Storage failure missing") }
        catch (error: IllegalStateException) { assertEquals("No free storage", error.message); assertEquals("Cleanup failed", error.suppressed.single().message) }
    }
}
