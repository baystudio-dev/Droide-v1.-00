package com.baystudio.droide.core

import android.app.Application
import android.content.Intent
import android.os.Looper
import kotlinx.coroutines.*
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

/** Authored Android host tests; real device notification/wake behavior needs physical validation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DevelopmentSessionExitTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun openSession() {
        ApplicationWorkAdmission.initialize(app)
        DevelopmentForegroundSessionRegistry.closeForExit().getOrThrow()
        val epoch = ApplicationWorkAdmission.reopenFromUser()
        DevelopmentForegroundSessionRegistry.openForEpoch(epoch)
    }
    @After fun releaseTestAdmission() {
        DevelopmentForegroundSessionRegistry.closeForExit().getOrThrow()
        if (!ApplicationWorkAdmission.isOpen) ApplicationWorkAdmission.reopenFromUser()
        DevelopmentForegroundSessionRegistry.openForEpoch(ApplicationWorkAdmission.currentEpoch)
    }
    private fun action(action: String) = Intent().setAction(action)
        .putExtra(DevelopmentForegroundService.EXTRA_EPOCH, ApplicationWorkAdmission.currentEpoch)

    @Test fun idleResidencyDoesNotAcquireCpuAndExitTargetsTheServiceDirectly() {
        val controller = Robolectric.buildService(DevelopmentForegroundService::class.java).create()
        val service = controller.get()
        try {
            assertTrue(DevelopmentForegroundService.ensureSession(app))
            service.onStartCommand(shadowOf(app).nextStartedService, 0, 1)
            assertNull(ShadowPowerManager.getLatestWakeLock())
            val notification = shadowOf(service).lastForegroundNotification
            assertEquals("Acquire WakeLock", notification.actions[0].title)
            val exit = shadowOf(notification.actions[1].actionIntent)
            assertTrue(exit.isService)
            assertEquals(ApplicationWorkAdmission.ACTION_REQUEST_EXIT, exit.savedIntent.action)
            assertEquals(ApplicationWorkAdmission.currentEpoch, exit.savedIntent.getStringExtra(DevelopmentForegroundService.EXTRA_EPOCH))
            assertTrue(exit.savedIntent.data.toString().endsWith("/exit"))
        } finally { controller.destroy() }
    }
    @Test fun staleNotificationExitCannotCloseTheReplacementEpoch() {
        val oldEpoch = ApplicationWorkAdmission.currentEpoch
        val controller = Robolectric.buildService(DevelopmentForegroundService::class.java).create()
        val service = controller.get()
        try {
            ApplicationWorkAdmission.closeForExit()
            val newEpoch = ApplicationWorkAdmission.reopenFromUser()
            service.onStartCommand(Intent().setAction(DevelopmentForegroundService.ACTION_REQUEST_EXIT)
                .putExtra(DevelopmentForegroundService.EXTRA_EPOCH, oldEpoch), 0, 1)
            assertTrue(ApplicationWorkAdmission.isCurrentOpen(newEpoch))
            assertFalse(DevelopmentForegroundService.manualWakeLock.value)
        } finally { controller.destroy() }
    }

    @Test fun releasingManualLeasePreservesAutomaticWorkAndDoesNotCancel() {
        val controller = Robolectric.buildService(DevelopmentForegroundService::class.java).create(); val service = controller.get()
        val token = "owned-cpu-operation-012345"; var canceled = 0
        DevelopmentForegroundSessionRegistry.register(token) { canceled++ }
        try {
            service.onStartCommand(action("com.baystudio.droide.DEV_FGS_START").putExtra("token", token).putExtra("label", "Test"), 0, 1)
            val automatic = ShadowPowerManager.getLatestWakeLock(); assertTrue(automatic.isHeld)
            service.onStartCommand(action(DevelopmentForegroundService.ACTION_ACQUIRE_WAKE_LOCK), 0, 2)
            val manual = ShadowPowerManager.getLatestWakeLock(); assertNotSame(automatic, manual); assertTrue(manual.isHeld)
            service.onStartCommand(action(DevelopmentForegroundService.ACTION_RELEASE_WAKE_LOCK), 0, 3)
            assertFalse(manual.isHeld); assertTrue(automatic.isHeld); assertFalse(DevelopmentForegroundService.manualWakeLock.value)
            assertEquals(0, canceled)
        } finally { controller.destroy() }
    }
    @Test fun exitCancelsUndeliveredRegistrationsAndRejectsLateAcquire() {
        val controller = Robolectric.buildService(DevelopmentForegroundService::class.java).create(); val service = controller.get()
        val oldAcquire = action(DevelopmentForegroundService.ACTION_ACQUIRE_WAKE_LOCK)
        var canceled = 0
        DevelopmentForegroundSessionRegistry.register("queued-operation-0123456789") { canceled++ }
        try {
            ApplicationWorkAdmission.closeForExit()
            runBlocking {
                val stopped = async(Dispatchers.Unconfined) { DevelopmentForegroundService.shutdownForExit(app) }
                shadowOf(Looper.getMainLooper()).idle()
                controller.destroy()
                shadowOf(Looper.getMainLooper()).idle()
                stopped.await()
            }
            assertEquals(1, canceled)
            service.onStartCommand(oldAcquire, 0, 1)
            assertFalse(DevelopmentForegroundService.manualWakeLock.value)
            ApplicationWorkAdmission.reopenFromUser()
            service.onStartCommand(oldAcquire, 0, 2)
            assertFalse(DevelopmentForegroundService.manualWakeLock.value)
            shadowOf(Looper.getMainLooper()).idle()
        } finally { controller.destroy() }
    }
    @Test fun cancellationRequestFailureIsObservableAndOtherOwnersStillStop() {
        var second = 0
        DevelopmentForegroundSessionRegistry.register("failed-operation-0123456789") { error("stop failed") }
        DevelopmentForegroundSessionRegistry.register("second-operation-0123456789") { second++ }
        val result = DevelopmentForegroundSessionRegistry.closeForExit()
        assertTrue(result.isFailure); assertEquals(1, second)
        assertFalse(DevelopmentForegroundSessionRegistry.isRegistered("failed-operation-0123456789"))
    }
    @Test fun closedAdmissionDuringSaveKeepsExistingCpuAndRejectsLateAcquireWithoutCancellation() {
        val controller = Robolectric.buildService(DevelopmentForegroundService::class.java).create()
        val service = controller.get(); var canceled = 0
        val token = "preservation-owner-0123456789"
        DevelopmentForegroundSessionRegistry.register(token) { canceled++ }
        try {
            service.onStartCommand(action("com.baystudio.droide.DEV_FGS_START").putExtra("token",token).putExtra("label","Save pending"),0,1)
            val cpu = ShadowPowerManager.getLatestWakeLock()
            val lateAcquire = action(DevelopmentForegroundService.ACTION_ACQUIRE_WAKE_LOCK)
            ApplicationWorkAdmission.closeForExit()
            service.onStartCommand(lateAcquire,0,2)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DevelopmentCpuWakeLease.RENEW_MS + 1))
            assertEquals(0,canceled)
            assertTrue(cpu.isHeld)
            assertFalse(DevelopmentForegroundService.manualWakeLock.value)
            assertTrue(DevelopmentForegroundSessionRegistry.isRegistered(token))
        } finally { controller.destroy() }
    }

}
