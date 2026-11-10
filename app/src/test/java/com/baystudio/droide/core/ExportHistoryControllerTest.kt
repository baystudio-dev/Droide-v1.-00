package com.baystudio.droide.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ExportHistoryControllerTest {
    @Test fun healthyHistoryBypassesCorruptCurrentWithoutPublishingOrRecoveringJob() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        org.robolectric.shadows.ShadowApplication.setProcessName(context.packageName)
        context.applicationInfo.processName = context.packageName
        val root = File(context.filesDir, "exports-v1")
        root.deleteRecursively()
        try {
            val store = ExportJournal(root)
            store.write(ExportJobState("healthy", "epoch", ExportJobPhase.COMPLETED_VERIFIED))
            File(root, "current.properties").writeText("version=broken\n")
            val before = ExportController.state.value
            val snapshot = ExportController.historySnapshot(context)
            assertEquals(listOf("healthy"), snapshot.jobs.map { it.id })
            assertTrue(snapshot.currentUnreadable)
            assertEquals(before, ExportController.state.value)
            ExportController.initialize(context)
            assertEquals(listOf("healthy"), ExportController.history(context).map { it.id })
            assertFalse(File(root, "current.properties").exists())
            assertEquals("version=broken\n", root.listFiles()!!.single { it.name.startsWith("recovery-") }.resolve("current.properties").readText())
            assertEquals(before, ExportController.state.value)
        } finally {
            ExportController::class.java.getDeclaredField("pointerAuthority").apply { isAccessible = true }.let {
                (it.get(ExportController) as? ExportPointerRecovery)?.close(); it.set(ExportController, null)
            }
            ExportController::class.java.getDeclaredField("journal").apply { isAccessible = true }.set(ExportController, null)
            root.deleteRecursively()
        }
    }
}
