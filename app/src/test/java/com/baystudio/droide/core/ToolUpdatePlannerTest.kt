package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ToolUpdatePlannerTest {
    private fun family(id: String, category: ExtensionCategory = ExtensionCategory.TOOLCHAINS) =
        ExtensionFamily(id, id, category, "", listOf(ExtensionVersion("old")), "generic")
    private fun item(id: String, version: String) = ExtensionVersionState(family(id),
        ExtensionVersion(version, installKind = ExtensionInstallKind.REVIEWED_RECIPE), ExtensionState.AVAILABLE, "")

    @Test fun mutableNpmToolsAcceptNewVersionsWhileManagedSnapshotsRejectReplacement() {
        for (entry in UbuntuNpmCatalog.entries) {
            val recipe = WorkstationInstallRecipe(entry.familyId, "99.8.7", entry.packageName, "99.8.7", entry.command, 22,
                entry.provenance, allowLifecycleScripts = entry.allowLifecycleScripts, lifecycleScriptReview = entry.lifecycleScriptReview,
                additionalDependencies = if (entry.familyId == "lsp.typescript") mapOf("typescript" to "8.1.0") else emptyMap())
            if (entry.reviewedVersion == null) {
                assertTrue(entry.familyId, LocalUbuntuNpmPolicy.supports(recipe))
            } else {
                assertFalse("Pinned integration accepted an arbitrary replacement: ${entry.familyId}",
                    LocalUbuntuNpmPolicy.supports(recipe))
            }
        }
        assertEquals(5, UbuntuNpmCatalog.entries.count { it.familyId.startsWith("plugin.") })
    }

    @Test fun aNewNodeMajorHasItsOwnPublisherArchiveAndPreservesExistingNode22Receipts() {
        val hash = "a".repeat(64)
        val next = LocalUbuntuNodeDistribution.resolve("$hash  node-v26.9.1-linux-arm64.tar.gz\n")
        assertEquals("26.9.1", next.version)
        assertEquals("https://nodejs.org/download/release/v26.9.1/node-v26.9.1-linux-arm64.tar.gz", next.assetUrl)
        assertTrue(LocalUbuntuNodeDistribution.guestWrapper("26.9.1", "npm", "/owned").contains("node-v26.9.1"))
        assertEquals(LocalUbuntuNodeDistribution.AUTHORITY, LocalUbuntuNodeDistribution.recipe("22.23.3", hash).versionAuthority)
    }

    @Test fun requestingAnUnlistedVersionProducesAnInstallableRequestInsteadOfNotFound() {
        val npm = ToolVersionRequests.select(listOf(item("plugin.codex", UbuntuNpmCatalog.VERSION)), "plugin.codex", "4.8.9")!!
        assertEquals("4.8.9", npm.version.version); assertEquals(ExtensionState.AVAILABLE, npm.state)
        val ruby = ToolVersionRequests.select(listOf(item("lsp.ruby-lsp", "0.26.11")), "lsp.ruby-lsp", "0.99.2")!!
        assertEquals(ExtensionInstallKind.USER_MANAGED, ruby.version.installKind)
        assertTrue(ToolUpdatePlanner.recipe(ruby.family, "0.99.2", null).command.contains("--version '0.99.2'"))
    }

    @Test fun opencodeLatestSelectsTheStandalonePublisherRouteWithoutNodeSetup() {
        val options = listOf(item("plugin.opencode", UbuntuNpmCatalog.VERSION), item("plugin.opencode", LocalUbuntuVendorPolicy.VERSION_CHOICE))
        val choice = ToolVersionRequests.select(options, "plugin.opencode", "latest")!!
        assertEquals(LocalUbuntuVendorPolicy.VERSION_CHOICE, choice.version.version)
        val definition = LocalUbuntuVendorCatalog.find("plugin.opencode")!!
        assertNull(definition.pinnedVersion); assertEquals("opencode-linux-arm64.tar.gz", definition.asset("99.0.1"))
        assertFalse(definition.packages.any { it == "nodejs" || it == "npm" })
    }

    @Test fun catalogCompilerAndRuntimeIdsUseActualProviderBackends() {
        for (id in listOf("toolchain.llvm", "toolchain.jdk", "runtime.python", "framework.flutter")) {
            val recipe = ToolUpdatePlanner.recipe(family(id), "99.2.1", null)
            assertTrue(id, recipe.usesMise); assertTrue(recipe.command.contains("@99.2.1"))
            assertFalse(recipe.command.contains("apt-get update"))
        }
        assertTrue(ToolUpdatePlanner.recipe(family("toolchain.llvm"), "20.1.8", null).command.contains("'clang@20.1.8'"))
        for (id in listOf("runtime.powershell", "lsp.powershell-editor-services")) {
            assertThrows(IllegalArgumentException::class.java) {
                ToolUpdatePlanner.recipe(family(id), "7.6.0", null)
            }
            val managed = requireNotNull(DevelopmentExtensionCatalog.byId(id))
            assertTrue(DevelopmentExtensionProjection.localVersions(managed).any {
                it.installKind == ExtensionInstallKind.REVIEWED_RECIPE &&
                    ("pwsh" in it.provides || "powershell-editor-services" in it.provides)
            })
        }
        val apt = ToolUpdatePlanner.recipe(family("toolchain.gcc"), "latest", null)
        assertEquals("Ubuntu APT", apt.provider)
        assertTrue(apt.command.contains("--no-remove install"))
        assertThrows(IllegalStateException::class.java) { ToolUpdatePlanner.recipe(family("debug.custom"), "99.2.1", null) }
    }

    @Test fun hostileVersionTextRemainsOneLiteralArgumentAndCannotBecomeAShellCommand() {
        host { dir ->
            val marker = File(dir, "unexpected")
            val value = "1.0'; touch ${marker.name}; '"
            assertTrue(value.length <= 100)
            fake(dir, "npm", "printf '%s\\0' \"\$@\" > \"\$CALL_LOG\"")
            val recipe = ToolUpdatePlanner.recipe(family("plugin.codex", ExtensionCategory.PLUGINS), value, null)
            val result = run(dir, recipe.command)
            assertEquals(result.output, 0, result.exit)
            assertEquals(listOf("install", "--global", "@agentclientprotocol/codex-acp@$value"), args(dir))
            assertFalse(marker.exists())
            assertThrows(IllegalArgumentException::class.java) { ToolUpdatePlanner.request("latest\nsecond-command") }
        }
    }

    @Test fun upstreamManagersHaveVersionRoutesWithoutGuessingAnUnknownVendorTool() {
        val providers = listOf(PackageSourceKind.NPM, PackageSourceKind.PYPI, PackageSourceKind.CARGO, PackageSourceKind.GO,
            PackageSourceKind.RUBYGEMS, PackageSourceKind.COMPOSER, PackageSourceKind.GITHUB_RELEASE)
        for (provider in providers) {
            val source = PackageSourceDefinition("test.provider", provider, "example/tool", "tool", "https://publisher.example/",
                goModulePath = if (provider == PackageSourceKind.GO) "example.org/tool/cmd/tool" else null)
            val recipe = ToolUpdatePlanner.recipe(family("test.provider"), "99.2.1", source)
            assertTrue(provider.name, recipe.command.isNotBlank())
            assertFalse(recipe.command.contains("setup Ubuntu"))
        }
        val unknown = PackageSourceDefinition("unknown.vendor", PackageSourceKind.VENDOR_OFFICIAL, "unknown", "tool", "https://publisher.example/")
        assertThrows(IllegalStateException::class.java) { ToolUpdatePlanner.recipe(family("unknown.vendor"), "99.2.1", unknown) }
    }

    @Test fun goBackendInstallsTheExecutablePackageAndBootstrapsMissingGoFirst() = host { dir ->
        fake(dir, "mise", "printf '%s\\0' \"\$@\" >> \"\$CALL_LOG\"")
        val source = PackageSourceDefinition("debug.delve", PackageSourceKind.GO, "github.com/go-delve/delve/cmd/dlv", "dlv",
            "https://github.com/go-delve/delve", goModulePath = "github.com/go-delve/delve")
        val recipe = ToolUpdatePlanner.recipe(family("debug.delve"), "v1.99.0", source)
        val result = run(dir, recipe.command, includeSystemTools = false)
        assertEquals(result.output, 0, result.exit)
        assertEquals(listOf("use", "--global", "go@latest", "use", "--global", "go:github.com/go-delve/delve/cmd/dlv@v1.99.0", "reshim"), args(dir))
        assertEquals(listOf("dlv"), recipe.healthCommands)
        assertTrue(recipe.aptPrerequisites.contains("build-essential"))
    }

    @Test fun installedGoIsReusedWithoutAnotherRuntimeSetup() = host { dir ->
        fake(dir, "go", "exit 0")
        fake(dir, "mise", "printf '%s\\0' \"\$@\" >> \"\$CALL_LOG\"")
        val source = PackageSourceDefinition("debug.delve", PackageSourceKind.GO, "github.com/go-delve/delve/cmd/dlv", "dlv",
            "https://github.com/go-delve/delve", goModulePath = "github.com/go-delve/delve")
        val result = run(dir, ToolUpdatePlanner.recipe(family("debug.delve"), "v1.99.0", source).command, includeSystemTools = false)
        assertEquals(result.output, 0, result.exit)
        assertEquals(listOf("use", "--global", "go:github.com/go-delve/delve/cmd/dlv@v1.99.0", "reshim"), args(dir))
    }

    @Test fun cargoBackendBootstrapsMissingRustAndHasNativeBuildPrerequisites() = host { dir ->
        fake(dir, "mise", "printf '%s\\0' \"\$@\" >> \"\$CALL_LOG\"")
        val source = PackageSourceDefinition("quality.custom", PackageSourceKind.CARGO, "some-tool", "tool", "https://crates.io/")
        val recipe = ToolUpdatePlanner.recipe(family("quality.custom"), "9.8.7", source)
        val result = run(dir, recipe.command, includeSystemTools = false)
        assertEquals(result.output, 0, result.exit)
        assertEquals(listOf("use", "--global", "rust@latest", "use", "--global", "cargo:some-tool@9.8.7", "reshim"), args(dir))
        assertTrue(recipe.aptPrerequisites.contains("build-essential"))
    }

    @Test fun compilerMajorAndExactAptVersionsReachThePublisherAsLiteralArguments() = host { dir ->
        fake(dir, "apt-get", "printf '%s\\0' \"\$@\" >> \"\$CALL_LOG\"; [ \"\$1\" = update ] || exit 29")
        val major = ToolUpdatePlanner.recipe(family("toolchain.gcc"), "14", null)
        assertEquals(29, run(dir, major.command).exit)
        assertTrue(args(dir).containsAll(listOf("gcc-14", "g++-14")))
        assertTrue(major.command.contains("/usr/bin/gcc-14"))
        File(dir, "calls").delete()
        val exact = ToolUpdatePlanner.recipe(family("cli.git"), "1:2.55.0-0ubuntu1", null)
        assertEquals(29, run(dir, exact.command).exit)
        assertTrue(args(dir).contains("git=1:2.55.0-0ubuntu1"))
    }

    @Test fun sdkLatestInstallsTheLatestStablePlatformInExistingUserSdkAndChecksIt() = host { dir ->
        sdkFixture(dir)
        val recipe = ToolUpdatePlanner.recipe(family("sdk.android"), "latest", null)
        val result = run(dir, recipe.command)
        assertEquals(result.output, 0, result.exit)
        val arguments = args(dir)
        assertTrue(arguments.contains("--sdk_root=${dir.absolutePath}/Android/Sdk"))
        assertTrue(arguments.contains("platforms;android-38"))
        assertFalse(arguments.contains("--update"))
        assertTrue(File(dir, "Android/Sdk/platforms/android-38/android.jar").exists())
    }

    @Test fun sdkExactVersionIsNotIgnoredAndMissingLicenseStopsBeforeInstallation() = host { dir ->
        sdkFixture(dir)
        val recipe = ToolUpdatePlanner.recipe(family("sdk.android"), "37", null)
        val result = run(dir, recipe.command)
        assertEquals(result.output, 0, result.exit)
        assertTrue(args(dir).contains("platforms;android-37"))
        File(dir, "calls").delete()
        File(dir, "Android/Sdk/licenses/android-sdk-license").delete()
        val refused = run(dir, recipe.command)
        assertNotEquals(0, refused.exit)
        assertTrue(refused.output.contains("--licenses"))
        assertFalse(File(dir, "calls").exists())
    }

    @Test fun debugpyUsesItsPythonModuleRatherThanAssumingPipProvidesAnExecutable() {
        val source = PackageSourceDefinition("debug.python-debugpy", PackageSourceKind.PYPI, "debugpy", "debugpy", "https://pypi.org/project/debugpy/")
        val recipe = ToolUpdatePlanner.recipe(family("debug.python-debugpy"), "9.8.7", source)
        val helper = PythonToolInstallCommands.recipe(source.familyId, "debugpy==9.8.7", source.command, source.healthArgs, module = "debugpy")
        assertEquals(helper.command, recipe.command)
        assertEquals(helper.healthCheckCommand, recipe.healthCheckCommand)
        assertEquals(listOf("python3", "python3-venv"), recipe.aptPrerequisites)
        assertEquals(listOf("debugpy"), recipe.healthCommands)
        assertTrue(recipe.healthCheckCommand!!.contains("-I -m 'debugpy'"))
        assertFalse(recipe.healthCheckCommand.contains("command -v"))
        assertTrue(recipe.command.contains("exec \"\$HOME/.local/share/droide-tools/debug.python-debugpy/bin/python\" -m debugpy \"\$@\""))
    }

    @Test fun failedSdkManagerCannotBecomeSuccessEvenIfAnOlderComponentIsPresent() = host { dir ->
        sdkFixture(dir)
        File(dir, "Android/Sdk/platforms/android-37").mkdirs()
        File(dir, "Android/Sdk/platforms/android-37/android.jar").writeText("old")
        fake(dir, "sdkmanager", "exit 29")
        assertEquals(29, run(dir, ToolUpdatePlanner.recipe(family("sdk.android"), "37", null).command).exit)
    }

    @Test fun sdkBuildToolsMustExecuteInsteadOfBeingAdmittedFromFilenamesAlone() = host { dir ->
        sdkFixture(dir)
        val build = File(dir, "Android/Sdk/build-tools/36.0.0").apply { mkdirs() }
        File(build, "aapt2").apply { writeText("#!/bin/sh\nexit 126\n"); setExecutable(true) }
        File(build, "apksigner").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
        fake(dir, "sdkmanager", "exit 0")
        val recipe = ToolUpdatePlanner.recipe(family("build.android-tools"), "36.0.0", null)
        assertEquals(126, run(dir, recipe.command).exit)
        assertTrue(recipe.healthCheckedDuringInstall)
    }

    private data class Result(val exit: Int, val output: String)
    private fun host(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("droide-update-provider-").toFile()
        try { File(dir, "bin").mkdirs(); block(dir) } finally { dir.deleteRecursively() }
    }
    private fun fake(dir: File, name: String, body: String) {
        File(dir, "bin/$name").apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }
    }
    private fun args(dir: File): List<String> = File(dir, "calls").readText().split('\u0000').dropLastWhile { it.isEmpty() }
    private fun run(dir: File, command: String, includeSystemTools: Boolean = true): Result {
        val output = File(dir, "output")
        val process = ProcessBuilder("/bin/sh", "-c", command).directory(dir).redirectErrorStream(true).redirectOutput(output).apply {
            environment()["HOME"] = dir.absolutePath
            environment()["PATH"] = "${dir.absolutePath}/bin" + if (includeSystemTools) ":/usr/bin:/bin" else ""
            environment()["CALL_LOG"] = File(dir, "calls").absolutePath
            environment().remove("ANDROID_HOME"); environment().remove("ANDROID_SDK_ROOT")
        }.start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Provider fixture timed out") }
        return Result(process.exitValue(), output.readText())
    }
    private fun sdkFixture(dir: File) {
        File(dir, "Android/Sdk/licenses").mkdirs()
        File(dir, "Android/Sdk/licenses/android-sdk-license").writeText("accepted fixture")
        fake(dir, "sdkmanager", """
            printf '%s\0' "${'$'}@" >> "${'$'}CALL_LOG"
            for arg in "${'$'}@"; do case "${'$'}arg" in --sdk_root=*) sdk="${'$'}{arg#--sdk_root=}";; --list) listing=1;; platforms\;android-*) component="${'$'}arg";; esac; done
            if [ "${'$'}{listing:-}" = 1 ]; then
                printf '%s\n' 'platforms;android-37 | 1 | Android API 37' 'platforms;android-38 | 1 | Android API 38' 'platforms;android-99-preview | 1 | Preview'
            else
                component_dir="${'$'}sdk/${'$'}(printf '%s' "${'$'}component" | tr ';' '/')"
                mkdir -p "${'$'}component_dir"; printf fixture > "${'$'}component_dir/android.jar"
            fi
        """.trimIndent())
    }
    @Test fun androidPrerequisiteCheckRejectsFreshSdkWithoutStartingAnInstall() = host { dir ->
        val check = requireNotNull(ToolUpdatePlanner.prerequisiteCheck("sdk.android", null))
        val result = run(dir, check, includeSystemTools = false)
        assertNotEquals(0, result.exit)
        assertTrue(result.output.contains("command-line tools are missing"))
        assertTrue(result.output.contains("reviewed SDK or Build Tools version"))
        assertFalse(File(dir, "calls").exists())
        assertFalse(File(dir, "Android/Sdk").exists())
    }

    @Test fun androidPrerequisiteCheckIsReadOnlyAndDoesNotInvokeExistingManager() = host { dir ->
        sdkFixture(dir)
        val result = run(dir, requireNotNull(ToolUpdatePlanner.prerequisiteCheck("sdk.android", null)))
        assertEquals(result.output, 0, result.exit)
        assertFalse(File(dir, "calls").exists())
    }

    @Test fun androidPrerequisiteCheckFindsManagerOutsidePathAndRequiresLicense() = host { dir ->
        sdkFixture(dir)
        val manager = File(dir, "Android/Sdk/cmdline-tools/latest/bin/sdkmanager")
        requireNotNull(manager.parentFile).mkdirs()
        assertTrue(File(dir, "bin/sdkmanager").renameTo(manager))
        val check = requireNotNull(ToolUpdatePlanner.prerequisiteCheck("sdk.android", null))
        assertEquals(0, run(dir, check).exit)
        File(dir, "Android/Sdk/licenses/android-sdk-license").delete()
        val refused = run(dir, check)
        assertNotEquals(0, refused.exit)
        assertTrue(refused.output.contains("--licenses"))
        assertFalse(File(dir, "calls").exists())
    }

    @Test fun sdkPrerequisitesCoverAllSdkFamiliesButDoNotAffectOtherProviders() {
        for (id in listOf("sdk.android", "build.android-tools", "toolchain.android-ndk")) {
            assertNotNull(ToolUpdatePlanner.prerequisiteCheck(id, null))
        }
        assertNotNull(ToolUpdatePlanner.prerequisiteCheck("cli.adb",
            PackageSourceDefinition("cli.adb", PackageSourceKind.ANDROID_SDK, "platform-tools", "adb", "https://developer.android.com/")))
        assertNull(ToolUpdatePlanner.prerequisiteCheck("runtime.node", null))
    }

}
