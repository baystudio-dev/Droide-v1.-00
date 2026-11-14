package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

internal const val WORKSTATION_GUEST_ADMISSION_CONTRACT_KEY = "guest.admission-contract.sha256"

object WorkstationGuestEnvironmentSpec {
    const val ID = "alpine-3.24.2-aarch64"
    const val VERSION = "Alpine 3.24"
    const val ROOT_DIR = "alpine-3.24.2"

    val rootfsArtifact = TrustedArtifactSpec(
        id = "alpine-minirootfs-3.24.2-aarch64",
        url = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz",
        sha256 = "9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773",
        fileName = "alpine-minirootfs-3.24.2-aarch64.tar.gz",
        maxBytes = 4_028_030,
        expectedBytes = 4_028_030,
    )

    const val MAIN_REPOSITORY = "https://dl-cdn.alpinelinux.org/alpine/v3.24/main"
    const val COMMUNITY_REPOSITORY = "https://dl-cdn.alpinelinux.org/alpine/v3.24/community"
}

/** Historical path/API compatibility only. Retired methods reject before any runtime work. */
@Suppress("UNUSED_PARAMETER")
class WorkstationGuestEnvironmentManager(
    context: Context,
) {
    val backendId: PackageBackendId = PackageBackendId.LOCAL_APP
    private val launcher = File(context.applicationContext.filesDir.canonicalFile,
        "guest/${WorkstationGuestEnvironmentSpec.ROOT_DIR}/launch").absolutePath

    fun launcherPath(): String = launcher

    suspend fun ensureQemuInstalled(): String = LegacyAlpineRuntimeRetirement.reject()
    suspend fun isHealthy(): Boolean = LegacyAlpineRuntimeRetirement.reject()
    suspend fun ensureInstalled(): String = LegacyAlpineRuntimeRetirement.reject()
    internal suspend fun ensureInstalledUnlocked(): String = LegacyAlpineRuntimeRetirement.reject()

    suspend fun execute(argv: List<String>, maxOutputBytes: Int = 1_500_000): ExecResult = LegacyAlpineRuntimeRetirement.reject()
    internal suspend fun executeInstalled(argv: List<String>, maxOutputBytes: Int = 1_500_000,
        timeoutMs: Long = 900_000): ExecResult = LegacyAlpineRuntimeRetirement.reject()
}

data class WorkstationGuestAdmissionProbe(
    val id: String,
    val shellCommand: String,
) {
    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Invalid guest admission probe id" }
        require(shellCommand.length in 1..1_000 && '\u0000' !in shellCommand && '\n' !in shellCommand && '\r' !in shellCommand) {
            "Invalid guest admission probe script"
        }
    }
}

