package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Optional guest name records, never Android permissions or group membership. */

internal class UbuntuGuestGroupNames(private val nanoTime: () -> Long = System::nanoTime) {
    private var lastStamp: String? = null
    private var lastAttemptAt = 0L
    private var lastSucceeded = false

    suspend fun repair(
        rootfs: File,
        execute: suspend (List<String>, Int, Long) -> ExecResult,
        checkCancelled: () -> Unit = {},
        diagnostic: (String) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        checkCancelled()
        val before = try { snapshot(rootfs) } catch (failure: Exception) {
            diagnostic("Skipped optional group naming: ${failure.message.orEmpty().take(512)}")
            return
        }
        val age = nanoTime() - lastAttemptAt
        if (before == lastStamp && age in 0 until if (lastSucceeded) SUCCESS_CACHE_NANOS else FAILURE_CACHE_NANOS) return
        lastStamp = null
        val result = try {
            execute(command(), MAX_OUTPUT_BYTES, TIMEOUT_MS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            checkCancelled()
            lastStamp = before; lastAttemptAt = nanoTime(); lastSucceeded = false
            diagnostic("Optional group naming incomplete: ${failure.message.orEmpty().take(512)}")
            return
        }
        currentCoroutineContext().ensureActive()
        checkCancelled()
        lastAttemptAt = nanoTime()
        lastSucceeded = result.exitCode == 0 && !result.timedOut && !result.outputIncomplete
        // groupadd can publish one database before another fails. Keep all effects/evidence; never undo
        // records, delete locks, or claim an atomic multi-database transaction.
        lastStamp = try { snapshot(rootfs) } catch (_: Exception) { null }
        if (!lastSucceeded || lastStamp == null) {
            diagnostic("Optional group naming incomplete (exit=${result.exitCode}, timeout=${result.timedOut}, " +
                "incompleteOutput=${result.outputIncomplete}). Existing records were not rolled back.\n" +
                result.output.takeLast(MAX_DIAGNOSTIC_CHARS))
        }
    }

    companion object {
        const val MAX_OUTPUT_BYTES = 16_384
        const val TIMEOUT_MS = 5_000L
        const val SUCCESS_CACHE_NANOS = 300_000_000_000L
        const val FAILURE_CACHE_NANOS = 30_000_000_000L
        private const val MAX_DIAGNOSTIC_CHARS = 4_096

        internal fun command(): List<String> = listOf(
            "/bin/sh", "-c", SCRIPT, "droide-group-names", "/etc",
            "/usr/bin/id", "/usr/bin/getent", "/usr/sbin/groupadd",
        )

        /** Bounded metadata only, no tree scan or persistent marker. */

        private fun snapshot(rootfs: File): String = buildString {
            require(rootfs.absoluteFile.normalize() == rootfs.canonicalFile) { "Guest parent path is symlinked" }
            fun inspect(file: File, directory: Boolean = false, writable: Boolean = false, optional: Boolean = false) {
                append(file.absolutePath).append('\u0000')
                if (optional && !Files.exists(file.toPath(), NOFOLLOW_LINKS)) { append("absent\u0000"); return }
                val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(!attrs.isSymbolicLink && if (directory) attrs.isDirectory else attrs.isRegularFile) { "Unsafe ${file.name} type" }
                val mode = Files.getPosixFilePermissions(file.toPath(), NOFOLLOW_LINKS)
                require(PosixFilePermission.GROUP_WRITE !in mode && PosixFilePermission.OTHERS_WRITE !in mode) { "Shared-writable ${file.name}" }
                if (writable) require(PosixFilePermission.OWNER_WRITE in mode && Files.isWritable(file.toPath())) { "Read-only ${file.name}" }
                if (!directory) {
                    require(attrs.size() <= 1_048_576) { "Oversize ${file.name}" }
                    require((Files.getAttribute(file.toPath(), "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() == 1L) { "Hard-linked ${file.name}" }
                }
                append("${attrs.fileKey()}:${attrs.size()}:${attrs.lastModifiedTime()}:$mode\u0000")
            }
            inspect(requireNotNull(rootfs.parentFile) { "Guest root has no parent" }, directory = true)
            inspect(rootfs, directory = true)
            val etc = File(rootfs, "etc")
            inspect(etc, directory = true, writable = true)
            for (db in listOf("group", "gshadow")) {
                inspect(File(etc, db), writable = true, optional = db == "gshadow")
                inspect(File(etc, "$db-"), writable = true, optional = true)
                for (suffix in listOf("+", ".lock")) {
                    require(!Files.exists(File(etc, "$db$suffix").toPath(), NOFOLLOW_LINKS)) { "Existing $db$suffix; preserve pending database work" }
                }
            }
            // The libc advisory-lock file normally survives unlock; existence is not contention.
            inspect(File(etc, ".pwd.lock"), writable = true, optional = true)
            val nss = File(etc, "nsswitch.conf")
            inspect(nss)
            val bytes = nss.inputStream().use { input ->
                val buffer = ByteArray(65_537)
                var count = 0
                while (count < buffer.size) {
                    val n = input.read(buffer, count, buffer.size - count)
                    if (n < 0) break
                    count += n
                }
                require(count <= 65_536) { "Oversize NSS configuration" }
                buffer.copyOf(count)
            }
            val groups = bytes.toString(Charsets.UTF_8).lineSequence().map { it.substringBefore('#').trim() }
                .mapNotNull { Regex("^group\\s*:\\s*(.*)$").matchEntire(it)?.groupValues?.get(1)?.trim() }.toList()
            require(groups == listOf("files")) { "Custom or unknown group NSS configuration" }
            // Explicit files-only getent below also prevents a concurrent NSS edit causing a remote lookup.
            for (path in listOf("usr", "usr/bin", "usr/sbin")) inspect(File(rootfs, path), directory = true)
            for (path in listOf("usr/bin/id", "usr/bin/getent", "usr/sbin/groupadd")) {
                val tool = File(rootfs, path)
                inspect(tool)
                require(Files.isExecutable(tool.toPath())) { "Missing executable $path" }
            }
        }

        private val SCRIPT = """
            set -eu
            set -f
            LC_ALL=C
            export LC_ALL
            etc=${'$'}1; id=${'$'}2; getent=${'$'}3; groupadd=${'$'}4
            fail() { printf 'Droide Ubuntu group naming: %s\n' "${'$'}*" >&2; exit 1; }
            for tool in "${'$'}id" "${'$'}getent" "${'$'}groupadd"; do
                [ -x "${'$'}tool" ] || fail "missing tool ${'$'}tool"
            done
            gids=${'$'}("${'$'}id" -G) || fail 'numeric id -G failed'
            [ -n "${'$'}gids" ] && [ "${'$'}{#gids}" -le 704 ] || fail 'empty or oversized numeric group list'
            count=0
            for gid in ${'$'}gids; do
                case "${'$'}gid" in ''|*[!0-9]*) fail 'invalid numeric group ID';; esac
                [ "${'$'}{#gid}" -le 10 ] && [ "${'$'}gid" -le 4294967294 ] || fail 'group ID out of range'
                count=${'$'}((count + 1)); [ "${'$'}count" -le 64 ] || fail 'too many group IDs'
            done
            for gid in ${'$'}gids; do
                if "${'$'}getent" -s files group "${'$'}gid" >/dev/null; then continue
                else status=${'$'}?; [ "${'$'}status" -eq 2 ] || fail "GID lookup failed (${ '$' }status)"; fi
                name=droide_android_${'$'}gid
                if "${'$'}getent" -s files group "${'$'}name" >/dev/null; then fail "name collision: ${'$'}name"
                else status=${'$'}?; [ "${'$'}status" -eq 2 ] || fail "name lookup failed (${ '$' }status)"; fi
                # groupadd would replace an orphan shadow-group entry with this same name.
                # Keep its password/admin/member fields even when no group record refers to it.
                if [ -e "${'$'}etc/gshadow" ]; then
                    if "${'$'}getent" -s files gshadow "${'$'}name" >/dev/null; then fail "shadow name collision: ${'$'}name"
                    else status=${'$'}?; [ "${'$'}status" -eq 2 ] || fail "shadow name lookup failed (${ '$' }status)"; fi
                fi
                # A fresh shell's PID is preserved by exec, unlike the surrounding loop's PID.
                # Recheck standard shadow paths immediately before invoking the unmodified tool.
                /bin/sh -c '
                    set -eu
                    etc=${'$'}1; groupadd=${'$'}2; gid=${'$'}3; name=${'$'}4
                    fail() { printf "Droide Ubuntu group naming: unsafe or pending %s\n" "${'$'}1" >&2; exit 1; }
                    [ -d "${'$'}etc" ] && [ ! -L "${'$'}etc" ] && [ -w "${'$'}etc" ] || fail "${'$'}etc"
                    for db in "${'$'}etc/group" "${'$'}etc/gshadow"; do
                        if [ "${'$'}db" = "${'$'}etc/group" ] || [ -e "${'$'}db" ] || [ -L "${'$'}db" ]; then
                            [ ! -L "${'$'}db" ] && [ -f "${'$'}db" ] && [ -w "${'$'}db" ] || fail "${'$'}db"
                        fi
                        for path in "${'$'}db-" "${'$'}etc/.pwd.lock"; do
                            [ ! -L "${'$'}path" ] || fail "${'$'}path"
                            if [ -e "${'$'}path" ]; then [ -f "${'$'}path" ] && [ -w "${'$'}path" ] || fail "${'$'}path"; fi
                        done
                        for path in "${'$'}db+" "${'$'}db.lock" "${'$'}db.${'$'}${'$'}"; do
                            [ ! -e "${'$'}path" ] && [ ! -L "${'$'}path" ] || fail "${'$'}path"
                        done
                    done
                    exec "${'$'}groupadd" --gid "${'$'}gid" "${'$'}name"
                ' droide-group-add "${'$'}etc" "${'$'}groupadd" "${'$'}gid" "${'$'}name" || fail "groupadd incomplete for GID ${'$'}gid; preserving existing effects"
                "${'$'}getent" -s files group "${'$'}gid" >/dev/null || fail "new GID ${'$'}gid still has no name"
            done
        """.trimIndent()
    }
}
