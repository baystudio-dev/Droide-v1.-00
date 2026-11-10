package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded, no-follow reads. Reject replaced parents/files before returning any resource bytes. */
internal class SkillBundleReader(root: File, directory: File, private val validateSource: () -> Unit = {}) {
    private val anchor = root.toPath().toAbsolutePath().normalize()
    private val base = directory.toPath().toAbsolutePath().normalize()
    private val originalParents = parents(base)
    private data class Version(val key: Any, val bytes: Long, val modified: FileTime, val created: FileTime)

    private fun parents(directory: Path): List<Any> {
        require(directory.startsWith(anchor)) { "Skill resources escape their source" }
        var current = anchor
        val keys = mutableListOf<Any>()
        for (part in listOf("") + anchor.relativize(directory).map { it.toString() }.filter { it.isNotEmpty() }) {
            if (part.isNotEmpty()) current = current.resolve(part)
            val attrs = Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attrs.isDirectory) { "Skill resource parent is not an ordinary directory" }
            keys += checkNotNull(attrs.fileKey())
        }
        return keys
    }

    private fun checkBase() {
        validateSource()
        check(parents(base) == originalParents) { "Skill folder changed; refresh before reading resources" }
    }

    suspend fun paths(): List<String> {
        checkBase()
        val result = mutableListOf<String>()
        var entries = 0
        var inspected = 0
        suspend fun visit(directory: Path, depth: Int) {
            currentCoroutineContext().ensureActive()
            require(depth <= 8) { "Skill resource nesting exceeds its limit" }
            val before = parents(directory)
            Files.newDirectoryStream(directory).use { children ->
                for (child in children) {
                    currentCoroutineContext().ensureActive()
                    require(++inspected <= 512) { "Skill directory contains too many entries" }
                    val relative = base.relativize(child).joinToString("/")
                    if (AgentWorkspacePathPolicy.internalArtifact(relative)) continue
                    require(++entries <= SkillManagement.MAX_FILES * 2) { "Skill resources exceed their entry limit" }
                    val attrs = Files.readAttributes(child, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    if (attrs.isDirectory) visit(child, depth + 1)
                    else {
                        require(attrs.isRegularFile && result.size < SkillManagement.MAX_FILES) { "Skill contains unsupported resources or symbolic links" }
                        result += SkillManagement.resourcePath(relative)
                    }
                }
            }
            check(parents(directory) == before) { "Skill resource directory changed during listing" }
        }
        visit(base, 0)
        checkBase()
        return result.sorted()
    }

    suspend fun read(relative: String, limit: Int): ByteArray {
        currentCoroutineContext().ensureActive()
        checkBase()
        val path = base.resolve(SkillManagement.resourcePath(relative))
        val beforeParents = parents(path.parent)
        fun version(): Version {
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attrs.isRegularFile && attrs.size() in 0..limit.toLong()) { "Skill resource is not a bounded ordinary file" }
            return Version(checkNotNull(attrs.fileKey()), attrs.size(), attrs.lastModifiedTime(), attrs.creationTime())
        }
        val before = version()
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { SkillManagement.boundedBytes(it, limit) }
        currentCoroutineContext().ensureActive()
        check(version() == before && parents(path.parent) == beforeParents) { "Skill resource changed during read" }
        checkBase()
        return bytes
    }
}
