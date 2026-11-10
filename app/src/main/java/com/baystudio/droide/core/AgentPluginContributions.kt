package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException

internal enum class AgentRuleAdmissionLimit { PER_PLUGIN, TOTAL }

object AgentPluginContributions {
    const val MAX_PLUGIN_COUNT = 32
    const val MAX_MARKDOWN_BYTES = 64L * 1024L

    fun skillFiles(source: AgentPluginSource, workDir: File): List<Pair<AgentPluginContributionRoot, File>> =
        source.contributions(workDir).asSequence().flatMap { plugin ->
            val out = mutableListOf<Pair<AgentPluginContributionRoot, File>>()
            safeFile(plugin, "SKILL.md", MAX_MARKDOWN_BYTES)?.let { out += plugin to it }
            safeDir(plugin, "skills")?.listFiles().orEmpty()
                .asSequence()
                .filter { it.isDirectory && !PathSecurity.isSymbolicLink(it) }
                .sortedBy { it.name.lowercase() }
                .take(64)
                .mapNotNull { dir -> safeFile(plugin, "skills/${dir.name}/SKILL.md", MAX_MARKDOWN_BYTES)?.let { plugin to it } }
                .forEach(out::add)
            safeDir(plugin, "commands")?.listFiles().orEmpty().asSequence()
                .filter { it.isFile && it.extension.equals("md", true) && it.length() in 1..MAX_MARKDOWN_BYTES && !PathSecurity.isSymbolicLink(it) }
                .sortedBy { it.name.lowercase() }.take(48).map { plugin to it }.forEach(out::add)
            out
        }.take(128).toList()

    fun agentFiles(source: AgentPluginSource, workDir: File): List<Pair<AgentPluginContributionRoot, File>> =
        source.contributions(workDir).asSequence().flatMap { plugin ->
            safeDir(plugin, "agents")?.listFiles().orEmpty().asSequence()
                .filter { it.isFile && it.extension.equals("md", true) && it.length() in 1..MAX_MARKDOWN_BYTES && !PathSecurity.isSymbolicLink(it) }
                .sortedBy { it.name.lowercase() }
                .take(32)
                .map { plugin to it }
                .toList()
        }.take(96).toList()

    fun ruleFiles(source: AgentPluginSource, workDir: File): List<Pair<AgentPluginContributionRoot, File>> = ruleFiles(source, workDir, null)

    internal fun ruleFiles(
        source: AgentPluginSource,
        workDir: File,
        onSkipped: ((AgentPluginContributionRoot, File?, String) -> Unit)?,
    ): List<Pair<AgentPluginContributionRoot, File>> = ruleFiles(source, workDir, onSkipped, null)

    internal fun ruleFiles(
        source: AgentPluginSource,
        workDir: File,
        onSkipped: ((AgentPluginContributionRoot, File?, String) -> Unit)?,
        onLimited: ((AgentRuleAdmissionLimit) -> Unit)?,
    ): List<Pair<AgentPluginContributionRoot, File>> {
        val plugins = source.contributions(workDir)
        val result = mutableListOf<Pair<AgentPluginContributionRoot, File>>()
        for ((index, plugin) in plugins.withIndex()) {
            val candidates = ruleCandidates(plugin, onSkipped, onLimited)
            val remaining = 96 - result.size
            candidates.take(remaining).mapTo(result) { plugin to it }
            if (result.size == 96) {
                if (candidates.size > remaining || index < plugins.lastIndex) onLimited?.invoke(AgentRuleAdmissionLimit.TOTAL)
                break
            }
        }
        return result
    }

