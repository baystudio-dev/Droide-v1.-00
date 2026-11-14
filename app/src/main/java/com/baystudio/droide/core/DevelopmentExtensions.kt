package com.baystudio.droide.core

import java.io.File

 
enum class ExtensionCategory(val label: String) {
    LANGUAGES("Languages"),
    SDKS("SDKs"),
    TOOLCHAINS("Toolchains"),
    BUILD_TOOLS("Build Tools"),
    RUNTIMES("Runtimes"),
    PACKAGE_MANAGERS("Packages & Dependencies"),
    CLI_TOOLS("CLI Tools"),
    PLUGINS("Plugins"),
    LANGUAGE_SERVERS("Language Servers"),
    FORMATTERS_LINTERS("Formatters & Linters"),
    DEBUGGERS("Debuggers"),
    TESTING("Testing"),
    FRAMEWORKS("Frameworks"),
    THEMES("Themes"),
}

enum class ExecutionScope {
     
    EDITOR,

     
    LOCAL,

     
    LOCAL_LINUX_ARM64,
}

enum class ExtensionInstallKind {
    BUILT_IN,
    ANDROID_MANAGED_TOOLCHAIN,
    ANDROID_LOCAL_COMPONENT,
    MANAGED_PACKAGE,
     
    GUEST_PACKAGE,
     
    REVIEWED_RECIPE,
    USER_MANAGED,
    CATALOG_ONLY,
}

data class ExtensionVersion(
    val version: String,
    val channel: String = "stable",
    val recommended: Boolean = false,
    val installKind: ExtensionInstallKind = ExtensionInstallKind.CATALOG_ONLY,
    val scope: ExecutionScope = ExecutionScope.LOCAL_LINUX_ARM64,
    val provides: Set<String> = emptySet(),
    val requires: Set<String> = emptySet(),
    val note: String = "",
    // These entry points are alternatives; other executable provides remain required.
    val alternativeCommands: Set<String> = emptySet(),
) {
    fun detectsExternalCommands(commands: Set<String>): Boolean {
        val executable = provides.filter { it.matches(Regex("[A-Za-z0-9._+-]{1,64}")) }.toSet()
        if (executable.isEmpty() || !executable.containsAll(alternativeCommands)) return false
        return (executable - alternativeCommands).all { it in commands } &&
            (alternativeCommands.isEmpty() || alternativeCommands.any { it in commands })
    }
}

data class ExtensionFamily(
    val id: String,
    val name: String,
    val category: ExtensionCategory,
    val description: String,
    val versions: List<ExtensionVersion>,
     
    val iconKey: String,
    val publisher: String = "",
    // Never used as executable input.
    val keywords: Set<String> = emptySet(),
)

enum class ExtensionState {
    BUILT_IN,
    INSTALLED,
    EXTERNAL,
    AVAILABLE,
    UNAVAILABLE,
}

data class ExtensionVersionState(
    val family: ExtensionFamily,
    val version: ExtensionVersion,
    val state: ExtensionState,
    val detail: String,
)


object DevelopmentExtensionCatalog {
    private fun family(
        id: String,
        name: String,
        category: ExtensionCategory,
        description: String,
        iconKey: String,
        publisher: String,
        versions: List<ExtensionVersion>,
        vararg keywords: String,
    ) = ExtensionFamily(
        id = id,
        name = name,
        category = category,
        description = description,
        versions = versions,
        iconKey = iconKey,
        publisher = publisher,
        keywords = keywords.filter(String::isNotBlank).map(String::lowercase).toSet(),
    )

    private fun externalVersion(
        vararg commands: String,
        requires: Set<String> = emptySet(),
        note: String = "Detected on the workstation and managed outside Droide.",
    ) = ExtensionVersion(
        version = "External",
        channel = "user-managed",
        scope = ExecutionScope.LOCAL_LINUX_ARM64,
        provides = commands.filter(String::isNotBlank).toSet(),
        requires = requires,
        note = note,
    )

    private fun externalFamily(
        id: String,
        name: String,
        category: ExtensionCategory,
        description: String,
        iconKey: String,
        publisher: String,
        commands: Set<String>,
        requires: Set<String> = emptySet(),
        alternatives: Set<String> = emptySet(),
        vararg keywords: String,
    ) = family(
        id = id,
        name = name,
        category = category,
        description = description,
        iconKey = iconKey,
        publisher = publisher,
        versions = listOf(externalVersion(*commands.toTypedArray(), requires = requires).copy(alternativeCommands = alternatives)),
        keywords = keywords,
    )

    private fun languageFamilies(): List<ExtensionFamily> = LanguageRegistry.all.distinctBy { it.id }.map { language ->
        val builtIn = LanguageRegistry.isBuiltIn(language.id)
        family(
            id = "language.${language.id}",
            name = language.name,
            category = ExtensionCategory.LANGUAGES,
            description = LanguageRuntimePresentation.description(language.id, language.name),
            iconKey = "language:${language.id}",
            publisher = if (builtIn) "Droide" else "Extension",
            versions = listOf(
                ExtensionVersion(
                    version = if (builtIn) "Built-in" else "Extension-provided",
                    channel = if (builtIn) "bundled" else "contributed",
                    recommended = true,
                    installKind = ExtensionInstallKind.BUILT_IN,
                    scope = ExecutionScope.EDITOR,
                    provides = setOf("language:${language.id}"),
                )
            ),
            keywords = arrayOf(language.id, *language.extensions.toTypedArray(), "syntax", "language"),
        )
    }

    private fun androidSdkFamily(): ExtensionFamily = family(
        id = "sdk.android",
        name = "Android SDK Platform",
        category = ExtensionCategory.SDKS,
        description = "Android SDK platforms for project builds.",
        iconKey = "brand:android",
        publisher = "Google",
        versions = listOf(
            ExtensionVersion(
                version = "Project-selected",
                channel = "project",
                recommended = true,
                scope = ExecutionScope.LOCAL_LINUX_ARM64,
                provides = setOf("android-sdk"),
                note = "Uses the SDK version required by the project.",
            )
        ),
        "android", "sdk", "platform", "adb", "build-tools",
    )

    private fun jdkFamily(): ExtensionFamily = family(
        id = "toolchain.jdk",
        name = "Java Development Kit",
        category = ExtensionCategory.TOOLCHAINS,
        description = "JDK toolchains for JVM and Android projects.",
        iconKey = "language:java",
        publisher = "OpenJDK",
        versions = listOf(
            ExtensionVersion(
                version = "Project-selected",
                channel = "project",
                recommended = true,
                scope = ExecutionScope.LOCAL_LINUX_ARM64,
                provides = setOf("java", "javac", "jar"),
                note = "Uses a compatible JDK for the project Gradle Wrapper.",
            )
        ),
        "java", "openjdk", "javac", "jvm", "jdk",
    )

