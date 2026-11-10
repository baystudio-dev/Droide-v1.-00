package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Read-only admission. Source expectations are not an assertion that original artifacts or a guest passed. */
internal object LocalUbuntuPowerShellRuntimePolicy {
    const val FAMILY = "runtime.powershell"
    const val VERSION = "7.6.7"
    const val COMMAND = "pwsh"
    const val REPOSITORY = "PowerShell/PowerShell"
    const val SOURCE_COMMIT = "c10ef6d557c0d6fe661836f78dbd6fc355261326"
    const val RELEASE_ID = 412_050_033L
    const val ASSET_ID = 638_079_095L
    const val TAG = "v7.6.7"
    const val ASSET = "powershell-7.6.7-linux-arm64.tar.gz"
    const val SHA256 = "ba465efdfb0984c1c5dc5ed9eb0b79a7e91a1b52c45f99377058c0659b84d3f1"
    const val ARTIFACT_BYTES = 71_930_903L
    const val API_URL = "https://api.github.com/repos/$REPOSITORY/releases/$RELEASE_ID"
    const val RELEASE_URL = "https://github.com/$REPOSITORY/releases/tag/$TAG"
    const val ASSET_URL = "https://github.com/$REPOSITORY/releases/download/$TAG/$ASSET"
    const val MAX_FILES = 4096
    const val MAX_TREE_BYTES = 512L * 1024 * 1024
    const val DOTNET_VERSION = "10.0.13"
    const val INTERPRETER = "/lib/ld-linux-aarch64.so.1"
    private const val MAX_JSON = 4 * 1024 * 1024
    val nativeLibraries = setOf("libclrgc.so", "libclrjit.so", "libcoreclr.so", "libcoreclrtraceptprovider.so",
        "libhostfxr.so", "libhostpolicy.so", "libmscordaccore.so", "libmscordbi.so", "libpsl-native.so",
        "libSystem.Globalization.Native.so", "libSystem.IO.Compression.Native.so", "libSystem.IO.Ports.Native.so",
        "libSystem.Native.so", "libSystem.Net.Security.Native.so", "libSystem.Security.Cryptography.Native.OpenSsl.so")
    val requiredData = setOf("pwsh.dll", "pwsh.deps.json", "pwsh.runtimeconfig.json", "System.Management.Automation.dll",
        "System.Private.CoreLib.dll", "LICENSE.txt", "ThirdPartyNotices.txt")
    private val executables = setOf(COMMAND, "createdump")

    fun requireReviewed(recipe: LocalUbuntuVendorRecipe) {
        require(recipe.familyId == FAMILY && recipe.definition == LocalUbuntuVendorCatalog.find(FAMILY) &&
            recipe.version == VERSION && recipe.releaseTag == TAG && recipe.assetName == ASSET &&
            recipe.assetUrl == ASSET_URL && recipe.assetSha256 == SHA256 && recipe.assetSize == ARTIFACT_BYTES &&
            recipe.provenanceUrl == RELEASE_URL) { "Only the exact reviewed PowerShell $VERSION Linux ARM64 original archive is supported" }
    }
    fun executablePaths(recipe: LocalUbuntuVendorRecipe): Set<String> { requireReviewed(recipe); return executables }

    fun releaseRecipe(definition: LocalUbuntuVendorDefinition, requested: String?, text: String): LocalUbuntuVendorRecipe {
        require(requested == VERSION && definition == LocalUbuntuVendorCatalog.find(FAMILY)) { "Choose the explicit reviewed PowerShell $VERSION release; no version is substituted" }
        requireRelease(text, RELEASE_ID, API_URL, RELEASE_URL, TAG, ASSET_ID, ASSET, ARTIFACT_BYTES, ASSET_URL, SHA256)
        return LocalUbuntuVendorRecipe(definition, VERSION, TAG, ASSET, ASSET_URL, SHA256, ARTIFACT_BYTES, RELEASE_URL).also(::requireReviewed)
    }

    internal fun requireRelease(text: String, releaseId: Long, api: String, page: String, tag: String,
        assetId: Long, assetName: String, bytes: Long, assetUrl: String, sha256: String) {
        val release = strictObject(text, 1_000_000)
        scalar(release, "id", releaseId.toString()); scalar(release, "draft", "false"); scalar(release, "prerelease", "false")
        require(string(release, "url") == api && string(release, "html_url") == page && string(release, "tag_name") == tag) { "PowerShell exact release identity changed" }
        val assets = release["assets"] as? JsonArray ?: error("PowerShell publisher assets missing")
        require(assets.size in 1..256)
        val named = assets.map { element ->
            val asset = element as? JsonObject ?: error("PowerShell publisher asset is not an object")
            string(asset, "name") to asset
        }
        require(named.map { it.first }.toSet().size == named.size) { "Duplicate PowerShell publisher asset name" }
        val asset = named.singleOrNull { it.first == assetName }?.second ?: error("Exact PowerShell asset missing")
        scalar(asset, "id", assetId.toString()); scalar(asset, "size", bytes.toString())
        require(string(asset, "state") == "uploaded" && string(asset, "digest") == "sha256:$sha256" &&
            string(asset, "browser_download_url") == assetUrl) { "PowerShell exact asset identity or digest changed" }
    }
    internal fun string(o: JsonObject, key: String): String = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: error("PowerShell JSON '$key' must be a string")
    private fun scalar(o: JsonObject, key: String, expected: String) {
        require((o[key] as? JsonPrimitive)?.let { !it.isString && it.content == expected } == true) { "PowerShell JSON '$key' has wrong scalar type or value" }
    }

