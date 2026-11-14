package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.serialization.json.*

/** Reviewed server-specific discovery and configuration. Existing integrations are unchanged. */
internal object LspServerProfiles {
    fun isAsm(spec: LanguageServerSpec): Boolean = LspAsmProfile.matches(spec)

    fun isSolidity(spec: LanguageServerSpec): Boolean = LspSolidityProfile.matches(spec)
    fun isScala(spec: LanguageServerSpec): Boolean = LspScalaProfile.matches(spec)
    fun isElixir(spec: LanguageServerSpec): Boolean = LspElixirProfile.matches(spec)

    fun isHaskell(spec: LanguageServerSpec): Boolean = LspHaskellProfile.matches(spec)

    fun isR(spec: LanguageServerSpec): Boolean = LspRProfile.matches(spec)

    private val vimMarkers = listOf(".git", "autoload", "plugin")
    private val perlMarkers = listOf("cpanfile", "Makefile.PL", "Build.PL", ".git")
    private val texlabMarkers = listOf(".texlabroot", "texlabroot", "Tectonic.toml", ".latexmkrc", "latexmkrc")
    private val texlabExtensions = setOf("tex", "sty", "cls", "lco", "bib", "bibtex")

    fun isElp(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == LspElpProfile.ID &&
        spec.languageIds == setOf("erlang") && spec.extensions == LspElpProfile.extensions && spec.filenames.isEmpty() &&
        spec.commands == listOf(listOf(LspElpProfile.COMMAND))
    private fun isElpRoute(spec: LanguageServerSpec, path: String? = null): Boolean = spec.id == LspElpProfile.ID ||
        "erlang" in spec.languageIds || spec.extensions.any { it in LspElpProfile.extensions } || path?.let(LspElpProfile::supportsPath) == true ||
        spec.commands.any { it.firstOrNull()?.substringAfterLast('/') in setOf("elp", LspElpProfile.COMMAND) }

    fun isGraphql(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == LspGraphqlProfile.ID &&
        spec.languageIds == setOf("graphql") && spec.extensions == LspGraphqlProfile.extensions && spec.filenames.isEmpty() &&
        spec.commands == listOf(listOf(LspGraphqlProfile.COMMAND))
    private fun isGraphqlRoute(spec: LanguageServerSpec, path: String? = null): Boolean = spec.id == LspGraphqlProfile.ID ||
        "graphql" in spec.languageIds || spec.extensions.any { it in LspGraphqlProfile.extensions } || path?.let(LspGraphqlProfile::supportsPath) == true

    fun isToml(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == LspTomlProfile.ID &&
        spec.languageIds == setOf("toml") && spec.extensions == LspTomlProfile.extensions && spec.filenames.isEmpty() &&
        spec.commands == listOf(listOf(LspTomlProfile.COMMAND))
    private fun isTomlRoute(spec: LanguageServerSpec, path: String? = null): Boolean = spec.id == LspTomlProfile.ID ||
        "toml" in spec.languageIds || "toml" in spec.extensions || path?.let(LspTomlProfile::supportsPath) == true

    fun isCSharp(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == LspCSharpProfile.ID &&
        spec.languageIds == setOf("csharp") && spec.extensions == LspCSharpProfile.extensions && spec.filenames.isEmpty() &&
        spec.commands == listOf(listOf(LspCSharpProfile.COMMAND))
    private fun isCSharpRoute(spec: LanguageServerSpec, path: String? = null): Boolean = spec.id == LspCSharpProfile.ID ||
        "csharp" in spec.languageIds || "cs" in spec.extensions || path?.let(LspCSharpProfile::supportsPath) == true

    fun isVue(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == LspVueProfile.ID &&
        spec.languageIds == setOf("vue") && spec.extensions == LspVueProfile.extensions && spec.filenames.isEmpty() &&
        spec.commands == listOf(listOf(LspVueProfile.COMMAND))
    private fun isVueRoute(spec: LanguageServerSpec, path: String? = null): Boolean = spec.id == LspVueProfile.ID ||
        "vue" in spec.languageIds || "vue" in spec.extensions || path?.let(LspVueProfile::supportsPath) == true

    fun isNim(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == LspNimProfile.ID &&
        spec.languageIds == setOf("nim") && spec.extensions == LspNimProfile.extensions && spec.filenames.isEmpty() &&
        spec.commands == listOf(listOf(LspNimProfile.COMMAND))

    private fun isNimRoute(spec: LanguageServerSpec, path: String? = null): Boolean =
        spec.id == LspNimProfile.ID || "nim" in spec.languageIds || "nim" in spec.extensions ||
            (path != null && LanguageRegistry.forFile(path)?.id == "nim")

    fun isCMake(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == LspCMakeProfile.ID &&
        spec.languageIds == setOf("cmake") && spec.extensions == LspCMakeProfile.extensions && spec.filenames == LspCMakeProfile.filenames &&
        spec.commands == listOf(listOf(LspCMakeProfile.COMMAND))

    private fun isCMakeRoute(spec: LanguageServerSpec, path: String? = null): Boolean =
        spec.id == LspCMakeProfile.ID || "cmake" in spec.languageIds || "cmake" in spec.extensions ||
            spec.filenames.any { it.equals("CMakeLists.txt", ignoreCase = true) } ||
            (path != null && LanguageRegistry.forFile(path)?.id == "cmake")

    fun isSvelte(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == LspSvelteProfile.ID &&
        spec.languageIds == setOf("svelte") && spec.extensions == LspSvelteProfile.extensions && spec.filenames.isEmpty() &&
        spec.commands == listOf(listOf(LspSvelteProfile.COMMAND, "--stdio"))

    private fun isSvelteRoute(spec: LanguageServerSpec, path: String? = null): Boolean =
        spec.id == LspSvelteProfile.ID || "svelte" in spec.languageIds || "svelte" in spec.extensions ||
            (path != null && LanguageRegistry.forFile(path)?.id == "svelte")

    fun isSqruff(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == LspSqruffProfile.ID &&
        spec.languageIds == setOf("sql") && spec.extensions == LspSqruffProfile.extensions && spec.filenames.isEmpty() &&
        spec.commands == listOf(listOf(LspSqruffProfile.ID, "lsp"))

    /** A conservative formatting deny-list only. A basename never establishes managed provenance. */
    fun isUnverifiedSqruff(spec: LanguageServerSpec): Boolean = spec.source != "built-in" && spec.commands.any { argv ->
        argv.firstOrNull()?.replace('\\', '/')?.substringAfterLast('/')?.lowercase() in setOf("sqruff", "sqruff.exe")
    }

    private fun isSqlRoute(spec: LanguageServerSpec, path: String? = null): Boolean =
        (path != null && (LspSqruffProfile.supportsPath(path) || LanguageRegistry.forFile(path)?.id == "sql")) ||
            spec.id == LspSqruffProfile.ID || isUnverifiedSqruff(spec) || "sql" in spec.languageIds || "sql" in spec.extensions

    fun isPrisma(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == LspPrismaProfile.ID &&
        spec.languageIds == setOf("prisma") && spec.extensions == LspPrismaProfile.extensions && spec.filenames.isEmpty() &&
        spec.commands == listOf(listOf(LspPrismaProfile.ID, "--stdio"))

    fun isPowerShell(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == LspPowerShellProfile.ID &&
        spec.languageIds == setOf("powershell") && spec.extensions == LspPowerShellProfile.extensions && spec.filenames.isEmpty() &&
        spec.commands == listOf(listOf(LspPowerShellProfile.ID))

    private fun isPowerShellRoute(spec: LanguageServerSpec, path: String? = null): Boolean =
        spec.id == LspPowerShellProfile.ID || "powershell" in spec.languageIds || spec.extensions.any { it in LspPowerShellProfile.extensions } ||
            (path != null && (LspPowerShellProfile.supportsPath(path) || LanguageRegistry.forFile(path)?.id == "powershell"))

    fun isClojure(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == "clojure-lsp"

    fun isMake(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == "make-ls"

    fun isLemMinx(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == "lemminx"

    fun isZls(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == "zls"

    fun isBuf(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == "buf"

    fun isTerraform(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == "terraform-ls"

    /** These profiles share one opened-workspace session, not an immutable per-document owner. */
    fun reusesReadySessionAcrossDocumentClose(spec: LanguageServerSpec): Boolean =
        isR(spec) || isHaskell(spec) || isElixir(spec) || isAsm(spec) || isScala(spec) || isSolidity(spec)

    fun refreshAfterPersistedMutation(spec: LanguageServerSpec): Boolean = isElp(spec) || isVue(spec) || isNim(spec) || isCMake(spec) || isSvelte(spec) || isPowerShell(spec) || isSqruff(spec) || isPrisma(spec) || isClojure(spec) || isMake(spec) || isMarksman(spec) || isTerraform(spec) || isBuf(spec) || isZls(spec) || isLemMinx(spec)

    fun isTexlab(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == "texlab"

    fun isMarksman(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == "marksman"
    fun isPerl(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == "perlnavigator"
    private fun isVim(spec: LanguageServerSpec): Boolean = spec.source == "built-in" && spec.id == "vim-language-server"

    fun requiresProjectAnalysis(spec: LanguageServerSpec, path: String): Boolean =
        spec.id == LspMdxProfile.ID || "mdx" in spec.languageIds || "mdx" in spec.extensions || path.endsWith(".mdx", ignoreCase = true) ||
        LspSolidityProfile.isRoute(spec, path) || LspScalaProfile.isRoute(spec, path) || LspAsmProfile.isRoute(spec, path) || LspElixirProfile.isRoute(spec, path) || LspHaskellProfile.isRoute(spec, path) || LspRProfile.isRoute(spec, path) || isElpRoute(spec, path) || isGraphqlRoute(spec, path) || isTomlRoute(spec, path) || isCSharpRoute(spec, path) || isVueRoute(spec, path) || isNimRoute(spec, path) || isCMakeRoute(spec, path) || isSvelteRoute(spec, path) || isPowerShellRoute(spec, path) || isSqlRoute(spec, path) || isPrismaRoute(spec, path) || isClojureRoute(spec) || LspClojureProfile.supportsPath(path) ||
            spec.id == "perlnavigator" || "perl" in spec.languageIds ||
            spec.extensions.any { it in setOf("pl", "pm") } || LanguageRegistry.forFile(path)?.id == "perl" ||
            spec.id == "texlab" || spec.languageIds.any { it in setOf("latex", "tex", "bibtex", "bib") } ||
            spec.extensions.any { it in texlabExtensions } || LanguageRegistry.forFile(path)?.id == "latex" ||
            spec.id == "terraform-ls" || spec.languageIds.any { it in setOf("terraform", "terraform-vars") } ||
            spec.extensions.any { it in setOf("tf", "tfvars") } || LanguageRegistry.forFile(path)?.id == "terraform" ||
            spec.id == "buf" || "proto" in spec.languageIds || "proto" in spec.extensions || LanguageRegistry.forFile(path)?.id == "proto" ||
            spec.id == "zls" || "zig" in spec.languageIds || spec.extensions.any { it in setOf("zig", "zon") } || LanguageRegistry.forFile(path)?.id == "zig" ||
            spec.id == "lemminx" || spec.languageIds.any { it in setOf("xml", "dtd") } ||
            spec.extensions.any { it in LspLemMinxProfile.extensions } || LanguageRegistry.forFile(path)?.id == "xml" ||
            spec.id == "make-ls" || "makefile" in spec.languageIds || spec.extensions.any { it in LspMakeProfile.extensions } ||
            spec.filenames.any { it in LspMakeProfile.filenames } || LanguageRegistry.forFile(path)?.id == "makefile"

    private fun isPrismaRoute(spec: LanguageServerSpec, path: String? = null): Boolean =
        (path != null && (LspPrismaProfile.supportsPath(path) || LanguageRegistry.forFile(path)?.id == "prisma")) ||
            spec.id == LspPrismaProfile.ID || "prisma" in spec.languageIds || "prisma" in spec.extensions

    private fun isClojureRoute(spec: LanguageServerSpec, path: String? = null): Boolean =
        (path != null && LspClojureProfile.supportsPath(path)) || spec.id == "clojure-lsp" || "clojure" in spec.languageIds || spec.extensions.any { it in LspClojureProfile.extensions }

    fun analysisConfiguration(spec: LanguageServerSpec, workspace: File? = null, project: File? = null): String = buildJsonObject {
        put("id", spec.id); put("source", spec.source)
        put("commands", buildJsonArray { spec.commands.forEach { argv -> add(buildJsonArray { argv.forEach(::add) }) } })
        put("languages", buildJsonArray { spec.languageIds.sorted().forEach(::add) })
        put("extensions", buildJsonArray { spec.extensions.sorted().forEach(::add) })
        put("filenames", buildJsonArray { spec.filenames.sorted().forEach(::add) })
        put("profile", when {
            LspMdxProfile.matches(spec) -> "mdx-managed-sdk-project-plugins-v1"
            isAsm(spec) -> LspAsmProfile.IDENTITY
            isSolidity(spec) -> LspSolidityProfile.IDENTITY
            isScala(spec) -> LspScalaProfile.IDENTITY
            isElixir(spec) -> LspElixirProfile.IDENTITY
            isHaskell(spec) -> LspHaskellProfile.IDENTITY
            isR(spec) -> LspRProfile.IDENTITY
            isElp(spec) -> LspElpProfile.IDENTITY
            isGraphql(spec) -> LspGraphqlProfile.IDENTITY
            isToml(spec) -> LspTomlProfile.IDENTITY
            isCSharp(spec) -> LspCSharpProfile.IDENTITY
            isVue(spec) -> LspVueProfile.IDENTITY
            isNim(spec) -> LspNimProfile.IDENTITY
            isCMake(spec) -> LspCMakeProfile.IDENTITY
            isSvelte(spec) -> LspSvelteProfile.IDENTITY
            isPowerShell(spec) -> LspPowerShellProfile.IDENTITY
            isSqruff(spec) -> LspSqruffProfile.IDENTITY
            isPrisma(spec) -> LspPrismaProfile.IDENTITY
            isClojure(spec) -> LspClojureProfile.IDENTITY
            isMake(spec) -> LspMakeProfile.IDENTITY
            isLemMinx(spec) -> "lemminx-0.31.2-trusted-xml-analysis-v1"
            isZls(spec) -> "zls-0.16.0-trusted-compiler-analysis-v1"
            isBuf(spec) -> "buf-1.74.0-trusted-protobuf-analysis-v1"
            isTerraform(spec) -> "terraform-ls-0.39.0-trusted-module-analysis-v1"
            isPerl(spec) -> "perlnavigator-0.8.20-full-analysis-v1"
            isTexlab(spec) -> "texlab-5.26.0-trusted-native-editing-v1"
            else -> "explicit-custom-server-v1"
        })
        if (isVue(spec)) {
            put("rootPolicy", LspVueProfile.ROOT_POLICY); put("sync", "complete-ledger-native-ack-v1")
            put("providers", "native-fixed-pair-eager32-signature-four-diagnostics-v1")
        }
        if (isNim(spec)) {
            put("configuration", LspNimProfile.configuration())
            put("startup", LspNimProfile.initializationOptions())
            put("rootPolicy", LspNimProfile.ROOT_POLICY)
            put("requestPolicy", LspNimProfile.REQUEST_POLICY)
            put("launcher", "nim-fixed-original-triple-node22233-atomic4096-v1")
            put("sync", "full-open-v1-change-v2-no-save-v1")
            if (project != null) put("selection", LspNimProfile.selectionIdentity(project))
        }
        if (isCMake(spec)) {
            put("selection", "explicit-mapped-source-build-revision-and-managed-identity-v1")
            put("codec", "cmake-utf32-scalar-v1")
            put("sync", "cmake-full-sync-pygls131-v1")
            put("completion", "cpython312-unicode150-word-v1")
            put("formatter", "DISABLED")
            put("owner", "cmake-editor-bounded-owner-v1")
        }
        if (isSvelte(spec)) {
            put("startup", LspSvelteProfile.initializationOptions())
            put("rootPolicy", LspSvelteProfile.ROOT_POLICY)
            put("companions", LspSvelteProfile.COMPANION_POLICY)
            put("watch", LspSvelteProfile.WATCH_POLICY)
            if (project != null) put("selection", LspSvelteProfile.selectionIdentity(project))
        }
        if (isPowerShell(spec)) {
            put("startup", LspPowerShellProfile.initializationOptions("<mapped-consented-project>"))
            put("launcher", LspPowerShellLaunchPolicy.editorIdentity)
            put("rootPolicy", LspPowerShellProfile.ROOT_POLICY)
            put("readiness", LspPowerShellProfile.READINESS_POLICY)
            put("featureRequests", "owner-bounded16-completion-active-latest-4s9s10s-caller-detach-v1")
            put("sync", "real-open-v1-full-change-v2-full-replacements-v1")
            put("rename", "current-grant-single-file-disclaimer-no-alias-v1")
        }
        if (isSqruff(spec)) {
            put("startup", buildJsonObject {})
            put("launcher", LspSqruffProfile.LAUNCH_POLICY_REVISION)
            put("rootPolicy", LspSqruffProfile.ROOT_POLICY)
            put("rootSelection", project?.let(LspSqruffProfile::selectionIdentity) ?: buildJsonObject { put("state", "unbound") })
            put("hostPolicy", "existing-ubuntu-user-home-env-native-project-cwd-config-v1")
            put("configuration", false)
            put("pushedSettings", false)
            put("dynamicRegistration", false)
            put("save", false)
            put("sourcePolicy", "managed-immutable-lf-wire-scalar-diagnostics-v1")
            put("formatting", "managed-exact-single-full-native-replacement-exclusive-retire-v1")
            put("provenance", "exact-managed-byte-identity-required-for-adapters-v1")
        }
        if (isPrisma(spec)) {
            put("startup", buildJsonObject {})
            put("launcher", LspPrismaProfile.LAUNCH_POLICY_REVISION)
            put("rootPolicy", LspPrismaProfile.ROOT_POLICY)
            put("rootSelection", project?.let(LspPrismaProfile::selectionIdentity) ?: buildJsonObject { put("state", "unbound") })
            put("hostPolicy", "existing-ubuntu-user-home-env-credentials-project-cwd-v1")
            put("configuration", false)
            put("pushedSettings", false)
            put("definitionLinkSupport", true)
            put("transport", LspPrismaProfile.TRANSPORT_POLICY)
            put("provenance", "command-v-unverified-no-managed-guarantees-by-name-v1")
            put("codeActions", "edit-only-no-non-null-command-v1")
        }
        if (isClojure(spec)) {
            put("startup", LspClojureProfile.initializationOptions())
            put("launcher", LspClojureProfile.LAUNCH_POLICY_REVISION)
            put("hostPolicy", "existing-ubuntu-user-home-env-config-credentials-v1")
            put("rootPolicy", LspClojureProfile.ROOT_POLICY)
            put("readinessClosePolicy", LspClojureProfile.READINESS_POLICY)
            put("codeActions", "edit-only-no-non-null-command-v1")
            put("formatting", "current-request-only-exact-single-full-document-sentinel-v1")
        }
        if (isMake(spec)) {
            put("startup", buildJsonObject {})
            put("hostPolicy", "existing-ubuntu-env-project-cwd-no-arguments-v1")
            put("rootPolicy", "nearest-real-vcs-directory-then-opened-workspace-with-absent-parent-barrier-v1")
            put("disabledProviders", buildJsonArray { add("hover"); add("documentSymbol"); add("formatting") })
            put("codeActions", "edit-only-no-non-null-command-v1")
        }
        if (isLemMinx(spec)) {
            put("startup", LspLemMinxProfile.initializationOptions())
            put("settings", LspLemMinxProfile.configuration())
            put("launcher", LspLemMinxLaunchPolicy.editorIdentity)
            put("rootPolicy", "nearest-real-vcs-directory-then-document-parent-with-directory-uri-v1")
        }
        if (isZls(spec)) {
            put("startup", LspZlsProfile.initializationOptions())
            put("launcher", LspZlsLaunchPolicy.editorIdentity)
            put("selection", "discovered-absolute-zig-stable-0.16.x-nearest-exact-hint-v1")
            put("hint", if (workspace != null && project != null) LspZlsProfile.hint(workspace, project).identity()
                else buildJsonObject { put("state", "unbound") })
        }
        if (isBuf(spec)) {
            put("startup", buildJsonObject {})
            put("hostPolicy", "existing-ubuntu-env-project-cwd-v1")
            put("rootPolicy", "nearest-typed-buf-config-or-vcs-then-document-directory-v1")
        }
        if (isTerraform(spec)) {
            put("startup", terraformInitializationOptions())
            put("hostPolicy", "existing-ubuntu-env-project-cwd-v1")
        }
        if (isSolidity(spec)) put("startup", LspSolidityProfile.initializationOptions())
        if (isScala(spec)) { put("settings", LspScalaProfile.configuration()); put("startup", LspScalaProfile.initializationOptions()) }
        if (isElixir(spec)) put("settings", LspElixirProfile.configuration())
        if (isHaskell(spec)) {
            put("startup", LspHaskellProfile.settings())
            put("settings", LspHaskellProfile.configuration())
        }
        if (isTexlab(spec)) {
            put("startup", buildJsonObject { put("skipDistro", false) })
            put("settings", requireNotNull(configurationAfterInitialize(spec, null)))
            put("runtimeLauncher", LspTexlabLaunchPolicy.editorIdentity)
        }
    }.toString()

    fun hasProjectRoots(spec: LanguageServerSpec): Boolean =
        isSolidity(spec) || isScala(spec) || isAsm(spec) || isElixir(spec) || isHaskell(spec) || isR(spec) || isVue(spec) || isNim(spec) || isCMake(spec) || isSvelte(spec) || isPowerShell(spec) || isSqruff(spec) || isPrisma(spec) || isClojure(spec) || isVim(spec) || isPerl(spec) || isMarksman(spec) || isTexlab(spec) || isTerraform(spec) || isBuf(spec) || isZls(spec) || isLemMinx(spec) || isMake(spec)

    fun projectRoot(spec: LanguageServerSpec, workspace: File, document: File): File {
        if (isSolidity(spec)) return LspSolidityProfile.projectRoot(workspace, document)
        if (isScala(spec)) return LspScalaProfile.projectRoot(workspace, document)
        if (isAsm(spec)) return LspAsmProfile.projectRoot(workspace, document)
        if (isElixir(spec)) return LspElixirProfile.projectRoot(workspace, document)
        if (isHaskell(spec)) return LspHaskellProfile.projectRoot(workspace, document)
        if (isR(spec)) return LspRProfile.projectRoot(workspace, document)
        // The manager supplies its committed CMake selection. This fallback is an
        // inert document identity only and cannot select or authorize a build.
        if (isVue(spec)) return LspVueProfile.projectRoot(workspace, document)
        if (isNim(spec)) return LspNimProfile.projectRoot(workspace, document)
        if (isCMake(spec)) return document.canonicalFile.parentFile ?: workspace.canonicalFile
        if (isSvelte(spec)) return LspSvelteProfile.projectRoot(workspace, document)
        if (isSqruff(spec)) return LspSqruffProfile.projectRoot(workspace, document)
        if (isPrisma(spec)) return LspPrismaProfile.projectRoot(workspace, document)
        val boundary = workspace.canonicalFile
        val file = document.canonicalFile
        require(PathSecurity.contains(boundary, file)) { "Language-server document escaped the workspace" }
        if (!hasProjectRoots(spec)) return boundary
        val directory = file.parentFile ?: boundary
        // A dirty editor tab can outlive its on-disk directory. Keep its exact
        // identity available for status/revoke/close; admission checks availability.
        if (isTerraform(spec)) return directory
        // Do not route a deleted nested project through a pre-approved ancestor.
        if ((isPowerShell(spec) || isMake(spec) || isClojure(spec)) && !Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) return directory
        var candidate = directory
        while (PathSecurity.contains(boundary, candidate)) {
            if (if (isClojure(spec)) LspClojureProfile.projectMarker(candidate)
                else if (isPowerShell(spec) || isLemMinx(spec) || isMake(spec)) listOf(".git", ".hg", ".svn", ".jj").any { Files.isDirectory(File(candidate, it).toPath(), NOFOLLOW_LINKS) }
                else if (isZls(spec)) LspZlsProfile.projectMarker(candidate)
                else if (isBuf(spec)) bufProject(candidate)
                else if (isMarksman(spec)) marksmanProject(candidate)
                else if (isTexlab(spec)) texlabMarkers.any {
                    Files.isRegularFile(File(candidate, it).toPath(), NOFOLLOW_LINKS)
                } || Files.isDirectory(File(candidate, ".git").toPath(), NOFOLLOW_LINKS)
                else markers(candidate, if (isVim(spec)) vimMarkers else perlMarkers).isNotEmpty()) return candidate
            if (candidate == boundary) break
            candidate = candidate.parentFile ?: break
        }
        // Vim's released server ignores rootUri and falls back to the document's
        // parent. Match that actual contract, with '.' preventing ancestor search.
        return if (isVim(spec) || isBuf(spec) || isZls(spec) || isLemMinx(spec)) directory else boundary
    }

    fun projectAvailable(spec: LanguageServerSpec, project: File): Boolean =
        if (isAsm(spec)) LspAsmProfile.projectAvailable(project) else
        if (isSolidity(spec)) LspSolidityProfile.projectAvailable(project) else
        if (isScala(spec)) LspScalaProfile.projectAvailable(project) else
        if (isElixir(spec)) LspElixirProfile.projectAvailable(project) else
        if (isHaskell(spec)) LspHaskellProfile.projectAvailable(project) else
        if (isR(spec)) LspRProfile.projectAvailable(project) else
        if (isVue(spec)) LspVueProfile.projectAvailable(project) else
        if (isNim(spec)) LspNimProfile.projectAvailable(project) else
        if (isCMake(spec)) Files.isDirectory(project.toPath(), NOFOLLOW_LINKS) else
        if (isSvelte(spec)) LspSvelteProfile.projectAvailable(project) else
        if (isSqruff(spec)) LspSqruffProfile.projectAvailable(project) else
        if (isPrisma(spec)) LspPrismaProfile.projectAvailable(project) else
        (!isPowerShell(spec) && !isClojure(spec) && !isTerraform(spec) && !isBuf(spec) && !isZls(spec) && !isLemMinx(spec) && !isMake(spec)) || Files.isDirectory(project.toPath(), NOFOLLOW_LINKS)

    fun unavailableProjectMessage(spec: LanguageServerSpec): String =
        if (isAsm(spec)) "Assembly workspace directory is unavailable" else
        if (isSolidity(spec)) "Solidity workspace directory is unavailable" else
        if (isScala(spec)) "Scala workspace directory is unavailable" else
        if (isElixir(spec)) "Elixir workspace directory is unavailable" else
        if (isHaskell(spec)) "Haskell workspace directory is unavailable" else
        if (isR(spec)) "R opened workspace directory is unavailable" else
        if (isVue(spec)) "Vue exact opened workspace or document parent is unavailable" else
        if (isNim(spec)) "Nim fixed-toolchain analysis requires an available project directory with exactly one regular root Nimble manifest" else
        if (isCMake(spec)) "CMake source/build selection or managed runtime is unavailable" else
        if (isSvelte(spec)) "Svelte project directory or typed configuration marker is unavailable" else
        if (isPowerShell(spec)) "PowerShell project directory is unavailable" else
        if (isSqruff(spec)) "SQL project directory or configuration boundary is unavailable" else
        if (isPrisma(spec)) "Prisma project directory or configuration boundary is unavailable" else if (isClojure(spec)) "Clojure project directory is unavailable" else if (isMake(spec)) "Make project directory is unavailable" else if (isLemMinx(spec)) "XML project directory is unavailable" else if (isZls(spec)) "Zig project directory is unavailable" else if (isBuf(spec)) "Buf project directory is unavailable" else "Terraform module directory is unavailable"

    /** A client grant/session root, not a replica of Buf's controlling workspace search. */
    private fun bufProject(root: File): Boolean =
        listOf("buf.yaml", "buf.work.yaml").any { Files.isRegularFile(File(root, it).toPath(), NOFOLLOW_LINKS) } ||
            listOf(".git", ".hg", ".svn", ".jj").any { Files.isDirectory(File(root, it).toPath(), NOFOLLOW_LINKS) }

    /** Exact upstream root types: a TOML file or a VCS directory, never a symlink. */
    private fun marksmanProject(root: File): Boolean =
        Files.isRegularFile(File(root, ".marksman.toml").toPath(), NOFOLLOW_LINKS) ||
            listOf(".git", ".hg", ".svn", ".jj").any {
                Files.isDirectory(File(root, it).toPath(), NOFOLLOW_LINKS)
            }

    // Empty workspaceFolders deliberately prevents fallback to rootUri in Marksman.
    // Each didOpen then creates a singleton document without scanning the workspace.
    fun publishesWorkspaceRoot(spec: LanguageServerSpec, project: File): Boolean =
        !isMarksman(spec) || marksmanProject(project)

    private fun terraformInitializationOptions(): JsonObject = buildJsonObject {
        put("terraform", buildJsonObject { put("timeout", "30s") })
        put("experimentalFeatures", buildJsonObject { put("validateOnSave", false) })
        put("validation", buildJsonObject { put("enableEnhancedValidation", true) })
    }

    fun initializationOptions(spec: LanguageServerSpec, project: File): JsonObject = buildJsonObject {
        if (isSolidity(spec)) LspSolidityProfile.initializationOptions().forEach { (key, value) -> put(key, value) }
        if (isScala(spec)) LspScalaProfile.initializationOptions().forEach { (key, value) -> put(key, value) }
        if (isHaskell(spec)) LspHaskellProfile.settings().forEach { (key, value) -> put(key, value) }
        if (isNim(spec)) LspNimProfile.initializationOptions().forEach { (key, value) -> put(key, value) }
        if (isSvelte(spec)) LspSvelteProfile.initializationOptions().forEach { (key, value) -> put(key, value) }
        if (isPowerShell(spec)) LspPowerShellProfile.initializationOptions(project.path).forEach { (key, value) -> put(key, value) }
        if (isClojure(spec)) LspClojureProfile.initializationOptions().forEach { (key, value) -> put(key, value) }
        if (isLemMinx(spec)) LspLemMinxProfile.initializationOptions().forEach { (key, value) -> put(key, value) }
        if (isZls(spec)) LspZlsProfile.initializationOptions().forEach { (key, value) -> put(key, value) }
        if (isTerraform(spec)) terraformInitializationOptions().forEach { (key, value) -> put(key, value) }
        // Full distribution lookup is allowed only after the explicit project grant.
        // This flag cannot prevent latexmkrc execution, hence the independent gate.
        if (isTexlab(spec)) put("skipDistro", false)
        if (isVim(spec)) {
            put("isNeovim", false)
            put("iskeyword", "")
            put("runtimepath", "")
            put("vimruntime", "")
            put("diagnostic", buildJsonObject { put("enable", true) })
            put("suggest", buildJsonObject {
                put("fromRuntimepath", false)
                put("fromVimruntime", false)
            })
            put("indexes", buildJsonObject {
                // 2.3.1 ignores this false flag. Empty runtime paths and disabled
                // runtime suggestions above are the actual bounding controls.
                put("runtimepath", false)
                put("gap", 100)
                put("count", 1)
                put("projectRootPatterns", buildJsonArray {
                    markers(project, vimMarkers).ifEmpty { listOf(".") }.forEach(::add)
                })
            })
        }
    }

    fun clientCapabilities(spec: LanguageServerSpec): JsonObject {
        val base = LspProtocolContract.clientCapabilities()
        if (isAsm(spec)) return LspAsmProfile.clientCapabilities(base)
        if (isSolidity(spec)) return LspSolidityProfile.clientCapabilities(base)
        if (isScala(spec)) return LspScalaProfile.clientCapabilities(base)
        if (isElixir(spec)) return LspElixirProfile.clientCapabilities(base)
        if (isHaskell(spec)) return LspHaskellProfile.clientCapabilities(base)
        if (isR(spec)) return LspRProfile.clientCapabilities(base)
        if (isVue(spec)) return LspVueProfile.clientCapabilities(base)
        if (isNim(spec)) return LspNimProfile.clientCapabilities(base)
        if (isCMake(spec)) return LspCMakeProfile.clientCapabilities(base)
        if (isSvelte(spec)) return LspSvelteProfile.clientCapabilities(base)
        if (isPowerShell(spec)) return LspPowerShellProfile.clientCapabilities(base)
        if (isSqruff(spec)) {
            val documents = base.getValue("textDocument").jsonObject
            val sync = JsonObject(documents.getValue("synchronization").jsonObject + ("didSave" to JsonPrimitive(false)))
            return JsonObject(base + ("workspace" to JsonObject(base.getValue("workspace").jsonObject + mapOf(
                "configuration" to JsonPrimitive(false),
                "didChangeConfiguration" to buildJsonObject { put("dynamicRegistration", false) },
                "didChangeWatchedFiles" to buildJsonObject { put("dynamicRegistration", false) },
            ))) + ("textDocument" to JsonObject(documents + ("synchronization" to sync))))
        }
        if (isPrisma(spec)) return LspPrismaProfile.clientCapabilities(base)
        if (isClojure(spec)) return LspClojureProfile.clientCapabilities(base)
        if (isLemMinx(spec)) return JsonObject(base + ("workspace" to JsonObject(base.getValue("workspace").jsonObject + mapOf(
            "configuration" to JsonPrimitive(true),
            "didChangeConfiguration" to buildJsonObject { put("dynamicRegistration", false) },
            "didChangeWatchedFiles" to buildJsonObject { put("dynamicRegistration", false) },
        ))))
        if (!isPerl(spec) && !isTexlab(spec) && !isTerraform(spec) && !isBuf(spec) && !isZls(spec) && !isMake(spec)) return base
        val workspace = base.getValue("workspace").jsonObject + ("configuration" to JsonPrimitive(false))
        val configured = if (isTerraform(spec) || isBuf(spec) || isZls(spec) || isLemMinx(spec) || isMake(spec)) workspace + ("didChangeWatchedFiles" to buildJsonObject {
            put("dynamicRegistration", false)
        }) else if (isTexlab(spec)) workspace + ("didChangeConfiguration" to buildJsonObject {
            put("dynamicRegistration", false)
        }) else workspace
        return JsonObject(base + ("workspace" to JsonObject(configured)))
    }

    fun configurationAfterInitialize(spec: LanguageServerSpec, perlPath: String?): JsonObject? {
        if (isScala(spec)) return LspScalaProfile.configuration()
        if (isElixir(spec)) return LspElixirProfile.configuration()
        if (isHaskell(spec)) return LspHaskellProfile.configuration()
        if (isLemMinx(spec)) return LspLemMinxProfile.configuration()
        if (isTexlab(spec)) return buildJsonObject {
            put("settings", buildJsonObject { put("texlab", buildJsonObject {
                put("build", buildJsonObject {
                    put("onSave", false); put("forwardSearchAfter", false); put("useFileList", false)
                })
                put("chktex", buildJsonObject { put("onOpenAndSave", false); put("onEdit", false) })
                put("latexFormatter", "latexindent")
                put("bibtexFormatter", "texlab")
                put("diagnosticsDelay", 300)
            }) })
        }
        if (!isPerl(spec)) return null
        require(!perlPath.isNullOrBlank() && perlPath.startsWith("/")) { "Install a Perl interpreter in local Ubuntu before enabling PerlNavigator." }
        return buildJsonObject {
            put("settings", buildJsonObject {
                put("perlnavigator", buildJsonObject {
                    put("perlPath", perlPath)
                    put("perlParams", buildJsonArray {})
                    put("includePaths", buildJsonArray {})
                    put("includeLib", true)
                    put("enableWarnings", true)
                    put("perlCompileEnabled", true)
                    put("perlcriticEnabled", true)
                    put("perltidyEnabled", true)
                    put("perlimportsLintEnabled", false)
                    put("perlimportsTidyEnabled", false)
                    put("logging", false)
                    put("enableProgress", false)
                })
            })
        }
    }

    fun requireSupportedDocumentPath(spec: LanguageServerSpec, mappedPath: String) {
        if (!isPerl(spec)) return
        require(mappedPath.none { it == '\'' || it == '"' || it == '\\' || it.code < 32 || it.code == 127 }) {
            "PerlNavigator 0.8.20 cannot safely analyze paths containing quotes, backslashes or control characters. Move this project/file to a supported path before enabling analysis."
        }
    }

    fun installationDisclosure(spec: LanguageServerSpec, path: String? = null): String? =
        if (isElp(spec)) LspElpProfile.INSTALLATION_DISCLOSURE else
        if (isToml(spec)) LspTomlProfile.INSTALLATION_DISCLOSURE else
        if (isVue(spec)) LspVueProfile.INSTALLATION_DISCLOSURE else
        if (isNim(spec)) LspNimProfile.INSTALLATION_DISCLOSURE else
        if (isNimRoute(spec, path)) "This custom Nim declaration does not install tools or inherit the managed fixed-toolchain guarantees. Its executable and dependencies must be provided separately." else
        if (isCMakeRoute(spec, path)) LspCMakeProfile.INSTALLATION_DISCLOSURE else
        if (isSvelteRoute(spec, path)) LspSvelteProfile.INSTALLATION_DISCLOSURE else
        if (isPowerShellRoute(spec, path)) LspPowerShellProfile.INSTALLATION_DISCLOSURE else
        if (isSqlRoute(spec, path)) LspSqruffProfile.INSTALLATION_DISCLOSURE else
        if (isPrismaRoute(spec, path)) LspPrismaProfile.INSTALLATION_DISCLOSURE
        else if (isClojureRoute(spec, path)) LspClojureProfile.INSTALLATION_DISCLOSURE else null

    fun approvalExplanation(spec: LanguageServerSpec, project: File? = null, path: String? = null): String = when {
        LspMdxProfile.matches(spec) -> "MDX analysis reads the project TypeScript configuration and may execute its remark or virtual-code plugins. Enable only for a project you trust; Revoke stops the analysis session."
        isAsm(spec) -> LspAsmProfile.APPROVAL_EXPLANATION
        isSolidity(spec) -> LspSolidityProfile.APPROVAL_EXPLANATION
        isScala(spec) -> LspScalaProfile.APPROVAL_EXPLANATION
        isElixir(spec) -> LspElixirProfile.APPROVAL_EXPLANATION
        isHaskell(spec) -> LspHaskellProfile.APPROVAL_EXPLANATION
        isR(spec) -> LspRProfile.APPROVAL_EXPLANATION
        isElp(spec) -> LspElpProfile.APPROVAL_EXPLANATION
        isElpRoute(spec, path) -> "Run this unverified custom Erlang/ELP command under its own project execution grant. Native managed ELP runtime, UTF-16, ownership and diagnostic-origin guarantees do not apply."
        isGraphql(spec) -> LspGraphqlProfile.APPROVAL_EXPLANATION
        isGraphqlRoute(spec, path) -> "Run this custom GraphQL command under its own project execution grant. Its project/configuration, file, network and process behavior is unverified; managed local-schema snapshot and package guarantees do not apply."
        isToml(spec) -> LspTomlProfile.APPROVAL_EXPLANATION
        isTomlRoute(spec, path) -> "Run this custom TOML command under its own project execution grant. Its file, network, configuration and process behavior are unverified. The managed local snapshot, offline-fetch, cursor and package guarantees do not apply."
        isCSharp(spec) -> LspCSharpProfile.APPROVAL_EXPLANATION
        isCSharpRoute(spec, path) -> "Run this custom C# command under its own grant. Its project loading may execute MSBuild targets, SDK resolvers, source generators, credential providers or other project/configuration code; read and write outside the selected folder; and use network access and credentials. Its runtime, synchronization, provenance and capabilities are unverified. The managed C# selection, owner and result guarantees do not apply. Revoke cannot undo file/network effects."
        isVue(spec) -> LspVueProfile.APPROVAL_EXPLANATION
        isVueRoute(spec, path) -> "Run this custom Vue command with trusted project/configuration effects under its own grant. Managed paired synchronization, provenance and native feature guarantees do not apply."
        isNimRoute(spec, path) -> if (isNim(spec)) LspNimProfile.APPROVAL_EXPLANATION else
            "This custom Nim declaration may execute compiler, Nimble, project, dependency or configuration code with the Ubuntu user's file and network access. Its compiler selection, metadata handling, provenance and process ownership are unverified. The fixed-toolchain managed profile does not apply. Revoke cannot undo file or network effects."
        isCMakeRoute(spec, path) -> LspCMakeProfile.APPROVAL_EXPLANATION +
            (if (isCMake(spec)) " Exact managed runtime and selected source/build authority are required." else " This custom declaration has unverified generic behavior; managed coordinate, private-state and process-owner guarantees do not apply.")
        isSvelteRoute(spec, path) -> LspSvelteProfile.APPROVAL_EXPLANATION + (if (isSvelte(spec)) "" else " This custom declaration has unverified provenance and generic behavior; no companion/watcher guarantee applies.")
        isPowerShellRoute(spec, path) -> if (isPowerShell(spec))
            LspPowerShellProfile.APPROVAL_EXPLANATION + " Exact managed runtime/server launch proof is required before startup."
            else LspPowerShellProfile.CUSTOM_APPROVAL_EXPLANATION
        isSqlRoute(spec, path) -> LspSqruffProfile.APPROVAL_EXPLANATION +
            (if (isSqruff(spec)) " Managed pin proof is required for sqruff coordinate and formatting repairs."
            else " This is a custom SQL route; capabilities, build features and provenance are unverified. Built-in managed identity, LF/scalar conversion and formatting repair do not apply. Review the exact command and origin.")
        isPrismaRoute(spec, path) -> LspPrismaProfile.APPROVAL_EXPLANATION +
            (if (isPrisma(spec)) " This built-in client profile is limited to legacy schemas; Next/ORM 8 documents are unavailable, and Prisma 6/mixed-generation disk schemas remain unverified."
            else " This is a custom Prisma route; generation, semantics, provenance and client feature compatibility are unverified. The built-in legacy generation filter, managed pin/prelude and health guarantees do not apply. Review its exact command and origin.")
        isClojureRoute(spec, path) -> LspClojureProfile.APPROVAL_EXPLANATION +
            (if (isClojure(spec)) "" else " This is a custom Clojure route; built-in launch, readiness and compatibility guarantees do not apply. Review its exact command and origin.") +
            (project?.let { LspClojureProfile.toolHints(it) }?.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = " Matching standard tool hints: ", separator = "; ", postfix = ".") ?: "") +
            " All matching standard specs may run; configured project-specs can replace these hints. Tool versions and compatibility have not been checked."
        isMake(spec) -> "Make LS recursively reads local project/include files and uses open Make buffers. The reviewed parser treats recipes and shell expressions as text; it does not run Make, compilers or project commands or request network resources. Include metadata probes may reach outside this folder, file traversal is not race-proof, and there is no aggregate/depth limit; a special file can block native reads. This root is not a filesystem sandbox. Known IDE writes restart indexes on the next supported request. A discovered executable has unverified provenance and may behave differently; review its origin before enabling it."
        isLemMinx(spec) -> "LemMinX reads XML/schema/catalog references, including local files outside this folder, and downloads referenced remote resources after this grant. Requests expose referenced URLs/identifiers and ordinary network metadata to resource servers; caches are written. Entity expansion and XInclude are disabled, but other grammar/catalog reads and network access are not sandboxed or bounded by a download quota. The managed wrapper uses private per-process state; discovered or custom executables have unverified provenance and may execute code. Known IDE writes retire indexes; the next supported request restarts analysis and may fetch resources again."
        isZls(spec) -> "ZLS runs the selected Zig compiler and evaluates build.zig, dependencies, generated commands, pkg-config and C translation, including reachable ancestor build files outside this folder. This may read/write files or access the network using guest HOME/configuration. Compiler/kernel checks and analysis start only after this grant. Known IDE saves restart analysis and may execute check-only build steps again; no check step means no build-on-save diagnostic guarantee. This root is a client session scope, not a sandbox. No compiler is installed, upgraded or silently substituted."
        isBuf(spec) -> "Buf automatically analyzes and lints open/changed Proto files using discovered configuration, including ancestor buf.work.yaml/v2 workspaces outside this selected folder. Configured native plugins can execute local programs; modules, Wasm plugins and policies may be fetched over the network using guest configuration/credentials. Project code may read/write files or use network access. Local imports, ancestor configuration and symlinks are not confined by this root. Known IDE saves/mutations restart indexes under this grant and may repeat checks/fetches. Dependency updates and generation commands are not automatically invoked."
        isTerraform(spec) -> "Terraform LS may execute the guest Terraform CLI and project-selected provider binaries automatically during initialization/indexing. Tools can access guest HOME/configuration/credentials, modify files or use the network. Local modules may be read outside this folder. Public Terraform Registry requests can include module identifiers (including private identifiers), and Algolia source completion can receive typed source text. Known IDE saves/mutations restart indexes under this grant and may repeat that work. No automatic init, plan, apply or deployment is added."
        isPerl(spec) -> "Analysis may compile Perl files, run BEGIN blocks, load project/interpreter modules and execute configuration or optional analysis tools."
        isTexlab(spec) -> "Texlab can execute project latexmkrc files automatically while indexing, before any build. Distribution discovery and explicit formatting can run local tools. Linked files may be read outside this folder. Tools and project configuration may write/delete files or use the network. Automatic build, ChkTeX and viewer launch stay off in this profile."
        else -> "This custom server and its configuration can execute project code and local tools while analyzing files. Review the exact command before enabling it."
    }

    fun enabledNote(spec: LanguageServerSpec): String = when {
        isAsm(spec) -> "Preparing assembly analysis. " + LspAsmProfile.INSTALL_NOTE
        isSolidity(spec) -> "Preparing Solidity project indexing. " + LspSolidityProfile.INSTALL_NOTE
        isScala(spec) -> "Preparing Scala analysis. " + LspScalaProfile.INSTALL_NOTE
        isElixir(spec) -> "Preparing Elixir analysis. " + LspElixirProfile.INSTALL_NOTE
        isHaskell(spec) -> "Preparing Haskell analysis. " + LspHaskellProfile.INSTALL_NOTE
        isR(spec) -> "Preparing the selected R language server; installation and analysis results are not certified. " + LspRProfile.INSTALL_NOTE
        isToml(spec) -> "Taplo local snapshot analysis enabled. " + LspTomlProfile.FEATURE_NOTE
        isElp(spec) -> LspElpProfile.FEATURE_NOTE
        isElpRoute(spec) -> "Custom Erlang server; behavior and provenance unverified."
        isGraphql(spec) -> LspGraphqlProfile.FEATURE_NOTE
        isGraphqlRoute(spec) -> "Custom GraphQL command; managed local-schema guarantees do not apply."
        isTomlRoute(spec) -> "Custom TOML command enabled; behavior and provenance remain unverified."
        isCSharp(spec) -> "Preparing native C# model; project completeness remains unverified."
        isCSharpRoute(spec) -> "Custom C# command enabled under its own grant; generic semantics and provenance remain unverified."
        isVue(spec) -> "Preparing the owned Vue native pair; Revoke stops it. " + LspVueProfile.FEATURE_NOTE
        isNim(spec) -> "Preparing explicit Nim 2.2.12 fixed-toolchain analysis with nimlangserver 1.16.0. Restricted metadata tools and existing dependencies are required; graph compatibility remains unknown."
        isNimRoute(spec) -> "The custom Nim route has generic behavior and unverified compiler selection and provenance."
        isCMake(spec) -> "CMake completion/hover is enabled for the selected source/build. Protocol and project-query outcome remain unverified until demand; no configure or tool was started by this enable action."
        isCMakeRoute(spec) -> "The custom CMake declaration has generic behavior and unverified provenance; managed coordinate and owned-build guarantees do not apply."
        isSvelte(spec) -> "Preparing the managed Svelte companion/observation adapter; Revoke stops it. Native semantics remain unverified."
        isSvelteRoute(spec) -> "The custom Svelte route has generic unverified behavior without the managed companion or watcher adapter."
        isPowerShell(spec) -> "Preparing PowerShell analysis under the current grant. Profiles and modules may execute; Revoke stops the owned session. Native analysis success remains unverified."
        isPowerShellRoute(spec) -> "The custom PowerShell route has unverified host/prompt semantics, provenance and capabilities; built-in adapters do not apply."
        isSqruff(spec) -> "SQL native-config analysis is enabled; this action did not start or install tools. Exact managed identity and semantic readiness remain to be checked. Unversioned lint may remain pending."
        isSqlRoute(spec) -> "The custom SQL route's capabilities, build features and provenance are unverified; this action did not start or install tools."
        isPrisma(spec) -> "Legacy-schema analysis is enabled; executable/config/runtime and semantic readiness are unverified. Next/ORM 8 remains unavailable. This action did not start or install tools."
        isPrismaRoute(spec) -> "The custom Prisma server's generation, capabilities, provenance and semantic readiness are unverified. This action did not start or install tools."
        isClojure(spec) -> "Preparing Clojure project analysis. First indexing may take several minutes; Revoke stops it. Project-tool compatibility and dependency success remain unverified."
        isClojureRoute(spec) -> "The custom Clojure server's capabilities, project-tool compatibility and dependency success remain unverified."
        isMake(spec) -> "Parser/runtime readiness and executable provenance remain unverified; this action did not start or install tools. Hover, document symbols and formatting are unavailable for this pin."
        isLemMinx(spec) -> "Java/JAR, schema and network readiness remain unverified; this action did not start or install tools."
        isZls(spec) -> "Node, selected Zig 0.16.x, kernel and build readiness remain unverified; this action did not start or install them."
        isBuf(spec) -> "Configured plugins/dependencies remain unverified; no package installation or analysis was started by this action."
        isTerraform(spec) -> "Optional Terraform/provider tools remain unverified; no package installation or analysis was started by this action."
        isPerl(spec) -> "Optional Perl modules remain unverified; no package installation or analysis was started by this action."
        isTexlab(spec) -> "Optional TeX and formatting tools remain unverified; no package installation or analysis was started by this action."
        else -> "No package installation or analysis was started by this action; the custom server's capabilities are unverified."
    }

    fun runningToolNote(spec: LanguageServerSpec): String = when {
        isAsm(spec) -> " · asm-lsp connected; bounded editor feature profile"
        isSolidity(spec) -> " · Solidity server connected; validation may continue"
        isScala(spec) -> " · Metals connected; build import/indexing may continue"
        isElixir(spec) -> " · ElixirLS connected; project compilation may continue"
        isHaskell(spec) -> " · HLS connected; project loading may continue"
        isR(spec) -> " · R protocol connected; optional packages and complete project analysis remain unverified"
        isToml(spec) -> " · native local TOML assistance; unversioned advisory diagnostics"
        isElp(spec) -> " · native ELP; compilation health unverified"
        isGraphql(spec) -> " · GraphQL local SDL schema"
        isGraphqlRoute(spec) -> " · custom GraphQL command; unverified behavior"
        isTomlRoute(spec) -> " · custom TOML command; generic behavior and provenance unverified"
        isCSharp(spec) -> " · native current-document results; project completeness unverified"
        isCSharpRoute(spec) -> " · custom C# command; generic behavior and provenance unverified"
        isVue(spec) -> " · native pair; bounded capture and observation; external/transitive coverage qualified"
        isNim(spec) -> " · metadata admitted and buffers replayed; compiler/backend analysis and transitive compatibility remain unverified"
        isNimRoute(spec) -> " · generic custom Nim; compiler selection, project behavior and provenance unverified"
        isCMake(spec) -> " · protocol connected; project refresh outcome unverified; completion/hover only; save refreshes on next demand"
        isCMakeRoute(spec) -> " · generic custom CMake; coordinates, provenance and project behavior unverified"
        isSvelte(spec) -> " · captured companions and bounded observed disk; native semantics and external-change completeness unverified"
        isSvelteRoute(spec) -> " · generic custom Svelte; companion/watch integration and provenance unverified"
        isPowerShell(spec) -> " · current-buffer replay ready; asynchronous analysis/profile success unverified; best-effort single-file rename"
        isPowerShellRoute(spec) -> " · custom PowerShell host, prompt behavior and provenance unverified"
        isSqruff(spec) -> " · native lint/tokens/full formatting only; unversioned warnings and pending results do not certify validity; edits require restart on demand"
        isUnverifiedSqruff(spec) -> " · unverified custom sqruff; formatting unavailable because the native range defect has no managed identity proof"
        isSqlRoute(spec) -> " · custom SQL features and executable provenance unverified; no managed sqruff coordinate/format repairs"
        isPrisma(spec) -> " · legacy schemas only; config/semantic readiness and executable provenance unverified"
        isPrismaRoute(spec) -> " · custom Prisma generation, semantics and executable provenance unverified"
        isClojureRoute(spec) -> " · project tools/dependencies and executable provenance unverified; commands/archive navigation unavailable"
        isMake(spec) -> " · hover/symbols/formatting unavailable; heuristic unversioned diagnostics and executable provenance unverified"
        isLemMinx(spec) -> " · schema/network and executable provenance unverified; IDE writes revalidate on next request"
        isZls(spec) -> " · compiler/build readiness and provenance unverified; persisted saves restart analysis"
        isBuf(spec) -> " · configured plugins/dependencies unverified; empty diagnostics do not prove full lint; reopen after external changes"
        isTerraform(spec) -> " · optional Terraform/providers unverified; reopen after external changes"
        isPerl(spec) -> " · optional Perl modules unverified"
        isTexlab(spec) -> " · optional TeX/latexindent tools unverified; build/viewer UI unavailable"
        else -> ""
    }

    fun optionalToolNote(spec: LanguageServerSpec): String? = when {
        isAsm(spec) -> LspAsmProfile.FEATURE_NOTE + " " + LspAsmProfile.INSTALL_NOTE
        isSolidity(spec) -> LspSolidityProfile.FEATURE_NOTE + " " + LspSolidityProfile.INSTALL_NOTE
        isScala(spec) -> LspScalaProfile.FEATURE_NOTE + " " + LspScalaProfile.INSTALL_NOTE
        isElixir(spec) -> LspElixirProfile.FEATURE_NOTE + " " + LspElixirProfile.INSTALL_NOTE
        isHaskell(spec) -> LspHaskellProfile.FEATURE_NOTE + " " + LspHaskellProfile.INSTALL_NOTE
        isR(spec) -> LspRProfile.FEATURE_NOTE + " " + LspRProfile.INSTALL_NOTE
        isToml(spec) -> LspTomlProfile.FEATURE_NOTE
        isElp(spec) -> LspElpProfile.FEATURE_NOTE
        isElpRoute(spec) -> "Custom Erlang server; behavior and provenance unverified."
        isGraphql(spec) -> LspGraphqlProfile.FEATURE_NOTE
        isGraphqlRoute(spec) -> "Custom GraphQL command; managed local-schema guarantees do not apply."
        isTomlRoute(spec) -> "Custom TOML command: generic behavior under its own grant; managed snapshot, offline and captured-result guarantees do not apply."
        isCSharp(spec) -> LspCSharpProfile.FEATURE_NOTE
        isCSharpRoute(spec) -> "Custom C# command: generic behavior under its own project execution grant; managed C# guarantees do not apply."
        isVue(spec) -> LspVueProfile.FEATURE_NOTE
        isVueRoute(spec) -> "Custom Vue command: generic behavior under its own grant; managed pairing and captured-companion guarantees do not apply."
        isNim(spec) -> LspNimProfile.FEATURE_NOTE
        isNimRoute(spec) -> "Custom Nim declarations retain generic behavior under their own current grant. Exact fixed compiler, refusing fallback, captured-target coordinate and owned-runtime guarantees do not apply."
        isCMake(spec) -> LspCMakeProfile.FEATURE_NOTE
        isCMakeRoute(spec) -> "Custom CMake declarations retain generic UTF-16 client behavior under their own current grant. Exact managed Python scalar conversion, native completion prefix and owned-build exclusion do not apply."
        isSvelte(spec) -> LspSvelteProfile.FEATURE_NOTE
        isSvelteRoute(spec) -> "Custom Svelte declarations use generic behavior under their own grant. Managed provenance, dirty TS/JS companion replay and real watcher coverage are not established."
        isPowerShell(spec) -> LspPowerShellProfile.FEATURE_NOTE
        isPowerShellRoute(spec) -> "Custom PowerShell declarations keep generic client behavior. Native prompt semantics, executable provenance and readiness are unverified; the exact built-in host, rename acknowledgement, completion, synchronization and semantic adapters do not apply."
        isSqruff(spec) -> LspSqruffProfile.FEATURE_NOTE
        isUnverifiedSqruff(spec) -> "Custom sqruff formatting is unavailable: its known full-replacement range defect cannot be safely repaired without exact managed identity. Coordinate assumptions and build features are unverified; generic diagnostics/tokens still use ordinary UTF-16 validation under project consent. A command basename is not provenance."
        isSqlRoute(spec) -> "Custom SQL route: project configuration and guest filesystem/network access require trust. Capabilities and build provenance are unverified. Built-in sqruff identity, LF/scalar conversion and malformed formatting-range repair do not apply; known sqruff formatting is unavailable without managed proof. No database adapter is added."
        isPrisma(spec) -> LspPrismaProfile.FEATURE_NOTE
        isPrismaRoute(spec) -> "Custom Prisma route: executable configuration and local/remote access require trust. Generation, semantics, executable/package/runtime provenance and client feature compatibility are unverified. Built-in legacy generation filtering, managed pin/prelude and zero-document health guarantees do not apply."
        isClojure(spec) -> LspClojureProfile.FEATURE_NOTE
        isClojureRoute(spec) -> "Custom Clojure route: full-project commands, hooks, dependency acquisition and writes require trust. Project-tool compatibility, dependency success, executable provenance and client feature compatibility are unverified. Built-in readiness, launch and formatting guarantees do not apply."
        isMake(spec) -> LspMakeProfile.FEATURE_NOTE
        isLemMinx(spec) -> "Managed recipe uses the exact original JAR, fixed Java 21 and Node with private per-process cache/home/tmp. Discovered executables are not attested as that wrapper. Remote grammar transfers have no reviewed byte/time quota; local references may escape this root. Unversioned diagnostics do not certify current schema validity. IDE writes retire indexes and the next supported request revalidates; external changes require reopen. Resource creation, server commands, code lens, links/folding/colors, completion-resolve and build/run UI are not integrated."
        isZls(spec) -> "Requires Node, a separately selected stable Zig 0.16.x compiler and Linux 5.10+. A nearest .zigversion is an exact pin. Discovery/version responses are compatibility evidence, not payload attestation. Build graph, dependencies and check-step readiness remain unverified; ZON has a smaller feature subset. IDE writes restart analysis under the current grant; external changes require reopen. A queued save may redundantly trigger checking without rolling back editor text. No target-kernel, full comptime analysis or general build-command UI acceptance is claimed."
        isBuf(spec) -> "Configured plugins/dependencies and network/cache readiness are unverified; failures may be log-only, so even current-version empty diagnostics do not prove full lint. Cache/WKT navigation outside the workspace is unavailable. Multi-file edits require captured open-target text; unopened targets remain rejected. Type-definition, folding/highlight/link/code-lens, prepare-rename and generation/dependency-command UI are not integrated. IDE persisted changes restart indexes; external changes require workspace reopen."
        isTerraform(spec) -> "Native diagnostics are partial and may use embedded provider schemas; they do not prove CLI validation or infrastructure validity. Terraform/provider readiness is unverified; CLI formatting is optional. Nested tfvars use their own directory context. IDE persisted changes restart indexes; external terminal/provider changes require workspace reopen. Init/plan/apply, useful quick-fix/code-action, completion-resolve, declaration and code-lens UI are not integrated; rename is unsupported."
        isPerl(spec) -> "Perl::Critic/PPI and Perl::Tidy readiness is unverified; an empty report or no formatting edits does not verify those optional tools."
        isTexlab(spec) -> "TeX/latexmk/latexindent readiness is unverified; empty diagnostics or formatting edits do not verify tools. Native BibTeX formatting is available. Build, ChkTeX opt-in, cleanup, forward/inverse-search, folding/highlight/link and completion-resolve UI are not integrated in this stage."
        else -> null
    }

    private fun markers(root: File, candidates: List<String>): List<String> = candidates.filter { marker ->
        val path = File(root, marker).toPath()
        !Files.isSymbolicLink(path) && Files.exists(path, NOFOLLOW_LINKS)
    }
}
