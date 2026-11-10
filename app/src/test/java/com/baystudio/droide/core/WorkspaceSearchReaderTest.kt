package com.baystudio.droide.core

import java.io.File
import java.io.InputStream
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSearchReaderTest {
    private class Source(val data: ByteArray, val chunk: Int = Int.MAX_VALUE, val afterRead: () -> Unit = {}) : InputStream() {
        var reads = 0
        var closes = 0
        private var offset = 0
        override fun read(): Int = error("The decoder should use the admitted bulk operation")
        override fun read(buffer: ByteArray, start: Int, count: Int): Int {
            reads++
            if (offset == data.size) return -1
            val n = minOf(count, chunk, data.size - offset)
            data.copyInto(buffer, start, offset, offset + n)
            offset += n
            afterRead()
            return n
        }
        override fun close() { closes++ }
    }
    private fun budget(limits: WorkspaceSearchLimits = WorkspaceSearchLimits(), clock: () -> Long = { 0L }) =
        WorkspaceSearchBudget(limits, EmptyCoroutineContext, WorkspaceRequestDeadline(limits.durationNanos, clock))

    private suspend fun stop(block: suspend () -> Unit): WorkspaceSearchStopReason {
        try { block() } catch (limit: WorkspaceSearchLimitException) { return limit.reason }
        throw AssertionError("Expected a typed search stop")
    }

    @Test(timeout = 15_000) fun bytesAreChargedBeforeAnExtraRead(): Unit = runBlocking {
        val source = Source("ab\ncd\n".toByteArray(), chunk = 1)
        val work = budget(WorkspaceSearchLimits(diskBytes = 4))
        val lines = mutableListOf<String>()
        assertEquals(WorkspaceSearchStopReason.BYTE_LIMIT, stop {
            WorkspaceSearchReader(work) { source }.scanFile(File("unused"), { true }) { _, line -> lines += line }
        })
        assertEquals(listOf("ab"), lines)
        assertEquals(4, source.reads)
        assertEquals(4L, work.stats.diskBytes)
        assertEquals(4, work.stats.readCalls)
        assertEquals(1, source.closes)
    }

    @Test(timeout = 15_000) fun exactQuotaDoesNotPretendToObserveEof(): Unit = runBlocking {
        for (quota in listOf(3L, 4L)) {
            val source = Source("ab\n".toByteArray())
            val work = budget(WorkspaceSearchLimits(diskBytes = quota))
            val lines = mutableListOf<String>()
            val reader = WorkspaceSearchReader(work) { source }
            if (quota == 3L) assertEquals(WorkspaceSearchStopReason.BYTE_LIMIT, stop {
                reader.scanFile(File("unused"), { true }) { _, line -> lines += line }
            }) else reader.scanFile(File("unused"), { true }) { _, line -> lines += line }
            assertEquals(listOf("ab"), lines)
            assertEquals(if (quota == 3L) 1 else 2, source.reads)
            assertEquals(3L, work.stats.diskBytes)
            assertEquals(1, source.closes)
        }
    }

    @Test(timeout = 15_000) fun quotaCutUtf8IsNotReplacementOrAFinalLine(): Unit = runBlocking {
        val bytes = "€\n".toByteArray(Charsets.UTF_8)
        val source = Source(bytes, chunk = 1)
        val lines = mutableListOf<String>()
        assertEquals(WorkspaceSearchStopReason.BYTE_LIMIT, stop {
            WorkspaceSearchReader(budget(WorkspaceSearchLimits(diskBytes = 2))) { source }
                .scanFile(File("unused"), { true }) { _, line -> lines += line }
        })
        assertTrue(lines.isEmpty())
        assertEquals(2, source.reads)
        assertEquals(1, source.closes)
        val actualMalformed = Source(bytes.copyOf(2), chunk = 1)
        WorkspaceSearchReader(budget()) { actualMalformed }
            .scanFile(File("unused"), { true }) { _, line -> lines += line }
        assertEquals(listOf("\uFFFD"), lines)
    }

    @Test(timeout = 15_000) fun perFileQuotaIsEnforcedDuringReading(): Unit = runBlocking {
        val source = Source("abcd".toByteArray())
        val work = budget(WorkspaceSearchLimits(fileBytes = 2))
        val lines = mutableListOf<String>()
        assertEquals(WorkspaceSearchStopReason.FILE_BYTE_LIMIT, stop {
            WorkspaceSearchReader(work) { source }.scanFile(File("unused"), { true }) { _, line -> lines += line }
        })
        assertTrue(lines.isEmpty())
        assertEquals(1, source.reads)
        assertEquals(2L, work.stats.diskBytes)
        assertEquals(1, source.closes)
    }

    @Test(timeout = 15_000) fun splitMultibyteAndCrLfKeepLineSemantics(): Unit = runBlocking {
        val lines = mutableListOf<String>()
        val source = Source("€\r\n𐐀\nz".toByteArray(), chunk = 1)
        WorkspaceSearchReader(budget()) { source }.scanFile(File("unused"), { true }) { _, line -> lines += line }
        assertEquals(listOf("€", "𐐀", "z"), lines)
        lines.clear()
        assertEquals(WorkspaceSearchStopReason.BYTE_LIMIT, stop {
            WorkspaceSearchReader(budget(WorkspaceSearchLimits(diskBytes = 2))) { Source("a\r\nb".toByteArray()) }
                .scanFile(File("unused"), { true }) { _, line -> lines += line }
        })
        assertEquals(listOf("a"), lines)
    }

    @Test(timeout = 15_000) fun lineBoundsNeverEmitATruncatedPrefix(): Unit = runBlocking {
        val lines = mutableListOf<String>()
        val exact = WorkspaceSearchReader(budget(WorkspaceSearchLimits(lineChars = 4))) { Source("abcd\n".toByteArray()) }
        exact.scanFile(File("unused"), { true }) { _, line -> lines += line }
        assertEquals(listOf("abcd"), lines)
        lines.clear()
        assertEquals(WorkspaceSearchStopReason.LINE_LENGTH_LIMIT, stop {
            WorkspaceSearchReader(budget(WorkspaceSearchLimits(lineChars = 4))) { Source("abcde\n".toByteArray()) }
                .scanFile(File("unused"), { true }) { _, line -> lines += line }
        })
        assertTrue(lines.isEmpty())
        val work = budget(WorkspaceSearchLimits(lines = 2))
        assertEquals(WorkspaceSearchStopReason.LINE_LIMIT, stop {
            WorkspaceSearchReader(work) { Source("a\nb\nc\n".toByteArray()) }
                .scanFile(File("unused"), { true }) { _, line -> lines += line }
        })
        assertEquals(listOf("a", "b"), lines)
        assertEquals(2, work.stats.lines)
    }

    @Test(timeout = 15_000) fun dirtyAndDiskTrailingEmptyAccountingIsPreserved(): Unit = runBlocking {
        for ((content, dirtyExpected, diskExpected) in listOf(
            Triple("", listOf(""), emptyList()),
            Triple("a\n", listOf("a", ""), listOf("a")),
            Triple("\r\n", listOf("", ""), listOf("")),
        )) {
            val dirty = mutableListOf<String>()
            WorkspaceSearchReader(budget()).scanDirty(content, { true }) { _, line -> dirty += line }
            val disk = mutableListOf<String>()
            WorkspaceSearchReader(budget()) { Source(content.toByteArray(), chunk = 1) }
                .scanFile(File("unused"), { true }) { _, line -> disk += line }
            assertEquals(dirtyExpected, dirty)
            assertEquals(diskExpected, disk)
        }
    }

    @Test(timeout = 15_000) fun opensReadsCancellationAndDeadlineAreAdmitted(): Unit = runBlocking {
        var opened = 0
        assertEquals(WorkspaceSearchStopReason.SOURCE_OPEN_LIMIT, stop {
            WorkspaceSearchReader(budget(WorkspaceSearchLimits(sourceOpens = 0))) { opened++; Source(byteArrayOf()) }
                .scanFile(File("unused"), { true }) { _, _ -> }
        })
        assertEquals(0, opened)
        val source = Source("abcd".toByteArray(), chunk = 1)
        assertEquals(WorkspaceSearchStopReason.READ_LIMIT, stop {
            WorkspaceSearchReader(budget(WorkspaceSearchLimits(readCalls = 2))) { source }
                .scanFile(File("unused"), { true }) { _, _ -> }
        })
        assertEquals(2, source.reads)
        assertEquals(1, source.closes)
        var now = 0L
        val timed = Source("ab\n".toByteArray(), afterRead = { now = 6L })
        val work = budget(WorkspaceSearchLimits(durationNanos = 5L)) { now }
        assertEquals(WorkspaceSearchStopReason.DEADLINE, stop {
            WorkspaceSearchReader(work) { timed }.scanFile(File("unused"), { true }) { _, _ -> }
        })
        assertEquals(1, timed.reads)
        assertEquals(1, timed.closes)
        val job = Job().apply { cancel() }
        val cancelled = WorkspaceSearchBudget(WorkspaceSearchLimits(), job)
        assertThrows(CancellationException::class.java) { cancelled.checkpoint() }
        assertTrue(cancelled.stopReasons.isEmpty())
    }
}
