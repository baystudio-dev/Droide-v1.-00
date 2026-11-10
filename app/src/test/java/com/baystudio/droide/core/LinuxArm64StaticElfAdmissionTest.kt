package com.baystudio.droide.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LinuxArm64StaticElfAdmissionTest {
    @Test fun reviewedStaticExecutableAndPieAreAccepted() {
        for (type in listOf(2, 3)) checkElf(type = type) { assertEquals("", LinuxArm64ElfAdmission.requireLinuxStatic(it).interpreter) }
    }
    @Test fun staticSharedObjectAndMemoryOnlyEntryAreRejected() {
        for (entry in listOf(0L, 0x2000L, 0x10f0L)) checkElf(entry = entry) { file ->
            try { LinuxArm64ElfAdmission.requireLinuxStatic(file); fail() } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun wrongArchitectureDynamicInterpreterAndEscapedLoadsAreRejected() {
        for (mutate in listOf<(ByteBuffer)->Unit>({ it.putShort(18, 62) }, { it.putInt(64, 3) }, { it.putLong(72, 4000) }, { it.putInt(68, 4) }))
            checkElf(mutate = mutate) { file -> try { LinuxArm64ElfAdmission.requireLinuxStatic(file); fail() } catch (_: IllegalArgumentException) {} }
    }
    private fun checkElf(type: Int = 2, entry: Long = 0x1008, mutate: (ByteBuffer)->Unit = {}, action: (java.io.File)->Unit) {
        val b = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(0x7f, 69, 76, 70, 2, 1, 1)); b.putShort(16, type.toShort()); b.putShort(18, 183)
        b.putInt(20, 1); b.putLong(24, entry); b.putLong(32, 64); b.putShort(52, 64); b.putShort(54, 56); b.putShort(56, 1)
        b.putInt(64, 1); b.putInt(68, 5); b.putLong(72, 128); b.putLong(80, 0x1000); b.putLong(96, 128); b.putLong(104, 256)
        mutate(b)
        val file = Files.createTempFile("droide-static-elf-", ".bin").toFile().apply { writeBytes(b.array()) }
        try { action(file) } finally { file.delete() }
    }
}
