package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files

/** The bytes reached the canonical project; only the subsequent synchronization failed. */
class ProjectFileSyncException(val relativePath: String, val bytes: Long, cause: Exception) :
    IOException("File saved locally at $relativePath ($bytes bytes), but synchronization is pending. " +
        "Use the existing project file and refresh; do not repeat the import.", cause)

internal fun interface NewProjectFilePublisher {
    fun publish(staged: File, destination: File)

    companion object {
        // Android SELinux forbids hard links, including inside app-private project storage. The host
        // implementation is only for JVM tooling/tests, never a native-load fallback.
        val platform = NewProjectFilePublisher { staged, destination ->
            if (System.getProperty("java.vm.name") == "Dalvik" ||
                System.getProperty("java.runtime.name") == "Android Runtime") {
                native().publish(staged, destination)
            } else Files.createLink(destination.toPath(), staged.toPath())
        }

        fun native(rename: (ByteArray, ByteArray) -> Int = { source, destination ->
            NativeFilePublication.renameNoReplace(source, destination)
        }) =
            NewProjectFilePublisher { staged, destination ->
                val error = try {
                    rename(staged.absolutePath.toByteArray(Charsets.UTF_8), destination.absolutePath.toByteArray(Charsets.UTF_8))
                } catch (failure: LinkageError) {
                    throw FileSystemException(destination.path, null,
                        "Atomic file publication is unavailable. Repair or update Droide; the original file was preserved.")
                        .also { it.initCause(failure) }
                }
                when (error) {
                    0 -> Unit
                    17 -> throw FileAlreadyExistsException(destination.path, null, "Existing project data was preserved; choose another name")
                    else -> throw FileSystemException(destination.path, null,
                        "Cannot publish project file atomically (errno=$error). The original was preserved; check storage access and free space.")
                }
            }
    }
}

/** Loaded on demand for confined filesystem operations, including interrupted recovery. */
internal object NativeFilePublication {
    init { System.loadLibrary("droide_files") }

    @JvmStatic external fun renameNoReplace(source: ByteArray, destination: ByteArray): Int
    @JvmStatic external fun moveNoReplace(source: ByteArray, destination: ByteArray): Int
    @JvmStatic external fun openDirectoryRoot(path: ByteArray): Int
    @JvmStatic external fun duplicateDescriptor(fd: Int): Int
    @JvmStatic external fun closeDescriptor(fd: Int): Int
    @JvmStatic external fun forceDirectory(fd: Int): Int
    @JvmStatic external fun exportDescriptorLock(fd: Int, acquire: Boolean): Int
    @JvmStatic external fun openOrdinaryRelative(root: Int, relative: ByteArray): Int
    @JvmStatic external fun openDirectoryRelative(root: Int, relative: ByteArray): Int
    @JvmStatic external fun createOrdinaryRelative(root: Int, relative: ByteArray): Long
    @JvmStatic external fun createDirectoryRelative(root: Int, relative: ByteArray): Long
    @JvmStatic external fun kindRelative(root: Int, relative: ByteArray): Int
    @JvmStatic external fun readDescriptor(fd: Int, bytes: ByteArray, offset: Int, length: Int): Int
    @JvmStatic external fun writeDescriptor(fd: Int, bytes: ByteArray, offset: Int, length: Int): Int
    @JvmStatic external fun forceFileDescriptor(fd: Int, mode: Int): Int
    @JvmStatic external fun changedTimeDescriptor(fd: Int): LongArray
    @JvmStatic external fun moveNoReplaceAnchored(sourceRoot: Int, source: ByteArray, targetRoot: Int, target: ByteArray): Int
    @JvmStatic external fun moveNoReplaceAnchoredDurable(sourceRoot: Int, source: ByteArray, targetRoot: Int, target: ByteArray): Long
}

internal fun interface WorkspacePathMove {
    fun move(source: File, destination: File)

    companion object {
        val platform = WorkspacePathMove { source, destination ->
            if (System.getProperty("java.vm.name") == "Dalvik" ||
                System.getProperty("java.runtime.name") == "Android Runtime") {
                native().move(source, destination)
            } else {
                // JVM tooling backend. Actual Android race behavior is checked by the JNI harness.
                Files.move(source.toPath(), destination.toPath())
            }
        }

        fun native(rename: (ByteArray, ByteArray) -> Int = { source, destination ->
            NativeFilePublication.moveNoReplace(source, destination)
        }) = WorkspacePathMove { source, destination ->
            NewProjectFilePublisher.native(rename).publish(source, destination)
        }
    }
}
