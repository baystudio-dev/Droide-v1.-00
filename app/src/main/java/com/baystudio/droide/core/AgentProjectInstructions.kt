package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext










object AgentProjectInstructions {
    private const val MAX_BRIEF_FILE_BYTES = 50_000L
    private const val MAX_RULE_FILE_BYTES = 12_000L
    private const val MAX_RULES = 24
    private const val MAX_TOTAL_CHARS = 28_000

    /** Only instructions along the requested path apply; sibling directory rules do not. */
    fun scopedPaths(files: FileRepository, path: String): List<String> {
        val root = files.root.canonicalFile
        val target = files.resolveChecked(path).canonicalFile
        var directory = if (target.isDirectory) target else target.parentFile
        val ancestors = mutableListOf<File>()
        while (directory != null && PathSecurity.contains(root, directory)) {
            ancestors += directory
            if (directory == root) break
            require(ancestors.size <= 32) { "Instruction path is too deep" }
            directory = directory.parentFile
        }
        return ancestors.asReversed().mapNotNull { dir ->
            val candidate = File(dir, "AGENTS.md")
            if (!candidate.isFile || PathSecurity.isSymbolicLink(candidate)) null else
                root.toPath().relativize(candidate.toPath()).toString().replace(File.separatorChar, '/')
        }
    }

    suspend fun loadScoped(files: FileRepository, path: String, expectedPaths: List<String>? = null): String {
        val paths = scopedPaths(files, path)
        require(expectedPaths == null || paths == expectedPaths) { "Applicable instructions changed after approval; retry to review current permissions" }
        if (paths.isEmpty()) return "No scoped AGENTS.md instructions found for $path. Root workspace/plugin rules still apply."
        val content = buildString {
            appendLine("Applicable AGENTS.md files for $path (root to nearest directory; nearer rules override broader rules in their scope):")
            for (rel in paths) {
                appendLine("\n--- $rel ---")
                appendLine(files.readText(rel, MAX_BRIEF_FILE_BYTES))
            }
        }
        if (content.length > 10_000) return "[DROIDE_PROJECT_INSTRUCTIONS incomplete=true]\n" +
            "Applicable files: ${paths.joinToString(", ")}\n" +
            "Instructions exceed one tool response. Read every applicable file with read_file start/end before changing this path."
        return content
    }

    suspend fun load(files: FileRepository, workDir: File, plugins: AgentPluginSource = AgentPluginSource.EMPTY): String = withContext(Dispatchers.IO) {
        val body = StringBuilder()
        val sources = linkedSetOf<String>()
        val incomplete = linkedMapOf<String, String>()

        fun appendPart(label: String, source: String, text: String, perFileLimit: Int) {
            sources += source
            if (text.length > perFileLimit) incomplete[source] = "file_limit"
            val part = "\n--- $label ---\n${text.take(perFileLimit)}\n"
            val remaining = (MAX_TOTAL_CHARS - body.length).coerceAtLeast(0)
            if (part.length > remaining) incomplete[source] = "total_limit"
            body.append(part.take(remaining))
        }

        // Request-local exact-text reuse only. Never skip a scoped tool read based on
        // a previous load: files may have changed, or that brief may no longer be active.
        val sameInstructionRoot = runCatching { files.root.canonicalFile == workDir.canonicalFile }.getOrDefault(false)
        var completeRootAgents: String? = null
        var interveningDroideSource = false
        for (rel in listOf("AGENTS.md", ".droide/AGENTS.md", "GEMINI.md")) {
            val file = runCatching { PathSecurity.resolveWithin(workDir, rel) }.getOrNull() ?: continue
            if (!file.isFile || PathSecurity.isSymbolicLink(file) || file.length() == 0L) continue
            sources += rel
            if (rel == ".droide/AGENTS.md") interveningDroideSource = true
            if (file.length() > MAX_BRIEF_FILE_BYTES) {
                incomplete[rel] = "file_limit"
                continue
            }
            val text = runSuspendCatching { files.readText(rel, maxBytes = MAX_BRIEF_FILE_BYTES) }.getOrNull()
            if (text == null) incomplete[rel] = "unreadable"
            else {
                // These two names are in the SAME directory. Preserve source order and
                // identity; do not collapse identical bodies across a .droide source,
                // different directory, incomplete read, normalization or intervening rule.
                val reference = "[Exact full-text duplicate in this request: GEMINI.md has the same verbatim body as AGENTS.md above. " +
                    "Apply that body independently as GEMINI.md at the project root, in this source position; preserve its source-relative meaning.]"
                val duplicate = sameInstructionRoot && rel == "GEMINI.md" && !interveningDroideSource &&
                    completeRootAgents == text && text.length > reference.length
                appendPart("Project instructions ($rel)", rel, if (duplicate) reference else text, 8_000)
                if (rel == "AGENTS.md" && text.length <= 8_000 && incomplete.isEmpty()) completeRootAgents = text
            }
        }

        val workspaceRules = alwaysOnRuleCandidates(workDir).take(MAX_RULES + 1).toList()
        if (workspaceRules.size > MAX_RULES) {
            sources += ".agents/rules and .agent/rules directories"
            incomplete["workspace rule directories"] = "rule_limit"
        }
        workspaceRules.take(MAX_RULES).forEach { rule ->
            val rel = rule.relativeTo(workDir).invariantSeparatorsPath
            sources += rel
            if (rule.length() > MAX_RULE_FILE_BYTES) {
                incomplete[rel] = "file_limit"
                return@forEach
            }
            val text = runSuspendCatching { readWorkspaceRule(rule) }.getOrNull()
            if (text == null) incomplete[rel] = "unreadable"
            else appendPart("Always-on workspace rule ($rel)", rel, stripFrontmatter(text), MAX_RULE_FILE_BYTES.toInt())
        }

        val pluginRules = AgentRuleManager(workDir, plugins).alwaysOnWithDiagnostics()
        val ruleNotice = pluginRules.notice().let { if (it.isEmpty()) "" else "[DROIDE_RULE_DIAGNOSTICS]\n$it\n" }
        pluginRules.values.forEach { rule ->
            appendPart("Always-on Agent plugin rule (${rule.name}; ${rule.sourceLabel})",
                "plugin rule ${rule.name}", rule.body, MAX_RULE_FILE_BYTES.toInt())
        }
        if (body.length + ruleNotice.length > MAX_TOTAL_CHARS) incomplete["plugin rule diagnostics"] = "total_limit"
        if (incomplete.isEmpty()) return@withContext ruleNotice + body.take((MAX_TOTAL_CHARS - ruleNotice.length).coerceAtLeast(0))

        // The notice precedes the bounded excerpt, so truncation cannot hide it. List all
        // sources: reserving space for this notice can also shorten the last complete part.
        val notice = buildString {
            appendLine("[DROIDE_PROJECT_INSTRUCTIONS incomplete=true]")
            appendLine("Limits: ${incomplete.entries.joinToString { "${it.key} (${it.value})" }}")
            appendLine("Sources: ${sources.joinToString(", ")}")
            appendLine("Partial excerpts follow. Before editing, read every listed workspace source with read_file pagination (start/end or offset), and applicable scoped AGENTS.md files. If rule_limit is set, list the rule directories and read their applicable always-on rules. Use the rule tool to read named plugin rules. An unreadable source must be resolved before assuming its instructions are complete.")
        }
        ruleNotice + notice + body.take((MAX_TOTAL_CHARS - ruleNotice.length - notice.length).coerceAtLeast(0))
    }

