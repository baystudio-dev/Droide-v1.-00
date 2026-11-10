package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.json.*

/** Client grant/selection policy, not a boundary on upstream ancestor build evaluation. */
internal object LspZlsProfile {
    data class Hint(val path: String?, val version: String?, val problem: String?) {
        val usable: Boolean get() = problem == null
        fun identity(): JsonObject = buildJsonObject {
            put("state", if (problem != null) "invalid" else if (version != null) "exact" else "absent")
            path?.let { put("path", it) }; version?.let { put("version", it) }; problem?.let { put("problem", it) }
        }
    }
    private val stable = Regex("0|[1-9][0-9]{0,8}")
    private fun stableVersion(value: String): Boolean = value.split('.').let { parts ->
        parts.size == 3 && parts.all { stable.matches(it) }
    }

    /** Invalid .zigversion is a selection barrier, even though it is not a valid marker. */
    fun projectMarker(directory: File): Boolean =
        hintPresent(File(directory, ".zigversion")) ||
            listOf("build.zig", "build.zig.zon", "zls.build.json").any {
                Files.isRegularFile(File(directory, it).toPath(), NOFOLLOW_LINKS)
            } || listOf(".git", ".hg", ".svn", ".jj").any {
                Files.isDirectory(File(directory, it).toPath(), NOFOLLOW_LINKS)
            }

    fun hint(workspace: File, project: File): Hint {
        val boundary = workspace.canonicalFile
        var current = project.canonicalFile
        if (!PathSecurity.contains(boundary, current)) return Hint(null, null, "Zig project escaped the workspace")
        while (PathSecurity.contains(boundary, current)) {
            val file = File(current, ".zigversion")
            if (hintPresent(file)) return readHint(file)
            if (current == boundary) break
            current = current.parentFile ?: break
        }
        return Hint(null, null, null)
    }

    private fun hintPresent(file: File): Boolean = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS); true
    } catch (_: NoSuchFileException) { false } catch (_: Exception) { true }

    private fun readHint(file: File): Hint {
        fun invalid(reason: String) = Hint(file.path, null, "Invalid .zigversion: $reason")
        return try {
            if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) return invalid("use a regular non-symlink file")
            if (Files.size(file.toPath()) !in 1L..128L) return invalid("expected 1–128 bytes")
            val bytes = ByteArray(129)
            val count = Files.newByteChannel(file.toPath(), setOf(StandardOpenOption.READ, NOFOLLOW_LINKS)).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
                buffer.position()
            }
            if (count !in 1..128) return invalid("expected 1–128 bytes")
            val decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, count)).toString()
            val version = decoded.trim()
            if (!stableVersion(version)) return invalid("expected one stable major.minor.patch version")
            if (!version.startsWith("0.16.")) return invalid("ZLS 0.16.0 requires a stable Zig 0.16.x compiler")
            Hint(file.path, version, null)
        } catch (_: Exception) { invalid("file is unreadable or changed while reading") }
    }

    fun initializationOptions(): JsonObject = buildJsonObject {
        put("enable_build_on_save", true)
        put("build_on_save_args", buildJsonArray { add("--check-only") })
        put("force_autofix", false)
    }
}
