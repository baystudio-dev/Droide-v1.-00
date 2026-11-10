package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Observed mode evidence only. This is not a filesystem CAS or exclusion of external writers. */
internal class DefaultCatalogModeGuard(private val app: File) {
    class Blocked internal constructor(cause: Throwable) : IOException(
        "Default project catalog mode cannot be verified. Update-selection evidence requires review; " +
            "no automatic mode switch or fallback was performed. Restart only after this evidence has been reviewed.", cause)

    private var blocked: Blocked? = null
    private var appIdentity: WorkspaceDirectoryIdentity? = null
    private var storageIdentity: WorkspaceDirectoryIdentity? = null

    @Synchronized fun requireDefaultMode() {
        blocked?.let { throw it }
        try {
            val appIdentity = this.appIdentity ?: WorkspaceDirectoryIdentity(app).also { this.appIdentity = it }
            appIdentity.requireCurrent()
            val storage = File(app, "catalog-startup")
            val attributes = attributesOrAbsent(storage)
            if (attributes != null) {
                check(attributes.isDirectory && !attributes.isSymbolicLink) { "Catalog startup storage is unsafe" }
                val storageIdentity = this.storageIdentity ?: WorkspaceDirectoryIdentity(storage).also { this.storageIdentity = it }
                storageIdentity.requireCurrent()
                for (name in UPDATE_EVIDENCE) {
                    check(attributesOrAbsent(File(storage, name)) == null) {
                        "Catalog update-selection evidence is present: $name"
                    }
                }
                storageIdentity.requireCurrent()
            } else check(storageIdentity == null) { "Catalog startup storage disappeared" }
            appIdentity.requireCurrent()
        } catch (failure: Exception) {
            throw Blocked(failure).also { blocked = it }
        }
    }

    private fun attributesOrAbsent(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }

    private companion object {
        // Initial registration artifacts are intentionally NOT alternative-mode evidence.
        val UPDATE_EVIDENCE = listOf("catalog-updates", "catalog-update-membership.json",
            ".catalog-update-membership.json.pending", "startup-admission.json", ".startup-admission.json.pending")
    }
}
