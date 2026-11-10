package com.baystudio.droide.core

import java.io.File
import java.nio.file.*
import kotlinx.coroutines.*

// A missing catalog entry is not proof that a workspace or backup may be deleted.
internal object ProjectWorkspaceMaintenance {
    suspend fun prepare(projectsRoot: File, registered: Map<String, File>): List<File> = withContext(Dispatchers.IO) {
        val root = projectsRoot.canonicalFile
        check(root.isDirectory || root.mkdirs()) { "Project storage is unavailable" }
        val targets = registered.mapNotNull { (id, file) ->
            runCatching { id to ProjectWorkspacePolicy.requireDirectChild(root, file) }.getOrNull()
        }.toMap()
        Files.newDirectoryStream(root.toPath()).use { entries ->
            for (path in entries) {
                currentCoroutineContext().ensureActive()
                if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue
                val file = path.toFile()
                when {
                    file.name.startsWith(".delete-") -> {
                        val target = targets[file.name.removePrefix(".delete-")]
                        check(target == null) {
                            "Legacy workspace deletion evidence has no recorded identity: ${file.path}. " +
                                "Restore or review both locations manually; no tree was moved."
                        }
                        // Duplicate and unregistered trees remain available for recovery.
                    }
                }
            }
        }
        emptyList()
    }

    // A caller-supplied path, reserved prefix, or absent catalog entry is not deletion authority.

    @Suppress("UNUSED_PARAMETER") // Preserve the startup API without inspecting an untrusted path.
    suspend fun collect(projectsRoot: File, detached: List<File>): List<String> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        require(detached.isEmpty()) {
            "Permanent project cleanup requires verified deletion custody; all supplied workspace paths were preserved"
        }
        emptyList()
    }
}
