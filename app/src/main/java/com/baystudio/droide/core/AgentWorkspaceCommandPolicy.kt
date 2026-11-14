package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files

/**
 * Relative Agent commands and file tools share the project root. The prefix runs only
 * inside an already-owned command process; it must never be sent to the human shell.
 * Keep the original shell text intact, including heredocs, lists and trailing comments.
 */
internal object AgentWorkspaceCommandPolicy {
    fun requireRoot(workspace: File): File {
        require(workspace.isDirectory && !Files.isSymbolicLink(workspace.toPath())) {
            "Agent workspace is unavailable; no command was launched"
        }
        return workspace.canonicalFile
    }

    fun shell(workspace: File, command: String): String {
        val root = requireRoot(workspace)
        // `cd root && first; second` is unsafe: second would run even if cd failed.
        // A standalone guarded prefix also preserves the command's own exit status.
        return "cd -- ${LocalExecutionSubstrate.shellQuote(root.absolutePath)} || exit\n$command"
    }
}
