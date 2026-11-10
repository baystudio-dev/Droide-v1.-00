package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Candidate only: all command strings terminate at an injected fake executor. */
class AptDeadlineBaselineTest {
    private data class Call(val argv: List<String>, val limit: Int, val deadline: Long) {
        val text get() = argv.last()
        val probe get() = argv.first() == "dpkg-query" || argv == listOf("dpkg", "--audit")
        val work get() = argv == listOf("dpkg", "--configure", "-a") ||
            text.endsWith(" update") || "--download-only" in text || "--no-download" in text || "-f install" in text
    }

    private class Fixture(var pending: Boolean = false, var configureFailures: Int = 0) {
        val calls = mutableListOf<Call>()
        var installed = false
        var cleanups = 0
        var marks = 0
        var intercept: (suspend (Call) -> ExecResult?)? = null
        val installer = UbuntuAptDependencyInstaller(
            UbuntuAptCommandRunner({ argv, limit, deadline, _ ->
                val call = Call(argv, limit, deadline).also(calls::add)
                intercept?.invoke(call) ?: when {
                    argv.first() == "dpkg-query" -> ExecResult(if (installed) 0 else 1,
                        if (installed) "git\tarm64\tinstall ok installed\n" else "", false)
                    argv == listOf("dpkg", "--audit") -> ExecResult(0, if (pending) "pending configuration" else "", false)
                    argv == listOf("dpkg", "--configure", "-a") -> {
                        if (configureFailures-- > 0) ExecResult(1, "dependency problem", false)
                        else { pending = false; ExecResult(0, "", false) }
                    }
                    "--no-download" in call.text -> { installed = true; ExecResult(0, "", false) }
                    else -> ExecResult(0, "download=512\ninstalled_kib=2\n", false)
                }
            }),
            object : UbuntuAptMetadataState {
                private var fresh = false
                override fun isFresh() = fresh
                override fun refreshed() { fresh = true }
                override fun invalidate() { fresh = false }
            },
            requireSpace = { _, _ -> }, layerMarked = { false },
            markLayer = { _, _ -> marks++ }, cleanup = { cleanups++ },
        )
        suspend fun install() = installer.ensure(listOf("git"), "1234567890abcdef12345678", progress = {})
    }

    @Test fun installAndRepairCommandsHaveNoImplicitDeadline() = runBlocking {
        val f = Fixture(pending = true, configureFailures = 1)
        f.install()
        val work = f.calls.filter { it.work }
        assertEquals("Both configure attempts, update, repair, download and install must be observed", 6, work.size)
        assertTrue(work.all { it.limit in setOf(256_000, 512_000) })
        assertEquals(1, f.marks)
        assertEquals(1, f.cleanups)
        // Work has no implicit wall-clock deadline; probes and lock waits remain bounded.
        assertEquals(List(work.size) { 0L }, work.map { it.deadline })
    }

    @Test fun probesPlanningOutputAndNetworkLockControlsRemainBounded() = runBlocking {
        val f = Fixture(pending = true, configureFailures = 1)
        f.install()
        val probes = f.calls.filter { it.probe }
        val planning = f.calls.filter { !it.probe && !it.work }
        assertTrue(probes.isNotEmpty()); assertTrue(planning.isNotEmpty())
        assertTrue(probes.all { it.deadline == 30_000L && it.limit in setOf(32_000, 64_000) })
        assertTrue(planning.all { it.deadline == 900_000L && it.limit in setOf(128_000, 256_000) })
        for (option in listOf("--no-remove", "Acquire::Retries=1", "Acquire::http::Timeout=30",
            "Acquire::https::Timeout=30", "DPkg::Lock::Timeout=5")) {
            assertTrue(FoundryUbuntuAptPolicy.OPTIONS.contains(option))
        }
    }

    @Test fun explicitDeadlineAndFiniteNativeLockWaitAreRetained() = runBlocking {
        val deadlines = mutableListOf<Long>()
        var now = 0L
        val runner = UbuntuAptCommandRunner({ _, _, deadline, _ ->
            deadlines += deadline
            ExecResult(2, "dpkg frontend lock was locked by another process with pid 17", false)
        }, nowMs = { now }, pause = { now += it }, lockWaitMs = 2_500)
        try { runner.run(listOf("dpkg", "--configure", "-a"), timeoutMs = 1_250); fail("Lock wait did not stop") }
        catch (_: UbuntuPackageManagerBusyException) { }
        assertEquals(2_500L, now)
        assertEquals(listOf(1_250L, 1_250L, 1_250L, 1_250L), deadlines)
    }

    @Test(timeout = 10_000) fun cancelDuringDownloadCleansWithoutInstallOrAutomaticRepair() = runBlocking {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        f.intercept = { call -> if ("--download-only" in call.text) { entered.complete(Unit); awaitCancellation() } else null }
        val job = launch { f.install() }
        try { withTimeout(2_000) { entered.await(); job.cancelAndJoin() } }
        finally { withContext(NonCancellable) { withTimeout(2_000) { job.cancelAndJoin() } } }
        assertEquals(1, f.calls.count { "--download-only" in it.text })
        assertFalse(f.calls.any { "--no-download" in it.text || "-f install" in it.text || it.argv.first() == "dpkg" && !it.probe })
        assertEquals(1, f.cleanups); assertEquals(0, f.marks)
    }
}
