package com.baystudio.droide.core

/** Mutable user tools use their upstream managers; managed snapshot receipts are not edited. */
data class UserToolUpdateRecipe(
    val command: String,
    val usesMise: Boolean = false,
    val aptPrerequisites: List<String> = emptyList(),
    val provider: String,
    val healthCommands: List<String> = emptyList(),
    val healthCheckCommand: String? = null,
    val healthCheckedDuringInstall: Boolean = false,
    val selectedCommandChecks: List<UserToolCommandCheck> = emptyList(),
    val expectedCommandPaths: Map<String, String> = emptyMap(),
    val miseRequest: String? = null,
)

internal object ToolUpdatePlanner {
    private val runtimeTools = mapOf(
        "runtime.node" to "node", "runtime.python" to "python", "runtime.ruby" to "ruby",
        "runtime.php" to "php", "runtime.lua" to "lua", "runtime.perl" to "perl",
        "runtime.dart" to "dart", "runtime.powershell" to "github:PowerShell/PowerShell",
        "toolchain.go" to "go", "toolchain.rust" to "rust", "toolchain.jdk" to "java",
        "toolchain.kotlin" to "kotlin", "toolchain.dotnet" to "dotnet", "toolchain.nim" to "nim",
        "toolchain.haskell" to "ghc", "toolchain.scala" to "scala", "toolchain.erlang" to "erlang",
        "toolchain.elixir" to "elixir", "toolchain.clojure" to "clojure", "toolchain.swift" to "swift",
        "toolchain.llvm" to "clang", "toolchain.zig" to "zig",
        "toolchain.solidity" to "solidity", "sdk.flutter" to "flutter", "sdk.kotlin" to "kotlin",
        "build.gradle" to "gradle", "build.maven" to "maven", "build.cmake" to "cmake",
        "package.bun" to "bun", "package.uv" to "uv", "package.pnpm" to "pnpm", "package.yarn" to "yarn",
        "package.pub" to "dart", "test.dart" to "dart", "framework.flutter" to "flutter",
        "package.gradle" to "gradle", "test.gradle" to "gradle", "package.maven" to "maven",
        "package.sbt" to "sbt", "package.leiningen" to "leiningen",
        "quality.gofmt" to "go", "test.go" to "go", "test.exunit" to "elixir",
        "package.cargo" to "rust", "test.cargo" to "rust",
        "cli.helm" to "helm", "cli.kubectl" to "kubectl", "build.bazel" to "bazel",
        "quality.golangci-lint" to "golangci-lint", "quality.stylua" to "stylua",
        "quality.tflint" to "tflint", "quality.hadolint" to "hadolint", "quality.protolint" to "protolint",
        "quality.swiftformat" to "swiftformat", "quality.swiftlint" to "swiftlint", "quality.ktlint" to "ktlint",
        "lsp.rust-analyzer" to "rust-analyzer",
        "lsp.sourcekit-lsp" to "swift", "lsp.dart" to "dart",
    )

    fun request(version: String): String {
        val value = version.trim()
        require(value.isNotEmpty() && value.length <= 100 && value.none { it.isISOControl() }) { "Enter latest or a package version" }
        return if (value in setOf("Latest official", "Latest npm", "Latest Node.js", "Reviewed Ubuntu release")) "latest" else value
    }

    fun recipe(family: ExtensionFamily, requested: String, source: PackageSourceDefinition?): UserToolUpdateRecipe {
        val recipe = createRecipe(family, requested, source)
        return recipe.copy(selectedCommandChecks = UserToolUpdateHealth.checks(family.id, request(requested), recipe))
    }

