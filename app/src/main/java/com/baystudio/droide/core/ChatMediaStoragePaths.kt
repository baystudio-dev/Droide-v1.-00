package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Virtual conversation aliases map to app-owned storage outside source/project/dependency trees. */
object ChatMediaStoragePaths {
    const val VIRTUAL_ROOT = ".droide/chat-media"
    private val idPattern = Regex("m-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val leafPattern = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")

    fun workspaceKey(workDir: File): String = MessageDigest.getInstance("SHA-256")
        .digest(canonicalWorkspace(workDir).path.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun baseDirectory(workDir: File): File {
        val workspace = canonicalWorkspace(workDir)
        val parent = requireNotNull(workspace.parentFile) { "Workspace has no private media storage parent" }

        var ancestor: File? = parent
        var projects: File? = null
        while (ancestor != null) {
            if (ancestor.name == "projects") projects = ancestor
            ancestor = ancestor.parentFile
        }
        val base = projects?.parentFile?.let { File(it, "chat-media") } ?: File(parent, ".droide-chat-media")
        require(!PathSecurity.contains(workspace, base)) { "Chat media storage must be outside the source workspace" }
        requireNoLinks(base)
        return base
    }

    fun directory(workDir: File): File = File(baseDirectory(workDir), workspaceKey(workDir)).also(::requireNoLinks)

    internal fun canonicalWorkspace(workDir: File): File {
        var component: File? = workDir.absoluteFile
        while (component != null) {
            if (Files.isSymbolicLink(component.toPath())) {
                // Android's legacy app-data alias is system-owned and resolves to the same private user tree.
                require(component.path in setOf("/data/data", "/data/user/0") &&
                    component.canonicalPath in setOf("/data/data", "/data/user/0")) {
                    "Workspace ancestry contains a symbolic link; chat media storage was left unchanged."
                }
            }
            component = component.parentFile
        }
        return workDir.canonicalFile
    }

    internal fun physicalPath(workDir: File, alias: String, allowBlobDirectory: Boolean = false): File {
        return physicalPathIn(directory(workDir), alias, allowBlobDirectory)
    }

    internal fun physicalPathIn(root: File, alias: String, allowBlobDirectory: Boolean = false): File {
        require(alias.startsWith("$VIRTUAL_ROOT/blobs/")) { "Invalid managed media alias" }
        val parts = alias.removePrefix("$VIRTUAL_ROOT/").split('/')
        require(parts.size == 3 || allowBlobDirectory && parts.size == 2) { "Managed media alias contains an invalid path" }
        require(parts[0] == "blobs" && idPattern.matches(parts[1]) &&
            (parts.size == 2 || leafPattern.matches(parts[2]))) { "Invalid managed media alias" }
        return File(root, parts.joinToString("/")).also { file ->
            require(file.toPath() == file.toPath().normalize() && file.toPath().startsWith(root.toPath())) { "Managed media alias escapes storage" }
            requireNoLinks(file)
        }
    }

    internal fun requireNoLinks(file: File) {
        var component: File? = file.absoluteFile
        while (component != null) {
            require(!Files.isSymbolicLink(component.toPath())) { "Chat media storage cannot use symbolic links; existing files were preserved." }
            component = component.parentFile
        }
    }
}
