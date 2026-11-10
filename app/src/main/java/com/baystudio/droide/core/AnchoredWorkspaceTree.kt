package com.baystudio.droide.core

import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

internal class WorkspaceInventoryCapacityException(kind: String) : IllegalArgumentException(
    "Workspace synchronization inventory contains too many $kind; data was preserved",
)

internal object AnchoredWorkspaceTree {
    data class Snapshot(
        val files: MutableMap<String, WorkspaceFileFingerprintCache.Fingerprint> = linkedMapOf(),
        val directories: MutableSet<String> = linkedSetOf(),
        val opaque: MutableSet<String> = linkedSetOf(),
    )

    fun snapshot(root: File, fingerprints: WorkspaceFileFingerprintCache, context: CoroutineContext,
                 excluded: (String) -> Boolean = { !AgentWorkspacePathPolicy.visible(it) }): Snapshot {
        val result = Snapshot()
        val pending = java.util.ArrayDeque<String>().apply { add("") }
        AnchoredWorkspaceFiles(root).use { files ->
            while (pending.isNotEmpty()) {
                context.ensureActive()
                val parent = pending.removeFirst()
                files.forEachChild(parent) { name ->
                    context.ensureActive()
                    val relative = if (parent.isEmpty()) name else "$parent/$name"
                    if (excluded(relative)) result.opaque += relative else when (files.kind(relative)) {
                        AnchoredWorkspaceFiles.Kind.DIRECTORY -> {
                            if (result.directories.size >= 40_000) throw WorkspaceInventoryCapacityException("directories")
                            result.directories += relative
                            pending.add(relative)
                        }
                        AnchoredWorkspaceFiles.Kind.FILE -> {
                            if (result.files.size >= 20_000) throw WorkspaceInventoryCapacityException("files")
                            result.files[relative] = fingerprints.read(files, relative, File(root, relative).toPath(), context)
                        }
                        AnchoredWorkspaceFiles.Kind.ABSENT -> error("Workspace changed during inspection: $relative; retry after concurrent writes finish")
                        else -> result.opaque += relative
                    }
                    if (result.opaque.size > 40_000) throw WorkspaceInventoryCapacityException("excluded entries")
                }
            }
        }
        return result
    }
}
