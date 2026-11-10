package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Retired runtime entry fence and read-only presence diagnostic. No cleanup is authorized here. */
internal object LegacyAlpineRuntimeRetirement {
    const val UNSUPPORTED_MESSAGE = "Bundled Alpine execution is no longer supported. Existing data and recovery records are preserved; use Ubuntu for current development."
    const val PENDING_MESSAGE = "Legacy Alpine data or recovery records are retained; cleanup remains pending and has not been verified."

    fun reject(): Nothing = throw UnsupportedOperationException(UNSUPPORTED_MESSAGE)

    /** Inspect only path metadata, stopping at the first link, non-directory, or missing ancestor. */

    fun pendingCleanupWarning(appRoot: File): String? {
        val root = appRoot.toPath().toAbsolutePath()
        if (root.nameCount > 64 || root.any { it.toString() == "." || it.toString() == ".." }) return PENDING_MESSAGE
        val legacyPaths = listOf(
            "packages/.guest-cleanup",
            "guest/alpine-3.24.2",
            "guest/.staging/alpine-3.24.2",
            "guest/.previous/alpine-3.24.2",
        )
        return try {
            if (legacyPaths.any { relative ->
                val candidate = root.resolve(relative)
                var cursor = requireNotNull(candidate.root)
                var absent = false
                var evidence = false
                for (part in candidate) {
                    cursor = cursor.resolve(part)
                    val attributes = try {
                        Files.readAttributes(cursor, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    } catch (_: NoSuchFileException) {
                        absent = true
                        break
                    }
                    if (attributes.isSymbolicLink || !attributes.isDirectory) {
                        evidence = true
                        break
                    }
                }
                evidence || !absent
            }) PENDING_MESSAGE else null
        } catch (_: IOException) {
            PENDING_MESSAGE
        } catch (_: SecurityException) {
            PENDING_MESSAGE
        }
    }
}
