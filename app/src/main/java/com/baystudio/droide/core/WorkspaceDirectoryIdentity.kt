package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes

internal class WorkspaceDirectoryIdentity(private val root: File) {
    private val path = root.canonicalFile
    private val key = identity()

    fun requireCurrent() {
        check(root.canonicalFile == path && identity() == key) {
            "Workspace directory was moved or replaced: ${root.absolutePath}. Reopen the project; existing data was preserved."
        }
    }

    private fun identity(): Any {
        val attributes = try { Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
        catch (failure: java.io.IOException) {
            throw IllegalStateException("Workspace directory is unavailable: ${root.absolutePath}. Restore or reopen it; no replacement was created.", failure)
        }
        check(attributes.isDirectory && !attributes.isSymbolicLink) { "Workspace directory is unsafe: ${root.absolutePath}" }
        return checkNotNull(attributes.fileKey()) { "Workspace directory identity is unavailable" }
    }
}
