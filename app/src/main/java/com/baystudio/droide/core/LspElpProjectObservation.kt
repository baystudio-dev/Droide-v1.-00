package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Native discovery projection, never executes ELP/configuration or substitutes a TOML parser. */
internal data class ElpProjectObservation(val inspection: ElpSelectionInspection, val root: String,
    val descriptor: String, val kind: Kind, val rebarProfile: String, val staticData: JsonObject?,
    val observedPaths: Map<String, ElpObservedFile>, val configurationIdentity: String,
    val memberRoots: Set<String>) {
    enum class Kind { REBAR, STATIC, NO_MANIFEST }
    fun toGuest(local: String): String {
        val root = inspection.workspaceRoot
        require(local == root || local.startsWith(root.trimEnd('/') + "/"))
        return inspection.guestWorkspaceRoot.trimEnd('/') + local.removePrefix(root)
    }
    fun toLocal(guest: String): String {
        val root = inspection.guestWorkspaceRoot
        require(guest == root || guest.startsWith(root.trimEnd('/') + "/"))
        return inspection.workspaceRoot.trimEnd('/') + guest.removePrefix(root)
    }
    fun containsLocal(local: String): Boolean = contains(toGuest(local))
    val needsRebar get() = kind == Kind.REBAR
    fun contains(path: String): Boolean = memberRoots.any { path == it || path.startsWith(it.trimEnd('/') + "/") }
}

