package com.baystudio.droide.ui

import android.os.FileObserver
import com.baystudio.droide.core.ExplorerFilePolicy
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

internal class AndroidWorkspaceDocumentWatch(private val root: File) : WorkspaceDocumentWatchFactory {
    internal data class Target(val directory: File, val child: String, val watchingAncestor: Boolean)

    override fun open(path: String, invalidate: (Boolean) -> Unit): AutoCloseable? {
        val target = target(root, path) ?: return null
        @Suppress("DEPRECATION")
        val observer = object : FileObserver(target.directory.absolutePath, EVENTS) {
            override fun onEvent(event: Int, child: String?) {
                if (event and EVENTS == 0) return
                val selfChanged = event and (DELETE_SELF or MOVE_SELF) != 0
                if (!selfChanged && child != target.child) return
                try { invalidate(selfChanged || target.watchingAncestor) }
                catch (_: Exception) { /* Never escape the Android observer callback thread. */ }
            }
        }
        try { observer.startWatching() }
        catch (failure: Exception) {
            observer.stopWatching()
            throw failure
        }
        return AutoCloseable { observer.stopWatching() }
    }

    internal companion object {
        private const val EVENTS = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
            FileObserver.MOVED_FROM or FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.ATTRIB or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF

        fun target(root: File, path: String): Target? {
            require(path.isNotBlank() && path.length <= 4_096) { "Invalid active document watch path" }
            require(!Files.isSymbolicLink(root.toPath())) { "Workspace root cannot be a symbolic link" }
            require(ExplorerFilePolicy.requireUnaliasedProjectPath(root, path) == path) {
                "Active document watch path must be unaliased"
            }
            val base = root.canonicalFile
            if (!Files.isDirectory(base.toPath(), NOFOLLOW_LINKS)) return null
            var child = File(base, path)
            val intendedParent = child.parentFile ?: return null
            var directory = intendedParent
            while (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) {
                if (directory == base) return null
                child = directory
                directory = directory.parentFile ?: return null
            }
            require(directory.toPath().startsWith(base.toPath())) { "Active document watch escaped workspace" }
            return Target(directory, child.name, directory != intendedParent)
        }
    }
}
