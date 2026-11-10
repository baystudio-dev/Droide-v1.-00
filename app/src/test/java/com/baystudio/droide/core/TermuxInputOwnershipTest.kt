package com.baystudio.droide.core

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.lang.reflect.Proxy
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the vendored Java queue/input hook; deliberately does not start Android JNI/PRoot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TermuxInputOwnershipTest {
    private fun session(): TerminalSession {
        val client = Proxy.newProxyInstance(TerminalSessionClient::class.java.classLoader,
            arrayOf(TerminalSessionClient::class.java)) { _, _, _ -> null } as TerminalSessionClient
        return TerminalSession("/unused", "/", emptyArray(), emptyArray(), 100, client)
    }
    private fun queue(session: TerminalSession): Any = TerminalSession::class.java.getDeclaredField("mTerminalToProcessIOQueue")
        .apply { isAccessible = true }.get(session)!!
    private fun setPid(session: TerminalSession, pid: Int) = TerminalSession::class.java.getDeclaredField("mShellPid")
        .apply { isAccessible = true }.setInt(session, pid)
    private fun closeQueue(queue: Any) { queue.javaClass.getDeclaredMethod("close").apply { isAccessible = true }.invoke(queue) }

    @Test fun allWriteFormsUseOneOwnerAndDeadNativeQueueIsNeverAcknowledged() {
        val terminal = session()
        val writes = mutableListOf<ByteArray>()
        terminal.setInputHandler { bytes, start, count -> writes += bytes.copyOfRange(start, start + count) }
        terminal.write("hello")
        terminal.writeCodePoint(false, 0x1f600)
        terminal.writeCodePoint(false, 'x'.code)
        terminal.write(byteArrayOf(0, 1, 2, 3), 1, 2)
        assertEquals("hello", String(writes[0]))
        assertEquals("😀", String(writes[1]))
        assertEquals("x", String(writes[2]))
        assertArrayEquals(byteArrayOf(1, 2), writes[3])
        assertTrue(runCatching { terminal.setInputHandler { _, _, _ -> } }.isFailure)

        assertTrue(runCatching { terminal.writeToPty(byteArrayOf(1), 0, 1) }.isFailure)
        val failed = CompletableDeferred<Boolean>()
        val worker = Thread { failed.complete(terminal.writeToPty(byteArrayOf(1), 0, 1)) }
        worker.start(); worker.join(2_000)
        assertFalse(worker.isAlive)
        assertFalse(runBlocking { failed.await() })
    }

    @Test(timeout = 8_000) fun saturatedByteQueueDoesNotBlockUiAndOneWriterKeepsHumanAgentChunksWhole() = runBlocking<Unit> {
        val terminal = session()
        setPid(terminal, 1) // Queue-only fixture; never JNI or real PID manipulation.
        val queue = queue(terminal)
        val started = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val errors = CopyOnWriteArrayList<String>()
        val relay = TerminalInputRelay(scope, CompletableDeferred(terminal), { target, bytes ->
            started.countDown()
            check(target.writeToPty(bytes, 0, bytes.size)) { "Queue closed after a partial write" }
        }, errors::add)
        terminal.setInputHandler { bytes, offset, count -> relay.send(bytes, offset, count) }
        val input = ProcessInputBuffer()
        input.bindPty(relay::send) { relay.send(byteArrayOf(4)) }
        try {
            terminal.write("H".repeat(65_536))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            // The first worker is now blocked in the original 4 KiB ByteQueue. Main only admits chunks.
            input.offer("A".repeat(65_536))
            terminal.write("U".repeat(65_536))
            input.offer("B".repeat(65_536))
            assertTrue(runCatching { input.offer("overflow") }.isFailure)
            assertTrue(errors.any { "256 KiB" in it })
            val read = queue.javaClass.getDeclaredMethod("read", ByteArray::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val received = withTimeout(3_000) { withContext(Dispatchers.IO) {
                val output = ByteArrayOutputStream()
                val block = ByteArray(1024)
                while (output.size() < 262_144) {
                    val count = read.invoke(queue, block, true) as Int
                    check(count > 0)
                    output.write(block, 0, count)
                }
                output.toString("UTF-8")
            } }
            assertEquals("H".repeat(65_536) + "A".repeat(65_536) + "U".repeat(65_536) + "B".repeat(65_536), received)
            // Cancellation closes the real upstream queue, waking its otherwise uninterruptible writer.
            terminal.write("pending".repeat(8_000))
            relay.close(reportUnsent = true)
            closeQueue(queue)
            assertFalse(relay.send("must not replay".toByteArray()))
        } finally {
            input.abort(); relay.close(); closeQueue(queue)
            scope.coroutineContext.job.cancelAndJoin()
        }
    }
}
