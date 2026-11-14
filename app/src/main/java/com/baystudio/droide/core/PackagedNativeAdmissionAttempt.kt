package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Verified path values, not open descriptors or a lease on future pathname execution. */
internal data class PackagedNativeArtifacts(
    val launcher: File,
    val loader: File,
    val loader32: File,
    val cli: File,
) {
    val loaderEnvironment: Map<String, String> get() = mapOf(
        "PROOT_LOADER" to loader.absolutePath,
        "PROOT_LOADER_32" to loader32.absolutePath,
    )
}

/** One preparation owns this metadata-only cache; no Job or completed startup callback is retained. */
internal class PackagedNativeAdmissionAttempt internal constructor(
    namedFiles: List<File>,
    private val cache: PackagedEngineAdmissionCache,
    private val validateEngine: (() -> Unit) -> File,
    private val validateCli: (() -> Unit) -> File,
) : AutoCloseable {
    private val files = namedFiles.toList()
    private var closed = false

    init {
        require(files.map { it.name } == listOf(
            PackagedLinuxEngine.LIBRARY_NAME, "libproot-loader.so", "libproot-loader32.so", DroideCliClientInstaller.NATIVE_FILE_NAME,
        )) { "Complete native admission requires all four fixed APK-native artifacts" }
        require(files.all { it.isAbsolute }) { "Native artifact paths must be absolute" }
        val root = requireNotNull(files.first().parentFile).canonicalFile
        require(files.all { it.isAbsolute && it.parentFile == root }) { "Native artifacts must share one canonical APK-native directory" }
    }

    @Synchronized fun require(checkCancelled: () -> Unit): PackagedNativeArtifacts {
        check(!closed) { "Native admission attempt is closed" }
        try {
            checkCancelled()
            requireNamedFiles(checkCancelled)
            cache.require(files, checkCancelled) {
                // Never use PackagedLinuxEngine.requireReady's process-global cache here.
                val engine = validateEngine(checkCancelled)
                check(engine == files[0]) { "Engine verification returned another native path" }
                checkCancelled()
                val cli = validateCli(checkCancelled)
                check(cli == files[3]) { "CLI verification returned another native path" }
                requireNamedFiles(checkCancelled)
                checkCancelled()
                engine // A cache entry is published only after the complete four-file validation.
            }
            checkCancelled()
            check(!closed) { "Native admission attempt closed during verification" }
            return PackagedNativeArtifacts(files[0], files[1], files[2], files[3])
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    private fun requireNamedFiles(checkCancelled: () -> Unit) {
        files.forEach { file ->
            checkCancelled()
            // Inspect the named children before canonicalization can hide a symlink alias.
            check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(file.toPath()) && file.canExecute()) {
                "Packaged native artifact is missing, aliased or non-executable: ${file.name}"
            }
        }
    }

    @Synchronized override fun close() {
        closed = true
        cache.clear()
    }

    companion object {
        fun create(context: Context, checkCancelled: () -> Unit): PackagedNativeAdmissionAttempt {
            checkCancelled()
            val app = context.applicationContext
            val files = PackagedLinuxEngine.nativeFiles(app, checkCancelled) +
                DroideCliClientInstaller.nativeFile(app, checkCancelled)
            checkCancelled()
            return PackagedNativeAdmissionAttempt(
                files, PackagedEngineAdmissionCache(),
                validateEngine = { checkpoint -> PackagedLinuxEngine.requireFresh(app, checkpoint) },
                validateCli = { checkpoint -> DroideCliClientInstaller.ensureInstalled(app, checkpoint) },
            )
        }
    }
}