    private fun createRecipe(family: ExtensionFamily, requested: String, source: PackageSourceDefinition?): UserToolUpdateRecipe {
        val version = request(requested)
        require(family.id!=LocalUbuntuElpPolicy.FAMILY) { "ELP requires its original pinned installer and explicit runtime health approval; generic update unavailable" }
        require(family.id != LocalUbuntuCMakePolicy.FAMILY) { "Use the exact reviewed CMake five-wheel installer. Generic pip/latest/sdist/extra updates are unavailable." }
        require(family.id != LocalUbuntuSveltePolicy.FAMILY_ID) { "Use the exact reviewed Svelte ${LocalUbuntuSveltePolicy.VERSION} managed installer; latest, tags and global/source fallback updates are unavailable." }
        require(!LocalUbuntuGraphqlPolicy.isFamily(family.id)) { "Use exact GraphQL original-library/private-Node installation; generic update sources cannot substitute this route." }
        require(!LocalUbuntuTomlPolicy.isFamily(family.id)) { "Use exact Taplo original-library/private-Node installation; generic update sources cannot substitute this route." }
        require(!LocalUbuntuCSharpPolicy.isFamily(family.id)) { "C# uses exact original three-artifact installation; generic update sources are unavailable." }
        require(!LocalUbuntuVuePolicy.isFamily(family.id)) { "Use the exact explicit Vue bundle/private Node installer; no generic npm, latest or compiler substitution." }
        require(!LocalUbuntuNimPolicy.isFamily(family.id)) {
            "Use the explicit three-artifact fixed Nim installer in Extensions. Generic updates cannot replace its private compiler or helper."
        }
        require(family.id !in setOf(LocalUbuntuPowerShellRuntimePolicy.FAMILY, LocalUbuntuPowerShellEditorServicesPolicy.FAMILY)) {
            "Use the exact reviewed PowerShell 7.6.7 / PSES 4.7.0 installer in Extensions; generic updates cannot substitute managed prerequisites."
        }
        require(family.id != LocalUbuntuClojurePolicy.FAMILY) {
            "Use the explicit reviewed Clojure ${LocalUbuntuClojurePolicy.VERSION} original native installer in Extensions; an unreviewed generic update is unavailable and no version is substituted."
        }
        require(family.id != LocalUbuntuSqruffPolicy.FAMILY) {
            "Use the explicit reviewed Sqruff ${LocalUbuntuSqruffPolicy.VERSION} installer in Extensions; an unreviewed generic update is unavailable and no version is substituted."
        }
        require(family.id != LocalUbuntuMakePolicy.FAMILY) {
            "Use the explicit reviewed Make LS ${LocalUbuntuMakePolicy.VERSION} installer in Extensions; an unreviewed generic update is unavailable and no version is substituted."
        }
        require(family.id != LocalUbuntuLemMinxPolicy.FAMILY) {
            "Use the explicit reviewed LemMinX ${LocalUbuntuLemMinxPolicy.VERSION} original-JAR installer in Extensions; an unreviewed generic update is unavailable and no version is substituted."
        }
        require(family.id != LocalUbuntuZlsPolicy.FAMILY) {
            "Use the explicit reviewed ZLS ${LocalUbuntuZlsPolicy.VERSION} installer in Extensions; an unreviewed generic update is unavailable and no version is substituted."
        }
        require(family.id != LocalUbuntuBufPolicy.FAMILY) {
            "Use the explicit reviewed Buf ${LocalUbuntuBufPolicy.VERSION} installer in Extensions; an unreviewed generic update is unavailable and no version is substituted."
        }
        require(family.id != LocalUbuntuTerraformPolicy.FAMILY) {
            "Use the explicit reviewed Terraform LS ${LocalUbuntuTerraformPolicy.VERSION} installer in Extensions; an unreviewed generic update is unavailable and no version is substituted."
        }
        require(family.id != LocalUbuntuTexlabPolicy.FAMILY) {
            "Use the explicit reviewed Texlab ${LocalUbuntuTexlabPolicy.VERSION} installer in Extensions; an unreviewed generic update is unavailable and no version is substituted."
        }
        require(family.id != LocalUbuntuMarksmanPolicy.FAMILY) {
            "Use the explicit reviewed Marksman ${LocalUbuntuMarksmanPolicy.VERSION} installer in Extensions; an unreviewed generic update is unavailable and no version is substituted."
        }
        require(family.versions.none { it.installKind == ExtensionInstallKind.BUILT_IN }) { "Built-in editor features are updated with Droide" }
        val apt = UbuntuAptCatalog.find(family.id)
        if (apt != null && version == "latest" && source?.source !in setOf(PackageSourceKind.NPM, PackageSourceKind.PYPI, PackageSourceKind.GO,
                PackageSourceKind.CARGO, PackageSourceKind.RUBYGEMS, PackageSourceKind.COMPOSER)) {
            return aptRecipe(apt.packages, apt.commands)
        }
        if (family.id == "toolchain.gcc") {
            val major = version.toIntOrNull()
            return if (major != null) aptRecipe(listOf("gcc-$major", "g++-$major", "make", "libc6-dev", "pkg-config"),
                mapOf("gcc" to "/usr/bin/gcc-$major", "g++" to "/usr/bin/g++-$major", "cc" to "/usr/bin/gcc-$major", "c++" to "/usr/bin/g++-$major"))
            else aptRecipe(listOf("gcc=$version", "g++=$version", "make", "libc6-dev", "pkg-config"),
                mapOf("gcc" to "/usr/bin/gcc", "g++" to "/usr/bin/g++"))
        }
        if (version.toIntOrNull() != null) {
            val names = when (family.id) {
                "toolchain.llvm" -> listOf("clang", "lld")
                "lsp.clangd" -> listOf("clangd")
                "quality.clang-format" -> listOf("clang-format")
                "quality.clang-tidy" -> listOf("clang-tidy")
                "debug.lldb" -> listOf("lldb")
                else -> null
            }
            if (names != null) {
                val aliases = when (family.id) {
                    "toolchain.llvm" -> mapOf("clang" to "/usr/bin/clang-$version", "clang++" to "/usr/bin/clang++-$version", "ld.lld" to "/usr/bin/ld.lld-$version")
                    "debug.lldb" -> mapOf("lldb" to "/usr/bin/lldb-$version", "lldb-dap" to "/usr/bin/lldb-dap-$version")
                    else -> names.associateWith { "/usr/bin/$it-$version" }
                }
                return aptRecipe(names.map { "$it-$version" } + if (family.id == "toolchain.llvm") listOf("build-essential", "libc6-dev", "pkg-config") else emptyList(), aliases)
            }
        }
        UbuntuNpmCatalog.find(family.id)?.let { npm ->
            require(npm.reviewedVersion == null) { "Use the explicit reviewed ${npm.packageName} ${npm.reviewedVersion} installer; an unreviewed global update is unavailable." }
            return UserToolUpdateRecipe("npm install --global " + q("${npm.packageName}@$version"), provider = "npm", healthCommands = listOf(npm.command))
        }
        if (family.id == "package.npm") return UserToolUpdateRecipe("npm install --global " + q("npm@$version"),
            provider = "npm", healthCommands = listOf("npm"))
        if (family.id == "lsp.ruby-lsp" || source?.source == PackageSourceKind.RUBYGEMS) {
            val name = if (family.id == "lsp.ruby-lsp") "ruby-lsp" else requireNotNull(source).packageName
            if (family.id == "package.rubygems") return UserToolUpdateRecipe("gem update --system " +
                (if (version == "latest") "" else q(version) + " ") + "--no-document",
                aptPrerequisites = listOf("ruby"), provider = "RubyGems", healthCommands = listOf("gem"))
            return UserToolUpdateRecipe("gem install --no-document " + q(name) + if (version == "latest") "" else " --version " + q(version),
                aptPrerequisites = listOf("ruby", "ruby-dev", "build-essential"), provider = "RubyGems", healthCommands = listOf(source?.command ?: "ruby-lsp"))
        }
        if (family.id == "package.hex") return UserToolUpdateRecipe(
            "mix local.hex " + (if (version == "latest") "" else q(version) + " ") + "--force",
            aptPrerequisites = listOf("elixir", "erlang-ssl"), provider = "Hex", healthCommands = listOf("mix"), healthCheckCommand = "mix hex.info")
        if (source?.source == PackageSourceKind.NPM) {
            val global = if (family.category == ExtensionCategory.FRAMEWORKS) "" else "--global "
            return UserToolUpdateRecipe("npm install $global" + q("${source.packageName}@$version"), provider = "npm",
                healthCommands = if (global.isBlank()) emptyList() else listOf(source.command),
                healthCheckCommand = if (global.isBlank()) "node -e " + q("require.resolve(" + js(source.packageName) + ")") else null)
        }
        if (source?.source == PackageSourceKind.PYPI) {
            val packageSpec = if (version == "latest") source.packageName else "${source.packageName}==$version"
            val generated = PythonToolInstallCommands.recipe(family.id, packageSpec, source.command, source.healthArgs,
                module = if (family.id == "debug.python-debugpy") "debugpy" else null)
            return UserToolUpdateRecipe(generated.command, aptPrerequisites = listOf("python3", "python3-venv"), provider = "PyPI / user virtualenv",
                healthCommands = listOf(source.command), healthCheckCommand = generated.healthCheckCommand)
        }
        if (source?.source == PackageSourceKind.GO) return mise("go:${source.packageName}", version, listOf(source.command),
            before = "if ! command -v go >/dev/null 2>&1; then mise use --global 'go@latest' || exit; fi; ",
            packages = listOf("git", "build-essential", "pkg-config", "ca-certificates"))
        if (source?.source == PackageSourceKind.CARGO) return mise("cargo:${source.packageName}", version, listOf(source.command),
            before = "if ! command -v cargo >/dev/null 2>&1; then mise use --global 'rust@latest' || exit; fi; ",
            packages = listOf("git", "build-essential", "pkg-config", "ca-certificates"))
        if (source?.source == PackageSourceKind.COMPOSER) return UserToolUpdateRecipe(
            "composer global require " + q(source.packageName + if (version == "latest") "" else ":$version") +
                " && composer_bin=\$(composer global config bin-dir --absolute) && mkdir -p \"\$HOME/.local/bin\" && ln -sf \"\$composer_bin/" + source.command + "\" \"\$HOME/.local/bin/" + source.command + "\"",
            aptPrerequisites = listOf("composer", "php-cli", "php-mbstring", "php-xml", "unzip"), provider = "Composer", healthCommands = listOf(source.command))
        if (source?.source == PackageSourceKind.ANDROID_SDK || family.id in setOf("sdk.android", "build.android-tools", "toolchain.android-ndk")) {
            return androidSdk(family.id, version, source)
        }
        if (family.id in setOf("quality.rustfmt", "quality.clippy")) {
            val component = if (family.id == "quality.rustfmt") "rustfmt" else "clippy"
            return mise("rust", if (version == "latest") "stable" else version, listOf(if (component == "rustfmt") "rustfmt" else "cargo-clippy"),
                after = " && mise exec -- rustup component add " + q(component))
        }
        runtimeTools[family.id]?.let { tool ->
            val commands = when (family.id) {
                "runtime.powershell" -> listOf("pwsh")
                "toolchain.llvm" -> listOf("clang", "clang++")
                else -> family.versions.flatMap { it.provides }.filter { it.matches(Regex("[A-Za-z0-9._+-]+")) }.distinct().take(1)
            }
            val needsJava = tool in setOf("kotlin", "scala", "clojure", "gradle", "maven", "sbt", "leiningen")
            return mise(tool, version, commands,
                before = if (needsJava) "if ! command -v java >/dev/null 2>&1; then mise use --global 'java@latest' || exit; fi; " else "",
                packages = if (tool in setOf("clang", "rust", "nim", "swift")) listOf("build-essential", "pkg-config", "ca-certificates") else emptyList())
        }
        if (apt != null) return aptRecipe(listOf(apt.packages.first() + "=$version") + apt.packages.drop(1), apt.commands)
        if (source?.source == PackageSourceKind.GITHUB_RELEASE) return mise("github:${source.packageName}", version, listOf(source.command))
        LocalUbuntuVendorCatalog.find(family.id)?.repository?.let { return mise("github:$it", version, listOf(LocalUbuntuVendorCatalog.find(family.id)!!.command)) }
        error("${family.name} uses project dependencies or publisher-specific installation; use its project package manager or publisher installer in Terminal. Requested version $version is not restricted by Droide.")
    }

