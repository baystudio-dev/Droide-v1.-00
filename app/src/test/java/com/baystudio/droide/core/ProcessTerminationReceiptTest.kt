package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Integration regressions for strict direct-child/pipe failure propagation. */
class ProcessTerminationReceiptTest {
    @Test fun cancelledHostedOwnerRetainsCustodyWhenPipeCloseFails() = runBlocking {
        val root = Files.createTempDirectory("strict-hosted-custody-").toFile()
        val closeError = IOException("owned stderr close failed")
        val process = Peer(alive = true, errorClose = { throw closeError })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val confirmations = AtomicInteger()
        val leaseCalls = AtomicInteger()
        val custody = object : WorkspaceProcessCustody() {
            override fun acquire(root: File) = object : Receipt() {
                override fun enterFactory() = Unit
                override fun confirmCleanupOrAbort() { confirmations.incrementAndGet() }
            }
        }
        val hosted = WorkspaceProcessWriteAdmission.create(root, custody) {
            LocalHostedProcess(process, scope) { leaseCalls.incrementAndGet() }
        }
        try {
            withTimeout(3_000) { process.waitEntered.await() }
            scope.cancel()
            val failure = withTimeout(5_000) { runCatching { hosted.awaitExit() }.exceptionOrNull() }
            assertNotNull(failure)
            assertTrue(generateSequence(failure) { it.cause }.any { it === closeError })
            assertFalse(process.isAlive)
            assertEquals(1, leaseCalls.get())
            assertEquals(0, confirmations.get())
            assertTrue(runCatching { ExportWriterAdmission.beginCapture(root).close() }.isFailure)
            assertTrue(runCatching { hosted.awaitExit() }.isFailure)
            assertEquals(0, confirmations.get())
        } finally { process.finish(); hosted.close(); scope.cancel(); root.deleteRecursively() }
    }

    @Test fun captureCannotPublishSuccessfulReceiptAfterPipeCloseFailure() = runBlocking {
        val closeError = IOException("capture stderr close failed")
        val process = Peer(alive = false, errorClose = { throw closeError })
        val receipt = AtomicReference<Throwable?>()
        val receiptCalls = AtomicInteger()
        val failure = withTimeout(5_000) {
            runCatching {
                LocalProcessSupervisor.captureOwned(process, onCleanupCompleted = {
                    receipt.set(it); receiptCalls.incrementAndGet()
                })
            }.exceptionOrNull()
        }
        assertSame(closeError, failure)
        assertSame(closeError, receipt.get())
        assertEquals(1, receiptCalls.get())
    }

    @Test fun leaseFailureRemainsPrimaryWhenStrictTerminationAlsoFails() {
        val closeError = IOException("lease helper stderr close failed")
        val process = Peer(alive = false, code = 9, errorClose = { throw closeError })
        val failure = runCatching { LocalLeaseCleanup.await(process) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure!!.message.orEmpty().contains("exit 9"))
        assertTrue(failure.suppressed.any { it === closeError })
    }

    @Test fun naturalOutputSurvivesUntilExplicitCloseAndBlockedCloseStaysFailed() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        val process = Peer(alive = false, errorClose = {
            closes.incrementAndGet(); entered.countDown(); release.await()
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val hosted = LocalHostedProcess(process, scope) {}
        try {
            assertEquals(0, withTimeout(3_000) { hosted.awaitExit() })
            assertEquals("tail", hosted.stdout.readBytes().toString(Charsets.UTF_8))
            assertEquals(0, closes.get())
            hosted.close()
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            val failure = withTimeout(5_000) { runCatching { hosted.awaitExit() }.exceptionOrNull() }
            assertNotNull(failure)
            assertTrue(generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains("timed out") })
            assertEquals(1, closes.get())
            release.countDown()
            // Late native completion cannot rewrite an already failed hosted receipt.
            assertTrue(runCatching { hosted.awaitExit() }.isFailure)
            assertEquals(1, closes.get())
        } finally { release.countDown(); hosted.close(); scope.cancel() }
    }

    private class Peer(alive: Boolean, private val code: Int = 0, errorClose: () -> Unit) : Process() {
        private val live = AtomicBoolean(alive)
        private val exited = CountDownLatch(if (alive) 1 else 0)
        val waitEntered = CompletableDeferred<Unit>()
        private val output = ByteArrayOutputStream()
        private val input = ByteArrayInputStream("tail".toByteArray())
        private val error = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() = errorClose()
        }
        override fun getOutputStream() = output
        override fun getInputStream() = input
        override fun getErrorStream() = error
        override fun isAlive() = live.get()
        override fun waitFor(): Int { waitEntered.complete(Unit); exited.await(); return code }
        override fun waitFor(timeout: Long, unit: TimeUnit) = exited.await(timeout, unit)
        override fun exitValue(): Int { check(!live.get()); return code }
        override fun destroy() = finish()
        override fun destroyForcibly(): Process { finish(); return this }
        fun finish() { live.set(false); exited.countDown() }
    }
}
