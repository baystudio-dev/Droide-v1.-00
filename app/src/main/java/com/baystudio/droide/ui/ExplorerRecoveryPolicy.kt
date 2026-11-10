package com.baystudio.droide.ui

import com.baystudio.droide.core.ProjectCopyJournal

/** Explicit recovery is for misplaced project content, never a migration of app state. */
internal object ExplorerRecoveryPolicy {
    private val managed = setOf(
        ".runtime-agent-plugins", ".runtime-hooks", "artifacts", "backups", "browser", "build",
        "build-evidence", "run", "sessions", "subagents", "tmp", "vision", "web-cache", "worktrees",
        "agents", "commands", "skills", "themes", "tools", "AGENTS.md", "capabilities.json",
        "formatter.json", "hooks.json", "launch.json", "mcp.json", "providers.json", "tasks.json",
        "theme.json", "saf_manifest.json", "saf_manifest.txt", "history", "shares", "transactions",
        "auth.json", "debug-state", "editor-recovery", "permission-policies-v1", "projects.json",
        "active_project.txt", "recent_projects.json", "saf-write-recovery", "terminal-revive",
    )

    fun canRecover(path: String): Boolean {
        val parts = path.split('/')
        return parts.size == 2 && parts[0] == ".droide" && parts[1].isNotBlank() &&
            !ProjectCopyJournal.isProtectedRecoveryPath(path) && parts[1] !in managed && !parts[1].startsWith('.')
    }
}
