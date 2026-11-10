package com.baystudio.droide.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Publisher formats are projected into owned files; the guest never extracts an archive itself. */
internal object LocalUbuntuVendorArchive {
    suspend fun project(recipe: LocalUbuntuVendorRecipe, archive: File, launcher: String): SafeTreeProjection {
        recipe.validate()
        require(recipe.familyId!=LocalUbuntuElpPolicy.FAMILY) { "ELP requires its strict single-member original archive transaction" }
        val def = recipe.definition
        val executable = LocalUbuntuVendorPolicy.executablePaths(recipe)
        val active = coroutineContext.let { context -> { context.ensureActive() } }
        require(recipe.familyId != LocalUbuntuGraphqlNodePolicy.FAMILY) { "GraphQL Node requires its dedicated authenticated data-only archive transaction" }
        if (recipe.familyId in setOf(LocalUbuntuPowerShellRuntimePolicy.FAMILY, LocalUbuntuPowerShellEditorServicesPolicy.FAMILY))
            return LocalUbuntuPowerShellArchive.project(recipe, archive, active)
        if ((LocalUbuntuNimPolicy.isFamily(recipe.familyId) || recipe.familyId in setOf(LocalUbuntuTomlNodePolicy.FAMILY,LocalUbuntuVueHelperPolicy.FAMILY)) && def.format == LocalUbuntuVendorFormat.TAR_GZ)
            GZIPInputStream(archive.inputStream(), 65_536).use { requireNimTarModes(it, def.maxFiles, def.maxUnpackedBytes, active) }
        val projection = when (def.format) {
            LocalUbuntuVendorFormat.ZIP -> if (recipe.familyId == LocalUbuntuClojurePolicy.FAMILY)
                LocalUbuntuClojureArchive.project(recipe, archive, active)
                else SafeWholeTreeArchive.project(archive, DeclarativeGitHubArtifactFormat.ZIP,
                executable, def.maxFiles, def.maxUnpackedBytes, checkActive = active)
            LocalUbuntuVendorFormat.TAR_GZ -> if (recipe.familyId == "lsp.jdtls") SafeWholeTreeArchive.projectVendorTar(
                archive, executable, def.maxFiles, def.maxUnpackedBytes, active, gzip = true, allowReviewedNumericPax = true)
                else SafeWholeTreeArchive.project(archive, DeclarativeGitHubArtifactFormat.TAR_GZ,
                if (recipe.familyId in setOf(LocalUbuntuNimHelperPolicy.FAMILY,LocalUbuntuVueHelperPolicy.FAMILY,LocalUbuntuTomlNodePolicy.FAMILY)) emptySet() else executable, def.maxFiles, def.maxUnpackedBytes,
                if (recipe.familyId == LocalUbuntuNimPolicy.FAMILY || recipe.familyId in setOf(LocalUbuntuSqruffPolicy.FAMILY, LocalUbuntuBufPolicy.FAMILY, LocalUbuntuMakePolicy.FAMILY)) DeclarativeWholeTreeLinkPolicy.REJECT else DeclarativeWholeTreeLinkPolicy.SAFE_RELATIVE,
                modePolicy = if (recipe.familyId in setOf(LocalUbuntuNimHelperPolicy.FAMILY,LocalUbuntuVueHelperPolicy.FAMILY,LocalUbuntuTomlNodePolicy.FAMILY)) DeclarativeWholeTreeModePolicy.PINNED_ARCHIVE else DeclarativeWholeTreeModePolicy.EXPLICIT_ALLOWLIST,
                checkActive = active)
            LocalUbuntuVendorFormat.RAW -> archive.inputStream().use { single(recipe, archive, it, active) }
            LocalUbuntuVendorFormat.GZIP_RAW -> GZIPInputStream(archive.inputStream(), 65_536).use { single(recipe, archive, it, active) }
            LocalUbuntuVendorFormat.TAR_XZ -> {
                // Bash defines ulimit -f in KiB. Bound the intermediate TAR before xz starts;
                // cancellation/timeout reaps the leased PRoot process and the file is always removed.
                val bound = Math.addExact(def.maxUnpackedBytes, Math.addExact(def.maxFiles.toLong() * 1024, 8192))
                val parent = requireNotNull(archive.parentFile)
                check(parent.usableSpace >= Math.addExact(bound, def.maxUnpackedBytes + 268_435_456L)) { "Insufficient storage for bounded XZ projection" }
                val decoded = File.createTempFile(".vendor-tar-", ".part", parent)
                try {
                    val script = "set -eu; ulimit -f ${(bound + 1023) / 1024}; exec /usr/bin/xz -dc -- ${q(archive.absolutePath)} > ${q(decoded.absolutePath)}"
                    val command = listOf("/system/bin/sh", launcher, "/bin/bash", "-c", script).joinToString(" ", transform = ::q)
                    val result = LocalExecutionSubstrate.shellBounded(command, 16_384, timeoutMs = 120_000)
                    check(!result.timedOut && result.exitCode == 0 && decoded.length() in 1..bound) { "Bounded XZ decoding failed: ${result.output.takeLast(2000)}" }
                    projectDecodedTar(recipe, decoded, active)
                } finally { check(PathSecurity.deleteTreeNoFollow(decoded)) { "Cannot clean decoded vendor TAR" } }
            }
        }
        return try {
            if (LocalUbuntuNimPolicy.isFamily(recipe.familyId)) LocalUbuntuNimLaunchAuthority.inventory(recipe, projection.root, active)
            if (recipe.familyId == LocalUbuntuTomlNodePolicy.FAMILY) LocalUbuntuTomlNodePolicy.requireTree(File(projection.root,recipe.entryPoint),active)
            if (recipe.familyId == LocalUbuntuVueHelperPolicy.FAMILY) LocalUbuntuVueHelperPolicy.requireTree(File(projection.root,recipe.entryPoint),active)
            if (recipe.familyId == LocalUbuntuSqruffPolicy.FAMILY) {
                require(projection.symlinks.isEmpty())
                LocalUbuntuSqruffPolicy.requireTree(File(projection.root, recipe.entryPoint))
            }
            projection
        } catch (failure: Throwable) { projection.close(); throw failure }
    }

