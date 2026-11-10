package com.baystudio.droide.core

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrustedArtifactOfflineProgressTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun verifiedCacheAndDirectDownloadReuseNeverNeedDnsOrHttp() = runBlocking {
        val bytes = ByteArray(16 * 1024) { 42 }
        val spec = spec(bytes, "https://offline-cache.invalid/archive.bin")
        val context = context()
        val file = seed(context, spec, bytes)
        val downloader = TrustedArtifactDownloader(context, forbiddenHttpClient())
        assertEquals(file, downloader.cached(spec))
        val events = mutableListOf<TrustedArtifactDownloadProgress>()
        assertEquals(file, downloader.download(spec, events::add))
        assertTrue(events.last().fromCache)
        assertTrue(events.any { it.detail?.contains("Verifying cached archive") == true })
    }

    @Test fun corruptOfflineCacheIsRejectedLocallyAndDiscardAlsoNeedsNoDns() = runBlocking {
        val bytes = ByteArray(16 * 1024) { 42 }
        val spec = spec(bytes, "https://offline-cache.invalid/archive.bin")
        val context = context()
        val file = seed(context, spec, bytes.copyOf().apply { this[0] = 1 })
        val downloader = TrustedArtifactDownloader(context, forbiddenHttpClient())
        assertNull(downloader.cached(spec))
        downloader.discard(spec)
        assertFalse(file.exists())
    }

    @Test fun offlineCacheDoesNotBypassStructureSizeDigestOrSymlinkChecks() = runBlocking {
        val bytes = ByteArray(16 * 1024) { 42 }
        val context = context()
        val original = spec(bytes, "https://offline-cache.invalid/archive.bin")
        val file = seed(context, original, bytes)
        val downloader = TrustedArtifactDownloader(context, forbiddenHttpClient())
        assertNull(downloader.cached(original.copy(expectedBytes = bytes.size - 1L)))
        assertNull(downloader.cached(original.copy(sha256 = "b".repeat(64))))
        for (url in listOf("http://example.com/a", "https://127.0.0.1/a", "https://[::1]/a")) {
            try { downloader.cached(original.copy(url = url)); fail("Unsafe cache descriptor admitted") }
            catch (_: IllegalArgumentException) { }
        }
        val backing = File(context.cacheDir, "backing.bin").apply { writeBytes(bytes) }
        file.delete()
        java.nio.file.Files.createSymbolicLink(file.toPath(), backing.toPath())
        assertNull(downloader.cached(original))
    }

    @Test fun firstSub512KiBBytesAreReportedBeforeTheNextBodyRead() = runBlocking {
        val bytes = ByteArray(32 * 1024) { 42 }
        val events = mutableListOf<TrustedArtifactDownloadProgress>()
        var reads = 0
        val body = streamingBody(bytes.size.toLong()) { sink, _ ->
            when (reads++) {
                0 -> { sink.write(bytes, 0, 4096); 4096L }
                1 -> {
                    assertTrue("First 4 KiB must be visible before another read can stall",
                        events.any { it.bytesDownloaded == 4096L && it.detail == null })
                    sink.write(bytes, 4096, bytes.size - 4096)
                    (bytes.size - 4096).toLong()
                }
                else -> -1L
            }
        }
        val downloader = TrustedArtifactDownloader(context(), client(body))
        downloader.download(spec(bytes), events::add)
        assertTrue(events.any { it.detail?.contains("Preparing", ignoreCase = true) == true })
        assertTrue(events.any { it.detail?.contains("Resolving", ignoreCase = true) == true })
        assertTrue(events.any { it.detail?.contains("Waiting for HTTPS response") == true })
    }

    @Test fun lowVolumeTransferReportsElapsedTimeAndFinalBytesOnReadFailure() = runBlocking {
        val events = mutableListOf<TrustedArtifactDownloadProgress>()
        var reads = 0
        val body = streamingBody(32768L) { sink, _ ->
            when (reads++) {
                0 -> { sink.write(ByteArray(4096)); 4096L }
                1 -> { Thread.sleep(350L); sink.write(ByteArray(4096)); 4096L }
                2 -> {
                    assertTrue("Slow transfer must report before reaching the byte threshold",
                        events.any { it.bytesDownloaded == 8192L && it.detail == null })
                    sink.write(ByteArray(1024)); 1024L
                }
                else -> throw IOException("fixture body stalled")
            }
        }
        val downloader = TrustedArtifactDownloader(context(), client(body))
        try {
            downloader.download(spec(ByteArray(32768)), events::add)
            fail("Expected body failure")
        } catch (expected: IOException) {
            assertEquals("fixture body stalled", expected.message)
        }
        assertEquals("Final partial bytes remain visible after failure", 9216L, events.last().bytesDownloaded)
    }

    @Test fun cacheMissStillPerformsNetworkTargetValidationBeforeHttp() = runBlocking {
        val events = mutableListOf<TrustedArtifactDownloadProgress>()
        val downloader = TrustedArtifactDownloader(context(), forbiddenHttpClient())
        try {
            downloader.download(spec(ByteArray(16), "https://offline-cache.invalid/archive.bin"), events::add)
            fail("An unresolved hostname cannot start an HTTP request")
        } catch (_: java.net.UnknownHostException) { }
        assertTrue(events.any { it.detail?.contains("Resolving", ignoreCase = true) == true })
    }

    @Test fun preparationBodyAndVerificationNotificationsShowTheirActualState() {
        val context = context()
        ApplicationWorkAdmission.initialize(context)
        ApplicationWorkAdmission.reopenFromUser()
        val store = ArtifactTransferStore(temporary.newFolder(), 1, "a".repeat(32))
        val queued = store.ensure(spec(ByteArray(32768)))
        fun notification() = ArtifactTransferNotifications.build(context, checkNotNull(store.read(queued.id)), false)
        fun text() = notification().extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertEquals(queued.detail, text())
        assertTrue(notification().extras.getBoolean(android.app.Notification.EXTRA_PROGRESS_INDETERMINATE))
        val worker = "b".repeat(32)
        store.markRunning(queued.id, queued.requestIdentity, worker)
        assertTrue(text().contains("Preparing"))
        store.markProgress(queued.id, worker, TrustedArtifactDownloadProgress(16384L, 32768L))
        assertEquals("Downloaded 16 KiB / 32 KiB", text())
        assertFalse(notification().extras.getBoolean(android.app.Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertEquals(50, notification().extras.getInt(android.app.Notification.EXTRA_PROGRESS))
        store.markProgress(queued.id, worker, TrustedArtifactDownloadProgress(32768L, 32768L,
            detail = "Verifying downloaded archive 0 / 32 KiB…"))
        assertTrue(text().startsWith("Verifying downloaded archive"))
        assertTrue(notification().extras.getBoolean(android.app.Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test fun redirectStillRejectsPrivateTargetsBeforeIssuingAnotherRequest() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Redirect")
                .header("Location", "https://127.0.0.1/private").body(ByteArray(0).toResponseBody()).build()
        }.build()
        val downloader = TrustedArtifactDownloader(context(), client)
        try {
            downloader.download(spec(ByteArray(16)))
            fail("Private redirect must not be fetched")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Private/local"))
        }
        assertEquals(1, requests)
    }

    @Test fun sha512VerifiedCacheAlsoWorksWithoutDnsOrHttp() = runBlocking {
        val bytes = ByteArray(8192) { 7 }
        val digest = MessageDigest.getInstance("SHA-512").digest(bytes).joinToString("") { "%02x".format(it) }
        val spec = TrustedSha512ArtifactSpec("r43-sha512", "https://offline-cache.invalid/archive.bin",
            digest, "archive.bin", 1024L * 1024L, bytes.size.toLong())
        val context = context()
        val file = File(File(context.cacheDir, "trusted-artifacts").apply { mkdirs() },
            "s512-${digest.take(16)}-${spec.fileName}").apply { writeBytes(bytes) }
        val downloader = TrustedArtifactDownloader(context, forbiddenHttpClient())
        assertEquals(file, downloader.cached(spec))
        val progress = mutableListOf<TrustedArtifactDownloadProgress>()
        assertEquals(file, downloader.download(spec, progress::add))
        assertEquals("Using verified cached archive; no download needed.", progress.last().detail)
        downloader.discard(spec)
        assertFalse(file.exists())
    }

    private fun streamingBody(length: Long, readChunk: (Buffer, Long) -> Long): ResponseBody = object : ResponseBody() {
        private val stream = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = readChunk(sink, byteCount)
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = length
        override fun source(): BufferedSource = stream
    }

    private fun client(body: ResponseBody) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
    }.build()

    private fun forbiddenHttpClient() = OkHttpClient.Builder().addInterceptor {
        throw AssertionError("HTTP must not run for this fixture")
    }.build()

    private fun context(): Context {
        val cache = temporary.newFolder()
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = cache
        }
    }

    private fun spec(bytes: ByteArray, url: String = "https://1.1.1.1/archive.bin") = TrustedArtifactSpec(
        "r43-fixture", url,
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        "archive.bin", 1024L * 1024L, bytes.size.toLong(),
    )

    private fun seed(context: Context, spec: TrustedArtifactSpec, bytes: ByteArray): File =
        File(File(context.cacheDir, "trusted-artifacts").apply { mkdirs() },
            "s256-${spec.sha256.take(16)}-${spec.fileName}").apply { writeBytes(bytes) }
}
