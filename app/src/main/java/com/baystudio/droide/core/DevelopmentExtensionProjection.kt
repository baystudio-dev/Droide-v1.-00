package com.baystudio.droide.core

internal object DevelopmentExtensionProjection {
    data class RecordViews(
        val catalogRecords: List<ManagedPackageRecord>,
        val localCapabilityRecords: List<ManagedPackageRecord>,
    )

    /** One guest owns both catalog installation status and executable contribution. */
    fun recordsForUbuntuSnapshot(
        records: List<ManagedPackageRecord>, recoveryProblem: String?, appRoot: String,
        ownsLocal: (ManagedPackageRecord) -> Boolean,
        normalize: (ManagedPackageRecord) -> ManagedPackageRecord = { it },
    ): RecordViews = recordsForSnapshot(
        records.map(normalize).filter { ownsLocal(it) && LocalLinuxPackageEnvironment.supportsWorkspaceSelection(it, appRoot) },
        recoveryProblem, ownsLocal,
    )

    /** Recovery failure disables activation, while durable receipts remain available for repair. */
    fun recordsForSnapshot(
        records: List<ManagedPackageRecord>,
        recoveryProblem: String?,
        ownsLocal: (ManagedPackageRecord) -> Boolean,
    ): RecordViews {
        val durable = records.toList()
        val capabilities = durable.filter { record ->
            val local = ownsLocal(record)
            (record.scope != ExecutionScope.LOCAL_LINUX_ARM64.name || local) &&
                (recoveryProblem == null || !local)
        }
        return RecordViews(durable, capabilities)
    }

