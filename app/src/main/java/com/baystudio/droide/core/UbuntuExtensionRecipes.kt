package com.baystudio.droide.core

data class UbuntuAptRecipe(
    val familyId: String,
    val packages: List<String>,
    val commands: Map<String, String>,
    val healthArgs: Map<String, List<String>> = emptyMap(),
    val commandArgs: Map<String, List<String>> = emptyMap(),
) {
    val version: String get() = UbuntuAptCatalog.VERSION
    fun validate() {
        require(familyId.matches(Regex("[a-z0-9._-]{1,120}")))
        require(packages.size in 1..48 && packages.distinct().size == packages.size)
        require(packages.all { it.matches(Regex("[a-z0-9][a-z0-9+.-]{0,119}")) })
        require(commands.isNotEmpty() && commands.size <= 16)
        require(commands.all { (name, path) -> name.matches(Regex("[A-Za-z0-9._+-]{1,80}")) &&
            (path.matches(Regex("/usr/bin/[A-Za-z0-9._+-]{1,100}")) ||
                (familyId == "toolchain.jdk" && name in setOf("java", "javac", "jar") &&
                    path == "/usr/lib/jvm/java-17-openjdk-arm64/bin/$name" && "openjdk-17-jdk-headless" in packages)) })
        require(healthArgs.keys.all { it in commands } && healthArgs.values.all { args ->
            args.size <= 8 && args.all { it.length <= 120 && it.none { c -> c.code < 32 || c.code == 127 } }
        })
        require(commandArgs.keys.all { it in commands } && commandArgs.values.all { args ->
            args.size <= 8 && args.all { it.length <= 120 && it.none { c -> c.code < 32 || c.code == 127 } }
        })
    }
    fun probeArgs(name: String): List<String> = healthArgs[name] ?: listOf("--version")
    fun launchArgs(name: String): List<String> = commandArgs[name].orEmpty()
}

