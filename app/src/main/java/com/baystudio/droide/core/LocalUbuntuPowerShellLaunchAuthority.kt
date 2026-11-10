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

/** Read-only PowerShell ownership. Metadata discovery is deliberately distinct from byte proof. */
internal object LocalUbuntuPowerShellLaunchAuthority {
    const val FILE_NAME = "POWERSHELL_LAUNCH_AUTHORITY.json"
    const val NOTICE_FILE = "DROIDE_POWERSHELL_RECONCILIATION.txt"
    const val NOTICE_SHA256 = "86f77f1007adb7813c9a60b0a4c8711d1bc1a8832731291db5e4ab41ecaff3f0"
    val noticeText: String = """
        PowerShell 7.6.7 / PowerShell Editor Services 4.7.0
        Supplementary source/provenance reconciliation. This file is authored by Droide and is not an upstream license or a certified binary SBOM.

        Exact original runtime: PowerShell/PowerShell v7.6.7, source c10ef6d557c0d6fe661836f78dbd6fc355261326, powershell-7.6.7-linux-arm64.tar.gz, 71,930,903 bytes, publisher SHA-256 ba465efdfb0984c1c5dc5ed9eb0b79a7e91a1b52c45f99377058c0659b84d3f1.
        Exact original server: PowerShell/PowerShellEditorServices v4.7.0, source e124c135c6bcb95e94ca1ea7bc4ca032249e4572, PowerShellEditorServices.zip, 20,484,361 bytes, publisher SHA-256 5084f0326cc88539e9d880b08f6c52ce5be4672bb8ecee8921e35f8895a6b9d7.

        Original trees retain LICENSE.txt and ThirdPartyNotices.txt (runtime), LICENSE and NOTICE.txt (PSES), and all bundled module/dependency notices. No original notice is modified or replaced by this report. Both main source repositories use MIT licensing; actual distribution license closure must include their bundled dependencies.

        Source-declared bundle: PSScriptAnalyzer 1.25.0 and PSReadLine 2.4.5. The pinned PSES NOTICE.txt contains version entries older than current build props. Source NuGet/module pins are not measured published assembly identities and do not resolve that discrepancy. The self-contained runtime is expected from source to include .NET 10.0.13; this is not an observed archive assertion.

        Remaining redistribution/compliance gates: inspect the actual original ZIP/TAR inventory, every original dependency notice, published .deps.json and managed assembly identities; reconcile source package/version, stale source notice entries, actual published version and license source. Keep original notices intact. No per-member hashes or comprehensive SBOM have been fabricated. Publisher metadata and source inspection alone do not certify runtime compatibility or license completeness.

        Sources:
        https://github.com/PowerShell/PowerShell/releases/tag/v7.6.7
        https://github.com/PowerShell/PowerShellEditorServices/releases/tag/v4.7.0
        https://github.com/PowerShell/PSScriptAnalyzer/tree/1.25.0
        https://github.com/PowerShell/PSReadLine/tree/v2.4.5
    """.trimIndent() + "\n"
    private const val MANIFEST_LIMIT = 2L * 1024 * 1024
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    internal fun receiptSha(record: ManagedPackageRecord): String {
        fun mapping(values: Map<String, String>) = JsonObject(values.toSortedMap().mapValues { JsonPrimitive(it.value) })
        fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
        return LspPowerShellLaunchPolicy.sha256(buildJsonObject {
            put("family", record.familyId); put("version", record.version); put("root", record.installRoot)
            put("scope", record.scope); put("installedAt", record.installedAtEpochMs); put("active", record.active)
            put("paths", strings(record.pathEntries)); put("environment", mapping(record.environment)); put("commands", mapping(record.commands))
            put("dependencies", strings(record.dependencies)); put("metadata", mapping(record.metadata))
            put("health", JsonArray(record.healthChecks.map { buildJsonObject { put("executable", it.executable); put("args", strings(it.args)) } }))
            put("artifact", record.artifactSha256?.let(::JsonPrimitive) ?: JsonNull)
            put("checksum", record.checksumFile); put("abi", record.abi?.let(::JsonPrimitive) ?: JsonNull)
        }.toString())
    }
    fun create(recipe: LocalUbuntuVendorRecipe, activation: String, stage: String, finalGuest: String,
        treeManifest: String, runtime: ManagedPackageRecord?): String {
        require(LocalUbuntuVendorPolicy.isPowerShell(recipe)); recipe.validate()
        val isRuntime = recipe.familyId == LocalUbuntuPowerShellRuntimePolicy.FAMILY
        require(isRuntime == (runtime == null))
        require(activation.matches(Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
        require(stage.startsWith('/') && finalGuest == "/opt/droide/packages/${recipe.familyId}/${recipe.version}/arm64-v8a")
        return buildJsonObject {
            put("schema", 1); put("family", recipe.familyId); put("version", recipe.version)
            put("archiveSha256", recipe.assetSha256); put("policy", LspPowerShellLaunchPolicy.REVISION)
            put("stageRoot", stage); put("finalRoot", finalGuest); put("activationId", activation)
            put("treeManifestSha256", LspPowerShellLaunchPolicy.sha256(treeManifest))
            put("helperSha256", LspPowerShellLaunchPolicy.sha256(LocalUbuntuPowerShellLspHealth.script))
            put("supplementaryNoticeSha256", NOTICE_SHA256)
            put("runtimeRoot", if (isRuntime) finalGuest else LocalUbuntuPowerShellEditorServicesPolicy.RUNTIME_GUEST.removeSuffix("/payload/tree/pwsh"))
            put("runtimeArchiveSha256", LocalUbuntuPowerShellRuntimePolicy.SHA256)
            put("runtimeActivationId", runtime?.metadata?.get(LocalManagedPackageMetadata.ACTIVATION_ID_KEY) ?: activation)
            put("runtimeSealSha256", runtime?.metadata?.get(LocalManagedPackageMetadata.SEAL_SHA256_KEY) ?: "staging-self")
        }.toString() + "\n"
    }
    private fun parents(root: File) {
        var cursor = requireNotNull(root.toPath().toAbsolutePath().root)
        for (part in root.toPath().toAbsolutePath()) {
            cursor = cursor.resolve(part)
            val a = Files.readAttributes(cursor, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(a.isDirectory && !a.isSymbolicLink) { "PowerShell managed parent is not a real directory" }
        }
    }
    private fun generation(record: ManagedPackageRecord, appRoot: String, active: () -> Unit): Pair<String, String> {
        active()
        val root = File(record.installRoot)
        require(root.absolutePath == root.canonicalPath && root.absolutePath == LocalPackageInstallJournal.paths(appRoot, record.familyId,
            record.version, LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE).final.absolutePath)
        parents(root)
        require(record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name && record.abi == "arm64-v8a" && record.checksumFile == "SHA256SUMS")
        val activation = requireNotNull(record.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY])
        require(readText(File(root, "DROIDE_ACTIVATION_ID"), 128, active) == activation)
        val seal = readText(File(root, "SHA256SUMS"), MANIFEST_LIMIT, active)
        val sealSha = LspPowerShellLaunchPolicy.sha256(seal)
        require(sealSha == record.metadata[LocalManagedPackageMetadata.SEAL_SHA256_KEY])
        val recipe = requireNotNull(LocalUbuntuVendorPolicy.fromReceipt(record))
        require(LocalUbuntuVendorPolicy.verifyIntegration(record, recipe, appRoot))
        return activation to sealSha
    }
    /** Bounded metadata/authoring consistency only. Never walks payloads, runs health or prepares a runtime. */
    suspend fun inspect(appRoot: String, pses: ManagedPackageRecord, records: List<ManagedPackageRecord>,
        selections: Map<String, String>, discovered: String): PowerShellManagedLaunchIdentity? {
        val context = currentCoroutineContext(); val active = { context.ensureActive() }
        return try {
            require(records.size <= 512 && records.map { it.familyId to it.version }.distinct().size == records.size)
            require(pses.familyId == LocalUbuntuPowerShellEditorServicesPolicy.FAMILY && pses.version == LocalUbuntuPowerShellEditorServicesPolicy.VERSION)
            require(LocalLinuxPackageEnvironment.selectedRecords(records, selections, appRoot).singleOrNull { it.familyId == pses.familyId } == pses)
            require(selections[pses.familyId] == null || selections[pses.familyId] == pses.version)
            val runtime = records.single { it.familyId == LocalUbuntuPowerShellRuntimePolicy.FAMILY && it.version == LocalUbuntuPowerShellRuntimePolicy.VERSION }
            LocalUbuntuPowerShellEditorServicesPolicy.requireRuntime(runtime, appRoot)
            require(pses.dependencies.size == 2 && LocalUbuntuPowerShellEditorServicesPolicy.RUNTIME_KEY in pses.dependencies)
            val layerKey = pses.dependencies.single { it != LocalUbuntuPowerShellEditorServicesPolicy.RUNTIME_KEY }
            require(runtime.dependencies == listOf(layerKey))
            val layer = records.single { "${it.familyId}@${it.version}" == layerKey }
            val recipe = requireNotNull(LocalUbuntuVendorPolicy.fromReceipt(pses))
            require(layer.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND] == FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND &&
                layer.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES]?.split(',')?.sorted() == recipe.requiredPackages.sorted())
            val (activation, seal) = generation(pses, appRoot, active)
            val (runtimeActivation, runtimeSeal) = generation(runtime, appRoot, active)
            val guest = "/opt/droide/packages/${pses.familyId}/${pses.version}/arm64-v8a"
            val launch = "$guest/payload/bin/powershell-editor-services"
            require(discovered == launch || discovered == "/opt/droide/bin/powershell-editor-services")
            var projection = "version-specific"
            if (discovered != launch) {
                val file = File(appRoot, "managed/ubuntu-bin/powershell-editor-services"); parents(file.parentFile!!)
                val text = readText(file, 65_536, active)
                require(text == "#!/bin/sh\nexec '$launch' \"${'$'}@\"\n" && file.canExecute())
                projection = LspPowerShellLaunchPolicy.sha256(text)
            }
            requireAuthority(pses, recipe, appRoot, runtime, active)
            requireAuthority(runtime, requireNotNull(LocalUbuntuVendorPolicy.fromReceipt(runtime)), appRoot, null, active)
            PowerShellManagedLaunchIdentity(pses.familyId, pses.version, runtime.familyId, runtime.version,
                LspPowerShellLaunchPolicy.REVISION, recipe.assetSha256, runtime.artifactSha256!!,
                receiptSha(pses), receiptSha(runtime), seal, runtimeSeal,
                LspPowerShellLaunchPolicy.sha256(JsonObject(selections.toSortedMap().mapValues { JsonPrimitive(it.value) }).toString() + ":$projection:" + receiptSha(layer)),
                discovered, launch, LocalUbuntuPowerShellEditorServicesPolicy.RUNTIME_GUEST, activation, runtimeActivation).also {
                require(LspPowerShellLaunchPolicy.matchesIdentity(it))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    private fun requireAuthority(record: ManagedPackageRecord, recipe: LocalUbuntuVendorRecipe, appRoot: String,
        runtime: ManagedPackageRecord?, active: () -> Unit) {
        val root = File(record.installRoot)
        val manifest = readText(File(root, "TREE_SHA256SUMS"), MANIFEST_LIMIT, active)
        val stage = LocalPackageInstallJournal.paths(appRoot, record.familyId, record.version,
            LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE).stage.absolutePath
        val guest = "/opt/droide/packages/${record.familyId}/${record.version}/arm64-v8a"
        require(readText(File(root, FILE_NAME), 65_536, active) == create(recipe,
            record.metadata.getValue(LocalManagedPackageMetadata.ACTIVATION_ID_KEY), stage, guest, manifest, runtime)) {
            "PowerShell launch authority does not match its current runtime/tree generation"
        }
    }
    /** One finite two-tree proof per new process, called only under transaction→package mutation locks. */
    suspend fun prove(appRoot: String, identity: PowerShellManagedLaunchIdentity, records: List<ManagedPackageRecord>, active: () -> Unit) {
        val started = System.nanoTime()
        val check = { active(); check(System.nanoTime() - started <= 45_000_000_000L) { "PowerShell launch identity deadline exceeded" } }
        require(LspPowerShellLaunchPolicy.matchesIdentity(identity))
        val pses = records.single { it.familyId == identity.familyId && it.version == identity.version }
        val runtime = records.single { it.familyId == identity.runtimeFamilyId && it.version == identity.runtimeVersion }
        require(receiptSha(pses) == identity.receiptSha256 && receiptSha(runtime) == identity.runtimeReceiptSha256)
        proveTree(runtime, check); proveTree(pses, check)
        val guestRoot = File(appRoot, "guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/rootfs")
        val native = LocalUbuntuPowerShellRuntimePolicy.requireTree(File(runtime.installRoot, "payload/tree/pwsh"), check)
        LocalUbuntuPowerShellRuntimePolicy.requireExecutionClosure(native, guestRoot, check)
        LocalUbuntuPowerShellEditorServicesPolicy.requireTree(File(pses.installRoot, "payload/tree/${LocalUbuntuPowerShellEditorServicesPolicy.ENTRY}"), check)
        requireInstalledPackages(guestRoot, requireNotNull(LocalUbuntuVendorPolicy.fromReceipt(pses)).requiredPackages, check)
        // Node comes from this explicit Ubuntu package layer, never PATH or another selected Node.
        val node = File(guestRoot, "usr/bin/node")
        parents(node.parentFile!!); LinuxArm64ElfAdmission.requireUbuntuGlibc(node)
        check()
    }
    private fun requireInstalledPackages(root: File, packages: List<String>, active: () -> Unit) {
        val status = readText(File(root, "var/lib/dpkg/status"), 16L * 1024 * 1024, active)
        val installed = mutableSetOf<String>()
        for (paragraph in status.split("\n\n")) {
            active()
            val values = paragraph.lineSequence().filter { !it.startsWith(' ') && ':' in it }.associate { it.substringBefore(':') to it.substringAfter(':').trim() }
            if (values["Status"] == "install ok installed" && values["Architecture"] in setOf("arm64", "all")) values["Package"]?.let(installed::add)
        }
        for (name in packages) require(name in installed) { "PowerShell prerequisite missing: $name in the managed Ubuntu layer" }
    }
    internal fun proveTree(record: ManagedPackageRecord, active: () -> Unit) {
        val recipe = requireNotNull(LocalUbuntuVendorPolicy.fromReceipt(record)); val root = File(record.installRoot)
        val manifest = readText(File(root, "SHA256SUMS"), MANIFEST_LIMIT, active)
        require(LspPowerShellLaunchPolicy.sha256(manifest) == record.metadata[LocalManagedPackageMetadata.SEAL_SHA256_KEY])
        require((android.system.Os.lstat(File(root, "SHA256SUMS").absolutePath).st_mode and 4095) == 420)
        val expected = linkedMapOf<String, String>()
        for (line in manifest.split('\n').filter { it.isNotEmpty() }) {
            active(); require(expected.size < recipe.definition.maxFiles + 16 && line.length > 66 && line.substring(0, 64).matches(Regex("[0-9a-f]{64}")) && line.substring(64, 66) == "  ")
            val relative = line.substring(66); safeRelative(relative); require(expected.put(relative, line.take(64)) == null)
        }
        val files = linkedMapOf<String, File>(); var bytes = 0L; var directories = 0
        fun walk(dir: File, depth: Int) {
            active(); require(depth <= 32 && ++directories <= 8192); parents(dir)
            Files.newDirectoryStream(dir.toPath()).use { entries ->
            for (path in entries) {
                val file = path.toFile()
                active(); val a = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(!a.isSymbolicLink)
                if (a.isDirectory) walk(file, depth + 1) else {
                    val relative = file.relativeTo(root).invariantSeparatorsPath; safeRelative(relative)
                    require(a.isRegularFile && files.size < recipe.definition.maxFiles + 17)
                    require(android.system.Os.lstat(file.absolutePath).st_nlink == 1L)
                    bytes = Math.addExact(bytes, a.size()); require(bytes <= recipe.definition.maxUnpackedBytes + 8L * 1024 * 1024)
                    files[relative] = file
                }
            }
            }
        }
        walk(root, 0); require(files.keys == expected.keys + "SHA256SUMS")
        val verified = linkedMapOf<String, String>()
        for ((relative, checksum) in expected) {
            active()
            val executable = relative in setOf("payload/bin/${recipe.command}", "payload/host-bin/${recipe.command}") ||
                relative.startsWith("payload/tree/") && relative.removePrefix("payload/tree/") in LocalUbuntuVendorPolicy.executablePaths(recipe)
            require((android.system.Os.lstat(files.getValue(relative).absolutePath).st_mode and 4095) == if (executable) 493 else 420) {
                "PowerShell managed file mode changed: $relative"
            }
            val measured = measure(files.getValue(relative), recipe.definition.maxUnpackedBytes, active).second
            require(measured == checksum) { "PowerShell managed content changed: $relative" }; verified[relative] = measured
        }
        require(readText(File(root, "TREE_LINKS.tsv"), 1, active).isEmpty())
        val tree = verified.filterKeys { it.startsWith("payload/tree/") }
        val digest = MessageDigest.getInstance("SHA-256")
        for ((key, sha) in tree.toSortedMap()) {
            val relative = key.removePrefix("payload/tree/")
            val mode = if (relative in LocalUbuntuVendorPolicy.executablePaths(recipe)) 493 else 420
            require((android.system.Os.lstat(files.getValue(key).absolutePath).st_mode and 4095) == mode)
            digest.update("$relative\u0000$mode\u0000$sha\n".toByteArray(Charsets.UTF_8))
        }
        require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == record.metadata[LocalManagedPackageMetadata.TREE_SHA256_KEY])
        require(readText(File(root, "SHA256SUMS"), MANIFEST_LIMIT, active) == manifest)
    }
    private fun safeRelative(value: String) {
        require(value.length in 1..4095 && !value.startsWith('/') && '\\' !in value && value.none { it.code < 32 || it.code == 127 } &&
            value.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
    }
    internal fun readText(file: File, limit: Long, active: () -> Unit = {}): String {
        val output = ByteArrayOutputStream(); visit(file, limit, active) { b, n -> output.write(b, 0, n) }
        return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }
    private fun measure(file: File, limit: Long, active: () -> Unit): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256"); val count = visit(file, limit, active) { b, n -> digest.update(b, 0, n) }
        return count to digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun visit(file: File, limit: Long, active: () -> Unit, consume: (ByteArray, Int) -> Unit): Long {
        active(); parents(requireNotNull(file.parentFile))
        val before = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(before.isRegularFile && !before.isSymbolicLink && before.fileKey() != null && before.size() in 0..limit)
        require(android.system.Os.lstat(file.absolutePath).st_nlink == 1L)
        var bytes = 0L
        FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteBuffer.allocate(65_536)
            while (true) {
                active(); buffer.clear(); buffer.limit(minOf(buffer.capacity().toLong(), before.size() + 1 - bytes).toInt())
                val n = input.read(buffer); if (n < 0) break; require(n > 0)
                bytes = Math.addExact(bytes, n.toLong()); require(bytes <= before.size()); consume(buffer.array(), n)
            }
            val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(bytes == before.size() && input.size() == bytes && before.fileKey() == after.fileKey() && before.size() == after.size() &&
                before.lastModifiedTime() == after.lastModifiedTime() && after.isRegularFile && !after.isSymbolicLink)
        }
        return bytes
    }
}