    fun localVersions(family: ExtensionFamily): List<ExtensionVersion> = buildList {
        val requires = family.versions.flatMap { it.requires }.toSet()
        fun addRecipe(version: String, channel: String, provides: Set<String>, note: String) {
            add(ExtensionVersion(version, channel, recommended = true,
                installKind = ExtensionInstallKind.REVIEWED_RECIPE,
                scope = ExecutionScope.LOCAL_LINUX_ARM64, provides = provides,
                requires = requires, note = note))
        }
        if (LocalUbuntuCSharpPolicy.isFamily(family.id)) {
            val recipe=LocalUbuntuCSharpPolicy.recipe(family.id)
            addRecipe(recipe.version,"csharp-original-exact",setOf(recipe.command),
                "Exact original csharp-ls 0.28.0, SDK 10.0.402/runtime 10.0.13 and independent private Node 22.23.3: three receipts, one server. Publisher SHA-512 SDK/tool and SHA-256 helper. Whole original configuration/dependency/native/notice checks and owned zero-project health gate activation. Embedded NuGet signature is retained but not cryptographically verified. Project MSBuild and generators execute only after Enable; original/native/device acceptance remains unrun.")
            return@buildList
        }
        if (family.id == LocalAndroidWorkstationPolicy.FAMILY) addRecipe(
            LocalAndroidWorkstationPolicy.VERSION, "ubuntu-components",
            setOf(LocalAndroidWorkstationPolicy.COMMAND, "android-build", "android-sdk"),
            "OpenJDK 17, SDK 36, ARM64 Build Tools 36.0.0 and Gradle 8.13 with owned receipts and complete prerequisite checks.")
        LocalUbuntuCMakeCatalog.find(family.id)?.let { recipe -> addRecipe(recipe.version,
            "pypi-reviewed-five-wheels", setOf(recipe.command),
            "Exact CMake language-server 0.1.11, pygls 1.3.1 and three pinned dependencies. Finite purelib ZIP/RECORD/license checks; CPython 3.12/Unicode 15 and CMake 3.28 runtime fingerprints. Plain completion/hover and immediate module suggestions, plus selected configured-build target/cache suggestions after explicit execution consent. No formatting, diagnostics or navigation. Zero-document health is lifecycle-only; original payload and target acceptance remain separate.") }
        LocalUbuntuRubyCatalog.find(family.id)?.let { recipe -> addRecipe(recipe.version,
            "rubygems-pinned", setOf(recipe.command),
            "Original Ruby LSP and six SHA-256-pinned gems; private frozen Ubuntu bundle and stdio health check.") }
        UbuntuAptCatalog.find(family.id)?.let { recipe -> addRecipe(recipe.version,
            "ubuntu-apt", recipe.commands.keys,
            "Signed Ubuntu 24.04 ARM64 packages; shared with Linux terminal.") }
        if (family.id == "runtime.node") addRecipe(LocalUbuntuNodeDistribution.VERSION,
            "nodejs-official", setOf("node", "npm", "npx"),
            "Official current Node.js ARM64 archive with publisher SHA-256; downloaded on request.")
        UbuntuNpmCatalog.find(family.id)?.let { entry -> addRecipe(entry.offeredVersion,
            if (entry.reviewedVersion == null || entry.allowsPublisherUpdates) "npm-official" else "npm-reviewed",
            if (entry.familyId == LocalUbuntuMusePolicy.FAMILY) setOf("muse", "muse-code-acp") else setOf(entry.command),
            if (entry.familyId == LocalUbuntuMusePolicy.FAMILY)
                "Latest official Meta Muse plus community ACP adapter; native download exceeds 350 MB. Installs Node.js when needed; one Extensions action for terminal and chat. Sign in with muse login."
            else if (entry.familyId == "plugin.kimi-code")
                "Official Kimi Code with native ACP; updates through Extensions. Requires Node.js 22.19+ (installed when needed); migration scripts and automatic self-updates are disabled for this managed installation. Sign in with kimi login."
            else if (entry.familyId == "plugin.deepseek-harness")
                "Official DeepSeek Harness public preview with native ACP. Requires Node.js 24.9+ (installed when needed). Updates through Extensions; configure providers with dsh web --no-open. Native Plan mode is not advertised."
            else if (entry.familyId == LocalUbuntuGraphqlPolicy.FAMILY)
                "Exact five-original-package GraphQL local-schema intelligence with independent Node 22.23.3. Data-only SHA-512 archive admission, complete originals/notices and finite CJS graph; no npm or project-code loading. Native completion/hover/validation and named-node outline require explicit bounded local-schema consent. Original/native/device acceptance remains unrun."
            else if (entry.familyId == LocalUbuntuTomlPolicy.FAMILY)
                "Older exact original @taplo/lsp 0.8.0 and @taplo/core 0.2.0 library route with independently owned Node 22.23.3. Publisher SHA-512, all original files and conservative notices retained. No npm/scripts. Local immutable config/schema snapshots and blocked remote fetch; genuine staged health/owned cleanup gate installation. Original and device acceptance remain unrun in the source checkpoint."
            else if (entry.familyId == LocalUbuntuVuePolicy.FAMILY)
                "Exact Vue/plugin 3.3.13, TypeScript 6.0.3 and private TS-LSP 6.0.2: 89 SHA-512 archive identities, 90 exact placements, no npm/lifecycle execution. Separate private Node 22.23.3 storage/receipt; one configuration. Trusted project/config/plugins and network effects require consent. Genuine paired staged health gates future activation. Complete unopened TS-LSP bundle attribution and original/native/device acceptance remain unresolved."
            else if (entry.familyId == "lsp.prisma-language-server")
                "Reviewed original @prisma/language-server 31.13.0 with 43 exact dependencies, selected Node 22+/npm 10 and a sealed public-entry debug-to-stderr prelude. Zero-document health is transport-only. Legacy schemas require explicit executable-config consent; Next/ORM 8 is unavailable, and Prisma 6/mixed-generation, original payload and target-device acceptance remain unverified. Generic PATH discovery does not attest this recipe."
            else if (entry.familyId == "lsp.svelte-language-server")
                "Reviewed original Svelte language-server 0.18.5 with exact package, alias, dependency, peer and bounded physical-tree validation; owned Node 22+ and npm 10+. One public svelteserver --stdio configuration. Typed managed identity gates companion/observer support; project compiler/config/plugin execution requires explicit consent. Zero-document health is transport-only. Outside-root reads, external ABA, independent native watchers, original payload and native/device semantics remain unverified."
            else if (entry.reviewedVersion == null) "Exact official npm package and integrity-locked dependencies in Ubuntu."
            else "Reviewed ${entry.packageName} ${entry.reviewedVersion}; publisher SHA-512, sealed dependencies and original-server stdio health. Target-device acceptance is separate.") }
        LocalUbuntuBundleAliasPolicy.find(family.id)?.let { recipe -> addRecipe(
            LocalUbuntuBundleAliasPolicy.VERSION_CHOICE, "sdk-bundle", recipe.commands.keys,
            "Commands supplied by ${recipe.primaryFamilyId}; exact SDK dependency without a second SDK copy.") }
        LocalUbuntuVendorCatalog.find(family.id)?.let { definition -> addRecipe(
            definition.offeredVersion, "publisher-ubuntu",
            setOf(definition.command) + definition.auxiliaryCommands.keys,
            if (definition.familyId == LocalUbuntuElpPolicy.FAMILY)
                "Exact original ELP ${LocalUbuntuElpPolicy.RELEASE_TAG}. Explicit selected OTP/escript health approval, full byte proof and complete cleanup gate activation. Rebar and optional CPython are unverified prerequisites, not additional servers. Project analysis needs its own informed execution grant. Native transport health does not certify project, embedded services, transitive notices or target acceptance."
            else if (definition.familyId == LocalUbuntuGraphqlNodePolicy.FAMILY)
                "Independent exact original Node 22.23.3 prerequisite for GraphQL. Full original tree, inert npm/npx/corepack, config, native launch-chain closure and notices retained. No terminal Node default or another-family receipt reuse. Genuine owned identity health and device acceptance are separate gates."
            else if (definition.familyId == LocalUbuntuTomlNodePolicy.FAMILY)
                "Exact independent original Node 22.23.3 for Taplo WASM. Full original tree/config/native closure/notices and isolated identity/resource health are required. Terminal Node selection unchanged; no another-family receipt or health authority. Original/device acceptance remains unrun."
            else if (definition.familyId == LocalUbuntuVueHelperPolicy.FAMILY)
                "Exact independent private original Node 22.23.3 for Vue analysis. Full publisher tree, safe relative links, source-bound configuration and native closure are retained. No public node/npm/npx or terminal selection change; no Nim receipt/health/process authority is reused. Original runtime/device acceptance remains unrun."
            else if (definition.familyId == LocalUbuntuNimPolicy.FAMILY)
                "Exact Nim 2.2.12 / nimlangserver 1.16.0 fixed analysis with private Node 22.23.3. Three original artifacts, three transactions, one server. Project metadata may run code after explicit consent. Automatic compiler selection is disabled; restricted PATH metadata tools can fail. Terminal Nim and Node defaults stay unchanged; original archive/closure/native acceptance remains separate."
            else if (definition.familyId in setOf(LocalUbuntuNimToolchainPolicy.FAMILY,LocalUbuntuNimHelperPolicy.FAMILY))
                "Exact private prerequisite for fixed Nim analysis. The complete original tree and notices are retained; only the private health command is exposed. No compiler/npm/project command is run by toolchain static admission; no terminal toolchain or Node default is selected."
            else if (definition.familyId == LocalUbuntuPowerShellEditorServicesPolicy.FAMILY)
                "Exact PSES 4.7.0 original module ZIP with separately receipt-owned PowerShell 7.6.7 ARM64 runtime. Two artifacts supply one server. Node supervises the original private named-pipe transport and zero-document health; native stdio is excluded. Noninteractive ConsoleHost and closed child stdin deny prompts. Editor profiles/modules/completers may run code and access files/network after informed project consent. Interactive runtime defaults are preserved; no dotnet SDK or Gallery install. Original artifact/license/native/device acceptance remains separate."
            else if (definition.familyId == LocalUbuntuPowerShellRuntimePolicy.FAMILY)
                "Exact original PowerShell 7.6.7 ARM64 whole tree with publisher SHA-256, finite native/Ubuntu closure and private startup/version health. Createdump stays internal. PSES pins this dependency independently of the interactive default. Signed Ubuntu prerequisites run normal package configuration; dirty shared state needs explicit repair. Original artifact/license/device acceptance remains separate."
            else if (definition.familyId == LocalUbuntuSqruffPolicy.FAMILY)
                "Reviewed original sqruff ${LocalUbuntuSqruffPolicy.VERSION} ARM64-musl archive with publisher SHA-256. Native project/HOME config, ignore/glob/outside-root reads and inline directives require informed consent. Default release source has no Python templaters; custom builds are unverified. Exact managed identity gates coordinate/full-format repairs. Node is private zero-document installer-health only. Unversioned lint may remain pending; edits restart on demand. No completion/navigation or database UI/execution. Original payload/device acceptance remains separate."
            else if (definition.familyId == "lsp.clojure-lsp")
                "Reviewed Clojure LSP 2026.10.15-11.42.38 original ARM64 ZIP with publisher SHA-256. Full project/dependency analysis can run project tools, hooks and custom linters, access guest credentials/network and write caches after explicit consent. Java/project tools are separate and unverified; Node supervises the managed launcher and lifecycle-only health. A local guest temporary log may remain after shutdown. Original native payload/device and full-project acceptance remain unverified."
            else if (definition.familyId == LocalUbuntuMakePolicy.FAMILY)
                "Reviewed Make LS ${LocalUbuntuMakePolicy.VERSION} original ARM64 archive with publisher SHA-256 and proposed notice/static-ELF admission. Project/include reads require explicit consent. Hover, document symbols and formatting are unavailable for this pin; Node is installer-health only. Original payload/device acceptance remains unverified."
            else if (definition.familyId == LocalUbuntuLemMinxPolicy.FAMILY)
                "Reviewed LemMinX ${LocalUbuntuLemMinxPolicy.VERSION} original JAR with publisher SHA-256/size, fixed Ubuntu Java 21 and Node, and private process state. XML references can read outside the project and fetch remote resources after explicit consent. Target and schema/network acceptance remain separate."
            else if (definition.familyId == LocalUbuntuZlsPolicy.FAMILY)
                "Reviewed ZLS ${LocalUbuntuZlsPolicy.VERSION} original ARM64 archive with publisher SHA-256 and retained LICENSE/README. Full project analysis requires compatible Zig 0.16.x, Linux 5.10+ and explicit project-code consent. Node is installer-health only; payload and device acceptance remain separate."
            else if (definition.familyId == LocalUbuntuBufPolicy.FAMILY)
                "Reviewed Buf ${LocalUbuntuBufPolicy.VERSION} original ARM64 archive with publisher SHA-256 and retained license. Proto analysis may execute configured plugins and fetch dependencies after explicit consent. Node is installer-health only; target acceptance and optional plugins remain unverified."
            else if (definition.familyId == LocalUbuntuTerraformPolicy.FAMILY)
                "Reviewed Terraform LS ${LocalUbuntuTerraformPolicy.VERSION} official ARM64 ZIP and publisher SHA-256. Project analysis may execute Terraform/provider tools and contact Registry/Algolia; explicit consent required. CLI is optional; no init/plan/apply or CLI-validation claim. Node is health-only; device acceptance remains separate."
            else if (definition.familyId == LocalUbuntuTexlabPolicy.FAMILY)
                "Reviewed Texlab ${LocalUbuntuTexlabPolicy.VERSION} publisher SHA-256 and exact ARM64 archive size. Analysis requires explicit project-code consent; optional TeX tools are separate. Node runs only the isolated installer health helper. Device acceptance is separate."
            else if (definition.familyId == LocalUbuntuMarksmanPolicy.FAMILY)
                "Reviewed Marksman ${LocalUbuntuMarksmanPolicy.VERSION} publisher SHA-256 and exact ARM64 payload size; self-contained .NET with Ubuntu libraries. Node runs only the installer health helper. Device acceptance is separate."
            else "Original publisher ARM64 release; Ubuntu prerequisites and command health checked before activation.") }
    }

