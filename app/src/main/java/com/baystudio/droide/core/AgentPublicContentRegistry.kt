package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Private metadata, never stored in project files. Locators are not bearer capabilities. */
internal class AgentPublicContentRegistry(private val root: File) {
    @Serializable private data class Record(val session: String, val binding: String,
        val reference: AgentPublicContentRef, val abort: PublicAnswerAbortCheckpoint? = null)
    private val json = Json { encodeDefaults = true }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun binding(m: AgentMessage) = hash(listOf(m.role, m.content, m.presentationId, m.streamPart?.let { json.encodeToString(AgentStreamPart.serializer(), it) }.orEmpty()).joinToString("") { "${it.length}:$it" })
    private fun path(id: String): File {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid public content locator" }
        ChatMediaStoragePaths.requireNoLinks(root)
        Files.createDirectories(root.toPath())
        return File(root, "$id.json").also { ChatMediaStoragePaths.requireNoLinks(it) }
    }
    fun publish(session: String, m: AgentMessage): AgentMessage {
        val ref = m.publicContent ?: run { require(m.publicContentId == null); return m }
        require(m.publicAbort == null || m.publicAbort.reference == ref)
        val record = Record(session, binding(m), ref, m.publicAbort)
        val encoded = json.encodeToString(Record.serializer(), record)
        val id = hash(encoded)
        val destination = path(id)
        if (destination.exists()) {
            require(read(id) == record) { "Public content registry conflict" }
        } else {
            val tmp = Files.createTempFile(root.toPath(), ".pending-", ".json").toFile()
            try {
                java.io.FileOutputStream(tmp).use { it.write(encoded.toByteArray(Charsets.UTF_8)); it.fd.sync() }
                Files.move(tmp.toPath(), destination.toPath(), ATOMIC_MOVE)
            } finally { tmp.delete() }
        }
        return m.copy(publicContentId = id)
    }
    private fun read(id: String): Record = Files.newInputStream(path(id).toPath(), NOFOLLOW_LINKS).use {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (output.size() <= 16384) {
            val count = it.read(buffer, 0, minOf(buffer.size, 16385 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        require(bytes.size <= 16384) { "Invalid public registry metadata size" }
        json.decodeFromString(Record.serializer(), bytes.toString(Charsets.UTF_8))
    }
    fun restore(session: String, m: AgentMessage): AgentMessage {
        val id = m.publicContentId ?: return m
        val record = read(id)
        require(record.session == session && record.binding == binding(m)) { "Public content locator ownership mismatch" }
        require(m.role == "assistant")
        return m.copy(publicContent = record.reference, publicAbort = record.abort)
    }
}
