package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class MobileBuildResourcePolicyTest {
    private fun snapshot(
        cpu: Int = 8,
        available: Long = 6 * GIB,
        lowMemory: Boolean = false,
        powerSave: Boolean = false,
        thermal: Int = 0,
        headroom: Float? = null,
    ) = MobileBuildResourcePolicy.Snapshot(cpu, 8 * GIB, available, lowMemory, powerSave, thermal, headroom)

    @Test fun memoryPressureSerializesBuildsDespitePlentifulCpu() {
        for (sample in listOf(snapshot(cpu = 32, lowMemory = true), snapshot(cpu = 32, available = GIB - 1))) {
            val plan = MobileBuildResourcePolicy.decide(sample)
            assertEquals(MobileBuildResourcePolicy.Pressure.CONSTRAINED, plan.pressure)
            assertEquals(1, plan.maxWorkers)
            assertTrue(plan.daemonIdleMillis <= 2 * 60_000L)
        }
    }

    @Test fun severeHeatOverridesMemoryAndCpuAvailability() {
        for (sample in listOf(snapshot(cpu = 32, thermal = 3), snapshot(cpu = 32, headroom = 1.0f))) {
            val plan = MobileBuildResourcePolicy.decide(sample)
            assertEquals(MobileBuildResourcePolicy.Pressure.CRITICAL, plan.pressure)
            assertEquals(1, plan.maxWorkers)
            assertTrue(plan.daemonIdleMillis <= 60_000L)
        }
    }

    @Test fun batterySavingReducesConcurrencyWithoutDisablingBuilds() {
        for (cpu in listOf(1, 8, 32)) {
            val normal = MobileBuildResourcePolicy.decide(snapshot(cpu = cpu))
            val saving = MobileBuildResourcePolicy.decide(snapshot(cpu = cpu, powerSave = true))
            assertEquals(MobileBuildResourcePolicy.Pressure.WARM, saving.pressure)
            assertTrue(saving.maxWorkers in 1..minOf(2, cpu))
            assertTrue(saving.maxWorkers <= normal.maxWorkers)
            assertTrue(saving.daemonIdleMillis <= normal.daemonIdleMillis)
        }
    }

    @Test fun normalPlansRespectAvailableCpusAndTheMobileWorkerCeiling() {
        val plans = listOf(1, 2, 6, 10, 128).map { cpu ->
            MobileBuildResourcePolicy.decide(snapshot(cpu = cpu)).also { plan ->
                assertEquals(MobileBuildResourcePolicy.Pressure.NORMAL, plan.pressure)
                assertTrue(plan.maxWorkers in 1..minOf(cpu, 4))
            }
        }
        assertTrue(plans.zipWithNext().all { (a, b) -> a.maxWorkers <= b.maxWorkers })
        assertEquals(4, plans.last().maxWorkers)
    }

    @Test fun fallingAvailableMemoryNeverIncreasesConcurrencyOrIdleRetention() {
        val plans = listOf(6 * GIB, 3 * GIB, 2 * GIB, GIB, GIB - 1, 0L).map {
            MobileBuildResourcePolicy.decide(snapshot(cpu = 16, available = it))
        }
        assertTrue(plans.zipWithNext().all { (a, b) -> a.maxWorkers >= b.maxWorkers })
        assertTrue(plans.zipWithNext().all { (a, b) -> a.daemonIdleMillis >= b.daemonIdleMillis })
        assertEquals(1, plans.last().maxWorkers)
    }

    @Test fun unavailableHeadroomAndCpuTelemetryStillProduceBoundedPlans() {
        val normal = MobileBuildResourcePolicy.decide(snapshot())
        for (headroom in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1.0f)) {
            assertEquals(normal, MobileBuildResourcePolicy.decide(snapshot(headroom = headroom)))
        }
        assertEquals(1, MobileBuildResourcePolicy.decide(snapshot(cpu = 0)).maxWorkers)
        assertEquals(1, MobileBuildResourcePolicy.decide(snapshot(cpu = -1, available = -1)).maxWorkers)
    }

    @Test fun everyPressureKeepsBuildCacheWithoutForcingProjectConfigurationCache() {
        for (sample in listOf(snapshot(), snapshot(powerSave = true), snapshot(lowMemory = true), snapshot(thermal = 6))) {
            val plan = MobileBuildResourcePolicy.decide(sample)
            assertTrue(plan.useBuildCache)
            assertFalse(plan.forceConfigurationCache)
            assertTrue(plan.daemonIdleMillis in 30_000L..15 * 60_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MobileBuildResourcePolicy.Plan(MobileBuildResourcePolicy.Pressure.NORMAL, 1, 60_000, true, true)
        }
    }

    companion object {
        private const val GIB = 1024L * 1024L * 1024L
    }
}
