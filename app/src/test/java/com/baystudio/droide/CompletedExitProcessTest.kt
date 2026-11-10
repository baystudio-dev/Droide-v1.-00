package com.baystudio.droide

import android.os.Looper
import android.os.Process
import com.baystudio.droide.core.ApplicationWorkAdmission
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowProcess
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DroideApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CompletedExitProcessTest {
    private fun application(): DroideApplication {
        val app = RuntimeEnvironment.getApplication() as DroideApplication
        ShadowApplication.setProcessName(app.packageName)
        app.applicationInfo.processName = app.packageName
        ShadowProcess.setPid(4242)
        return app
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test fun completedExitClosesAdmissionBeforeTerminatingItsOwnProcess() {
        val app = application()
        val epoch = ApplicationWorkAdmission.reopenFromUser()
        app.requestExit(epoch, Any())
        awaitCondition { ShadowProcess.wasKilled(Process.myPid()) }
        assertTrue(app.exitState.value.toString(), app.exitState.value.completed)
        assertFalse(ApplicationWorkAdmission.isOpen)
        assertTrue(ShadowProcess.wasKilled(4242))
    }

    @Test fun failedCleanupKeepsProcessAliveUntilSuccessfulRetry() {
        val app = application()
        val invalidStorage = File(app.filesDir, "exports-v1").apply { writeText("not a directory") }
        app.requestExit(ApplicationWorkAdmission.reopenFromUser(), Any())
        awaitCondition { app.exitState.value.failure != null }
        assertNotNull(app.exitState.value.failure)
        assertFalse(app.exitState.value.completed)
        assertFalse(ShadowProcess.wasKilled(4242))
        assertTrue(invalidStorage.delete())
        app.retryExit()
        awaitCondition { ShadowProcess.wasKilled(4242) }
        assertTrue(app.exitState.value.toString(), app.exitState.value.completed)
        assertTrue(ShadowProcess.wasKilled(4242))
    }
}
