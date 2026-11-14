package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption
import java.util.Base64

data class ElpPathIdentity(val guestPath: String, val metadata: String, val linkTarget: String? = null)
data class ElpFileIdentity(val guestPath: String, val canonicalGuestPath: String, val size: Long,
    val sha256: String, val metadata: String, val aliases: List<ElpPathIdentity>)
data class ElpSelectedToolEntry(val role: String, val file: ElpFileIdentity, val installationRoot: String,
    val provenance: String = "discovered-unverified")
data class ElpToolSelection(val erl: ElpSelectedToolEntry, val escript: ElpSelectedToolEntry,
    val rebar: ElpSelectedToolEntry? = null, val python: ElpSelectedToolEntry? = null) {
    val consentIdentity get() = LspElpLaunchPolicy.sha(toString().toByteArray(Charsets.UTF_8))
}
data class ElpRuntimeEvidence(val otpVersion: String, val otpMajor: Int, val ertsVersion: String,
    val architecture: String, val rootDir: String, val libDir: String, val rebarVersion: String?,
    val selectionIdentity: String, val libraryIdentity: String, val navigationSourcesAvailable: Boolean,
    val libraryFiles: List<ElpFileIdentity> = emptyList(), val libraryDirectories: List<ElpPathIdentity> = emptyList())

/** Finite candidate compatibility; a version response does not attest executable provenance. */
internal object ElpToolPolicy {
    private val stable = Regex("(27|28)\\.(0|[1-9][0-9]*)(?:\\.(0|[1-9][0-9]*))*")
    fun compatible(otp: String, rebar: String?, needsRebar: Boolean): Boolean {
        val match = stable.matchEntire(otp) ?: return false
        val major = match.groupValues[1].toInt()
        if (major == 27 && match.groupValues[2].toInt() < 3) return false
        return !needsRebar || (major == 27 && rebar in setOf("3.24.0", "3.25.1")) || (major == 28 && rebar == "3.25.1")
    }
    fun rebarVersion(output: String, major: Int): String {
        require(output.toByteArray().size <= 16_384)
        val line = output.trim().lineSequence().single()
        val m = Regex("rebar (3\\.[0-9]+\\.[0-9]+) on Erlang/OTP ([0-9]+) Erts [0-9]+(?:\\.[0-9]+)+").matchEntire(line)
            ?: error("Unrecognized selected Rebar version report")
        require(m.groupValues[2].toInt() == major)
        return m.groupValues[1]
    }
    fun parseReport(output: ByteArray): Map<String, String> {
        require(output.size in 1..16_384)
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output)).toString()
        val lines = text.trimEnd('\n').split('\n')
        require(lines.first() == "DROIDE_ELP_RUNTIME_V1" && lines.last() == "END_DROIDE_ELP_RUNTIME_V1")
        val result = linkedMapOf<String, String>()
        lines.drop(1).dropLast(1).forEach { line ->
            val pair = line.split('\t'); require(pair.size == 2 && pair[0].matches(Regex("[a-z_]+")) && pair[0] !in result)
            val bytes = Base64.getDecoder().decode(pair[1]); require(bytes.size <= 4096)
            result[pair[0]] = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().trimEnd('\r', '\n')
        }
        require(result.keys == setOf("otp_release", "otp_version_file", "erts_version", "system_version", "architecture",
            "root_dir", "lib_dir", "kernel_dir", "stdlib_dir", "application_module", "erl_scan_module", "compiler_dir", "compile_module"))
        return result.toMap()
    }
    fun validatePair(selection: ElpToolSelection, erl: Map<String, String>, escript: Map<String, String>,
        rebarVersion: String?, needsRebar: Boolean, files: ElpGuestFilesystem, deadline: ElpDeadline): ElpRuntimeEvidence {
        require(erl == escript) { "Selected erl and escript report different runtime installations" }
        val version = erl.getValue("otp_version_file"); val major = erl.getValue("otp_release").toInt()
        require(version.substringBefore('.').toInt() == major && compatible(version, rebarVersion, needsRebar))
        require(erl.getValue("architecture").matches(Regex("(?:aarch64|arm64)-.*linux.*")))
        val root = erl.getValue("root_dir"); val lib = erl.getValue("lib_dir")
        require(root == selection.erl.installationRoot && root == selection.escript.installationRoot && lib == "$root/lib")
        val origins = mutableListOf<String>()
        val libraryFiles = mutableListOf<ElpFileIdentity>(); val libraryDirectories = mutableListOf<ElpPathIdentity>()
        for (key in listOf("root_dir", "lib_dir", "kernel_dir", "stdlib_dir") + if (needsRebar) listOf("compiler_dir") else emptyList()) {
            deadline.check(); val path = erl.getValue(key)
            require(path == root || path.startsWith("$root/"))
            val directory = files.directory(path)
            require(directory.linkTarget == null || directory.linkTarget == root || directory.linkTarget.startsWith("$root/"))
            libraryDirectories += directory; origins += directory.toString()
        }
        for (key in listOf("application_module", "erl_scan_module") + if (needsRebar) listOf("compile_module") else emptyList()) {
            deadline.check(); val path = erl.getValue(key); require(path.startsWith("$lib/"))
            val captured = files.capture(path, 16 * 1024 * 1024, deadline).first
            require(captured.canonicalGuestPath.startsWith("$lib/")); libraryFiles += captured; origins += captured.toString()
        }
        val versionFile = files.capture("$root/releases/$major/OTP_VERSION", 4096, deadline)
        require(versionFile.second.toString(Charsets.UTF_8).trim() == version)
        libraryFiles += versionFile.first; origins += versionFile.first.toString()
        return ElpRuntimeEvidence(version, major, erl.getValue("erts_version"), erl.getValue("architecture"), root, lib,
            rebarVersion, selection.consentIdentity, LspElpLaunchPolicy.sha(origins.joinToString("\n").toByteArray()),
            listOf(erl.getValue("kernel_dir"), erl.getValue("stdlib_dir")).all { files.isDirectory("$it/src") && files.isDirectory("$it/include") },
            libraryFiles.toList(), libraryDirectories.toList())
    }
}

