package com.baystudio.droide.core

import com.flyfishxu.kadb.KadbConnectionControl
import com.flyfishxu.kadb.core.AdbHandshakeSecurity
import com.flyfishxu.kadb.core.AdbProtocol
import com.flyfishxu.kadb.decodePairedDeviceGuid
import com.flyfishxu.kadb.transport.PlainBlockingChannel
import com.flyfishxu.kadb.shell.AdbShellPacket
import com.flyfishxu.kadb.shell.readShellPacket
import com.flyfishxu.kadb.queue.MessageQueue
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import okio.Buffer

/** Tests the derivative production classes. Socket subclasses never perform network operations. */
class KadbReconstructionSafetyTest {
    @Test fun oversizedShellHeaderFailsBeforeAnyPayloadIsBuffered() {
        val source = Buffer().writeByte(1).writeIntLe(Int.MAX_VALUE)
        val error = runCatching { readShellPacket(source, 16_384) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("output limit"))
        assertEquals(0L, source.size)
    }
    @Test fun shellFramesHonorRemainingBudgetAndStillAllowExitAtZeroBudget() {
        val source = Buffer().writeByte(1).writeIntLe(3).writeUtf8("abc")
            .writeByte(2).writeIntLe(2).writeUtf8("de")
        assertEquals(3, readShellPacket(source, 4).payload.size)
        assertTrue(runCatching { readShellPacket(source, 1) }.isFailure)
        assertEquals(2L, source.size)
        assertTrue(readShellPacket(Buffer().writeByte(3).writeIntLe(1).writeByte(0), 0) is AdbShellPacket.Exit)
    }
    @Test fun truncatedShellHeaderFailsWithoutAllocatingDeclaredPayload() {
        assertTrue(runCatching { readShellPacket(Buffer().writeByte(1).writeByte(4), 16_384) }
            .exceptionOrNull() is java.io.EOFException)
    }
    @Test fun requiredTlsRejectsPlainAuthAndConnectionBeforeProcessing() {
        assertTrue(runCatching { AdbHandshakeSecurity(true).before(AdbProtocol.CMD_AUTH) }.isFailure)
        assertTrue(runCatching { AdbHandshakeSecurity(true).before(AdbProtocol.CMD_CNXN) }.isFailure)
    }
    @Test fun onlyCompletedUpgradePermitsTlsConnectionAndRepeatedUpgradeFails() {
        val policy = AdbHandshakeSecurity(true)
        policy.before(AdbProtocol.CMD_STLS)
        assertFalse(policy.encrypted)
        assertTrue(runCatching { policy.before(AdbProtocol.CMD_CNXN) }.isFailure)
        policy.handshakeCompleted()
        policy.before(AdbProtocol.CMD_CNXN)
        assertTrue(policy.encrypted)
        assertTrue(runCatching { policy.before(AdbProtocol.CMD_STLS) }.isFailure)
    }
    @Test fun explicitLegacyStillAcceptsAuthAndPlainConnection() {
        val policy = AdbHandshakeSecurity(false)
        policy.before(AdbProtocol.CMD_AUTH)
        policy.before(AdbProtocol.CMD_CNXN)
        assertFalse(policy.encrypted)
    }
    @Test fun pairingResultRequiresGuidTypeTerminatorAndSafeBoundedData() {
        val bytes = ByteArray(8191)
        "adb-serial-A1b2C3".toByteArray().copyInto(bytes)
        assertEquals("adb-serial-A1b2C3", decodePairedDeviceGuid(1, bytes))
        assertTrue(runCatching { decodePairedDeviceGuid(0, bytes) }.isFailure)
        assertTrue(runCatching { decodePairedDeviceGuid(1, ByteArray(8191)) }.isFailure)
        assertTrue(runCatching { decodePairedDeviceGuid(1, ByteArray(8191) { 'a'.code.toByte() }) }.isFailure)
        bytes[100] = 1
        assertTrue(runCatching { decodePairedDeviceGuid(1, bytes) }.isFailure)
    }
    @Test fun terminalCancellationClosesLateOwnedHandlesAndCannotBeReset() {
        val control = KadbConnectionControl()
        var closed = 0
        control.own(Closeable { closed++ })
        control.cancel()
        control.cancel()
        assertTrue(runCatching { control.own(Closeable { closed++ }) }.isFailure)
        assertTrue(runCatching { control.checkActive() }.isFailure)
        assertEquals(2, closed)
    }
    @Test(timeout = 5_000) fun productionTcpFactoryOwnsRawSocketBeforeBlockingConnect() {
        val control = KadbConnectionControl()
        val socket = FakeSocket()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> {
                runCatching {
                    PlainBlockingChannel.connect("fixture.invalid", 1, 8_000, control,
                        socketFactory = { socket }, resolve = { _, port -> InetSocketAddress.createUnresolved("fixture.invalid", port) })
                }.isFailure
            }
            assertTrue(socket.entered.await(2, TimeUnit.SECONDS))
            control.cancel()
            assertTrue(result.get(2, TimeUnit.SECONDS))
            assertTrue(socket.closed.get())
            assertEquals(1, socket.connects.get())
        } finally { control.cancel(); executor.shutdownNow() }
    }
    @Test(timeout = 5_000) fun latePlatformResolverResultCannotStartTcpAfterCancellation() {
        val control = KadbConnectionControl()
        val socket = FakeSocket()
        val resolving = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> {
                runCatching {
                    PlainBlockingChannel.connect("fixture.invalid", 1, 8_000, control,
                        socketFactory = { socket }, resolve = { _, port ->
                            resolving.countDown()
                            release.await()
                            InetSocketAddress.createUnresolved("fixture.invalid", port)
                        })
                }.isFailure
            }
            assertTrue(resolving.await(2, TimeUnit.SECONDS))
            control.cancel()
            release.countDown()
            assertTrue(result.get(2, TimeUnit.SECONDS))
            assertEquals(0, socket.connects.get())
            assertTrue(socket.closed.get())
        } finally { release.countDown(); control.cancel(); executor.shutdownNow() }
    }

    @Test(timeout = 5_000) fun terminalCancelUnblocksProductionRawRead() = exerciseBlockedRawIo(write = false)

    @Test(timeout = 5_000) fun terminalCancelUnblocksProductionRawWrite() = exerciseBlockedRawIo(write = true)

    private fun exerciseBlockedRawIo(write: Boolean) {
        val control = KadbConnectionControl()
        val socket = FakeIoSocket()
        val channel = PlainBlockingChannel.connect("fixture.invalid", 1, 8_000, control,
            socketFactory = { socket }, resolve = { _, port -> InetSocketAddress.createUnresolved("fixture.invalid", port) })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> {
                runCatching {
                    runBlocking {
                        if (write) channel.write(ByteBuffer.wrap(byteArrayOf(1)), 0, TimeUnit.MILLISECONDS)
                        else channel.read(ByteBuffer.allocate(1), 0, TimeUnit.MILLISECONDS)
                    }
                }.exceptionOrNull() is IOException
            }
            assertTrue(socket.entered.await(2, TimeUnit.SECONDS))
            control.cancel()
            assertTrue(result.get(2, TimeUnit.SECONDS))
            assertTrue(socket.closed.get())
        } finally { control.cancel(); channel.close(); executor.shutdownNow() }
    }

    @Test(timeout = 5_000) fun queueReadFailureWakesAnotherWaitingReader() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queue = object : MessageQueue<Int>() {
            override fun readMessage(): Int {
                entered.countDown()
                release.await()
                throw IOException("fixture read failure")
            }
            override fun getLocalId(message: Int) = message
            override fun getCommand(message: Int) = 1
            override fun isCloseCommand(message: Int) = false
        }
        queue.startListening(1)
        queue.startListening(2)
        val failures = java.util.concurrent.LinkedBlockingQueue<Throwable>()
        fun reader(id: Int) = Thread {
            runCatching { queue.take(id, 1) }.exceptionOrNull()?.let { failures.put(it) }
        }.apply { isDaemon = true }
        val first = reader(1)
        val second = reader(2)
        try {
            first.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            second.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (second.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(Thread.State.WAITING, second.state)
            release.countDown()
            repeat(2) {
                val failure = failures.poll(2, TimeUnit.SECONDS)
                assertTrue(failure is IOException)
                assertEquals("fixture read failure", failure?.message)
            }
        } finally {
            release.countDown()
            first.interrupt(); second.interrupt()
            first.join(500); second.join(500)
        }
    }

    private class FakeIoSocket : Socket() {
        val entered = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        private val released = CountDownLatch(1)
        private var timeout = 0
        override fun setKeepAlive(on: Boolean) {}
        override fun setTcpNoDelay(on: Boolean) {}
        override fun connect(endpoint: SocketAddress, timeout: Int) {}
        override fun setSoTimeout(value: Int) { timeout = value }
        override fun getSoTimeout() = timeout
        override fun getInputStream() = object : InputStream() {
            override fun read(): Int { entered.countDown(); released.await(); throw IOException("Fake raw read closed") }
        }
        override fun getOutputStream() = object : OutputStream() {
            override fun write(value: Int) { entered.countDown(); released.await(); throw IOException("Fake raw write closed") }
        }
        override fun close() { closed.set(true); released.countDown() }
    }

    private class FakeSocket : Socket() {
        val entered = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val connects = AtomicInteger()
        private val released = CountDownLatch(1)
        override fun setKeepAlive(on: Boolean) {}
        override fun setTcpNoDelay(on: Boolean) {}
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            connects.incrementAndGet()
            entered.countDown()
            released.await()
            throw IOException("Fake socket closed")
        }
        override fun close() { closed.set(true); released.countDown() }
    }
}