internal object LspElpProjectObservation {
    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun nearestToml(raw: ElpSelectionInspection): ElpObservedFile? = raw.ancestors.firstNotNullOfOrNull { a -> a.markers[".elp.toml"]?.takeIf { it.kind != "absent" } }
    private fun path(base: String, value: String): String {
        require(value.isNotEmpty() && '\u0000' !in value && '\\' !in value)
        val result = if (value.startsWith('/')) File(value) else File(base, value)
        return result.toPath().normalize().toString()
    }
    private fun inWorkspace(raw: ElpSelectionInspection, value: String) {
        require(value == raw.guestWorkspaceRoot || value.startsWith(raw.guestWorkspaceRoot.trimEnd('/') + "/")) { "Native controlling path is outside mapped workspace: $value" }
    }
    private fun text(file: ElpObservedFile): String {
        require(file.kind == "regular"); val bytes = requireNotNull(file.bytes); require(bytes.size <= 1_048_576)
        require(file.identity?.sha256 == sha(bytes)) { "Descriptor bytes differ from observed identity" }
        return bytes.toString(Charsets.UTF_8).also { require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) }
    }
    private fun arrayStrings(value: JsonElement?, default: List<String> = emptyList()): List<String> =
        if (value == null) default else (value as? JsonArray ?: error("Expected native string array")).map { LspElpResultAuthority.string(it, 16_384) }.also { require(it.size <= 4096) }
    private fun validateToml(data: JsonObject) {
        fun table(name: String): JsonObject? = data[name]?.let { it as? JsonObject ?: error("ELP $name must be a table") }
        table("eqwalizer")?.let { e ->
            e["enable_all"]?.let { require((it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull != null) }
            e["max_tasks"]?.let { require((it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.longOrNull?.let { n -> n >= 0 } == true) }
            arrayStrings(e["ignore_modules"]).forEach { require(it.none { c -> c in "*?[]" }) { "Native EqWAlizer glob projection unavailable" } }
        }
        table("otp")?.let { require(it.containsKey("exclude_apps")) { "Native otp table requires exclude_apps" }; arrayStrings(it["exclude_apps"]) }
        table("daemon")?.let { d -> listOf("idle_timeout_secs", "startup_timeout_secs").forEach { k -> d[k]?.let { require((it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.longOrNull?.let { n -> n >= 0 } == true) } } }
        table("rebar")?.get("profile")?.let { LspElpResultAuthority.string(it, 64) }
        table("build_info")
        table("buck")?.let { b ->
            val enabled = (b["enabled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("Native buck table requires Boolean enabled")
            require((b["build_deps"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != null) { "Native buck table requires build_deps" }
            require(b.containsKey("included_targets")); arrayStrings(b["included_targets"])
            for (key in listOf("deps_targets", "excluded_targets", "test_application_labels")) arrayStrings(b[key])
            for (key in listOf("deps_target", "source_root")) b[key]?.let { LspElpResultAuthority.string(it, 16_384) }
            require(!enabled) { "Buck is unavailable: no admitted Buck execution/ownership contract" }
        }
    }
    suspend fun resolve(raw: ElpSelectionInspection, parsedToml: JsonObject? = null,
        observe: suspend (List<String>) -> Map<String, ElpObservedFile>): ElpProjectObservation {
        require(raw.ancestors.isNotEmpty() && raw.ancestors.size <= 64 && raw.ancestors.last().guestPath == "/") { "Complete guest ancestor observation required" }
        require(raw.workspaceRoot == File(raw.workspaceRoot).toPath().normalize().toString())
        raw.ancestors.zipWithNext().forEach { (a, b) -> require(a.guestPath.substringBeforeLast('/').ifEmpty { "/" } == b.guestPath) }
        val observed = linkedMapOf<String, ElpObservedFile>()
        val explicitPaths = linkedSetOf<String>()
        raw.ancestors.forEach { a ->
            require(a.directoryIdentity.isNotEmpty())
            listOf(".elp.toml", ".elp_lint.toml", "rebar.config", "rebar.config.script", "rebar.lock", "build_info.json", "src", "include", "test").forEach { n -> require(a.markers.containsKey(n)) }
            a.markers.values.forEach { f -> require(f.kind in setOf("absent", "regular", "directory")); observed[f.guestPath] = f }
        }
        suspend fun inspect(paths: List<String>) {
            require(paths.size <= 4096); paths.forEach { inWorkspace(raw, it) }; explicitPaths += paths
            val missing = paths.distinct().filterNot(observed::containsKey)
            if (missing.isNotEmpty()) { val found = observe(missing); require(found.keys == missing.toSet()); observed.putAll(found) }
        }
        fun present(a: ElpGuestAncestor, name: String): ElpObservedFile? = a.markers[name]?.takeIf { it.kind != "absent" }
        val toml = nearestToml(raw)
        var descriptor: String; var kind: ElpProjectObservation.Kind; var static: JsonObject? = null; var profile = "test"
        if (toml != null) {
            inWorkspace(raw, toml.guestPath); text(toml)
            val data = requireNotNull(parsedToml) { "Selected Python metadata inspection is required for .elp.toml" }; validateToml(data)
            val parent = toml.guestPath.substringBeforeLast('/'); val a = raw.ancestors.single { it.guestPath == parent }
            profile = (data["rebar"] as? JsonObject)?.get("profile")?.let { LspElpResultAuthority.string(it, 64) } ?: "test"
            require(profile.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Selected Rebar profile is outside the finite shim contract" }
            val build = data["build_info"] as? JsonObject
            if (build != null) {
                kind = ElpProjectObservation.Kind.STATIC
                if (build.containsKey("file")) {
                    descriptor = path(parent, LspElpResultAuthority.string(build["file"], 16_384)); inspect(listOf(descriptor))
                    static = Json.parseToJsonElement(text(requireNotNull(observed[descriptor]))) as? JsonObject ?: error("Static project is not an object")
                } else { descriptor = toml.guestPath; static = build }
            } else {
                val rebar = present(a, "rebar.config.script") ?: present(a, "rebar.config")
                kind = if (rebar != null) ElpProjectObservation.Kind.REBAR else ElpProjectObservation.Kind.NO_MANIFEST
                // Exact release quirk: static/no-manifest in-place receives the TOML file itself.
                descriptor = rebar?.guestPath ?: "$parent/.static"
            }
        } else {
            require(parsedToml == null)
            val rebar = raw.ancestors.flatMap { a -> listOfNotNull(present(a, "rebar.config"), present(a, "rebar.config.script")) }.lastOrNull()
            val build = raw.ancestors.firstNotNullOfOrNull { present(it, "build_info.json") }
            if (rebar != null) { descriptor = rebar.guestPath; kind = ElpProjectObservation.Kind.REBAR }
            else if (build != null) { descriptor = build.guestPath; kind = ElpProjectObservation.Kind.STATIC; static = Json.parseToJsonElement(text(build)) as? JsonObject ?: error("Static project is not an object") }
            else { val src = raw.ancestors.firstNotNullOfOrNull { present(it, "src") }; val root = src?.guestPath?.substringBeforeLast('/') ?: raw.guestPath.substringBeforeLast('/'); descriptor = "$root/.static"; kind = ElpProjectObservation.Kind.NO_MANIFEST }
        }
        inWorkspace(raw, descriptor); val root = descriptor.substringBeforeLast('/'); inWorkspace(raw, root)
        if (kind == ElpProjectObservation.Kind.REBAR) require(raw.tools?.rebar != null) { "The selected native Rebar project requires a captured Rebar tool" }
        val members = linkedSetOf(root)
        if (static != null) {
            val isTomlBuild = descriptor.endsWith("/.elp.toml")
            val normalized = linkedMapOf<String, JsonElement>()
            for (category in listOf("apps", "deps")) {
                val entries = static[category]?.let { if (it is JsonArray) it.toList() else if (isTomlBuild) listOf(it) else error("Static apps/deps must be arrays") }.orEmpty()
                require(entries.size <= 4096)
                val byName = linkedMapOf<String, JsonObject>()
                for (entry in entries) {
                    val app = if (entry is JsonPrimitive && entry.isString && isTomlBuild) {
                        val literal = entry.content; require(literal.none { it in "*?[]" }) { "Native Rust glob membership is unavailable: $literal" }
                        val dir = path(root, literal); inspect(listOf(dir)); val seen = requireNotNull(observed[dir])
                        if (seen.kind == "absent") continue // Native literal glob has no matches.
                        require(seen.kind == "directory") { "Static app literal is not a directory: $dir" }
                        buildJsonObject { put("name", dir.substringAfterLast('/')); put("dir", dir); put("src_dirs", JsonArray(listOf(JsonPrimitive("src")))); put("extra_src_dirs", JsonArray(listOf(JsonPrimitive("test")))); put("include_dirs", JsonArray(listOf("include", "src", ".").map(::JsonPrimitive))) }
                    } else entry as? JsonObject ?: error("Malformed native static app")
                    val name = LspElpResultAuthority.string(app["name"], 1024); val dir = path(root, LspElpResultAuthority.string(app["dir"], 16_384)); inspect(listOf(dir))
                    val seen = requireNotNull(observed[dir]); require(seen.kind == "directory" && seen.directoryIdentity != null) { "Static app directory unavailable: $dir" }
                    members += dir
                    val src = arrayStrings(app["src_dirs"], listOf("src")); val extra = arrayStrings(app["extra_src_dirs"]); val includes = arrayStrings(app["include_dirs"])
                    val macros = app["macros"]?.let { it as? JsonObject ?: error("Native macros must be string map") } ?: buildJsonObject {}
                    macros.forEach { (k, v) -> require(k.length <= 1024); LspElpResultAuthority.string(v, 4096) }
                    val ebin = app["ebin"]?.takeUnless { it == JsonNull }?.let { path(dir, LspElpResultAuthority.string(it, 16_384)) }
                    if (ebin != null) { inspect(listOf(ebin)); require(observed[ebin]?.kind == "directory") }
                    byName[name] = buildJsonObject { put("name", name); put("dir", dir); put("src_dirs", JsonArray(src.map(::JsonPrimitive))); put("extra_src_dirs", JsonArray(extra.map(::JsonPrimitive))); put("include_dirs", JsonArray(includes.map(::JsonPrimitive))); put("macros", macros); ebin?.let { put("ebin", it) } }
                }
                normalized[category] = JsonArray(byName.values.toList())
            }
            static = JsonObject(normalized)
        }
        require(observed.values.sumOf { it.bytes?.size?.toLong() ?: 0L } <= 8_388_608)
        val selectionAncestors = raw.ancestors.filter { it.guestPath == root || root.startsWith(it.guestPath.trimEnd('/') + "/") }
        // Descendant negative marker paths depend on the initiating tab, not the selected project.
        // They are re-observed per feature and must not split one native root into two owners.
        val selectedMarkerPaths = selectionAncestors.flatMap { it.markers.values }.mapTo(hashSetOf()) { it.guestPath }
        val selectionFiles = observed.filterKeys { it == descriptor || it in selectedMarkerPaths || it in explicitPaths }
        val identity = sha((raw.workspaceRoot + raw.guestWorkspaceRoot + raw.tools?.consentIdentity + selectionAncestors.map { a -> a.guestPath + a.directoryIdentity + a.markers.values.map { "${it.guestPath}:${it.kind}:${it.identity}:${it.directoryIdentity}" } } + "\n$root\n$descriptor\n$kind\n$profile\n$static\n" +
            selectionFiles.toSortedMap().map { (k, v) -> "$k:${v.kind}:${v.identity}:${v.directoryIdentity}" }.joinToString("\n") + LspElpProfile.IDENTITY + LspElpProfile.settings).toByteArray())
        return ElpProjectObservation(raw, root, descriptor, kind, profile, static, observed.toMap(), identity, members)
    }
}