    private fun ruleCandidates(
        plugin: AgentPluginContributionRoot,
        onSkipped: ((AgentPluginContributionRoot, File?, String) -> Unit)?,
        onLimited: ((AgentRuleAdmissionLimit) -> Unit)?,
    ): List<File> {
        val root: File
        val rootBefore: BasicFileAttributes
        try {
            root = plugin.root.canonicalFile
            rootBefore = Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            check(rootBefore.isDirectory && rootBefore.fileKey() != null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { onSkipped?.invoke(plugin, null, "directory_unavailable"); return emptyList() }
        val directory = File(root, "rules")
        val before = try {
            Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            val rootUnchanged = try {
                val after = Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                after.isDirectory && after.fileKey() == rootBefore.fileKey()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (!rootUnchanged) onSkipped?.invoke(plugin, null, "directory_changed")
            return emptyList()
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { onSkipped?.invoke(plugin, null, "directory_unreadable"); return emptyList() }
        val directoryReason = when {
            before.isSymbolicLink -> "directory_symlink"
            !before.isDirectory -> "not_directory"
            before.fileKey() == null -> "directory_unavailable"
            else -> null
        }
        if (directoryReason != null) { onSkipped?.invoke(plugin, null, directoryReason); return emptyList() }
        val entries = try { directory.listFiles() ?: throw IOException("Cannot enumerate rule directory") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { onSkipped?.invoke(plugin, null, "directory_unreadable"); return emptyList() }
        val accepted = entries.asSequence().filter { it.extension.equals("md", true) }.filter { file ->
            val reason = try {
                val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                when {
                    attrs.isSymbolicLink -> "symbolic_link"
                    !attrs.isRegularFile -> "not_regular_file"
                    attrs.size() == 0L -> "empty_file"
                    attrs.size() !in 1..12_000L -> "file_too_large"
                    else -> null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "entry_unreadable" }
            if (reason != null) onSkipped?.invoke(plugin, file, reason)
            reason == null
        }.sortedBy { it.name.lowercase() }.take(if (onLimited == null) 32 else 33).toList()
        val stable = try {
            val after = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            val rootAfter = Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            rootAfter.isDirectory && rootAfter.fileKey() == rootBefore.fileKey() &&
                after.isDirectory && after.fileKey() == before.fileKey() &&
                after.lastModifiedTime() == before.lastModifiedTime() && after.creationTime() == before.creationTime()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        if (!stable) { onSkipped?.invoke(plugin, null, "directory_changed"); return emptyList() }
        if (accepted.size > 32) onLimited?.invoke(AgentRuleAdmissionLimit.PER_PLUGIN)
        return accepted.take(32)
    }

    fun mcpFiles(source: AgentPluginSource, workDir: File): List<Pair<AgentPluginContributionRoot, File>> =
        source.contributions(workDir).asSequence().flatMap { plugin ->
            listOf(".mcp.json", "mcp_config.json", "mcp.json").mapNotNull { rel ->
                safeFile(plugin, rel, 128L * 1024L)?.let { plugin to it }
            }
        }.take(32).toList()

    fun hookFiles(source: AgentPluginSource, workDir: File): List<Pair<AgentPluginContributionRoot, File>> =
        source.contributions(workDir).asSequence().flatMap { plugin ->
            listOf("hooks/hooks.json", "hooks.json").mapNotNull { rel ->
                safeFile(plugin, rel, 128L * 1024L)?.let { plugin to it }
            }
        }.take(32).toList()

    fun toolFiles(source: AgentPluginSource, workDir: File): List<Pair<AgentPluginContributionRoot, File>> =
        source.contributions(workDir).asSequence().mapNotNull { plugin ->
            safeFile(plugin, "tools/tools.json", 128L * 1024L)?.let { plugin to it }
                ?: safeFile(plugin, "tools.json", 128L * 1024L)?.let { plugin to it }
        }.take(32).toList()

    fun expandPluginRoot(value: String, plugin: AgentPluginContributionRoot): String = value
        .replace("${'$'}{DROIDE_PLUGIN_ROOT}", plugin.root.absolutePath)
        .replace("${'$'}{CLAUDE_PLUGIN_ROOT}", plugin.root.absolutePath)
        .replace("${'$'}{PLUGIN_ROOT}", plugin.root.absolutePath)

    private fun safeDir(plugin: AgentPluginContributionRoot, rel: String): File? = runCatching {
        PathSecurity.resolveWithin(plugin.root, rel).takeIf { it.isDirectory && !PathSecurity.isSymbolicLink(it) }
    }.getOrNull()

    private fun safeFile(plugin: AgentPluginContributionRoot, rel: String, maxBytes: Long): File? = runCatching {
        PathSecurity.resolveWithin(plugin.root, rel).takeIf {
            it.isFile && !PathSecurity.isSymbolicLink(it) && it.length() in 1..maxBytes
        }
    }.getOrNull()
}