    /** The metadata size check is only a hint: a workspace rule can grow before it is opened. */
    internal suspend fun readWorkspaceRule(file: File): String =
        BoundedUtf8.read(Files.newInputStream(file.toPath(), NOFOLLOW_LINKS), MAX_RULE_FILE_BYTES) {
            throw IllegalArgumentException("Workspace rule grew beyond its read limit")
        }

    fun discoverAlwaysOnRules(workDir: File): List<File> = alwaysOnRuleCandidates(workDir)
        .filter { it.length() <= MAX_RULE_FILE_BYTES }.take(MAX_RULES).toList()

    private fun alwaysOnRuleCandidates(workDir: File): Sequence<File> {
        val canonicalRoot = runCatching { workDir.canonicalFile }.getOrNull() ?: return emptySequence()
        return listOf(".agents/rules", ".agent/rules")
            .asSequence()
            .mapNotNull { rel -> runCatching { PathSecurity.resolveWithin(canonicalRoot, rel) }.getOrNull() }
            .filter { it.isDirectory && !PathSecurity.isSymbolicLink(it) }
            .flatMap { dir ->
                dir.listFiles().orEmpty().asSequence()
                    .filter { file ->
                        file.isFile && !PathSecurity.isSymbolicLink(file) &&
                            file.extension.equals("md", ignoreCase = true) &&
                            file.length() > 0L &&
                            PathSecurity.contains(canonicalRoot, file)
                    }
            }
            .sortedBy { it.absolutePath }
            .filter(::isAlwaysOn)
    }

    private fun isAlwaysOn(file: File): Boolean {
        val prefix = runCatching {
            file.bufferedReader().use { reader ->
                val buffer = CharArray(4_096)
                val count = reader.read(buffer)
                if (count < 0) "" else String(buffer, 0, count)
            }
        }.getOrNull() ?: return false
        if (!prefix.startsWith("---")) return false
        val end = prefix.indexOf("\n---", startIndex = 3)
        if (end < 0) return false
        val frontmatter = prefix.substring(3, end)
        val trigger = Regex("(?im)^\\s*trigger\\s*:\\s*([^#\\r\\n]+)")
            .find(frontmatter)?.groupValues?.get(1)?.trim()?.lowercase() ?: return false
        // A quoted YAML scalar has the same activation value as its plain spelling.
        // Remove only one matching pair; malformed/mismatched quotes must not activate.
        val activation = if (trigger.length >= 2 && trigger.first() in "\"'" && trigger.last() == trigger.first())
            trigger.substring(1, trigger.length - 1) else trigger
        return activation == "always_on" || activation == "always-on" || activation == "always"
    }

    private fun stripFrontmatter(text: String): String {
        if (!text.startsWith("---")) return text.trim()
        val end = text.indexOf("\n---", startIndex = 3)
        if (end < 0) return text.trim()
        return text.substring(end + 4).trim()
    }

}
