package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException

/** Reuse SHA/ELF admission only while every APK-native file retains its filesystem identity. */
internal class PackagedEngineAdmissionCache(
    private val identityProvider: ((List<File>, () -> Unit) -> List<String>?)? = null,
) {
    private data class Admitted(val identity: List<String>, val executable: File)
    private var admitted: Admitted? = null

    fun require(files: List<File>, validate: () -> File): File = require(files, {}, validate)

    @Synchronized fun require(files: List<File>, checkCancelled: () -> Unit, validate: () -> File): File {
        try {
            checkCancelled()
            val before = identity(files, checkCancelled)?.toList()
            if (before != null && admitted?.identity == before) {
                checkCancelled()
                return requireNotNull(admitted).executable
            }
            admitted = null
            val executable = validate() // Failures never become admissions.
            checkCancelled()
            val after = identity(files, checkCancelled)
            if (before != null) check(after == before) { "Packaged Linux engine changed during validation" }
            checkCancelled()
            if (before != null) admitted = Admitted(before, executable)
            return executable
        } catch (failure: Throwable) {
            admitted = null
            throw failure
        }
    }

    @Synchronized fun clear() { admitted = null }

    private fun identity(files: List<File>, checkCancelled: () -> Unit): List<String>? {
        checkCancelled()
        return if (identityProvider == null) fileIdentity(files, checkCancelled)
        else identityProvider.invoke(files, checkCancelled)
    }

    private fun fileIdentity(files: List<File>, checkCancelled: () -> Unit): List<String>? = try {
        files.map { file ->
            checkCancelled()
            val noFollow = LinkOption.NOFOLLOW_LINKS
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, noFollow)
            check(attributes.isRegularFile && !attributes.isSymbolicLink && file.canExecute())
            val key = requireNotNull(attributes.fileKey())
            // ctime also changes when bytes are changed and the old mtime is restored. Unsupported
            // filesystem metadata disables reuse; it never admits an unknown file.
            val changed = requireNotNull(Files.getAttribute(file.toPath(), "unix:ctime", noFollow))
            "${file.absolutePath}:$key:${attributes.size()}:${attributes.lastModifiedTime()}:$changed"
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
}
