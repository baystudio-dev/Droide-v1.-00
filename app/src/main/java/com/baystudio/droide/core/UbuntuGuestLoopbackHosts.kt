package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Guest-local name data only. */

internal object UbuntuGuestLoopbackHosts {
    internal const val MAX_BYTES = 65_536
    internal const val DEFAULTS = "127.0.0.1 localhost\n::1 localhost\n"
    internal enum class Status { SEEDED, PRESENT, PRESERVED, SKIPPED }
    internal data class Result(val status: Status, val diagnostic: String) {
        val hasLocalhost: Boolean get() = status == Status.SEEDED || status == Status.PRESENT
    }
    private data class Stamp(val key: Any?, val size: Long, val modified: String, val mode: Set<PosixFilePermission>)
    private data class Snapshot(val directories: List<Stamp>, val hosts: Stamp?, val nss: Stamp,
        val hostsBytes: ByteArray, val nssBytes: ByteArray)

    /** Bounded read-only identity also used by warm/package admission. No persistent marker. */
    fun fingerprint(rootfs: File): String? = try {
        val s = snapshot(rootfs)
        MessageDigest.getInstance("SHA-256").run {
            update((s.directories.toString() + s.hosts.toString() + s.nss.toString()).toByteArray())
            update(s.hostsBytes); update(0.toByte()); update(s.nssBytes)
            digest().joinToString("") { "%02x".format(it) }
        }
    } catch (_: Exception) { null }

