package com.baystudio.droide.core

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Execution is argv-only and permission-gated by AgentTools.
data class CustomToolDef(val name: String, val description: String, val script: File)

class CustomToolManager internal constructor(private val workDir: File, private val openMetadata: (File) -> InputStream) {
    constructor(workDir: File) : this(workDir, { Files.newInputStream(it.toPath(), NOFOLLOW_LINKS) })
    fun discover(): List<CustomToolDef> {
        val base = runCatching { PathSecurity.resolveWithin(workDir, ".droide/tools") }.getOrElse { return emptyList() }
        if (!base.isDirectory) return emptyList()
        val files = base.listFiles()
            ?.asSequence()
            ?.filter {
                it.extension in setOf("sh", "py", "js") && it.isFile && it.length() in 1..MAX_SCRIPT_BYTES &&
                    !PathSecurity.isSymbolicLink(it) && PathSecurity.contains(workDir, it)
            }
            ?.take(50)?.toList().orEmpty()
        fun legacyName(file: File) = file.nameWithoutExtension
            .replace(Regex("[^A-Za-z0-9_-]"), "_").take(56).ifBlank { "tool" }
        val groups = files.groupBy(::legacyName)
        // Reserve even ambiguous legacy IDs: a generated name must not shadow another script.
        val used = groups.keys.toMutableSet()
        val names = mutableMapOf<File, String>()
        files.sortedBy { it.name }.forEach { file ->
            val legacy = legacyName(file)
            if (groups.getValue(legacy).size == 1) names[file] = legacy
            else {
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(file.name.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }.take(12)
                // Counter makes collisions resolvable even against deliberately chosen legacy names.
                // The finite occupied-name set bounds the counter search.
                val name = (0..used.size).asSequence().map { counter ->
                    val suffix = "_${digest}_$counter"
                    legacy.take(56 - suffix.length) + suffix
                }.first { it !in used }
                used.add(name)
                names[file] = name
            }
        }
        return files.map { file -> CustomToolDef(names.getValue(file), description(file), file) }
    }

    private fun description(file: File): String {
        val fallback = "Custom tool ${file.nameWithoutExtension}"
        return try {
            openMetadata(file).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(512)
                var prefixKnown = false
                while (output.size() <= MAX_DESCRIPTION_BYTES) {
                    val requested = minOf(buffer.size, MAX_DESCRIPTION_BYTES + 1 - output.size())
                    val count = input.read(buffer, 0, requested)
                    if (count < 0) return parseDescription(output.toByteArray(), fallback)
                    check(count in 1..requested) { "Metadata read made no progress" }
                    val end = (0 until count).firstOrNull { buffer[it] == 10.toByte() || buffer[it] == 13.toByte() }
                    if (end != null) {
                        output.write(buffer, 0, end)
                        return parseDescription(output.toByteArray(), fallback)
                    }
                    output.write(buffer, 0, count)
                    if (!prefixKnown && output.size() >= DESCRIPTION_PREFIX.size) {
                        val bytes = output.toByteArray()
                        if (DESCRIPTION_PREFIX.indices.any { bytes[it] != DESCRIPTION_PREFIX[it] }) return fallback
                        prefixKnown = true
                    }
                }
                descriptionPrefix("[Description omitted: first line exceeds $MAX_DESCRIPTION_BYTES bytes] $fallback")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { fallback }
    }

    private fun parseDescription(bytes: ByteArray, fallback: String): String {
        val first = String(bytes, Charsets.UTF_8)
        return descriptionPrefix(if (first.startsWith("# desc:")) first.removePrefix("# desc:").trim() else fallback)
    }

    private fun descriptionPrefix(value: String): String {
        var end = minOf(value.length, 300)
        if (end in 1 until value.length && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
        return value.substring(0, end)
    }

    // Preserve the script input contract exactly; launch failures belong to execArgv,
    // never silently substitute a truncated argument before execution.
    fun argv(name: String, input: String): List<String>? {
        val def = discover().firstOrNull { it.name == name } ?: return null
        return argv(def, input)
    }

    private fun argv(def: CustomToolDef, input: String): List<String> {
        val relative = def.script.canonicalFile.relativeTo(workDir.canonicalFile).invariantSeparatorsPath
        return when (def.script.extension) {
            "py" -> listOf("python3", relative, input)
            "js" -> listOf("node", relative, input)
            else -> listOf("sh", relative, input)
        }
    }

    fun permissionFingerprint(name: String): String? = discover().firstOrNull { it.name == name }?.let(::fingerprint)

    suspend fun execute(
        name: String,
        input: String,
        terminal: ITerminalSession? = null,
        expectedFingerprint: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val def = discover().firstOrNull { it.name == name } ?: return@withContext "ERROR: custom tool not found: $name"
        expectedFingerprint?.let { expected ->
            require(fingerprint(def) == expected) {
                "Custom tool changed after approval: $name. Review the current script before running it."
            }
        }
        // Do not resolve the name again after checking this definition's fingerprint.
        val argv = argv(def, input)
        val execution = terminal
            ?: return@withContext "ERROR: custom tool execution requires the approved workspace execution boundary"
        val result = execution.execArgv(argv, timeoutMs = AgentTimeoutPolicy.PLUGIN_TOOL_MS)
        run {
            val notice = if (result.outputIncomplete)
                "[DROIDE_TOOL_OUTPUT_INCOMPLETE capture_complete=false]\n" +
                    "Output capture is incomplete; omitted content is unknown. Do not repeat a side-effecting operation just to recover its output.\n"
                else ""
            if (result.timedOut) {
                if (result.output.isEmpty() && !result.outputIncomplete) "(timeout)"
                else "[TIMEOUT]\n$notice${result.output}"
            } else "exit=${result.exitCode}\n$notice${result.output}"
        }
    }
    private fun fingerprint(def: CustomToolDef): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(def.script.canonicalPath.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        def.script.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_SCRIPT_BYTES = 2L * 1024L * 1024L
        const val MAX_DESCRIPTION_BYTES = 8_192
        val DESCRIPTION_PREFIX = "# desc:".toByteArray(Charsets.UTF_8)
    }

}
