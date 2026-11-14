package com.baystudio.droide.core

import com.baystudio.droide.BuildConfig

import android.content.Context
import android.os.Build
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

// A downloaded executable in filesDir is never a substitute.
object PackagedLinuxEngine {
    const val LIBRARY_NAME = "libdroide_proot.so"
    private const val LIBRARY_SHA256 = "6a7babd8480adea03afba45a3cabd89a1387040a0cdc205a90f37185cb4f882b"
    private const val LOADER_NAME = "libproot-loader.so"
    private const val LOADER_SHA256 = "44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04"
    private const val LOADER32_NAME = "libproot-loader32.so"
    private const val LOADER32_SHA256 = "25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34"
    private val admissions = PackagedEngineAdmissionCache()

    fun requireReady(context: Context): File = requireReady(context, {})

    internal fun requireReady(context: Context, checkCancelled: () -> Unit): File {
        val files = nativeFiles(context, checkCancelled)
        val launcher = checkedLauncher(files)
        return admissions.require(listOf(launcher) + files.drop(1), checkCancelled) {
            verifyFresh(files, checkCancelled)
        }
    }

    /** Named children remain noncanonical so an attempt's no-follow check sees aliases. */
    internal fun nativeFiles(context: Context, checkCancelled: () -> Unit): List<File> {
        checkCancelled()
        check(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" && android.os.Process.is64Bit()) {
            "Local Linux currently needs an ARM64 Android process"
        }
        val nativeDirPath = context.applicationContext.applicationInfo.nativeLibraryDir
        check(!nativeDirPath.isNullOrBlank()) { "Android did not provide a native-library directory" }
        val nativeDir = File(nativeDirPath).canonicalFile
        checkCancelled()
        return listOf(LIBRARY_NAME, LOADER_NAME, LOADER32_NAME).map { File(nativeDir, it) }
    }

    /** Complete-attempt admission must never consult the process-global engine cache. */
    internal fun requireFresh(context: Context, checkCancelled: () -> Unit): File =
        verifyFresh(nativeFiles(context, checkCancelled), checkCancelled)

    private fun checkedLauncher(files: List<File>): File {
        val nativeDir = files.first().parentFile
        val launcher = files.first().canonicalFile
        check(launcher.parentFile == nativeDir && launcher.isFile && launcher.canExecute()) {
            "Droide Linux engine is absent from APK native libraries; install a build with a certified ARM64 engine"
        }
        return launcher
    }

    private fun verifyFresh(files: List<File>, checkCancelled: () -> Unit): File {
        checkCancelled()
        val launcher = checkedLauncher(files)
        SystemLinkerExec.requireAndroidArm64DynamicElf(launcher)
        checkCancelled()
        check(sha256(launcher, checkCancelled) == if (BuildConfig.FDROID_NATIVE_SOURCE) BuildConfig.SOURCE_PROOT_SHA256 else LIBRARY_SHA256) {
            "Packaged PRoot does not match the reviewed release"
        }
        loader(launcher.parentFile!!, LOADER_NAME,
            if (BuildConfig.FDROID_NATIVE_SOURCE) BuildConfig.SOURCE_PROOT_LOADER_SHA256 else LOADER_SHA256, 2, checkCancelled)
        loader(launcher.parentFile!!, LOADER32_NAME,
            if (BuildConfig.FDROID_NATIVE_SOURCE) BuildConfig.SOURCE_PROOT_LOADER32_SHA256 else LOADER32_SHA256, 1, checkCancelled)
        checkCancelled()
        return launcher
    }

    fun loaderEnvironment(context: Context): Map<String, String> {
        val nativeDir = requireReady(context).parentFile!!
        return mapOf(
            "PROOT_LOADER" to File(nativeDir, LOADER_NAME).absolutePath,
            "PROOT_LOADER_32" to File(nativeDir, LOADER32_NAME).absolutePath,
        )
    }

    private fun loader(nativeDir: File, name: String, expectedSha: String, elfClass: Int, checkCancelled: () -> Unit) {
        checkCancelled()
        val file = File(nativeDir, name).canonicalFile
        check(file.parentFile == nativeDir && file.isFile && file.canExecute()) { "Packaged PRoot loader is absent: $name" }
        DataInputStream(file.inputStream()).use { stream ->
            val header = ByteArray(20)
            stream.readFully(header)
            check(header[0] == 0x7f.toByte() &&
                header[1] == 69.toByte() && header[2] == 76.toByte() && header[3] == 70.toByte() &&
                header[4] == elfClass.toByte() && header[5] == 1.toByte() &&
                header[18] == (if (elfClass == 2) 183.toByte() else 40.toByte()) && header[19] == 0.toByte()) {
                "Packaged PRoot loader has an unsupported ELF ABI: $name"
            }
        }
        checkCancelled()
        check(sha256(file, checkCancelled) == expectedSha) { "Packaged PRoot loader does not match the reviewed release: $name" }
    }

    internal fun sha256(file: File, checkCancelled: () -> Unit): String {
        checkCancelled()
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val block = ByteArray(64 * 1024)
            while (true) {
                checkCancelled()
                val n = input.read(block)
                if (n < 0) break
                digest.update(block, 0, n)
                checkCancelled()
            }
        }
        checkCancelled()
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun unavailableReason(context: Context): String? = unavailableReason(context, {})

    internal fun unavailableReason(context: Context, checkCancelled: () -> Unit): String? = try {
        requireReady(context, checkCancelled)
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // Fatal Error is deliberately not optional-resource absence.
        failure.message ?: failure.javaClass.simpleName
    }
}
