package com.baystudio.droide.core

import java.io.File
import java.util.Locale

enum class ExplorerSortOrder { NAME_ASC, NAME_DESC, TYPE, MODIFIED_DESC }

data class DirectoryListingOptions(
    val sortOrder: ExplorerSortOrder = ExplorerSortOrder.NAME_ASC,
    val hideDroideInternal: Boolean = false,
) {
    companion object {
        val EXPLORER = DirectoryListingOptions(hideDroideInternal = true)
    }
}

object ExplorerFilePolicy {
    /** Only Droide's root metadata tree is reserved; project dotfiles remain ordinary files. */
    fun isDroideInternalPath(relative: String): Boolean {
        val path = relative.replace('\\', '/').trim('/')
        return path == ".droide" || path.startsWith(".droide/")
    }

    /** Canonical resolution prevents aliases, dot segments and links escaping the workspace. */
    fun normalizeProjectPath(root: File, relative: String): String {
        val file = PathSecurity.resolveWithin(root, relative.replace('\\', '/'))
        return root.canonicalFile.toPath().relativize(file.toPath()).joinToString("/")
    }

    /** Recoverable user files may be read/moved from internal paths, but never through aliases. */
    fun requireUnaliasedProjectPath(root: File, relative: String): String {
        val path = normalizeProjectPath(root, relative)
        // A canonical in-workspace result alone would accept links to another user folder. User
        // mutations keep their chosen tree position and never traverse such aliases.
        var component = root.canonicalFile
        relative.replace('\\', '/').split('/').forEach { segment ->
            component = when (segment) {
                "", "." -> component
                ".." -> requireNotNull(component.parentFile)
                else -> File(component, segment)
            }
            require(!PathSecurity.isSymbolicLink(component)) { "Choose a project folder without symbolic links" }
        }
        return path
    }

    /** User creation/import/move destinations may never target Droide's own runtime state. */
    fun requireUserTarget(root: File, relative: String): String =
        requireUnaliasedProjectPath(root, relative).also { path ->
            require(!isDroideInternalPath(path)) { "Choose a project folder; .droide contains internal workspace data" }
        }

    internal fun order(options: DirectoryListingOptions): Comparator<FileEntry> {
        fun name(entry: FileEntry): String = entry.path.substringAfterLast('/')
        val nameAscending = compareBy<FileEntry>({ name(it).lowercase(Locale.ROOT) }, { name(it) }, { it.path })
        val contentOrder = when (options.sortOrder) {
            ExplorerSortOrder.NAME_ASC -> nameAscending
            ExplorerSortOrder.NAME_DESC -> nameAscending.reversed()
            ExplorerSortOrder.TYPE -> compareBy<FileEntry> {
                if (it.isDir) "" else name(it).substringAfterLast('.', "").lowercase(Locale.ROOT)
            }.then(nameAscending)
            ExplorerSortOrder.MODIFIED_DESC -> compareByDescending<FileEntry> { it.modified }.then(nameAscending)
        }
        return compareBy<FileEntry> { !it.isDir }.then(contentOrder)
    }
}
