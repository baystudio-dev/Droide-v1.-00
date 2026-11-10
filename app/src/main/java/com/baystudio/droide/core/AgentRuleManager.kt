package com.baystudio.droide.core

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CancellationException

data class AgentRuleSummary(val name: String, val description: String, val activation: AgentPluginRuleActivation, val sourceLabel: String)

internal data class AgentRuleDiscovery<T>(
    val values: List<T>,
    val skippedCount: Int,
    val details: List<String>,
    val limits: Set<AgentRuleAdmissionLimit> = emptySet(),
    val alwaysOnTruncated: Boolean = false,
) {
    val isComplete: Boolean get() = skippedCount == 0 && limits.isEmpty() && !alwaysOnTruncated

    fun notice(): String = if (isComplete) "" else buildString {
        append("Rule discovery incomplete: ")
        if (skippedCount > 0) {
            append("$skippedCount encountered entry issue(s). ")
            append(details.joinToString("; "))
            if (skippedCount > details.size) append("; ${skippedCount - details.size} further issue(s) omitted")
            append(". ")
        }
        for (limit in limits) append(when (limit) {
            AgentRuleAdmissionLimit.PER_PLUGIN -> "Per-plugin rule admission limit (32 files) omitted candidates whose rule validity was not checked. "
            AgentRuleAdmissionLimit.TOTAL -> "Aggregate rule admission limit (96 files) left candidates or plugins unexamined; additional rules are not confirmed. "
        })
        if (alwaysOnTruncated) append("Always-on rule selection limit (24 rules): additional admitted guidance was not included. Load applicable omitted rules explicitly. ")
        append("Inspect plugin rule files before assuming instructions are complete.")
    }
}

