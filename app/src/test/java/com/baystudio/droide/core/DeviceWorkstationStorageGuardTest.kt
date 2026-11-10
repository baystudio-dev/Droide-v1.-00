package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class DeviceWorkstationStorageGuardTest {
    @Test fun dfKilobytesBecomeBytesAndFreeSpaceIsNotTotalCapacity() {
        val report = "Filesystem 1K-blocks Used Available Use% Mounted on\n" +
            "/dev/block/data 134217728 133169152 1048576 99% /data\n"
        val space = DeviceWorkstationStorageGuard.parseDf(report)
        assertEquals(128 * GIB, space.totalBytes)
        assertEquals(GIB, space.availableBytes)
        assertTrue(space.availableBytes < space.totalBytes)
    }

    @Test fun missingTruncatedAndNonNumericReportsCannotBecomeFreeSpace() {
        val invalid = listOf("", "Filesystem 1K-blocks Used Available Use% Mounted on", "/dev/data 100 20",
            "/dev/data 100 broken 80 20% /data", "/dev/data 9223372036854775808 0 0 0% /data")
        for (report in invalid) {
            val failure = runCatching { DeviceWorkstationStorageGuard.parseDf(report) }.exceptionOrNull()
            assertTrue("Malformed df report was accepted: $report",
                failure is IllegalArgumentException || failure is IllegalStateException)
        }
    }

    @Test fun inconsistentAndNegativeStorageCountersAreRejected() {
        for (report in listOf("/dev/data -1 0 0 0% /data", "/dev/data 100 -1 20 0% /data",
            "/dev/data 100 10 -1 0% /data", "/dev/data 100 101 0 0% /data", "/dev/data 100 60 41 0% /data")) {
            assertThrows(IllegalArgumentException::class.java) { DeviceWorkstationStorageGuard.parseDf(report) }
        }
    }

    @Test fun oversizedDfCountersCannotOverflowIntoAnAvailableBudget() {
        assertThrows(ArithmeticException::class.java) {
            DeviceWorkstationStorageGuard.parseDf("/dev/data ${Long.MAX_VALUE} 0 0 0% /data")
        }
    }

    @Test fun operationAndReserveBothCountAtTheExactAdmissionBoundary() {
        val operation = 2 * MIB
        val reserve = DeviceWorkstationStorageGuard.EMERGENCY_RESERVE_BYTES
        val available = operation + reserve
        assertEquals(available, DeviceWorkstationStorageGuard.checkHeadroom(operation, "install package", reserve, available))
        val failure = assertThrows(IllegalStateException::class.java) {
            DeviceWorkstationStorageGuard.checkHeadroom(operation, "install package", reserve, available - 1)
        }
        assertTrue(failure.message.orEmpty().contains("Not enough storage"))
        assertTrue(failure.message.orEmpty().contains("install package"))
        assertTrue(failure.message.orEmpty().contains("safety reserve"))
    }

    @Test fun zeroDownloadDoesNotWaiveEmergencyStorageReserve() {
        val reserve = DeviceWorkstationStorageGuard.EMERGENCY_RESERVE_BYTES
        assertEquals(reserve, DeviceWorkstationStorageGuard.checkHeadroom(0, "finish setup", reserve, reserve))
        assertThrows(IllegalStateException::class.java) {
            DeviceWorkstationStorageGuard.checkHeadroom(0, "finish setup", reserve, reserve - 1)
        }
    }

    @Test fun invalidOrOverflowingBudgetsFailBeforeSpaceCanBeAdmitted() {
        for ((operation, reserve, purpose) in listOf(Triple(-1L, 0L, "install"), Triple(0L, -1L, "install"),
            Triple(0L, 0L, " "), Triple(0L, 0L, "x".repeat(161)))) {
            assertThrows(IllegalArgumentException::class.java) {
                DeviceWorkstationStorageGuard.checkHeadroom(operation, purpose, reserve, Long.MAX_VALUE)
            }
        }
        assertThrows(ArithmeticException::class.java) {
            DeviceWorkstationStorageGuard.checkHeadroom(Long.MAX_VALUE, "install", 1, Long.MAX_VALUE)
        }
    }

    companion object {
        private const val MIB = 1024L * 1024L
        private const val GIB = 1024L * MIB
    }
}
