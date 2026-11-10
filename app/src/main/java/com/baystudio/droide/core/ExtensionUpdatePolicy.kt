package com.baystudio.droide.core

/** Pure catalog metadata. Opening Extensions must not fetch versions or inspect package trees. */
internal object ExtensionUpdatePolicy {
    enum class Route { PUBLISHER, DROIDE, PARENT, PROJECT, UPSTREAM_MANAGER, COMPONENTS }

    data class Policy(
        val route: Route,
        val description: String,
        val relatedFamilies: List<String> = emptyList(),
        val documentationUrl: String? = null,
    ) {
        val hasVersionPicker: Boolean get() = route == Route.PUBLISHER
    }

    private val parents = mapOf(
        "runtime.csharp-sdk" to "lsp.csharp-ls",
        "runtime.csharp-helper" to "lsp.csharp-ls",
        "runtime.graphql-node" to "lsp.graphql-library",
        "runtime.taplo-node" to "lsp.taplo-wasm",
        "runtime.vue-helper" to "lsp.vue",
        "runtime.nim-helper" to "lsp.nimlangserver",
        "runtime.nim-lsp" to "lsp.nimlangserver",
        "runtime.powershell" to "lsp.powershell-editor-services",
    )

    // Updating their upstream executable alone does not update Droide's integration.

    private val integrated = mapOf(
        "lsp.graphql-library" to "GraphQL libraries and their private runtime",
        "lsp.taplo-wasm" to "Taplo WASM API and its private runtime",
        "lsp.csharp-ls" to "C# server, SDK and project helper",
        "lsp.vue" to "Vue server, TypeScript plugin and paired analysis helper",
        "lsp.nimlangserver" to "Nim server, compiler and analysis helper",
        "lsp.svelte-language-server" to "Svelte compiler, server and TypeScript integration",
        "lsp.prisma-language-server" to "Prisma legacy-schema adapter and configuration handling",
        "lsp.elp" to "ELP transport and selected Erlang runtime integration",
        "lsp.cmake-language-server" to "CMake server and its isolated Python dependency set",
        "lsp.powershell-editor-services" to "PowerShell runtime and Editor Services host",
        "lsp.sqruff" to "Sqruff formatting and source-coordinate adapter",
        "lsp.clojure-lsp" to "Clojure launch adapter and source attribution",
        "lsp.make-ls" to "Make LS launch and lifecycle adapter",
        "lsp.lemminx" to "LemMinX Java host and schema adapter",
    )

    private val upstream = mapOf(
        "lsp.metals" to Policy(Route.UPSTREAM_MANAGER,
            "Update Metals through Coursier using a JDK supported by that release, then expose metals on the Ubuntu PATH. Droide uses the selected executable without a version pin.",
            documentationUrl = "https://scalameta.org/metals/docs/editors/new-editor/"),
        "lsp.asm-lsp" to Policy(Route.UPSTREAM_MANAGER,
            "Update asm-lsp through Cargo or its official ARM64 releases. Keep asm-lsp on the Ubuntu PATH; compiler and assembler selection remains project-owned.",
            documentationUrl = "https://github.com/bergercookie/asm-lsp#installation"),
        "lsp.elixir-ls" to Policy(Route.UPSTREAM_MANAGER,
            "Update the complete ElixirLS release using an Elixir/Erlang combination supported by that release. Keep its scripts together and expose language_server.sh on the Ubuntu PATH.",
            documentationUrl = "https://github.com/elixir-lsp/elixir-ls#installation"),
        "lsp.haskell-hls" to Policy(Route.UPSTREAM_MANAGER,
            "Update HLS through GHCup for the project's GHC version. Keep haskell-language-server-wrapper on the Ubuntu PATH; Droide does not pin the compiler or HLS release.",
            documentationUrl = "https://haskell-language-server.readthedocs.io/en/latest/installation.html"),
        "lsp.r-languageserver" to Policy(Route.UPSTREAM_MANAGER,
            "Update the languageserver R package in the library used by your selected R installation: install.packages('languageserver', repos='https://cloud.r-project.org'). Droide does not pin its version.",
            documentationUrl = "https://github.com/REditorSupport/languageserver#installation"),
    )

    private val projects = mapOf(
        "framework.react" to "Update react and react-dom together in package.json using the project's npm, pnpm or Yarn workflow.",
        "framework.vue" to "Update vue in package.json with the project's package manager; keep its compiler and framework plugins compatible.",
        "framework.svelte" to "Update svelte and the project's SvelteKit/Vite dependencies together using its package manager.",
        "framework.django" to "Update Django in the project's Python dependency manifest and lockfile, then synchronize its virtual environment.",
        "framework.fastapi" to "Update FastAPI in the project's Python dependency manifest and lockfile, keeping Pydantic and Starlette compatible.",
        "framework.spring" to "Update the Spring Boot plugin/parent or BOM in the project's Gradle or Maven configuration, then use its wrapper.",
        "framework.ktor" to "Update Ktor dependencies and plugins together in the project's Gradle build or version catalog.",
        "framework.rails" to "Update Rails in the project's Gemfile and use Bundler to update its lockfile and installed bundle.",
        "framework.laravel" to "Update laravel/framework in the project's composer.json and update its Composer lockfile and dependencies.",
    )

    fun forFamily(family: ExtensionFamily): Policy {
        if (family.versions.any { it.installKind == ExtensionInstallKind.BUILT_IN }) {
            return Policy(Route.DROIDE, "Included in Droide. Update the application to update this feature.")
        }
        parents[family.id]?.let { parent ->
            return Policy(Route.PARENT, "Updated together with its parent integration. A separate runtime update would break the integration's dependency contract.", listOf(parent))
        }
        integrated[family.id]?.let { components ->
            return Policy(Route.DROIDE, "$components are updated together through Droide releases. Install the compatible version offered by this release below; independent upstream versions need a matching Droide adapter.")
        }
        upstream[family.id]?.let { return it }
        projects[family.id]?.let {
            return Policy(Route.PROJECT, "$it This entry detects the project's framework; it does not install a global copy.")
        }
        if (family.id == "meta.android-development") return Policy(Route.COMPONENTS,
            "Update the Android SDK, build tools and JDK separately. Gradle follows the project's wrapper; the Android integration is updated with Droide.",
            listOf("sdk.android", "build.android-tools", "toolchain.jdk", "build.gradle"))
        return Policy(Route.PUBLISHER, if (family.id == LocalUbuntuZlsPolicy.FAMILY)
            "Update from the publisher within the Zig 0.16 compatible release line. New compiler lines need a matching Droide adapter. Version lookup runs only when requested."
            else "Update from the package publisher or its package manager. Version lookup runs only when requested; activation still requires a compatible runtime and a successful installation check.")
    }
}
