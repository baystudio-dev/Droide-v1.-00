package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Sqruff-only portable inspection. No shell, health, executable probing or generic verify(record) path. */
internal object LocalUbuntuSqruffLaunchAuthority {
    private const val SMALL_FILE_LIMIT = 65_536L
    private val files = setOf("DROIDE_ACTIVATION_ID", "RECIPE_LOCK.json", "TREE_LINKS.tsv", "TREE_COMMANDS.tsv",
        LocalUbuntuSqruffLspHealth.FILE_NAME, "payload/bin/sqruff", "payload/host-bin/sqruff", "payload/tree/sqruff")
    private val directories = setOf("payload", "payload/bin", "payload/host-bin", "payload/tree")

    suspend fun inspect(appRoot: String, record: ManagedPackageRecord, records: List<ManagedPackageRecord>,
        selections: Map<String, String>, discovered: String): SqruffManagedLaunchIdentity? {
        val context = currentCoroutineContext()
        val active = { context.ensureActive() }
        return try {
            active()
            require(records.size <= 512 && records.count { it.familyId == record.familyId && it.version == record.version } == 1)
            require(record.familyId == LocalUbuntuSqruffPolicy.FAMILY && record.version == LocalUbuntuSqruffPolicy.VERSION)
            require(LocalLinuxPackageEnvironment.selectedRecords(records, selections, appRoot).singleOrNull { it.familyId == record.familyId } == record)
            // An explicit unavailable selection must not gain identity through selectedRecords' active
            // fallback.
            require(selections[record.familyId] == null || selections[record.familyId] == record.version)
            require(record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name && record.abi == "arm64-v8a" && record.checksumFile == "SHA256SUMS")
            require(record.metadata[LocalManagedPackageMetadata.PROFILE_KEY] == DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name &&
                record.metadata[LocalManagedPackageMetadata.LIBC_KEY] == DeclarativeLibcCompatibility.GLIBC.name &&
                record.metadata[LocalManagedPackageMetadata.COMMAND_KEY] == "sqruff")
            val root = File(record.installRoot)
            val expectedRoot = LocalPackageInstallJournal.paths(appRoot, record.familyId, record.version,
                LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE).final
            require(root.absolutePath == expectedRoot.absolutePath && root.canonicalPath == root.absolutePath)
            require(records.none { it != record && it.installRoot == record.installRoot })
            requireRealParents(root)
            val guest = requireNotNull(LocalLinuxPackageEnvironment.guestPath(root.absolutePath, "$appRoot/packages/"))
            val wrapper = "$guest/payload/bin/sqruff"
            require(discovered == wrapper || discovered == "/opt/droide/bin/sqruff")
            val activation = requireNotNull(record.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY])
            require(activation.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
            require(readBoundedText(File(root, "DROIDE_ACTIVATION_ID"), 128, active) == activation)
            exactInventory(root, active)
            val recipe = requireNotNull(LocalUbuntuVendorPolicy.fromReceipt(record))
            LocalUbuntuSqruffPolicy.requireReviewed(recipe)
            require(LocalUbuntuVendorPolicy.verifyIntegration(record, recipe, appRoot))
            active()
            val seal = readBoundedText(File(root, "SHA256SUMS"), SMALL_FILE_LIMIT, active)
            val sealSha = sha256(seal.toByteArray(Charsets.UTF_8))
            require(sealSha == record.metadata[LocalManagedPackageMetadata.SEAL_SHA256_KEY])
            val checksums = linkedMapOf<String, String>()
            for (line in seal.split('\n').filter { it.isNotEmpty() }) {
                active()
                require(checksums.size < files.size && line.length > 66 && line.substring(0, 64).matches(Regex("[0-9a-f]{64}")) && line.substring(64, 66) == "  ")
                val relative = line.substring(66)
                require(relative in files && checksums.put(relative, line.substring(0, 64)) == null)
            }
            require(checksums.keys == files)
            for ((relative, expected) in checksums) {
                val limit = if (relative == "payload/tree/sqruff") LocalUbuntuSqruffPolicy.MAX_TREE_BYTES else SMALL_FILE_LIMIT
                require(measure(File(root, relative), limit, active).second == expected)
            }
            val native = File(root, "payload/tree/sqruff")
            for (relative in listOf("payload/tree/sqruff", "payload/bin/sqruff", "payload/host-bin/sqruff")) {
                val file = File(root, relative)
                require(file.canExecute() && (android.system.Os.lstat(file.absolutePath).st_mode and 4095) == 493) {
                    "Sqruff projected executable mode changed"
                }
            }
            LocalUbuntuSqruffPolicy.requireTree(native)
            LinuxArm64ElfAdmission.requireLinuxStatic(native)
            val nativeSha = checksums.getValue("payload/tree/sqruff")
            val treeSha = sha256("sqruff\u0000493\u0000$nativeSha\n".toByteArray(Charsets.UTF_8))
            require(treeSha == record.metadata[LocalManagedPackageMetadata.TREE_SHA256_KEY])
            require(checksums.getValue("TREE_LINKS.tsv") == sha256(byteArrayOf()) &&
                checksums.getValue("TREE_LINKS.tsv") == record.metadata[LocalManagedPackageMetadata.TREE_LINKS_SHA256_KEY])
            require(readBoundedText(File(root, "TREE_COMMANDS.tsv"), SMALL_FILE_LIMIT, active) == "sqruff\tsqruff\n" &&
                checksums.getValue("TREE_COMMANDS.tsv") == record.metadata[LocalManagedPackageMetadata.TREE_COMMANDS_SHA256_KEY])
            val lock = Json.parseToJsonElement(readBoundedText(File(root, "RECIPE_LOCK.json"), SMALL_FILE_LIMIT, active)).jsonObject
            require(lock["treeFiles"]?.jsonPrimitive?.content == "1" && lock["treeSymlinks"]?.jsonPrimitive?.content == "0" &&
                lock["treeBytes"]?.jsonPrimitive?.content == native.length().toString() && lock["treeSha256"]?.jsonPrimitive?.content == treeSha &&
                lock["treeLinksSha256"]?.jsonPrimitive?.content == checksums["TREE_LINKS.tsv"] &&
                lock["commands"] == buildJsonObject { put("sqruff", "sqruff") } &&
                lock["runtimeDependencies"] == JsonArray(record.dependencies.map(::JsonPrimitive)))
            // Inspect the global projection only when it was actually discovered. Never substitute for an
            // unreviewed path.
            var projectionSha = "version-specific"
            if (discovered == "/opt/droide/bin/sqruff") {
                val projection = File(appRoot, "managed/ubuntu-bin/sqruff")
                requireRealParents(projection.parentFile!!)
                val text = readBoundedText(projection, SMALL_FILE_LIMIT, active)
                require(text == "#!/bin/sh\nexec '${wrapper.replace("'", "'\\''")}' \"\$@\"\n")
                require(projection.canExecute())
                projectionSha = sha256(text.toByteArray(Charsets.UTF_8))
            }
            exactInventory(root, active)
            requireRealParents(root); require(root.canonicalPath == root.absolutePath)
            require(readBoundedText(File(root, "SHA256SUMS"), SMALL_FILE_LIMIT, active) == seal)
            active()
            SqruffManagedLaunchIdentity(record.familyId, record.version, LocalUbuntuSqruffPolicy.SOURCE_POLICY_REVISION,
                recipe.assetSha256, nativeSha, receiptSha(record), checksums.getValue("RECIPE_LOCK.json"), sealSha,
                "${record.familyId}@${record.version}:$activation:${selections[record.familyId] ?: "active"}:$projectionSha",
                discovered, wrapper, "$guest/payload/tree/sqruff")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private fun receiptSha(record: ManagedPackageRecord): String {
        fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
        fun mapping(values: Map<String, String>) = JsonObject(values.toSortedMap().mapValues { JsonPrimitive(it.value) })
        val value = buildJsonObject {
            put("family", record.familyId); put("version", record.version); put("scope", record.scope)
            put("root", record.installRoot); put("installedAt", record.installedAtEpochMs); put("active", record.active)
            put("paths", strings(record.pathEntries)); put("environment", mapping(record.environment)); put("commands", mapping(record.commands))
            put("dependencies", strings(record.dependencies)); put("metadata", mapping(record.metadata))
            put("health", JsonArray(record.healthChecks.map { check -> buildJsonObject {
                put("executable", check.executable); put("args", strings(check.args))
            } }))
            put("artifact", record.artifactSha256?.let(::JsonPrimitive) ?: JsonNull)
            put("checksum", record.checksumFile); put("abi", record.abi?.let(::JsonPrimitive) ?: JsonNull)
        }
        return sha256(value.toString().toByteArray(Charsets.UTF_8))
    }
    private fun exactInventory(root: File, active: () -> Unit) {
        val seenFiles = mutableSetOf<String>(); val seenDirs = mutableSetOf<String>()
        fun walk(dir: File) {
            active()
            require(dir.isDirectory && !PathSecurity.isSymbolicLink(dir))
            val children = requireNotNull(dir.listFiles())
            require(children.size <= files.size + 1)
            for (child in children) {
                active(); val relative = child.relativeTo(root).invariantSeparatorsPath
                val attributes = Files.readAttributes(child.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(!attributes.isSymbolicLink)
                if (attributes.isDirectory) { require(relative in directories && seenDirs.add(relative)); walk(child) }
                else { require(attributes.isRegularFile && relative in files + "SHA256SUMS" && seenFiles.add(relative))
                    requireSingleLink(child)
                    require(attributes.size() <= if (relative == "payload/tree/sqruff") LocalUbuntuSqruffPolicy.MAX_TREE_BYTES else SMALL_FILE_LIMIT) }
            }
        }
        walk(root); require(seenDirs == directories && seenFiles == files + "SHA256SUMS")
    }
    private fun requireRealParents(directory: File) {
        val path = directory.toPath().toAbsolutePath(); var cursor = requireNotNull(path.root)
        for (part in path) { cursor = cursor.resolve(part)
            val attributes = Files.readAttributes(cursor, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attributes.isDirectory && !attributes.isSymbolicLink)
        }
    }
    private fun requireSingleLink(file: File) {
        // Android API 21 lstat exposes link count directly; do not assume a JDK-only file-attribute
        // view.
        val count = android.system.Os.lstat(file.absolutePath).st_nlink
        require(count == 1L) { "Sqruff managed file is linked or link identity is unavailable" }
    }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun unchanged(a: BasicFileAttributes, b: BasicFileAttributes) = a.isRegularFile && b.isRegularFile &&
        a.fileKey() != null && a.fileKey() == b.fileKey() && a.size() == b.size() && a.lastModifiedTime() == b.lastModifiedTime()
    private fun visit(file: File, limit: Long, active: () -> Unit, consume: (ByteArray, Int) -> Unit): Long {
        active(); requireSingleLink(file)
        val before = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(before.isRegularFile && !before.isSymbolicLink && before.fileKey() != null && before.size() in 0..limit)
        var bytes = 0L
        FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { input ->
            require(input.size() == before.size())
            val buffer = ByteBuffer.allocate(65_536)
            while (true) {
                active(); buffer.clear(); buffer.limit(minOf(buffer.capacity().toLong(), before.size() + 1 - bytes).toInt())
                val n = input.read(buffer); if (n < 0) break
                require(n > 0); bytes += n; require(bytes <= before.size() && bytes <= limit); consume(buffer.array(), n)
            }
            active(); requireSingleLink(file); require(bytes == before.size() && input.size() == bytes && unchanged(before,
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)))
        }
        return bytes
    }
    internal fun measure(file: File, limit: Long, active: () -> Unit): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = visit(file, limit, active) { buffer, n -> digest.update(buffer, 0, n) }
        return bytes to digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    internal fun readBoundedText(file: File, limit: Long, active: () -> Unit = {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Sqruff inspection canceled")
    }): String {
        require(limit in 1..SMALL_FILE_LIMIT)
        val output = ByteArrayOutputStream(); visit(file, limit, active) { buffer, n -> output.write(buffer, 0, n) }
        return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }
}
