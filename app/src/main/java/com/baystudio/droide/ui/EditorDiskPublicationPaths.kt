package com.baystudio.droide.ui

import com.baystudio.droide.core.WorkspaceRecoveryEditorUnavailableException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Metadata-only NOFOLLOW admission. Never follows a live editor alias to guess authority.
 * Cooperating editor mutations are fenced by the state monitor; this is not filesystem CAS
 * against an arbitrary same-UID writer retargeting a path between metadata check and rename.
 */
internal object EditorDiskPublicationPaths {
    fun normalized(relative: String): String {
        if (relative.startsWith('/') || '\\' in relative || '\u0000' in relative)
            unavailable()
        val parts = relative.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) unavailable()
        return parts.joinToString("/")
    }
    fun requireUnaliased(root: File, relative: String, target: String? = null): String {
        val normalized = normalized(relative)
        var current = root.canonicalFile
        val parts = normalized.split('/')
        var editorLeaf: BasicFileAttributes? = null
        for (index in -1 until parts.size) {
            if (index >= 0) current = File(current, parts[index])
            val attributes = try {
                Files.readAttributes(current.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) { null }
            catch (_: Exception) { unavailable() }
            if (index == parts.lastIndex) editorLeaf = attributes
            if (attributes == null) {
                if (index < 0) unavailable()
            } else if (attributes.isSymbolicLink ||
                (index < parts.lastIndex && !attributes.isDirectory) ||
                (index == parts.lastIndex && !attributes.isRegularFile)) unavailable()
        }
        if (target != null && normalized != normalized(target)) {
            val targetFile = File(root.canonicalFile, normalized(target))
            val targetLeaf = try {
                Files.readAttributes(targetFile.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) { null }
            catch (_: Exception) { unavailable() }
            if (targetLeaf != null && !targetLeaf.isRegularFile) unavailable()
            if (editorLeaf != null && targetLeaf != null) {
                val editorKey = editorLeaf.fileKey() ?: unavailable()
                val targetKey = targetLeaf.fileKey() ?: unavailable()
                if (editorKey.javaClass != targetKey.javaClass || editorKey == targetKey) unavailable()
            }
        }
        return normalized
    }
    private fun unavailable(): Nothing = throw WorkspaceRecoveryEditorUnavailableException(
        "Large disk writes are unavailable while an editor or retained copy uses an ambiguous or symbolic-link path. " +
            "Use the existing editor save path or close that document before retrying.")
}