object UbuntuAptCatalog {
    const val VERSION = "ubuntu-24.04"
    private fun r(id: String, packages: String, vararg commands: String, args: List<String> = listOf("--version")): UbuntuAptRecipe =
        UbuntuAptRecipe(id, packages.split(' '), commands.associateWith { "/usr/bin/$it" }, commands.associateWith { args })
    val entries = listOf(
        r("runtime.python", "python3 python-is-python3 python3-venv", "python3", "python"),
        r("runtime.php", "php-cli", "php"),
        r("runtime.ruby", "ruby", "ruby"),
        r("runtime.lua", "lua5.4", "lua", args = listOf("-v")),
        r("runtime.r", "r-base-core", "R", "Rscript"),
        r("runtime.perl", "perl", "perl"),
        // A compiler version probe is not a native-build closure. APT is deliberately
        // run without recommendations; Cargo's system linker and crate build scripts
        // therefore need their C toolchain explicitly, as do the C/C++ recipes.
        r("toolchain.rust", "rustc cargo rustfmt build-essential pkg-config ca-certificates", "rustc", "cargo", "rustfmt"),
        r("toolchain.jdk", "openjdk-17-jdk-headless", "java", "javac", "jar", args = listOf("-version"))
            .copy(commands = setOf("java", "javac", "jar").associateWith { "/usr/lib/jvm/java-17-openjdk-arm64/bin/$it" },
                healthArgs = mapOf("java" to listOf("-version"), "javac" to listOf("-version"), "jar" to listOf("--version"))),
        r("toolchain.kotlin", "kotlin openjdk-17-jdk-headless", "kotlinc", args = listOf("-version")),
        r("toolchain.go", "golang-go", "go", "gofmt", args = listOf("version"))
            .copy(healthArgs = mapOf("go" to listOf("version"), "gofmt" to listOf("-h"))),
        r("toolchain.llvm", "clang lld build-essential libc6-dev pkg-config", "clang", "clang++", "ld.lld"),
        r("toolchain.gcc", "build-essential libc6-dev pkg-config", "gcc", "g++", "cc", "c++"),
        r("toolchain.dotnet", "dotnet-sdk-8.0", "dotnet"),
        r("toolchain.haskell", "ghc cabal-install", "ghc", "runhaskell", "cabal"),
        r("toolchain.scala", "scala openjdk-17-jdk-headless", "scala", "scalac", args = listOf("-version")),
        r("toolchain.erlang", "erlang-base erlang-dev", "erl", args = listOf("-version"))
            .copy(healthArgs = mapOf("erl" to listOf("-noshell", "-eval", "io:format(\"~s~n\", [erlang:system_info(otp_release)]), halt()."))),
        r("toolchain.elixir", "elixir", "elixir", "elixirc", "mix"),
        r("toolchain.clojure", "clojure", "clojure", args = listOf("-e", "(println (clojure-version))")),
        // The package only recommends a C compiler; --no-install-recommends needs an explicit build closure.
        // Fixed analysis uses runtime.nim-lsp; this terminal compiler recipe remains independent.
        r("toolchain.nim", "nim gcc libc6-dev", "nim", "nimble"),
        r("package.pip", "python3-pip python3-venv", "pip3")
            .copy(commands = mapOf("pip" to "/usr/bin/pip3", "pip3" to "/usr/bin/pip3")),
        r("package.composer", "composer", "composer"),
        r("package.bundler", "ruby-bundler", "bundle"),
        r("package.cargo", "cargo build-essential pkg-config ca-certificates", "cargo"),
        r("package.rubygems", "ruby", "gem"),
        r("package.nimble", "nim gcc libc6-dev", "nimble"),
        r("package.poetry", "python3-poetry", "poetry"),
        r("package.leiningen", "leiningen", "lein", args = listOf("version")),
        r("package.maven", "maven", "mvn"),
        UbuntuAptRecipe("package.nuget", listOf("dotnet-sdk-8.0"), mapOf("nuget" to "/usr/bin/dotnet"),
            commandArgs = mapOf("nuget" to listOf("nuget"))),
        r("cli.docker", "docker.io", "docker"),
        r("build.make", "make", "make"),
        r("build.cmake", "cmake", "cmake"),
        r("build.ninja", "ninja-build", "ninja"),
        r("build.maven", "maven", "mvn"),
        r("build.meson", "meson", "meson"),
        r("cli.git", "git", "git"),
        r("cli.ripgrep", "ripgrep", "rg"),
        UbuntuAptRecipe("cli.fd", listOf("fd-find"), mapOf("fd" to "/usr/bin/fdfind")),
        r("cli.jq", "jq", "jq"),
        r("cli.curl", "curl", "curl"),
        r("cli.wget", "wget", "wget"),
        r("cli.ssh", "openssh-client", "ssh", args = listOf("-V")),
        r("cli.zip", "zip unzip", "zip", "unzip", args = listOf("-v")),
        r("cli.tree", "tree", "tree"),
        r("cli.tar", "tar", "tar"),
        r("cli.rsync", "rsync", "rsync"),
        r("cli.fzf", "fzf", "fzf"),
        r("cli.sqlite", "sqlite3", "sqlite3", args = listOf("-version")),
        r("cli.gh", "gh", "gh"),
        r("cli.adb", "adb", "adb", args = listOf("version")),
        r("cli.fastboot", "fastboot", "fastboot"),
        r("lsp.python-pylsp", "python3-pylsp", "pylsp"),
        r("lsp.gopls", "gopls", "gopls", args = listOf("version")),
        r("lsp.clangd", "clangd", "clangd"),
        r("quality.black", "black", "black"),
        r("quality.shellcheck", "shellcheck", "shellcheck"),
        r("quality.shfmt", "shfmt", "shfmt", args = listOf("-version")),
        r("quality.clang-format", "clang-format", "clang-format"),
        r("quality.rustfmt", "rustfmt", "rustfmt"),
        r("quality.yamllint", "yamllint", "yamllint"),
        r("quality.clang-tidy", "clang-tidy", "clang-tidy"),
        r("quality.gofmt", "golang-go", "gofmt", args = listOf("-h")),
        r("quality.clippy", "rust-clippy build-essential pkg-config", "cargo-clippy", "clippy-driver"),
        r("quality.rubocop", "rubocop", "rubocop"),
        r("quality.sqlfluff", "sqlfluff", "sqlfluff"),
        r("debug.delve", "delve", "dlv", args = listOf("version")),
        // Noble publishes lldb-dap-18 for ARM64. Installation/stdio admission is
        // distinct from permission to ptrace a debuggee in the Android/PRoot host.
        UbuntuAptRecipe("debug.lldb", listOf("lldb-18"),
            mapOf("lldb" to "/usr/bin/lldb-18", "lldb-dap" to "/usr/bin/lldb-dap-18"),
            healthArgs = mapOf("lldb" to listOf("--version"), "lldb-dap" to listOf("--help"))),
        UbuntuAptRecipe("debug.python-debugpy", listOf("python3-debugpy"),
            mapOf("debugpy" to "/usr/bin/python3"), commandArgs = mapOf("debugpy" to listOf("-m", "debugpy"))),
        r("test.pytest", "python3-pytest", "pytest"),
        r("test.phpunit", "phpunit", "phpunit"),
        r("test.rspec", "ruby-rspec", "rspec"),
        r("test.cargo", "cargo build-essential pkg-config ca-certificates", "cargo"),
        r("test.go", "golang-go", "go", args = listOf("version")),
        r("test.exunit", "elixir", "mix"),
    ).onEach { it.validate() }
    fun find(familyId: String): UbuntuAptRecipe? = entries.firstOrNull { it.familyId == familyId }
    val languageRuntime = mapOf(
        "python" to "runtime.python", "javascript" to "runtime.node", "typescript" to "toolchain.typescript",
        "react" to "runtime.node", "tsx" to "runtime.node", "jsx" to "runtime.node", "vue" to "runtime.node", "svelte" to "runtime.node",
        "go" to "toolchain.go", "rust" to "toolchain.rust", "java" to "toolchain.jdk", "kotlin" to "toolchain.kotlin",
        "goose" to "toolchain.goose",
        "c" to "toolchain.gcc", "cpp" to "toolchain.gcc", "csharp" to "toolchain.dotnet", "php" to "runtime.php",
        "ruby" to "runtime.ruby", "lua" to "runtime.lua", "r" to "runtime.r", "perl" to "runtime.perl",
        "haskell" to "toolchain.haskell", "scala" to "toolchain.scala", "erlang" to "toolchain.erlang",
        "elixir" to "toolchain.elixir", "clojure" to "toolchain.clojure", "nim" to "toolchain.nim",
        "dart" to "runtime.dart", "powershell" to "runtime.powershell", "swift" to "toolchain.swift",
        "zig" to "toolchain.zig", "solidity" to "toolchain.solidity", "sql" to "cli.sqlite",
    )
}

