package com.baystudio.droide.core

import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Serialized across backend instances; publish only after an atomic, synced file replacement. */
internal class UserProviderStore(private val file: File) {
    fun load(): List<UserProviderDefinition> = synchronized(gate) { read().also(::publish) }

    fun save(value: UserProviderDefinition, expected: UserProviderDefinition?): UserProviderDefinition = synchronized(gate) {
        val normalized = value.normalized()
        val current = read()
        require(expected == null || expected.id == normalized.id) { "Provider ID cannot change while editing." }
        check(current.find { it.id == normalized.id } == expected) { "Provider already exists or was changed. Reopen it before saving." }
        val next = current.filterNot { it.id == normalized.id } + normalized
        write(next)
        normalized
    }

    fun remove(expected: UserProviderDefinition) = synchronized(gate) {
        val current = read()
        check(current.find { it.id == expected.id } == expected) { "Provider changed. Reopen it before removing." }
        write(current.filterNot { it.id == expected.id })
    }

    private fun read(): List<UserProviderDefinition> {
        if (!file.exists()) return emptyList()
        require(file.isFile && file.length() <= UserProviderCodec.MAX_BYTES) { "Custom provider settings are unreadable or too large." }
        return file.inputStream().use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= UserProviderCodec.MAX_BYTES) { "Custom provider settings are too large." }
                bytes.write(buffer, 0, count)
            }
            UserProviderCodec.decode(bytes.toString("UTF-8"))
        }
    }

    private fun write(values: List<UserProviderDefinition>) {
        val encoded = UserProviderCodec.encode(values)
        val parent = checkNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create custom provider settings directory." }
        val temporary = File.createTempFile(".providers-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { it.write(encoded.toByteArray()); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            publish(values)
        } finally {
            temporary.delete()
        }
    }

    private fun publish(values: List<UserProviderDefinition>) = ProviderRegistry.installUserConfigured(values)

    companion object { private val gate = Any() }
}
