package com.baystudio.droide.core

import android.content.Intent
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DevelopmentForegroundLifecycleTest {
    @Before fun openApplicationSession() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        DevelopmentForegroundSessionRegistry.closeForExit().getOrThrow()
        DevelopmentForegroundSessionRegistry.openForEpoch(ApplicationWorkAdmission.reopenFromUser())
    }
    @Test fun removingTheUiTaskDoesNotCancelWorkAndTheNotificationStopStillDoes() {
        val controller = Robolectric.buildService(DevelopmentForegroundService::class.java).create()
        val service = controller.get()
        val token = "background-agent-0123456789"
        var cancellations = 0
        DevelopmentForegroundSessionRegistry.register(token) { cancellations++ }
        try {
            service.onStartCommand(start(token), 0, 1)
            service.onTaskRemoved(Intent())
            assertEquals(0, cancellations)
            assertTrue(DevelopmentForegroundSessionRegistry.isRegistered(token))
            service.onStartCommand(Intent().setAction("com.baystudio.droide.DEV_FGS_STOP").putExtra("token", token).putExtra(DevelopmentForegroundService.EXTRA_EPOCH, ApplicationWorkAdmission.currentEpoch), 0, 2)
            assertEquals(1, cancellations)
            assertFalse(DevelopmentForegroundSessionRegistry.isRegistered(token))
        } finally { controller.destroy() }
        assertEquals(1, cancellations)
    }

    @Test fun onlyCpuOwnersKeepLeaseAndFinishNeverCancelsIdleAgentOwner() {
        val controller = Robolectric.buildService(DevelopmentForegroundService::class.java).create()
        val service = controller.get()
        val context = RuntimeEnvironment.getApplication()
        val cpuToken = "cpu-operation-0123456789"
        val idleToken = "idle-operation-0123456789"
        var cpuCanceled = 0
        var idleCanceled = 0
        DevelopmentForegroundSessionRegistry.register(cpuToken) { cpuCanceled++ }
        DevelopmentForegroundSessionRegistry.registerWithCpuPolicy(idleToken, false) { idleCanceled++ }
        try {
            service.onStartCommand(start(cpuToken), 0, 1)
            val lock = ShadowPowerManager.getLatestWakeLock()
            assertTrue(lock.isHeld)
            service.onStartCommand(start(idleToken), 0, 2)
            DevelopmentForegroundService.finish(context, DevelopmentForegroundService.Handle(cpuToken))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(lock.isHeld)
            assertTrue(DevelopmentForegroundSessionRegistry.isRegistered(idleToken))
            assertEquals(0, cpuCanceled)
            assertEquals(0, idleCanceled)
            DevelopmentForegroundService.finish(context, DevelopmentForegroundService.Handle(idleToken))
            shadowOf(Looper.getMainLooper()).idle()
        } finally { controller.destroy() }
        assertEquals(0, idleCanceled)
    }

    @Test fun stopActionCancelsOnlyNamedOperationAndRetainsOtherActiveOwner() {
        val controller = Robolectric.buildService(DevelopmentForegroundService::class.java).create()
        val service = controller.get()
        val first = "first-operation-0123456789"
        val second = "second-operation-0123456789"
        var firstCanceled = 0
        var secondCanceled = 0
        DevelopmentForegroundSessionRegistry.register(first) { firstCanceled++ }
        DevelopmentForegroundSessionRegistry.register(second) { secondCanceled++ }
        try {
            service.onStartCommand(start(first), 0, 1)
            service.onStartCommand(start(second), 0, 2)
            service.onStartCommand(Intent().setAction("com.baystudio.droide.DEV_FGS_STOP").putExtra("token", first).putExtra(DevelopmentForegroundService.EXTRA_EPOCH, ApplicationWorkAdmission.currentEpoch), 0, 3)
            assertEquals(1, firstCanceled)
            assertEquals(0, secondCanceled)
            assertFalse(DevelopmentForegroundSessionRegistry.isRegistered(first))
            assertTrue(DevelopmentForegroundSessionRegistry.isRegistered(second))
            assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        } finally { controller.destroy() }
        assertEquals(1, firstCanceled)
        assertEquals(1, secondCanceled)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    private fun start(token: String) = Intent().setAction("com.baystudio.droide.DEV_FGS_START")
        .putExtra("token", token).putExtra("label", "Fixture").putExtra(DevelopmentForegroundService.EXTRA_EPOCH, ApplicationWorkAdmission.currentEpoch)
}
