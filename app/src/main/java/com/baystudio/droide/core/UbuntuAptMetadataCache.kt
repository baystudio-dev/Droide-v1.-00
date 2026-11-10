package com.baystudio.droide.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

internal interface UbuntuAptMetadataState {
    fun isFresh(): Boolean
    fun refreshed()
    fun invalidate()
}

/** A successful authenticated update is reused only with unchanged sources, keys and indexes. */
internal class UbuntuAptMetadataCache(
    private val rootfs: File,
    private val stamp: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : UbuntuAptMetadataState {
    private data class Snapshot(val fingerprint: String, val validUntilMs: Long?)

    override fun isFresh(): Boolean = runCatching {
        if (!stamp.isFile || Files.isSymbolicLink(stamp.toPath()) || stamp.length() > 4_096) return@runCatching false
        val saved = Json.parseToJsonElement(stamp.readText()).jsonObject
        val time = saved["updatedAtMs"]?.jsonPrimitive?.longOrNull ?: return@runCatching false
        val now = nowMs()
        if (now < time || now - time >= MAX_AGE_MS) return@runCatching false
        val current = snapshot() ?: return@runCatching false
        saved["schema"]?.jsonPrimitive?.intOrNull == 1 &&
            saved["fingerprint"]?.jsonPrimitive?.contentOrNull == current.fingerprint &&
            (current.validUntilMs == null || now < current.validUntilMs)
    }.getOrDefault(false)

    override fun refreshed() {
        val current = snapshot() ?: return invalidate()
        check(!Files.isSymbolicLink(stamp.toPath())) { "Unsafe APT cache checkpoint" }
        val parent = requireNotNull(stamp.parentFile)
        check(parent.isDirectory && !Files.isSymbolicLink(parent.toPath()))
        val next = File(parent, ".${stamp.name}.next")
        check(!Files.isSymbolicLink(next.toPath()))
        try {
            FileOutputStream(next).use { out ->
                out.write(buildJsonObject {
                    put("schema", 1); put("updatedAtMs", nowMs()); put("fingerprint", current.fingerprint)
                }.toString().toByteArray()); out.fd.sync()
            }
            check(next.renameTo(stamp)) { "Could not commit APT cache checkpoint" }
        } finally { if (next.exists()) next.delete() }
    }

    override fun invalidate() { if (!Files.isSymbolicLink(stamp.toPath())) stamp.delete() }

    private fun snapshot(): Snapshot? {
        val digest = MessageDigest.getInstance("SHA-256")
        var files = 0; var bytes = 0L
        val captured = mutableSetOf<String>()
        val sources = mutableListOf<String>()
        fun add(path: String): Boolean {
            val node = File(rootfs, path)
            if (Files.isSymbolicLink(node.toPath()) || !node.canonicalFile.toPath().startsWith(rootfs.canonicalFile.toPath())) return false
            val entries = if (node.isDirectory) node.listFiles()?.sortedBy { it.name } ?: return false
                else if (node.isFile) listOf(node) else emptyList()
            for (file in entries) {
                if (++files > 256 || !file.isFile || Files.isSymbolicLink(file.toPath())) return false
                bytes += file.length()
                if (bytes > 4_000_000 || file.length() > 1_000_000) return false
                val relative = file.relativeTo(rootfs).path
                val content = file.readBytes()
                captured += "/$relative"
                if (relative == "etc/apt/sources.list" || relative.startsWith("etc/apt/sources.list.d/")) sources += content.toString(Charsets.UTF_8)
                digest.update(relative.toByteArray()); digest.update(0)
                digest.update(content); digest.update(0)
            }
            return true
        }
        for (path in listOf("etc/apt/sources.list", "etc/apt/sources.list.d", "etc/apt/apt.conf", "etc/apt/apt.conf.d",
            "etc/apt/trusted.gpg", "etc/apt/trusted.gpg.d", "etc/apt/keyrings", "usr/share/keyrings")) if (!add(path)) return null
        // Keep APT doing its normal refresh in that case instead of reusing a checkpoint that cannot
        // detect the custom key changing.
        for (source in sources) for (setting in Regex("(?im)\\bsigned-by\\s*[=:]\\s*([^\\r\\n]+)").findAll(source)) {
            for (path in Regex("/[^\\s,\\]\\[\\\"']+").findAll(setting.groupValues[1].substringBefore(']'))) {
                if (path.value !in captured) return null
            }
        }
        val lists = File(rootfs, "var/lib/apt/lists")
        if (Files.isSymbolicLink(lists.toPath()) || !lists.canonicalFile.toPath().startsWith(rootfs.canonicalFile.toPath())) return null
        val indexes = lists.listFiles()?.filter { it.name !in setOf("lock", "partial", "auxfiles") }?.sortedBy { it.name } ?: return null
        if (indexes.size > 256 || indexes.none { it.name.endsWith("InRelease") } ||
            indexes.none { it.name.contains("_Packages") }) return null
        var earliest: Long? = null
        for (index in indexes) {
            if (!index.isFile || Files.isSymbolicLink(index.toPath()) || index.length() == 0L) return null
            digest.update("${index.name}:${index.length()}:${index.lastModified()}\n".toByteArray())
            if (index.name.endsWith("InRelease")) {
                val header = index.inputStream().use { stream ->
                    val buffer = ByteArray(16_384); var used = 0
                    while (used < buffer.size) { val count = stream.read(buffer, used, buffer.size - used); if (count < 0) break; used += count }
                    String(buffer, 0, used, Charsets.UTF_8)
                }
                val expiry = Regex("(?m)^Valid-Until:\\s*(.+)$").find(header)?.groupValues?.get(1)
                if (expiry != null) {
                    val parsed = runCatching { ZonedDateTime.parse(expiry.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() ?: return null
                    earliest = earliest?.let { minOf(it, parsed) } ?: parsed
                }
            }
        }
        return Snapshot(digest.digest().joinToString("") { "%02x".format(it) }, earliest)
    }

    companion object { const val MAX_AGE_MS = 6L * 60L * 60L * 1_000L }
}
