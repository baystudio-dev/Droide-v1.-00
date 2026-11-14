package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CancellationException

// Progressive-disclosure Agent Skills with project + integrity-verified plugin sources.
data class Skill(val name: String, val description: String, val body: String, val sourceLabel: String = "workspace")
data class SkillSummary(val name: String, val description: String, val sourceLabel: String)

data class SkillDocument(
    val skill: Skill,
    val markdown: String,
    val file: File,
    val managedScope: String?,
    val userInvocable: Boolean,
    val disableModelInvocation: Boolean,
    internal val sourceRoot: File,
    internal val bundleDirectory: File,
    internal val validateSource: () -> Unit,
    internal val bundled: Boolean = false,
) {
    val version: DiskFileVersion get() = DiskFileVersion.fromText(markdown)
}

class SkillManager private constructor(
    private val workDir: File,
    private val plugins: AgentPluginSource,
    private val openInput: (File) -> InputStream,
    private val personalRoot: File?,
    private val includeWorkspace: Boolean = true,
    private val includeBundled: Boolean = false,
) {
    constructor(workDir: File, plugins: AgentPluginSource = AgentPluginSource.EMPTY) :
        this(workDir, plugins, { Files.newInputStream(it.toPath(), NOFOLLOW_LINKS) }, null)

    constructor(workDir: File, plugins: AgentPluginSource, personalRoot: File?) :
        this(workDir, plugins, { Files.newInputStream(it.toPath(), NOFOLLOW_LINKS) }, personalRoot)

    internal constructor(workDir: File, plugins: AgentPluginSource, openInput: (File) -> InputStream) :
        this(workDir, plugins, openInput, null)

    internal fun withBundled(): SkillManager = SkillManager(workDir, plugins, openInput, personalRoot, includeWorkspace, true)

    private fun bundledCatalog() = if (includeBundled) BundledAgentSkills.catalog() else emptyList()

    private data class Source(val file: File, val root: File, val plugin: AgentPluginContributionRoot?, val maxBytes: Int, val label: String = "workspace", val managedScope: String? = null)
    private data class Entry(val summary: SkillSummary, val source: Source, val controls: SkillDocumentFormat.Controls)
    private data class Observation(val path: File, val key: Any, val bytes: Long, val modified: FileTime, val created: FileTime,
        val parents: List<Any>)

    // Keep the existing full-discovery API for callers that explicitly need bodies.
    fun discover(): List<Skill> = (sources().mapNotNull { read(it, metadataOnly = false) } +
        bundledCatalog().asSequence().mapNotNull { BundledAgentSkills.document(it.name, workDir)?.skill })
        .distinctBy { it.name }.take(MAX_SKILLS).toList()

    fun catalog(limit: Int = MAX_SKILLS): List<SkillSummary> {
        require(limit in 0..MAX_SKILLS) { "Skill catalog limit must be between 0 and $MAX_SKILLS" }
        return (entries().map { it.summary } + bundledCatalog().asSequence()).distinctBy { it.name }.take(limit).toList()
    }

    fun load(name: String): Skill? = document(name)?.skill

    fun document(name: String): SkillDocument? = try {
        val selected = entries().take(MAX_SKILLS).firstOrNull { it.summary.name == name }
        if (selected == null) {
            if (includeBundled && entries().none { it.summary.name == name }) BundledAgentSkills.document(name, workDir) else null
        } else {
            val observation = observe(selected.source)
            val raw = readSource(selected.source, metadataOnly = false)
            val loaded = raw?.let { parse(it, selected.source) }
            if (loaded == null || summary(loaded) != selected.summary || observe(selected.source) != observation) null
            else {
                val controls = SkillDocumentFormat.controls(raw)
                val folder = requireNotNull(selected.source.file.parentFile)
                val managed = selected.source.managedScope.takeIf {
                    folder.name == loaded.name && selected.source.file.absoluteFile == observation.path
                }
                SkillDocument(loaded, raw, observation.path, managed, controls.userInvocable,
                    controls.disableModelInvocation, selected.source.root.canonicalFile, folder.canonicalFile,
                    { check(observe(selected.source) == observation) { "Skill changed; reload it before reading resources" } })
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    fun modelCatalog(limit: Int = MAX_SKILLS): List<SkillSummary> = filteredCatalog(limit) { !it.controls.disableModelInvocation }
    fun invocationCatalog(limit: Int = MAX_SKILLS): List<SkillSummary> = filteredCatalog(limit) { it.controls.userInvocable }

    private fun filteredCatalog(limit: Int, predicate: (Entry) -> Boolean): List<SkillSummary> {
        require(limit in 0..MAX_SKILLS)
        val local = entries().take(MAX_SKILLS).toList()
        val names = local.map { it.summary.name }.toSet()
        return (local.filter(predicate).asSequence().map { it.summary } + bundledCatalog().asSequence().filter { it.name !in names })
            .take(limit).toList()
    }

    private fun entries(): Sequence<Entry> = sources().mapNotNull { source ->
        val raw = readSource(source, metadataOnly = true) ?: return@mapNotNull null
        parse(raw, source)?.let { Entry(summary(it), source, SkillDocumentFormat.controls(raw)) }
    }.distinctBy { it.summary.name }

    private fun summary(skill: Skill) = SkillSummary(skill.name, skill.description, skill.sourceLabel)

    private fun sources(): Sequence<Source> = sequence {
        for (relative in if (includeWorkspace) ROOTS else emptyList()) {
            val base = runCatching { PathSecurity.resolveWithin(workDir, relative) }.getOrNull() ?: continue
            if (!base.isDirectory || PathSecurity.isSymbolicLink(base)) continue
            val dirs = childDirectories(base)
            for (dir in dirs) {
                if (PathSecurity.contains(workDir, dir)) yield(Source(File(dir, "SKILL.md"), workDir, null, 200_000,
                    managedScope = if (relative == ".droide/skills") "workspace" else null))
            }
        }
        personalRoot?.takeIf { it.isDirectory && !PathSecurity.isSymbolicLink(it) }?.let { root ->
            for (dir in childDirectories(root)) yield(Source(File(dir, "SKILL.md"), root, null, 200_000, "personal", "user"))
        }
        for ((plugin, file) in AgentPluginContributions.skillFiles(plugins, workDir))
            yield(Source(file, plugin.root, plugin, AgentPluginContributions.MAX_MARKDOWN_BYTES.toInt()))
    }

    private fun childDirectories(base: File): List<File> = try {
        Files.newDirectoryStream(base.toPath()).use { paths ->
            paths.asSequence().take(512).map { it.toFile() }
                .filter { it.isDirectory && !PathSecurity.isSymbolicLink(it) }
                .take(100).sortedBy { it.name }.toList()
        }
    } catch (_: java.io.IOException) { emptyList() }

    private fun read(source: Source, metadataOnly: Boolean): Skill? = readSource(source, metadataOnly)?.let { parse(it, source) }

    private fun readSource(source: Source, metadataOnly: Boolean): String? = try {
        val before = observe(source)
        val raw = openInput(before.path).use { readBounded(it, source.maxBytes, metadataOnly) }
        check(observe(source) == before) { "Skill changed during read" }
        raw
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private fun readBounded(input: InputStream, maxBytes: Int, metadataOnly: Boolean): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(if (metadataOnly) 512 else 8_192)
        var matched = 0
        var headerEnd = -1
        while (true) {
            val offset = output.size()
            val read = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - offset))
            if (read < 0) break
            check(read > 0 && read <= maxBytes - offset) { "Skill exceeds its read limit or made no progress" }
            output.write(buffer, 0, read)
            if (metadataOnly) {
                for (index in 0 until read) {
                    val at = offset + index
                    if (at < 3) {
                        if (buffer[index] != '-'.code.toByte()) return null
                        continue
                    }
                    val value = buffer[index]
                    matched = if (value == HEADER_END[matched]) matched + 1 else if (value == '\n'.code.toByte()) 1 else 0
                    if (matched == HEADER_END.size) { headerEnd = at + 1; break }
                }
                if (headerEnd >= 0) break
            }
        }
        if (metadataOnly && headerEnd < 0) return null
        val bytes = output.toByteArray()
        return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes, 0, if (metadataOnly) headerEnd else bytes.size)).toString()
    }

    private fun observe(source: Source): Observation {
        val root = source.root.canonicalFile
        val file = source.file.canonicalFile
        val parents = parents(root, file)
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(attrs.isRegularFile && attrs.size() in 0..source.maxBytes.toLong()) { "Skill is not an admitted ordinary file" }
        return Observation(file, checkNotNull(attrs.fileKey()), attrs.size(), attrs.lastModifiedTime(), attrs.creationTime(), parents)
    }

    private fun parents(root: File, file: File): List<Any> {
        val base = root.absoluteFile.toPath().normalize()
        val path = file.absoluteFile.toPath().normalize()
        require(path != base && path.startsWith(base)) { "Skill path escapes its source" }
        val result = mutableListOf<Any>()
        var current = base
        val relative = base.relativize(path.parent)
        for (part in listOf("") + relative.map { it.toString() }.filter { it.isNotEmpty() }) {
            if (part.isNotEmpty()) current = current.resolve(part)
            val attrs = Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            check(attrs.isDirectory) { "Skill parent is not an ordinary directory" }
            result += checkNotNull(attrs.fileKey())
        }
        return result
    }

    private fun parse(raw: String, source: Source): Skill? {
        val file = source.file
        val plugin = source.plugin
        if (!raw.startsWith("---")) return null
        val end = raw.indexOf("\n---", 3)
        if (end == -1) return null
        val fm = raw.substring(3, end)
        val body = boundedText(raw.substring(end + 4).trim(), 12_000)
        val declared = NAME.find(fm)?.groupValues?.get(1)?.trim()?.trim('"', '\'')
            ?: if (file.parentFile.name == "commands") file.nameWithoutExtension else file.parentFile.name
        val localName = declared.trim().lowercase().takeIf { it.length <= 256 && VALID_NAME.matches(it) } ?: return null
        val rawDescription = DESCRIPTION.find(fm)?.groupValues?.get(1)?.trim()
        val legacyDescription = rawDescription?.trim('"', '\'') ?: localName
        val hasBlockDescription = DESCRIPTION.findAll(fm).any { BLOCK_DESCRIPTION.matches(it.groupValues[1].trim()) }
        val desc = if (hasBlockDescription) {
            SkillYamlDescription.blockValue(fm + "\n")?.trim() ?: legacyDescription
        } else legacyDescription
        val finalName = if (plugin == null) localName else "${plugin.id}:$localName"
        return Skill(finalName, boundedText(desc, 1_024), body, plugin?.let { "plugin:${it.id}@${it.contentSha256.take(12)}" } ?: source.label)
    }

    private fun boundedText(text: String, limit: Int): String {
        var end = minOf(text.length, limit)
        if (end in 1 until text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return text.substring(0, end)
    }

    companion object {
        internal fun personal(root: File) = SkillManager(root, AgentPluginSource.EMPTY,
            { Files.newInputStream(it.toPath(), NOFOLLOW_LINKS) }, root, includeWorkspace = false)
        private const val MAX_SKILLS = 160
        private val ROOTS = listOf(".droide/skills", ".agents/skills", ".github/skills", ".copilot/skills", ".agent/skills", ".claude/skills", ".opencode/skills")
        private val HEADER_END = byteArrayOf(10, 45, 45, 45)
        private val NAME = Regex("(?im)^\\s*name\\s*:\\s*(.+)$")
        private val BLOCK_DESCRIPTION = Regex("[>|](?:[1-9][+-]?|[+-][1-9]?)?(?:[ \\t]+#.*)?")
        private val DESCRIPTION = Regex("(?im)^\\s*description\\s*:\\s*(.+)$")
        private val VALID_NAME = Regex("^[a-z0-9]+(?:[-_][a-z0-9]+)*$")
    }
}