    private fun runtimeAndToolchainFamilies(): List<ExtensionFamily> = listOf(
        externalFamily("toolchain.typescript", "TypeScript Compiler", ExtensionCategory.TOOLCHAINS, "TypeScript compiler installed separately from editor syntax support.", "language:typescript", "Microsoft", setOf("tsc")),
        externalFamily(
            "runtime.python", "Python Runtime", ExtensionCategory.RUNTIMES,
            "Python runtime for scripts, tooling and packages.",
            "language:python", "Python Software Foundation", setOf("python", "python3"), keywords = arrayOf("cpython", "pip"),
        ),
        externalFamily(
            "runtime.node", "Node.js Runtime", ExtensionCategory.RUNTIMES,
            "Node.js runtime for JavaScript/TypeScript tooling and packages.",
            "brand:nodejs", "OpenJS Foundation", setOf("node", "npm"), keywords = arrayOf("javascript", "typescript"),
        ),
        externalFamily("runtime.php", "PHP Runtime", ExtensionCategory.RUNTIMES, "PHP CLI runtime for PHP projects and Composer tooling.", "language:php", "PHP Foundation", setOf("php"), keywords = arrayOf("php-cli")),
        externalFamily("runtime.ruby", "Ruby Runtime", ExtensionCategory.RUNTIMES, "Ruby runtime for scripts, Bundler, RubyGems and Rails tooling.", "language:ruby", "Ruby", setOf("ruby"), keywords = arrayOf("ruby", "gem")),
        externalFamily("runtime.dart", "Dart SDK", ExtensionCategory.RUNTIMES, "Dart runtime, analyzer and package tooling.", "language:dart", "Google", setOf("dart"), keywords = arrayOf("dart-sdk", "pub")),
        externalFamily("runtime.lua", "Lua Runtime", ExtensionCategory.RUNTIMES, "Lua interpreter for scripts and Lua developer tools.", "language:lua", "Lua", setOf("lua")),
        externalFamily("runtime.r", "R Runtime", ExtensionCategory.RUNTIMES, "R runtime for statistical computing projects.", "language:r", "R Foundation", setOf("R", "Rscript")),
        externalFamily("runtime.perl", "Perl Runtime", ExtensionCategory.RUNTIMES, "Perl runtime and module tooling.", "language:perl", "Perl", setOf("perl")),
        externalFamily("runtime.csharp-sdk", "C# private SDK", ExtensionCategory.RUNTIMES, "Exact private SDK 10.0.402/runtime 10.0.13 for C# analysis; terminal dotnet remains separate.", "language:csharp", ".NET", setOf("droide-csharp-sdk")),
        externalFamily("runtime.csharp-helper", "C# private helper", ExtensionCategory.RUNTIMES, "Exact independent private Node 22.23.3 for C# owned analysis; no Node/npm CLI default.", "language:csharp", "Node.js", setOf("droide-csharp-helper")),
        externalFamily("runtime.graphql-node", "GraphQL private Node", ExtensionCategory.RUNTIMES, "Exact independent original Node 22.23.3 for GraphQL local-schema libraries; no terminal Node/npm default.", "language:graphql", "Node.js", setOf("droide-graphql-node")),
        externalFamily("runtime.taplo-node", "Taplo private Node", ExtensionCategory.RUNTIMES, "Exact independent Node 22.23.3 for original Taplo WASM; no terminal Node/npm default.", "language:toml", "Node.js", setOf("droide-taplo-node")),
        externalFamily("runtime.vue-helper", "Vue private helper", ExtensionCategory.RUNTIMES, "Exact independent private Node 22.23.3 for Vue paired analysis; no Node/npm CLI or terminal default.", "language:vue", "Node.js", setOf("droide-vue-helper")),
        externalFamily("runtime.nim-helper", "Nim private helper", ExtensionCategory.RUNTIMES, "Exact private Node 22.23.3 for fixed Nim analysis; no Node/npm CLI or terminal default.", "language:nim", "Node.js", setOf("droide-nim-helper")),
        externalFamily("runtime.nim-lsp", "Nim analysis toolchain", ExtensionCategory.RUNTIMES, "Exact private Nim/nimsuggest 2.2.12 and Nimble 0.24.1 for fixed analysis; terminal Nim remains separate.", "language:nim", "Nim", setOf("droide-nim-lsp-toolchain")),
        externalFamily("runtime.powershell", "PowerShell", ExtensionCategory.RUNTIMES, "PowerShell Core runtime for cross-platform scripts.", "language:powershell", "Microsoft", setOf("pwsh"), keywords = arrayOf("powershell-core")),
        externalFamily("toolchain.kotlin", "Kotlin Toolchain", ExtensionCategory.TOOLCHAINS, "Kotlin compiler and JVM tooling.", "language:kotlin", "JetBrains", setOf("kotlinc", "kotlin"), requires = setOf("java"), keywords = arrayOf("kotlin", "compiler")),
        externalFamily("toolchain.rust", "Rust Toolchain", ExtensionCategory.TOOLCHAINS, "Rust compiler, Cargo and standard developer tools.", "language:rust", "Rust Project", setOf("rustc", "cargo", "rustfmt"), keywords = arrayOf("rustup", "cargo")),
        externalFamily("toolchain.go", "Go Toolchain", ExtensionCategory.TOOLCHAINS, "Go compiler, module tooling and formatter.", "language:go", "Go Project", setOf("go", "gofmt"), keywords = arrayOf("golang", "go-mod")),
        externalFamily("toolchain.llvm", "LLVM / Clang", ExtensionCategory.TOOLCHAINS, "LLVM C/C++ compilers and linker with system headers and native build prerequisites.", "language:cpp", "LLVM Project", setOf("clang", "clang++", "ld.lld"), keywords = arrayOf("llvm", "lld", "clang", "c++")),
        externalFamily("toolchain.gcc", "GNU Compiler Collection", ExtensionCategory.TOOLCHAINS, "GNU C/C++ compilers, system headers and Make build prerequisites.", "language:c", "GNU Project", setOf("gcc", "g++", "cc", "c++"), keywords = arrayOf("gcc", "g++", "c++")),
        externalFamily("toolchain.dotnet", ".NET SDK", ExtensionCategory.TOOLCHAINS, ".NET SDK and CLI for C# projects.", "language:csharp", "Microsoft", setOf("dotnet"), keywords = arrayOf("csharp", "c#", "nuget")),
        externalFamily("toolchain.swift", "Swift Toolchain", ExtensionCategory.TOOLCHAINS, "Swift compiler and SourceKit-compatible tooling.", "language:swift", "Swift Project", setOf("swift", "swiftc")),
        externalFamily("toolchain.haskell", "GHC / Haskell Toolchain", ExtensionCategory.TOOLCHAINS, "Glasgow Haskell Compiler and package tooling.", "language:haskell", "Haskell", setOf("ghc", "runhaskell", "cabal"), keywords = arrayOf("ghc", "cabal")),
        externalFamily("toolchain.scala", "Scala Toolchain", ExtensionCategory.TOOLCHAINS, "Scala compiler and command-line tooling.", "language:scala", "Scala Center", setOf("scala", "scalac")),
        externalFamily("toolchain.erlang", "Erlang / OTP", ExtensionCategory.TOOLCHAINS, "Erlang runtime and compiler toolchain.", "language:erlang", "Erlang/OTP", setOf("erl", "erlc", "escript")),
        externalFamily("toolchain.elixir", "Elixir Toolchain", ExtensionCategory.TOOLCHAINS, "Elixir compiler, Mix and IEx tooling.", "language:elixir", "Elixir", setOf("elixir", "elixirc", "mix"), requires = setOf("erl")),
        externalFamily("toolchain.clojure", "Clojure CLI", ExtensionCategory.TOOLCHAINS, "Clojure CLI and JVM project tooling.", "language:clojure", "Clojure", setOf("clojure"), requires = setOf("java")),
        externalFamily("toolchain.zig", "Zig Toolchain", ExtensionCategory.TOOLCHAINS, "Zig compiler and build system.", "language:zig", "Zig Software Foundation", setOf("zig")),
        externalFamily("toolchain.nim", "Nim Toolchain", ExtensionCategory.TOOLCHAINS, "Nim compiler and Nimble ecosystem tooling.", "language:nim", "Nim", setOf("nim", "nimble")),
        externalFamily("toolchain.goose", "Goose Compiler", ExtensionCategory.TOOLCHAINS, GooseLanguage.INSTALL_NOTE,
            "language:goose", "aardappel/goose", setOf(GooseLanguage.COMMAND)),
        externalFamily("toolchain.solidity", "Solidity Compiler", ExtensionCategory.TOOLCHAINS, "Solidity compiler for smart-contract projects.", "language:solidity", "Solidity", setOf("solc"), keywords = arrayOf("ethereum", "solc")),
    )

