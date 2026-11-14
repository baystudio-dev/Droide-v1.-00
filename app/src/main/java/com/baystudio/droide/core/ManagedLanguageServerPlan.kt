package com.baystudio.droide.core








enum class LanguageServerRuntimeKind {
    ANDROID_NATIVE,
    LINUX_ARM64_PROOT,
    JVM,
    NODE,
}

data class ManagedRuntimeRequirement(
    val command: String,
    val versionArgs: List<String>,
    val minimumMajor: Int,
    val minimumMinor: Int = 0,
    val minimumPatch: Int = 0,
) {
    fun validate() {
        require(command.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid runtime command" }
        require(versionArgs.isNotEmpty() && versionArgs.size <= 4) { "Invalid runtime version arguments" }
        require(versionArgs.all { it.matches(Regex("[-A-Za-z0-9._+=]{1,80}")) }) { "Invalid runtime version argument" }
        require(minimumMajor in 1..999 && minimumMinor in 0..999 && minimumPatch in 0..999) { "Invalid minimum runtime version" }
    }

    val minimumVersion: String get() = "$minimumMajor.$minimumMinor.$minimumPatch"
}

data class ManagedLanguageServerPlanEntry(
    val languageServerId: String,
    val packageFamilyId: String,
    val pinnedVersion: String,
    val command: String,
    val runtime: LanguageServerRuntimeKind,
    val requiredCommands: Set<String> = emptySet(),
    val runtimeRequirements: List<ManagedRuntimeRequirement> = emptyList(),
    val notes: String,
)

object ManagedLanguageServerPlan {
    val priority = listOf(
        ManagedLanguageServerPlanEntry("mdx-language-server", "lsp.mdx-language-server", "0.6.4",
            "mdx-language-server", LanguageServerRuntimeKind.NODE, requiredCommands = setOf("node"),
            runtimeRequirements = listOf(ManagedRuntimeRequirement("node", listOf("--version"), 22)),
            notes = "Official @mdx-js/language-server through the on-demand Ubuntu npm installer. Publisher integrity and dependency lock are checked; startup negotiates capabilities. Compiler/runtime binaries are not bundled."),
        ManagedLanguageServerPlanEntry("elp",LocalUbuntuElpPolicy.FAMILY,LocalUbuntuElpPolicy.VERSION,LocalUbuntuElpPolicy.COMMAND,LanguageServerRuntimeKind.LINUX_ARM64_PROOT,
            notes="One exact original ELP server. Selected preinstalled OTP/escript and optional Rebar/CPython retain unverified provenance and are not servers. Explicit one-attempt installer health approval; separate informed project execution grant. Full byte proof, compatible runtime pair and complete cleanup gate activation. Native transport health does not certify embedded service/project/eqWAlizer or target acceptance."),
        ManagedLanguageServerPlanEntry(languageServerId="graphql-local-schema",packageFamilyId=LocalUbuntuGraphqlPolicy.FAMILY,pinnedVersion=LocalUbuntuGraphqlPolicy.VERSION,
            command=LocalUbuntuGraphqlPolicy.COMMAND,runtime=LanguageServerRuntimeKind.LINUX_ARM64_PROOT,
            notes="One exact local-schema GraphQL library route: five original SHA-512 packages and independent runtime.graphql-node 22.23.3 prerequisite. No npm, lifecycle, server/CLI/MessageProcessor or project-code import. Explicit inert .graphqlrc.json with captured SDL/fragments; native completion/hover/validation and named-node outline only. Genuine staged health and independently verified cleanup gate activation. Original layout/import/notice/runtime/device acceptance remains unrun in source validation."),
        ManagedLanguageServerPlanEntry(languageServerId="taplo-toml-wasm",packageFamilyId=LocalUbuntuTomlPolicy.FAMILY,pinnedVersion=LocalUbuntuTomlPolicy.VERSION,
            command=LocalUbuntuTomlPolicy.COMMAND,runtime=LanguageServerRuntimeKind.LINUX_ARM64_PROOT,
            notes="One exact original TaploLsp library route: @taplo/lsp 0.8.0 plus @taplo/core 0.2.0 and independent runtime.taplo-node 22.23.3. Two publisher SHA-512 archives; no npm/bin guessing/scripts. Preserve originals and conservative notices, finite public covered-source refresh, actual API/schema/format/symbol health and owned cleanup. Local config/schema snapshots only, remote fetch blocked, Unicode cursor restriction and same-document rename. Original/runtime/device acceptance remains unrun in source validation."),
        ManagedLanguageServerPlanEntry(
            languageServerId="csharp-ls", packageFamilyId=LocalUbuntuCSharpPolicy.FAMILY, pinnedVersion=LocalUbuntuCSharpPolicy.VERSION,
            command=LocalUbuntuCSharpPolicy.COMMAND, runtime=LanguageServerRuntimeKind.LINUX_ARM64_PROOT,
            notes="One C# configuration, three exact original artifacts: csharp-ls 0.28.0, private SDK 10.0.402/runtime 10.0.13 and independent Node 22.23.3. SDK/tool publisher SHA-512 and helper SHA-256. Full original layouts/configs/managed/native/notices gate data admission; isolated owned health gates server activation. Save validates metadata, Enable authorizes MSBuild targets and generators. No restore/build/test or terminal SDK selection mutation. Original/native/device acceptance remains unrun.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "vue-language-server", packageFamilyId = LocalUbuntuVuePolicy.FAMILY, pinnedVersion = LocalUbuntuVuePolicy.VERSION,
            command = LocalUbuntuVuePolicy.COMMAND, runtime = LanguageServerRuntimeKind.LINUX_ARM64_PROOT,
            notes = "One Vue configuration: exact original Vue/plugin 3.3.13, TypeScript 6.0.3 and private TS-LSP 6.0.2 bundle; independent private Node 22.23.3 is an explicit prerequisite. SHA-512 data-only extraction of 89 identities in 90 placements; no npm/lifecycle execution. Trusted project/config/plugin and network effects require consent. Native paired staged health gates activation; original/native/device acceptance remains unrun. Embedded TS-LSP attribution completeness is unresolved.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "nimlangserver", packageFamilyId = "lsp.nimlangserver", pinnedVersion = "1.16.0",
            command = "nimlangserver", runtime = LanguageServerRuntimeKind.LINUX_ARM64_PROOT,
            requiredCommands = emptySet(),
            notes = "Exact fixed Nim 2.2.12 / nimlangserver 1.16.0 with private original Node 22.23.3. Three explicit separately journaled artifacts supply one server; no terminal Nim/Node selection change or implicit install. Consent precedes project metadata evaluation. Native Nimble uses the fixed compiler and restricted PATH; automatic compiler selection and PATH git/hg are unavailable. Authentic bounded dump only, no fallback compiler; computed/transitive compatibility remains unknown. Completion, hover, signatures, provisional diagnostics and captured-target navigation/rename; no formatting, tasks/tests, macro expansion or broad workspace support. Original payload/native/device acceptance remains unrun.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "cmake-language-server", packageFamilyId = LocalUbuntuCMakePolicy.FAMILY,
            pinnedVersion = LocalUbuntuCMakePolicy.VERSION, command = LocalUbuntuCMakePolicy.COMMAND,
            runtime = LanguageServerRuntimeKind.LINUX_ARM64_PROOT,
            notes = "Exact five original purelib wheels for CMake language-server 0.1.11 with pygls 1.3.1. Fixed CPython 3.12/Unicode 15, CMake 3.28, runtime/helper fingerprints and typed health-free ownership. One server; native plain completion/hover/module suggestions plus selected configured-build targets/cache under explicit execution trust. Full range-less sync and managed UTF-32 only. No formatting, diagnostics or navigation; generic discovery never inherits the specialized adapter. Original payload/native/device acceptance remains separate.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = LspSvelteProfile.ID,
            packageFamilyId = "lsp.svelte-language-server",
            pinnedVersion = LspSvelteProfile.VERSION,
            command = LspSvelteProfile.COMMAND,
            runtime = LanguageServerRuntimeKind.NODE,
            requiredCommands = setOf("node", "npm"),
            runtimeRequirements = listOf(
                ManagedRuntimeRequirement("node", listOf("--version"), 22),
                ManagedRuntimeRequirement("npm", listOf("--version"), 10),
            ),
            notes = "Exact original Svelte language-server 0.18.5, one public svelteserver --stdio configuration, with bounded exact package/alias/dependency/peer validation. Managed installation requires owned Node 22+ and npm 10+; command discovery is not typed package/runtime/launcher proof. Project compilers, Svelte/Vite/preprocessor/Prettier configuration and plugins can execute code after explicit consent. Existing ordinary TS/JS companions and bounded observed disk generations gate native requests; outside-root reads, external ABA, independent native watchers, original payload and target-device acceptance remain unverified. No reverse dirty-Svelte-to-TypeScript claim.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "powershell-editor-services", packageFamilyId = "lsp.powershell-editor-services",
            pinnedVersion = "4.7.0", command = "powershell-editor-services", runtime = LanguageServerRuntimeKind.LINUX_ARM64_PROOT,
            requiredCommands = setOf("node"),
            notes = "Exact PSES 4.7.0 original module ZIP plus receipt-owned PowerShell 7.6.7 ARM64 whole tree, one server. The selected interactive pwsh is never substituted. Node supervises private native-pipe byte forwarding and zero-document health. Noninteractive ConsoleHost with closed child stdin; native stdio is excluded. Explicit consent covers profiles/modules/completers and external effects. No dotnet SDK, Gallery update, DAP, REPL or new Run actions. Original artifact/license/native/device acceptance remains unverified.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "python-pyright",
            packageFamilyId = "lsp.python-pyright",
            pinnedVersion = "1.1.414",
            command = "pyright-langserver",
            runtime = LanguageServerRuntimeKind.NODE,
            requiredCommands = setOf("node"),
            runtimeRequirements = listOf(ManagedRuntimeRequirement("node", listOf("--version"), 14)),
            notes = "Provision from an exact pinned Pyright distribution plus a certified ARM64 Node runtime (upstream engine minimum Node 14).",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "python-pylsp",
            packageFamilyId = "lsp.python-pylsp",
            pinnedVersion = "1.15.0",
            command = "pylsp",
            runtime = LanguageServerRuntimeKind.LINUX_ARM64_PROOT,
            requiredCommands = setOf("python3"),
            runtimeRequirements = listOf(ManagedRuntimeRequirement("python3", listOf("--version"), 3, 9)),
            notes = "Provision from pinned Python wheels in the compatibility userspace; python-lsp-server 1.15.0 requires Python 3.9+ and no unbounded pip install is allowed at runtime.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "typescript",
            packageFamilyId = "lsp.typescript-language-server",
            pinnedVersion = "5.3.0",
            command = "typescript-language-server",
            runtime = LanguageServerRuntimeKind.NODE,
            requiredCommands = setOf("node"),
            runtimeRequirements = listOf(ManagedRuntimeRequirement("node", listOf("--version"), 22, 22, 2)),
            notes = "Provision a pinned TypeScript Language Server bundle plus a certified ARM64 Node runtime; v5.3.0 declares Node >=22.22.2.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "clangd",
            packageFamilyId = "lsp.clangd",
            pinnedVersion = "23.1.1",
            command = "clangd",
            runtime = LanguageServerRuntimeKind.LINUX_ARM64_PROOT,
            notes = "Use an exact ARM64 clangd artifact in the compatibility userspace; never execute an x86 host binary on-device.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "gopls",
            packageFamilyId = "lsp.gopls",
            pinnedVersion = "0.23.0",
            command = "gopls",
            runtime = LanguageServerRuntimeKind.ANDROID_NATIVE,
            notes = "Preferred path is a reproducibly built CGO-disabled android/arm64 binary with source/module hashes pinned.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "rust-analyzer",
            packageFamilyId = "lsp.rust-analyzer",
            pinnedVersion = "20260914",
            command = "rust-analyzer",
            runtime = LanguageServerRuntimeKind.ANDROID_NATIVE,
            notes = "Bootstrap from the exact hash-pinned Termux aarch64 package built from upstream rust-analyzer 2026-09-14; Droide extracts only the reviewed executable and never runs Debian maintainer scripts.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "kotlin",
            packageFamilyId = "lsp.kotlin-language-server",
            pinnedVersion = "1.3.13",
            command = "kotlin-language-server",
            runtime = LanguageServerRuntimeKind.JVM,
            requiredCommands = setOf("java"),
            notes = "Use a pinned JVM distribution and launch it with the workstation JDK, keeping the Java process in the certified execution environment.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "jdtls",
            packageFamilyId = "lsp.jdtls",
            pinnedVersion = "1.61.0",
            command = "jdtls",
            runtime = LanguageServerRuntimeKind.JVM,
            requiredCommands = setOf("java"),
            runtimeRequirements = listOf(ManagedRuntimeRequirement("java", listOf("-version"), 21)),
            notes = "Use Eclipse JDT LS 1.61.0 with a dedicated certified Java 21+ runtime. Keep the dedicated language-server runtime separate from the project-selected Android/Gradle JDK; do not replace a build runtime just to satisfy JDT LS.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "vim-language-server",
            packageFamilyId = "lsp.vim-language-server",
            pinnedVersion = "2.3.1",
            command = "vim-language-server",
            runtime = LanguageServerRuntimeKind.NODE,
            requiredCommands = setOf("node"),
            runtimeRequirements = listOf(ManagedRuntimeRequirement("node", listOf("--version"), 22)),
            notes = "Reviewed upstream npm bundle 2.3.1 through Ubuntu Extensions. Node 22 is the managed-runtime policy, not an asserted upstream minimum. No runtime indexing paths are fabricated; target-device validation remains separate.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = LspSqruffProfile.ID,
            packageFamilyId = "lsp.sqruff",
            pinnedVersion = LspSqruffProfile.VERSION,
            command = LspSqruffProfile.ID,
            runtime = LanguageServerRuntimeKind.LINUX_ARM64_PROOT,
            notes = "Exact original sqruff 0.41.0 ARM64-musl archive and native lsp command. Node is private installer-health only, not an editor runtime. Project/HOME config, ignore/glob/outside-root reads and inline directives require informed consent. The reviewed default release has no Python templaters; custom builds are unverified. Managed exact-byte identity gates LF/scalar/full-format repairs; relevant edits restart on next demand. Unversioned diagnostics and silence do not prove validity. No completion, navigation or database UI/execution; original payload/device acceptance remains separate.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = LspPrismaProfile.ID,
            packageFamilyId = "lsp.prisma-language-server",
            pinnedVersion = LspPrismaProfile.VERSION,
            command = LspPrismaProfile.ID,
            runtime = LanguageServerRuntimeKind.NODE,
            requiredCommands = setOf("node"),
            runtimeRequirements = listOf(ManagedRuntimeRequirement("node", listOf("--version"), 22)),
            notes = "Exact original @prisma/language-server 31.13.0 and 43 dependencies. Managed installation requires selected Node 22+ and npm 10, and uses the sealed public-entry debug-to-stderr prelude plus zero-document transport health. PATH discovery remains provenance-unverified. Legacy schemas only; Next/ORM 8, Prisma 6, mixed-generation disk schemas, original payload and device acceptance are unverified. Executable config imports require explicit project consent; no database/generate/migrate adapter.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "clojure-lsp",
            packageFamilyId = "lsp.clojure-lsp",
            pinnedVersion = "2026.10.15-11.42.38",
            command = "clojure-lsp",
            runtime = LanguageServerRuntimeKind.LINUX_ARM64_PROOT,
            notes = "Exact original ARM64 native ZIP; Node is the managed supervisor/health dependency, not a native LSP or generic-discovery requirement. Full analysis may execute matched/custom classpath commands, hooks and linters, use guest credentials/network and write caches/logs/stubs after informed trust. Initial guest-temp log residue may remain; native platform/project acceptance and artifact SBOM closure are unverified.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "make-ls",
            packageFamilyId = "lsp.make-ls",
            pinnedVersion = "0.1.25",
            command = "make-ls",
            runtime = LanguageServerRuntimeKind.LINUX_ARM64_PROOT,
            notes = "Exact original publisher ARM64 archive and retained notices; source-derived proposed archive/ELF admission remains unverified on target. Parsing needs no Make/Go/compiler runtime; Node is installer-health only. Explicit project-read consent, full-sync and partial native features; hover, document symbols and formatting are unavailable for this pin.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "lemminx",
            packageFamilyId = "lsp.lemminx",
            pinnedVersion = "0.31.2",
            command = "lemminx",
            runtime = LanguageServerRuntimeKind.JVM,
            requiredCommands = setOf("node", "java"),
            notes = "Exact original LemMinX JAR with publisher SHA-256/size. Managed wrapper uses fixed Ubuntu Java 21 and Node, private per-process state, and explicit project consent for local/remote XML references. PATH discovery is not fixed-Java or managed-wrapper attestation; actual JAR/Java/device and schema/network readiness remain unverified.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "zls",
            packageFamilyId = "lsp.zls",
            pinnedVersion = "0.16.0",
            command = "zls",
            runtime = LanguageServerRuntimeKind.LINUX_ARM64_PROOT,
            requiredCommands = setOf("node", "zig"),
            notes = "Exact original publisher ARM64 archive. Editor requires Node and a separately selected stable Zig 0.16.x on Linux 5.10+; exact nearest .zigversion is checked after consent. Compiler/build execution, caches and dependencies remain unverified. No compiler upgrade/substitution or target acceptance is implied.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "buf",
            packageFamilyId = "lsp.buf",
            pinnedVersion = "1.74.0",
            command = "buf",
            runtime = LanguageServerRuntimeKind.LINUX_ARM64_PROOT,
            notes = "Exact original ARM64 archive with publisher SHA-256 and retained license. Native Proto features need no Go/protoc runtime; configured plugins/dependencies are optional and may execute/fetch after explicit consent. Node is installer-health only. No generation/dependency update adapter or target acceptance is claimed.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "terraform-ls",
            packageFamilyId = "lsp.terraform-ls",
            pinnedVersion = "0.39.0",
            command = "terraform-ls",
            runtime = LanguageServerRuntimeKind.LINUX_ARM64_PROOT,
            notes = "Exact official ARM64 ZIP with publisher SHA-256; Terraform CLI is optional. Analysis may run Terraform/providers and contact Registry/Algolia after explicit project enablement. No automatic init/plan/apply; advertised CLI validate is a no-op in this release. Target acceptance is unverified.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "texlab",
            packageFamilyId = "lsp.texlab",
            pinnedVersion = "5.26.0",
            command = "texlab",
            runtime = LanguageServerRuntimeKind.LINUX_ARM64_PROOT,
            notes = "Exact publisher ARM64 GNU/Linux release with SHA-256 and size. TeX is optional for native editing, but discovery can execute latexmkrc and requires explicit project enablement. Build, formatter, lint and viewer tools are separate; target acceptance is unverified.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "marksman",
            packageFamilyId = "lsp.marksman",
            pinnedVersion = "2026-02-08",
            command = "marksman",
            runtime = LanguageServerRuntimeKind.LINUX_ARM64_PROOT,
            notes = "Reviewed publisher ARM64 single-file self-contained .NET 9 release, exact SHA-256 and size. Requires Ubuntu system libraries, not a dotnet executable. Node is only the installer health helper. No device/runtime compatibility certification is implied.",
        ),
        ManagedLanguageServerPlanEntry(
            languageServerId = "perlnavigator",
            packageFamilyId = "lsp.perlnavigator",
            pinnedVersion = "0.8.20",
            command = "perlnavigator",
            runtime = LanguageServerRuntimeKind.NODE,
            requiredCommands = setOf("node", "perl"),
            runtimeRequirements = listOf(ManagedRuntimeRequirement("node", listOf("--version"), 22)),
            notes = "Reviewed PerlNavigator npm 0.8.20 with a separately installed Perl interpreter. Node 22 is the managed-runtime policy; upstream requires Node 16+. Project analysis can execute Perl code and requires explicit workspace enablement. Optional Perl tooling and target acceptance remain separate.",
        ),
    )

    fun validate(registry: List<LanguageServerSpec> = LanguageServerRegistry.builtIns) {
        require(priority.isNotEmpty()) { "Managed LSP plan is empty" }
        require(priority.map { it.languageServerId }.distinct().size == priority.size) { "Duplicate managed LSP id" }
        require(priority.map { it.packageFamilyId }.distinct().size == priority.size) { "Duplicate managed LSP package family" }
        require(priority.all { it.packageFamilyId.matches(Regex("[A-Za-z0-9._-]{1,120}")) }) { "Invalid managed LSP package family" }
        require(priority.all { it.command.matches(Regex("[A-Za-z0-9._+-]{1,80}")) }) { "Invalid managed LSP command" }
        priority.forEach { entry ->
            require(entry.requiredCommands.all { it.matches(Regex("[A-Za-z0-9._+-]{1,80}")) }) { "Invalid required runtime command" }
            entry.runtimeRequirements.forEach(ManagedRuntimeRequirement::validate)
            require(entry.runtimeRequirements.map { it.command }.toSet().all { it in entry.requiredCommands }) {
                "Runtime version checks must reference declared required commands"
            }
        }
        val known = registry.map { it.id }.toSet()
        val missing = priority.map { it.languageServerId }.filterNot { it in known }
        require(missing.isEmpty()) { "Managed LSP plan references unknown registry ids: $missing" }
    }

    fun forServer(id: String): ManagedLanguageServerPlanEntry? = priority.firstOrNull { it.languageServerId == id }
}
