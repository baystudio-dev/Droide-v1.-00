package com.baystudio.droide.core

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real host TCP sockets exercise blocking I/O; this does not claim Android LocalSocket coverage. */
class CliConnectionLifetimeTest {
    @Test(timeout = 10_000) fun cancelAlsoUnblocksProgressWriterAndCancelsOnlyItsOwnedWork() = runBlocking<Unit> {
        socketPair().use { pair ->
            pair.server.sendBufferSize = 1_024
            pair.client.receiveBufferSize = 1_024
            val writing = CountDownLatch(1)
            val canceled = CompletableDeferred<Unit>()
            val finalFrames = AtomicInteger()
            val server = launch(Dispatchers.Default) {
                try { CliConnectionLifetime.serve(
                    operation = { CliProgressRelay.execute(operation = {
                        currentCoroutineContext()[PackageProgressObserver]!!.publish(PackageProgressSnapshot("installing", "Working"))
                        try { awaitCancellation() } finally { canceled.complete(Unit) }
                    }, writeProgress = {
                        writing.countDown()
                        // Exercise the same blocking I/O cancellation boundary beyond OS buffers.
                        pair.server.getOutputStream().write(ByteArray(8 * 1024 * 1024))
                    }) },
                    readPeer = { pair.server.getInputStream().read() },
                    closePeer = pair::shutdownServer,
                    writeResponse = { finalFrames.incrementAndGet() },
                ) } catch (failure: IOException) {
                    if (currentCoroutineContext().isActive) throw failure
                }
            }
            assertTrue(writing.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) { server.cancelAndJoin(); canceled.await() }
            assertEquals(0, finalFrames.get())
            assertTrue(pair.server.isClosed)
        }
    }

    @Test(timeout = 10_000) fun peerDisconnectAfterProgressCancelsWorkWithoutAFalseFinalFrame() = runBlocking<Unit> {
        socketPair().use { pair ->
            val canceled = CompletableDeferred<Unit>()
            val finalFrames = AtomicInteger()
            val server = launch(Dispatchers.Default) {
                CliConnectionLifetime.serve(
                    operation = { CliProgressRelay.execute(operation = {
                        currentCoroutineContext()[PackageProgressObserver]!!.publish(PackageProgressSnapshot("downloading", "Fetching"))
                        try { awaitCancellation() } finally { canceled.complete(Unit) }
                    }, writeProgress = { DroideCliWireProtocol.writeProgress(pair.server.getOutputStream(), it) }) },
                    readPeer = { pair.server.getInputStream().read() },
                    closePeer = pair::shutdownServer,
                    writeResponse = { finalFrames.incrementAndGet() },
                )
            }
            val observed = withContext(Dispatchers.IO) { DroideCliWireProtocol.readResponseFrame(pair.client.getInputStream()) }
            assertTrue(observed is DroideCliWireProtocol.ResponseFrame.Progress)
            pair.client.close()
            withTimeout(3_000) { server.join(); canceled.await() }
            assertEquals(0, finalFrames.get())
            assertTrue(pair.server.isClosed)
        }
    }

    @Test(timeout = 10_000) fun cancelingOperationUnblocksPeerReadBeforeStructuredJoin() = runBlocking<Unit> {
        socketPair().use { pair ->
            val started = CompletableDeferred<Unit>()
            val peerReading = CountDownLatch(1)
            val closed = AtomicInteger()
            val writes = AtomicInteger()
            val server = launch(Dispatchers.Default) {
                CliConnectionLifetime.serve(
                    operation = { started.complete(Unit); awaitCancellation() },
                    readPeer = { peerReading.countDown(); pair.server.getInputStream().read() },
                    closePeer = { closed.incrementAndGet(); pair.shutdownServer() },
                    writeResponse = { writes.incrementAndGet() },
                )
            }
            started.await()
            assertTrue(peerReading.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) { server.cancelAndJoin() }
            assertEquals(1, closed.get())
            assertEquals(0, writes.get())
            assertTrue(pair.server.isClosed)
            assertEquals(-1, withContext(Dispatchers.IO) { pair.client.getInputStream().read() })
        }
    }

    @Test(timeout = 10_000) fun cancellationInsideInstallerClosesSocketWithoutResponseOrReplay() = runBlocking<Unit> {
        socketPair().use { pair ->
            val peerReading = CountDownLatch(1)
            val release = CompletableDeferred<Unit>()
            val operations = AtomicInteger()
            val writes = AtomicInteger()
            val result = async(Dispatchers.Default) {
                runCatching {
                    CliConnectionLifetime.serve(
                        operation = {
                            operations.incrementAndGet()
                            release.await()
                            throw CancellationException("installer canceled")
                        },
                        readPeer = { peerReading.countDown(); pair.server.getInputStream().read() },
                        closePeer = pair::shutdownServer,
                        writeResponse = { writes.incrementAndGet() },
                    )
                }
            }
            assertTrue(peerReading.await(3, TimeUnit.SECONDS))
            release.complete(Unit)
            assertTrue(withTimeout(3_000) { result.await() }.exceptionOrNull() is CancellationException)
            assertEquals(1, operations.get())
            assertEquals(0, writes.get())
            assertTrue(pair.server.isClosed)
        }
    }

