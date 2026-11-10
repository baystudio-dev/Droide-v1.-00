package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Runs compiled production policies; does not invoke Android, ADB, downloads or installation. */
fun main(args: Array<String>) {
    require(args.size == 2)
    val document = Json.decodeFromString<DeclarativePackageCatalogDocument>(File(args[0]).readText())
    val catalog = PackageSourceCatalog.fromDocument(document)
    val androidBytes = File(args[1]).readBytes()
    require(MessageDigest.getInstance("SHA-256").digest(androidBytes).joinToString("") { "%02x".format(it) } == AndroidLocalComponentCatalog.PINNED_ASSET_SHA256)
    val androidComponents = Json.decodeFromString<AndroidLocalComponentCatalogDocument>(androidBytes.decodeToString()).also(AndroidLocalComponentCatalog::validate)
    val families = DevelopmentExtensionCatalog.families
    require(families.map { it.id }.distinct().size == families.size) { "Duplicate extension family" }
    require(UbuntuAptCatalog.entries.map { it.familyId }.distinct().size == UbuntuAptCatalog.entries.size)
    require(UbuntuNpmCatalog.entries.map { it.familyId }.distinct().size == UbuntuNpmCatalog.entries.size)
    UbuntuAptCatalog.entries.forEach { it.validate(); require(DevelopmentExtensionCatalog.byId(it.familyId) != null) }
    UbuntuNpmCatalog.entries.forEach { require(DevelopmentExtensionCatalog.byId(it.familyId) != null) }

    val inventory = families.map { family ->
        val apt = UbuntuAptCatalog.find(family.id)
        val npm = UbuntuNpmCatalog.find(family.id)
        val fixedNpm = WorkstationInstallRecipeCatalog.entries.filter { it.familyId == family.id && LocalUbuntuNpmPolicy.supports(it) }
        val source = catalog.find(family.id)
        val localSource = source?.takeIf(LocalUbuntuReviewedArtifactPolicy::supports)
        val localChoices = DevelopmentExtensionProjection.localVersions(family)
        val routes = buildList {
            if (LocalUbuntuVendorCatalog.find(family.id) != null) add("UBUNTU_PUBLISHER_OVERLAY")
            if (LocalUbuntuBundleAliasPolicy.find(family.id) != null) add("UBUNTU_SDK_COMMAND_ALIAS")
            if (apt != null) add("UBUNTU_APT")
            if (npm != null) add("UBUNTU_NPM")
            if (LocalUbuntuRubyCatalog.find(family.id) != null) add("PINNED_UBUNTU_RUBY")
            if (family.id == LocalAndroidWorkstationPolicy.FAMILY) add("PINNED_ANDROID_WORKSTATION_BUNDLE")
            if (fixedNpm.isNotEmpty()) add("PINNED_UBUNTU_NPM")
            if (family.id == "runtime.node") add("OFFICIAL_NODE_22")
            if (androidComponents.entries.any { it.familyId == family.id }) add("PINNED_ANDROID_LOCAL_COMPONENT")
            if (localSource != null) add(if (LocalUbuntuReviewedRawPolicy.supports(localSource)) "UBUNTU_NATIVE_RAW" else "UBUNTU_WHOLE_TREE")
        }
        val builtin = family.versions.any { it.installKind == ExtensionInstallKind.BUILT_IN }
        val projectFeature = !builtin && family.versions.all { it.scope == ExecutionScope.EDITOR }
        buildJsonObject {
            put("familyId", family.id); put("name", family.name); put("category", family.category.name)
            put("classification", when {
                builtin -> "BUILT_IN"
                projectFeature -> "PROJECT_FEATURE"
                routes.isNotEmpty() -> "LOCAL_INSTALL_ROUTE"
                else -> "NO_LOCAL_INSTALL_ROUTE"
            })
            put("localRoutes", JsonArray(routes.map(::JsonPrimitive)))
            put("localChoices", buildJsonArray { localChoices.forEach { choice -> add(buildJsonObject {
                put("version", choice.version); put("channel", choice.channel)
                put("kind", choice.installKind.name); put("scope", choice.scope.name)
                put("provides", JsonArray(choice.provides.sorted().map(::JsonPrimitive)))
            }) } })
            put("baseProvides", JsonArray(family.versions.flatMap { it.provides }.distinct().sorted().map(::JsonPrimitive)))
            put("versions", buildJsonArray { family.versions.forEach { version -> add(buildJsonObject {
                put("version", version.version); put("kind", version.installKind.name); put("scope", version.scope.name)
            }) } })
            if (apt != null) put("apt", buildJsonObject {
                put("version", apt.version); put("packages", JsonArray(apt.packages.map(::JsonPrimitive)))
                put("commands", buildJsonObject { apt.commands.forEach { (name, path) -> put(name, path) } })
                put("commandArgs", buildJsonObject { apt.commands.keys.forEach { name -> put(name, JsonArray(apt.launchArgs(name).map(::JsonPrimitive))) } })
                put("health", buildJsonObject { apt.commands.keys.forEach { name -> put(name, JsonArray(apt.probeArgs(name).map(::JsonPrimitive))) } })
            })
            if (npm != null) put("npm", buildJsonObject {
                put("packageName", npm.packageName); put("command", npm.command); put("provenance", npm.provenance)
            })
            LocalUbuntuRubyCatalog.find(family.id)?.let { ruby -> put("ruby", buildJsonObject {
                put("version", ruby.version); put("command", ruby.command)
                put("gemArchives", buildJsonArray { ruby.gems.forEach { gem -> add(buildJsonObject {
                    put("name", gem.name); put("version", gem.version); put("sha256", gem.sha256)
                }) } })
            }) }
            if (fixedNpm.isNotEmpty()) put("pinnedNpm", buildJsonArray { fixedNpm.forEach { recipe -> add(buildJsonObject {
                put("version", recipe.version); put("packageName", recipe.packageName); put("packageVersion", recipe.packageVersion)
                put("commands", JsonArray(LocalUbuntuNpmPolicy.commands(recipe).keys.map(::JsonPrimitive)))
            }) } })
            androidComponents.entries.filter { it.familyId == family.id }.takeIf { it.isNotEmpty() }?.let { components ->
                put("androidLocalComponents", buildJsonArray { components.forEach { component -> add(buildJsonObject {
                    put("version", component.version); put("kind", component.kind.name); put("sha256", component.sha256)
                }) } })
            }
            if (source != null) put("declarativeSource", buildJsonObject {
                put("provider", source.source.name); put("providerInstallerReady", source.installerReady)
                put("localRawAdmitted", LocalUbuntuReviewedRawPolicy.supports(source))
                put("localWholeTreeAdmitted", LocalUbuntuReviewedWholeTreePolicy.supports(source))
                source.bundlePrimaryFamilyId?.let { put("bundlePrimaryFamilyId", it) }
                put("command", source.command)
            })
            if (!builtin && !projectFeature && routes.isEmpty()) put("reason", when {
                source?.bundlePrimaryFamilyId?.let { it != family.id } == true -> "Bundle metadata does not supply a local Ubuntu projection transaction"
                source != null && source.installerReady -> "Reviewed source is not admitted by an implemented local Ubuntu installer"
                source != null -> "Source is pending certification or has no implemented local Ubuntu installer"
                else -> "No reviewed local Ubuntu recipe for this family"
            })
        }
    }
    val familyInventory = inventory.associateBy { it.getValue("familyId").jsonPrimitive.content }
    // Enumerate every language, including editor/project-only entries. Syntax recognition and an
    // install recipe are not execution or protocol proof.
    val languages = LanguageRegistry.all.map { language ->
        val runtimeFamily = UbuntuAptCatalog.languageRuntime[language.id]
        val runtime = runtimeFamily?.let(familyInventory::get)
        val representative = "src/main.${language.extensions.firstOrNull() ?: "txt"}"
        val servers = LanguageServerRegistry.builtIns.filter { spec ->
            language.id in spec.languageIds || language.extensions.any { it in spec.extensions }
        }
        val debuggers = DebugAdapterRegistry.builtIns.filter { spec -> language.extensions.any { it in spec.extensions } }
        buildJsonObject {
            put("languageId", language.id); put("name", language.name)
            put("extensions", JsonArray(language.extensions.map(::JsonPrimitive)))
            put("runPlan", if (language.runner != null) "EXPLICIT_TOOL_RUN_PLAN" else "EDITOR_OR_PROJECT_TOOLING")
            runtimeFamily?.let { put("runtimeFamily", it) }
            put("runtimeLocalRoutes", runtime?.get("localRoutes") ?: JsonArray(emptyList()))
            put("languageServers", buildJsonArray { servers.forEach { spec -> add(buildJsonObject {
                put("serverId", spec.id)
                put("commands", buildJsonArray { spec.commands.forEach { add(JsonArray(it.map(::JsonPrimitive))) } })
                put("localRoutes", familyInventory["lsp.${spec.id}"]?.get("localRoutes") ?: JsonArray(emptyList()))
            }) } })
            put("builtInDebugAdapters", buildJsonArray { debuggers.forEach { spec -> add(buildJsonObject {
                put("adapterId", spec.id); put("transport", spec.transport.name)
                put("autoConfigure", spec.autoConfigure); put("nativeProcessControlRequired", spec.nativeDebugger)
            }) } })
            put("representativePath", representative)
            put("verification", "STATIC_ROUTE_ONLY_NOT_INSTALL_BUILD_LSP_OR_DEBUG_PROOF")
        }
    }
    println(buildJsonObject {
        put("schema", 2)
        put("scope", "Compiled production catalog and local admission policies; static route inventory, not proof of installation, device execution, terminal or API availability")
        put("catalogRevision", catalog.revision)
        put("counts", buildJsonObject {
            put("families", inventory.size)
            inventory.groupingBy { it.getValue("classification").jsonPrimitive.content }.eachCount().toSortedMap().forEach { (kind, count) -> put(kind, count) }
            put("aptRecipes", UbuntuAptCatalog.entries.size); put("npmRecipes", UbuntuNpmCatalog.entries.size)
            put("publisherRecipes", LocalUbuntuVendorCatalog.all.size); put("sdkAliasRecipes", LocalUbuntuBundleAliasPolicy.all().size)
            put("rubyRecipes", 1)
        })
        put("families", JsonArray(inventory))
        put("languages", JsonArray(languages))
    })
}
