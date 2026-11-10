package com.baystudio.droide.core

import android.app.Application
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidProjectReadinessTest {
    @Test fun missingWrapperIsRecognizedButNeverReady() = withProject { root, manager ->
        val status = manager.refresh()
        assertTrue(status.androidProject)
        assertEquals(AndroidDevelopmentManager.Readiness.CHECK_FAILED, status.readiness)
        assertTrue(status.message.contains("gradlew"))
        assertEquals(36, status.compileSdk)
    }

    @Test fun incompleteWrapperReportsMissingProperties() = withProject { root, manager ->
        root.resolve("gradlew").writeText("#!/bin/sh\n")
        val status = manager.refresh()
        assertTrue(status.androidProject)
        assertEquals(AndroidDevelopmentManager.Readiness.CHECK_FAILED, status.readiness)
        assertTrue(status.message.contains("gradle-wrapper.properties"))
    }

    @Test fun unsafeWrapperIsNotMarkedReadyOrExecuted() = withProject { root, manager ->
        val outside = Files.createTempFile("outside-gradlew-", ".sh")
        try {
            assumeTrue(runCatching {
                Files.createSymbolicLink(root.resolve("gradlew").toPath(), outside)
            }.isSuccess)
            val status = manager.refresh()
            assertTrue(status.androidProject)
            assertEquals(AndroidDevelopmentManager.Readiness.CHECK_FAILED, status.readiness)
            assertTrue(status.message.contains("gradlew"))
            assertTrue(status.message.contains("unsafe"))
        } finally { Files.deleteIfExists(outside) }
    }

    private fun withProject(block: suspend (java.io.File, AndroidDevelopmentManager) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("android-readiness-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val previousPid = android.os.Process.myPid()
        val app = RuntimeEnvironment.getApplication()
        val journal = java.io.File(app.filesDir, "operation-journal/" + AndroidDevelopmentManager.stableProjectId(root))
        var ownsJournal = false
        var bridge: DeviceBridgeManager? = null
        try {
            ShadowProcess.setPid(4242)
            assertFalse("Fixture journal must be new", journal.exists())
            ownsJournal = true
            root.resolve("build.gradle.kts").writeText("android { compileSdk = 36 }")
            val fixtureBridge = DeviceBridgeManager(app, scope, DeviceBridgeDependencies(
                configureIdentity = {},
                create = { error("No bridge transport expected") },
                pair = { _, _, _ -> error("No pairing expected") },
                discovery = { error("No discovery expected") },
                resetIdentity = {},
            ))
            bridge = fixtureBridge
            block(root, AndroidDevelopmentManager(app, root, fixtureBridge))
        } finally {
            try {
                bridge?.close()
            } finally {
                try {
                    scope.cancel()
                    PathSecurity.deleteTreeNoFollow(root)
                    if (ownsJournal) PathSecurity.deleteTreeNoFollow(journal)
                } finally {
                    ShadowProcess.setPid(previousPid)
                }
            }
        }
    }
}