    private fun packageManagerFamilies(): List<ExtensionFamily> = listOf(
        externalFamily("package.npm", "npm", ExtensionCategory.PACKAGE_MANAGERS, "Node.js package manager and dependency registry client.", "brand:npm", "npm", setOf("npm"), requires = setOf("node"), keywords = arrayOf("package.json", "dependencies")),
        externalFamily("package.pnpm", "pnpm", ExtensionCategory.PACKAGE_MANAGERS, "Fast, disk-efficient Node.js package manager.", "brand:pnpm", "pnpm", setOf("pnpm"), requires = setOf("node")),
        externalFamily("package.yarn", "Yarn", ExtensionCategory.PACKAGE_MANAGERS, "JavaScript package and workspace manager.", "brand:yarn", "Yarn", setOf("yarn"), requires = setOf("node")),
        externalFamily("package.bun", "Bun Package Manager", ExtensionCategory.PACKAGE_MANAGERS, "Bun package manager and JavaScript runtime tooling.", "brand:bun", "Oven", setOf("bun"), keywords = arrayOf("bunx")),
        externalFamily("package.pip", "pip / PyPI", ExtensionCategory.PACKAGE_MANAGERS, "Python package installer for PyPI-compatible indexes.", "language:python", "Python Packaging Authority", setOf("pip", "pip3"), requires = setOf("python3"), keywords = arrayOf("pypi", "requirements.txt")),
        externalFamily("package.uv", "uv", ExtensionCategory.PACKAGE_MANAGERS, "Python package and project manager.", "language:python", "Astral", setOf("uv"), keywords = arrayOf("python", "pyproject.toml")),
        externalFamily("package.poetry", "Poetry", ExtensionCategory.PACKAGE_MANAGERS, "Python dependency and project manager.", "brand:poetry", "Poetry", setOf("poetry"), requires = setOf("python3")),
        externalFamily("package.cargo", "Cargo", ExtensionCategory.PACKAGE_MANAGERS, "Rust package manager and build frontend.", "language:rust", "Rust Project", setOf("cargo"), requires = setOf("rustc"), keywords = arrayOf("crates.io", "cargo.toml")),
        externalFamily("package.gradle", "Gradle Dependencies", ExtensionCategory.PACKAGE_MANAGERS, "Gradle dependency resolution for JVM and Android workspaces.", "brand:gradle", "Gradle", setOf("gradle"), requires = setOf("java"), keywords = arrayOf("maven-central", "dependencies")),
        externalFamily("package.maven", "Maven Dependencies", ExtensionCategory.PACKAGE_MANAGERS, "Maven dependency resolution and repositories.", "brand:maven", "Apache Software Foundation", setOf("mvn"), requires = setOf("java"), keywords = arrayOf("pom.xml", "maven-central")),
        externalFamily("package.composer", "Composer", ExtensionCategory.PACKAGE_MANAGERS, "Dependency manager for PHP projects.", "language:php", "Composer", setOf("composer"), requires = setOf("php"), keywords = arrayOf("packagist", "composer.json")),
        externalFamily("package.rubygems", "RubyGems", ExtensionCategory.PACKAGE_MANAGERS, "Ruby package distribution and gem management.", "brand:rubygems", "RubyGems", setOf("gem"), requires = setOf("ruby"), keywords = arrayOf("gemfile", "gems")),
        externalFamily("package.bundler", "Bundler", ExtensionCategory.PACKAGE_MANAGERS, "Ruby dependency resolver for Gemfile projects.", "language:ruby", "Ruby", setOf("bundle", "bundler"), requires = setOf("ruby")),
        externalFamily("package.nuget", "NuGet", ExtensionCategory.PACKAGE_MANAGERS, ".NET package and dependency manager.", "brand:nuget", "Microsoft", setOf("nuget", "dotnet"), keywords = arrayOf("nupkg", "csharp")),
        externalFamily("package.pub", "Dart pub", ExtensionCategory.PACKAGE_MANAGERS, "Dart and Flutter package manager.", "language:dart", "Google", setOf("dart"), keywords = arrayOf("pub.dev", "pubspec.yaml")),
        externalFamily("package.hex", "Hex", ExtensionCategory.PACKAGE_MANAGERS, "Package manager for the Erlang VM ecosystem.", "language:elixir", "Hex", setOf("mix"), keywords = arrayOf("hex.pm", "elixir", "erlang")),
        externalFamily("package.sbt", "sbt", ExtensionCategory.PACKAGE_MANAGERS, "Build and dependency tool for Scala projects.", "language:scala", "Scala", setOf("sbt"), requires = setOf("java")),
        externalFamily("package.leiningen", "Leiningen", ExtensionCategory.PACKAGE_MANAGERS, "Project and dependency automation for Clojure.", "language:clojure", "Clojure", setOf("lein"), requires = setOf("java")),
        externalFamily("package.nimble", "Nimble", ExtensionCategory.PACKAGE_MANAGERS, "Package manager for Nim projects.", "language:nim", "Nim", setOf("nimble"), requires = setOf("nim")),
    )

    private fun buildToolFamilies(): List<ExtensionFamily> = listOf(
        family(
            "build.android-tools", "Android Build Tools", ExtensionCategory.BUILD_TOOLS,
            "Android packaging, dexing and signing tools used by Android builds.", "brand:android", "Google",
            listOf(
                ExtensionVersion(
                    "Project-selected",
                    channel = "project",
                    recommended = true,
                    provides = setOf("aapt2", "apksigner", "zipalign"),
                    note = "Uses the Build Tools revision required by the project.",
                ),
            ),
            "aapt2", "d8", "r8", "apksigner", "zipalign",
        ),
        externalFamily("build.cmake", "CMake", ExtensionCategory.BUILD_TOOLS, "CMake project configuration and build generation.", "brand:cmake", "Kitware", setOf("cmake")),
        externalFamily("build.ninja", "Ninja", ExtensionCategory.BUILD_TOOLS, "Small high-speed build executor commonly generated by CMake.", "category:build", "Ninja", setOf("ninja")),
        externalFamily("build.gradle", "Gradle", ExtensionCategory.BUILD_TOOLS, "Gradle build system. Project Gradle Wrapper is preferred.", "brand:gradle", "Gradle", setOf("gradle"), requires = setOf("java"), keywords = arrayOf("gradlew", "android")),
        externalFamily("build.maven", "Apache Maven", ExtensionCategory.BUILD_TOOLS, "Maven build lifecycle for JVM projects.", "brand:maven", "Apache Software Foundation", setOf("mvn"), requires = setOf("java")),
        externalFamily("build.make", "GNU Make", ExtensionCategory.BUILD_TOOLS, "Makefile build automation.", "language:makefile", "GNU Project", setOf("make"), keywords = arrayOf("makefile")),
        externalFamily("build.meson", "Meson", ExtensionCategory.BUILD_TOOLS, "Meson native build system.", "category:build", "Meson", setOf("meson")),
        externalFamily("build.bazel", "Bazel", ExtensionCategory.BUILD_TOOLS, "Hermetic multi-language build system.", "brand:bazel", "Bazel", setOf("bazel", "bazelisk"), alternatives = setOf("bazel", "bazelisk")),
    )

