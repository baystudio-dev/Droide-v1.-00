package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class JsonRpcLifetimeTest {
    @Test(timeout = 10_000) fun failedWriterReportsToOwnerWithoutCancelingRegularParentOrReplay() = runBlocking {
        Fixture().use { fixture ->
            fixture.host.failWrites.set(true)
            fixture.rpc.start()
            val failure = runCatching { fixture.rpc.request("hover", timeoutMs = 3000) }.exceptionOrNull()
            assertIoFailure(failure, "owned writer failed")
            fixture.assertStoppedWithoutParentFailure()
            assertEquals(1, fixture.host.writeAttempts.get())
            assertEquals(1, fixture.host.starts.get())
            assertTrue(runCatching { fixture.rpc.start() }.exceptionOrNull() is IllegalStateException)
            assertEquals("A failed transport must not restart", 1, fixture.host.starts.get())
        }
    }

    @Test(timeout = 10_000) fun failedSecondWriterReleasesAlreadyPendingOwnerWithOriginalFailure() = runBlocking {
        Fixture().use { fixture ->
            fixture.rpc.start()
            val pending = async { runCatching { fixture.rpc.request("first", timeoutMs = 3000) } }
            withTimeout(1000) { fixture.host.frames.receive() }
            fixture.host.failWrites.set(true)
            val second = runCatching { fixture.rpc.request("second", timeoutMs = 3000) }.exceptionOrNull()
            assertIoFailure(second, "owned writer failed")
            assertIoFailure(withTimeout(1000) { pending.await().exceptionOrNull() }, "owned writer failed")
            fixture.assertStoppedWithoutParentFailure()
            assertEquals("One complete frame and one failing write; no replay", 3, fixture.host.writeAttempts.get())
        }
    }

    @Test(timeout = 10_000) fun abruptStderrFailureReleasesPendingOwnerWithoutUncaughtCoroutineError() = runBlocking {
        Fixture().use { fixture ->
            fixture.rpc.start()
            val pending = async { runCatching { fixture.rpc.request("hover", timeoutMs = 3000) } }
            withTimeout(1000) { fixture.host.frames.receive() }
            fixture.host.stderrInput.fail(IOException("owned stderr disconnected"))
            assertIoFailure(withTimeout(1000) { pending.await().exceptionOrNull() }, "owned stderr disconnected")
            fixture.assertStoppedWithoutParentFailure()
            assertEquals(1, fixture.host.starts.get())
        }
    }

    @Test(timeout = 10_000) fun inboundReplyFailureReleasesPendingOwnerWithoutCancelingRegularParent() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        Fixture { _, _ -> entered.complete(Unit); release.await(); JsonPrimitive("handled") }.use { fixture ->
            fixture.rpc.start()
            val pending = async { runCatching { fixture.rpc.request("hover", timeoutMs = 3000) } }
            withTimeout(1000) { fixture.host.frames.receive() }
            fixture.host.inboundRequest()
            withTimeout(1000) { entered.await() }
            fixture.host.failWrites.set(true)
            release.complete(Unit)
            assertIoFailure(withTimeout(1000) { pending.await().exceptionOrNull() }, "owned writer failed")
            fixture.assertStoppedWithoutParentFailure()
            assertEquals(3, fixture.host.writeAttempts.get())
        }
    }

    @Test(timeout = 10_000) fun inboundHandlerReturningAfterCloseCannotEscapeToParentExceptionHandler() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val handlerReturned = CompletableDeferred<Unit>()
        Fixture { _, _ ->
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            handlerReturned.complete(Unit)
            JsonPrimitive("handled")
        }.use { fixture ->
            try {
                fixture.rpc.start()
                val pending = async { runCatching { fixture.rpc.request("hover", timeoutMs = 3000) } }
                withTimeout(1000) { fixture.host.frames.receive() }
                fixture.host.inboundRequest()
                withTimeout(1000) { entered.await() }
                fixture.rpc.close()
                release.complete(Unit)
                withTimeout(1000) { handlerReturned.await() }
                assertTrue(withTimeout(1000) { pending.await().exceptionOrNull() } is CancellationException)
                fixture.assertStoppedWithoutParentFailure()
                assertEquals("Late inbound reply must not write or replay", 2, fixture.host.writeAttempts.get())
            } finally { release.complete(Unit) }
        }
    }

    @Test(timeout = 10_000) fun parentCancellationClosesBlockedReadersAndReleasesAllOwnedChildren() = runBlocking {
        Fixture().use { fixture ->
            fixture.rpc.start()
            fixture.parent.cancel()
            withTimeout(1000) { fixture.parent.join() }
            assertFalse(fixture.rpc.isRunning)
            assertTrue(fixture.host.closed.get())
            assertTrue(fixture.uncaught.isEmpty())
            assertEquals(0, pendingCount(fixture.rpc))
        }
    }

    @Test(timeout = 10_000) fun parentCancellationBeforeStartReleasesUnstartedOwnerWithoutSpawning() = runBlocking {
        Fixture().use { fixture ->
            fixture.parent.cancel()
            withTimeout(1000) { fixture.parent.join() }
            assertTrue(runCatching { fixture.rpc.start() }.exceptionOrNull() is IllegalStateException)
            assertEquals(0, fixture.host.starts.get())
            assertFalse(fixture.rpc.isRunning)
            assertTrue(fixture.uncaught.isEmpty())
            assertEquals(0, pendingCount(fixture.rpc))
            assertTrue(transportJob(fixture.rpc).isCompleted)
        }
    }

    @Test(timeout = 10_000) fun failedStartupAndUnstartedCloseLeaveNoOwnedJobAttachedToParent() = runBlocking {
        Fixture().use { fixture ->
            fixture.host.startFailure = IOException("owned spawn failed")
            assertIoFailure(runCatching { fixture.rpc.start() }.exceptionOrNull(), "owned spawn failed")
            fixture.assertStoppedWithoutParentFailure()
        }
        Fixture().use { fixture ->
            fixture.rpc.close()
            fixture.assertStoppedWithoutParentFailure()
            assertEquals(0, fixture.host.starts.get())
        }
    }

    @Test(timeout = 10_000) fun canceledQueuedStartDoesNotCloseTransportOwnedByFirstCaller() = runBlocking {
        Fixture().use { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.host.startEntered = entered
            fixture.host.releaseStart = release
            val first = async { fixture.rpc.start() }
            try {
                withTimeout(1000) { entered.await() }
                // Same dispatcher context makes withContext(IO) undispatched; this caller reaches the held mutex
                // before launch returns. No timer race.
                val queued = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { fixture.rpc.start() }
                assertTrue(queued.isActive)
                queued.cancelAndJoin()
                assertFalse(fixture.host.closed.get())
                assertTrue(transportJob(fixture.rpc).isActive)
                release.complete(Unit)
                withTimeout(1000) { first.await() }
                assertTrue(fixture.rpc.isRunning)
                assertEquals(1, fixture.host.starts.get())
                fixture.rpc.notify("still-healthy")
                assertTrue(withTimeout(1000) { fixture.host.frames.receive() }.contains("still-healthy"))
                fixture.rpc.close()
                fixture.assertStoppedWithoutParentFailure()
            } finally { release.complete(Unit) }
        }
    }

    @Test(timeout = 10_000) fun canceledIdempotentStartAtContextReturnDoesNotCloseExistingTransport() = runBlocking {
        Fixture().use { fixture ->
            fixture.rpc.start()
            val tasks = LinkedBlockingQueue<Runnable>()
            val callerDispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
            }
            val probeEntered = CountDownLatch(1)
            val releaseProbe = CountDownLatch(1)
            fixture.host.nextAliveProbe.set(probeEntered to releaseProbe)
            val caller = launch(callerDispatcher) { fixture.rpc.start() }
            fun nextTask(): Runnable = tasks.poll(2, TimeUnit.SECONDS) ?: error("Missing start continuation")
            try {

                nextTask().run()
                withContext(Dispatchers.IO) { assertTrue(probeEntered.await(2, TimeUnit.SECONDS)) }
                releaseProbe.countDown()
                val returning = withContext(Dispatchers.IO) { nextTask() }
                // Force prompt cancellation at the real withContext return boundary, without adding a production
                // seam or delaying startup heuristically.
                caller.cancel()
                returning.run()
                withTimeout(1000) { caller.join() }
                assertTrue(fixture.rpc.isRunning)
                assertFalse(fixture.host.closed.get())
                assertEquals(1, fixture.host.starts.get())
                fixture.rpc.notify("still-healthy")
                assertTrue(withTimeout(1000) { fixture.host.frames.receive() }.contains("still-healthy"))
                fixture.rpc.close()
                fixture.assertStoppedWithoutParentFailure()
            } finally {
                releaseProbe.countDown()
                caller.cancel()
                withContext(NonCancellable + Dispatchers.IO) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (!caller.isCompleted && System.nanoTime() < deadline) {
                        tasks.poll(20, TimeUnit.MILLISECONDS)?.run()
                    }
                    assertTrue("Queued caller must finish during fixture cleanup", caller.isCompleted)
                }
            }
        }
    }

    private class Fixture(
        handler: suspend (String, kotlinx.serialization.json.JsonElement?) -> kotlinx.serialization.json.JsonElement? = { _, _ -> JsonPrimitive("ok") },
    ) : AutoCloseable {
        val parent = Job() // Deliberately not SupervisorJob: an IDE parent must survive protocol IO failures.
        val uncaught = CopyOnWriteArrayList<Throwable>()
        private val scope = CoroutineScope(parent + Dispatchers.Default + CoroutineExceptionHandler { _, t -> uncaught += t })
        private val sibling = scope.launch { awaitCancellation() }
        val host = Host()
        val rpc = JsonRpcProcess(scope, File("."), listOf("fake-protocol-peer"), processHost = host, inboundRequestHandler = handler)

        suspend fun assertStoppedWithoutParentFailure() {
            withTimeout(2000) { transportJob(rpc).join() }
            assertFalse(rpc.isRunning)
            assertTrue(parent.isActive)
            assertTrue(sibling.isActive)
            assertTrue("No protocol exception may escape to the IDE handler: $uncaught", uncaught.isEmpty())
            assertEquals(0, pendingCount(rpc))
            assertEquals("Only the unrelated sibling remains", listOf(sibling), parent.children.toList())
        }

        override fun close() { rpc.close(); host.close(); scope.cancel() }
    }

    private class Host : StdioProcessHost, AutoCloseable {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        val starts = AtomicInteger()
        val writeAttempts = AtomicInteger()
        val closed = AtomicBoolean()
        val failWrites = AtomicBoolean()
        val frames = Channel<String>(Channel.UNLIMITED)
        val stderrInput = BlockingStderr()
        var startFailure: IOException? = null
        var startEntered: CompletableDeferred<Unit>? = null
        var releaseStart: CompletableDeferred<Unit>? = null
        val nextAliveProbe = AtomicReference<Pair<CountDownLatch, CountDownLatch>?>(null)
        private val stdoutInput = QueuedInput()
        private val bytes = ByteArrayOutputStream()
        private val stdinOutput = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
                writeAttempts.incrementAndGet()
                if (failWrites.get()) throw IOException("owned writer failed")
                if (closed.get()) throw IOException("owned stdin closed")
                bytes.write(b, off, len)
            }
            @Synchronized override fun flush() {
                val encoded = bytes.toByteArray()
                bytes.reset()
                frames.trySend(ContentLengthProtocol.readMessage(ByteArrayInputStream(encoded)) ?: error("missing frame"))
            }
        }

        override suspend fun resolveExecutable(command: String): String = command
        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
            starts.incrementAndGet()
            startEntered?.complete(Unit)
            releaseStart?.await()
            startFailure?.let { throw it }
            return object : HostedStdioProcess {
                override val stdin = stdinOutput
                override val stdout = stdoutInput
                override val stderr = stderrInput
                override val isAlive: Boolean get() {
                    nextAliveProbe.getAndSet(null)?.let { (entered, release) ->
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "Liveness probe fixture was not released" }
                    }
                    return !closed.get()
                }
                override val exitCode: Int? get() = if (closed.get()) 0 else null
                override suspend fun awaitExit(): Int { while (!closed.get()) delay(5); return 0 }
                override fun close() = this@Host.close()
            }
        }

        fun inboundRequest() {
            val encoded = ByteArrayOutputStream()
            ContentLengthProtocol.writeMessage(encoded, """{"jsonrpc":"2.0","id":"peer1","method":"workspace/configuration"}""")
            stdoutInput.offer(encoded.toByteArray())
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            stdoutInput.close()
            stderrInput.close()
            frames.close()
        }
    }

    private class QueuedInput : InputStream() {
        private val queue = LinkedBlockingQueue<Int>()
        override fun read(): Int = queue.take()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val value = read()
            if (value < 0) return -1
            b[off] = value.toByte()
            return 1
        }
        fun offer(bytes: ByteArray) { bytes.forEach { queue.add(it.toInt() and 0xff) } }
        override fun close() { queue.offer(-1) }
    }

    private class BlockingStderr : InputStream() {
        private val release = CountDownLatch(1)
        @Volatile private var failure: IOException? = null
        override fun read(): Int { release.await(); failure?.let { throw it }; return -1 }
        override fun read(b: ByteArray, off: Int, len: Int): Int = read()
        fun fail(error: IOException) { failure = error; release.countDown() }
        override fun close() { fail(IOException("owned stderr closed")) }
    }

    private companion object {
        fun assertIoFailure(failure: Throwable?, message: String) {
            assertTrue("Expected original IO error, got $failure", failure is IOException)
            assertEquals(message, failure?.message)
        }
        fun pendingCount(rpc: JsonRpcProcess): Int =
            (JsonRpcProcess::class.java.getDeclaredField("pendingCount").apply { isAccessible = true }.get(rpc) as AtomicInteger).get()
        fun transportJob(rpc: JsonRpcProcess): Job =
            JsonRpcProcess::class.java.getDeclaredField("transportJob").apply { isAccessible = true }.get(rpc) as Job
    }
}
