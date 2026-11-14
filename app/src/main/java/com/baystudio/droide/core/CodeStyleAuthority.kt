package com.baystudio.droide.core

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.abs

 
enum class IndentStyle { SPACES, TABS }

data class CodeStyleDefaults(
    val detectIndentation: Boolean = true,
    val indentStyle: IndentStyle = IndentStyle.SPACES,
    val tabWidth: Int = 4,
    val indentSize: Int = 4,
    val continuationIndent: Int = 8,
) {
    fun normalized(): CodeStyleDefaults = copy(
        tabWidth = tabWidth.coerceIn(1, 8),
        indentSize = indentSize.coerceIn(1, 8),
        continuationIndent = continuationIndent.coerceIn(1, 16),
    )
}

data class CodeStyleProfile(
    val indentStyle: IndentStyle,
    val tabWidth: Int,
    val indentSize: Int,
    val continuationIndent: Int,
    val source: String,
) {
    val useTabs: Boolean get() = indentStyle == IndentStyle.TABS
    val indentUnit: String get() = if (useTabs) "\t" else " ".repeat(indentSize.coerceIn(1, 8))
    val continuationUnit: String get() = if (useTabs) "\t".repeat(((continuationIndent + tabWidth - 1) / tabWidth).coerceIn(1, 4)) else " ".repeat(continuationIndent.coerceIn(1, 16))
    val label: String get() = if (useTabs) "Tabs:$tabWidth" else "Spaces:$indentSize"
}








class CodeStyleAuthority(private val projectRoot: File) {
    companion object {
        const val MAX_EDITORCONFIG_BYTES = 64 * 1024L
        private const val MAX_SECTIONS = 96
        private const val MAX_CONFIG_FILES = 32
        private const val MAX_ANCESTOR_DIRECTORIES = 256
        private const val DETECTION_LINES = 240

        fun detect(content: String): Pair<IndentStyle, Int>? {
            var tabLines = 0
            val spaceWidths = mutableListOf<Int>()
            content.lineSequence().take(DETECTION_LINES).forEach { line ->
                if (line.isBlank()) return@forEach
                var tabs = 0
                var spaces = 0
                for (c in line) {
                    when (c) {
                        '\t' -> if (spaces == 0) tabs++ else break
                        ' ' -> if (tabs == 0) spaces++ else break
                        else -> break
                    }
                }
                if (tabs > 0) tabLines++
                if (spaces in 1..16) spaceWidths += spaces
            }
            if (tabLines == 0 && spaceWidths.isEmpty()) return null
            if (tabLines > spaceWidths.size) return IndentStyle.TABS to 4
            if (spaceWidths.isEmpty()) return IndentStyle.TABS to 4
            val candidate = spaceWidths.reduce(::gcd).takeIf { it in 2..8 }
                ?: listOf(2, 4, 8).minBy { size -> spaceWidths.sumOf { abs(it % size) } }
            return IndentStyle.SPACES to candidate.coerceIn(1, 8)
        }

        private fun gcd(a: Int, b: Int): Int {
            var x = abs(a); var y = abs(b)
            while (y != 0) { val t = x % y; x = y; y = t }
            return x.coerceAtLeast(1)
        }
    }

    fun resolve(relativePath: String, languageId: String, content: String, defaults: CodeStyleDefaults): CodeStyleProfile {
        val base = defaults.normalized()
        var style = base.indentStyle
        var tabWidth = base.tabWidth
        var indentSize = base.indentSize
        var continuation = base.continuationIndent
        var source = "User default"

        val config = resolveEditorConfig(relativePath)
        if (config != null) {
            config["tab_width"]?.toIntOrNull()?.let { tabWidth = it.coerceIn(1, 8) }
            when (config["indent_style"]?.lowercase()) {
                "tab" -> style = IndentStyle.TABS
                "space" -> style = IndentStyle.SPACES
            }
            config["indent_size"]?.let { raw ->
                indentSize = if (raw.equals("tab", true)) tabWidth else raw.toIntOrNull()?.coerceIn(1, 8) ?: indentSize
            }
            if (base.detectIndentation && "indent_style" !in config && "indent_size" !in config) {
                detect(content)?.let { (detectedStyle, detectedWidth) ->
                    style = detectedStyle
                    if (detectedStyle == IndentStyle.SPACES) indentSize = detectedWidth else if ("tab_width" !in config) tabWidth = detectedWidth
                }
            }
            (config["ij_${languageId.lowercase()}_continuation_indent_size"]
                ?: config["ij_continuation_indent_size"])
                ?.toIntOrNull()?.let { continuation = it.coerceIn(1, 16) }
            source = ".editorconfig"
        } else if (base.detectIndentation) {
            detect(content)?.let { (detectedStyle, detectedWidth) ->
                style = detectedStyle
                if (detectedStyle == IndentStyle.SPACES) indentSize = detectedWidth else tabWidth = detectedWidth
                continuation = (if (detectedStyle == IndentStyle.SPACES) indentSize else tabWidth).times(2).coerceIn(1, 16)
                source = "Detected from file"
            }
        }

        

        if (config == null && source == "User default" && languageId.lowercase() in setOf("go", "makefile")) {
            style = IndentStyle.TABS
            tabWidth = base.tabWidth
            indentSize = base.indentSize
            continuation = (tabWidth * 2).coerceIn(1, 16)
            source = "Language default"
        }

        return CodeStyleProfile(style, tabWidth, indentSize, continuation, source)
    }

