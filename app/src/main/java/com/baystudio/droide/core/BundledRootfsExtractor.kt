package com.baystudio.droide.core
import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.*
import org.tukaani.xz.XZInputStream

 
object BundledRootfsExtractor {
    data class Image(
        val file: String, val bytes: Long, val sha256: String, val tarBytes: Long, val tarSha256: String,
        val hardLinks: Map<String, String> = emptyMap(),
    )
    val ubuntu = Image("ubuntu-base-24.04.5-base-arm64.tar.xz", 17_880_788,
        "33f099c82ff503ddae6ff669bb274165d70a7602de84cfd34cb0e30ce4b766ce", 106_741_760,
        "d0cebb41bb205a8e4e2afc60987dca30619a4fb2c6ec4a1a23a42839254a2ba6",
        hardLinks = mapOf("usr/bin/perl5.38.2" to "usr/bin/perl", "usr/bin/uncompress" to "usr/bin/gunzip"))
    suspend fun extract(context: Context, image: Image, destination: File) = withContext(Dispatchers.IO) {
        require(image == ubuntu) { "Only the bundled Ubuntu rootfs is supported" }
        extractVerified(destination, image, image.bytes, image.sha256,
            open = { context.assets.open("workstation/${image.file}") },
            decode = { XZInputStream(it, 32768) })
    }

    internal suspend fun extractDownloadedUbuntu(
        archive: File, destination: File, progress: (EnvironmentSetupPhase) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        extractVerified(destination, ubuntu, FoundryUbuntuBaseManifest.ROOTFS_BYTES, FoundryUbuntuBaseManifest.ROOTFS_SHA256,
            open = { archive.inputStream() }, decode = { GZIPInputStream(it, 65536) }, progress = progress)
    }

    private suspend fun extractVerified(
        destination: File, image: Image, compressedBytes: Long, compressedSha256: String,
        open: () -> InputStream, decode: (InputStream) -> InputStream,
        progress: (EnvironmentSetupPhase) -> Unit = {},
    ) {
        LocalExecutionSubstrate.requireSafeLocalPath(destination.absolutePath)
        check(destination.isDirectory && destination.list()?.isEmpty() == true && !PathSecurity.isSymbolicLink(destination)) {
            "Rootfs extraction requires an empty private staging directory"
        }
        val task = currentCoroutineContext()
        fun digest(decoded: Boolean): Pair<Long, String> {
            val md = MessageDigest.getInstance("SHA-256"); var count = 0L
            open().use { raw ->
                (if (decoded) decode(raw) else raw).use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        task.ensureActive(); val n = input.read(buffer); if (n < 0) break
                        count += n; check(count <= if (decoded) image.tarBytes else compressedBytes) { "Ubuntu archive exceeds its expected size" }
                        md.update(buffer, 0, n)
                    }
                }
            }
            return count to md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        progress(EnvironmentSetupPhase.VERIFYING)
        check(digest(false) == (compressedBytes to compressedSha256)) { "Ubuntu archive integrity failure" }
        check(digest(true) == (image.tarBytes to image.tarSha256)) { "Ubuntu TAR integrity failure" }
        progress(EnvironmentSetupPhase.EXTRACTING)
        
        val result = LocalProcessSupervisor.capture(
            listOf("/system/bin/toybox", "tar", "-xf", "-", "-C", destination.absolutePath), destination,
            maxOutputBytes = 65536, timeoutMs = 180_000,
            input = { output ->
                open().use { raw -> decode(raw).use { input ->
                    RootfsTarCompatibility.copy(input, output, image.hardLinks, checkCancelled = { task.ensureActive() })
                } }
            },
        )
        check(result.exitCode == 0 && !result.timedOut) { "Rootfs extraction failed: ${result.output.takeLast(4000)}" }
    }
}