    private fun cliToolFamilies(): List<ExtensionFamily> = listOf(
        externalFamily("cli.git", "Git CLI", ExtensionCategory.CLI_TOOLS, "Distributed version-control command line.", "brand:git", "Git Project", setOf("git"), keywords = arrayOf("scm", "version-control")),
        externalFamily("cli.gh", "GitHub CLI", ExtensionCategory.CLI_TOOLS, "GitHub pull request, issue, release and repository workflows.", "brand:github", "GitHub", setOf("gh")),
        externalFamily("cli.curl", "curl", ExtensionCategory.CLI_TOOLS, "URL transfer and HTTP debugging tool.", "brand:curl", "curl", setOf("curl")),
        externalFamily("cli.wget", "wget", ExtensionCategory.CLI_TOOLS, "Network downloader for HTTP/HTTPS resources.", "category:terminal", "GNU Project", setOf("wget")),
        externalFamily("cli.jq", "jq", ExtensionCategory.CLI_TOOLS, "Command-line JSON processor.", "language:json", "jq", setOf("jq")),
        externalFamily("cli.ripgrep", "ripgrep", ExtensionCategory.CLI_TOOLS, "Fast recursive text search used by developer workflows.", "category:search", "BurntSushi", setOf("rg"), keywords = arrayOf("rg", "grep", "search")),
        externalFamily("cli.fd", "fd", ExtensionCategory.CLI_TOOLS, "Fast filesystem search utility.", "category:search", "sharkdp", setOf("fd")),
        externalFamily("cli.fzf", "fzf", ExtensionCategory.CLI_TOOLS, "Interactive fuzzy finder for terminal workflows.", "category:search", "junegunn", setOf("fzf")),
        externalFamily("cli.tree", "tree", ExtensionCategory.CLI_TOOLS, "Directory-tree inspection command.", "category:terminal", "tree", setOf("tree")),
        externalFamily("cli.rsync", "rsync", ExtensionCategory.CLI_TOOLS, "Incremental file synchronization tool.", "category:terminal", "rsync", setOf("rsync")),
        externalFamily("cli.ssh", "OpenSSH", ExtensionCategory.CLI_TOOLS, "SSH client and secure remote development transport.", "category:terminal", "OpenBSD", setOf("ssh", "scp", "sftp")),
        externalFamily("cli.tar", "tar", ExtensionCategory.CLI_TOOLS, "Archive creation and extraction.", "category:archive", "GNU Project", setOf("tar")),
        externalFamily("cli.zip", "zip / unzip", ExtensionCategory.CLI_TOOLS, "ZIP archive creation and extraction.", "category:archive", "Info-ZIP", setOf("zip", "unzip")),
        externalFamily("cli.sqlite", "SQLite CLI", ExtensionCategory.CLI_TOOLS, "SQLite database shell and inspection tool.", "brand:sqlite", "SQLite", setOf("sqlite3"), keywords = arrayOf("database")),
        externalFamily("cli.adb", "Android Debug Bridge", ExtensionCategory.CLI_TOOLS, "ADB device, install, shell and logcat command line.", "brand:android", "Google", setOf("adb"), keywords = arrayOf("android", "platform-tools")),
        externalFamily("cli.fastboot", "Fastboot", ExtensionCategory.CLI_TOOLS, "Android bootloader protocol command-line tool.", "brand:android", "Google", setOf("fastboot"), keywords = arrayOf("android", "platform-tools")),
        externalFamily("cli.docker", "Docker CLI", ExtensionCategory.CLI_TOOLS, "Docker container client when a reachable engine is available.", "brand:docker", "Docker", setOf("docker"), keywords = arrayOf("container")),
        externalFamily("cli.kubectl", "kubectl", ExtensionCategory.CLI_TOOLS, "Kubernetes cluster command-line client.", "brand:kubernetes", "Kubernetes", setOf("kubectl"), keywords = arrayOf("k8s", "cluster")),
        externalFamily("cli.helm", "Helm", ExtensionCategory.CLI_TOOLS, "Kubernetes package manager CLI.", "brand:helm", "Helm", setOf("helm"), keywords = arrayOf("kubernetes", "charts")),
    )

    private fun aiAgentFamilies(): List<ExtensionFamily> = listOf(
        family(
            "plugin.deepseek-harness", "DeepSeek Harness", ExtensionCategory.PLUGINS,
            "Official DeepSeek coding harness with native ACP chat, tools and permission requests. Public preview.",
            "brand:deepseek", "DeepSeek",
            listOf(ExtensionVersion("0.2.2-rc.1", channel = "preview", recommended = true,
                installKind = ExtensionInstallKind.REVIEWED_RECIPE, provides = setOf("dsh"), requires = setOf("node", "npm"),
                note = "Official @deepseek-ai/dsh. Requires Node.js 24.9+; updates through Extensions. Configure providers with dsh web --no-open.")),
            "deepseek", "dsh", "harness", "ai", "agent", "acp",
        ),
        family(
            LocalUbuntuAgentDistributions.ANTIGRAVITY, "Antigravity CLI", ExtensionCategory.PLUGINS,
            "Google's original terminal agent with native ACP chat, tools and permission requests.",
            "brand:antigravity", "Google",
            listOf(ExtensionVersion(LocalUbuntuAgentDistributions.ANTIGRAVITY_VERSION, channel = "stable", recommended = true,
                installKind = ExtensionInstallKind.REVIEWED_RECIPE, provides = setOf("agy"),
                note = "Original Linux ARM64 binary, pinned checksum and managed updates. Sign in with agy acp --login in the Linux terminal.")),
            "antigravity", "agy", "google", "ai", "agent", "acp",
        ),
        family(
            LocalUbuntuMusePolicy.FAMILY, "Muse Code", ExtensionCategory.PLUGINS,
            "Meta's original coding agent with the community Muse Code ACP adapter for panel chat.",
            "brand:muse-code", "Meta / bex-co (community adapter)",
            listOf(ExtensionVersion(LocalUbuntuMusePolicy.VERSION, channel = "adapter", recommended = true,
                installKind = ExtensionInstallKind.REVIEWED_RECIPE, provides = setOf("muse", "muse-code-acp"), requires = setOf("node", "npm"),
                note = "Installs Meta Muse " + LocalUbuntuAgentDistributions.MUSE_VERSION + " and ACP adapter " + LocalUbuntuMusePolicy.VERSION +
                    ". Includes Node.js when needed. Native runtime download: 360 MB. Sign in with muse login in the Linux terminal.")),
            "meta", "muse", "ai", "agent", "acp", "muse-code-acp",
        ),
        family(
            "plugin.kimi-code", "Kimi Code CLI", ExtensionCategory.PLUGINS,
            "Moonshot AI's original terminal coding agent with native ACP panel chat.",
            "brand:kimi-code", "Moonshot AI",
            listOf(ExtensionVersion("2.5.2", channel = "stable", recommended = true,
                installKind = ExtensionInstallKind.REVIEWED_RECIPE, provides = setOf("kimi"), requires = setOf("node", "npm"),
                note = "Official @moonshot-ai/kimi-code 2.5.2; requires Node.js 22.19+ (installed when needed). Run kimi, then /login in the Linux terminal.")),
            "kimi", "moonshot", "ai", "agent", "acp", "@moonshot-ai/kimi-code",
        ),
        family(
            "plugin.opencode", "OpenCode", ExtensionCategory.PLUGINS,
            "Open-source coding agent with ACP editor integration and a structured headless fallback.",
            "brand:opencode", "OpenCode",
            listOf(ExtensionVersion("1.18.34", channel = "stable", recommended = true, installKind = ExtensionInstallKind.REVIEWED_RECIPE, provides = setOf("opencode"), requires = setOf("node", "npm"), note = "Installs official opencode-ai in local Ubuntu. Installation includes official Node.js 22 LTS and npm when needed.")),
            "ai", "agent", "acp", "mcp", "opencode-ai",
        ),
        family(
            "plugin.codex", "OpenAI Codex CLI", ExtensionCategory.PLUGINS,
            "OpenAI coding agent through the ACP community adapter for the original CLI.",
            "brand:codex", "OpenAI",
            listOf(ExtensionVersion("2.1.0", channel = "stable", recommended = true, installKind = ExtensionInstallKind.REVIEWED_RECIPE, provides = setOf("codex-acp", "codex"), requires = setOf("node", "npm"), note = "Installs the Codex ACP adapter and the original @openai/codex CLI in local Ubuntu. Installation includes official Node.js 22 LTS and npm when needed.")),
            "openai", "ai", "agent", "codex", "@openai/codex",
        ),
        family(
            "plugin.claude-code", "Claude Code", ExtensionCategory.PLUGINS,
            "Anthropic coding agent through ACP.",
            "brand:anthropic", "Anthropic",
            listOf(ExtensionVersion("0.84.0", channel = "stable", recommended = true, installKind = ExtensionInstallKind.REVIEWED_RECIPE, provides = setOf("claude-agent-acp", "claude"), requires = setOf("node", "npm"), note = "Installs Claude Agent SDK and ACP adapter; claude launches the bundled original CLI. Installation includes official Node.js 22 LTS and npm when needed.")),
            "claude", "ai", "agent", "anthropic", "@anthropic-ai/claude-code",
        ),
        family(
            "plugin.gemini-cli", "Gemini CLI", ExtensionCategory.PLUGINS,
            "Google coding agent integrated through Gemini CLI ACP inside the active Droide workspace.",
            "brand:gemini", "Google",
            listOf(ExtensionVersion("0.62.0", channel = "stable", recommended = true, installKind = ExtensionInstallKind.REVIEWED_RECIPE, provides = setOf("gemini"), requires = setOf("node", "npm"), note = "Reviewed npm recipe installs @google/gemini-cli 0.62.0. Installation includes official Node.js 22 LTS and npm when needed.")),
            "gemini", "ai", "agent", "mcp", "@google/gemini-cli",
        ),
        family(
            "plugin.github-copilot-cli", "GitHub Copilot CLI", ExtensionCategory.PLUGINS,
            "GitHub coding agent through Copilot CLI ACP.",
            "brand:github-copilot", "GitHub",
            listOf(ExtensionVersion("1.0.90", channel = "stable", recommended = true, installKind = ExtensionInstallKind.REVIEWED_RECIPE, provides = setOf("copilot"), requires = setOf("node", "npm"), note = "Reviewed npm recipe installs @github/copilot 1.0.90. Installation includes official Node.js 22 LTS and npm when needed.")),
            "github", "copilot", "ai", "agent", "mcp", "@github/copilot",
        ),
    )

