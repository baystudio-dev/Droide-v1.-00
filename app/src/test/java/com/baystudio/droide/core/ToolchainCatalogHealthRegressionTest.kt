package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ToolchainCatalogHealthRegressionTest {
    @Test fun historicalZigArchiveAndReceiptEntryShareOneLayout() = runBlocking {
        val recipe = LocalUbuntuVendorResolver { zig("0.13.0", "zig-linux-aarch64-0.13.0.tar.xz", HISTORICAL_SHA, "43090688") }
            .resolve("toolchain.zig", "0.13.0")
        assertEquals("zig-linux-aarch64-0.13.0/zig", recipe.entryPoint)
        assertEquals(recipe.entryPoint, recipe.definition.entry(recipe.version))
        assertEquals(HISTORICAL_SHA, recipe.assetSha256)
        assertEquals(43090688L, recipe.assetSize)
        recipe.validate()
    }
    @Test fun historicalZigReceiptRevalidationRetainsExactArchiveEntryAndDigest() {
        val root = Files.createTempDirectory("zig-historical-receipt-").toFile()
        try {
            val record = ManagedPackageRecord("toolchain.zig", "0.13.0", ExecutionScope.LOCAL_LINUX_ARM64.name, root.path, 0,
                artifactSha256 = HISTORICAL_SHA, metadata = mapOf(
                    LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE,
                    LocalUbuntuVendorPolicy.POLICY_KEY to LocalUbuntuVendorPolicy.POLICY_VERSION))
            val lock = """{"schema":"1","provider":"LOCAL_UBUNTU_VENDOR","familyId":"toolchain.zig","version":"0.13.0","artifactFormat":"TAR_XZ","command":"zig","treeEntryPoint":"zig-linux-aarch64-0.13.0/zig","releaseTag":"0.13.0","assetName":"zig-linux-aarch64-0.13.0.tar.xz","assetUrl":"https://ziglang.org/download/0.13.0/zig-linux-aarch64-0.13.0.tar.xz","assetSha256":"$HISTORICAL_SHA","assetSize":"43090688","provenanceUrl":"https://ziglang.org/download/"}"""
            val file = File(root, "RECIPE_LOCK.json"); file.writeText(lock)
            assertEquals("zig-linux-aarch64-0.13.0/zig", LocalUbuntuVendorPolicy.fromReceipt(record)!!.entryPoint)
            assertNull(LocalUbuntuVendorPolicy.fromReceipt(record.copy(artifactSha256 = "b".repeat(64))))
            file.writeText(lock.replace("zig-linux-aarch64-0.13.0/zig", "zig-aarch64-linux-0.13.0/zig"))
            assertNull(LocalUbuntuVendorPolicy.fromReceipt(record))
        } finally { root.deleteRecursively() }
    }
    @Test fun zigLayoutTransitionIsFiniteAndVersionBound() = runBlocking {
        val definition = LocalUbuntuVendorCatalog.find("toolchain.zig")!!
        assertEquals("zig-linux-aarch64-0.14.0.tar.xz", definition.asset("0.14.0"))
        assertEquals("zig-aarch64-linux-0.14.1.tar.xz", definition.asset("0.14.1"))
        // Synthetic current-layout fixture; no claim that this release exists upstream.
        val recipe = LocalUbuntuVendorResolver { zig("0.17.0", "zig-aarch64-linux-0.17.0.tar.xz") }.resolve("toolchain.zig")
        assertEquals("zig-aarch64-linux-0.17.0/zig", recipe.entryPoint)
        assertThrows(IllegalArgumentException::class.java) { definition.asset("0.13.0/../../x") }; Unit
    }
    @Test fun zigStillRejectsOriginVersionArchitectureSizeAndDigestSubstitution() = runBlocking {
        val good = zig("0.13.0", "zig-linux-aarch64-0.13.0.tar.xz", HISTORICAL_SHA, "43090688")
        for (bad in listOf(good.replace("https://ziglang.org/", "https://evil.invalid/"), good.replace("/0.13.0/", "/0.12.0/"),
            good.replace("zig-linux-aarch64-", "zig-linux-x86_64-"), good.replace(HISTORICAL_SHA, "not-a-digest"),
            good.replace("43090688", "0"), good.replace("43090688", "999999999999"),
            good.replace("zig-linux-aarch64-0.13.0.tar.xz", "../zig-linux-aarch64-0.13.0.tar.xz"))) {
            val failure = runCatching { LocalUbuntuVendorResolver { bad }.resolve("toolchain.zig", "0.13.0") }.exceptionOrNull()
            assertTrue(bad, failure is IllegalArgumentException)
        }
    }
    @Test fun bazelAlternativesDoNotWeakenRustOrOtherRequiredBundles() {
        val bazel = DevelopmentExtensionCatalog.byId("build.bazel")!!.versions.single()
        assertTrue(bazel.detectsExternalCommands(setOf("bazel")))
        assertTrue(bazel.detectsExternalCommands(setOf("bazelisk")))
        assertTrue(bazel.detectsExternalCommands(setOf("bazel", "bazelisk")))
        assertFalse(bazel.detectsExternalCommands(emptySet()))
        val rust = DevelopmentExtensionCatalog.byId("toolchain.rust")!!.versions.single()
        assertTrue(rust.detectsExternalCommands(rust.provides))
        assertFalse(rust.detectsExternalCommands(setOf("rustc")))
        assertFalse(rust.detectsExternalCommands(setOf("cargo")))
        assertFalse(ExtensionVersion("x", provides = setOf("language:x")).detectsExternalCommands(emptySet()))
        val requiredAndAlternative = ExtensionVersion("x", provides = setOf("runtime", "a", "b"), alternativeCommands = setOf("a", "b"))
        assertFalse(requiredAndAlternative.detectsExternalCommands(setOf("a")))
        assertTrue(requiredAndAlternative.detectsExternalCommands(setOf("runtime", "b")))
    }
    @Test fun languageCatalogTracksRegistrationReplacementAndRemovalWithoutDuplicateBuiltinIds() {
        val before = DevelopmentExtensionCatalog.families
        val nonLanguages = before.filter { it.category != ExtensionCategory.LANGUAGES }
        LanguageRegistry.registerDeclarative("r81-test", listOf(DeclarativeLanguageSpec("r81-temp", "Temporary", listOf("r81"), emptyList()),
            DeclarativeLanguageSpec("kotlin", "Alias Kotlin", listOf("r81kt"), emptyList())))
        try {
            assertNotNull(DevelopmentExtensionCatalog.byId("language.r81-temp"))
            assertEquals("Extension-provided", DevelopmentExtensionCatalog.byId("language.r81-temp")!!.versions.single().version)
            assertEquals("Extension", DevelopmentExtensionCatalog.byId("language.r81-temp")!!.publisher)
            assertEquals(1, DevelopmentExtensionCatalog.families.count { it.id == "language.kotlin" })
            assertEquals("Kotlin", DevelopmentExtensionCatalog.byId("language.kotlin")!!.name)
            assertEquals("kotlin", LanguageRegistry.forFile("x.r81kt")!!.id)
            assertEquals(nonLanguages, DevelopmentExtensionCatalog.families.filter { it.category != ExtensionCategory.LANGUAGES })
            LanguageRegistry.registerDeclarative("r81-test", listOf(DeclarativeLanguageSpec("r81-next", "Next", listOf("r81n"), emptyList())))
            assertNull(DevelopmentExtensionCatalog.byId("language.r81-temp"))
            assertNotNull(DevelopmentExtensionCatalog.byId("language.r81-next"))
        } finally { LanguageRegistry.unregisterDeclarative("r81-test") }
        assertEquals(before, DevelopmentExtensionCatalog.families)
    }
    @Test fun pinnedSdkChoiceUsesExactVersionAndProvenanceWhileLiveChoiceStaysLatest() {
        val pinned = PackageSourceDefinition("runtime.dart", PackageSourceKind.VENDOR_OFFICIAL, "dart", "dart", "https://dart.dev/get-dart",
            vendorVersionStrategy = DeclarativeVendorVersionStrategy.PINNED, vendorPinnedVersion = "3.9.4", vendorPinnedSha256 = "a".repeat(64))
        assertEquals("3.9.4", PackageSourceChoicePresentation.version(pinned))
        assertTrue(PackageSourceChoicePresentation.note(pinned).contains("catalog-pinned SHA-256"))
        assertTrue(PackageSourceChoicePresentation.note(pinned).contains(pinned.provenanceUrl))
        assertFalse(PackageSourceChoicePresentation.note(pinned).contains("Latest"))
        assertEquals("Latest official", PackageSourceChoicePresentation.version(pinned.copy(vendorVersionStrategy = DeclarativeVendorVersionStrategy.TEXT_ENDPOINT)))
    }
    @Test fun kotlinPlannerProbeRunsSelectedExecutableAndParsesItsStderr() = host { f -> runBlocking {
        val recipe = kotlinRecipe()
        assertEquals(listOf("-version"), recipe.selectedCommandChecks.single().arguments)
        val result = UserToolUpdateHealth.verify(recipe, f::execute)
        assertTrue(result.summary, result.identityVerified)
        assertTrue(result.summary.contains("2.0.21")); assertTrue(result.summary.contains(f.provider.path))
        assertTrue(f.calls.readText().contains("-version"))
    } }
    @Test fun brokenExecutableCannotPassFromPresenceAlone() = host { f -> runBlocking {
        f.tool("echo broken >&2; exit 127")
        assertTrue(runCatching { UserToolUpdateHealth.verify(kotlinRecipe(), f::execute) }.exceptionOrNull() is IllegalStateException)
    } }
    @Test fun staleVersionFailsEvenWhenDependencyBannerContainsRequestedVersion() = host { f -> runBlocking {
        f.tool("echo 'openjdk version \"2.0.21\"' >&2; echo 'info: kotlinc-jvm 1.9.25 (JRE 2.0.21)' >&2")
        val failure = runCatching { UserToolUpdateHealth.verify(kotlinRecipe(), f::execute) }.exceptionOrNull()
        assertTrue(failure!!.message, failure.message!!.contains("reports 1.9.25 instead of requested 2.0.21"))
    } }
    @Test fun nonMiseShadowIsRejectedEvenIfItPrintsCorrectVersion() = host { f -> runBlocking {
        File(f.bin, "kotlinc").delete()
        f.script(File(f.bin, "kotlinc"), "echo 'info: kotlinc-jvm 2.0.21' >&2")
        val failure = runCatching { UserToolUpdateHealth.verify(kotlinRecipe(), f::execute) }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("selection was preserved"))
    } }
    @Test fun configuredMiseShimPassesOnlyWhenCurrentProjectSelectsExpectedProvider() = host { f -> runBlocking {
        File(f.bin, "kotlinc").delete(); f.useShim()
        assertTrue(UserToolUpdateHealth.verify(kotlinRecipe(), f::execute).identityVerified)
        f.current = File(f.root, "older/kotlinc").apply { parentFile.mkdirs(); writeText("old") }.path
        assertTrue(runCatching { UserToolUpdateHealth.verify(kotlinRecipe(), f::execute) }.exceptionOrNull() is IllegalStateException)
    } }
    @Test fun latestHasProviderPathEvidenceButDoesNotCertifyUpstreamFreshness() = host { f -> runBlocking {
        val production = kotlinRecipe("stable")
        val latest = production.copy(miseRequest = "kotlin@latest", selectedCommandChecks = production.selectedCommandChecks.map {
            it.copy(miseRequest = "kotlin@latest", requestedToolVersion = "latest")
        })
        val result = UserToolUpdateHealth.verify(latest, f::execute)
        assertTrue(result.summary.contains("upstream freshness"))
        assertFalse(result.identityVerified)
    } }
    @Test fun miseChannelAndUnknownVersionOutputRemainExplicitlyPending() = host { f -> runBlocking {
        val result = UserToolUpdateHealth.verify(kotlinRecipe("stable"), f::execute)
        assertFalse(result.identityVerified); assertTrue(result.summary.contains("upstream freshness"))
        f.tool("echo 'openjdk version \"2.0.21\"' >&2")
        val unparsed = UserToolUpdateHealth.verify(kotlinRecipe(), f::execute)
        assertFalse(unparsed.identityVerified); assertTrue(unparsed.summary.contains("verification pending"))
    } }
    @Test fun unreviewedCommandsNeverReceiveGuessedVersionFlags() = host { f -> runBlocking {
        val family = ExtensionFamily("test.unknown", "Unknown", ExtensionCategory.CLI_TOOLS, "", listOf(ExtensionVersion("x")), "")
        val source = PackageSourceDefinition("test.unknown", PackageSourceKind.GITHUB_RELEASE, "owner/project", "mystery", "https://github.com/owner/project")
        val recipe = ToolUpdatePlanner.recipe(family, "1.0.0", source)
        assertNull(recipe.selectedCommandChecks.single().arguments)
        val pending = UserToolUpdateHealth.verify(recipe.copy(selectedCommandChecks = emptyList()), f::execute)
        assertFalse(pending.identityVerified); assertTrue(pending.summary.contains("verification pending"))
    } }
    @Test fun requestedVersionIsLiteralAndCannotInjectShellWhileCheckingProvider() = host { f -> runBlocking {
        val version = "2.0.21'; touch ${File(f.root, "injected").path}; '"
        val result = UserToolUpdateHealth.verify(kotlinRecipe(version), f::execute)
        assertFalse(result.identityVerified)
        assertFalse(File(f.root, "injected").exists())
        assertTrue(f.miseCalls.readText().contains("kotlin@$version"))
    } }
    @Test fun numericSelectorsUseBoundariesInsteadOfSubstringComparison() {
        assertTrue(UserToolUpdateHealth.matchesNumericRequest("2", "2.0.21"))
        assertTrue(UserToolUpdateHealth.matchesNumericRequest("2.0", "2.0.21"))
        assertFalse(UserToolUpdateHealth.matchesNumericRequest("2", "20.1.0"))
        assertFalse(UserToolUpdateHealth.matchesNumericRequest("2.0.2", "2.0.21"))
        assertFalse(UserToolUpdateHealth.matchesNumericRequest("2.0.21", "2.0.21-RC"))
        assertNull(UserToolUpdateHealth.observedVersion("kotlinc", "openjdk version \"2.0.21\""))
    }
    @Test fun dedicatedPythonAndAndroidChecksAreRetained() {
        val py = PackageSourceDefinition("debug.python-debugpy", PackageSourceKind.PYPI, "debugpy", "debugpy", "https://pypi.org/project/debugpy/")
        val family = ExtensionFamily(py.familyId, "Debugpy", ExtensionCategory.DEBUGGERS, "", listOf(ExtensionVersion("old")), "")
        val recipe = ToolUpdatePlanner.recipe(family, "1.8.0", py)
        assertEquals(PythonToolInstallCommands.recipe(py.familyId, "debugpy==1.8.0", py.command, py.healthArgs, "debugpy").healthCheckCommand, recipe.healthCheckCommand)
        val sdk = DevelopmentExtensionCatalog.byId("sdk.android")!!
        assertTrue(ToolUpdatePlanner.recipe(sdk, "37", null).healthCheckedDuringInstall)
    }
    @Test fun verificationPropagatesCancellationWithoutAnotherProbe() = runBlocking {
        var calls = 0
        val failure = runCatching { UserToolUpdateHealth.verify(kotlinRecipe()) { calls++; throw CancellationException("cancel") } }.exceptionOrNull()
        assertTrue(failure is CancellationException); assertEquals(1, calls)
    }
    @Test fun exactNpmRequestCannotCertifyAnOlderSelectedExecutable() = host { f -> runBlocking {
        val recipe = ToolUpdatePlanner.recipe(DevelopmentExtensionCatalog.byId("package.npm")!!, "11.2.0", null)
        assertEquals("11.2.0", recipe.selectedCommandChecks.single().requestedToolVersion)
        f.script(File(f.bin, "npm"), "if [ \"\$1\" = prefix ]; then printf '%s\\n' \"\$HOME\"; else printf '10.9.2\\n'; fi")
        val failure = runCatching { UserToolUpdateHealth.verify(recipe, f::execute) }.exceptionOrNull()
        assertTrue(failure!!.message, failure.message!!.contains("reports 10.9.2 instead of requested 11.2.0"))
    } }
    @Test fun truncatedOutputAndTimeoutCannotBecomeHealthSuccess() = runBlocking {
        for (result in listOf(ExecResult(0, "/x", false, true), ExecResult(0, "/x", true))) {
            assertTrue(runCatching { UserToolUpdateHealth.verify(kotlinRecipe()) { result } }.exceptionOrNull() is IllegalStateException)
        }
    }
    @Test fun aptCanonicalIdentityAllowsReviewedSymlinkButRejectsAnotherSelection() = host { f -> runBlocking {
        val original = kotlinRecipe("latest")
        assertEquals("Ubuntu APT", original.provider)
        assertEquals("/usr/bin/kotlinc", original.selectedCommandChecks.single().expectedPath)
        val fixture = original.copy(selectedCommandChecks = original.selectedCommandChecks.map { it.copy(expectedPath = f.provider.path) })
        val result = UserToolUpdateHealth.verify(fixture, f::execute)
        assertFalse(result.identityVerified)
        assertTrue(result.summary.contains("upstream/package-version equivalence"))
        File(f.bin, "kotlinc").delete(); f.script(File(f.bin, "kotlinc"), "exit 0")
        assertTrue(runCatching { UserToolUpdateHealth.verify(fixture, f::execute) }.exceptionOrNull() is IllegalStateException)
    } }
    @Test fun rustRoutesAndBuildPrerequisitesRemainUnchanged() {
        assertEquals("rust", LanguageRegistry.forFile("src/main.rs")!!.id)
        assertEquals("toolchain.rust", UbuntuAptCatalog.languageRuntime["rust"])
        for (id in listOf("toolchain.rust", "package.cargo", "test.cargo")) {
            val recipe = UbuntuAptCatalog.find(id)!!
            assertTrue(recipe.packages.containsAll(listOf("cargo", "build-essential", "pkg-config", "ca-certificates")))
            assertEquals("/usr/bin/cargo", recipe.commands["cargo"])
        }
        assertTrue(LanguageServerRegistry.builtIns.any { "rust" in it.languageIds && it.commands.any { command -> command.firstOrNull() == "rust-analyzer" } })
        assertEquals("rust-analyzer", LocalUbuntuVendorCatalog.find("lsp.rust-analyzer")!!.command)
        val components = listOf("quality.rustfmt", "quality.clippy").map { ToolUpdatePlanner.recipe(DevelopmentExtensionCatalog.byId(it)!!, "1.82.0", null) }
        assertTrue(components.all { it.command.contains("rustup component add") })
        assertTrue(components.all { it.selectedCommandChecks.all { check -> check.requestedToolVersion == null } })
    }
    @Test fun rustComponentBannerNeverCertifiesTheDifferentRustReleaseIdentity() = host { f -> runBlocking {
        val recipe = ToolUpdatePlanner.recipe(DevelopmentExtensionCatalog.byId("quality.rustfmt")!!, "1.82.0", null)
        Files.createSymbolicLink(File(f.bin, "rustfmt").toPath(), f.provider.toPath())
        f.tool("echo 'rustfmt 1.7.0-stable (fixture)' >&2")
        val result = UserToolUpdateHealth.verify(recipe, f::execute)
        assertFalse(result.identityVerified)
        assertTrue(result.summary.contains("requested package/tool-version equivalence"))
        assertTrue(result.summary.contains("rustfmt 1.7.0-stable"))
    } }
    @Test fun manuallyConstructedUnsafeCommandCannotReachShellInterpolation() = runBlocking {
        var calls = 0
        val recipe = kotlinRecipe().copy(selectedCommandChecks = listOf(UserToolCommandCheck("bad$(touch marker)", listOf("--version"))))
        assertTrue(runCatching { UserToolUpdateHealth.verify(recipe) { calls++; ExecResult(0, "", false) } }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, calls)
    }
    private fun kotlinRecipe(version: String = "2.0.21") = ToolUpdatePlanner.recipe(DevelopmentExtensionCatalog.byId("toolchain.kotlin")!!, version, null)
    private class Fixture(val root: File) {
        val bin = File(root, "bin").apply { mkdirs() }
        val provider = File(root, "provider/kotlinc").apply { parentFile.mkdirs() }
        val calls = File(root, "calls"); val miseCalls = File(root, "mise-calls")
        var current = provider.path
        var shim: File? = null
        init {
            tool("echo 'info: kotlinc-jvm 2.0.21 (JRE 17.0.12)' >&2")
            Files.createSymbolicLink(File(bin, "kotlinc").toPath(), provider.toPath())
            script(File(bin, "mise"), "printf '%s\\n' \"\$@\" >> \"\$MISE_CALLS\"; case \"\$*\" in *--tool*) printf '%s\\n' \"\$PROVIDER\";; *) printf '%s\\n' \"\$CURRENT\";; esac")
        }
        fun tool(body: String) = script(provider, "printf '%s\\n' \"\$@\" >> \"\$CALLS\"; $body")
        fun script(file: File, body: String) { file.parentFile.mkdirs(); file.writeText("#!/bin/sh\n$body\n"); file.setExecutable(true) }
        fun useShim() { shim = File(root, "custom-mise/shims/kotlinc"); script(shim!!, "exec \"\$PROVIDER\" \"\$@\"") }
        suspend fun execute(command: String): ExecResult {
            val output = File(root, "output")
            val process = ProcessBuilder("/bin/sh", "-c", command).directory(root).redirectErrorStream(true).redirectOutput(output).apply {
                environment()["PATH"] = listOfNotNull(shim?.parent, bin.path, "/usr/bin", "/bin").joinToString(":")
                environment()["HOME"] = root.path; environment()["MISE_DATA_DIR"] = File(root, "custom-mise").path
                environment()["PROVIDER"] = provider.path; environment()["CURRENT"] = current
                environment()["CALLS"] = calls.path; environment()["MISE_CALLS"] = miseCalls.path
            }.start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Fixture timed out") }
            return ExecResult(process.exitValue(), output.readText(), false)
        }
    }
    private fun host(test: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("droide-toolchain-health-").toFile()
        try { test(Fixture(root)) } finally { root.deleteRecursively() }
    }
    private fun zig(version: String, asset: String, sha: String = "a".repeat(64), size: String = "1") =
        """{"$version":{"aarch64-linux":{"tarball":"https://ziglang.org/download/$version/$asset","shasum":"$sha","size":"$size"}}}"""
    companion object { const val HISTORICAL_SHA = "041ac42323837eb5624068acd8b00cd5777dac4cf91179e8dad7a7e90dd0c556" }
}