object UbuntuNpmCatalog {
    const val VERSION = "Latest npm"
    data class Entry(val familyId: String, val packageName: String, val command: String, val provenance: String,
        val allowLifecycleScripts: Boolean = false, val lifecycleScriptReview: String? = null,
        val reviewedVersion: String? = null, val reviewedIntegrity: String? = null,
        val minimumNodeMajor: Int = 22, val allowsPublisherUpdates: Boolean = false) {
        val offeredVersion: String get() = if (allowsPublisherUpdates) VERSION else reviewedVersion ?: VERSION
    }
    val entries = listOf(
        Entry(LocalUbuntuMusePolicy.FAMILY, LocalUbuntuMusePolicy.PACKAGE, "muse-code-acp", LocalUbuntuMusePolicy.PROVENANCE,
            reviewedVersion = LocalUbuntuMusePolicy.VERSION, reviewedIntegrity = LocalUbuntuMusePolicy.INTEGRITY, allowsPublisherUpdates = true),
        Entry("plugin.kimi-code", "@moonshot-ai/kimi-code", "kimi", "https://www.kimi.com/code/docs/en/kimi-code-cli/reference/kimi-acp.html",
            reviewedVersion = "2.5.2", reviewedIntegrity = "sha512-a7UuugqQuPBHB2ZMiu8ezAFIwJ0dn6xanuRnTqa2pXXu12ng7kvigAlXy47TxNKpnyj8vgaNStedTv4kbqSCuA==", allowsPublisherUpdates = true),
        Entry("plugin.deepseek-harness", "@deepseek-ai/dsh", "dsh", "https://github.com/deepseek-ai/deepseek-harness",
            minimumNodeMajor = 24, allowsPublisherUpdates = true),
        Entry(LocalUbuntuGraphqlPolicy.FAMILY,LocalUbuntuGraphqlPolicy.PACKAGE,LocalUbuntuGraphqlPolicy.COMMAND,LocalUbuntuGraphqlPolicy.PROVENANCE,
            reviewedVersion=LocalUbuntuGraphqlPolicy.VERSION,reviewedIntegrity=LocalUbuntuGraphqlPolicy.integrity),
        Entry(LocalUbuntuTomlPolicy.FAMILY,LocalUbuntuTomlPolicy.PACKAGE,LocalUbuntuTomlPolicy.COMMAND,LocalUbuntuTomlPolicy.PROVENANCE,
            reviewedVersion=LocalUbuntuTomlPolicy.VERSION,reviewedIntegrity=LocalUbuntuTomlPolicy.integrity),
        Entry(LocalUbuntuVuePolicy.FAMILY,LocalUbuntuVuePolicy.PACKAGE,LocalUbuntuVuePolicy.COMMAND,LocalUbuntuVuePolicy.PROVENANCE,
            reviewedVersion=LocalUbuntuVuePolicy.VERSION,reviewedIntegrity=LocalUbuntuVuePolicy.integrity),
        Entry("plugin.opencode", "opencode-ai", "opencode", "https://opencode.ai/docs/cli/", true,
            "User-requested official opencode-ai root binary selector after registry-integrity locking; transitive lifecycle scripts remain disabled. Updated package command health is checked before activation."),
        Entry("plugin.codex", "@agentclientprotocol/codex-acp", "codex-acp", "https://github.com/agentclientprotocol/codex-acp"),
        Entry("plugin.claude-code", "@agentclientprotocol/claude-agent-acp", "claude-agent-acp", "https://github.com/agentclientprotocol/claude-agent-acp"),
        Entry("plugin.gemini-cli", "@google/gemini-cli", "gemini", "https://github.com/google-gemini/gemini-cli"),
        Entry("plugin.github-copilot-cli", "@github/copilot", "copilot", "https://docs.github.com/en/copilot/reference/copilot-cli-reference/acp-server"),
        Entry("toolchain.typescript", "typescript", "tsc", "https://www.typescriptlang.org/download/"),
        Entry("package.pnpm", "pnpm", "pnpm", "https://pnpm.io/installation"),
        Entry("package.yarn", "yarn", "yarn", "https://yarnpkg.com/getting-started/install"),
        Entry("quality.prettier", "prettier", "prettier", "https://prettier.io/docs/install"),
        Entry("quality.eslint", "eslint", "eslint", "https://eslint.org/docs/latest/use/getting-started"),
        Entry("quality.markdownlint", "markdownlint-cli", "markdownlint", "https://github.com/igorshubovych/markdownlint-cli"),
        Entry("test.jest", "jest", "jest", "https://jestjs.io/docs/getting-started"),
        Entry("test.vitest", "vitest", "vitest", "https://vitest.dev/guide/"),
        Entry("lsp.nomicfoundation-solidity-language-server", "@nomicfoundation/solidity-language-server",
            "nomicfoundation-solidity-language-server", "https://github.com/NomicFoundation/hardhat-vscode",
            allowsPublisherUpdates = true),
        Entry("lsp.python-pyright", "pyright", "pyright-langserver", "https://github.com/microsoft/pyright"),
        Entry("lsp.mdx-language-server", "@mdx-js/language-server", "mdx-language-server", "https://github.com/mdx-js/mdx-analyzer",
            reviewedVersion = "0.6.4", reviewedIntegrity = "sha512-FuHTy1Uvs2/I0ULSsGK+F+gja/1jvGhx6Mtnqw93jIIPh+3oQEtX4fpCh5wpdQPK9aaef+DKwGXttOTKZV/DBw==", allowsPublisherUpdates = true),
        Entry("lsp.typescript", "typescript-language-server", "typescript-language-server", "https://github.com/typescript-language-server/typescript-language-server"),
        Entry("lsp.intelephense", "intelephense", "intelephense", "https://github.com/bmewburn/intelephense-docs"),
        Entry("lsp.bash-language-server", "bash-language-server", "bash-language-server", "https://github.com/bash-lsp/bash-language-server"),
        Entry("lsp.dockerfile-language-server", "dockerfile-language-server-nodejs", "docker-langserver", "https://github.com/rcjsuen/dockerfile-language-server-nodejs"),
        Entry("lsp.yaml-language-server", "yaml-language-server", "yaml-language-server", "https://github.com/redhat-developer/yaml-language-server"),
        Entry("lsp.vscode-json-language-server", "vscode-langservers-extracted", "vscode-json-language-server", "https://github.com/hrsh7th/vscode-langservers-extracted"),
        Entry("lsp.vscode-html-language-server", "vscode-langservers-extracted", "vscode-html-language-server", "https://github.com/hrsh7th/vscode-langservers-extracted"),
        Entry("lsp.vscode-css-language-server", "vscode-langservers-extracted", "vscode-css-language-server", "https://github.com/hrsh7th/vscode-langservers-extracted"),
        // Exact published bundle: no production dependencies or install lifecycle scripts.
        // Source commit f6e1808e441c64f47f6fb86886eb9ad5a4e74156; upstream MIT license.
        Entry("lsp.vim-language-server", "vim-language-server", "vim-language-server", "https://github.com/iamcco/vim-language-server",
            reviewedVersion = "2.3.1", reviewedIntegrity = "sha512-Y9Zy3NKzfndLUfuZRrITkpfGvpL2K7hNPV8sJ1iB6mf1YRxIX3Hs6lwZimoD5pmvamUaOQhNGYMS/Ox9itzA4Q==", allowsPublisherUpdates = true),
        Entry(LocalUbuntuSveltePolicy.FAMILY_ID, LocalUbuntuSveltePolicy.PACKAGE, LocalUbuntuSveltePolicy.COMMAND, LocalUbuntuSveltePolicy.PROVENANCE,
            reviewedVersion = LocalUbuntuSveltePolicy.VERSION, reviewedIntegrity = LocalUbuntuSveltePolicy.INTEGRITY),
        Entry(LocalUbuntuPrismaPolicy.FAMILY_ID, LocalUbuntuPrismaPolicy.PACKAGE, LocalUbuntuPrismaPolicy.COMMAND, LocalUbuntuPrismaPolicy.PROVENANCE,
            reviewedVersion = LocalUbuntuPrismaPolicy.VERSION, reviewedIntegrity = LocalUbuntuPrismaPolicy.INTEGRITY),
        Entry("lsp.perlnavigator", "perlnavigator-server", "perlnavigator", "https://github.com/bscan/PerlNavigator",
            reviewedVersion = "0.8.20", reviewedIntegrity = "sha512-whXoAEQwavV2GF/KuzzJ/yg5x9q3XUccMNrA06N0kvmi53BcySburu3Y/YEhPpNjJe0NNxpK7kOj80PE9vRu/g==", allowsPublisherUpdates = true),
    )
    fun find(familyId: String): Entry? = entries.firstOrNull { it.familyId == familyId }
    fun supports(recipe: WorkstationInstallRecipe): Boolean = find(recipe.familyId)?.let {
        recipe.packageName == it.packageName && recipe.command == it.command && recipe.provenanceUrl == it.provenance &&
            recipe.version == recipe.packageVersion && recipe.minimumNodeMajor >= it.minimumNodeMajor &&
            (it.reviewedVersion == null ||
                if (recipe.packageVersion == it.reviewedVersion) recipe.registryIntegrity == it.reviewedIntegrity
                else it.allowsPublisherUpdates) &&
            recipe.allowLifecycleScripts == it.allowLifecycleScripts &&
            (if (it.allowLifecycleScripts) !recipe.lifecycleScriptReview.isNullOrBlank() else recipe.lifecycleScriptReview == null) &&
            when (recipe.familyId) {
                "lsp.typescript", LspMdxProfile.FAMILY -> recipe.additionalDependencies.keys == setOf("typescript")
                LocalUbuntuPerlNavigatorPolicy.FAMILY_ID -> recipe.additionalDependencies ==
                    if (recipe.version == it.reviewedVersion) LocalUbuntuPerlNavigatorPolicy.dependencies else emptyMap<String, String>()
                LocalUbuntuGraphqlPolicy.FAMILY -> LocalUbuntuGraphqlPolicy.supports(recipe)
                LocalUbuntuTomlPolicy.FAMILY -> LocalUbuntuTomlPolicy.supports(recipe)
                LocalUbuntuVuePolicy.FAMILY -> LocalUbuntuVuePolicy.supports(recipe)
                LocalUbuntuSveltePolicy.FAMILY_ID -> LocalUbuntuSveltePolicy.supports(recipe)
                LocalUbuntuPrismaPolicy.FAMILY_ID -> LocalUbuntuPrismaPolicy.supports(recipe)
                else -> recipe.additionalDependencies.isEmpty()
            }
    } == true
}
