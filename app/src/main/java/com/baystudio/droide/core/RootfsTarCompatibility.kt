package com.baystudio.droide.core

import java.io.InputStream
import java.io.OutputStream

/** Adapts only byte-verified bundled TARs; no filesystem paths are opened here. */
internal object RootfsTarCompatibility {
    private const val BLOCK = 512
    private const val MAX_LINK_BYTES = 16 * 1024 * 1024

    fun copy(
        input: InputStream,
        output: OutputStream,
        hardLinks: Map<String, String>,
        checkCancelled: () -> Unit = {},
    ) {
        require(hardLinks.size <= 16 && hardLinks.all { (path, target) -> safePath(path) && safePath(target) && path != target }) {
            "Unsafe rootfs hard-link mapping"
        }
        val targets = hardLinks.values.toSet()
        val cache = mutableMapOf<String, Pair<ByteArray, ByteArray>>()
        val copied = mutableSetOf<String>()
        val buffer = ByteArray(65_536)
        var cachedBytes = 0L
        while (true) {
            checkCancelled()
            val header = ByteArray(BLOCK)
            readFully(input, header, BLOCK, checkCancelled)
            if (header.all { it == 0.toByte() }) {
                output.write(header)
                while (true) {
                    checkCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    require((0 until n).all { buffer[it] == 0.toByte() }) { "Non-padding bytes after rootfs TAR terminator" }
                    output.write(buffer, 0, n)
                }
                require(copied == hardLinks.keys) { "Rootfs TAR is missing a reviewed hard link" }
                return
            }
            val checksum = octal(header, 148, 8)
            require(header.indices.sumOf { if (it in 148..155) 32L else (header[it].toInt() and 255).toLong() } == checksum) {
                "Invalid rootfs TAR header checksum"
            }
            val name = text(header, 0, 100)
            val prefix = text(header, 345, 155)
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            val size = octal(header, 124, 12)
            when (header[156].toInt().toChar()) {
                '1' -> {
                    require(size == 0L && hardLinks[path] == text(header, 157, 100) && copied.add(path)) {
                        "Rootfs TAR contains an unreviewed hard link: $path"
                    }
                    val (payload, mode) = requireNotNull(cache[hardLinks.getValue(path)]) { "Rootfs hard-link target must precede its alias" }
                    header[156] = '0'.code.toByte()
                    payload.size.toString(8).padStart(11, '0').toByteArray(Charsets.US_ASCII).copyInto(header, 124)
                    header[135] = 0
                    header.fill(0, 157, 257)
                    mode.copyInto(header, 100)
                    header.fill(' '.code.toByte(), 148, 156)
                    val sum = header.sumOf { it.toInt() and 255 }
                    (sum.toString(8).padStart(6, '0') + "\u0000 ").toByteArray(Charsets.US_ASCII).copyInto(header, 148)
                    output.write(header)
                    output.write(payload)
                    output.write(ByteArray(padding(payload.size.toLong())))
                }
                else -> {
                    val retain = path in targets && header[156].toInt().toChar() in setOf('0', '\u0000')
                    val payload = if (retain) {
                        require(path !in cache && size <= MAX_LINK_BYTES - cachedBytes) { "Rootfs hard-link target exceeds memory bound" }
                        cachedBytes += size
                        ByteArray(size.toInt())
                    } else null
                    output.write(header)
                    var remaining = size
                    var offset = 0
                    while (remaining > 0) {
                        checkCancelled()
                        val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        require(n > 0) { "Truncated rootfs TAR payload" }
                        output.write(buffer, 0, n)
                        payload?.let { buffer.copyInto(it, offset, 0, n); offset += n }
                        remaining -= n
                    }
                    payload?.let { cache[path] = it to header.copyOfRange(100, 108) }
                    val pad = padding(size)
                    readFully(input, buffer, pad, checkCancelled)
                    output.write(buffer, 0, pad)
                }
            }
        }
    }

    private fun padding(size: Long): Int = ((BLOCK - size % BLOCK) % BLOCK).toInt()

    private fun readFully(input: InputStream, buffer: ByteArray, count: Int, checkCancelled: () -> Unit) {
        var offset = 0
        while (offset < count) {
            checkCancelled()
            val n = input.read(buffer, offset, count - offset)
            require(n > 0) { "Truncated rootfs TAR block" }
            offset += n
        }
    }

    private fun text(header: ByteArray, offset: Int, length: Int): String =
        String(header, offset, length, Charsets.UTF_8).substringBefore('\u0000')

    private fun octal(header: ByteArray, offset: Int, length: Int): Long {
        val value = text(header, offset, length).trim()
        require(value.isNotEmpty() && value.all { it in '0'..'7' }) { "Unsupported rootfs TAR numeric field" }
        return requireNotNull(value.toLongOrNull(8)) { "Rootfs TAR numeric field exceeds bounds" }
    }

    private fun safePath(path: String): Boolean = path.isNotEmpty() && !path.startsWith('/') &&
        path.none { it == '\\' || it.code < 32 || it.code == 127 } && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
}
