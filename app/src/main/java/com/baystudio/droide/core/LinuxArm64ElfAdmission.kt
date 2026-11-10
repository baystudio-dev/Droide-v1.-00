package com.baystudio.droide.core

import java.io.File
import java.io.RandomAccessFile

// Fail closed when required state cannot be verified.
object LinuxArm64ElfAdmission {
    data class Descriptor(
        val type: Int,
        val machine: Int,
        val interpreter: String,
    )

    private const val ELF64_HEADER_BYTES = 64
    private const val ELF64_PROGRAM_HEADER_BYTES = 56
    private const val PT_INTERP = 3L
    private const val EM_AARCH64 = 183
    private const val ET_EXEC = 2
    private const val ET_DYN = 3
    const val AARCH64_GLIBC_INTERPRETER = "/lib/ld-linux-aarch64.so.1"

    /** Separate admission for reviewed static publisher executables, never for shared objects. */
    fun requireLinuxStatic(file: File): Descriptor {
        require(file.isFile && !PathSecurity.isSymbolicLink(file)) { "Static payload is not a regular file" }
        RandomAccessFile(file, "r").use { raf ->
            require(raf.length() >= ELF64_HEADER_BYTES)
            val header = ByteArray(ELF64_HEADER_BYTES).also(raf::readFully)
            require(header.copyOfRange(0, 7).contentEquals(byteArrayOf(0x7f, 69, 76, 70, 2, 1, 1))) { "Static payload is not little-endian ELF64" }
            val type = u16(header, 16)
            require(type in setOf(ET_EXEC, ET_DYN) && u16(header, 18) == EM_AARCH64) { "Static payload is not AArch64 executable ELF" }
            require(u32(header, 20) == 1L && u16(header, 52) == ELF64_HEADER_BYTES)
            val entry = u64(header, 24)
            val offset = u64(header, 32)
            val size = u16(header, 54)
            val count = u16(header, 56)
            require(entry > 0 && size == ELF64_PROGRAM_HEADER_BYTES && count in 1..512)
            require(offset >= ELF64_HEADER_BYTES && offset <= raf.length() &&
                Math.multiplyExact(size.toLong(), count.toLong()) <= raf.length() - offset)
            var executableEntry = false
            for (index in 0 until count) {
                raf.seek(offset + index.toLong() * size)
                val ph = ByteArray(ELF64_PROGRAM_HEADER_BYTES).also(raf::readFully)
                val kind = u32(ph, 0)
                require(kind != PT_INTERP) { "Static admission rejects dynamic interpreters" }
                if (kind != 1L) continue
                val fileOffset = u64(ph, 8)
                val address = u64(ph, 16)
                val fileBytes = u64(ph, 32)
                val memoryBytes = u64(ph, 40)
                require(fileBytes <= memoryBytes && fileOffset <= raf.length() && fileBytes <= raf.length() - fileOffset)
                require(address <= Long.MAX_VALUE - memoryBytes)
                if (u32(ph, 4) and 1L != 0L && entry >= address && entry - address < fileBytes) executableEntry = true
            }
            require(executableEntry) { "Static ELF entry point is outside executable file-backed load segments" }
            return Descriptor(type, EM_AARCH64, "")
        }
    }

    /** Native extensions are shared objects, never admitted as static executables. */
    fun requireAarch64SharedObject(file: File): Descriptor {
        require(file.isFile && !PathSecurity.isSymbolicLink(file)) { "Native library is not a regular file" }
        RandomAccessFile(file, "r").use { raf ->
            require(raf.length() >= ELF64_HEADER_BYTES) { "Native library has a truncated ELF header" }
            val header = ByteArray(ELF64_HEADER_BYTES).also(raf::readFully)
            require(header.copyOfRange(0, 7).contentEquals(byteArrayOf(0x7f, 69, 76, 70, 2, 1, 1))) { "Native library is not little-endian ELF64" }
            require(u16(header, 16) == ET_DYN && u16(header, 18) == EM_AARCH64 && u64(header, 24) == 0L) {
                "Native library is not an AArch64 shared object"
            }
            require(u32(header, 20) == 1L && u16(header, 52) == ELF64_HEADER_BYTES)
            val offset = u64(header, 32); val size = u16(header, 54); val count = u16(header, 56)
            require(size == ELF64_PROGRAM_HEADER_BYTES && count in 1..512 && offset >= ELF64_HEADER_BYTES &&
                offset <= raf.length() && size.toLong() * count <= raf.length() - offset) { "Native library has an invalid ELF program table" }
            var executable = false
            for (index in 0 until count) {
                raf.seek(offset + index.toLong() * size)
                val ph = ByteArray(ELF64_PROGRAM_HEADER_BYTES).also(raf::readFully)
                val kind = u32(ph, 0)
                require(kind != PT_INTERP) { "Native library must not declare an executable interpreter" }
                if (kind != 1L) continue
                val fileOffset = u64(ph, 8); val address = u64(ph, 16)
                val fileBytes = u64(ph, 32); val memoryBytes = u64(ph, 40)
                require(fileBytes <= memoryBytes && fileOffset <= raf.length() && fileBytes <= raf.length() - fileOffset &&
                    address <= Long.MAX_VALUE - memoryBytes) { "Native library load segment escapes the file" }
                val alignment = u64(ph, 48)
                require(alignment <= 1L || alignment and (alignment - 1L) == 0L) { "Native library load alignment is invalid" }
                if (alignment > 1L) require(fileOffset % alignment == address % alignment) { "Native library load segment is misaligned" }
                if (u32(ph, 4) and 1L != 0L && fileBytes > 0L) executable = true
            }
            require(executable) { "Native library has no executable file-backed load segment" }
            return Descriptor(ET_DYN, EM_AARCH64, "")
        }
    }