    fun repair(rootfs: File, checkCancelled: () -> Unit = {}): Result {
        var pending: Path? = null
        try {
            checkCancelled()
            val before = snapshot(rootfs)
            val nss = text(before.nssBytes).lineSequence().map { it.substringBefore('#').trim() }
                .mapNotNull { Regex("^hosts\\s*:\\s*(.*)$").matchEntire(it)?.groupValues?.get(1) }
                .map { it.trim().split(Regex("[ \\t]+")) }.toList()
            if (nss.size != 1 || nss.single() !in listOf(listOf("files"), listOf("files", "dns"))) {
                return Result(Status.PRESERVED, "Custom hosts NSS policy preserved; check /etc/nsswitch.conf for files-first localhost lookup.")
            }
            val hostText = text(before.hostsBytes)
            var explicit = false
            var custom = false
            for (line in hostText.lineSequence()) {
                val body = line.substringBefore('#').trim()
                if (body.isEmpty()) continue
                val fields = body.split(Regex("[ \\t]+"))
                // Do not reinterpret malformed user data or repair over an incomplete record.
                require(fields.size >= 2) { "Malformed hosts record; preserved" }
                if (fields.drop(1).any { it.all { c -> c.code < 128 } && it.removeSuffix(".").equals("localhost", ignoreCase = true) }) {
                    explicit = true
                    if (!isLoopbackLiteral(fields.first())) custom = true
                }
            }
            if (explicit) return if (custom) Result(Status.PRESERVED,
                "Explicit custom/non-loopback localhost mapping preserved; inspect /etc/hosts before retrying the local service.")
            else Result(Status.PRESENT, "Existing loopback localhost mapping preserved.")

            val next = before.hostsBytes + (if (hostText.isNotEmpty() && !hostText.endsWith('\n')) "\n" else "").toByteArray() + DEFAULTS.toByteArray()
            require(next.size <= MAX_BYTES) { "Hosts file has no bounded space for defaults" }
            val etc = File(rootfs, "etc").toPath()
            pending = Files.createTempFile(etc, ".droide-hosts-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            val pendingKey = stamp(pending, directory = false).key
            FileChannel.open(pending, WRITE, NOFOLLOW_LINKS).use { channel ->
                val bytes = ByteBuffer.wrap(next)
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            Files.setPosixFilePermissions(pending, before.hosts?.mode ?: PosixFilePermissions.fromString("rw-r--r--"))
            checkCancelled()
            require(same(before, snapshot(rootfs))) { "Hosts/NSS changed during preparation; preserved" }
            require(stamp(pending, directory = false).key == pendingKey && read(pending).contentEquals(next)) { "Pending hosts file changed; preserved" }
            // No non-atomic fallback: failed/cancelled publication leaves the old hosts file intact.
            Files.move(pending, File(rootfs, "etc/hosts").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            pending = null
            return Result(Status.SEEDED, "Initialized guest-local IPv4 and IPv6 localhost records.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return Result(Status.SKIPPED, "Localhost initialization skipped: ${failure.message.orEmpty().take(256)}. Existing guest files preserved; inspect /etc/hosts and /etc/nsswitch.conf.")
        } finally {
            pending?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun isLoopbackLiteral(value: String): Boolean {
        val v4 = value.split('.')
        if (v4.size == 4 && v4.all { it.isNotEmpty() && (it.length == 1 || it.first() != '0') && it.all { c -> c in '0'..'9' } && it.toIntOrNull() in 0..255 }) {
            return v4.first() == "127"
        }
        // Recognize numeric IPv6 loopback only; no InetAddress/getaddrinfo/DNS call.
        if (!value.contains(':') || value.any { it !in "0123456789abcdefABCDEF:" }) return false
        val halves = value.split("::")
        if (halves.size > 2) return false
        fun groups(s: String): List<String> = if (s.isEmpty()) emptyList() else s.split(':')
        val left = groups(halves[0]); val right = if (halves.size == 2) groups(halves[1]) else emptyList()
        val explicit = left + right
        if (explicit.any { it.length !in 1..4 }) return false
        val zeros = 8 - explicit.size
        if (halves.size == 1 && zeros != 0 || halves.size == 2 && zeros < 1) return false
        val all = left + List(if (halves.size == 2) zeros else 0) { "0" } + right
        return all.size == 8 && all.dropLast(1).all { it.toInt(16) == 0 } && all.last().toInt(16) == 1
    }

    private fun text(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().also { s ->
            require(s.none { (it.code < 32 && it !in "\r\n\t") || it.code == 127 }) { "Invalid control characters" }
        }

    private fun snapshot(rootfs: File): Snapshot {
        require(rootfs.absoluteFile.normalize() == rootfs.canonicalFile) { "Symlinked guest parent" }
        val etc = File(rootfs, "etc").toPath()
        val directories = listOf(requireNotNull(rootfs.parentFile).toPath(), rootfs.toPath(), etc).map { stamp(it, true) }
        val hosts = etc.resolve("hosts"); val nss = etc.resolve("nsswitch.conf")
        val h = if (Files.exists(hosts, NOFOLLOW_LINKS)) stamp(hosts, false) else null
        val n = stamp(nss, false)
        val hb = if (h == null) byteArrayOf() else read(hosts)
        val nb = read(nss)
        require(h == if (Files.exists(hosts, NOFOLLOW_LINKS)) stamp(hosts, false) else null) { "Hosts changed during read" }
        require(n == stamp(nss, false)) { "NSS changed during read" }
        require(directories == listOf(requireNotNull(rootfs.parentFile).toPath(), rootfs.toPath(), etc).map { stamp(it, true) }) { "Guest parent changed during read" }
        return Snapshot(directories, h, n, hb, nb)
    }

    private fun stamp(path: Path, directory: Boolean): Stamp {
        val a = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(!a.isSymbolicLink && if (directory) a.isDirectory else a.isRegularFile) { "Unsafe ${path.fileName} type" }
        val mode = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
        require(PosixFilePermission.GROUP_WRITE !in mode && PosixFilePermission.OTHERS_WRITE !in mode) { "Shared-writable ${path.fileName}" }
        // Conservative optional migration: never replace a read-only record with a writable one.
        require(PosixFilePermission.OWNER_WRITE in mode && Files.isWritable(path)) { "Read-only ${path.fileName}" }
        if (!directory) {
            require(a.size() <= MAX_BYTES) { "Oversize ${path.fileName}" }
            require((Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() == 1L) { "Hard-linked ${path.fileName}" }
            require((Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) as Number).toInt() and 0b111_000_000_000 == 0) { "Special file mode" }
        }
        // Directory child changes (including our own temp file) do not change path ownership.
        return Stamp(a.fileKey(), if (directory) 0 else a.size(), if (directory) "" else a.lastModifiedTime().toString(), mode)
    }

    private fun read(path: Path): ByteArray = Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { input ->
        val b = ByteArray(MAX_BYTES + 1); var n = 0
        while (n < b.size) { val count = input.read(b, n, b.size - n); if (count < 0) break; n += count }
        require(n <= MAX_BYTES) { "Oversize ${path.fileName}" }
        b.copyOf(n)
    }
    private fun same(a: Snapshot, b: Snapshot) = a.directories == b.directories && a.hosts == b.hosts && a.nss == b.nss &&
        a.hostsBytes.contentEquals(b.hostsBytes) && a.nssBytes.contentEquals(b.nssBytes)
}
