package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files

object ProjectWorkspacePolicy {
    fun projectsRoot(appFilesDir: File): File {
        // Android's app files path may itself have a platform alias (/data/data).
        // Resolve that parent, but never bless a user-replaced projects symlink as
        // the app-private workspace container.
        val root = File(appFilesDir.canonicalFile, "projects")
        require(!Files.isSymbolicLink(root.toPath())) {
            "Project storage container is a symbolic link; project catalog and files were preserved"
        }
        return root.canonicalFile
    }

    fun defaultRoot(projectsRoot: File, projectId: String): File =
        requireDirectChild(projectsRoot, File(projectsRoot, PathSecurity.safeLeafName(projectId)))

    fun requireDirectChild(projectsRoot: File, candidate: File): File {
        require(!Files.isSymbolicLink(projectsRoot.absoluteFile.toPath().normalize())) {
            "Project storage container is a symbolic link; project catalog and files were preserved"
        }
        val root = projectsRoot.canonicalFile
        require(!Files.isSymbolicLink(candidate.absoluteFile.toPath().normalize())) { "Project workspace must not be a symbolic link" }
        val child = candidate.canonicalFile
        require(child != root) { "Project workspace must not be the projects container" }
        require(PathSecurity.contains(root, child)) { "Project workspace escaped app-private project root" }
        require(child.parentFile?.canonicalFile == root) { "Project workspace must be one direct child of the projects container" }
        require(!child.name.startsWith(".delete-")) { "Project workspace must not use reserved deletion-recovery storage" }
        return child
    }

    /**
     * Registry IDs own runtime/editor recovery state; canonical roots own bytes. They must
     * remain one-to-one. Never merge aliases or invent replacement directories here: either
     * action could strand a project's retained edits or give another ID deletion authority.
     * This only checks registered paths, not their contents or a recursive project tree.
     */
    fun requireDistinctRoots(projectsRoot: File, projects: List<DroideProject>) {
        val ids = mutableSetOf<String>()
        val owners = mutableMapOf<File, String>()
        val default = defaultRoot(projectsRoot, "default")
        for (project in projects) {
            val id = PathSecurity.safeLeafName(project.id)
            require(id != "default" || !project.workspaceRemoved) { "Default project must remain in the workspace" }
            require(ids.add(id)) { "Duplicate project ID; project catalog and files were preserved" }
            val root = requireDirectChild(projectsRoot, File(project.rootPath))
            require(id == "default" || root != default) {
                "Project $id aliases the Default workspace; project catalog and files were preserved. Review the saved project roots."
            }
            val previous = owners.put(root, id)
            require(previous == null) {
                "Projects $previous and $id share one workspace; project catalog and files were preserved. Review the saved project roots."
            }
        }
    }

    fun restoreRoot(projectsRoot: File, projectId: String, storedRoot: String?): File {
        val restored = storedRoot?.let { value ->
            val candidate = File(value)
            // An alias or an in-flight deletion tree is not a legacy path to silently
            // "repair" into a new empty project. Preserve the catalog for explicit recovery.
            require(!Files.isSymbolicLink(candidate.absoluteFile.toPath().normalize())) {
                "Saved project workspace is a symbolic link; project catalog and files were preserved"
            }
            require(!candidate.canonicalFile.name.startsWith(".delete-")) {
                "Saved project workspace points at deletion-recovery storage; project catalog and files were preserved"
            }
            runCatching { requireDirectChild(projectsRoot, candidate) }.getOrNull()
        }
        return restored ?: defaultRoot(projectsRoot, projectId)
    }
}