    private fun resolveEditorConfig(relativePath: String): Map<String, String>? {
        val root = try { projectRoot.canonicalFile } catch (_: IOException) { return null }
            catch (_: SecurityException) { return null }
        val target = try { PathSecurity.resolveWithin(root, relativePath) } catch (_: Exception) { return null }
        val configs = mutableListOf<Pair<File, Parsed>>()
        var dir = target.parentFile
        var visited = 0
        while (dir != null && dir.toPath().startsWith(root.toPath()) &&
            configs.size < MAX_CONFIG_FILES && visited++ < MAX_ANCESTOR_DIRECTORIES) {
            val candidate = File(dir, ".editorconfig")
            val checked = try {
                PathSecurity.resolveWithin(root, root.toPath().relativize(candidate.toPath()).toString())
            } catch (_: Exception) { null }
            val parsed = checked?.let(::parse)
            if (checked != null && parsed != null) {
                configs += checked to parsed
                if (parsed.root) break
            }
            if (dir == root) break
            dir = dir.parentFile
        }
        if (configs.isEmpty()) return null

        val result = linkedMapOf<String, String>()
        var tabWidthUnset = false
        val matchBudget = EditorConfigGlob.Budget()
        configs.asReversed().forEach { (file, parsed) ->
            val configDirectory = file.parentFile ?: return@forEach
            val relFromConfig = configDirectory.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/')
            parsed.sections.filter { EditorConfigGlob.matches(it.pattern, relFromConfig, matchBudget) }.forEach { section ->
                section.values.forEach { (key, value) ->
                    if (key == "tab_width") tabWidthUnset = value.equals("unset", true)
                    if (value.equals("unset", true)) result.remove(key) else result[key] = value
                }
            }
        }
        // EditorConfig derives an unspecified tab width from numeric indent_size.
        // Resolve after inheritance; explicit/unset tab_width must retain user intent.
        if ("tab_width" !in result && !tabWidthUnset) {
            result["indent_size"]?.takeIf { it.toIntOrNull() != null }?.let { result["tab_width"] = it }
        }
        return result.takeIf { it.keys.any { key -> key in setOf("indent_style", "indent_size", "tab_width", "ij_continuation_indent_size") || key.startsWith("ij_") } }
    }

    private data class Section(val pattern: String, val values: Map<String, String>)
    private data class Parsed(val root: Boolean, val sections: List<Section>)

    private fun parse(file: File): Parsed? {
        // A project file can disappear or grow after stat. Never let optional style
        // configuration crash composition or allocate an unbounded input line.
        val text = try {
            if (!file.isFile || file.length() !in 1..MAX_EDITORCONFIG_BYTES) return null
            file.inputStream().use { input ->
                val output = ByteArrayOutputStream(file.length().coerceAtMost(MAX_EDITORCONFIG_BYTES).toInt())
                val buffer = ByteArray(4_096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > MAX_EDITORCONFIG_BYTES) return null
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        var root = false
        val sections = mutableListOf<Section>()
        var currentPattern: String? = null
        var current = linkedMapOf<String, String>()
        fun flush() {
            val p = currentPattern ?: return
            if (sections.size < MAX_SECTIONS && p.length in 1..EditorConfigGlob.MAX_PATTERN_CHARS) {
                sections += Section(p, current.toMap())
            }
            current = linkedMapOf()
        }
        text.lineSequence().take(2_000).forEach { raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith('#') || line.startsWith(';')) return@forEach
            if (line.startsWith('[') && line.endsWith(']')) {
                flush(); currentPattern = line.substring(1, line.length - 1).trim(); return@forEach
            }
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()
            if (currentPattern == null && key == "root") root = value.equals("true", true)
            else if (currentPattern != null && key.length <= 96 && value.length <= 128) current[key] = value
        }
        flush()
        return Parsed(root, sections)
    }
}

object CodeStyleSnippets {
     
    fun normalize(source: String, profile: CodeStyleProfile): String {
        if (!source.contains('\n')) return source
        val lines = source.split('\n')
        val widths = lines.drop(1).mapNotNull { line ->
            if (line.isBlank()) null else line.takeWhile { it == ' ' }.length.takeIf { it > 0 }
        }
        val sourceUnit = widths.minOrNull()?.coerceAtLeast(1) ?: return source
        return lines.mapIndexed { index, line ->
            if (index == 0) line else {
                val leading = line.takeWhile { it == ' ' }.length
                if (leading == 0) line else profile.indentUnit.repeat((leading / sourceUnit).coerceAtLeast(1)) + line.drop(leading)
            }
        }.joinToString("\n")
    }
}
