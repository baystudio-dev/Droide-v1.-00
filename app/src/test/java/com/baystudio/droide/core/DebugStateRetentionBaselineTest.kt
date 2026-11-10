package com.baystudio.droide.core

import android.app.Application
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DebugStateRetentionBaselineTest {
    @Test fun unreadableStoredStateIsRetainedByPublicLoad() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val id = "retention-${UUID.randomUUID()}"
        val file = File(context.filesDir, ".droide/debug-state/$id.bin")
        file.parentFile.mkdirs()
        val original = "invalid encrypted header but recoverable original evidence".toByteArray()
        file.writeBytes(original)
        try {
            runCatching { DebugStateStore(context, id).load() }
            assertTrue("Loading unreadable state must not delete the stored bytes", file.isFile)
            assertArrayEquals(original, file.readBytes())
        } finally { file.delete() }
    }
}