/** Resolve the actual guest view, including absolute guest symlinks, without executing a guest shell. */
internal class ElpGuestFilesystem(private val rootfs: File, private val workspace: File?,
    val guestWorkspaceRoot: String = workspace?.absolutePath ?: LspElpLaunchPolicy.WORKSPACE_ROOT) {
    init {
        require(rootfs.canonicalFile == rootfs.absoluteFile && Files.isDirectory(rootfs.toPath(), NOFOLLOW_LINKS))
        workspace?.let { require(it.canonicalFile == it.absoluteFile && Files.isDirectory(it.toPath(), NOFOLLOW_LINKS)) }
        require(LspElpLaunchPolicy.path(guestWorkspaceRoot))
    }
    val mappingIdentity: String get() = listOf(rootfs.absolutePath, GraphqlWire.canonicalDirectory(rootfs),
        workspace?.let { GraphqlWire.ancestors(it).toString() }.orEmpty(), guestWorkspaceRoot).joinToString("\n")
    private var hashed = 0L
    private fun local(guest: String): File {
        require(guest == "/" || LspElpLaunchPolicy.path(guest))
        val bound = guestWorkspaceRoot.takeIf { workspace != null }
        return if (bound != null && (guest == bound || guest.startsWith("$bound/"))) File(requireNotNull(workspace), guest.removePrefix(bound).removePrefix("/"))
            else File(rootfs, guest.removePrefix("/"))
    }
    private fun attributes(file: File, directory: Boolean = false): String {
        val attrs = Files.readAttributes(file.toPath(), if (directory) "unix:dev,ino,mode" else "unix:dev,ino,mode,size,lastModifiedTime,ctime,nlink", NOFOLLOW_LINKS)
        return attrs.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" }
    }
    fun resolve(guest: String): Pair<String, List<ElpPathIdentity>> {
        require(guest == "/" || LspElpLaunchPolicy.path(guest))
        var pending = guest.split('/').filter { it.isNotEmpty() }.toMutableList()
        val done = mutableListOf<String>(); val aliases = mutableListOf<ElpPathIdentity>(); var links = 0
        while (pending.isNotEmpty()) {
            // A real PRoot bind endpoint is resolved before its unbound host-shaped parents.
            val bindParts = guestWorkspaceRoot.split('/').filter { it.isNotEmpty() }
            if (workspace != null && done.isEmpty() && pending.take(bindParts.size) == bindParts) {
                aliases += ElpPathIdentity(guestWorkspaceRoot, attributes(workspace, true))
                done.addAll(bindParts); pending = pending.drop(bindParts.size).toMutableList()
                if (pending.isEmpty()) break
            }
            require(aliases.size < 512); val next = pending.removeAt(0)
            when (next) { "." -> continue; ".." -> { require(done.isNotEmpty()); done.removeAt(done.lastIndex); continue } }
            val current = "/" + (done + next).joinToString("/"); val file = local(current)
            val basic = try {
                Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (missing: java.nio.file.NoSuchFileException) {
                require(links == 0) { "Guest alias target is unavailable: $current" }
                throw missing
            }
            if (basic.isSymbolicLink) {
                require(++links <= 32); val target = Files.readSymbolicLink(file.toPath()).toString()
                require(target.toByteArray().size <= 4096 && target.none { it.code < 32 })
                aliases += ElpPathIdentity(current, attributes(file), target)
                if (target.startsWith('/')) done.clear()
                pending = (target.split('/').filter { it.isNotEmpty() } + pending).toMutableList()
            } else {
                val isDir = basic.isDirectory
                if (pending.isNotEmpty()) require(isDir)
                aliases += ElpPathIdentity(current, attributes(file, isDir)); done += next
            }
        }
        val canonical = "/" + done.joinToString("/")
        require(canonical == "/" || LspElpLaunchPolicy.path(canonical))
        return canonical to aliases.toList()
    }
    fun directory(path: String): ElpPathIdentity {
        val resolved = resolve(path); val file = local(resolved.first)
        require(Files.isDirectory(file.toPath(), NOFOLLOW_LINKS))
        return ElpPathIdentity(path, attributes(file, true), resolved.first.takeIf { it != path })
    }
    fun isDirectory(path: String): Boolean = runCatching { directory(path); true }.getOrDefault(false)
    fun exists(path: String): Boolean {
        require(path == "/" || LspElpLaunchPolicy.path(path))
        if (path == "/" || workspace != null && path == guestWorkspaceRoot) return true
        // Resolve guest parents before asking about the final entry. Host absolute aliases are not guest absence.
        return try {
            val parent = resolve(path.substringBeforeLast('/').ifEmpty { "/" }).first
            val entry = File(local(parent), path.substringAfterLast('/'))
            Files.readAttributes(entry.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            true
        } catch (_: java.nio.file.NoSuchFileException) { false }
    }
    fun capture(path: String, max: Int, deadline: ElpDeadline): Pair<ElpFileIdentity, ByteArray> {
        deadline.check(); val (canonical, aliases) = resolve(path); val file = local(canonical)
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        val before = attributes(file); val size = Files.size(file.toPath())
        require(size in 0..max.toLong() && hashed + size <= 134_217_728L); hashed += size
        val bytes = Files.newByteChannel(file.toPath(), setOf(StandardOpenOption.READ, NOFOLLOW_LINKS)).use { channel ->
            val out = ByteBuffer.allocate(size.toInt()); while (out.hasRemaining()) { deadline.check(); require(channel.read(out) > 0) }
            require(channel.size() == size); out.array()
        }
        require(before == attributes(file) && resolve(path) == canonical to aliases) { "Selected guest file changed during observation" }
        return ElpFileIdentity(path, canonical, size, LspElpLaunchPolicy.sha(bytes), before, aliases) to bytes
    }
    fun observe(path: String, deadline: ElpDeadline): ElpObservedFile {
        deadline.check(); if (!exists(path)) return ElpObservedFile(path, "absent")
        val canonical = resolve(path).first
        require(workspace == null || path == "/" || !(path == guestWorkspaceRoot || path.startsWith(guestWorkspaceRoot + "/")) || (canonical == guestWorkspaceRoot || canonical.startsWith(guestWorkspaceRoot + "/"))) { "Guest project alias escaped mapped workspace" }
        return if (Files.isDirectory(local(canonical).toPath(), NOFOLLOW_LINKS)) ElpObservedFile(path, "directory", directoryIdentity = directory(path).toString())
        else capture(path, 1_048_576, deadline).let { ElpObservedFile(path, "regular", it.first, it.second) }
    }
    fun candidate(role: String, deadline: ElpDeadline): ElpSelectedToolEntry? {
        val name = if (role == "python") "python3.12" else role
        val roots = LocalLinuxUserEnvironment.executableRoots().distinct(); require(roots.size <= 64)
        val path = roots.map { "$it/$name" }.firstOrNull(::exists) ?: return null
        // A selected dynamic/version-manager wrapper is unavailable, never executed to discover its behavior.
        require(!path.contains("/mise/shims/") && !path.contains("/shims/"))
        val (identity, bytes) = capture(path, 64 * 1024 * 1024, deadline)
        require(Files.isExecutable(local(identity.canonicalGuestPath).toPath()))
        val canonical = identity.canonicalGuestPath
        val installation = when (role) {
            "erl", "escript" -> {
                val allowed = listOf("/usr/lib/erlang", "/usr/local/lib/erlang")
                val root = allowed.singleOrNull { canonical.startsWith("$it/") }
                    ?: error("Selected OTP does not have a conventional reviewed alias closure")
                require(identity.aliases.filter { it.linkTarget != null }.all { a ->
                    roots.any { a.guestPath == it || a.guestPath.startsWith("$it/") } || allowed.any { a.guestPath == it || a.guestPath.startsWith("$it/") }
                })
                if (role == "erl") {
                    val text = bytes.toString(Charsets.UTF_8)
                    require(canonical == "$root/bin/erl" && text.startsWith("#!/bin/sh") && text.contains("ERL_ROOTDIR") && text.contains("erlexec"))
                } else require(bytes.take(4) == listOf(127.toByte(), 69.toByte(), 76.toByte(), 70.toByte()) && canonical.endsWith("/bin/escript"))
                root
            }
            "rebar3" -> {
                val line = bytes.take(4096).toByteArray().toString(Charsets.UTF_8).lineSequence().firstOrNull().orEmpty()
                require(line.matches(Regex("#!(?:/usr/bin/env escript|/[^ \\r\\n]+/escript)"))) { "Selected Rebar must be a direct escript" }
                require(roots.any { canonical.startsWith("$it/") }); canonical.substringBeforeLast('/')
            }
            "python" -> {
                require(bytes.take(4) == listOf(127.toByte(), 69.toByte(), 76.toByte(), 70.toByte()))
                require(canonical in listOf("/usr/bin/python3.12", "/usr/local/bin/python3.12"))
                canonical.substringBeforeLast('/').substringBeforeLast('/')
            }
            else -> error("Unsupported selected tool")
        }
        return ElpSelectedToolEntry(role, identity, installation)
    }
    fun verify(entry: ElpSelectedToolEntry, deadline: ElpDeadline) { require(capture(entry.file.guestPath, 64 * 1024 * 1024, deadline).first == entry.file) }
}