    private fun pluginFamilies(): List<ExtensionFamily> = listOf(
        family("plugin.git-integration", "Git Integration", ExtensionCategory.PLUGINS, "Built-in Git source control.", "brand:git", "Droide", listOf(ExtensionVersion("Built-in", channel = "bundled", recommended = true, installKind = ExtensionInstallKind.BUILT_IN, scope = ExecutionScope.EDITOR, provides = setOf("plugin:git"))), "git", "scm"),
        family("plugin.ai-agent", "Droide AI Agent", ExtensionCategory.PLUGINS, "Built-in Git tools for Agent.", "brand:droide-agent", "Droide", listOf(ExtensionVersion("Built-in", channel = "bundled", recommended = true, installKind = ExtensionInstallKind.BUILT_IN, scope = ExecutionScope.EDITOR, provides = setOf("plugin:agent"))), "ai", "agent"),
        family("plugin.android-workstation", "Android Workstation Integration", ExtensionCategory.PLUGINS, "Bundled integration for Device Bridge, Android builds, install/run and logcat.", "brand:android", "Droide", listOf(ExtensionVersion("Built-in", channel = "bundled", recommended = true, installKind = ExtensionInstallKind.BUILT_IN, scope = ExecutionScope.EDITOR, provides = setOf("plugin:android-workstation"))), "android", "device", "adb"),
    )

    private fun languageServerFamilies(): List<ExtensionFamily> = LanguageServerRegistry.builtIns.map { spec ->
        val command = spec.commands.firstOrNull()?.firstOrNull().orEmpty()
        val language = spec.languageIds.sorted().firstOrNull()
        family(
            id = when (spec.id) {
                LspGraphqlProfile.ID -> LocalUbuntuGraphqlPolicy.FAMILY
                LspTomlProfile.ID -> LocalUbuntuTomlPolicy.FAMILY
                LspVueProfile.ID -> LocalUbuntuVuePolicy.FAMILY
                else -> "lsp.${spec.id}"
            },
            name = if (spec.id == "goose") "Goose" else if (spec.id == "mdx-language-server") "MDX" else if (LspSolidityProfile.matches(spec)) "Solidity · Nomic Foundation" else if (LspScalaProfile.matches(spec)) "Scala · Metals" else if (LspAsmProfile.matches(spec)) "Assembly · asm-lsp" else if (LspElixirProfile.matches(spec)) "Elixir · ElixirLS" else if (LspHaskellProfile.matches(spec)) "Haskell · HLS" else if (LspRProfile.matches(spec)) "R · languageserver" else if (spec.id == "elp") "Erlang · ELP" else if (spec.id == "graphql-local-schema") "GraphQL local-schema intelligence" else if (spec.id == "taplo-toml-wasm") "TOML · Taplo WASM" else if (spec.id == "csharp-ls") "C# native project analysis" else if (spec.id == "vue-language-server") "Vue paired analysis" else if (spec.id == "nimlangserver") "Nim fixed-toolchain analysis" else if (spec.id == "powershell-editor-services") "PowerShell Editor Services" else if (spec.id == LspSqruffProfile.ID) "SQL · sqruff" else if (spec.id == LspPrismaProfile.ID) "Prisma legacy schemas" else if (spec.id == LspSvelteProfile.ID) "Svelte" else if (spec.id == "cmake-language-server") "CMake" else spec.id,
            category = ExtensionCategory.LANGUAGE_SERVERS,
            description = if (spec.id == "goose") "Goose language features through the separately installed aardappel/goose compiler." else if (spec.id == "mdx-language-server") "MDX diagnostics, completion and navigation through the official MDX language server." else if (LspSolidityProfile.matches(spec)) LspSolidityProfile.FEATURE_NOTE else if (LspScalaProfile.matches(spec)) LspScalaProfile.FEATURE_NOTE else if (LspAsmProfile.matches(spec)) LspAsmProfile.FEATURE_NOTE else if (LspElixirProfile.matches(spec)) LspElixirProfile.FEATURE_NOTE else if (LspHaskellProfile.matches(spec)) LspHaskellProfile.FEATURE_NOTE else if (LspRProfile.matches(spec)) LspRProfile.FEATURE_NOTE else if (spec.id == "elp") "Original ELP 2026-10-14 for lowercase Erlang source/header editing. Selected compatible OTP/escript and optional Rebar remain provenance-unverified prerequisites. Explicit health approval and separate project-code permission; native transport readiness is not completed analysis."
                else if (spec.id == "graphql-local-schema") "Exact graphql-language-service 5.7.0 / GraphQL 16.13.2 original public libraries and independent private Node 22.23.3. One approved inert .graphqlrc.json, bounded local SDL/fragments and dirty source snapshots. Completion, hover, source-owned validation and named-node outline; no remote schemas, project code, definitions, formatting, rename or query execution. Native/device acceptance remains separate."
                else if (spec.id == "csharp-ls") "Exact csharp-ls 0.28.0 with private SDK 10.0.402 and independent Node helper. Save validates metadata; Enable authorizes native MSBuild targets and generators. Original/native/device acceptance remains separate."
                else if (spec.id == "taplo-toml-wasm") "Exact original Taplo 0.8.0 library with private Node 22.23.3. Local schema-backed completion/hover, formatting, symbols, same-document rename and limited native tokens. Cursor/Unicode and schema restrictions apply; diagnostics are asynchronous advisory. Remote fetch is blocked. Original/device acceptance is separate."
                else if (spec.id == "vue-language-server") "Exact original Vue 3.3.13 / TS-LSP 6.0.2 / TypeScript 6.0.3 paired analysis with independent private Node 22.23.3; configuration/plugin/network execution requires trusted-project consent. Native/device acceptance is separate."
                else if (spec.id == "nimlangserver") "Exact Nim 2.2.12 / nimlangserver 1.16.0 analysis with private Node helper; Nimble compiler selection is disabled. Project metadata may execute code after informed consent; not a sandbox."
                else if (spec.id == "powershell-editor-services") "PowerShell scripts, modules and data through exact managed PSES 4.7.0 / PowerShell 7.6.7. Profiles and live module/completer code require consent; no DAP, REPL or new Run actions."
                else if (spec.id == LspSqruffProfile.ID) "Native SQL lint warnings, full semantic tokens and guarded full formatting under project-config consent. No completion, navigation or database UI/execution is added; unversioned diagnostics may remain pending."
                else if (spec.id == LspPrismaProfile.ID) "Legacy Prisma schema analysis with executable-project-config consent. Next/ORM 8 is unavailable; Prisma 6 and mixed-generation disk schemas remain unverified."
                else if (spec.id == LspSvelteProfile.ID) "Native Svelte 0.18.5 analysis with typed managed identity, explicit project compiler/config/plugin consent, captured existing TS/JS companions and bounded disk observation. Native/device semantics and reverse dirty-Svelte-to-TypeScript integration remain unverified."
                else if (spec.id == "cmake-language-server") "Exact managed CMake completion, documentation hover and module suggestions; selected configured-build targets/cache require informed execution consent. One server with CPython/CMake prerequisites. Formatting, diagnostics and navigation are unavailable."
                else "Language server support for ${spec.languageIds.sorted().joinToString()}.",
            iconKey = language?.let { "language:$it" } ?: "category:lsp",
            publisher = "Upstream / user-managed",
            versions = listOf(
                externalVersion(*(if (command.isBlank()) emptyArray() else arrayOf(command)),
                    note = if (spec.id == "goose") GooseLanguage.INSTALL_NOTE else if (spec.id == "mdx-language-server") "Install on demand in Extensions. Requires Node.js 22 or newer; opening an MDX file does not install packages." else if (LspSolidityProfile.matches(spec)) LspSolidityProfile.INSTALL_NOTE else if (LspScalaProfile.matches(spec)) LspScalaProfile.INSTALL_NOTE else if (LspAsmProfile.matches(spec)) LspAsmProfile.INSTALL_NOTE else if (LspElixirProfile.matches(spec)) LspElixirProfile.INSTALL_NOTE else if (LspHaskellProfile.matches(spec)) LspHaskellProfile.INSTALL_NOTE else if (LspRProfile.matches(spec)) LspRProfile.INSTALL_NOTE else if (spec.id == "graphql-local-schema") "The managed command requires exact receipts, private runtime, typed owner and explicit local-source consent. Same-name/custom commands do not inherit managed authority; original/native/device acceptance is separate."
                    else if (spec.id == "powershell-editor-services") "Detects user-managed `$command` only. A name or reported version does not establish managed receipts, exact runtime, private pipe/state or deny-safe prompting. Custom declarations do not inherit the reviewed managed integration."
                    else if (spec.id == LspSqruffProfile.ID) "Detects user-managed `$command` in local Ubuntu. Executable/build provenance remains unverified; command discovery does not establish reviewed 0.41.0 managed identity or grant LF/scalar/formatting repairs. Native configuration and outside-root reads require informed project consent."
                    else if (spec.id == LspPrismaProfile.ID) "Detects user-managed `$command` in local Ubuntu. Executable/package/runtime/prelude provenance remains unverified; command discovery does not establish the managed 31.13.0 pin, debug redirection or zero-document health guarantees."
                    else if (spec.id == LspSvelteProfile.ID) "Detects user-managed `$command` in local Ubuntu. A same-name command does not establish the exact managed package/runtime/launcher or enable the specialized companion/observer contract. Generic custom declarations retain separate consent and generic behavior."
                    else if (spec.id == "cmake-language-server") "Same-name commands are provenance-unverified and do not receive managed UTF-32 repair, private state or build ownership. Use the exact reviewed five-wheel integration and explicitly enable analysis for the selected source/build."
                    else if (command.isBlank()) "No command candidate is registered."
                    else "Detects user-managed `$command` in local Ubuntu; install a managed language server to enable verified editor integration.",
                    requires = if (spec.id == LspSvelteProfile.ID || spec.id == "mdx-language-server") setOf("node", "npm") else emptySet()),
            ),
            keywords = arrayOf(spec.id, *spec.languageIds.toTypedArray(), "lsp", "language-server"),
        )
    }

