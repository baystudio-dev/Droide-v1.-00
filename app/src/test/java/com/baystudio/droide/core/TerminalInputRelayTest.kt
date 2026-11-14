package com.baystudio.droide.core

import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TerminalInputRelayTest {
    /** No actual thread/transport: every worker continuation stays in this finite manual queue. */
    private class FinalizationFixture(write: (String, ByteArray) -> Unit, onError: (String) -> Unit = {}) {
        private val queued = mutableListOf<Runnable>()
        private val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued += block }
        }
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val relay = TerminalInputRelay(scope, CompletableDeferred("ready"), write, onError, ioDispatcher = dispatcher)
        fun drain() {
            repeat(100) {
                if (queued.isEmpty()) return
                queued.removeAt(0).run()
            }
            assertTrue("Mock continuation budget exhausted", queued.isEmpty())
        }
        fun close() { relay.close(); scope.cancel(); drain() }
    }

    @Test fun completionWithWithheldRelayQueueCannotPublishCleanExit() {
        var writes = 0
        val notices = mutableListOf<String>()
        val fixture = FinalizationFixture({ _, _ -> writes++ }, notices::add)
        try {
            assertTrue(fixture.relay.send(byteArrayOf(1)))
            val receipt = NativePtyCleanupReceipt(0, 0, 0).withRelayInputFailure(fixture.relay.finishInput())
            assertEquals(-1, receipt.exitCode)
            assertEquals(5, receipt.inputFailureReason)
            assertTrue(notices.isEmpty()) // Truth does not depend on a scheduled observer.
            fixture.drain()
            assertEquals(0, writes)
            assertEquals(5, fixture.relay.finishInput())
        } finally { fixture.close() }
    }

    @Test fun completionDuringInflightRelayWriteStaysUnconfirmedAfterReturn() {
        lateinit var fixture: FinalizationFixture
        var receipt: NativePtyCleanupReceipt? = null
        var writes = 0
        fixture = FinalizationFixture({ _, _ ->
            writes++
            // Deterministic pause while write is admitted, before its return/decrement.
            receipt = NativePtyCleanupReceipt(0, 0, 0).withRelayInputFailure(fixture.relay.finishInput())
        })
        try {
            assertTrue(fixture.relay.send(byteArrayOf(1)))
            fixture.drain()
            assertEquals(1, writes)
            assertEquals(-1, requireNotNull(receipt).exitCode)
            assertEquals(5, fixture.relay.finishInput())
        } finally { fixture.close() }
    }

    @Test fun fullyForwardedRelayAndCleanNativeReceiptRemainClean() {
        var writes = 0
        val fixture = FinalizationFixture({ _, _ -> writes++ })
        try {
            assertTrue(fixture.relay.send(byteArrayOf(1)))
            fixture.drain()
            assertEquals(1, writes)
            val receipt = NativePtyCleanupReceipt(0, 0, 0).withRelayInputFailure(fixture.relay.finishInput())
            assertEquals(0, receipt.exitCode)
            assertEquals(0, fixture.relay.finishInput())
        } finally { fixture.close() }
    }

    @Test fun priorRelayFailureRemainsStickyAfterPendingCountersClear() {
        val fixture = FinalizationFixture({ _, _ -> fail("No write after failure") })
        try {
            assertTrue(fixture.relay.send(byteArrayOf(1)))
            fixture.relay.fail(NativePtyCleanupReceipt.inputFailureNotice(3))
            fixture.drain()
            assertEquals(5, fixture.relay.finishInput())
            assertEquals(-1, NativePtyCleanupReceipt(0, 0, 0).withRelayInputFailure(fixture.relay.finishInput()).exitCode)
        } finally { fixture.close() }
    }

    @Test fun priorSilentDiscardCannotBecomeCleanAfterQueueCountersClear() {
        val fixture = FinalizationFixture({ _, _ -> fail("Closed queued input must not be forwarded") })
        try {
            assertTrue(fixture.relay.send(byteArrayOf(1)))
            fixture.relay.close(reportUnsent = false)
            fixture.drain()
            assertEquals(5, fixture.relay.finishInput())
        } finally { fixture.close() }
    }

    @Test fun reentrantThrowingObserverCannotPreventFinalizationReceipt() {
        lateinit var fixture: FinalizationFixture
        var first = true
        var observedReceipt: Int? = null
        var retryAccepted: Boolean? = null
        var observerThrew = false
        fixture = FinalizationFixture({ _, _ -> fail("Closed input cannot write") }, {
            if (first) {
                first = false
                observedReceipt = fixture.relay.finishInput()
                retryAccepted = fixture.relay.send(byteArrayOf(2))
                observerThrew = true
                throw IllegalStateException("Observer failed after reentrant finalization")
            }
        })
        try {
            assertTrue(fixture.relay.send(byteArrayOf(1)))
            fixture.relay.fail(NativePtyCleanupReceipt.inputFailureNotice(3))
            assertEquals(5, observedReceipt)
            assertEquals(false, retryAccepted)
            assertTrue(observerThrew)
            val receipt = NativePtyCleanupReceipt(0, 0, 0).withRelayInputFailure(fixture.relay.finishInput())
            assertEquals(-1, receipt.exitCode)
            fixture.drain()
            assertEquals(5, fixture.relay.finishInput())
        } finally { fixture.close() }
    }

    @Test fun repeatedRelayFinalizationNeverUpgradesAfterLateSettlement() {
        val notices = mutableListOf<String>()
        val fixture = FinalizationFixture({ _, _ -> fail("Finalized queued input cannot write") }, notices::add)
        try {
            assertTrue(fixture.relay.send(byteArrayOf(1)))
            assertEquals(5, fixture.relay.finishInput())
            assertEquals(5, fixture.relay.finishInput())
            fixture.drain()
            assertEquals(5, fixture.relay.finishInput())
            assertTrue(notices.isEmpty())
        } finally { fixture.close() }
    }

    // These focused methods are pure queued-dispatcher tests. Do not run this whole
    // class in a mocked-only phase: the historical methods below start real shells.
    @Test fun asynchronousPtyFailureClosesAdmissionOnceBeforeObserverRetry() {
        val queued = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued += block }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val errors = mutableListOf<String>()
        var retryAccepted: Boolean? = null
        lateinit var relay: TerminalInputRelay<String>
        relay = TerminalInputRelay(scope, CompletableDeferred("ready"), { _, _ -> fail("No replay after failure") }, { detail ->
            errors += detail
            if ("could not be confirmed" in detail) retryAccepted = relay.send(byteArrayOf(2))
        }, ioDispatcher = dispatcher)
        try {
            assertTrue(relay.send(byteArrayOf(1)))
            relay.fail(NativePtyCleanupReceipt.inputFailureNotice(2))
            relay.fail(NativePtyCleanupReceipt.inputFailureNotice(3))
            assertEquals(false, retryAccepted)
            assertEquals(1, errors.count { "could not be confirmed" in it })
            assertTrue(errors.any { "deadline expired" in it })
            while (queued.isNotEmpty()) queued.removeAt(0).run()
            assertFalse(relay.send(byteArrayOf(3)))
            assertEquals(1, errors.count { "could not be confirmed" in it })
        } finally { relay.close(); scope.cancel(); while (queued.isNotEmpty()) queued.removeAt(0).run() }
    }

    @Test fun asynchronousPtyFailureObserverCannotPreventCloseByThrowing() {
        val queued = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued += block }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var failureCalls = 0
        val relay = TerminalInputRelay(scope, CompletableDeferred("ready"), { _, _ -> fail("Must not write") }, { detail ->
            if ("could not be confirmed" in detail) { failureCalls++; throw IllegalStateException("Observer failure") }
        }, ioDispatcher = dispatcher)
        try {
            assertTrue(relay.send(byteArrayOf(1)))
            relay.fail(NativePtyCleanupReceipt.inputFailureNotice(4))
            relay.fail(NativePtyCleanupReceipt.inputFailureNotice(5))
            assertFalse(relay.send(byteArrayOf(2)))
            while (queued.isNotEmpty()) queued.removeAt(0).run()
            assertEquals(1, failureCalls)
        } finally { relay.close(); scope.cancel(); while (queued.isNotEmpty()) queued.removeAt(0).run() }
    }

    @Test fun nativeFailureAfterQueueAdmissionRemainsVisibleWithoutReplaying() {
        val queued = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued += block }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val writes = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val relay = TerminalInputRelay(scope, CompletableDeferred("ready"), { _, bytes -> writes += String(bytes) }, errors::add,
            ioDispatcher = dispatcher)
        try {
            assertTrue(relay.send("accepted into native queue".toByteArray()))
            while (queued.isNotEmpty()) queued.removeAt(0).run()
            assertEquals(listOf("accepted into native queue"), writes)
            relay.fail(NativePtyCleanupReceipt.inputFailureNotice(3))
            while (queued.isNotEmpty()) queued.removeAt(0).run()
            assertEquals(1, errors.count { "could not be confirmed" in it })
            assertFalse(relay.send("retry".toByteArray()))
            assertEquals(listOf("accepted into native queue"), writes)
        } finally { relay.close(); scope.cancel(); while (queued.isNotEmpty()) queued.removeAt(0).run() }
    }

    @Test fun writeFailureClosesInputBeforeAnErrorObserverCanRetry() {
        val queued = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued += block }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val writes = mutableListOf<String>()
        var retryAccepted: Boolean? = null
        lateinit var relay: TerminalInputRelay<String>
        relay = TerminalInputRelay(scope, CompletableDeferred("ready"), { _, bytes ->
            writes += String(bytes)
            throw IOException("Connection interrupted after write")
        }, { error ->
            if ("could not be confirmed" in error) retryAccepted = relay.send("retry".toByteArray())
        }, ioDispatcher = dispatcher)
        try {
            assertTrue(relay.send("one".toByteArray()))
            queued.removeAt(0).run()
            assertEquals(false, retryAccepted)
            assertEquals(listOf("one"), writes)
        } finally { relay.close(); scope.cancel(); queued.toList().forEach(Runnable::run) }
    }

    @Test fun alreadyCancelledScopeRejectsInputEvenIfTheWriterBodyNeverStarts() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.cancel()
        val errors = CopyOnWriteArrayList<String>()
        val relay = TerminalInputRelay(scope, CompletableDeferred<String>(), { _, _ -> fail("Must not write") }, errors::add)
        assertFalse(relay.send("must not disappear".toByteArray()))
        assertTrue(errors.any { it.contains("closed") })
        relay.close()
    }

    @Test fun cancellationRejectsInputBeforeQueuedIoWorkerCanRunItsFinally() {
        val queued = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued += block }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val errors = CopyOnWriteArrayList<String>()
        val ready = CompletableDeferred<String>()
        val relay = TerminalInputRelay(scope, ready, { _, _ -> fail("Cancelled input must not write") }, errors::add,
            ioDispatcher = dispatcher)
        assertTrue(queued.isNotEmpty())
        scope.cancel()
        assertFalse(relay.send("rejected immediately".toByteArray()))
        assertTrue(errors.any { it.contains("closed") })
        relay.close()
        queued.toList().forEach(Runnable::run)
    }

    @Test(timeout = 9_000) fun slowTerminalStartupRetainsImmutableUnicodeInputAndExecutesItExactlyOnce() = runBlocking {
        val ready = CompletableDeferred<Process>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val errors = CopyOnWriteArrayList<String>()
        val relay = TerminalInputRelay(scope, ready, { process, bytes ->
            process.outputStream.write(bytes); process.outputStream.flush()
        }, errors::add)
        val process = ProcessBuilder("/bin/sh").redirectErrorStream(true).start()
        val output = async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
        try {
            val queued = "printf 'once 日本語 😀\\n'\n".toByteArray()
            assertTrue(relay.send(queued))
            queued.fill(0)
            assertTrue(relay.send("printf 'second\\n'; exit 0\n".toByteArray()))
            delay(2_200) // The old ADB writer silently abandoned input after about two seconds.
            assertFalse(output.isCompleted)
            assertTrue(errors.isEmpty())
            ready.complete(process)
            assertEquals("once 日本語 😀\nsecond\n", withTimeout(3_000) { output.await() })
            assertEquals(0, process.waitFor())
            assertTrue(errors.isEmpty())
        } finally {
            relay.close()
            ProcessIo.terminate(process)
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test(timeout = 6_000) fun closingDuringStartupDoesNotReplayOldInputIntoAReplacementTerminal() = runBlocking {
        val ready = CompletableDeferred<Process>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = Files.createTempDirectory("droide-no-input-replay-").toFile()
        val marker = root.resolve("must-not-exist")
        val errors = CopyOnWriteArrayList<String>()
        val old = TerminalInputRelay(scope, ready, { process, bytes -> process.outputStream.write(bytes); process.outputStream.flush() }, errors::add)
        val process = ProcessBuilder("/bin/sh").redirectErrorStream(true).start()
        try {
            assertTrue(old.send("touch '${marker.absolutePath}'\n".toByteArray()))
            old.close(reportUnsent = true)
            ready.complete(process)
            val replacement = TerminalInputRelay(scope, CompletableDeferred(process),
                { shell, bytes -> shell.outputStream.write(bytes); shell.outputStream.flush() }, errors::add)
            try {
                assertTrue(replacement.send("printf fresh; exit\n".toByteArray()))
                val text = withTimeout(2_000) { withContext(Dispatchers.IO) { process.inputStream.bufferedReader().readText() } }
                assertEquals("fresh", text)
                assertFalse(marker.exists())
                assertFalse(old.send("echo forbidden\n".toByteArray()))
            } finally { replacement.close() }
        } finally {
            old.close()
            ProcessIo.terminate(process)
            scope.coroutineContext.job.cancelAndJoin()
            root.deleteRecursively()
        }
    }

    @Test(timeout = 4_000) fun startupInputIsBoundedAndWriteFailuresReportUnconfirmedDeliveryWithoutRetrying() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pendingConnection = CompletableDeferred<String>()
        val errors = CopyOnWriteArrayList<String>()
        val blocked = TerminalInputRelay(scope, pendingConnection, { _, _ -> error("Still starting") }, errors::add)
        try {
            repeat(4) { assertTrue(blocked.send(ByteArray(65_536))) }
            assertFalse(blocked.send(byteArrayOf(1)))
            assertTrue(errors.any { it.contains("256 KiB") })
            val delivered = CopyOnWriteArrayList<String>()
            val connected = TerminalInputRelay(scope, CompletableDeferred("ready"), { _, bytes ->
                delivered += String(bytes)
                throw IOException("Connection interrupted after write")
            }, errors::add)
            try {
                connected.send("one".toByteArray())
                connected.send("two".toByteArray())
                withTimeout(2_000) { while (errors.none { it.contains("could not be confirmed") }) delay(10) }
                assertEquals(listOf("one"), delivered)
                assertFalse(connected.send("retry".toByteArray()))
            } finally { connected.close() }
        } finally {
            blocked.close()
            scope.coroutineContext.job.cancelAndJoin()
        }
    }
}
