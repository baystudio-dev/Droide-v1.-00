package com.baystudio.droide.core

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
class TrustedArtifactDownloadProgressTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun verificationHasProgressAndRepeatedUseReadsCacheWithoutAnotherHttpRequest() = runBlocking {
        val bytes = ByteArray(5 * 1024 * 1024) { (it % 239).toByte() }
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(bytes.toResponseBody()).build()
        }.build()
        val downloader = TrustedArtifactDownloader(context(), client)
        val spec = spec(bytes)
        val progress = mutableListOf<TrustedArtifactDownloadProgress>()
        val file = downloader.download(spec, progress::add)
        assertEquals(1, requests)
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(progress.any { it.detail?.contains("Verifying downloaded archive 0") == true })
        assertTrue(progress.any { it.detail?.contains("5120 / 5120") == true })
        assertEquals(file, downloader.download(spec, progress::add))
        assertEquals(1, requests)
        assertTrue(progress.last().fromCache)
        assertTrue(progress.any { it.detail?.contains("Verifying cached archive") == true })
        assertTrue(progress.last().describe().contains("no download"))
        // Keeping size and timestamp cannot defeat a fresh cryptographic check.
        val time = file.lastModified()
        file.writeBytes(bytes.copyOf().apply { this[0] = 8 }); file.setLastModified(time)
        assertNull(downloader.cached(spec))
        assertEquals(file, downloader.download(spec))
        assertEquals(2, requests)
    }

    @Test fun stoppingDuringHashingPreservesACompletePartialForNextVerifiedResume() = runBlocking {
        val bytes = ByteArray(1024 * 1024) { 42 }
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(bytes.toResponseBody()).build()
        }.build()
        val downloader = TrustedArtifactDownloader(context(), client)
        val spec = spec(bytes)
        try {
            downloader.download(spec) { if (it.detail?.startsWith("Verifying downloaded archive") == true) throw CancellationException("stop hash") }
            fail("Cancellation ignored")
        } catch (expected: CancellationException) { assertEquals("stop hash", expected.message) }
        assertArrayEquals(bytes, downloader.download(spec).readBytes())
        assertEquals("Complete partial must be verified without downloading again", 1, requests)
    }

    private fun context(): Context {
        val cache = temporary.newFolder()
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = cache
        }
    }
    private fun spec(bytes: ByteArray) = TrustedArtifactSpec("progress-fixture", "https://1.1.1.1/fixture.tar.gz",
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        "fixture.tar.gz", 8L * 1024 * 1024, bytes.size.toLong())
}
