package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.IdentityHashMap

/** One explicit, memory-only analysis choice per opened workspace. Validation never executes a project. */
class CSharpAnalysisSelections(private val workspace: File, private val inspect: (String, String?) -> String) {
    enum class Mode { EXPLICIT_SOLUTION, NATIVE_DIRECTORY_DISCOVERY }
    data class Selection(val rootPath: String, val mode: Mode, val solutionPath: String?,
        val observation: String, val revision: Long = 0) {
        val stableIdentity: String get() = listOf(rootPath, mode.name, solutionPath.orEmpty(), observation, revision.toString()).joinToString("\u0000")
    }
    data class Status(val selection: Selection?, val suggestedRootPath: String, val available: Boolean, val message: String)
    private val staged = IdentityHashMap<Selection, Unit>()
    private var selected: Selection? = null
    private var revision = 0L
    private var closed = false
    private var unavailable: String? = null
    /** All blocking metadata observations are outside the manager/commit lock. */
    fun validate(document: File, rootPath: String, mode: Mode, solutionPath: String?): Selection {
        synchronized(this) { check(!closed) }
        val root = checked(rootPath, directory = true)
        require(contains(root.path, document.absoluteFile.normalize().path)) { "The analysis directory must contain the initiating C# document" }
        val solution = if (mode == Mode.EXPLICIT_SOLUTION) checked(requireNotNull(solutionPath), directory = false).also {
            require(it.extension in setOf("sln", "slnx") && contains(root.path, it.path)) { "Select an existing .sln or .slnx inside the analysis directory" }
        } else { require(solutionPath == null); null }
        val value = Selection(root.path, mode, solution?.path, observation(root, solution))
        synchronized(this) { check(!closed); staged.clear(); staged[value] = Unit }
        return value
    }
    @Synchronized fun commit(candidate: Selection, expected: Selection?): Selection {
        check(!closed && selected === expected && staged.remove(candidate) != null) { "C# selection changed; review and save again" }
        check(revision < Long.MAX_VALUE)
        return candidate.copy(revision = ++revision).also { selected = it; unavailable = null }
    }
    @Synchronized fun current(): Selection? = selected
    @Synchronized fun isCurrent(value: Selection): Boolean = !closed && selected === value && unavailable == null
    fun status(document: File): Status {
        val value = synchronized(this) { selected }
        if (value != null && (!Files.isDirectory(File(value.rootPath).toPath(), NOFOLLOW_LINKS) ||
            value.solutionPath?.let { !Files.isRegularFile(File(it).toPath(), NOFOLLOW_LINKS) } == true)) {
            synchronized(this) { if (selected === value) unavailable = "Selected C# root or solution is unavailable. Revoke remains available; save a new selection before Enable/Retry." }
        }
        return synchronized(this) { statusLocked(document) }
    }
    private fun statusLocked(document: File): Status {
        val value = selected
        return Status(value, value?.rootPath ?: document.absoluteFile.normalize().parent.orEmpty(),
            !closed && value != null && unavailable == null && contains(value.rootPath, document.absoluteFile.normalize().path),
            unavailable ?: when {
                closed -> "C# workspace is closed."
                value == null -> "Select and save an analysis directory and .sln/.slnx, or explicitly choose recursive directory discovery. Enable is separate."
                !contains(value.rootPath, document.absoluteFile.normalize().path) -> "This document is outside the saved C# analysis directory. Review the selection."
                value.mode == Mode.NATIVE_DIRECTORY_DISCOVERY -> "Saved native recursive discovery. Native solution/project discovery runs only after Enable."
                else -> "Saved solution: ${value.solutionPath}. Enable analysis separately."
            })
    }
    fun revalidate(value: Selection): Boolean {
        if (!isCurrent(value)) return false
        val valid = runCatching { observation(checked(value.rootPath, true), value.solutionPath?.let { checked(it, false) }) == value.observation }.getOrDefault(false)
        if (!valid) synchronized(this) { if (selected === value) unavailable = "Selected C# root, solution or SDK observations changed or disappeared. Save and enable again; Revoke remains available." }
        return valid && isCurrent(value)
    }
    @Synchronized fun close() { closed = true; staged.clear(); selected = null; unavailable = null }
    private fun checked(raw: String, directory: Boolean): File {
        require(raw.isNotBlank() && raw.length <= MAX_PATH_CHARS && raw.none { it.isISOControl() || it == '\\' } && raw.split('/').none { it == ".." })
        val file = (if (File(raw).isAbsolute) File(raw) else File(workspace, raw)).absoluteFile.normalize()
        require(contains(workspace.canonicalPath, file.path)) { "Select paths inside the opened workspace" }
        var cursor: File? = file
        while (cursor != null) { require(!Files.isSymbolicLink(cursor.toPath())) { "C# selection cannot traverse symlinks" }; cursor = cursor.parentFile }
        require(file.canonicalFile == file && Files.isReadable(file.toPath()) && if (directory) Files.isDirectory(file.toPath(), NOFOLLOW_LINKS) else Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "Selected C# path is unavailable" }
        return file
    }
    private fun observation(root: File, solution: File?): String {
        val before = Files.readAttributes(root.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val solutionIdentity = solution?.let {
            val a = Files.readAttributes(it.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(a.size() <= MAX_SOLUTION_BYTES)
            val bytes = Files.newInputStream(it.toPath(), NOFOLLOW_LINKS).use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (out.size() <= MAX_SOLUTION_BYTES) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, MAX_SOLUTION_BYTES + 1 - out.size()))
                    if (count < 0) break
                    require(count > 0) { "C# solution read made no progress" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            require(bytes.size <= MAX_SOLUTION_BYTES)
            val b = Files.readAttributes(it.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            check(a.fileKey() == b.fileKey() && a.size() == b.size() && a.lastModifiedTime() == b.lastModifiedTime())
            "${a.fileKey()}:${a.size()}:${a.lastModifiedTime()}:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }
        }.orEmpty()
        val native = inspect(root.path, solution?.path)
        check(checked(root.path, true) == root && Files.readAttributes(root.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() == before.fileKey())
        solution?.let { checked(it.path, false) }
        return "${before.fileKey()}\u0000$solutionIdentity\u0000$native"
    }
    companion object {
        const val MAX_PATH_CHARS = 4096
        const val MAX_SOLUTION_BYTES = 4 * 1024 * 1024
        fun contains(root: String, path: String): Boolean = path == root || path.startsWith(root.trimEnd('/') + "/")
    }
}
