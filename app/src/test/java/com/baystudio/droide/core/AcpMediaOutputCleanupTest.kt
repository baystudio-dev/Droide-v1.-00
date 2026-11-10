package com.baystudio.droide.core

import android.app.Application
import android.app.job.JobScheduler
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AcpMediaOutputCleanupTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val scheduler: JobScheduler get() = app.getSystemService(JobScheduler::class.java)
    private lateinit var root: File
    private lateinit var store: ChatMediaStore
    private lateinit var mapper: WorkspacePathMapper
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aYd0AAAAASUVORK5CYII=")
    private val io = object : AgentWorkspaceIo {
        override val checksumCommand = "sha256sum"
        override fun requirePath(path: String) = Unit
        override suspend fun refresh() = error("Cleanup enrollment must not refresh the execution workspace")
        override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult = error("No shell or package setup")
        override suspend fun pull(path: String, output: File) = error("Media uses bounded original streams")
        override suspend fun push(input: File, path: String) = error("No project mutation")
        override suspend fun openMedia(path: String) = ByteArrayInputStream(byteArrayOf(0x50, 0x4b, 3, 4, 7, 8))
    }

    @Before fun setup() {
        ApplicationWorkAdmission.initialize(app)
        ApplicationWorkAdmission.reopenFromUser()
        root = File(app.filesDir, "projects/acp-expiry-${UUID.randomUUID()}").apply { mkdirs() }.canonicalFile
        store = ChatMediaStore(root)
        mapper = WorkspacePathMapper(root, "/workspace")
        PathSecurity.deleteTreeNoFollow(File(app.filesDir, "chat-media-cleanup"))
        scheduler.cancelAll()
    }
    @After fun cleanup() {
        PathSecurity.deleteTreeNoFollow(ChatMediaStoragePaths.directory(root))
        PathSecurity.deleteTreeNoFollow(root)
        PathSecurity.deleteTreeNoFollow(File(app.filesDir, "chat-media-cleanup"))
        scheduler.cancelAll()
    }

    @Test fun firstActualCliOutputEnrollsFreshWorkspaceOnceForImageAndFileWithoutTouchingProjectOrDependencies() = runBlocking {
        val source = File(root, "source.kt").apply { writeText("user source") }
        val dependency = File(root, "node_modules/pkg/native.node").apply { parentFile.mkdirs(); writeText("installed dependency") }
        var schedules = 0
        val retention = AcpMediaOutputRetention {
            schedules++
            assertNull(ChatMediaCleanup.schedule(app, root))
        }
        assertNull(scheduler.getPendingJob(ChatMediaCleanup.JOB_ID))
        val image = AcpMediaOutputImporter.read(image(), store, "s-cli", mapper, io,
            onImported = retention::imported, authorizeRead = { true })!!
        val firstJob = scheduler.getPendingJob(ChatMediaCleanup.JOB_ID)!!
        val archive = AcpMediaOutputImporter.read(resource("archive.zip"), store, "s-cli", mapper, io,
            onImported = retention::imported, authorizeRead = { true })!!
        assertEquals(1, schedules)
        assertEquals(firstJob.extras.getLong(ChatMediaCleanup.EXTRA_EXPIRY), scheduler.getPendingJob(ChatMediaCleanup.JOB_ID)!!.extras.getLong(ChatMediaCleanup.EXTRA_EXPIRY))
        assertFalse(firstJob.isPeriodic)
        assertEquals(1, scheduler.allPendingJobs.count { it.id == ChatMediaCleanup.JOB_ID })
        assertArrayEquals(png, store.file(image)!!.readBytes())
        assertArrayEquals(byteArrayOf(0x50, 0x4b, 3, 4, 7, 8), store.file(archive)!!.readBytes())
        assertEquals(setOf(image.id, archive.id), store.ownerRefs("s-cli").map { it.id }.toSet())
        assertEquals("user source", source.readText())
        assertEquals("installed dependency", dependency.readText())
        assertFalse(File(root, "archive.zip").exists())
    }

    @Test fun invalidUnknownDeniedOrForeignOutputDoesNotEnrollAPhantomMediaOwner() = runBlocking {
        var schedules = 0
        val retention = AcpMediaOutputRetention { schedules++; ChatMediaCleanup.schedule(app, root); Unit }
        assertTrue(runCatching {
            AcpMediaOutputImporter.read(buildJsonObject { put("type", "image"); put("mimeType", "image/png"); put("data", "not valid base64") },
                store, "s-cli", mapper, io, onImported = retention::imported, authorizeRead = { true })
        }.isFailure)
        val unknown = buildJsonObject { put("type", "resource_link"); put("uri", "https://example.test/unknown.png") }
        assertNull(AcpMediaOutputImporter.read(unknown, store, "s-cli", mapper, io,
            onImported = retention::imported, authorizeRead = { true }))
        assertNull(AcpMediaOutputImporter.read(resource("private.zip"), store, "s-cli", mapper, io,
            onImported = retention::imported, authorizeRead = { false }))
        val foreign = store.import("another-conversation", "foreign.png", "image/png", ByteArrayInputStream(png))
        assertNull(AcpMediaOutputImporter.read(resource(foreign.path), store, "s-cli", mapper, io,
            onImported = retention::imported, authorizeRead = { true }))
        assertEquals(0, schedules)
        assertNull(scheduler.getPendingJob(ChatMediaCleanup.JOB_ID))
        assertTrue(store.ownerRefs("s-cli").isEmpty())
        assertArrayEquals(png, store.file(foreign)!!.readBytes())
    }

    private fun image() = buildJsonObject { put("type", "image"); put("mimeType", "image/png"); put("data", Base64.getEncoder().encodeToString(png)) }
    private fun resource(relative: String) = buildJsonObject { put("type", "resource_link"); put("uri", mapper.remoteUriForRelative(relative)); put("mimeType", "application/zip"); put("name", "archive.zip") }
}