data class WorkstationGuestPackageRecipe(
    val familyId: String,
    val version: String = WorkstationGuestEnvironmentSpec.VERSION,
    val packages: List<String>,
     
    val commands: Map<String, String>,
     
    val commandArgs: Map<String, List<String>> = emptyMap(),
    val healthCommand: String? = null,
    val healthArgs: List<String> = emptyList(),
     
    val additionalHealthChecks: List<ManagedPackageHealthCheck> = emptyList(),
     
    val admissionProbes: List<WorkstationGuestAdmissionProbe> = emptyList(),
    val provenanceUrl: String = "https://pkgs.alpinelinux.org/packages?branch=v3.24&arch=aarch64",
) {
    fun validate() {
        require(familyId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid guest package family" }
        require(version.matches(Regex("[A-Za-z0-9._+ -]{1,80}"))) { "Invalid guest package version" }
        require(packages.isNotEmpty() && packages.size <= 24 && packages.distinct().size == packages.size) { "Invalid guest package list" }
        require(packages.all { it.matches(Regex("[A-Za-z0-9._+:-]{1,100}")) }) { "Unsafe Alpine package name" }
        require(commands.size <= 32) { "Invalid guest command map" }
        require(commands.keys.all { it.matches(Regex("[A-Za-z0-9._+-]{1,80}")) }) { "Invalid guest command name" }
        require(commands.values.all { path ->
            path.startsWith('/') && path.length <= 240 && path.split('/').none { it == "." || it == ".." }
        }) { "Guest executables must be absolute safe paths" }
        require(commandArgs.keys.all { it in commands }) { "Guest command arguments must belong to an exported command" }
        require(commandArgs.size <= 32 && commandArgs.values.all { args ->
            args.size <= 8 && args.all { it.length <= 120 && '\u0000' !in it && '\n' !in it && '\r' !in it }
        }) { "Invalid guest command arguments" }
        require((healthCommand == null && healthArgs.isEmpty()) || healthCommand in commands) { "Guest health command must be exported, or omitted for dependency-only packages" }
        require(healthArgs.size <= 8 && healthArgs.all { it.length <= 120 && '\u0000' !in it && '\n' !in it && '\r' !in it }) { "Invalid guest health arguments" }
        require(additionalHealthChecks.size <= 16 && additionalHealthChecks.all { check ->
            check.executable in commands && check.args.size <= 8 &&
                check.args.all { it.length <= 120 && '\u0000' !in it && '\n' !in it && '\r' !in it }
        }) { "Guest behavioral health checks must target exported commands with bounded arguments" }
        require(admissionProbes.size <= 8 && admissionProbes.map { it.id }.distinct().size == admissionProbes.size) {
            "Guest admission probe ids must be unique and bounded"
        }
        admissionProbes.forEach(WorkstationGuestAdmissionProbe::validate)
        NetworkSecurity.validatePublicHttpsUrl(provenanceUrl)
    }


    fun requiredManagedHealthChecks(): List<ManagedPackageHealthCheck> = buildList {
        healthCommand?.let { add(ManagedPackageHealthCheck("bin/$it", healthArgs)) }
        additionalHealthChecks.forEach { add(ManagedPackageHealthCheck("bin/${it.executable}", it.args)) }
    }.distinct()

    fun admissionContractSha256(): String? {
        if (admissionProbes.isEmpty()) return null
        val identity = buildString {
            admissionProbes.sortedBy { it.id }.forEach { probe ->
                append(probe.id).append('\n').append(probe.shellCommand).append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun virtualPackageName(): String {
        // Package ownership identity must change when the reviewed dependency set changes.

        val identity = buildString {
            append(familyId).append('@').append(version).append('|')
            packages.sorted().forEach { append(it).append('\n') }
            commands.toSortedMap().forEach { (command, target) ->
                append(command).append('=').append(target).append('|')
                commandArgs[command].orEmpty().forEach { append(it).append('\u0001') }
                append('\n')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
        return ".droide_$digest"
    }
}

 
object WorkstationGuestPackageCatalog {
    private fun recipe(
        familyId: String,
        packages: List<String>,
        commands: Map<String, String>,
        healthCommand: String = commands.keys.first(),
        healthArgs: List<String> = listOf("--version"),
        commandArgs: Map<String, List<String>> = emptyMap(),
        additionalHealthChecks: List<ManagedPackageHealthCheck> = emptyList(),
        admissionProbes: List<WorkstationGuestAdmissionProbe> = emptyList(),
    ) = WorkstationGuestPackageRecipe(
        familyId, packages = packages, commands = commands, commandArgs = commandArgs,
        healthCommand = healthCommand, healthArgs = healthArgs, additionalHealthChecks = additionalHealthChecks,
        admissionProbes = admissionProbes,
    )

    private val rustCargoBuildRunProbe = WorkstationGuestAdmissionProbe(
        id = "rust-cargo-build-run",
        shellCommand = "set -eu; test -s /etc/ssl/certs/ca-certificates.crt; command -v cc >/dev/null; cc --version >/dev/null; d=\"${'$'}(mktemp -d /tmp/droide-rust-health.XXXXXX)\"; trap 'rm -rf \"${'$'}d\"' EXIT HUP INT TERM; cd \"${'$'}d\"; printf '%s\\n' 'fn main(){println!(\"droide-rust-ok\");}' > main.rs; rustc main.rs -o rustc-probe; test -x rustc-probe; test \"${'$'}(./rustc-probe)\" = droide-rust-ok; cargo new --quiet --bin --vcs none cargo-probe; cd cargo-probe; cargo build --quiet --offline; test -x target/debug/cargo-probe; cargo run --quiet --offline >/dev/null",
    )

    val entries: List<WorkstationGuestPackageRecipe> = listOf(
        recipe(
            "runtime.python",
            listOf("python3", "py3-pip"),
            mapOf("python" to "/usr/bin/python3", "python3" to "/usr/bin/python3", "pip" to "/usr/bin/pip3", "pip3" to "/usr/bin/pip3"),
            "python3",
            additionalHealthChecks = listOf(
                ManagedPackageHealthCheck(
                    "python3",
                    listOf("-c", "import subprocess,sys,pip;subprocess.run([sys.executable,'-c','pass'],check=True)"),
                ),
                ManagedPackageHealthCheck("pip3", listOf("--version")),
            ),
        ),
        recipe("runtime.node", listOf("nodejs", "npm"), mapOf("node" to "/usr/bin/node", "npm" to "/usr/bin/npm"), "node"),
        recipe("runtime.php", listOf("php84", "php84-dom", "php84-mbstring", "php84-phar", "php84-xmlwriter"), mapOf("php" to "/usr/bin/php84"), "php"),
        recipe("runtime.java", listOf("openjdk21-jre-headless"), mapOf("java" to "/usr/lib/jvm/java-21-openjdk/bin/java"), "java", listOf("-version")),
        
        recipe("runtime.jvm-shell", listOf("bash", "openjdk21-jre-headless"), mapOf("bash" to "/bin/bash", "java" to "/usr/lib/jvm/java-21-openjdk/bin/java"), "bash", listOf("--version")),
        recipe("runtime.ruby", listOf("ruby"), mapOf("ruby" to "/usr/bin/ruby", "gem" to "/usr/bin/gem"), "ruby"),
        recipe("runtime.lua", listOf("lua5.4"), mapOf("lua" to "/usr/bin/lua5.4"), "lua"),
        recipe("runtime.r", listOf("R"), mapOf("R" to "/usr/bin/R", "Rscript" to "/usr/bin/Rscript"), "R"),
        recipe("runtime.perl", listOf("perl"), mapOf("perl" to "/usr/bin/perl"), "perl", listOf("-v")),
        recipe(
            "toolchain.rust",
            listOf("rust", "cargo", "rustfmt", "build-base", "ca-certificates-bundle"),
            mapOf("rustc" to "/usr/bin/rustc", "cargo" to "/usr/bin/cargo", "rustfmt" to "/usr/bin/rustfmt"),
            "rustc",
            additionalHealthChecks = listOf(
                ManagedPackageHealthCheck("cargo", listOf("--version")),
                ManagedPackageHealthCheck("rustfmt", listOf("--version")),
            ),
            admissionProbes = listOf(rustCargoBuildRunProbe),
        ),
        recipe("toolchain.go", listOf("go"), mapOf("go" to "/usr/bin/go", "gofmt" to "/usr/bin/gofmt"), "go"),
        recipe("toolchain.llvm", listOf("clang22", "clang22-extra-tools", "lld22", "llvm22"), mapOf("clang" to "/usr/bin/clang", "clang++" to "/usr/bin/clang++", "lld" to "/usr/bin/lld", "ld.lld" to "/usr/bin/ld.lld", "clangd" to "/usr/bin/clangd", "clang-format" to "/usr/bin/clang-format", "clang-tidy" to "/usr/bin/clang-tidy"), "clang"),
        recipe("toolchain.gcc", listOf("gcc", "g++", "musl-dev"), mapOf("gcc" to "/usr/bin/gcc", "g++" to "/usr/bin/g++"), "gcc"),
        recipe("toolchain.dotnet", listOf("dotnet10-sdk"), mapOf("dotnet" to "/usr/bin/dotnet"), "dotnet"),
        recipe("toolchain.haskell", listOf("ghc"), mapOf("ghc" to "/usr/bin/ghc", "ghci" to "/usr/bin/ghci"), "ghc"),
        recipe("toolchain.erlang", listOf("erlang28"), mapOf("erl" to "/usr/bin/erl", "erlc" to "/usr/bin/erlc", "escript" to "/usr/bin/escript"), "erl", listOf("-version")),
        recipe("toolchain.elixir", listOf("elixir"), mapOf("elixir" to "/usr/bin/elixir", "elixirc" to "/usr/bin/elixirc", "mix" to "/usr/bin/mix"), "elixir"),
        recipe("toolchain.clojure", listOf("clojure"), mapOf("clojure" to "/usr/bin/clojure"), "clojure"),
        recipe("toolchain.zig", listOf("zig"), mapOf("zig" to "/usr/bin/zig"), "zig"),
        recipe("toolchain.nim", listOf("nim"), mapOf("nim" to "/usr/bin/nim"), "nim"),
        

        recipe("toolchain.ruby-native-build", listOf("ruby-dev", "build-base"), mapOf("gcc" to "/usr/bin/gcc", "make" to "/usr/bin/make"), "gcc", listOf("--version")),

        recipe("package.npm", listOf("nodejs", "npm"), mapOf("npm" to "/usr/bin/npm"), "npm"),
        recipe("package.pnpm", listOf("nodejs", "pnpm"), mapOf("pnpm" to "/usr/bin/pnpm"), "pnpm"),
        recipe("package.yarn", listOf("nodejs", "yarn"), mapOf("yarn" to "/usr/bin/yarn", "yarnpkg" to "/usr/bin/yarnpkg"), "yarn"),
        recipe("package.pip", listOf("python3", "py3-pip"), mapOf("pip" to "/usr/bin/pip3", "pip3" to "/usr/bin/pip3"), "pip3"),
        recipe("package.poetry", listOf("python3", "poetry"), mapOf("poetry" to "/usr/bin/poetry"), "poetry"),
        recipe(
            "package.cargo",
            listOf("rust", "cargo", "build-base", "ca-certificates-bundle"),
            mapOf("cargo" to "/usr/bin/cargo"),
            "cargo",
            admissionProbes = listOf(rustCargoBuildRunProbe),
        ),
        recipe("package.gradle", listOf("openjdk17-jdk", "gradle"), mapOf("gradle" to "/usr/bin/gradle"), "gradle"),
        recipe("package.maven", listOf("openjdk17-jdk", "maven"), mapOf("mvn" to "/usr/bin/mvn"), "mvn"),
        recipe("package.composer", listOf("php84", "composer"), mapOf("composer" to "/usr/bin/composer"), "composer"),
        recipe("package.bundler", listOf("ruby", "ruby-bundler"), mapOf("bundle" to "/usr/bin/bundle", "bundler" to "/usr/bin/bundler"), "bundle"),
        recipe("package.hex", listOf("elixir"), mapOf("mix" to "/usr/bin/mix"), "mix"),

        recipe("build.cmake", listOf("cmake"), mapOf("cmake" to "/usr/bin/cmake", "ctest" to "/usr/bin/ctest"), "cmake"),
        recipe("build.ninja", listOf("ninja-build"), mapOf("ninja" to "/usr/bin/ninja"), "ninja"),
        recipe("build.gradle", listOf("openjdk17-jdk", "gradle"), mapOf("gradle" to "/usr/bin/gradle"), "gradle"),
        recipe("build.maven", listOf("openjdk17-jdk", "maven"), mapOf("mvn" to "/usr/bin/mvn"), "mvn"),
        recipe("build.make", listOf("make"), mapOf("make" to "/usr/bin/make"), "make"),
        recipe("build.meson", listOf("meson"), mapOf("meson" to "/usr/bin/meson"), "meson"),

        recipe("cli.git", listOf("git"), mapOf("git" to "/usr/bin/git"), "git"),
        recipe("cli.gh", listOf("github-cli"), mapOf("gh" to "/usr/bin/gh"), "gh"),
        recipe("cli.curl", listOf("curl"), mapOf("curl" to "/usr/bin/curl"), "curl"),
        recipe("cli.wget", listOf("wget"), mapOf("wget" to "/usr/bin/wget"), "wget"),
        recipe("cli.jq", listOf("jq"), mapOf("jq" to "/usr/bin/jq"), "jq"),
        recipe("cli.ripgrep", listOf("ripgrep"), mapOf("rg" to "/usr/bin/rg"), "rg"),
        recipe("cli.fd", listOf("fd"), mapOf("fd" to "/usr/bin/fd"), "fd"),
        recipe("cli.fzf", listOf("fzf"), mapOf("fzf" to "/usr/bin/fzf"), "fzf"),
        recipe("cli.tree", listOf("tree"), mapOf("tree" to "/usr/bin/tree"), "tree"),
        recipe("cli.rsync", listOf("rsync"), mapOf("rsync" to "/usr/bin/rsync"), "rsync"),
        recipe("cli.ssh", listOf("openssh-client-default"), mapOf("ssh" to "/usr/bin/ssh", "scp" to "/usr/bin/scp", "sftp" to "/usr/bin/sftp"), "ssh"),
        recipe("cli.tar", listOf("tar"), mapOf("tar" to "/bin/tar"), "tar"),
        recipe("cli.zip", listOf("zip", "unzip"), mapOf("zip" to "/usr/bin/zip", "unzip" to "/usr/bin/unzip"), "zip"),
        recipe("cli.sqlite", listOf("sqlite"), mapOf("sqlite3" to "/usr/bin/sqlite3"), "sqlite3"),

        recipe("lsp.gopls", listOf("gopls"), mapOf("gopls" to "/usr/bin/gopls"), "gopls"),
        recipe("lsp.clangd", listOf("clang22-extra-tools"), mapOf("clangd" to "/usr/bin/clangd"), "clangd"),

        recipe("quality.ruff", listOf("py3-ruff"), mapOf("ruff" to "/usr/bin/ruff"), "ruff"),
        recipe("quality.black", listOf("black"), mapOf("black" to "/usr/bin/black"), "black"),
        recipe("quality.prettier", listOf("prettier"), mapOf("prettier" to "/usr/bin/prettier"), "prettier"),
        recipe("quality.rustfmt", listOf("rust", "rustfmt"), mapOf("rustfmt" to "/usr/bin/rustfmt"), "rustfmt"),
        recipe("quality.gofmt", listOf("go"), mapOf("gofmt" to "/usr/bin/gofmt"), "gofmt"),
        recipe("quality.clang-format", listOf("clang22-extra-tools"), mapOf("clang-format" to "/usr/bin/clang-format"), "clang-format"),
        recipe("quality.clang-tidy", listOf("clang22-extra-tools"), mapOf("clang-tidy" to "/usr/bin/clang-tidy"), "clang-tidy"),
        recipe("quality.shfmt", listOf("shfmt"), mapOf("shfmt" to "/usr/bin/shfmt"), "shfmt"),
        recipe("quality.shellcheck", listOf("shellcheck"), mapOf("shellcheck" to "/usr/bin/shellcheck"), "shellcheck"),
        recipe("quality.yamllint", listOf("yamllint"), mapOf("yamllint" to "/usr/bin/yamllint"), "yamllint"),

        recipe("test.pytest", listOf("python3", "py3-pytest"), mapOf("pytest" to "/usr/bin/pytest"), "pytest"),
        recipe(
            "test.cargo",
            listOf("rust", "cargo", "build-base", "ca-certificates-bundle"),
            mapOf("cargo" to "/usr/bin/cargo"),
            "cargo",
            admissionProbes = listOf(rustCargoBuildRunProbe),
        ),
        recipe("test.go", listOf("go"), mapOf("go" to "/usr/bin/go"), "go"),
        recipe("test.gradle", listOf("openjdk17-jdk", "gradle"), mapOf("gradle" to "/usr/bin/gradle"), "gradle"),
        recipe("test.exunit", listOf("elixir"), mapOf("mix" to "/usr/bin/mix"), "mix"),
    ).plus(FoundryGeneratedGuestRoutes.entries).onEach(WorkstationGuestPackageRecipe::validate)

    fun find(familyId: String, version: String): WorkstationGuestPackageRecipe? =
        entries.firstOrNull { it.familyId == familyId && it.version == version }

    fun forFamily(familyId: String): List<WorkstationGuestPackageRecipe> = entries.filter { it.familyId == familyId }

    fun providersForCommand(command: String): List<WorkstationGuestPackageRecipe> =
        entries.filter { command in it.commands }.distinctBy { it.familyId to it.version }
}

@Serializable
private data class GuestPackageRecipeLock(
    val schema: Int = 2,
    val familyId: String,
    val version: String,
    val provider: String = "alpine-apk",
    val guestEnvironment: String = WorkstationGuestEnvironmentSpec.ID,
    val virtualPackage: String,
    val packages: List<String>,
    val resolvedPackages: List<String>,
    val commands: Map<String, String>,
    val commandArgs: Map<String, List<String>> = emptyMap(),
    val admissionContractSha256: String? = null,
    val provenanceUrl: String,
    val installedAtEpochMs: Long,
)

/** The old receipt format and recipe identities above remain readable; new mutations are retired. */
@Suppress("UNUSED_PARAMETER")
class WorkstationGuestPackageInstaller(
    context: Context,
    private val localAuthority: LocalManagedPackageAuthority,
    private val environment: WorkstationGuestEnvironmentManager = WorkstationGuestEnvironmentManager(context.applicationContext),
) {
    fun requireBackend(familyId: String): Unit = LegacyAlpineRuntimeRetirement.reject()

    fun requireMixedBackend() = PackageBackendContract.requireSame(
        "Mixed guest and ADB recipe transaction", environment.backendId, PackageBackendId.DEVICE_ADB,
    )

    suspend fun install(recipe: WorkstationGuestPackageRecipe): ManagedPackageRecord = LegacyAlpineRuntimeRetirement.reject()

    suspend fun uninstall(recipe: WorkstationGuestPackageRecipe, record: ManagedPackageRecord): String = LegacyAlpineRuntimeRetirement.reject()

    suspend fun repair(recipe: WorkstationGuestPackageRecipe, existing: ManagedPackageRecord?): ManagedPackageRecord = LegacyAlpineRuntimeRetirement.reject()

    // The authority reconciles first; its real receipt/path conflicts are never swallowed here.
    suspend fun reconcilePendingGuestCleanup(): String? = withContext(Dispatchers.IO) {
        LegacyAlpineRuntimeRetirement.pendingCleanupWarning(File(localAuthority.appRoot))
    }
}
