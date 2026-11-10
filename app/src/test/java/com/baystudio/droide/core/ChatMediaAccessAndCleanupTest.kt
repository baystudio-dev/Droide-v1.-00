package com.baystudio.droide.core

import android.app.Application
import android.app.job.JobScheduler
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.OpenableColumns
import com.baystudio.droide.ui.AgentChatMediaOpener
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.UUID
import java.util.Properties
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatMediaAccessAndCleanupTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val scheduler: JobScheduler get() = app.getSystemService(JobScheduler::class.java)
    private lateinit var work: File
    private lateinit var provider: ChatMediaContentProvider
    @Before fun setup() {
        ApplicationWorkAdmission.initialize(app)
        ApplicationWorkAdmission.reopenFromUser()
        work = File(app.filesDir, "projects/media-${UUID.randomUUID()}").apply { mkdirs() }.canonicalFile
        File(app.filesDir, "chat-media-cleanup").deleteRecursively()
        scheduler.cancelAll()
        provider = ChatMediaContentProvider().also { instance ->
            instance.attachInfo(app, ProviderInfo().apply { authority = ChatMediaAccess.authority(app); exported = false; grantUriPermissions = true })
        }
    }
    @After fun cleanup() { work.deleteRecursively(); File(app.filesDir, "chat-media-cleanup").deleteRecursively(); scheduler.cancelAll() }

    @Test fun opaqueUriProvidesOnlyRegisteredOriginalAndReadOnlyMetadata() = runBlocking {
        val bytes = "owned attachment".toByteArray()
        val ref = ChatMediaStore(work).import("session", "notes.txt", "text/plain", ByteArrayInputStream(bytes))
        val uri = ChatMediaAccess.uri(app, work, ref)
        assertEquals("content", uri.scheme)
        assertFalse(uri.toString().contains("notes.txt"))
        assertFalse(uri.path.orEmpty().contains(".droide"))
        assertFalse(uri.toString().contains(work.absolutePath))
        assertEquals("text/plain", provider.getType(uri))
        provider.query(uri, null, null, null, null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("notes.txt", cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(bytes.size.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")).use { assertArrayEquals(bytes, it.readBytes()) }
        for (mode in listOf("w", "rw", "wa", "rwt")) assertUnavailable { provider.openFile(uri, mode) }
    }

    @Test fun pathTraversalUnknownIdsAndSymbolicLinksNeverGrantProjectFiles() = runBlocking {
        val ref = ChatMediaStore(work).import("session", "notes.txt", "text/plain", ByteArrayInputStream(byteArrayOf(1)))
        val safe = ChatMediaAccess.uri(app, work, ref)
        for (relative in listOf("../outside", "/absolute", "project/../../outside", "project\\outside")) {
            val bad = safe.buildUpon().path("/v1/${ChatMediaAccess.encode(relative)}/${ref.id}").build()
            assertUnavailable { provider.openFile(bad, "r") }
        }
        val unknown = safe.buildUpon().path("/v1/${safe.pathSegments[1]}/m-00000000-0000-0000-0000-000000000000").build()
        assertUnavailable { provider.openFile(unknown, "r") }
        val source = File(work, "private-source.txt").apply { writeText("private source") }
        val original = ChatMediaStore(work).file(ref)!!
        original.delete()
        Files.createSymbolicLink(original.toPath(), source.toPath())
        assertUnavailable { provider.openFile(safe, "r") }
        assertEquals("private source", source.readText())
    }

    @Test fun expiryRevokesProviderAccessBeforePhysicalCleanup() = runBlocking {
        val old = System.currentTimeMillis() - ChatMediaStore.RETENTION_MS - 1_000
        val store = ChatMediaStore(work, clock = { old })
        val ref = store.import("session", "expired.png", "image/png", ByteArrayInputStream(byteArrayOf(1)))
        val uri = Uri.Builder().scheme("content").authority(ChatMediaAccess.authority(app)).appendPath("v1")
            .appendPath(ChatMediaAccess.encode(ChatMediaAccess.relativeWorkspace(app, work))).appendPath(ref.id).build()
        val original = store.file(ref)!!
        assertTrue(original.exists())
        assertUnavailable { provider.openFile(uri, "r") }
        assertNotNull(AgentChatMediaOpener.open(app, work, ref))
        assertNull(shadowOf(app).nextStartedActivity)
        assertNull(ChatMediaCleanup.schedule(app, work))
        assertFalse(original.exists())
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }

    @Test fun userOpenAndShareUseExactContentReadGrantsAndRecoverFromMissingApps() = runBlocking {
        val ref = ChatMediaStore(work).import("session", "photo.jpg", "image/jpeg", ByteArrayInputStream(byteArrayOf(1, 2)))
        assertNull(AgentChatMediaOpener.open(app, work, ref))
        val open = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, open.action)
        assertEquals("content", open.data!!.scheme)
        assertEquals("image/jpeg", open.type)
        assertTrue(open.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, open.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNull(AgentChatMediaOpener.share(app, work, ref))
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(open.data, send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val denied = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException("secret private filename") }
        }
        val notice = AgentChatMediaOpener.open(denied, work, ref)!!
        assertTrue(notice.contains("Share"))
        assertFalse(notice.contains("secret"))
    }

    @Test fun oneShotExpiryJobHasNoNetworkPeriodicOrWakeRequirementsAndAvoidsRepeats() = runBlocking {
        val ref = ChatMediaStore(work).import("session", "notes.txt", "text/plain", ByteArrayInputStream(byteArrayOf(1)))
        assertNull(ChatMediaCleanup.schedule(app, work))
        val job = scheduler.getPendingJob(ChatMediaCleanup.JOB_ID)!!
        assertEquals(ComponentName(app, ChatMediaCleanupJobService::class.java), job.service)
        assertFalse(job.isPeriodic)
        assertFalse(job.isPersisted)
        assertFalse(job.isExpedited)
        assertNull(job.requiredNetwork)
        assertTrue(job.minLatencyMillis > 6L * 24 * 60 * 60 * 1000)
        assertEquals(ref.expiresAt, job.extras.getLong(ChatMediaCleanup.EXTRA_EXPIRY))
        assertNull(ChatMediaCleanup.schedule(app, work))
        assertSame(job, scheduler.getPendingJob(ChatMediaCleanup.JOB_ID))
        val service = app.packageManager.getServiceInfo(ComponentName(app, ChatMediaCleanupJobService::class.java), 0)
        assertFalse(service.exported)
        assertEquals("android.permission.BIND_JOB_SERVICE", service.permission)
        val declaredProvider = app.packageManager.getProviderInfo(ComponentName(app, ChatMediaContentProvider::class.java), 0)
        assertFalse(declaredProvider.exported)
        assertTrue(declaredProvider.grantUriPermissions)
    }

    @Test fun schedulerRejectionIsRecoverableWithoutFileLoss() = runBlocking {
        val store = ChatMediaStore(work)
        val ref = store.import("session", "notes.txt", "text/plain", ByteArrayInputStream(byteArrayOf(1)))
        shadowOf(scheduler).failOnJob(ChatMediaCleanup.JOB_ID)
        assertNotNull(ChatMediaCleanup.schedule(app, work))
        assertNotNull(store.file(ref))
        scheduler.cancelAll()
        assertNotNull(store.file(ref))
        assertNotNull(ChatMediaCleanup.resume(app))
    }

    @Test fun startupResumeRestoresOneShotJobFromRegistryWithoutScanningProjects() = runBlocking {
        val ref = ChatMediaStore(work).import("session", "notes.txt", "text/plain", ByteArrayInputStream(byteArrayOf(1)))
        assertNull(ChatMediaCleanup.schedule(app, work))
        scheduler.cancelAll()
        assertTrue(scheduler.allPendingJobs.isEmpty())
        assertNull(ChatMediaCleanup.resume(app))
        assertEquals(ref.expiresAt, scheduler.getPendingJob(ChatMediaCleanup.JOB_ID)!!.extras.getLong(ChatMediaCleanup.EXTRA_EXPIRY))
        assertEquals(1, scheduler.allPendingJobs.size)
    }

    @Test fun projectRemovalDoesNotOrphanPrivateMediaOrRecreateSourceForCleanup() = runBlocking {
        val store = ChatMediaStore(work)
        val ref = store.import("session", "notes.txt", "text/plain", ByteArrayInputStream(byteArrayOf(1)))
        val original = store.file(ref)!!
        assertFalse(PathSecurity.contains(work, original))
        assertFalse(File(work, ".droide/chat-media").exists())
        assertNull(ChatMediaCleanup.schedule(app, work))
        work.deleteRecursively()
        val registry = File(app.filesDir, "chat-media-cleanup/workspaces.properties")
        val properties = Properties().apply { registry.inputStream().use { load(it) } }
        properties.stringPropertyNames().forEach { key -> properties.setProperty(key, "0|" + properties.getProperty(key).substringAfter('|')) }
        registry.outputStream().use { properties.store(it, "Test: this workspace is due for a metadata check") }
        assertNull(ChatMediaCleanup.cleanup(app))
        assertFalse("Cleanup must not recreate source", work.exists())
        assertTrue(original.exists())
        assertEquals(ref.expiresAt, scheduler.getPendingJob(ChatMediaCleanup.JOB_ID)!!.extras.getLong(ChatMediaCleanup.EXTRA_EXPIRY))
        store.cleanupExpired(ref.expiresAt)
        assertFalse(original.exists())
        assertFalse(work.exists())
    }

    private fun assertUnavailable(action: () -> Any) {
        try { action(); fail("Unsafe media access succeeded") } catch (_: FileNotFoundException) { }
    }
}
