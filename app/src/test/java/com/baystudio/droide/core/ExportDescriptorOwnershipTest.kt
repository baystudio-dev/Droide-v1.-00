package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Ownership seam tests; Android Os/descriptor behavior still requires device QA. */
class ExportDescriptorOwnershipTest {
    @Test fun borrowedStreamAndOwnedDescriptorAreBothClosed() {
        val events = mutableListOf<String>()
        val result = withExportOwnedDescriptor(
            openInput = {
                object : ByteArrayInputStream(byteArrayOf(42)) {
                    override fun close() { events += "stream" }
                }
            },
            closeDescriptor = { events += "descriptor" },
        ) { it.read() }
        assertEquals(42, result)
        assertEquals(listOf("stream", "descriptor"), events)
    }

    @Test fun constructionFailureStillClosesOwnedDescriptor() {
        val failure = IOException("open stream failed")
        var closed = 0
        try {
            withExportOwnedDescriptor(
                openInput = { throw failure },
                closeDescriptor = { closed++ },
            ) { it.read() }
            fail("Expected failure")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }
        assertEquals(1, closed)
    }

    @Test fun descriptorCloseDoesNotMaskReadFailure() {
        val failure = IOException("read failed")
        val closeFailure = IOException("descriptor close failed")
        try {
            withExportOwnedDescriptor(
                openInput = { ByteArrayInputStream(byteArrayOf()) },
                closeDescriptor = { throw closeFailure },
            ) { throw failure }
            fail("Expected failure")
        } catch (actual: IOException) {
            assertSame(failure, actual)
            assertArrayEquals(arrayOf(closeFailure), actual.suppressed)
        }
    }

    @Test fun descriptorCloseFailurePreventsSuccess() {
        val closeFailure = IOException("descriptor close failed")
        try {
            withExportOwnedDescriptor(
                openInput = { ByteArrayInputStream(byteArrayOf()) },
                closeDescriptor = { throw closeFailure },
            ) { Unit }
            fail("Expected failure")
        } catch (actual: IOException) {
            assertSame(closeFailure, actual)
        }
    }
}
