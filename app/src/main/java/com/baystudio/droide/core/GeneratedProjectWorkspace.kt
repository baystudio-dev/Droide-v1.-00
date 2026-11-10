package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Generated roots must not adopt an existing entry or outrun its parent-directory sync. */
internal class GeneratedProjectWorkspace(
    private val createDirectory: (File, String) -> AnchoredWorkspaceFiles.MoveReceipt = { parent, leaf ->
        AnchoredWorkspaceFiles(parent).use { storage ->
            storage.createDirectory(leaf).also { receipt ->
                if (receipt.durable) storage.directoryKey(leaf)
            }
        }
    },
) {
    fun create(projectsRoot: File, root: File) {
        try {
            val receipt = createDirectory(projectsRoot, root.name)
            check(receipt.durable) {
                "Directory creation receipt: created=${receipt.published}, errno=${receipt.error}"
            }
        } catch (cancelled: CancellationException) {
            throw CancellationException(
                "Project workspace creation was interrupted: ${root.path}. Any created folder was preserved.",
            ).also { it.initCause(cancelled) }
        } catch (failure: Exception) {
            // Even an exception can follow publication. Never delete or adopt the entry.
            throw IOException(
                "Project workspace creation could not be confirmed: ${root.path}. " +
                    "Any existing or newly created folder was preserved; inspect this path before retrying.",
                failure,
            )
        }
    }
}