    private fun formatterLinterFamilies(): List<ExtensionFamily> = listOf(
        externalFamily("quality.ruff", "Ruff", ExtensionCategory.FORMATTERS_LINTERS, "Python linter and formatter.", "language:python", "Astral", setOf("ruff"), keywords = arrayOf("python", "lint", "format")),
        externalFamily("quality.black", "Black", ExtensionCategory.FORMATTERS_LINTERS, "Python code formatter.", "language:python", "Python", setOf("black")),
        externalFamily("quality.eslint", "ESLint", ExtensionCategory.FORMATTERS_LINTERS, "JavaScript/TypeScript linting.", "brand:eslint", "OpenJS Foundation", setOf("eslint"), requires = setOf("node")),
        externalFamily("quality.prettier", "Prettier", ExtensionCategory.FORMATTERS_LINTERS, "Opinionated formatter for web and structured-text ecosystems.", "language:javascript", "Prettier", setOf("prettier"), requires = setOf("node")),
        externalFamily("quality.ktlint", "ktlint", ExtensionCategory.FORMATTERS_LINTERS, "Kotlin linter and formatter.", "language:kotlin", "Pinterest", setOf("ktlint"), requires = setOf("java")),
        externalFamily("quality.rustfmt", "rustfmt", ExtensionCategory.FORMATTERS_LINTERS, "Official Rust formatter.", "language:rust", "Rust Project", setOf("rustfmt")),
        externalFamily("quality.clippy", "Clippy", ExtensionCategory.FORMATTERS_LINTERS, "Rust lint collection.", "language:rust", "Rust Project", setOf("cargo-clippy", "clippy-driver")),
        externalFamily("quality.gofmt", "gofmt", ExtensionCategory.FORMATTERS_LINTERS, "Official Go formatter.", "language:go", "Go Project", setOf("gofmt")),
        externalFamily("quality.golangci-lint", "golangci-lint", ExtensionCategory.FORMATTERS_LINTERS, "Go lint runner and aggregator.", "language:go", "golangci-lint", setOf("golangci-lint")),
        externalFamily("quality.clang-format", "clang-format", ExtensionCategory.FORMATTERS_LINTERS, "C/C++/Objective-C formatter.", "language:cpp", "LLVM Project", setOf("clang-format")),
        externalFamily("quality.clang-tidy", "clang-tidy", ExtensionCategory.FORMATTERS_LINTERS, "C/C++ static analysis and linting.", "language:cpp", "LLVM Project", setOf("clang-tidy")),
        externalFamily("quality.php-cs-fixer", "PHP CS Fixer", ExtensionCategory.FORMATTERS_LINTERS, "PHP coding-standard fixer.", "language:php", "PHP CS Fixer", setOf("php-cs-fixer"), requires = setOf("php")),
        externalFamily("quality.phpstan", "PHPStan", ExtensionCategory.FORMATTERS_LINTERS, "PHP static analysis.", "language:php", "PHPStan", setOf("phpstan"), requires = setOf("php")),
        externalFamily("quality.rubocop", "RuboCop", ExtensionCategory.FORMATTERS_LINTERS, "Ruby formatter and static code analyzer.", "language:ruby", "RuboCop", setOf("rubocop"), requires = setOf("ruby")),
        externalFamily("quality.swiftformat", "SwiftFormat", ExtensionCategory.FORMATTERS_LINTERS, "Swift source formatter.", "language:swift", "SwiftFormat", setOf("swiftformat")),
        externalFamily("quality.swiftlint", "SwiftLint", ExtensionCategory.FORMATTERS_LINTERS, "Swift style and convention linter.", "language:swift", "Realm", setOf("swiftlint")),
        externalFamily("quality.shfmt", "shfmt", ExtensionCategory.FORMATTERS_LINTERS, "Shell script formatter.", "language:shell", "mvdan", setOf("shfmt")),
        externalFamily("quality.shellcheck", "ShellCheck", ExtensionCategory.FORMATTERS_LINTERS, "Static analysis for shell scripts.", "language:shell", "ShellCheck", setOf("shellcheck")),
        externalFamily("quality.stylua", "StyLua", ExtensionCategory.FORMATTERS_LINTERS, "Lua formatter.", "language:lua", "StyLua", setOf("stylua")),
        externalFamily("quality.yamllint", "yamllint", ExtensionCategory.FORMATTERS_LINTERS, "YAML linter.", "language:yaml", "yamllint", setOf("yamllint")),
        externalFamily("quality.markdownlint", "markdownlint", ExtensionCategory.FORMATTERS_LINTERS, "Markdown style checker.", "language:markdown", "markdownlint", setOf("markdownlint")),
        externalFamily("quality.tflint", "TFLint", ExtensionCategory.FORMATTERS_LINTERS, "Terraform linter.", "language:terraform", "Terraform Linters", setOf("tflint")),
        externalFamily("quality.sqlfluff", "SQLFluff", ExtensionCategory.FORMATTERS_LINTERS, "SQL linter and formatter.", "language:sql", "SQLFluff", setOf("sqlfluff")),
        externalFamily("quality.hadolint", "Hadolint", ExtensionCategory.FORMATTERS_LINTERS, "Dockerfile linter.", "language:dockerfile", "Hadolint", setOf("hadolint")),
        externalFamily("quality.protolint", "protolint", ExtensionCategory.FORMATTERS_LINTERS, "Protocol Buffers linter.", "language:proto", "protolint", setOf("protolint")),
    )