    fun requireUbuntuGlibc(file: File): Descriptor {
        require(file.isFile && !PathSecurity.isSymbolicLink(file)) { "Reviewed payload is not a regular file" }
        RandomAccessFile(file, "r").use { raf ->
            require(raf.length() >= ELF64_HEADER_BYTES) { "Reviewed payload is too small to be ELF64" }
            val header = ByteArray(ELF64_HEADER_BYTES)
            raf.readFully(header)
            require(header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()) {
                "Reviewed payload is not ELF"
            }
            require(header[4].toInt() and 0xff == 2) { "Reviewed payload is not ELF64" }
            require(header[5].toInt() and 0xff == 1) { "Reviewed payload is not little-endian ELF" }
            require(header[6].toInt() and 0xff == 1) { "Reviewed payload has an unsupported ELF version" }

            val type = u16(header, 16)
            val machine = u16(header, 18)
            require(type == ET_EXEC || type == ET_DYN) { "Reviewed payload is not an executable ELF" }
            require(machine == EM_AARCH64) { "Reviewed payload is not Linux AArch64 ELF" }

            val phoff = u64(header, 32)
            val phentsize = u16(header, 54)
            val phnum = u16(header, 56)
            require(phentsize >= ELF64_PROGRAM_HEADER_BYTES) { "Reviewed ELF program-header size is invalid" }
            require(phnum in 1..512) { "Reviewed ELF program-header count is invalid" }
            val tableBytes = Math.multiplyExact(phentsize.toLong(), phnum.toLong())
            require(phoff >= ELF64_HEADER_BYTES && phoff <= raf.length() && tableBytes <= raf.length() - phoff) {
                "Reviewed ELF program-header table escapes the file"
            }

            var interpreter: String? = null
            for (index in 0 until phnum) {
                val entryOffset = phoff + index.toLong() * phentsize.toLong()
                raf.seek(entryOffset)
                val ph = ByteArray(ELF64_PROGRAM_HEADER_BYTES)
                raf.readFully(ph)
                if (u32(ph, 0) != PT_INTERP) continue
                require(interpreter == null) { "Reviewed ELF declares multiple PT_INTERP segments" }
                val offset = u64(ph, 8)
                val size = u64(ph, 32)
                require(size in 2L..256L && offset <= raf.length() && size <= raf.length() - offset) {
                    "Reviewed ELF interpreter segment is invalid"
                }
                val raw = ByteArray(size.toInt())
                raf.seek(offset)
                raf.readFully(raw)
                require(raw.last() == 0.toByte()) { "Reviewed ELF interpreter is not NUL terminated" }
                require(raw.dropLast(1).none { it == 0.toByte() }) { "Reviewed ELF interpreter contains embedded NUL" }
                interpreter = raw.copyOf(raw.size - 1).toString(Charsets.US_ASCII)
            }

            val exact = requireNotNull(interpreter) {
                "Reviewed Ubuntu/glibc payload has no dynamic interpreter; static binaries require a separate certification path"
            }
            require(exact == AARCH64_GLIBC_INTERPRETER) {
                if (exact.contains("ld-musl-")) {
                    "Reviewed payload targets musl, not the Ubuntu/glibc guest"
                } else {
                    "Reviewed payload uses unsupported AArch64 dynamic interpreter '$exact'"
                }
            }
            return Descriptor(type = type, machine = machine, interpreter = exact)
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun u64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = value or ((bytes[offset + index].toLong() and 0xff) shl (index * 8))
        }
        require(value >= 0L) { "Reviewed ELF contains an unsupported 64-bit offset" }
        return value
    }
}
