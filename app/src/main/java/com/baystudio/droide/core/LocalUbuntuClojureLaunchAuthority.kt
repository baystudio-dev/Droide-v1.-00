package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Created only from the verified publisher projection, then covered by the existing installer seal.
 * This is owned-package consistency, not a signature, manager attestation or same-UID sandbox. */
internal object LocalUbuntuClojureLaunchAuthority {
    const val FILE_NAME = "CLOJURE_LAUNCH_AUTHORITY.json"

    fun create(recipe: LocalUbuntuVendorRecipe, projection: SafeTreeProjection, activationId: String,
        stageRoot: String, finalGuestRoot: String): String {
        recipe.validate()
        require(recipe.familyId == LocalUbuntuClojurePolicy.FAMILY && projection.symlinks.isEmpty())
        val native = projection.files.single { it.relativePath == "clojure-lsp" }
        val origin = projection.files.single { it.relativePath == LocalUbuntuClojurePolicy.ORIGIN_PATH }
        require(projection.files.map { it.relativePath }.toSet() == setOf("clojure-lsp",
            LocalUbuntuClojurePolicy.NOTICE_PATH, LocalUbuntuClojurePolicy.ORIGIN_PATH))
        require(native.mode == 493 && origin.mode == 420)
        return encode(recipe, activationId, stageRoot, finalGuestRoot, native.sha256, native.size, origin.sha256)
    }

    private fun encode(recipe: LocalUbuntuVendorRecipe, activationId: String, stageRoot: String,
        finalGuestRoot: String, nativeSha: String, nativeBytes: Long, originSha: String): String {
        require(activationId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        require(stageRoot.endsWith("/packages/.staging/${LspClojureLaunchPolicy.STAGE}"))
        require(finalGuestRoot.endsWith("/packages/${recipe.familyId}/${recipe.version}/arm64-v8a"))
        require(nativeSha.matches(Regex("[0-9a-f]{64}")) && nativeBytes in 1..268_435_456L)
        require(originSha == LspClojureLaunchPolicy.sha256(LocalUbuntuClojurePolicy.originText(nativeSha, nativeBytes)))
        val target = "$finalGuestRoot/payload/tree/clojure-lsp"
        val wrapper = LocalUbuntuVendorPolicy.guestWrapper(recipe, target, finalGuestRoot)
        return buildJsonObject {
            put("schema", 1); put("family", recipe.familyId); put("version", recipe.version)
            put("archiveSha256", recipe.assetSha256); put("policy", LspClojureLaunchPolicy.REVISION)
            put("finalRoot", finalGuestRoot); put("stageRoot", stageRoot); put("activationId", activationId)
            put("nativeSha256", nativeSha); put("nativeBytes", nativeBytes)
            put("helperSha256", LspClojureLaunchPolicy.sha256(LocalUbuntuClojureLspHealth.script))
            put("wrapperSha256", LspClojureLaunchPolicy.sha256(wrapper)); put("originSha256", originSha)
        }.toString() + "\n"
    }

    fun requireInstalled(record: ManagedPackageRecord, recipe: LocalUbuntuVendorRecipe, appRoot: String) {
        val root = File(record.installRoot)
        val target = File(root, "payload/tree/clojure-lsp")
        LocalUbuntuClojurePolicy.requireTree(target)
        LocalUbuntuClojurePolicy.requireElfStructure(target)
        val nativeSha = measure(target, LocalUbuntuClojurePolicy.MAX_MEMBER_BYTES).second
        val origin = File(root, "payload/tree/${LocalUbuntuClojurePolicy.ORIGIN_PATH}")
        val stage = LocalPackageInstallJournal.paths(appRoot, recipe.familyId, recipe.version,
            LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE).stage.absolutePath
        val guest = requireNotNull(LocalLinuxPackageEnvironment.guestPath(root.absolutePath, "$appRoot/packages/"))
        val activationId = requireNotNull(record.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY])
        require(LocalPackageInstallJournal.matchesActivation(root, activationId))
        val file = File(root, FILE_NAME)
        require(file.isFile && !PathSecurity.isSymbolicLink(file) && file.length() in 1..16_384)
        require(readBoundedText(file, 16_384) == encode(recipe, activationId, stage, guest, nativeSha, target.length(), measure(origin, 16_384).second)) {
            "Clojure launch authority differs from the owned current receipt"
        }
    }

    /** Same-UID atomic replacement is outside package authority; enforce finite reads and detect observable drift. */
    private fun attributes(file: File): BasicFileAttributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    private fun unchanged(a: BasicFileAttributes, b: BasicFileAttributes) = a.isRegularFile && b.isRegularFile &&
        a.fileKey() != null && a.fileKey() == b.fileKey() && a.size() == b.size() && a.lastModifiedTime() == b.lastModifiedTime()
    private val interruptCheck: () -> Unit = { if (Thread.currentThread().isInterrupted) throw CancellationException("Clojure receipt read canceled") }
    private fun visit(file: File, limit: Long, active: () -> Unit, consume: (ByteArray, Int) -> Unit): Long {
        require(limit in 1..LocalUbuntuClojurePolicy.MAX_MEMBER_BYTES)
        active()
        val before = attributes(file)
        require(before.isRegularFile && before.fileKey() != null && before.size() in 1..limit) { "Clojure receipt file is not bounded regular data" }
        var bytes = 0L
        FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { input ->
            require(input.size() == before.size() && unchanged(before, attributes(file))) { "Clojure receipt changed before read" }
            val buffer = ByteBuffer.allocate(65_536)
            while (true) {
                active(); buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), before.size() + 1 - bytes).toInt())
                val count = input.read(buffer)
                if (count < 0) break
                require(count > 0) { "Clojure receipt read made no progress" }
                bytes = Math.addExact(bytes, count.toLong())
                require(bytes <= before.size() && bytes <= limit) { "Clojure receipt grew beyond admitted bound" }
                consume(buffer.array(), count)
            }
            active()
            require(bytes == before.size() && input.size() == bytes && unchanged(before, attributes(file))) { "Clojure receipt identity changed while reading" }
        }
        return bytes
    }
    internal fun measure(file: File, limit: Long, active: () -> Unit = interruptCheck): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = visit(file, limit, active) { buffer, count -> digest.update(buffer, 0, count) }
        return bytes to digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    internal fun readBoundedText(file: File, limit: Long, active: () -> Unit = interruptCheck): String {
        require(limit in 1..65_536)
        val output = ByteArrayOutputStream()
        visit(file, limit, active) { buffer, count -> output.write(buffer, 0, count) }
        return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }
}
