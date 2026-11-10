package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Shared catalog-only restore validation. Does not mutate storage, recents, or selection. */
internal object ProjectMembershipRestore {
    data class Plan(val target: DroideProject, val restored: DroideProject, val root: File, val projects: List<DroideProject>)
    fun plan(app: File, projects: List<DroideProject>, id: String): Plan {
        val target = projects.firstOrNull { it.id == id } ?: error("Stored project not found: $id")
        val projectsRoot = ProjectWorkspacePolicy.projectsRoot(app)
        ProjectWorkspacePolicy.requireDistinctRoots(projectsRoot, projects)
        val root = ProjectWorkspacePolicy.requireDirectChild(projectsRoot, File(target.rootPath))
        val directory = try { Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).isDirectory }
            catch (_: NoSuchFileException) { false }
        check(directory) { "Stored project folder is unavailable: ${root.path}. Restore the original folder before reopening; saved project data was kept." }
        val restored = target.copy(workspaceRemoved = false)
        return Plan(target, restored, root, projects.map { if (it.id == id) restored else it })
    }
}