    private fun debuggerFamilies(): List<ExtensionFamily> = listOf(
        family("debug.dap-host", "Debug Adapter Protocol Host", ExtensionCategory.DEBUGGERS, "DAP debugging for breakpoints, stack, variables and stepping.", "category:debug", "Droide", listOf(ExtensionVersion("Built-in", channel = "bundled", recommended = true, installKind = ExtensionInstallKind.BUILT_IN, scope = ExecutionScope.EDITOR, provides = setOf("debug:dap"))), "dap", "debug"),
        externalFamily("debug.python-debugpy", "Python debugpy", ExtensionCategory.DEBUGGERS, "Python Debug Adapter Protocol implementation.", "language:python", "Microsoft / Python", setOf("debugpy"), requires = setOf("python3"), keywords = arrayOf("dap", "debugpy")),
        externalFamily("debug.js-debug", "JavaScript / TypeScript Debugger", ExtensionCategory.DEBUGGERS, "Official VS Code JavaScript DAP adapter with owned Node.js prerequisite. Install explicitly; dependencies remain outside source.", "language:javascript", "Microsoft", setOf("js-debug-adapter"), requires = setOf("node"), keywords = arrayOf("dap", "node", "javascript", "typescript", "js-debug")),
        externalFamily("debug.codelldb", "CodeLLDB", ExtensionCategory.DEBUGGERS, "LLDB-based DAP adapter for native C/C++/Rust debugging.", "language:cpp", "Vadim Chugunov", setOf("codelldb"), keywords = arrayOf("lldb", "rust", "cpp")),
        externalFamily("debug.lldb", "LLVM LLDB / LLDB DAP", ExtensionCategory.DEBUGGERS, "Ubuntu ARM64 LLDB and its stdio DAP adapter. Native debugging also requires verified host process-control/ptrace support.", "language:cpp", "LLVM Project", setOf("lldb", "lldb-dap"), keywords = arrayOf("lldb", "dap", "rust", "cpp", "c")),
        externalFamily("debug.delve", "Delve DAP", ExtensionCategory.DEBUGGERS, "Go debugger with DAP support.", "language:go", "Go Delve", setOf("dlv"), keywords = arrayOf("go", "dap")),
        externalFamily("debug.netcoredbg", "netcoredbg", ExtensionCategory.DEBUGGERS, ".NET Core debugger with DAP support.", "language:csharp", "Samsung", setOf("netcoredbg"), keywords = arrayOf("dotnet", "csharp", "dap")),
        family("debug.android-jdwp", "Android JDWP Transport & Attach", ExtensionCategory.DEBUGGERS, "Android JDWP discovery and attach support.", "brand:android", "Droide", listOf(
            ExtensionVersion("Built-in transport", channel = "bundled", recommended = true, installKind = ExtensionInstallKind.BUILT_IN, scope = ExecutionScope.LOCAL_LINUX_ARM64, provides = setOf("android-jdwp-transport", "android-jdwp-process-discovery"), note = "Breakpoint semantics require a compatible DAP/JDI adapter."),
            ExtensionVersion("kotlin-debug-adapter 0.4.4", channel = "catalog-only candidate", installKind = ExtensionInstallKind.CATALOG_ONLY, scope = ExecutionScope.LOCAL_LINUX_ARM64, provides = setOf("kotlin-debug-adapter"), requires = setOf("jdk"), note = "Kotlin/JVM debugger adapter. Not available for install yet."),
        ), "jdwp", "android", "kotlin"),
    )

    private fun testingFamilies(): List<ExtensionFamily> = listOf(
        externalFamily("test.pytest", "pytest", ExtensionCategory.TESTING, "Python test runner and plugin ecosystem.", "brand:pytest", "pytest", setOf("pytest"), requires = setOf("python3")),
        externalFamily("test.jest", "Jest", ExtensionCategory.TESTING, "JavaScript test runner.", "language:javascript", "OpenJS ecosystem", setOf("jest"), requires = setOf("node")),
        externalFamily("test.vitest", "Vitest", ExtensionCategory.TESTING, "Vite-native JavaScript/TypeScript test runner.", "brand:vitest", "Vitest", setOf("vitest"), requires = setOf("node")),
        externalFamily("test.phpunit", "PHPUnit", ExtensionCategory.TESTING, "PHP unit-testing framework and runner.", "language:php", "PHPUnit", setOf("phpunit"), requires = setOf("php")),
        externalFamily("test.rspec", "RSpec", ExtensionCategory.TESTING, "Ruby behavior-driven test runner.", "language:ruby", "RSpec", setOf("rspec"), requires = setOf("ruby")),
        externalFamily("test.cargo", "cargo test", ExtensionCategory.TESTING, "Rust test runner integrated with Cargo.", "language:rust", "Rust Project", setOf("cargo"), requires = setOf("rustc")),
        externalFamily("test.go", "go test", ExtensionCategory.TESTING, "Built-in Go test runner.", "language:go", "Go Project", setOf("go")),
        externalFamily("test.gradle", "Gradle Test", ExtensionCategory.TESTING, "JVM/Android test tasks through Gradle.", "brand:gradle", "Gradle", setOf("gradle"), requires = setOf("java")),
        externalFamily("test.dart", "dart test", ExtensionCategory.TESTING, "Dart package test runner.", "language:dart", "Google", setOf("dart")),
        externalFamily("test.exunit", "ExUnit", ExtensionCategory.TESTING, "Elixir unit test framework through Mix.", "language:elixir", "Elixir", setOf("mix")),
    )

