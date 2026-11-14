package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Inspect the shared host filesystem before launching any recovery process. */
internal object UbuntuGuestRecoveryInspection {
    data class BootstrapState(val previousExists: Boolean, val stageExists: Boolean, val finalExists: Boolean)

    fun bootstrap(previous: File, stage: File, current: File): BootstrapState {
        // NOFOLLOW_LINKS on a leaf still traverses its parents. Reject redirected cleanup/restore paths.
        requireDirectParent(current)
        requireDirectParent(stage)
        val currentExists = guestDirectoryExists(current)
        val stageExists = guestDirectoryExists(stage)
        // An unused backup cannot block a current guest; the reconciler never restores it over that guest.
        val previousExists = if (currentExists) attributes(previous) != null else {
            requireDirectParent(previous)
            guestDirectoryExists(previous)
        }
        return BootstrapState(previousExists, stageExists, currentExists)
    }

    private fun requireDirectParent(path: File) {
        val parent = path.parentFile
        check(parent != null && attributes(parent)?.isSymbolicLink != true) {
            "Unsafe Ubuntu bootstrap directory; retained data was not changed: $parent"
        }
    }

    private fun guestDirectoryExists(path: File): Boolean {
        val entry = attributes(path) ?: return false
        check(entry.isDirectory && !entry.isSymbolicLink) {
            "Unexpected Ubuntu bootstrap entry; retained data was not changed: $path"
        }
        return true
    }

    suspend fun recoverLegacyCompactionIfPresent(launcher: File, transaction: File, recover: suspend () -> Unit) {
        val launch = attributes(launcher) ?: return
        check(!launch.isSymbolicLink) { "Unsafe Ubuntu recovery launcher" }
        if (!launch.isRegularFile) return
        val pending = attributes(transaction) ?: return
        check(pending.isDirectory && !pending.isSymbolicLink) { "Unsafe Ubuntu compaction transaction" }
        recover()
    }

    private fun attributes(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        // Only proven absence skips recovery. IO/permission errors propagate to the caller.
        null
    }
}
