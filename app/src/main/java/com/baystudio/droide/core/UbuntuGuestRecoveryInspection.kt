package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Inspect the shared host filesystem before launching any recovery process. */
internal object UbuntuGuestRecoveryInspection {
    data class BootstrapState(val previousExists: Boolean, val stageExists: Boolean, val finalExists: Boolean)

    fun bootstrap(previous: File, stage: File, current: File): BootstrapState = BootstrapState(
        attributes(previous) != null, attributes(stage) != null, attributes(current) != null,
    )

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
