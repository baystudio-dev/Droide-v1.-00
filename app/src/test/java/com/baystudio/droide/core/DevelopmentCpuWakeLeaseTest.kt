package com.baystudio.droide.core

import android.os.Looper
import android.os.PowerManager
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DevelopmentCpuWakeLeaseTest {
    @Test fun activeWorkRenewsOneBoundedLeaseAndReleasePreventsLateReacquisition() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(PowerManager::class.java)
        val lease = DevelopmentCpuWakeLease(manager)
        lease.hold()
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(lock.isHeld)
        assertFalse(shadowOf(lock).isReferenceCounted)
        lease.hold()
        assertEquals(1, shadowOf(lock).timesHeld)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DevelopmentCpuWakeLease.RENEW_MS))
        assertTrue(lock.isHeld)
        assertEquals(2, shadowOf(lock).timesHeld)
        lease.release()
        lease.release()
        assertFalse(lock.isHeld)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(3))
        assertFalse(lock.isHeld)
        assertEquals(2, shadowOf(lock).timesHeld)
    }

    @Test fun idleAgentRegistrationDoesNotRequestCpuAndCompletedOwnerCannotReturn() {
        val token = "idle-agent-0123456789"
        var cancellations = 0
        DevelopmentForegroundSessionRegistry.registerWithCpuPolicy(token, false) { cancellations++ }
        assertFalse(DevelopmentForegroundSessionRegistry.cpuWork(token))
        DevelopmentForegroundSessionRegistry.setCpuWork(token, true)
        assertTrue(DevelopmentForegroundSessionRegistry.cpuWork(token))
        DevelopmentForegroundSessionRegistry.remove(token)
        DevelopmentForegroundSessionRegistry.setCpuWork(token, true)
        assertFalse(DevelopmentForegroundSessionRegistry.isRegistered(token))
        assertFalse(DevelopmentForegroundSessionRegistry.cpuWork(token))
        assertEquals(0, cancellations)
    }
}