    enum class NativeRole { EXECUTABLE, SHARED_LIBRARY, GUEST_LOADER, GUEST_LIBC }
    data class NativeImage(val relativePath: String, val role: NativeRole, val needed: List<String>, val soname: String?,
        val originSearch: Boolean, val versionNeeds: Map<String, Set<String>>, val versionDefinitions: Set<String>)
    class NativeInventory internal constructor(val root: File, val files: Set<String>, val native: Map<String, NativeImage>, val expandedBytes: Long)
    data class GuestClosure(val nativeFiles: Map<String, String>, val versionNeeds: Map<String, Set<String>>)

    /** Magic-based full inventory, including ELF hidden under a non-native suffix. Does not execute/import anything. */
    fun requireTree(entry: File, active: () -> Unit = {}): NativeInventory {
        require(entry.name == COMMAND)
        val root = requireNotNull(entry.absoluteFile.parentFile)
        requireDirectory(root)
        val files = linkedSetOf<String>(); val native = linkedMapOf<String, NativeImage>(); val folded = hashSetOf<String>()
        var bytes = 0L; var entries = 0
        fun walk(directory: File, prefix: String, depth: Int) {
            require(depth <= 32); requireDirectory(directory)
            Files.newDirectoryStream(directory.toPath()).use { children ->
                for (path in children) {
                    active(); require(++entries <= MAX_FILES * 2) { "PowerShell tree entry bound exceeded" }
                    val name = path.fileName.toString(); requireName(name)
                    val relative = prefix + name
                    require(folded.add(relative.lowercase(java.util.Locale.ROOT))) { "PowerShell tree case-colliding member" }
                    if (Files.isDirectory(path, NOFOLLOW_LINKS)) walk(path.toFile(), "$relative/", depth + 1)
                    else {
                        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) { "PowerShell tree contains a link or special file: $relative" }
                        require(files.add(relative) && files.size <= MAX_FILES)
                        val size = Files.size(path); bytes = Math.addExact(bytes, size)
                        require(bytes <= MAX_TREE_BYTES) { "PowerShell tree expanded bound exceeded" }
                        val magic = readPrefix(path.toFile(), 4)
                        if (magic.contentEquals(byteArrayOf(127, 69, 76, 70))) {
                            val role = when (relative) { in executables -> NativeRole.EXECUTABLE; in nativeLibraries -> NativeRole.SHARED_LIBRARY
                                else -> error("PowerShell unreviewed native member: $relative") }
                            native[relative] = inspectElf(path.toFile(), relative, role, active)
                        } else require(relative !in executables && relative !in nativeLibraries) { "PowerShell native role is not ELF: $relative" }
                    }
                }
            }
        }
        walk(root, "", 0)
        require(files.containsAll(requiredData) && native.keys == executables + nativeLibraries && files.any { it.startsWith("Modules/") }) { "PowerShell original flat runtime layout or required member missing" }
        requiredData.forEach { require(Files.size(File(root, it).toPath()) > 0) { "PowerShell required data is empty: $it" } }
        require(native.values.mapNotNull { it.soname }.let { it.size == it.toSet().size }) { "PowerShell duplicate SONAME" }
        requireRuntimeConfiguration(root, files, active)
        return NativeInventory(root, files.toSet(), native.toMap(), bytes)
    }

    /** Source-proposed finite ABI families, verified from guest bytes at admission, never inferred from dpkg status. */
    private val guestNames = setOf("ld-linux-aarch64.so.1", "libc.so.6", "libm.so.6", "libdl.so.2", "libpthread.so.0", "librt.so.1",
        "libresolv.so.2", "libgcc_s.so.1", "libstdc++.so.6", "libz.so.1", "libgssapi_krb5.so.2", "libkrb5.so.3", "libk5crypto.so.3",
        "libcom_err.so.2", "libkrb5support.so.0", "libkeyutils.so.1", "libicuuc.so.74", "libicui18n.so.74", "libicudata.so.74", "libssl.so.3", "libcrypto.so.3")
    private val dlopenNames = setOf("libicuuc.so.74", "libicui18n.so.74", "libicudata.so.74", "libssl.so.3", "libcrypto.so.3", "libgssapi_krb5.so.2", "libz.so.1")

    /** Checks actual native edges and version definitions recursively. This is not an execution/device or arbitrary-module proof. */
    fun requireExecutionClosure(inventory: NativeInventory, guestRoot: File, active: () -> Unit = {}): GuestClosure {
        requireDirectory(guestRoot)
        val local = inventory.native.values.filter { it.role == NativeRole.SHARED_LIBRARY }.associateBy { it.soname ?: it.relativePath }
        require(local.size == nativeLibraries.size && local.keys.none { it in guestNames }) { "PowerShell ambiguous local native identities" }
        val observed = linkedMapOf<String, NativeImage>(); val paths = linkedMapOf<String, String>(); val needs = linkedMapOf<String, MutableSet<String>>()
        var total = 0L
        fun resolve(name: String): NativeImage {
            require(name in guestNames) { "PowerShell native dependency has no reviewed guest mapping: $name" }
            observed[name]?.let { return it }
            val candidates = (if (name == "ld-linux-aarch64.so.1") listOf(INTERPRETER, "/usr/lib/aarch64-linux-gnu/$name", "/lib/aarch64-linux-gnu/$name")
                else listOf("/usr/lib/aarch64-linux-gnu/$name", "/lib/aarch64-linux-gnu/$name"))
                .mapNotNull { resolveGuestFile(guestRoot, it, active) }.distinctBy { it.absolutePath }
            val file = candidates.singleOrNull() ?: error("PowerShell native dependency unresolved or ambiguous: $name")
            total = Math.addExact(total, Files.size(file.toPath())); require(total <= MAX_TREE_BYTES && observed.size < 64)
            val role = when (name) { "ld-linux-aarch64.so.1" -> NativeRole.GUEST_LOADER; "libc.so.6" -> NativeRole.GUEST_LIBC; else -> NativeRole.SHARED_LIBRARY }
            val image = inspectElf(file, name, role, active)
            require(image.soname == name && !image.originSearch) { "PowerShell guest SONAME or search path changed: $name" }
            observed[name] = image; paths[name] = file.absolutePath
            return image
        }
        val visiting = hashSetOf<String>(); val complete = hashSetOf<String>()
        fun visit(image: NativeImage, guest: Boolean) {
            active(); val key = (if (guest) "guest:" else "runtime:") + image.relativePath
            if (key in complete || !visiting.add(key)) return
            for (needed in image.needed) {
                val target = if (!guest) local[needed] else null
                if (target != null) require(image.originSearch) { "PowerShell package-local dependency lacks reviewed ORIGIN search: ${image.relativePath} -> $needed" }
                val dependency = target ?: resolve(needed)
                val required = image.versionNeeds[needed].orEmpty()
                require(dependency.versionDefinitions.containsAll(required)) { "PowerShell native symbol version unresolved: $needed requires ${required - dependency.versionDefinitions}" }
                needs.getOrPut(needed) { linkedSetOf() }.addAll(required)
                visit(dependency, target == null)
            }
            require(image.versionNeeds.keys.all { it in image.needed }) { "PowerShell version requirement has no DT_NEEDED edge" }
            visiting.remove(key); complete.add(key)
        }
        inventory.native.values.forEach { visit(it, false) }
        (dlopenNames + "ld-linux-aarch64.so.1").forEach { visit(resolve(it), true) }
        val interpreter = resolveGuestFile(guestRoot, INTERPRETER, active)
        require(interpreter != null && interpreter.absolutePath == paths["ld-linux-aarch64.so.1"]) { "PowerShell exact guest interpreter is missing or differs from inspected loader" }
        requireGuestLoaderConfiguration(guestRoot, paths, active)
        return GuestClosure(paths.toMap(), needs.mapValues { it.value.toSet() })
    }

    /** Resolve symlinks using guest absolute semantics, never the host root. Unknown/missing paths fail at their caller. */
    internal fun resolveGuestFile(root: File, guestPath: String, active: () -> Unit = {}, directory: Boolean = false): File? {
        require(guestPath.startsWith('/') && '\\' !in guestPath && '\u0000' !in guestPath)
        requireDirectory(root)
        val base = root.absoluteFile.toPath().normalize(); var remaining = guestPath.split('/').filter { it.isNotEmpty() }.toMutableList()
        val done = mutableListOf<String>(); var links = 0; var steps = 0
        while (remaining.isNotEmpty()) {
            active(); require(++steps <= 256 && remaining.size + done.size <= 64)
            val part = remaining.removeAt(0)
            if (part == ".") continue
            if (part == "..") { require(done.isNotEmpty()) { "PowerShell guest link escapes guest root" }; done.removeAt(done.lastIndex); continue }
            requireName(part)
            val path = done.fold(base) { at, p -> at.resolve(p) }.resolve(part)
            if (Files.isSymbolicLink(path)) {
                require(++links <= 40) { "PowerShell guest link cycle" }
                val target = Files.readSymbolicLink(path).toString(); require(target.length in 1..4096 && '\\' !in target && '\u0000' !in target)
                if (target.startsWith('/')) done.clear()
                remaining = (target.split('/').filter { it.isNotEmpty() } + remaining).toMutableList()
            } else {
                if (!Files.exists(path, NOFOLLOW_LINKS)) return null
                if (remaining.isNotEmpty()) require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "PowerShell guest native ancestor is not a directory" }
                else require(if (directory) Files.isDirectory(path, NOFOLLOW_LINKS) else Files.isRegularFile(path, NOFOLLOW_LINKS)) { "PowerShell guest native path has an unexpected type" }
                done += part
            }
        }
        return done.fold(base) { at, p -> at.resolve(p) }.toFile()
    }

    /** All matching cache targets must resolve to the inspected file; unknown/hwcaps variants are not
     * guessed. */
    private fun requireGuestLoaderConfiguration(root: File, expected: Map<String, String>, active: () -> Unit) {
        resolveGuestFile(root, "/etc/ld.so.preload", active)?.let {
            require(readBytes(it, 16_384, active, allowEmpty = true).all { byte -> byte.toInt().toChar() in " \t\r\n" }) {
                "PowerShell guest /etc/ld.so.preload contains unreviewed loader code"
            }
        }
        for (base in listOf("/usr/lib/aarch64-linux-gnu", "/lib/aarch64-linux-gnu", "/usr/lib", "/lib")) {
            for (suffix in listOf("glibc-hwcaps", "tls")) resolveGuestFile(root, "$base/$suffix", active, directory = true)?.let { directory ->
                Files.newDirectoryStream(directory.toPath()).use { require(!it.iterator().hasNext()) { "PowerShell guest loader capability directory requires exact review: $base/$suffix" } }
            }
        }
        val file = resolveGuestFile(root, "/etc/ld.so.cache", active) ?: return
        val bytes = readBytes(file, 4 * 1024 * 1024, active)
        require(bytes.size >= 48 && bytes.copyOfRange(0, 20).contentEquals("glibc-ld.so.cache1.1".toByteArray(Charsets.US_ASCII))) { "PowerShell guest loader cache format is unreviewed" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun u32(at: Int): Long = b.getInt(at).toLong() and 0xffffffffL
        val count = u32(20); val stringSize = u32(24); val stringsAt = 48 + count * 24
        require(count <= 8192 && stringSize in 1..4_194_304 && stringsAt <= bytes.size && stringSize <= bytes.size - stringsAt &&
            (bytes[28].toInt() and 255) in setOf(0, 2) && (29..31).all { bytes[it].toInt() == 0 } && (36..47).all { bytes[it].toInt() == 0 }) { "PowerShell guest loader cache bounds or byte order invalid" }
        val stringsEnd = stringsAt + stringSize
        fun text(index: Long): String {
            require(index in stringsAt until stringsEnd); var end = index.toInt()
            while (end < stringsEnd && bytes[end].toInt() != 0 && end - index <= 4096) end++
            require(end < stringsEnd && bytes[end].toInt() == 0)
            return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, index.toInt(), end - index.toInt())).toString()
        }
        repeat(count.toInt()) { index ->
            active(); val at = 48 + index * 24; val name = text(u32(at + 4)); val path = text(u32(at + 8))
            if (name in expected) {
                require(b.getInt(at) == 0x0a03 && u32(at + 12) == 0L && b.getLong(at + 16) == 0L) { "PowerShell guest loader cache ABI/hwcaps variant is unreviewed: $name" }
                require(path.startsWith('/') && resolveGuestFile(root, path, active)?.absolutePath == expected[name]) { "PowerShell guest loader cache redirects inspected dependency: $name" }
            }
        }
        val extension = u32(32)
        if (extension != 0L) {
            require(extension >= stringsEnd && extension % 4 == 0L && extension + 8 <= bytes.size)
            val at = extension.toInt(); require(b.getInt(at) == -358342284)
            val sections = u32(at + 4); require(sections <= 16 && extension + 8 + sections * 16 <= bytes.size)
            val seen = hashSetOf<Long>()
            repeat(sections.toInt()) { i ->
                val pos = at + 8 + i * 16; val tag = u32(pos); val offset = u32(pos + 8); val size = u32(pos + 12)
                require(seen.add(tag) && tag == 0L && u32(pos + 4) == 0L && offset >= stringsEnd && offset <= bytes.size && size <= bytes.size - offset) { "PowerShell guest loader cache extension is unreviewed" }
            }
        } else require(stringsEnd == bytes.size.toLong()) { "PowerShell guest loader cache trailing data is unreviewed" }
    }

    private fun requireRuntimeConfiguration(root: File, files: Set<String>, active: () -> Unit) {
        fun json(path: String) = strictObject(readText(File(root, path), MAX_JSON, active), MAX_JSON)
        val config = json("pwsh.runtimeconfig.json")
        require(config.keys == setOf("runtimeOptions")) { "PowerShell runtimeconfig top-level member is unreviewed" }
        val options = config["runtimeOptions"] as? JsonObject ?: error("PowerShell runtimeOptions missing")
        require(options.keys.all { it in setOf("tfm", "includedFrameworks", "configProperties") }) { "PowerShell runtimeOptions member is unreviewed" }
        require(string(options, "tfm") == "net10.0" && options["framework"] == null && options["frameworks"] == null &&
            options["additionalProbingPaths"] == null && options["rollForward"] == null) { "PowerShell requires exact self-contained net10.0 without SDK/framework fallback" }
        val included = options["includedFrameworks"] as? JsonArray ?: error("PowerShell included self-contained framework missing")
        require(included.size == 1 && (included.single() as? JsonObject)?.let { it.keys == setOf("name", "version") && string(it, "name") == "Microsoft.NETCore.App" && string(it, "version") == DOTNET_VERSION } == true) { "PowerShell unreviewed bundled .NET runtime patch" }
        val properties = options["configProperties"]?.let { it as? JsonObject ?: error("PowerShell configProperties must be an object") }
        properties?.forEach { (name, value) ->
            require(name in setOf("System.Reflection.Metadata.MetadataUpdater.IsSupported", "System.Runtime.Serialization.EnableUnsafeBinaryFormatterSerialization", "System.Runtime.TieredCompilation", "System.Runtime.TieredPGO", "System.GC.Server", "System.Globalization.Invariant", "System.Globalization.PredefinedCulturesOnly", "System.Runtime.Serialization.EnableUnsafeBinaryFormatterInDesigntimeLicenseContextSerialization")) { "PowerShell runtime configuration property is unreviewed: $name" }
            require(value is JsonPrimitive && !value.isString && value.content in setOf("true", "false"))
            if (name == "System.Globalization.Invariant") require(value.content == "false")
        }
        val deps = json("pwsh.deps.json")
        val targetName = string(deps["runtimeTarget"] as? JsonObject ?: error("PowerShell deps runtimeTarget missing"), "name")
        require(targetName == ".NETCoreApp,Version=v10.0/linux-arm64") { "PowerShell deps target is not reviewed Linux ARM64 net10.0" }
        val targets = deps["targets"] as? JsonObject ?: error("PowerShell deps targets missing")
        val target = targets[targetName] as? JsonObject ?: error("PowerShell deps exact target missing")
        require(targets.keys.all { it == targetName || it == ".NETCoreApp,Version=v10.0" })
        val runtimePackage = "Microsoft.NETCore.App.Runtime.linux-arm64/$DOTNET_VERSION"
        require(runtimePackage in target && target.keys.none { it.startsWith("Microsoft.NETCore.App.Runtime.") && it != runtimePackage }) { "PowerShell deps bundled runtime patch or RID is unreviewed" }
        val foundNative = hashSetOf<String>()
        target.forEach { (_, dependency) ->
            val sections = dependency as? JsonObject ?: error("PowerShell deps dependency must be an object")
            val runtimeTargets = sections["runtimeTargets"]
            if (runtimeTargets != null) {
                require(runtimeTargets is JsonObject)
                runtimeTargets.forEach { (path, value) ->
                    requireRelative(path); val metadata = value as? JsonObject ?: error("PowerShell runtimeTargets metadata missing")
                    val name = path.substringAfterLast('/')
                    require(string(metadata, "rid") == "linux-arm64" && string(metadata, "assetType") == "native" &&
                        path == "runtimes/linux-arm64/native/$name" && name in nativeLibraries && name in files) { "PowerShell deps runtimeTargets has an unreviewed native RID/asset" }
                    foundNative += name
                }
            }
            for (kind in listOf("runtime", "native", "resources")) {
                val section = sections[kind] ?: continue
                require(section is JsonObject)
                section.forEach { (path, _) ->
                    requireRelative(path)

                    val expected = if (kind == "resources") path else path.substringAfterLast('/')
                    require(expected in files) { "PowerShell deps asset missing from original layout: $path" }
                    if (kind == "native") {
                        require(expected in nativeLibraries || expected == "createdump")
                        require(path == expected || path == "runtimes/linux-arm64/native/$expected") { "PowerShell native dependency path has an unreviewed RID/layout" }
                        foundNative += expected
                    }
                }
            }
        }
        require(foundNative.containsAll(nativeLibraries - "libpsl-native.so")) { "PowerShell deps does not account for bundled runtime native members" }
        if ("powershell.config.json" in files) {
            val config = json("powershell.config.json")
            require(config.keys.none { it.contains("ModulePath", true) || it.contains("Startup", true) || it.contains("Experimental", true) }) { "PowerShell configuration contains unreviewed module/startup overrides" }
        }
        require(files.none { it.endsWith(".runtimeconfig.dev.json") }) { "PowerShell development runtime probing configuration is unreviewed" }
    }

    /** Complete program-table and dynamic/string/version-table inspection using bounded reads, never a native loader. */
    internal fun inspectElf(file: File, relative: String, role: NativeRole, active: () -> Unit = {}): NativeImage {
        val before = snapshot(file); require(before.size() in 64..MAX_TREE_BYTES)
        FileChannel.open(file.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS).use { input ->
            val length = input.size(); require(length == before.size())
            fun read(at: Long, count: Int): ByteBuffer {
                active(); require(at >= 0 && count >= 0 && at <= length && count <= length - at) { "PowerShell ELF file range invalid: $relative" }
                val b = ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN); input.position(at)
                while (b.hasRemaining()) { active(); require(input.read(b) > 0) { "PowerShell truncated ELF: $relative" } }; return b
            }
            val h = read(0, 64)
            require(h.getInt(0) == 0x464c457f && h.get(4).toInt() == 2 && h.get(5).toInt() == 1 && h.get(6).toInt() == 1 &&
                h.getShort(18).toInt() == 183 && h.getInt(20) == 1 && h.getShort(52).toInt() == 64) { "PowerShell native member must be ELF64 little-endian AArch64: $relative" }
            val type = h.getShort(16).toInt(); val entry = h.getLong(24); val phoff = h.getLong(32)
            val phnum = h.getShort(56).toInt() and 65535
            require(phoff >= 64 && phnum in 1..512 && h.getShort(54).toInt() == 56 && phoff <= length && phnum * 56L <= length - phoff)
            require(if (role == NativeRole.EXECUTABLE) type in setOf(2, 3) && entry > 0 else type == 3 && (role != NativeRole.SHARED_LIBRARY || entry == 0L)) { "PowerShell native executable/library role mismatch: $relative" }
            data class Segment(val type: Int, val flags: Int, val offset: Long, val address: Long, val bytes: Long, val memory: Long)
            val segments = (0 until phnum).map { i ->
                val p = read(phoff + i * 56L, 56); val off = p.getLong(8); val va = p.getLong(16); val size = p.getLong(32); val mem = p.getLong(40); val align = p.getLong(48)
                require(off >= 0 && size >= 0 && off <= length && size <= length - off && va >= 0 && mem >= size && va <= Long.MAX_VALUE - mem)
                require(align >= 0 && (align <= 1 || align and (align - 1) == 0L))
                if (p.getInt(0) == 1 && align > 1) require(off % align == va % align)
                Segment(p.getInt(0), p.getInt(4), off, va, size, mem)
            }
            val loads = segments.filter { it.type == 1 }; require(loads.isNotEmpty() && loads.any { it.flags and 1 != 0 })
            loads.forEach { require(it.flags and 7 == it.flags && it.flags and 3 != 3) { "PowerShell ELF writable executable load is unreviewed" } }
            val sorted = loads.sortedBy { it.address }
            sorted.zipWithNext().forEach { (a, b) -> require(a.address + a.memory <= b.address) { "PowerShell overlapping ELF load segments" } }
            if (entry != 0L) require(loads.count { it.flags and 1 != 0 && entry >= it.address && entry - it.address < it.bytes } == 1) { "PowerShell ELF entry is not uniquely file-backed executable code" }
            fun map(va: Long, size: Long): Long {
                require(va >= 0 && size > 0)
                val segment = loads.singleOrNull { va >= it.address && va - it.address <= it.bytes && size <= it.bytes - (va - it.address) }
                    ?: error("PowerShell ELF dynamic range has no unique file-backed load: $relative")
                return segment.offset + va - segment.address
            }
            val interp = segments.filter { it.type == 3 }
            if (role in setOf(NativeRole.EXECUTABLE, NativeRole.GUEST_LIBC)) {
                val p = interp.singleOrNull() ?: error("PowerShell ELF requires one glibc interpreter")
                require(p.bytes == INTERPRETER.length + 1L && read(p.offset, p.bytes.toInt()).array().contentEquals((INTERPRETER + '\u0000').toByteArray(Charsets.US_ASCII)))
            } else require(interp.isEmpty()) { "PowerShell shared/loader role cannot contain an interpreter" }
            val dyn = segments.singleOrNull { it.type == 2 } ?: error("PowerShell ELF requires one dynamic table")
            require(dyn.bytes in 16..65536 && dyn.bytes % 16 == 0L && map(dyn.address, dyn.bytes) == dyn.offset)
            val tags = linkedMapOf<Long, MutableList<Long>>(); var ended = false
            for (at in 0 until dyn.bytes step 16) {
                val d = read(dyn.offset + at, 16); val tag = d.getLong(0); val value = d.getLong(8)
                if (ended) require(tag == 0L && value == 0L) { "PowerShell ELF dynamic data follows DT_NULL" }
                else if (tag == 0L) { require(value == 0L); ended = true }
                else { require(value >= 0); tags.getOrPut(tag) { mutableListOf() }.add(value) }
            }
            require(ended); tags.filterKeys { it != 1L }.values.forEach { require(it.size == 1) { "PowerShell duplicate ELF dynamic singleton" } }
            // Finite ELF64/AArch64 ABI metadata only. DT_CONFIG, audit, filters and unknown extensions fail
            // closed.
            val standardTags = (1L..30L).toSet() + (32L..37L).toSet()
            val gnuTags = setOf(0x6ffffef5L, 0x6ffffff0L, 0x6ffffff9L, 0x6ffffffaL, 0x6ffffffbL,
                0x6ffffffcL, 0x6ffffffdL, 0x6ffffffeL, 0x6fffffffL)
            val aarch64Tags = setOf(0x70000001L, 0x70000003L, 0x70000005L)
            require(tags.keys.all { it in standardTags || it in gnuTags || it in aarch64Tags }) { "PowerShell ELF loader tag is unreviewed" }
            fun tag(n: Long): Long? = tags[n]?.single()
            val stringSize = tag(10) ?: error("PowerShell ELF string table length missing")
            require(stringSize in 1..4_194_304)
            val stringAt = map(tag(5) ?: error("PowerShell ELF string table missing"), stringSize)
            val strings = read(stringAt, stringSize.toInt()).array()
            fun stringAt(index: Long): String {
                require(index in 0 until stringSize); var end = index.toInt()
                while (end < strings.size && strings[end].toInt() != 0 && end - index < 4096) end++
                require(end < strings.size && strings[end].toInt() == 0)
                val bytes = strings.copyOfRange(index.toInt(), end)
                require(bytes.all { (it.toInt() and 255) in 32..126 }) { "PowerShell ELF loader string is not bounded ASCII" }
                return bytes.toString(Charsets.US_ASCII)
            }
            fun soname(value: String): String { require(value.matches(Regex("[A-Za-z0-9_+.-]{1,180}")) && value != "." && value != ".."); return value }
            val needed = tags[1].orEmpty().map { soname(stringAt(it)) }
            require(needed.toSet().size == needed.size)
            val name = tag(14)?.let { soname(stringAt(it)) }
            if (role == NativeRole.SHARED_LIBRARY && relative in nativeLibraries) require(name == null || name == relative)
            require(!(tags.containsKey(15) && tags.containsKey(29))) { "PowerShell ambiguous ELF search tags" }
            val search = (tag(15) ?: tag(29))?.let(::stringAt)
            require(search == null || search == "\$ORIGIN" || search == "\${ORIGIN}") { "PowerShell ELF has external or unreviewed loader search path: $relative" }
            val versionNeeds = linkedMapOf<String, MutableSet<String>>(); val definitions = linkedSetOf<String>()
            fun versions(addressTag: Long, countTag: Long, need: Boolean) {
                val addr = tag(addressTag); val count = tag(countTag)
                require((addr == null) == (count == null)); if (addr == null) return
                require(count != null && count in 1..1024)
                var va: Long = addr
                val visited = hashSetOf<Long>()
                repeat(count.toInt()) { index ->
                    require(visited.add(va)); val p = read(map(va, if (need) 16 else 20), if (need) 16 else 20)
                    require(p.getShort(0).toInt() == 1)
                    val auxCount = p.getShort(if (need) 2 else 6).toInt() and 65535; require(auxCount in 1..1024)
                    val owner = if (need) soname(stringAt(p.getInt(4).toLong() and 0xffffffffL)) else ""
                    val aux = p.getInt(if (need) 8 else 12).toLong() and 0xffffffffL
                    val next = p.getInt(if (need) 12 else 16).toLong() and 0xffffffffL
                    require(aux >= if (need) 16 else 20)
                    var av = Math.addExact(va, aux); val auxSeen = hashSetOf<Long>()
                    repeat(auxCount) { ai ->
                        require(auxSeen.add(av)); val a = read(map(av, if (need) 16 else 8), if (need) 16 else 8)
                        val version = stringAt(a.getInt(if (need) 8 else 0).toLong() and 0xffffffffL)
                        require(version.matches(Regex("[A-Za-z0-9_.+-]{1,180}")))
                        if (need) versionNeeds.getOrPut(owner) { linkedSetOf() }.add(version) else definitions += version
                        val an = a.getInt(if (need) 12 else 4).toLong() and 0xffffffffL
                        if (ai == auxCount - 1) require(an == 0L) else { require(an >= if (need) 16 else 8); av = Math.addExact(av, an) }
                    }
                    if (index == count.toInt() - 1) require(next == 0L) else { require(next >= if (need) 16 else 20); va = Math.addExact(va, next) }
                }
            }
            versions(0x6ffffffeL, 0x6fffffffL, true); versions(0x6ffffffcL, 0x6ffffffdL, false)
            require(input.size() == length && sameState(before, snapshot(file))) { "PowerShell ELF changed during inspection" }
            return NativeImage(relative, role, needed, name, search != null, versionNeeds.mapValues { it.value.toSet() }, definitions)
        }
    }

    internal fun requireRelative(value: String) {
        require(value.length in 1..4096 && !value.startsWith('/') && '\\' !in value && ':' !in value)
        val parts = value.split('/'); require(parts.size <= 32); parts.forEach(::requireName)
    }
    private fun requireName(value: String) { require(value.isNotEmpty() && value !in setOf(".", "..") && value.length <= 255 && value.none { it.code < 32 || it.code == 127 || it == '/' || it == '\\' || it == ':' }) }
    internal fun requireDirectory(file: File) {
        var at: File? = file.absoluteFile
        while (at != null) { require(Files.isDirectory(at.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(at.toPath())) { "PowerShell directory missing or linked: $at" }; at = at.parentFile }
    }
    private fun snapshot(file: File): BasicFileAttributes {
        requireDirectory(requireNotNull(file.absoluteFile.parentFile))
        return Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).also { require(it.isRegularFile && !it.isSymbolicLink) }
    }
    private fun sameState(a: BasicFileAttributes, b: BasicFileAttributes) = a.fileKey() == b.fileKey() && a.size() == b.size() && a.lastModifiedTime() == b.lastModifiedTime()
    internal fun measure(file: File, maximum: Long, active: () -> Unit = {}): Pair<Long, String> {
        val before = snapshot(file); require(before.size() in 1..maximum); val hash = MessageDigest.getInstance("SHA-256"); var size = 0L
        FileChannel.open(file.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS).use { input ->
            val bytes = ByteBuffer.allocate(65536)
            while (true) { active(); bytes.clear(); val n = input.read(bytes); if (n < 0) break; require(n > 0); size = Math.addExact(size, n.toLong()); require(size <= maximum); hash.update(bytes.array(), 0, n) }
            require(input.size() == before.size() && size == before.size() && sameState(before, snapshot(file)))
        }
        return size to hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    internal fun readText(file: File, max: Int, active: () -> Unit = {}): String =
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(readBytes(file, max, active))).toString()
    private fun readBytes(file: File, max: Int, active: () -> Unit, allowEmpty: Boolean = false): ByteArray {
        val before = snapshot(file); require(before.size() in (if (allowEmpty) 0L else 1L)..max.toLong())
        val b = ByteBuffer.allocate(before.size().toInt())
        FileChannel.open(file.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS).use { input -> while (b.hasRemaining()) { active(); require(input.read(b) > 0) }; require(input.size() == before.size()) }
        require(sameState(before, snapshot(file))); return b.array()
    }
    private fun readPrefix(file: File, length: Int): ByteArray = FileChannel.open(file.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS).use { input ->
        val b = ByteBuffer.allocate(minOf(input.size(), length.toLong()).toInt()); while (b.hasRemaining()) require(input.read(b) > 0); b.array()
    }
    internal fun strictObject(text: String, maximum: Int = MAX_JSON): JsonObject {
        require(text.toByteArray(Charsets.UTF_8).size in 1..maximum); StrictJson(text).validate()
        return Json.parseToJsonElement(text) as? JsonObject ?: error("PowerShell JSON must be an object")
    }
    private class StrictJson(private val s: String) {
        private var i = 0; private var values = 0
        fun validate() { value(0); white(); require(i == s.length) }
        private fun white() { while (i < s.length && s[i] in " \t\r\n") i++ }
        private fun take(c: Char): Boolean { white(); return if (i < s.length && s[i] == c) { i++; true } else false }
        private fun string(): String {
            white(); val start = i; require(i < s.length && s[i++] == '"')
            while (i < s.length) { val c = s[i++]; if (c == '"') return Json.parseToJsonElement(s.substring(start, i)).jsonPrimitive.content
                require(c.code >= 32); if (c == '\\') { require(i < s.length); val e = s[i++]; require(e in "\"\\/bfnrtu"); if (e == 'u') { require(i + 4 <= s.length && s.substring(i, i + 4).all { it in "0123456789abcdefABCDEF" }); i += 4 } } }
            error("Unterminated PowerShell JSON string")
        }
        private fun value(depth: Int) {
            require(depth <= 48 && ++values <= 100_000); white(); require(i < s.length)
            when (s[i]) {
                '{' -> { i++; val keys = hashSetOf<String>(); if (!take('}')) do { require(keys.add(string())) { "Duplicate PowerShell JSON key" }; require(take(':')); value(depth + 1); if (take('}')) break; require(take(',')) } while (true) }
                '[' -> { i++; if (!take(']')) do { value(depth + 1); if (take(']')) break; require(take(',')) } while (true) }
                '"' -> string()
                else -> { val start = i; while (i < s.length && s[i] !in ",]} \t\r\n") i++; require(i > start); val token = s.substring(start, i); require(token in setOf("true", "false", "null") || token.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))) }
            }
        }
    }
}