    private fun frameworkFamilies(): List<ExtensionFamily> = listOf(
        family("meta.android-development", "Android Development", ExtensionCategory.FRAMEWORKS, "Android build, test, run and logcat integration.", "brand:android", "Droide", listOf(ExtensionVersion("Project-driven", channel = "workstation", recommended = true, installKind = ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN, scope = ExecutionScope.LOCAL_LINUX_ARM64, provides = setOf("android-build", "android-run", "android-logcat", "jdk", "android-sdk"), note = "Uses the project Gradle Wrapper and installed Android toolchain.")), "android", "gradle"),
        family("framework.react", "React", ExtensionCategory.FRAMEWORKS, "React project recognition and JavaScript/TypeScript ecosystem metadata.", "language:react", "Meta", listOf(ExtensionVersion("Project dependency", channel = "workspace", scope = ExecutionScope.EDITOR, provides = setOf("framework:react"))), "jsx", "tsx"),
        family("framework.vue", "Vue", ExtensionCategory.FRAMEWORKS, "Vue single-file component project recognition.", "language:vue", "Vue.js", listOf(ExtensionVersion("Project dependency", channel = "workspace", scope = ExecutionScope.EDITOR, provides = setOf("framework:vue"))), "vuejs"),
        family("framework.svelte", "Svelte", ExtensionCategory.FRAMEWORKS, "Svelte component project recognition.", "language:svelte", "Svelte", listOf(ExtensionVersion("Project dependency", channel = "workspace", scope = ExecutionScope.EDITOR, provides = setOf("framework:svelte"))), "sveltekit"),
        family("framework.flutter", "Flutter", ExtensionCategory.FRAMEWORKS, "Flutter workspace recognition; Flutter SDK remains a separate external toolchain.", "brand:flutter", "Google", listOf(externalVersion("flutter", requires = setOf("dart"))), "flutter", "dart"),
        family("framework.django", "Django", ExtensionCategory.FRAMEWORKS, "Django workspace recognition for Python projects.", "brand:django", "Django Software Foundation", listOf(ExtensionVersion("Project dependency", channel = "workspace", scope = ExecutionScope.EDITOR, provides = setOf("framework:django"))), "python", "web"),
        family("framework.fastapi", "FastAPI", ExtensionCategory.FRAMEWORKS, "FastAPI workspace recognition for Python services.", "brand:fastapi", "FastAPI", listOf(ExtensionVersion("Project dependency", channel = "workspace", scope = ExecutionScope.EDITOR, provides = setOf("framework:fastapi"))), "python", "api"),
        family("framework.spring", "Spring Boot", ExtensionCategory.FRAMEWORKS, "Spring Boot project recognition for JVM workspaces.", "brand:springboot", "VMware / Broadcom", listOf(ExtensionVersion("Project dependency", channel = "workspace", scope = ExecutionScope.EDITOR, provides = setOf("framework:spring"))), "java", "kotlin", "spring"),
        family("framework.ktor", "Ktor", ExtensionCategory.FRAMEWORKS, "Ktor project recognition for Kotlin server/client workspaces.", "brand:ktor", "JetBrains", listOf(ExtensionVersion("Project dependency", channel = "workspace", scope = ExecutionScope.EDITOR, provides = setOf("framework:ktor"))), "kotlin", "server"),
        family("framework.rails", "Ruby on Rails", ExtensionCategory.FRAMEWORKS, "Rails project recognition for Ruby workspaces.", "language:ruby", "Rails Foundation", listOf(ExtensionVersion("Project dependency", channel = "workspace", scope = ExecutionScope.EDITOR, provides = setOf("framework:rails"))), "ruby", "rails"),
        family("framework.laravel", "Laravel", ExtensionCategory.FRAMEWORKS, "Laravel project recognition for PHP workspaces.", "brand:laravel", "Laravel", listOf(ExtensionVersion("Project dependency", channel = "workspace", scope = ExecutionScope.EDITOR, provides = setOf("framework:laravel"))), "php", "laravel"),
    )

    private fun themeFamilies(): List<ExtensionFamily> = listOf(
        "droide-dark" to "Droide Dark",
        "droide-light" to "Droide Light",
        "matrix" to "Matrix",
        "tokyonight" to "Tokyo Night",
    ).map { (id, name) ->
        family(
            id = "theme.$id",
            name = name,
            category = ExtensionCategory.THEMES,
            description = "Bundled Droide color theme. Custom bounded JSON themes remain supported per workspace.",
            iconKey = "category:theme",
            publisher = "Droide",
            versions = listOf(ExtensionVersion("Built-in", channel = "bundled", installKind = ExtensionInstallKind.BUILT_IN, scope = ExecutionScope.EDITOR, provides = setOf("theme:$id"))),
            keywords = arrayOf("theme", id),
        )
    }

    private val nonLanguageFamilies: List<ExtensionFamily> = buildList {
        add(androidSdkFamily())
        add(jdkFamily())
        addAll(runtimeAndToolchainFamilies())
        addAll(packageManagerFamilies())
        addAll(buildToolFamilies())
        addAll(cliToolFamilies())
        add(externalFamily("cli.mise", "mise Tool Manager", ExtensionCategory.PACKAGE_MANAGERS,
            "Install and update user toolchains and publisher tools with freely selected versions.", "category:cli", "mise",
            setOf("mise"), keywords = arrayOf("versions", "update", "toolchains")))
        addAll(aiAgentFamilies())
        addAll(pluginFamilies())
        addAll(languageServerFamilies())
        addAll(formatterLinterFamilies())
        addAll(debuggerFamilies())
        addAll(testingFamilies())
        addAll(frameworkFamilies())
        addAll(themeFamilies())
    }

    // Runtime registration/removal changes only the small language projection.
    val families: List<ExtensionFamily> get() = languageFamilies() + nonLanguageFamilies

    fun byId(id: String): ExtensionFamily? = families.firstOrNull { it.id == id }
}

enum class WorkspaceKind(val label: String) {
    ANDROID_GRADLE("Android Gradle"),
    GRADLE_JVM("Gradle JVM"),
    MAVEN_JVM("Maven JVM"),
    PYTHON("Python"),
    NODE("Node.js"),
    RUST("Rust"),
    GO("Go"),
    CMAKE("CMake"),
    GENERIC("Generic"),
}

data class WorkspaceEnvironmentSnapshot(
    val kind: WorkspaceKind,
    val activeLanguageId: String?,
    val requiredCapabilities: Set<String>,
    val markerFiles: List<String>,
)

 
object WorkspaceEnvironmentDetector {
    fun detect(root: File, activeFile: String? = null): WorkspaceEnvironmentSnapshot {
        val markers = mutableListOf<String>()
        fun has(name: String): Boolean = File(root, name).exists().also { if (it) markers += name }

        val hasGradle = has("settings.gradle") || has("settings.gradle.kts") || has("build.gradle") || has("build.gradle.kts")
        val android = sequenceOf(
            File(root, "app/src/main/AndroidManifest.xml"),
            File(root, "src/main/AndroidManifest.xml"),
            File(root, "AndroidManifest.xml"),
        ).any(File::isFile)
        val kind = when {
            hasGradle && android -> WorkspaceKind.ANDROID_GRADLE
            hasGradle -> WorkspaceKind.GRADLE_JVM
            has("pom.xml") -> WorkspaceKind.MAVEN_JVM
            has("pyproject.toml") || has("requirements.txt") || has("setup.py") -> WorkspaceKind.PYTHON
            has("package.json") -> WorkspaceKind.NODE
            has("Cargo.toml") -> WorkspaceKind.RUST
            has("go.mod") -> WorkspaceKind.GO
            has("CMakeLists.txt") -> WorkspaceKind.CMAKE
            else -> WorkspaceKind.GENERIC
        }
        val required = when (kind) {
            WorkspaceKind.ANDROID_GRADLE -> setOf("android-build", "java")
            WorkspaceKind.GRADLE_JVM, WorkspaceKind.MAVEN_JVM -> setOf("java")
            WorkspaceKind.PYTHON -> setOf("python3")
            WorkspaceKind.NODE -> setOf("node")
            WorkspaceKind.RUST -> setOf("cargo", "rustc")
            WorkspaceKind.GO -> setOf("go")
            WorkspaceKind.CMAKE -> setOf("cmake")
            WorkspaceKind.GENERIC -> emptySet()
        }
        return WorkspaceEnvironmentSnapshot(
            kind = kind,
            activeLanguageId = activeFile?.takeIf { it.isNotBlank() }?.let { LanguageRegistry.forFile(it)?.id },
            requiredCapabilities = required,
            markerFiles = markers.distinct(),
        )
    }
}