    /** Terminal installs have their own unversioned detection choice, never a managed receipt. */
    fun userInstalledChoice(family: ExtensionFamily): ExtensionVersion? {
        if (family.versions.any { it.installKind == ExtensionInstallKind.BUILT_IN || canDetectExternal(it) }) return null
        val commands = family.versions.filter { it.scope == ExecutionScope.LOCAL_LINUX_ARM64 }
            .flatMap { it.provides }.filter { it.matches(Regex("[A-Za-z0-9._+-]{1,64}")) }.toSet()
        if (commands.isEmpty()) return null
        return ExtensionVersion("User-installed", "user-managed", installKind = ExtensionInstallKind.CATALOG_ONLY,
            scope = ExecutionScope.LOCAL_LINUX_ARM64, provides = commands,
            note = "Detected from the shared Ubuntu PATH; version is owned by the user's package manager.")
    }

    fun platformProblem(supportedAbis: List<String>, is64Bit: Boolean): String? =
        if (is64Bit && supportedAbis.firstOrNull() == "arm64-v8a") null
        else "This installer requires a 64-bit ARM64 Android process and local Ubuntu; this device cannot run the packaged Linux engine"

    /** A command lookup proves a user-managed command exists, never a particular recipe version. */
    fun canDetectExternal(version: ExtensionVersion): Boolean =
        version.installKind == ExtensionInstallKind.CATALOG_ONLY &&
            version.scope == ExecutionScope.LOCAL_LINUX_ARM64