// Rules are guidance, never authority.
class AgentRuleManager internal constructor(
    private val workDir: File,
    private val plugins: AgentPluginSource,
    private val openInput: (File) -> InputStream,
) {
    constructor(workDir: File, plugins: AgentPluginSource = AgentPluginSource.EMPTY) :
        this(workDir, plugins, { Files.newInputStream(it.toPath(), NOFOLLOW_LINKS) })

    private class Issues {
        var skipped = 0
        private val details = mutableListOf<String>()
        private val limits = linkedSetOf<AgentRuleAdmissionLimit>()

        fun add(plugin: AgentPluginContributionRoot, file: File?, reason: String) {
            if (skipped < Int.MAX_VALUE) skipped++
            if (details.size < 4) details += "${label(plugin.id)}:rules/${label(file?.name ?: ".")} ($reason)"
        }

        fun limit(value: AgentRuleAdmissionLimit) { limits += value }

        fun <T> result(values: List<T>, alwaysOnTruncated: Boolean = false) = AgentRuleDiscovery(values, skipped, details.toList(), limits.toSet(), alwaysOnTruncated)

        private fun label(value: String): String {
            var end = minOf(value.length, 64)
            if (end in 1 until value.length && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
            return value.substring(0, end).map { if (it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()) ' ' else it }
                .joinToString("") + if (end < value.length) "…" else ""
        }
    }

    private fun entries(metadataOnly: () -> Boolean = { false }, issues: Issues? = null): Sequence<AgentPluginRule> {
        val seen = hashSetOf<String>()
        return AgentPluginContributions.ruleFiles(plugins, workDir, issues?.let { it::add }, issues?.let { it::limit }).asSequence().mapNotNull { (plugin, file) ->
            parse(plugin, file, metadataOnly(), issues)?.takeIf {
                if (seen.add(it.name)) true else { issues?.add(plugin, file, "duplicate_name"); false }
            }
        }.take(96)
    }

    fun discover(): List<AgentPluginRule> = entries().toList()
    fun load(name: String): AgentPluginRule? = entries().firstOrNull { it.name == name }
    fun alwaysOn(): List<AgentPluginRule> = entries().filter { it.activation == AgentPluginRuleActivation.ALWAYS }.take(MAX_ALWAYS_ON_RULES).toList()
    fun modelVisible(): List<AgentPluginRule> = modelVisible(64)

    fun catalog(limit: Int = 64): List<AgentRuleSummary> = catalogEntries(limit)

    internal fun catalogWithDiagnostics(limit: Int = 64): AgentRuleDiscovery<AgentRuleSummary> {
        val issues = Issues()
        return issues.result(catalogEntries(limit, issues))
    }

    internal fun loadWithDiagnostics(name: String): AgentRuleDiscovery<AgentPluginRule> {
        val issues = Issues()
        return issues.result(listOfNotNull(entries(issues = issues).firstOrNull { it.name == name }))
    }

    internal fun alwaysOnWithDiagnostics(): AgentRuleDiscovery<AgentPluginRule> {
        val issues = Issues()
        val selected = mutableListOf<AgentPluginRule>()
        var truncated = false
        for (rule in entries(metadataOnly = { selected.size == MAX_ALWAYS_ON_RULES }, issues = issues)) {
            if (rule.activation != AgentPluginRuleActivation.ALWAYS) continue
            if (selected.size == MAX_ALWAYS_ON_RULES) { truncated = true; break }
            selected += rule
        }
        return issues.result(selected, alwaysOnTruncated = truncated)
    }

    private fun catalogEntries(limit: Int, issues: Issues? = null): List<AgentRuleSummary> {
        require(limit in 0..96) { "Rule catalog limit must be between 0 and 96" }
        if (limit == 0) return emptyList()
        return entries(metadataOnly = { true }, issues = issues).filter { it.activation != AgentPluginRuleActivation.ALWAYS }.take(limit)
            .map { AgentRuleSummary(it.name, it.description, it.activation, it.sourceLabel) }.toList()
    }

    internal fun modelVisible(limit: Int): List<AgentPluginRule> {
        require(limit in 0..96) { "Rule catalog limit must be between 0 and 96" }
        if (limit == 0) return emptyList()
        return entries().filter { it.activation != AgentPluginRuleActivation.ALWAYS }.take(limit).toList()
    }

    private data class Observation(val key: Any, val size: Long, val modified: FileTime, val created: FileTime, val parents: List<Any>)

    private fun observe(plugin: AgentPluginContributionRoot, file: File): Observation {
        val root = plugin.root.canonicalFile.toPath()
        val path = file.absoluteFile.toPath().normalize()
        require(path != root && path.startsWith(root) && path == file.canonicalFile.toPath()) { "Rule path changed" }
        val parents = mutableListOf<Any>()
        var parent = root
        for (part in listOf("") + root.relativize(path.parent).map { it.toString() }.filter(String::isNotEmpty)) {
            if (part.isNotEmpty()) parent = parent.resolve(part)
            val attrs = Files.readAttributes(parent, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            check(attrs.isDirectory) { "Rule parent is not an ordinary directory" }
            parents += checkNotNull(attrs.fileKey())
        }
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(attrs.isRegularFile && attrs.size() in 1..MAX_RULE_BYTES.toLong()) { "Rule is not an admitted ordinary file" }
        return Observation(checkNotNull(attrs.fileKey()), attrs.size(), attrs.lastModifiedTime(), attrs.creationTime(), parents)
    }

    private fun read(plugin: AgentPluginContributionRoot, file: File, metadataOnly: Boolean): String {
        val before = observe(plugin, file)
        var prefixComplete = false
        val bytes = openInput(file).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(if (metadataOnly) 512 else 4_096)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_RULE_BYTES + 1 - output.size()))
                if (count < 0) break
                check(count in 1..(MAX_RULE_BYTES - output.size())) { "Rule exceeds its read limit or made no progress" }
                output.write(buffer, 0, count)
                if (metadataOnly && metadataComplete(output.toByteArray())) {
                    prefixComplete = true
                    break
                }
            }
            output.toByteArray()
        }
        check(bytes.size.toLong() <= before.size && (prefixComplete || bytes.size.toLong() == before.size) && observe(plugin, file) == before) { "Rule changed during read" }
        return String(bytes, Charsets.UTF_8)
    }

    private fun metadataComplete(bytes: ByteArray): Boolean {
        val decoded = CharBuffer.allocate(bytes.size)
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes), decoded, false)
        val raw = String(decoded.array(), 0, decoded.position()).replace("\r\n", "\n")
        if ("---\n".startsWith(raw) || raw == "---\r") return false
        if (raw.startsWith("---\n") && raw.indexOf("\n---\n", 4) < 0) return false
        return splitFrontmatter(raw).second.isNotBlank()
    }

    private fun parse(plugin: AgentPluginContributionRoot, file: File, metadataOnly: Boolean, issues: Issues?): AgentPluginRule? {
        var reason = "read_failed"
        return try {
            val raw = read(plugin, file, metadataOnly).replace("\r\n", "\n")
            reason = "invalid_rule"
            val split = splitFrontmatter(raw)
            val fm = split.first
            val body = split.second.trim().take(12_000)
            require(body.isNotBlank()) { "Empty Agent rule" }
            val declared = scalar(fm, "name") ?: file.nameWithoutExtension
            val local = declared.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').take(64)
            require(local.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "Invalid Agent rule name" }
            val description = (SkillYamlDescription.blockValue(fm + "\n") ?: scalar(fm, "description") ?: local).trim().take(500)
            val trigger = (scalar(fm, "trigger") ?: scalar(fm, "activation") ?: "model").trim().lowercase()
            val activation = when (trigger) {
                "always", "always_on", "always-on" -> AgentPluginRuleActivation.ALWAYS
                "manual" -> AgentPluginRuleActivation.MANUAL
                "glob", "globs" -> AgentPluginRuleActivation.GLOB
                else -> AgentPluginRuleActivation.MODEL
            }
            val globs = listValue(fm, "globs").take(32)
            AgentPluginRule(
                name = "${plugin.id}:$local",
                description = description,
                body = body,
                activation = activation,
                globs = globs,
                sourceLabel = "plugin:${plugin.id}@${plugin.contentSha256.take(12)}",
            )
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { issues?.add(plugin, file, reason); null }
    }

    private companion object {
        const val MAX_RULE_BYTES = 12_000
        const val MAX_ALWAYS_ON_RULES = 24
    }

    private fun splitFrontmatter(raw: String): Pair<String, String> {
        if (!raw.startsWith("---\n")) return "" to raw
        val end = raw.indexOf("\n---\n", 4)
        if (end < 0) return "" to raw
        return raw.substring(4, end) to raw.substring(end + 5)
    }

    private fun scalar(fm: String, key: String): String? = Regex("(?im)^\\s*${Regex.escape(key)}\\s*:\\s*([^#\\r\\n]+)")
        .find(fm)?.groupValues?.get(1)?.trim()?.trim('"', '\'')

    private fun listValue(fm: String, key: String): List<String> {
        val inline = scalar(fm, key)?.takeIf { it.startsWith("[") && it.endsWith("]") }
        if (inline != null) return inline.substring(1, inline.length - 1).split(',').map { it.trim().trim('"', '\'') }.filter(String::isNotBlank)
        val lines = fm.lines()
        val start = lines.indexOfFirst { it.trim().startsWith("$key:") }
        if (start < 0) return emptyList()
        return lines.drop(start + 1).takeWhile { it.trimStart().startsWith("-") }.map { it.trim().removePrefix("-").trim().trim('"', '\'') }.filter(String::isNotBlank)
    }
}
