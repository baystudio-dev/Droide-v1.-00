package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LocalLeaseCleanupTest {
    @Test fun successfulHelperCompletesAndIsReaped() {
        val helper = ProcessBuilder("/bin/sh", "-c", "exit 0").start()
        try {
            LocalLeaseCleanup.await(helper)
            assertFalse(helper.isAlive)
            assertEquals(0, helper.exitValue())
        } finally { LocalProcessSupervisor.terminate(helper) }
    }

    @Test fun nonzeroHelperIsReportedAndReaped() {
        val helper = ProcessBuilder("/bin/sh", "-c", "exit 9").start()
        try {
            val failure = runCatching { LocalLeaseCleanup.await(helper) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure!!.message!!.contains("exit 9"))
            assertFalse(helper.isAlive)
            assertEquals(9, helper.exitValue())
        } finally { LocalProcessSupervisor.terminate(helper) }
    }

    @Test fun timedOutHelperIsReportedAndReapedWithoutRetryingCleanup() {
        val helper = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start()
        try {
            val failure = runCatching { LocalLeaseCleanup.await(helper, timeoutMs = 100) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure!!.message!!.contains("timed out after 100ms"))
            assertFalse(helper.isAlive)
            helper.exitValue() // Throws if the helper was not reaped.
        } finally { LocalProcessSupervisor.terminate(helper) }
    }

    @Test fun hostedCleanupTimeoutIsObservableAndDoesNotCrashItsLivingOwnerScope() = runBlocking {
        val child = ProcessBuilder("/bin/sh", "-c", "exit 0").start()
        val uncaught = AtomicInteger()
        val cleanupCount = AtomicInteger()
        val helperRef = AtomicReference<Process?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> uncaught.incrementAndGet() })
        val hosted = LocalHostedProcess(child, scope) {
            cleanupCount.incrementAndGet()
            val helper = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start()
            helperRef.set(helper)
            LocalLeaseCleanup.await(helper, timeoutMs = 100)
        }
        try {
            val failure = withTimeout(5_000) { runCatching { hosted.awaitExit() }.exceptionOrNull() }
            assertTrue(failure is IllegalStateException)
            assertTrue(generateSequence(failure) { it.cause }.any { it is IOException && it.message.orEmpty().contains("cleanup timed out") })
            assertEquals(0, hosted.exitCode)
            assertFalse(child.isAlive)
            assertFalse(requireNotNull(helperRef.get()).isAlive)
            assertTrue(scope.isActive)
            assertEquals(0, uncaught.get())
            hosted.close()
            assertEquals(1, cleanupCount.get())
        } finally {
            hosted.close()
            scope.cancel()
            LocalProcessSupervisor.terminate(child)
            helperRef.get()?.let(LocalProcessSupervisor::terminate)
        }
    }

    @Test fun captureCannotReportSuccessWhenItsLeaseHelperFails() = runBlocking {
        val helpers = mutableListOf<Process>()
        val attempts = AtomicInteger()
        try {
            val failure = runCatching {
                LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "printf completed; exit 0"),
                    File(requireNotNull(System.getProperty("java.io.tmpdir"))), cleanup = {
                        attempts.incrementAndGet()
                        val helper = ProcessBuilder("/bin/sh", "-c", "exit 9").start()
                        helpers += helper
                        LocalLeaseCleanup.await(helper)
                    })
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure!!.message!!.contains("exit 9"))
            assertEquals(1, attempts.get())
            assertTrue(helpers.none { it.isAlive })
        } finally { helpers.forEach(LocalProcessSupervisor::terminate) }
    }
}
