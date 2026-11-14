package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ConcurrentHashMap

/** App-private response storage outside the source workspace. Provider/model strings never form paths. */
internal object AgentPublicContentStores {
    private val admissions = ConcurrentHashMap<String, OwnedResponseContentStore.HeadroomAdmission>()
    private const val SYSTEM_HEADROOM_BYTES = 32L * 1024 * 1024

    fun registry(workDir: File): AgentPublicContentRegistry {
        val parent = File(ChatMediaStoragePaths.baseDirectory(workDir).parentFile, "response-registry")
        require(!PathSecurity.contains(workDir.canonicalFile, parent))
        ChatMediaStoragePaths.requireNoLinks(parent)
        return AgentPublicContentRegistry(File(parent, ChatMediaStoragePaths.workspaceKey(workDir)))
    }

    fun forWorkspace(workDir: File, sessionId: String, cancelled: () -> Boolean = { false }): AgentPublicContentArchive {
        PathSecurity.safeLeafName(sessionId)
        return AgentPublicContentArchive(ownedStore(workDir, cancelled), sessionId)
    }

    internal fun ownedStore(workDir: File, cancelled: () -> Boolean = { false }): OwnedResponseContentStore {
        val mediaBase = ChatMediaStoragePaths.baseDirectory(workDir)
        val parent = File(mediaBase.parentFile, "response-content")
        ChatMediaStoragePaths.requireNoLinks(parent)
        require(!PathSecurity.contains(workDir.canonicalFile, parent)) { "Response storage must be outside source workspace" }
        val root = File(parent, ChatMediaStoragePaths.workspaceKey(workDir))
        ChatMediaStoragePaths.requireNoLinks(root)
        Files.createDirectories(root.toPath())
        require(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Response storage is not a directory" }
        val admission = admissions.computeIfAbsent(parent.canonicalPath) {
            // Shared across workspace writers. Actual filesystem free space is checked for every reservation.
            OwnedResponseContentStore.HeadroomAdmission({ parent.usableSpace }, SYSTEM_HEADROOM_BYTES)
        }
        return OwnedResponseContentStore(root.toPath(), admission, cancelled = cancelled)
    }
}