    @Test(timeout = 10_000) fun disconnectCancelsOwnedOperationAndNeverWritesAResult() = runBlocking<Unit> {
        socketPair().use { pair ->
            val started = CompletableDeferred<Unit>()
            val canceled = CompletableDeferred<Unit>()
            val writes = AtomicInteger()
            val server = launch(Dispatchers.Default) {
                CliConnectionLifetime.serve(
                    operation = {
                        started.complete(Unit)
                        try { awaitCancellation() } finally { canceled.complete(Unit) }
                    },
                    readPeer = { pair.server.getInputStream().read() },
                    closePeer = pair::shutdownServer,
                    writeResponse = { writes.incrementAndGet() },
                )
            }
            started.await()
            pair.client.close()
            withTimeout(3_000) { server.join(); canceled.await() }
            assertEquals(0, writes.get())
            assertTrue(pair.server.isClosed)
        }
    }

    @Test(timeout = 10_000) fun completedOperationWritesOneValidResponseAndClosesTheWaitingRead() = runBlocking<Unit> {
        socketPair().use { pair ->
            val release = CompletableDeferred<Unit>()
            val peerReading = CountDownLatch(1)
            val operations = AtomicInteger()
            val expected = DroideCliExecutionResult(4, "installed 日本語", "health check failed")
            val server = launch(Dispatchers.Default) {
                CliConnectionLifetime.serve(
                    operation = { operations.incrementAndGet(); release.await(); expected },
                    readPeer = { peerReading.countDown(); pair.server.getInputStream().read() },
                    closePeer = pair::shutdownServer,
                    writeResponse = { DroideCliWireProtocol.writeResponse(pair.server.getOutputStream(), it) },
                )
            }
            assertTrue(peerReading.await(3, TimeUnit.SECONDS))
            release.complete(Unit)
            val actual = withTimeout(3_000) { withContext(Dispatchers.IO) {
                DroideCliWireProtocol.readResponse(pair.client.getInputStream())
            } }
            withTimeout(3_000) { server.join() }
            assertEquals(expected, actual)
            assertEquals(1, operations.get())
            assertEquals(-1, pair.client.getInputStream().read())
        }
    }

    @Test(timeout = 10_000) fun cancellationAlsoUnblocksAResponseWriteWhenPeerStopsReading() = runBlocking<Unit> {
        socketPair().use { pair ->
            pair.server.sendBufferSize = 1_024
            pair.client.receiveBufferSize = 1_024
            val writing = CountDownLatch(1)
            val closed = AtomicInteger()
            val server = launch(Dispatchers.Default) {
                try { CliConnectionLifetime.serve(
                    operation = { DroideCliExecutionResult(0) },
                    readPeer = { pair.server.getInputStream().read() },
                    closePeer = { closed.incrementAndGet(); pair.shutdownServer() },
                    writeResponse = {
                        writing.countDown()
                        // Larger than OS socket buffers, independent of production frame limits. This exercises the
                        // helper's blocking write cancellation boundary.
                        pair.server.getOutputStream().write(ByteArray(8 * 1024 * 1024))
                    },
                ) } catch (failure: IOException) {
                    if (currentCoroutineContext().isActive) throw failure
                }
            }
            assertTrue(writing.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) { server.cancelAndJoin() }
            assertEquals(1, closed.get())
            assertTrue(pair.server.isClosed)
        }
    }

    @Test(timeout = 10_000) fun failedOperationUnblocksPeerAndPropagatesOriginalFailure() = runBlocking<Unit> {
        socketPair().use { pair ->
            val peerReading = CountDownLatch(1)
            val release = CompletableDeferred<Unit>()
            val expected = IOException("installer fixture failed")
            val server = async(Dispatchers.Default) {
                runCatching { CliConnectionLifetime.serve(
                    operation = { release.await(); throw expected },
                    readPeer = { peerReading.countDown(); pair.server.getInputStream().read() },
                    closePeer = pair::shutdownServer,
                    writeResponse = { fail("failed operation must not write a successful frame") },
                ) }
            }
            assertTrue(peerReading.await(3, TimeUnit.SECONDS))
            release.complete(Unit)
            val actual = withTimeout(3_000) { server.await() }.exceptionOrNull()
            // Coroutine stacktrace recovery may copy IOException across await(), preserving its
            // type/message/cause rather than JVM object identity.
            assertEquals(expected.javaClass, actual?.javaClass)
            assertEquals(expected.message, actual?.message)
            assertTrue(pair.server.isClosed)
        }
    }

    private fun socketPair(): SocketPair {
        val loopback = InetAddress.getByName("127.0.0.1")
        return ServerSocket(0, 1, loopback).use { listener ->
            val client = Socket(loopback, listener.localPort).apply { soTimeout = 3_000 }
            SocketPair(listener.accept(), client)
        }
    }

    private class SocketPair(val server: Socket, val client: Socket) : AutoCloseable {
        fun shutdownServer() {
            runCatching { server.shutdownInput() }
            runCatching { server.shutdownOutput() }
            runCatching { server.close() }
        }
        override fun close() { shutdownServer(); runCatching { client.close() } }
    }
}
