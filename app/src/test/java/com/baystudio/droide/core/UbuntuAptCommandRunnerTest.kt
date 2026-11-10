package com.baystudio.droide.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UbuntuAptCommandRunnerTest {
    private val busy = ExecResult(2, "dpkg: error: dpkg frontend lock was locked by another process with pid 5951", false)

    @Test fun busyLockRetriesTheSameCommandWithoutRepairAndReportsWaitingOnce() = runBlocking {
        var clock = 0L; val calls = mutableListOf<List<String>>(); val messages = mutableListOf<String>()
        val runner = UbuntuAptCommandRunner({ argv, _, _, _ -> calls += argv; if (calls.size < 3) busy else ExecResult(0, "", false) },
            nowMs = { clock }, pause = { clock += it })
        val argv = listOf("dpkg", "--configure", "-a")
        assertEquals(0, runner.run(argv, progress = messages::add).exitCode)
        assertEquals(listOf(argv, argv, argv), calls); assertEquals(1, messages.size)
        assertTrue(messages.single().contains("Waiting"))
    }

    @Test fun longLivedNativeLockStopsWithAnActionableBusyError() = runBlocking {
        var clock = 0L; var calls = 0
        val runner = UbuntuAptCommandRunner({ _, _, _, _ -> calls++; busy }, nowMs = { clock }, pause = { clock += it }, lockWaitMs = 2500)
        try { runner.run(listOf("dpkg", "--configure", "-a")); fail("Busy lock did not stop") }
        catch (error: UbuntuPackageManagerBusyException) { assertTrue(error.message!!.contains("other terminal/Agent installation")) }
        assertEquals(2500L, clock); assertEquals(4, calls)
    }

    @Test fun cancellationWhileWaitingDoesNotRestartTheCommand() = runBlocking {
        val waiting = CompletableDeferred<Unit>(); var calls = 0
        val runner = UbuntuAptCommandRunner({ _, _, _, _ -> calls++; busy }, pause = { waiting.complete(Unit); awaitCancellation() })
        val task = launch { runner.run(listOf("dpkg", "--configure", "-a")) }
        withTimeout(2000) { waiting.await(); task.cancelAndJoin() }
        assertEquals(1, calls); assertTrue(task.isCancelled)
    }

    @Test fun timeoutsPermissionsAndNetworkErrorsAreNotMisclassifiedAsLockContention() = runBlocking {
        for (result in listOf(
            ExecResult(124, busy.output, true),
            ExecResult(100, "Could not open lock file: Permission denied. Is another process using it?", false),
            ExecResult(100, "Could not open lock: Read-only file system", false),
            ExecResult(100, "Temporary failure resolving archive.ubuntu.com", false),
        )) {
            var calls = 0
            val runner = UbuntuAptCommandRunner({ _, _, _, _ -> calls++; result }, pause = { fail("Unexpected retry") })
            assertEquals(result, runner.run(listOf("apt-get", "update"))); assertEquals(1, calls)
        }
    }

    @Test fun aptListsAndFrontendContentionAreRecognizedWithoutBypassingLocks() {
        for (message in listOf(
            "Could not get lock /var/lib/apt/lists/lock - open (11: Resource temporarily unavailable)",
            "Unable to acquire the dpkg frontend lock. It is held by process 17 (apt-get)",
            "dpkg status database is locked by another process",
            "dpkg frontend lock was locked by /usr/bin/python3 process with pid 17",
        )) assertTrue(FoundryUbuntuAptPolicy.lockBusy(ExecResult(100, message, false)))
    }

    @Test fun dpkgStateRequiresCorrectArchitectureAndFullyConfiguredStatus() {
        val output = "git\tarm64\tinstall ok installed\n" +
            "curl\tamd64\tinstall ok installed\nca-certificates\tall\tinstall ok installed\n" +
            "clang\tarm64\tinstall ok unpacked\npython3\tarm64\tinstall reinstreq half-configured\n"
        assertEquals(setOf("git", "ca-certificates"), FoundryUbuntuAptPolicy.installedPackages(ExecResult(1, output, false),
            listOf("git", "curl", "ca-certificates", "clang", "python3")))
    }
}