    private fun aptRecipe(packages: List<String>, aliases: Map<String, String>): UserToolUpdateRecipe {
        val links = aliases.entries.filter { (name, path) -> path.substringAfterLast('/') != name }
        val command = "apt-get update && apt-get -y --no-install-recommends --no-remove install " + packages.joinToString(" ", transform = ::q) +
            if (links.isEmpty()) "" else " && mkdir -p \"\$HOME/.local/bin\" && " + links.joinToString(" && ") { (name, path) ->
                "test -x " + q(path) + " && ln -sf " + q(path) + " \"\$HOME/.local/bin/" + name + "\""
            }
        return UserToolUpdateRecipe(command, provider = "Ubuntu APT", healthCommands = aliases.keys.toList(), expectedCommandPaths = aliases)
    }

    private fun androidSdk(familyId: String, version: String, source: PackageSourceDefinition?): UserToolUpdateRecipe {
        val prefix = when (familyId) {
            "sdk.android" -> "platforms;android-"
            "build.android-tools" -> "build-tools;"
            "toolchain.android-ndk" -> "ndk;"
            else -> source?.packageName ?: error("Android package selector is missing")
        }
        require(version == "latest" || familyId in setOf("sdk.android", "build.android-tools", "toolchain.android-ndk")) {
            "SDK Manager publishes $prefix as its current package; historical revisions require the publisher archive in Terminal"
        }
        val discover = sdkDiscovery()
        val select = if (version != "latest") "SDK_COMPONENT=" + q(prefix + version) + "; " else when (familyId) {
            "sdk.android", "build.android-tools", "toolchain.android-ndk" -> {
                val pattern = when (familyId) {
                    "sdk.android" -> "^platforms;android-[0-9]+$"
                    "build.android-tools" -> "^build-tools;[0-9]+[.][0-9]+[.][0-9]+$"
                    else -> "^ndk;[0-9]+[.][0-9]+[.][0-9]+$"
                }
                "SDK_LIST=\$(\"\$SDK_MANAGER\" --sdk_root=\"\$SDK_ROOT\" --list --channel=0) || exit; " +
                    "SDK_COMPONENT=\$(printf '%s\\n' \"\$SDK_LIST\" | awk -F'|' " +
                    q("{p=\$1; gsub(/^[ \\t]+|[ \\t]+$/,\"\",p); if (p ~ /$pattern/) print p}") +
                    " | sort -V | tail -1); [ -n \"\$SDK_COMPONENT\" ] || { echo 'Publisher returned no stable Android package'; exit 1; }; "
            }
            else -> "SDK_COMPONENT=" + q(prefix) + "; "
        }
        val probe = when (familyId) {
            "sdk.android" -> "test -s \"\$SDK_ROOT/\$(printf '%s' \"\$SDK_COMPONENT\" | tr ';' '/')/android.jar\""
            "build.android-tools" -> "component_dir=\"\$SDK_ROOT/\$(printf '%s' \"\$SDK_COMPONENT\" | tr ';' '/')\"; " +
                "test -x \"\$component_dir/aapt2\" && \"\$component_dir/aapt2\" version && test -x \"\$component_dir/apksigner\" && \"\$component_dir/apksigner\" version"
            "toolchain.android-ndk" -> "component_dir=\"\$SDK_ROOT/\$(printf '%s' \"\$SDK_COMPONENT\" | tr ';' '/')\"; " +
                "test -s \"\$component_dir/source.properties\" || exit 1; ndk_clang=''; " +
                "for compiler_candidate in \"\$component_dir\"/toolchains/llvm/prebuilt/*/bin/clang; do [ -x \"\$compiler_candidate\" ] && { ndk_clang=\"\$compiler_candidate\"; break; }; done; " +
                "[ -n \"\$ndk_clang\" ] && \"\$ndk_clang\" --version"
            else -> "test -x \"\$SDK_ROOT/platform-tools/${source?.command ?: "adb"}\" && \"\$SDK_ROOT/platform-tools/${source?.command ?: "adb"}\" " +
                if (source?.command == "fastboot") "--version" else "version"
        }
        val license = sdkLicenseCheck()
        return UserToolUpdateRecipe(discover + license + select +
            "printf 'Installing Android SDK package %s in %s\\n' \"\$SDK_COMPONENT\" \"\$SDK_ROOT\"; " +
            "\"\$SDK_MANAGER\" --sdk_root=\"\$SDK_ROOT\" --install \"\$SDK_COMPONENT\" < /dev/null && (" + probe + ")",
            provider = "Android SDK Manager", healthCheckCommand = if (version == "latest") null else discover + select + probe,
            healthCheckedDuringInstall = true)
    }