    /** Same bounded TAR projection as before; only ZLS rejects every link and enforces its source-derived notices. */
    internal fun projectDecodedTar(recipe: LocalUbuntuVendorRecipe, decoded: File, active: () -> Unit = {}): SafeTreeProjection {
        recipe.validate()
        require(recipe.familyId!=LocalUbuntuElpPolicy.FAMILY) { "ELP requires its strict single-member original archive transaction" }
        val def = recipe.definition
        if (recipe.familyId == LocalUbuntuNimToolchainPolicy.FAMILY) decoded.inputStream().use { requireNimTarModes(it,def.maxFiles,def.maxUnpackedBytes,active) }
        val projection = SafeWholeTreeArchive.projectVendorTar(decoded,
            LocalUbuntuVendorPolicy.executablePaths(recipe), def.maxFiles, def.maxUnpackedBytes, active,
            linkPolicy = if (recipe.familyId in setOf(LocalUbuntuZlsPolicy.FAMILY, LocalUbuntuNimToolchainPolicy.FAMILY)) DeclarativeWholeTreeLinkPolicy.REJECT
                else DeclarativeWholeTreeLinkPolicy.SAFE_RELATIVE)
        return try {
            if (recipe.familyId == LocalUbuntuNimToolchainPolicy.FAMILY) LocalUbuntuNimToolchainPolicy.requireTree(File(projection.root,recipe.entryPoint),active)
            if (recipe.familyId == LocalUbuntuZlsPolicy.FAMILY) {
                require(projection.symlinks.isEmpty()) { "ZLS archive must not contain links" }
                LocalUbuntuZlsPolicy.requireTree(File(projection.root, recipe.entryPoint))
            }
            projection
        } catch (failure: Throwable) {
            projection.close()
            throw failure
        }
    }

    /** Nim-only header preflight rejects set-ID before normalized extraction can hide original mode bits. */
    internal fun requireNimTarModes(input: InputStream, maxFiles: Int, maxBytes: Long, active: () -> Unit = {}) {
        var entries=0; var bytes=0L; val header=ByteArray(512); val buffer=ByteArray(65536)
        fun exact(b:ByteArray,n:Int) { var at=0;while(at<n){active();val got=input.read(b,at,n-at);require(got>0){"Truncated Nim TAR"};at+=got} }
        fun octal(at:Int,n:Int):Long { val value=String(header,at,n,Charsets.US_ASCII).trim(' ', '\u0000');require(value.isNotEmpty()&&value.all { it in '0'..'7' });return value.toLong(8) }
        while(true) { exact(header,512);if(header.all { it==0.toByte() })break
            require(++entries<=maxFiles*2); val mode=octal(100,8);require(mode and 3072L==0L){"Nim original archive contains set-ID mode"}
            val size=octal(124,12);require(size>=0);bytes=Math.addExact(bytes,size);require(bytes<=maxBytes)
            var skip=Math.addExact(size,(512-size%512)%512)
            while(skip>0){val n=minOf(skip,buffer.size.toLong()).toInt();exact(buffer,n);skip-=n}
        }
    }
    private fun single(recipe: LocalUbuntuVendorRecipe, archive: File, input: InputStream, active: () -> Unit): SafeTreeProjection {
        require('/' !in recipe.entryPoint)
        val parent = requireNotNull(archive.parentFile)
        check(parent.usableSpace >= recipe.definition.maxUnpackedBytes + 268_435_456L)
        val root = File(parent, ".vendor-raw-${java.util.UUID.randomUUID()}")
        check(root.mkdir())
        return try {
            val target = File(root, recipe.entryPoint)
            val hash = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            target.outputStream().use { output ->
                val buffer = ByteArray(65_536)
                while (true) {
                    active()
                    val n = input.read(buffer)
                    if (n < 0) break
                    bytes = Math.addExact(bytes, n.toLong())
                    require(bytes <= recipe.definition.maxUnpackedBytes) { "Vendor payload exceeds its expanded bound" }
                    if (recipe.definition.format == LocalUbuntuVendorFormat.GZIP_RAW)
                        require(bytes <= Math.multiplyExact(archive.length().coerceAtLeast(1), 200)) { "Vendor gzip expansion ratio exceeds its bound" }
                    hash.update(buffer, 0, n); output.write(buffer, 0, n)
                }
            }
            require(bytes > 0)
            val fileSha = hash.digest().hex()
            val manifest = "${recipe.entryPoint}\u0000493\u0000$fileSha\n".toByteArray(Charsets.UTF_8)
            SafeTreeProjection(root, listOf(SafeTreeFile(recipe.entryPoint, target, 493, bytes, fileSha)),
                emptyList(), bytes, MessageDigest.getInstance("SHA-256").digest(manifest).hex())
        } catch (failure: Throwable) {
            PathSecurity.deleteTreeNoFollow(root)
            throw failure
        }
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun q(value: String) = LocalExecutionSubstrate.shellQuote(value)
}