    /** Bridge discovery describes Device Workstation, never an app-local installation receipt. */
    fun detectedDeviceComponent(
        family: ExtensionFamily,
        version: ExtensionVersion,
        workstation: AndroidDevelopmentManager.WorkstationInfo?,
    ): ExtensionVersionState? {
        if (workstation == null || version.scope != ExecutionScope.LOCAL_LINUX_ARM64 ||
            version.installKind !in setOf(ExtensionInstallKind.CATALOG_ONLY, ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN)) return null
        val detail = when (family.id) {
            "sdk.android" -> when {
                version.version == "Project-selected" -> "SDKs: ${workstation.sdkPlatforms.sorted().joinToString(", ")}"
                version.version.toIntOrNull()?.let { it in workstation.sdkPlatforms } == true -> "SDK ${version.version}"
                else -> null
            }
            "toolchain.jdk" -> when {
                version.version == "Project-selected" -> "JDK ${workstation.javaVersion} active"
                version.version.toIntOrNull() == workstation.javaVersion -> "JDK ${version.version} active"
                else -> null
            }
            "build.android-tools" -> when {
                version.version == "Project-selected" -> "Build Tools: ${workstation.buildToolsVersions.sorted().joinToString(", ")}"
                version.version in workstation.buildToolsVersions -> "Build Tools ${version.version}"
                else -> null
            }
            "meta.android-development" -> if (version.version == "Project-driven")
                "JDK ${workstation.javaVersion} · SDK ${workstation.compileSdk} · ${workstation.abi}" else null
            else -> null
        } ?: return null
        return ExtensionVersionState(family, version, ExtensionState.INSTALLED, "Installed Device Workstation · $detail")
    }

    fun visibleStates(states: List<ExtensionVersionState>): List<ExtensionVersionState> {
        val installerFamilies = states.asSequence().filter { it.version.installKind in setOf(
            ExtensionInstallKind.REVIEWED_RECIPE, ExtensionInstallKind.ANDROID_LOCAL_COMPONENT,
        ) }.map { it.family.id }.toSet()
        return states.filterNot { item ->
            item.family.id in installerFamilies && item.state == ExtensionState.UNAVAILABLE &&
                item.version.installKind == ExtensionInstallKind.CATALOG_ONLY &&
                item.version.scope != ExecutionScope.EDITOR &&
                item.version.version in setOf("External", "Local", "Workstation", "Project-selected")
        }
    }
}