    /** Read-only prerequisite probe for explicit upstream requests; reviewed components do not need sdkmanager. */
    fun prerequisiteCheck(familyId: String, source: PackageSourceDefinition?): String? =
        if (source?.source == PackageSourceKind.ANDROID_SDK ||
            familyId in setOf("sdk.android", "build.android-tools", "toolchain.android-ndk")) {
            sdkDiscovery() + sdkLicenseCheck()
        } else null

    const val ANDROID_PREREQUISITE_GUIDANCE =
        "Upstream Android versions require Android SDK command-line tools and accepted SDK licenses in the Ubuntu terminal. " +
            "Choose a reviewed SDK or Build Tools version in Extensions to install without sdkmanager."

    private fun sdkLicenseCheck(): String =
        "[ -s \"\$SDK_ROOT/licenses/android-sdk-license\" ] || { " +
            "printf 'Accept the Android SDK licenses in Terminal first: %s --sdk_root=%s --licenses\\n' \"\$SDK_MANAGER\" \"\$SDK_ROOT\"; exit 1; }; "

    private fun sdkDiscovery(): String =
        "SDK_ROOT=''; for sdk_candidate in \"\${ANDROID_HOME:-}\" \"\${ANDROID_SDK_ROOT:-}\" \"\$HOME/Android/Sdk\" \"\$HOME/android-sdk\" \"\$HOME/.local/share/android-sdk\" /opt/android-sdk /opt/android-sdk-linux /usr/lib/android-sdk; do " +
            "[ -d \"\$sdk_candidate\" ] || continue; case \"\$sdk_candidate\" in /opt/droide/packages/*|/opt/droide/android-sdk|/opt/droide/android-sdk/*) continue;; esac; " +
            "SDK_ROOT=\"\$sdk_candidate\"; break; done; " +
            "[ -n \"\$SDK_ROOT\" ] || SDK_ROOT=\"\$HOME/Android/Sdk\"; " +
            "SDK_MANAGER=\$(command -v sdkmanager 2>/dev/null || true); " +
            "if [ -z \"\$SDK_MANAGER\" ]; then for sdk_manager_candidate in \"\$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager\" \"\$SDK_ROOT\"/cmdline-tools/*/bin/sdkmanager \"\$SDK_ROOT/tools/bin/sdkmanager\" /opt/droide/android-sdk/cmdline-tools/latest/bin/sdkmanager; do " +
            "[ -x \"\$sdk_manager_candidate\" ] && { SDK_MANAGER=\"\$sdk_manager_candidate\"; break; }; done; fi; " +
            "[ -n \"\$SDK_MANAGER\" ] || { echo 'Android SDK command-line tools are missing. Choose a reviewed SDK or Build Tools version in Extensions, or install official command-line tools in Terminal for upstream versions'; exit 1; }; "

    private fun mise(tool: String, version: String, commands: List<String> = emptyList(), before: String = "", after: String = "", packages: List<String> = emptyList()) = UserToolUpdateRecipe(
        before + "mise use --global " + q("$tool@$version") + " && mise reshim" + after,
        usesMise = true, aptPrerequisites = packages, provider = "mise / publisher", healthCommands = commands, miseRequest = "$tool@$version")
    private fun js(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun q(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
}
