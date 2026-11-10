package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Authored JSON and ELF data only. No publisher binary, PowerShell/native process or loader is executed. */
class LocalUbuntuPowerShellRuntimePolicyTest {
    private val p get() = LocalUbuntuPowerShellRuntimePolicy
    private val definition get() = requireNotNull(LocalUbuntuVendorCatalog.find(p.FAMILY))
    private val asset get() = """{"id":${p.ASSET_ID},"name":"${p.ASSET}","size":${p.ARTIFACT_BYTES},"state":"uploaded","digest":"sha256:${p.SHA256}","browser_download_url":"${p.ASSET_URL}"}"""
    private val release get() = """{"id":${p.RELEASE_ID},"url":"${p.API_URL}","html_url":"${p.RELEASE_URL}","tag_name":"${p.TAG}","draft":false,"prerelease":false,"assets":[$asset]}"""
    private fun reject(block: () -> Unit) = assertTrue("Expected PowerShell admission rejection", runCatching(block).isFailure)
    private fun temporary(block: (File) -> Unit) { val d = Files.createTempDirectory("powershell-policy-").toFile(); try { block(d) } finally { d.deleteRecursively() } }

    @Test fun exactRuntimeMetadataAndInternalExecutableRole() {
        val r = p.releaseRecipe(definition, p.VERSION, release); p.requireReviewed(r)
        assertEquals(setOf("pwsh", "createdump"), p.executablePaths(r))
        assertEquals("7.6.7", r.version); assertEquals(p.ARTIFACT_BYTES, r.assetSize)
        for (requested in listOf(null, "", "latest", "Latest official", "v7.6.7", "7.6.6")) reject { p.releaseRecipe(definition, requested, release) }
        for (bad in listOf(r.copy(version = "7.6.8"), r.copy(releaseTag = "7.6.7"), r.copy(assetName = "powershell-7.6.7-linux-x64.tar.gz"),
            r.copy(assetSha256 = "0".repeat(64)), r.copy(assetSize = null), r.copy(assetSize = 1), r.copy(assetUrl = p.API_URL), r.copy(provenanceUrl = p.API_URL))) reject { p.requireReviewed(bad) }
    }
    @Test fun strictPublisherTypedScalarAndDuplicateFailures() {
        val original = Json.parseToJsonElement(release).jsonObject
        val choices = listOf<JsonElement>(JsonNull, JsonPrimitive(true), JsonPrimitive("false"), JsonPrimitive(1), JsonPrimitive("1"), JsonObject(emptyMap()), JsonArray(emptyList()))
        for (key in listOf("id", "url", "html_url", "tag_name", "draft", "prerelease")) {
            reject { p.releaseRecipe(definition, p.VERSION, JsonObject(original - key).toString()) }
            for (value in choices) if (value != original[key]) reject { p.releaseRecipe(definition, p.VERSION, JsonObject(original + (key to value)).toString()) }
        }
        val a = Json.parseToJsonElement(asset).jsonObject
        for (key in a.keys) {
            reject { p.releaseRecipe(definition, p.VERSION, JsonObject(original + ("assets" to JsonArray(listOf(JsonObject(a - key))))).toString()) }
            for (value in choices) if (value != a[key]) reject { p.releaseRecipe(definition, p.VERSION, JsonObject(original + ("assets" to JsonArray(listOf(JsonObject(a + (key to value)))))).toString()) }
        }
        for (bad in listOf(release.replace("\"draft\":false", "\"draft\":false,\"draft\":false"), release.replace("\"draft\":false", "\"dr\\u0061ft\":false,\"draft\":false"),
            release.replace(p.RELEASE_ID.toString(), "${p.RELEASE_ID}.0"), release.replace("[$asset]", "[$asset,$asset]"), "[]", "null", " ".repeat(1_000_001))) reject { p.releaseRecipe(definition, p.VERSION, bad) }
        assertEquals(p.VERSION, p.releaseRecipe(definition, p.VERSION, JsonObject(original + ("body" to JsonPrimitive("ordinary notes"))).toString()).version)
    }
    @Test fun strictJsonBoundsAndEscapedDuplicateKeys() {
        for (bad in listOf("{\"x\":1,\"x\":2}", "{\"x\":{\"a\":1,\"\\u0061\":2}}", "{\"x\":01}", "{\"x\":NaN}", "{\"x\":true,}", "{\"x\":[1,]}", "{}{}", "{\"x\":\"bad\nstring\"}", "[".repeat(49) + "0" + "]".repeat(49))) reject { p.strictObject(bad) }
        assertEquals(JsonPrimitive(true), p.strictObject("{\"valid\":true}")["valid"])
    }
    @Test fun nativeRolesAndStrictDynamicEdges() = temporary { dir ->
        val f = File(dir, "pwsh"); f.writeBytes(elf(true, needed = listOf("libc.so.6")))
        val info = p.inspectElf(f, "pwsh", LocalUbuntuPowerShellRuntimePolicy.NativeRole.EXECUTABLE)
        assertEquals(listOf("libc.so.6"), info.needed); assertNull(info.soname)
        reject { p.inspectElf(f, "libfake.so", LocalUbuntuPowerShellRuntimePolicy.NativeRole.SHARED_LIBRARY) }
        f.writeBytes(elf(false, soname = "libtest.so", search = "\$ORIGIN"))
        assertTrue(p.inspectElf(f, "libtest.so", LocalUbuntuPowerShellRuntimePolicy.NativeRole.SHARED_LIBRARY).originSearch)
        for (needed in listOf("../libc.so.6", "/lib/libc.so.6", "lib/x.so", "", "a:b.so")) {
            f.writeBytes(elf(false, needed = listOf(needed))); reject { p.inspectElf(f, "test.so", LocalUbuntuPowerShellRuntimePolicy.NativeRole.SHARED_LIBRARY) }
        }
        for (search in listOf("", "/usr/lib", "\$ORIGIN/..", "\$ORIGIN:/tmp", ".", "\$LIB")) {
            f.writeBytes(elf(false, search = search)); reject { p.inspectElf(f, "test.so", LocalUbuntuPowerShellRuntimePolicy.NativeRole.SHARED_LIBRARY) }
        }
    }
    @Test fun elfRejectsArchitectureEntryRangesAndDynamicCorruption() = temporary { dir ->
        val f = File(dir, "pwsh")
        val changes: List<(ByteBuffer) -> Unit> = listOf(
            { it.put(4, 1) }, { it.put(5, 2) }, { it.putShort(18, 62) }, { it.putLong(24, 0) }, { it.putLong(24, 0x30000) },
            { it.putLong(32, Long.MAX_VALUE) }, { it.putShort(54, 55) }, { it.putShort(56, 513) },
            { it.putLong(64 + 8, Long.MAX_VALUE) }, { it.putLong(64 + 32, Long.MAX_VALUE) }, { it.putLong(64 + 40, 10) },
            { it.putLong(64 + 48, 3) }, { it.putInt(64 + 4, 7) }, { it.putLong(0x300 + 8, 0x90000) },
            { it.putLong(0x310 + 8, 8_000_000) }, { it.putLong(0x320, 0x6ffffefc) }, { it.putLong(0x320 + 8, 1) })
        for (change in changes) { val bytes = elf(true); change(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)); f.writeBytes(bytes); reject { p.inspectElf(f, "pwsh", LocalUbuntuPowerShellRuntimePolicy.NativeRole.EXECUTABLE) } }
        f.writeBytes(elf(true, needed = listOf("libc.so.6", "libc.so.6"))); reject { p.inspectElf(f, "pwsh", LocalUbuntuPowerShellRuntimePolicy.NativeRole.EXECUTABLE) }
        f.writeBytes(elf(true).copyOf(80)); reject { p.inspectElf(f, "pwsh", LocalUbuntuPowerShellRuntimePolicy.NativeRole.EXECUTABLE) }
    }
    @Test fun unknownLoaderHooksAreNotAdmitted() = temporary { dir ->
        val file = File(dir, "pwsh")
        for (tag in listOf(0x6ffffefaL, 0x6ffffefbL, 0x6ffffefcL, 0x7ffffffdL, 0x7fffffffL, 0x12345678L, 0x70000002L)) {
            val bytes = elf(true); val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            b.putLong(0x320, tag); b.putLong(0x328, 0); b.putLong(0x330, 0); b.putLong(0x338, 0)
            b.putLong(64 + 56 + 32, 64); b.putLong(64 + 56 + 40, 64)
            file.writeBytes(bytes); reject { p.inspectElf(file, "pwsh", LocalUbuntuPowerShellRuntimePolicy.NativeRole.EXECUTABLE) }
        }
    }
    @Test fun runtimeOptionsAndPropertiesMustHaveReviewedShape() = temporary { root ->
        runtime(root); val config = File(root, "pwsh.runtimeconfig.json"); val original = config.readText()
        for (member in listOf("\"configProperties\":null", "\"configProperties\":[]", "\"configProperties\":true", "\"configProperties\":\"anything\"", "\"startupHooks\":[\"/tmp/hook\"]", "\"applyPatches\":true", "\"rollForwardOnNoCandidateFx\":2")) {
            config.writeText(original.replace("\"tfm\"", "$member,\"tfm\"")); reject { p.requireTree(File(root, "pwsh")) }
        }
        config.writeText(original.dropLast(1) + ",\"unknown\":true}"); reject { p.requireTree(File(root, "pwsh")) }
        config.writeText(original.replace("\"tfm\"", "\"configProperties\":{},\"tfm\"")); p.requireTree(File(root, "pwsh"))
    }
    @Test fun fullTreeClassifiesEveryMagicMemberAndRetainsManagedData() = temporary { root ->
        runtime(root)
        val inventory = p.requireTree(File(root, "pwsh"))
        assertEquals(p.nativeLibraries + setOf("pwsh", "createdump"), inventory.native.keys)
        File(root, "extra.dll").writeBytes(byteArrayOf(77, 90, 1, 2)); p.requireTree(File(root, "pwsh"))
        File(root, "hidden.txt").writeBytes(elf(false)); reject { p.requireTree(File(root, "pwsh")) }
        File(root, "hidden.txt").delete(); File(root, "createdump").delete(); reject { p.requireTree(File(root, "pwsh")) }
    }
    @Test fun treeRejectsLinksFrameworkFallbackAndConfigInjection() = temporary { root ->
        runtime(root)
        Files.createSymbolicLink(File(root, "linked").toPath(), File(root, "pwsh").toPath()); reject { p.requireTree(File(root, "pwsh")) }; File(root, "linked").delete()
        val config = File(root, "pwsh.runtimeconfig.json"); val original = config.readText()
        for (bad in listOf(original.replace("10.0.13", "10.0.12"), original.replace("net10.0", "net9.0"), original.replace("\"includedFrameworks\"", "\"frameworks\""),
            original.replace("\"tfm\"", "\"additionalProbingPaths\":[\"/tmp\"],\"tfm\""), original.replace("\"tfm\"", "\"configProperties\":{\"System.StartupHook\":true},\"tfm\""))) {
            config.writeText(bad); reject { p.requireTree(File(root, "pwsh")) }
        }
        config.writeText(original); File(root, "powershell.config.json").writeText("{\"PSModulePath\":\"/tmp\"}"); reject { p.requireTree(File(root, "pwsh")) }
    }
    @Test fun actualGuestClosureAndDlopenFilesAreRequired() = temporary { workspace ->
        val runtime = File(workspace, "runtime").apply { mkdir() }; runtime(runtime)
        val guest = File(workspace, "guest").apply { mkdir() }; guest(guest)
        val inventory = p.requireTree(File(runtime, "pwsh")); val closure = p.requireExecutionClosure(inventory, guest)
        assertTrue(closure.nativeFiles.keys.containsAll(listOf("libc.so.6", "libicuuc.so.74", "libcrypto.so.3", "ld-linux-aarch64.so.1")))
        File(guest, "usr/lib/aarch64-linux-gnu/libicuuc.so.74").delete(); reject { p.requireExecutionClosure(inventory, guest) }
    }
    @Test fun guestMappingRejectsUnreviewedSonamesAndWrongArchitecture() = temporary { workspace ->
        val r = File(workspace, "runtime").apply { mkdir() }; runtime(r)
        val g = File(workspace, "guest").apply { mkdir() }; guest(g)
        File(r, "pwsh").writeBytes(elf(true, needed = listOf("libunknown.so.7"))); reject { p.requireExecutionClosure(p.requireTree(File(r, "pwsh")), g) }
        File(r, "pwsh").writeBytes(elf(true, needed = listOf("libc.so.6")))
        val native = File(g, "usr/lib/aarch64-linux-gnu/libc.so.6"); val bytes = native.readBytes(); ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(18, 62); native.writeBytes(bytes)
        reject { p.requireExecutionClosure(p.requireTree(File(r, "pwsh")), g) }
    }
    @Test fun guestSymlinksUseGuestAbsolutePathsAndRejectEscapeCycleAndSpecial() = temporary { root ->
        val dir = File(root, "usr/lib/aarch64-linux-gnu").apply { mkdirs() }; File(dir, "real.so").writeBytes(byteArrayOf(1))
        Files.createSymbolicLink(File(root, "lib").toPath(), java.nio.file.Path.of("usr/lib"))
        Files.createSymbolicLink(File(dir, "absolute.so").toPath(), java.nio.file.Path.of("/usr/lib/aarch64-linux-gnu/real.so"))
        assertEquals(File(dir, "real.so").absolutePath, p.resolveGuestFile(root, "/lib/aarch64-linux-gnu/absolute.so")?.absolutePath)
        Files.createSymbolicLink(File(root, "escape").toPath(), java.nio.file.Path.of("../outside")); reject { p.resolveGuestFile(root, "/escape") }
        Files.createSymbolicLink(File(root, "cycle").toPath(), java.nio.file.Path.of("cycle")); reject { p.resolveGuestFile(root, "/cycle") }
        assertNull(p.resolveGuestFile(root, "/missing.so"))
    }
    @Test fun cancellationInterruptsTreeAndElfWork() = temporary { root ->
        runtime(root); reject { p.requireTree(File(root, "pwsh")) { error("cancel") } }
    }

    @Test fun symbolVersionsAreComparedWithActualGuestDefinitions() = temporary { workspace ->
        val r = File(workspace, "runtime").apply { mkdir() }; runtime(r)
        val g = File(workspace, "guest").apply { mkdir() }; guest(g)
        File(r, "pwsh").writeBytes(elf(true, needed = listOf("libc.so.6"), versionNeed = "libc.so.6" to "GLIBC_2.17"))
        val libc = File(g, "usr/lib/aarch64-linux-gnu/libc.so.6")
        reject { p.requireExecutionClosure(p.requireTree(File(r, "pwsh")), g) }
        libc.writeBytes(elf(true, soname = "libc.so.6", versionDefinition = "GLIBC_2.17"))
        assertEquals(setOf("GLIBC_2.17"), p.requireExecutionClosure(p.requireTree(File(r, "pwsh")), g).versionNeeds["libc.so.6"])
        val bytes = File(r, "pwsh").readBytes(); ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0x80c, 16)
        File(r, "pwsh").writeBytes(bytes); reject { p.requireTree(File(r, "pwsh")) }
    }
    @Test fun guestCacheCannotRedirectInspectedNativeClosureOrPreloadCode() = temporary { workspace ->
        val r = File(workspace, "runtime").apply { mkdir() }; runtime(r)
        val g = File(workspace, "guest").apply { mkdir() }; guest(g)
        val etc = File(g, "etc").apply { mkdir() }; val cache = File(etc, "ld.so.cache")
        val inventory = p.requireTree(File(r, "pwsh"))
        cache.writeBytes(cache("libc.so.6", "/lib/aarch64-linux-gnu/libc.so.6")); p.requireExecutionClosure(inventory, g)
        cache.writeBytes(cache("libc.so.6", "/tmp/libc.so.6")); reject { p.requireExecutionClosure(inventory, g) }
        cache.writeBytes("ld.so-1.7.0".toByteArray()); reject { p.requireExecutionClosure(inventory, g) }; cache.delete()
        File(etc, "ld.so.preload").writeText("/tmp/inject.so"); reject { p.requireExecutionClosure(inventory, g) }
        File(etc, "ld.so.preload").writeText(""); p.requireExecutionClosure(inventory, g)
        File(g, "usr/lib/ld-linux-aarch64.so.1").delete(); reject { p.requireExecutionClosure(inventory, g) }
    }
    @Test fun depsMustBindRuntimePatchRidAndRealAssets() = temporary { root ->
        runtime(root); val deps = File(root, "pwsh.deps.json"); val original = deps.readText()
        for (bad in listOf(original.replace("/10.0.13", "/10.0.12"), original.replace("linux-arm64", "linux-x64"),
            original.replace("libclrgc.so", "runtimes/linux-x64/native/libclrgc.so"), original.replace("pwsh.dll", "missing.dll"))) {
            deps.writeText(bad); reject { p.requireTree(File(root, "pwsh")) }
        }
    }
    private fun cache(name: String, path: String): ByteArray {
        val strings = (name + '\u0000' + path + '\u0000').toByteArray(); val bytes = ByteArray(72 + strings.size)
        "glibc-ld.so.cache1.1".toByteArray().copyInto(bytes); val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(20, 1); b.putInt(24, strings.size); b.put(28, 2); b.putInt(48, 0x0a03); b.putInt(52, 72); b.putInt(56, 72 + name.length + 1)
        strings.copyInto(bytes, 72); return bytes
    }

    private fun runtime(root: File) {
        for (name in p.requiredData) File(root, name).writeText("original synthetic data")
        File(root, "Modules/Test/Test.psd1").apply { parentFile.mkdirs(); writeText("@{}") }
        File(root, "pwsh").writeBytes(elf(true, needed = listOf("libc.so.6"))); File(root, "createdump").writeBytes(elf(true, needed = listOf("libc.so.6")))
        p.nativeLibraries.forEach { File(root, it).writeBytes(elf(false, soname = it, needed = listOf("libc.so.6"))) }
        File(root, "pwsh.runtimeconfig.json").writeText("""{"runtimeOptions":{"tfm":"net10.0","includedFrameworks":[{"name":"Microsoft.NETCore.App","version":"10.0.13"}]}}""")
        val native = p.nativeLibraries.joinToString(",") { "\"$it\":{}" }
        File(root, "pwsh.deps.json").writeText("""{"runtimeTarget":{"name":".NETCoreApp,Version=v10.0/linux-arm64"},"targets":{".NETCoreApp,Version=v10.0/linux-arm64":{"Microsoft.NETCore.App.Runtime.linux-arm64/10.0.13":{"native":{$native},"runtime":{"pwsh.dll":{}}}}}}""")
    }
    private fun guest(root: File) {
        val directory = File(root, "usr/lib/aarch64-linux-gnu").apply { mkdirs() }
        Files.createSymbolicLink(File(root, "lib").toPath(), java.nio.file.Path.of("usr/lib"))
        Files.createSymbolicLink(File(root, "usr/lib/ld-linux-aarch64.so.1").toPath(), java.nio.file.Path.of("aarch64-linux-gnu/ld-linux-aarch64.so.1"))
        for (name in listOf("libc.so.6", "libicuuc.so.74", "libicui18n.so.74", "libicudata.so.74", "libssl.so.3", "libcrypto.so.3", "libgssapi_krb5.so.2", "libz.so.1", "ld-linux-aarch64.so.1"))
            File(directory, name).writeBytes(elf(name == "libc.so.6", soname = name))
    }
    internal fun elf(executable: Boolean, soname: String? = null, needed: List<String> = emptyList(), search: String? = null, versionNeed: Pair<String, String>? = null, versionDefinition: String? = null): ByteArray {
        val bytes = ByteArray(4096); val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); val base = 0x10000L
        b.putInt(0, 0x464c457f); b.put(4, 2); b.put(5, 1); b.put(6, 1); b.putShort(16, 3); b.putShort(18, 183); b.putInt(20, 1)
        b.putLong(24, if (executable) base + 0x280 else 0); b.putLong(32, 64); b.putShort(52, 64); b.putShort(54, 56); b.putShort(56, if (executable) 3 else 2)
        fun ph(index: Int, type: Int, flags: Int, offset: Long, size: Long) { val at = 64 + index * 56; b.putInt(at, type); b.putInt(at + 4, flags); b.putLong(at + 8, offset); b.putLong(at + 16, base + offset); b.putLong(at + 32, size); b.putLong(at + 40, size); b.putLong(at + 48, 1) }
        ph(0, 1, 5, 0, bytes.size.toLong())
        val strings = java.io.ByteArrayOutputStream(); strings.write(0)
        fun text(value: String): Long { val at = strings.size(); strings.write(value.toByteArray()); strings.write(0); return at.toLong() }
        val tags = mutableListOf(5L to base + 0x600, 10L to 0L)
        needed.forEach { tags += 1L to text(it) }; soname?.let { tags += 14L to text(it) }; search?.let { tags += 29L to text(it) }
        versionNeed?.let { (owner, version) ->
            val ownerIndex = text(owner); val versionIndex = text(version)
            tags += 0x6ffffffeL to base + 0x800; tags += 0x6fffffffL to 1L
            b.putShort(0x800, 1); b.putShort(0x802, 1); b.putInt(0x804, ownerIndex.toInt()); b.putInt(0x808, 16); b.putInt(0x80c, 0)
            b.putInt(0x818, versionIndex.toInt()); b.putInt(0x81c, 0)
        }
        versionDefinition?.let { version ->
            val versionIndex = text(version); tags += 0x6ffffffcL to base + 0x900; tags += 0x6ffffffdL to 1L
            b.putShort(0x900, 1); b.putShort(0x906, 1); b.putInt(0x90c, 20); b.putInt(0x910, 0); b.putInt(0x914, versionIndex.toInt()); b.putInt(0x918, 0)
        }
        tags[1] = 10L to strings.size().toLong(); tags += 0L to 0L
        tags.forEachIndexed { i, pair -> b.putLong(0x300 + i * 16, pair.first); b.putLong(0x308 + i * 16, pair.second) }
        ph(1, 2, 4, 0x300, tags.size * 16L)
        if (executable) { val interp = (p.INTERPRETER + '\u0000').toByteArray(); interp.copyInto(bytes, 0x200); ph(2, 3, 4, 0x200, interp.size.toLong()) }
        strings.toByteArray().copyInto(bytes, 0x600); return bytes
    }
}
