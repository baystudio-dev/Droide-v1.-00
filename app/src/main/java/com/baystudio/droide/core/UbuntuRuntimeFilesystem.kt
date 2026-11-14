package com.baystudio.droide.core

import java.io.File

/** A private backing store outside the guest rootfs; Android's /dev has no usable POSIX shm. */
internal object UbuntuRuntimeFilesystem {
    private const val SHARED_MEMORY = "runtime-shm"
    private const val ALREADY_EXISTS = 17
    private val descriptorAliases = listOf(
        "/proc/self/fd" to "/dev/fd",
        "/proc/self/fd/0" to "/dev/stdin",
        "/proc/self/fd/1" to "/dev/stdout",
        "/proc/self/fd/2" to "/dev/stderr",
    )

    fun bindings(appFiles: File, hostPathExists: (String) -> Boolean = { File(it).exists() }): List<String> {
        require(':' !in appFiles.absolutePath) { "Runtime path cannot be represented by a PRoot bind" }
        AnchoredWorkspaceFiles(appFiles).use { root ->
            if (root.kind(SHARED_MEMORY) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                val created = root.createDirectory(SHARED_MEMORY)
                check(created.durable || created.error == ALREADY_EXISTS) { "Cannot prepare Linux shared-memory storage" }
            }
            check(root.kind(SHARED_MEMORY) == AnchoredWorkspaceFiles.Kind.DIRECTORY) {
                "Linux shared-memory storage is not a private directory"
            }
        }
        return buildList {
            addAll(listOf("-b", "${File(appFiles, SHARED_MEMORY).absolutePath}:/dev/shm"))
            // Both devices use the kernel CSPRNG; avoid legacy entropy-pool waits in guest clients.
            addAll(listOf("-b", "/dev/urandom:/dev/random"))
            descriptorAliases.forEach { (source, destination) ->
                if (!hostPathExists(destination)) addAll(listOf("-b", "$source:$destination"))
            }
        }
    }
}
