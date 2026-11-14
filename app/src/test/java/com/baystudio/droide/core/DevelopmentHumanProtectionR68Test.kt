package com.baystudio.droide.core

import android.app.Application
import android.content.Intent
import android.os.Looper
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.util.ReflectionHelpers

/**
 * Authored, unrun API 34 host integration tests for R68 human-command protection.
 * These exercise service dispatch and shadow wake locks, not device/OEM background guarantees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DevelopmentHumanProtectionR68Test {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun openSession() {
        ApplicationWorkAdmission.initialize(app)
        DevelopmentForegroundSessionRegistry.closeForExit().getOrThrow()
        DevelopmentForegroundSessionRegistry.openForEpoch(ApplicationWorkAdmission.reopenFromUser())
    }

    @After fun releaseTestAdmission() {
        DevelopmentForegroundSessionRegistry.closeForExit().getOrThrow()
        if (!ApplicationWorkAdmission.isOpen) ApplicationWorkAdmission.reopenFromUser()
        DevelopmentForegroundSessionRegistry.openForEpoch(ApplicationWorkAdmission.currentEpoch)
    }

    @Test fun humanRegistrationRemainsPendingUntilForegroundPromotionAndCpuAcquisition() {
        val controller = Robolectric.buildService(DevelopmentForegroundService::class.java).create()
        val service = controller.get()
        val human = Owner()
        var promotedWhenAcknowledged = false
        var heldWhenAcknowledged = false
        try {
            val handle = startHuman(human) {
                promotedWhenAcknowledged = shadowOf(service).lastForegroundNotification != null
                heldWhenAcknowledged = ShadowPowerManager.getLatestWakeLock()?.isHeld == true
            }
            val start = nextStart()
            assertTrue(DevelopmentForegroundSessionRegistry.isRegistered(handle.token))
            assertTrue(DevelopmentForegroundSessionRegistry.cpuWork(handle.token))
            assertEquals(0, human.acquired)
            assertNull(shadowOf(service).lastForegroundNotification)
            assertNull(ShadowPowerManager.getLatestWakeLock())

            service.onStartCommand(start, 0, 1)

            assertEquals(1, human.acquired)
            assertTrue(promotedWhenAcknowledged)
            assertTrue(heldWhenAcknowledged)
            assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertNoLossOrCancellation(human)

            // Android may redeliver a request; acknowledgement belongs to the registration.
            service.onStartCommand(Intent(start), 0, 2)
            assertEquals(1, human.acquired)
            assertNoLossOrCancellation(human)
        } finally { controller.destroy() }
    }

    @Test fun finishingBeforeDelayedStartPreventsAcquisitionAndAcknowledgement() {
        val human = Owner()
        val handle = startHuman(human)
        val delayedStart = nextStart()
        DevelopmentForegroundService.finish(app, handle)
        assertFalse(DevelopmentForegroundSessionRegistry.isRegistered(handle.token))

        val controller = Robolectric.buildService(DevelopmentForegroundService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(delayedStart, 0, 1)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(0, human.acquired)
            assertNoLossOrCancellation(human)
            assertFalse(DevelopmentForegroundSessionRegistry.isRegistered(handle.token))
            assertNull(ShadowPowerManager.getLatestWakeLock())
            assertNull(shadowOf(service).lastForegroundNotification)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally { controller.destroy() }
        assertNoLossOrCancellation(human)
    }

    @Test fun unexpectedDestructionReportsHumanProtectionLossButCancelsManagedOwner() {
        val controller = Robolectric.buildService(DevelopmentForegroundService::class.java).create()
        val service = controller.get()
        val human = Owner()
        var managedCanceled = 0
        var destroyed = false
        try {
            val humanHandle = startHuman(human)
            service.onStartCommand(nextStart(), 0, 1)
            val managedHandle = checkNotNull(DevelopmentForegroundService.start(app, "Managed fixture") {
                managedCanceled++
            })
            service.onStartCommand(nextStart(), 0, 2)
            val automatic = ShadowPowerManager.getLatestWakeLock()
            assertTrue(automatic.isHeld)

            controller.destroy()
            destroyed = true

            assertEquals(1, human.acquired)
            assertEquals(0, human.canceled)
            assertEquals(listOf("Automatic CPU protection became unavailable"), human.losses)
            assertEquals(1, managedCanceled)
            assertFalse(DevelopmentForegroundSessionRegistry.isRegistered(humanHandle.token))
            assertFalse(DevelopmentForegroundSessionRegistry.isRegistered(managedHandle.token))
            assertFalse(automatic.isHeld)
            val acquisitions = shadowOf(automatic).timesHeld
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DevelopmentCpuWakeLease.LEASE_MS + 1))
            assertEquals(acquisitions, shadowOf(automatic).timesHeld)
            assertFalse(automatic.isHeld)
            assertEquals(1, human.losses.size)
            assertEquals(1, managedCanceled)
        } finally { if (!destroyed) controller.destroy() }
    }

    @Test fun explicitStopCancelsHumanOwnerInsteadOfReportingProtectionLoss() {
        val controller = Robolectric.buildService(DevelopmentForegroundService::class.java).create()
        val service = controller.get()
        val human = Owner()
        try {
            val handle = startHuman(human)
            service.onStartCommand(nextStart(), 0, 1)
            val automatic = ShadowPowerManager.getLatestWakeLock()
            val stop = action(ACTION_STOP).putExtra("token", handle.token)

            service.onStartCommand(stop, 0, 2)
            service.onStartCommand(Intent(stop), 0, 3)

            assertEquals(1, human.canceled)
            assertTrue(human.losses.isEmpty())
            assertFalse(DevelopmentForegroundSessionRegistry.isRegistered(handle.token))
            assertFalse(automatic.isHeld)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally { controller.destroy() }
        assertEquals(1, human.canceled)
        assertTrue(human.losses.isEmpty())
    }

    @Test fun preservationFenceRetainsAndRenewsEstablishedCpuWithoutAdmittingLateAcquire() {
        val controller = Robolectric.buildService(DevelopmentForegroundService::class.java).create()
        val service = controller.get()
        val human = Owner()
        try {
            val handle = startHuman(human)
            service.onStartCommand(nextStart(), 0, 1)
            val automatic = ShadowPowerManager.getLatestWakeLock()
            val acquisitions = shadowOf(automatic).timesHeld
            val lateAcquire = action(DevelopmentForegroundService.ACTION_ACQUIRE_WAKE_LOCK)

            ApplicationWorkAdmission.closeForExit()
            service.onStartCommand(lateAcquire, 0, 2)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DevelopmentCpuWakeLease.RENEW_MS + 1))

            assertFalse(ApplicationWorkAdmission.isOpen)
            assertTrue(automatic.isHeld)
            assertSame(automatic, ShadowPowerManager.getLatestWakeLock())
            assertTrue(shadowOf(automatic).timesHeld > acquisitions)
            assertFalse(DevelopmentForegroundService.manualWakeLock.value)
            assertTrue(DevelopmentForegroundSessionRegistry.isRegistered(handle.token))
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertNotNull(shadowOf(service).lastForegroundNotification)
            assertEquals(1, human.acquired)
            assertNoLossOrCancellation(human)
        } finally { controller.destroy() }
    }

    @Test fun manualReleaseLeavesHumanAutomaticOwnerAndItsCpuLeaseIntact() {
        val controller = Robolectric.buildService(DevelopmentForegroundService::class.java).create()
        val service = controller.get()
        val human = Owner()
        try {
            val handle = startHuman(human)
            service.onStartCommand(nextStart(), 0, 1)
            val automatic = ShadowPowerManager.getLatestWakeLock()
            service.onStartCommand(action(DevelopmentForegroundService.ACTION_ACQUIRE_WAKE_LOCK), 0, 2)
            val manual = ShadowPowerManager.getLatestWakeLock()
            assertNotSame(automatic, manual)
            assertTrue(automatic.isHeld)
            assertTrue(manual.isHeld)
            assertTrue(DevelopmentForegroundService.manualWakeLock.value)

            service.onStartCommand(action(DevelopmentForegroundService.ACTION_RELEASE_WAKE_LOCK), 0, 3)

            assertFalse(manual.isHeld)
            assertFalse(DevelopmentForegroundService.manualWakeLock.value)
            assertTrue(automatic.isHeld)
            assertTrue(DevelopmentForegroundSessionRegistry.isRegistered(handle.token))
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertEquals(1, human.acquired)
            assertNoLossOrCancellation(human)
        } finally { controller.destroy() }
    }

    @Test fun acquiredObserverCanRetireExactTokenWithoutRevivingItOrRetiringNewOwner() {
        val controller = Robolectric.buildService(DevelopmentForegroundService::class.java).create()
        val service = controller.get()
        val retired = Owner()
        val newer = Owner()
        lateinit var retiredHandle: DevelopmentForegroundService.Handle
        try {
            retiredHandle = startHuman(retired) {
                DevelopmentForegroundService.finish(app, retiredHandle)
            }
            val retiredStart = nextStart()
            val newerHandle = startHuman(newer)
            val newerStart = nextStart()
            assertNotEquals(retiredHandle.token, newerHandle.token)

            // A newer command is delivered first; the older acquire observer retires only itself.
            service.onStartCommand(newerStart, 0, 1)
            val automatic = ShadowPowerManager.getLatestWakeLock()
            service.onStartCommand(retiredStart, 0, 2)
            shadowOf(Looper.getMainLooper()).idle() // Drain the old token's posted finish.
            service.onStartCommand(Intent(retiredStart), 0, 3) // Stale delivery cannot revive it.

            assertEquals(1, retired.acquired)
            assertEquals(1, newer.acquired)
            assertFalse(DevelopmentForegroundSessionRegistry.isRegistered(retiredHandle.token))
            assertTrue(DevelopmentForegroundSessionRegistry.isRegistered(newerHandle.token))
            assertTrue(automatic.isHeld)
            assertSame(automatic, ShadowPowerManager.getLatestWakeLock())
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertNoLossOrCancellation(retired)
            assertNoLossOrCancellation(newer)
        } finally { controller.destroy() }
        assertNoLossOrCancellation(retired)
    }

    @Test fun injectedRenewalOwnershipFailureWarnsHumanAndCancelsManagedWithoutReacquisition() {
        val controller = Robolectric.buildService(DevelopmentForegroundService::class.java).create()
        val service = controller.get()
        val human = Owner()
        var managedCanceled = 0
        val epoch = ApplicationWorkAdmission.currentEpoch
        try {
            val humanHandle = startHuman(human)
            service.onStartCommand(nextStart(), 0, 1)
            val managedHandle = checkNotNull(DevelopmentForegroundService.start(app, "Managed renewal fixture") {
                managedCanceled++
            })
            service.onStartCommand(nextStart(), 0, 2)
            val automatic = ShadowPowerManager.getLatestWakeLock()
            val acquisitions = shadowOf(automatic).timesHeld

            // Controlled fault at the actual renewable lease admission boundary. This does not
            // simulate a platform PowerManager permission denial or establish device behavior.
            ReflectionHelpers.setField(service, "serviceEpoch", "injected-retired-service-epoch")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DevelopmentCpuWakeLease.RENEW_MS + 1))

            assertEquals(0, human.canceled)
            assertEquals(listOf("Automatic CPU protection became unavailable"), human.losses)
            assertEquals(1, managedCanceled)
            assertFalse(DevelopmentForegroundSessionRegistry.isRegistered(humanHandle.token))
            assertFalse(DevelopmentForegroundSessionRegistry.isRegistered(managedHandle.token))
            assertFalse(automatic.isHeld)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals(acquisitions, shadowOf(automatic).timesHeld)

            ReflectionHelpers.setField(service, "serviceEpoch", epoch)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DevelopmentCpuWakeLease.LEASE_MS + 1))
            assertFalse(automatic.isHeld)
            assertEquals(acquisitions, shadowOf(automatic).timesHeld)
            assertEquals(1, human.losses.size)
            assertEquals(1, managedCanceled)
        } finally {
            ReflectionHelpers.setField(service, "serviceEpoch", epoch)
            controller.destroy()
        }
        assertEquals(0, human.canceled)
        assertEquals(1, human.losses.size)
        assertEquals(1, managedCanceled)
    }

    private class Owner {
        var acquired = 0
        var canceled = 0
        val losses = mutableListOf<String>()
    }

    private fun startHuman(owner: Owner, acquired: () -> Unit = {}): DevelopmentForegroundService.Handle =
        checkNotNull(DevelopmentForegroundService.startHumanCommand(app,
            onAcquired = { owner.acquired++; acquired() },
            onProtectionLost = { owner.losses += it },
            cancel = { owner.canceled++ },
        ))

    private fun nextStart(): Intent = checkNotNull(shadowOf(app).nextStartedService).also {
        assertEquals(ACTION_START, it.action)
        assertEquals(ApplicationWorkAdmission.currentEpoch, it.getStringExtra(DevelopmentForegroundService.EXTRA_EPOCH))
    }

    private fun action(value: String): Intent = Intent().setAction(value)
        .putExtra(DevelopmentForegroundService.EXTRA_EPOCH, ApplicationWorkAdmission.currentEpoch)

    private fun assertNoLossOrCancellation(owner: Owner) {
        assertEquals(0, owner.canceled)
        assertTrue(owner.losses.isEmpty())
    }

    private companion object {
        const val ACTION_START = "com.baystudio.droide.DEV_FGS_START"
        const val ACTION_STOP = "com.baystudio.droide.DEV_FGS_STOP"
    }
}
