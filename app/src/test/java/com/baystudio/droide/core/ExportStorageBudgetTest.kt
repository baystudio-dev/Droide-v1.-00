package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class ExportStorageBudgetTest {
    @Test fun metadataRecoveryDoesNotRequireCaptureReserve() {
        ExportStorageBudget.requireMetadataAvailable(File("."), 8192)
        assertTrue(runCatching { ExportStorageBudget.requireAvailable(File("."), 0, 8192) }.isFailure)
        assertTrue(runCatching { ExportStorageBudget.requireMetadataAvailable(File("."), 0) }.isFailure)
    }
    @Test fun lowSpaceMidstreamPreservesAlreadyWrittenBytes() {
        var free = ExportStorageBudget.RESERVE + 10
        val bytes = ByteArrayOutputStream()
        val stream = ExportStorageBudget.output(File("."), bytes, 10) { free }
        stream.write(ByteArray(10) { 7 })
        free = ExportStorageBudget.RESERVE + 5
        try { stream.write(ByteArray(10)); fail("Insufficient space accepted") } catch (failure: ExportFailure) {
            assertEquals("InsufficientStorage", failure.category)
        }
        assertArrayEquals(ByteArray(10) { 7 }, bytes.toByteArray())
    }
    @Test fun metadataByteWritesUseBoundedProbeCredit() {
        var probes = 0
        val sink = object : java.io.OutputStream() { override fun write(value: Int) {} }
        val stream = ExportStorageBudget.output(File("."), sink) { probes++; ExportStorageBudget.RESERVE + 1024 * 1024 }
        repeat(1000) { stream.write(1) }
        assertTrue("Byte-sized metadata should not stat the filesystem per byte", probes < 20)
        assertTrue(runCatching { ExportStorageBudget.output(File("."), sink, -1) }.isFailure)
    }
    @Test fun noFileSizeCeilingAndOverflowSafeAdmission() {
        ExportStorageBudget.requireAvailable(File("."), Long.MAX_VALUE - ExportStorageBudget.RESERVE, Long.MAX_VALUE)
        assertTrue(runCatching { ExportStorageBudget.requireAvailable(File("."), Long.MAX_VALUE, Long.MAX_VALUE) }.isFailure)
        assertTrue(runCatching { ExportStorageBudget.requireAvailable(File("."), 0, ExportStorageBudget.RESERVE) }.isFailure)
    }
    @Test fun unknownLengthRequiresOnlyNextChunkPlusReserve() {
        val bytes = ByteArrayOutputStream()
        val stream = ExportStorageBudget.output(File("."), bytes) { ExportStorageBudget.RESERVE + 5 }
        stream.write(ByteArray(5))
        assertTrue(runCatching { stream.write(ByteArray(6)) }.isFailure)
        assertEquals(5, bytes.size())
    }
}
