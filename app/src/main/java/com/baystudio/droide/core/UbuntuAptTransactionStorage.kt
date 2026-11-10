package com.baystudio.droide.core

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Serializes Droide processes and removes only the installer's private archive transactions. */
internal class UbuntuAptTransactionStorage(private val guestRoot: File) {
    private val cacheRoot get() = File(guestRoot, "rootfs/var/cache/droide-foundry")

    suspend fun <T> operation(progress: (String) -> Unit, block: suspend () -> T): T {
        val lockFile = File(guestRoot, ".droide-apt-install.lock")
        safe(lockFile)
        FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val started = System.nanoTime()
            var announced = false
            while (true) {
                currentCoroutineContext().ensureActive()
                val lock = tryLock(channel)
                if (lock != null) return lock.use { block() }
                if (!announced) { progress("Waiting for another Droide dependency installation…\n"); announced = true }
                if ((System.nanoTime() - started) / 1_000_000L >= 180_000L) throw UbuntuPackageManagerBusyException("Another Droide dependency installation is still running; retry when it finishes")
                delay(250)
            }
        }
    }

    /** Native dpkg/APT locks are acquired briefly for cleanup, never removed or bypassed. */
    fun pruneAbandoned(): Int {
        if (!cacheRoot.isDirectory) return 0
        safe(cacheRoot)
        val channels = mutableListOf<FileChannel>(); val locks = mutableListOf<FileLock>()
        try {
            for (path in listOf("var/lib/dpkg/lock-frontend", "var/lib/dpkg/lock", "var/lib/apt/lists/lock", "var/cache/apt/archives/lock")) {
                val file = File(guestRoot, "rootfs/$path")
                if (!file.exists()) continue
                safe(file)
                val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)
                channels += channel
                val lock = tryLock(channel) ?: return 0
                locks += lock
            }
            var removed = 0
            for (directory in cacheRoot.listFiles().orEmpty().take(512)) {
                if (directory.name.matches(Regex("[0-9a-f]{24}")) && directory.isDirectory &&
                    !Files.isSymbolicLink(directory.toPath()) && clean(directory)) removed++
            }
            return removed
        } finally { locks.asReversed().forEach { it.close() }; channels.asReversed().forEach { it.close() } }
    }

    fun cleanup(layerId: String) {
        require(layerId.matches(Regex("[0-9a-f]{24}")))
        val directory = File(cacheRoot, layerId)
        if (!directory.exists()) return
        safe(directory)
        // A surviving native APT process may still own the archive lock after an app restart. Leave its
        // cache for the next idle cleanup.
        clean(directory)
    }

    private fun clean(directory: File): Boolean {
        safe(directory)
        val cacheLock = File(directory, "archives/lock")
        if (!cacheLock.exists()) return PathSecurity.deleteTreeNoFollow(directory)
        safe(cacheLock)
        FileChannel.open(cacheLock.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val lock = tryLock(channel) ?: return false
            return lock.use { PathSecurity.deleteTreeNoFollow(directory) }
        }
    }

    private fun safe(file: File) {
        val base = guestRoot.absoluteFile.toPath().normalize()
        val path = file.absoluteFile.toPath().normalize()
        require(path.startsWith(base)) { "Installer cache escaped the guest" }
        var parent: File? = file.absoluteFile
        while (parent != null && parent.toPath().startsWith(base)) {
            require(!Files.isSymbolicLink(parent.toPath())) { "Unsafe installer cache path" }
            if (parent.toPath() == base) break
            parent = parent.parentFile
        }
    }

    private fun tryLock(channel: FileChannel): FileLock? = try { channel.tryLock() }
        catch (_: OverlappingFileLockException) { null }
}
