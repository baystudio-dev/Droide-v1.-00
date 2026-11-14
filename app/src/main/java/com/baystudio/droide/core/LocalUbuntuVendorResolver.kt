package com.baystudio.droide.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Only publisher metadata/checksums select bytes; missing digests fail before any installation. */
class LocalUbuntuVendorResolver(
    private val fetchText: suspend (String) -> String = { url ->
        val response = SafeHttp.get(url, accept = "*/*", maxBytes = 1_000_000)
        check(response.code == 200) { "Publisher metadata failed with HTTP ${response.code}" }
        response.body
    },
) {
    suspend fun resolve(definition: LocalUbuntuVendorDefinition, requestedVersion: String = "Latest official"): LocalUbuntuVendorRecipe {
        require(LocalUbuntuVendorCatalog.find(definition.familyId) == definition)
        val latest = requestedVersion in setOf("Latest official", "latest", "")
        val requested = requestedVersion.takeUnless { latest }
        requested?.let { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}"))) }
        val recipe = when (definition.familyId) {
            LocalUbuntuAgentDistributions.ANTIGRAVITY, LocalUbuntuAgentDistributions.MUSE_RUNTIME ->
                LocalUbuntuAgentDistributions.resolve(definition.familyId, requested, ::boundedText)
            LocalUbuntuElpPolicy.FAMILY -> {
                require(requested==LocalUbuntuElpPolicy.VERSION) { "Select exact ELP ${LocalUbuntuElpPolicy.VERSION}; generic latest/GitHub updates cannot substitute" }
                LocalUbuntuElpPolicy.publisherRecipe(definition,boundedText(LocalUbuntuElpPolicy.METADATA_URL))
            }
            LocalUbuntuNimPolicy.FAMILY -> LocalUbuntuNimPolicy.releaseRecipe(definition, requested, boundedText(LocalUbuntuNimPolicy.API_URL))
            LocalUbuntuNimToolchainPolicy.FAMILY -> LocalUbuntuNimToolchainPolicy.releaseRecipe(definition, requested, boundedText(LocalUbuntuNimToolchainPolicy.API_URL))
            LocalUbuntuNimHelperPolicy.FAMILY -> LocalUbuntuNimHelperPolicy.releaseRecipe(definition, requested, boundedText(LocalUbuntuNimHelperPolicy.CHECKSUM_URL))
            LocalUbuntuGraphqlNodePolicy.FAMILY -> LocalUbuntuGraphqlNodePolicy.releaseRecipe(definition, requested, boundedText(LocalUbuntuGraphqlNodePolicy.CHECKSUM_URL))
            LocalUbuntuTomlNodePolicy.FAMILY -> LocalUbuntuTomlNodePolicy.releaseRecipe(definition, requested, boundedText(LocalUbuntuTomlNodePolicy.CHECKSUM_URL))
            LocalUbuntuVueHelperPolicy.FAMILY -> LocalUbuntuVueHelperPolicy.releaseRecipe(definition, requested, boundedText(LocalUbuntuVueHelperPolicy.CHECKSUM_URL))
            LocalUbuntuPowerShellRuntimePolicy.FAMILY -> LocalUbuntuPowerShellRuntimePolicy.releaseRecipe(definition, requested,
                boundedText(LocalUbuntuPowerShellRuntimePolicy.API_URL))
            LocalUbuntuPowerShellEditorServicesPolicy.FAMILY -> {
                require(requested == LocalUbuntuPowerShellEditorServicesPolicy.VERSION) { "Choose the exact reviewed PSES 4.7.0 recipe; no version is substituted" }
                LocalUbuntuPowerShellEditorServicesPolicy.publisherRecipe(definition, boundedText(LocalUbuntuPowerShellEditorServicesPolicy.METADATA_URL))
            }
            LocalUbuntuSqruffPolicy.FAMILY -> {
                require(requested == LocalUbuntuSqruffPolicy.VERSION) {
                    "Choose the explicit reviewed Sqruff ${LocalUbuntuSqruffPolicy.VERSION} recipe; no requested version is substituted."
                }
                LocalUbuntuSqruffPolicy.publisherRecipe(definition, boundedText(LocalUbuntuSqruffPolicy.METADATA_URL))
            }
            LocalUbuntuClojurePolicy.FAMILY -> {
                require(requested == LocalUbuntuClojurePolicy.VERSION) {
                    "Choose the explicit reviewed Clojure LSP ${LocalUbuntuClojurePolicy.VERSION} recipe; no requested version is silently substituted."
                }
                val metadata = boundedText(LocalUbuntuClojurePolicy.METADATA_URL)
                val checksum = boundedText(LocalUbuntuClojurePolicy.CHECKSUM_URL)
                LocalUbuntuClojurePolicy.publisherRecipe(definition, metadata, checksum)
            }
            LocalUbuntuMakePolicy.FAMILY -> {
                require(requested == LocalUbuntuMakePolicy.VERSION) {
                    "Choose the explicit reviewed Make LS ${LocalUbuntuMakePolicy.VERSION} recipe; no requested version is silently substituted."
                }
                LocalUbuntuMakePolicy.publisherRecipe(definition, boundedText(LocalUbuntuMakePolicy.METADATA_URL))
            }
            LocalUbuntuLemMinxPolicy.FAMILY -> {
                require(requested == LocalUbuntuLemMinxPolicy.VERSION) {
                    "Choose the explicit reviewed LemMinX ${LocalUbuntuLemMinxPolicy.VERSION} recipe; no requested version is silently substituted."
                }
                LocalUbuntuLemMinxPolicy.publisherRecipe(definition, boundedText(LocalUbuntuLemMinxPolicy.METADATA_URL))
            }
            LocalUbuntuZlsPolicy.FAMILY -> zls(definition, requested)
            LocalUbuntuBufPolicy.FAMILY -> github(definition, requested)
            LocalUbuntuTerraformPolicy.FAMILY -> terraform(definition, requested)
            LocalUbuntuTexlabPolicy.FAMILY -> github(definition, requested)
            LocalUbuntuMarksmanPolicy.FAMILY -> github(definition, requested)
            "toolchain.zig" -> zig(definition, requested)
            "toolchain.solidity" -> solidity(definition, requested)
            "cli.kubectl" -> kubectl(definition, requested)
            "cli.helm" -> helm(definition, requested)
            "build.gradle" -> gradle(definition, requested)
            "lsp.jdtls" -> jdtls(definition, requested)
            "package.hex" -> {
                require(latest || requested == definition.pinnedVersion) { "Install other Hex versions using mix local.hex" }
                hex(definition)
            }
            else -> github(definition, requested)
        }
        recipe.validate()
        return recipe
    }
    suspend fun resolve(familyId: String, requestedVersion: String = "Latest official"): LocalUbuntuVendorRecipe =
        resolve(requireNotNull(LocalUbuntuVendorCatalog.find(familyId)) { "No reviewed local vendor source for $familyId" }, requestedVersion)

    private suspend fun github(def: LocalUbuntuVendorDefinition, requested: String?): LocalUbuntuVendorRecipe {
        val repo = requireNotNull(def.repository)
        val lookup = "https://api.github.com/repos/$repo/releases/" + (requested?.let { "tags/${def.tagPrefix}$it" } ?: "latest")
        val release = objectAt(lookup)
        require(release["draft"]?.jsonPrimitive?.contentOrNull != "true" &&
            (requested != null || release["prerelease"]?.jsonPrimitive?.contentOrNull != "true")) { "Publisher release is not stable" }
        val tag = release.string("tag_name")
        require(tag.startsWith(def.tagPrefix)) { "Publisher tag has an unreviewed prefix" }
        val version = tag.removePrefix(def.tagPrefix)
        require(requested == null || version == requested) { "Publisher resolved a different version" }
        val assetName = def.asset(version)
        val assets = release["assets"] as? JsonArray ?: error("Publisher release assets are missing")
        val asset = assets.mapNotNull { it as? JsonObject }.singleOrNull { it.string("name") == assetName }
            ?: error("Publisher ARM64 asset is missing or ambiguous: $assetName")
        val url = asset.string("browser_download_url")
        require(url == "https://github.com/$repo/releases/download/$tag/$assetName") { "Publisher asset escaped its reviewed release" }
        val published = asset["digest"]?.jsonPrimitive?.contentOrNull
        if (def.familyId == LocalUbuntuBufPolicy.FAMILY && version == LocalUbuntuBufPolicy.VERSION) require(published == "sha256:${LocalUbuntuBufPolicy.SHA256}") {
            "Buf publisher digest is missing, malformed or differs from the exact reviewed archive"
        }
        if (def.familyId == LocalUbuntuTexlabPolicy.FAMILY && version == LocalUbuntuTexlabPolicy.VERSION) require(published == "sha256:${LocalUbuntuTexlabPolicy.SHA256}") {
            "Texlab publisher digest is missing, malformed or differs from the exact reviewed artifact"
        }
        if (def.familyId == LocalUbuntuMarksmanPolicy.FAMILY && version == LocalUbuntuMarksmanPolicy.VERSION) require(published == "sha256:${LocalUbuntuMarksmanPolicy.SHA256}") {
            "Marksman publisher digest is missing, malformed or differs from the exact reviewed artifact"
        }
        val digest = requireNotNull(published?.takeIf { it.matches(Regex("sha256:[0-9a-f]{64}")) }
            ?: def.pinnedSha256?.takeIf { version == def.pinnedVersion }?.let { "sha256:$it" }) {
            "Publisher asset has no digest and this version has no reviewed source checksum"
        }
        return LocalUbuntuVendorRecipe(def, version, tag, assetName, url, digest.removePrefix("sha256:"),
            asset["size"]?.jsonPrimitive?.longOrNull ?: error("Publisher asset size missing"), "https://github.com/$repo/releases/tag/$tag")
    }
    private suspend fun zig(def: LocalUbuntuVendorDefinition, requested: String?): LocalUbuntuVendorRecipe {
        val index = objectAt("https://ziglang.org/download/index.json")
        val version = requested ?: index.keys.filter { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) }
            .maxWithOrNull(compareBy<String>({ it.split('.')[0].toInt() }, { it.split('.')[1].toInt() }, { it.split('.')[2].toInt() }))
            ?: error("Official Zig stable release missing")
        val entry = ((index[version] as? JsonObject)?.get("aarch64-linux") as? JsonObject) ?: error("Official Zig ARM64 release missing")
        val url = entry.string("tarball")
        require(url == "https://ziglang.org/download/$version/${def.asset(version)}")
        return LocalUbuntuVendorRecipe(def, version, version, def.asset(version), url, entry.string("shasum"), entry.string("size").toLong(), "https://ziglang.org/download/")
    }
    private suspend fun solidity(def: LocalUbuntuVendorDefinition, requested: String?): LocalUbuntuVendorRecipe {
        val index = objectAt("https://binaries.soliditylang.org/linux-arm64/list.json")
        val version = requested ?: index.string("latestRelease")
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        val asset = (index["releases"] as? JsonObject)?.string(version) ?: error("Official Solidity ARM64 release missing")
        val build = (index["builds"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.singleOrNull {
            it.string("version") == version && it.string("path") == asset
        } ?: error("Official Solidity ARM64 build is missing or ambiguous")
        val sha = build.string("sha256").removePrefix("0x")
        return LocalUbuntuVendorRecipe(def, version, "v$version", asset, "https://binaries.soliditylang.org/linux-arm64/$asset", sha,
            null, "https://binaries.soliditylang.org/linux-arm64/list.json")
    }
    private suspend fun terraform(def: LocalUbuntuVendorDefinition, requested: String?): LocalUbuntuVendorRecipe {
        val version = requested ?: objectAt("https://releases.hashicorp.com/terraform-ls/index.json")
            .get("versions").let { value -> latestStable((value as? JsonObject)?.keys.orEmpty()) }
        require(version.matches(Regex("[0-9]+[.][0-9]+[.][0-9]+")))
        val asset = def.asset(version)
        val base = "https://releases.hashicorp.com/terraform-ls/$version/"
        val url = base + "terraform-ls_${version}_SHA256SUMS"
        val sha = LocalUbuntuTerraformPolicy.publisherChecksum(boundedText(url), asset)
        return LocalUbuntuVendorRecipe(def, version, "v$version", asset, base + asset, sha,
            if (version == LocalUbuntuTerraformPolicy.VERSION) LocalUbuntuTerraformPolicy.ARTIFACT_BYTES else null, url)
    }

    private suspend fun zls(def: LocalUbuntuVendorDefinition, requested: String?): LocalUbuntuVendorRecipe {
        require(requested == null || LocalUbuntuZlsPolicy.supportsVersion(requested)) {
            "This ZLS integration requires the Zig 0.16 compatible release line"
        }
        val text = boundedText(LocalUbuntuZlsPolicy.INDEX_URL)
        val index = Json.parseToJsonElement(text) as? JsonObject ?: error("ZLS publisher index is not an object")
        val version = requested ?: latestStable(index.keys.filter(LocalUbuntuZlsPolicy::supportsVersion).toSet())
        if (version == LocalUbuntuZlsPolicy.VERSION) return LocalUbuntuZlsPolicy.publisherRecipe(def, text)
        val target = ((index[version] as? JsonObject)?.get("aarch64-linux") as? JsonObject)
            ?: error("Publisher does not provide this ZLS version for Linux ARM64")
        return LocalUbuntuVendorRecipe(def, version, version, def.asset(version), target.string("tarball"),
            target.string("shasum"), target.string("size").toLong(), LocalUbuntuZlsPolicy.INDEX_URL)
    }

    private fun latestStable(versions: Set<String>): String = versions
        .filter { it.matches(Regex("[0-9]{1,6}[.][0-9]{1,6}[.][0-9]{1,6}")) }
        .maxWithOrNull(compareBy<String>({ it.substringBefore('.').toInt() },
            { it.split('.')[1].toInt() }, { it.substringAfterLast('.').toInt() }))
        ?: error("Publisher has no stable version")

    private suspend fun kubectl(def: LocalUbuntuVendorDefinition, requested: String?): LocalUbuntuVendorRecipe {
        val tag = requested?.let { "v$it" } ?: fetchText("https://dl.k8s.io/release/stable.txt").trim()
        require(tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")))
        val version = tag.removePrefix("v")
        val url = "https://dl.k8s.io/release/$tag/bin/linux/arm64/kubectl"
        return LocalUbuntuVendorRecipe(def, version, tag, "kubectl", url, checksum("$url.sha256"), null,
            "https://kubernetes.io/docs/tasks/tools/install-kubectl-linux/")
    }
    private suspend fun helm(def: LocalUbuntuVendorDefinition, requested: String?): LocalUbuntuVendorRecipe {
        val tag = requested?.let { "v$it" } ?: objectAt("https://api.github.com/repos/helm/helm/releases/latest").string("tag_name")
        require(tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")))
        val version = tag.removePrefix("v")
        val asset = def.asset(version)
        val url = "https://get.helm.sh/$asset"
        return LocalUbuntuVendorRecipe(def, version, tag, asset, url, checksum("$url.sha256sum"), null, "https://helm.sh/docs/intro/install/")
    }
    private suspend fun gradle(def: LocalUbuntuVendorDefinition, requested: String?): LocalUbuntuVendorRecipe {
        val version = requested ?: objectAt("https://services.gradle.org/versions/current").string("version")
        require(version.matches(Regex("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?")))
        val asset = def.asset(version)
        val url = "https://services.gradle.org/distributions/$asset"
        val sha = checksum("$url.sha256")
        return LocalUbuntuVendorRecipe(def, version, "v$version", asset, url, sha, null, "https://gradle.org/releases/")
    }
    private suspend fun jdtls(def: LocalUbuntuVendorDefinition, requested: String?): LocalUbuntuVendorRecipe {
        val base = "https://download.eclipse.org/jdtls/milestones/"
        val version = requested ?: Regex("href=['\"]/jdtls/milestones/([0-9]+\\.[0-9]+\\.[0-9]+)['\"]")
            .findAll(boundedText(base)).map { it.groupValues[1] }.toSet()
            .maxWithOrNull(compareBy<String>({ it.split('.')[0].toInt() }, { it.split('.')[1].toInt() }, { it.split('.')[2].toInt() }))
            ?: error("Official JDTLS milestone version missing")
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        val pattern = Regex("jdt-language-server-${Regex.escape(version)}-[0-9]{12,14}\\.tar\\.gz")
        val asset = pattern.findAll(boundedText("$base$version/")).map { it.value }.toSet().singleOrNull()
            ?: error("Official JDTLS archive is missing or ambiguous")
        val url = "$base$version/$asset"
        return LocalUbuntuVendorRecipe(def, version, version, asset, url, checksum("$url.sha256"),
            if (version == def.pinnedVersion && asset == def.asset(version)) 51_037_522L else null,
            "https://github.com/eclipse-jdtls/eclipse.jdt.ls")
    }
    private suspend fun hex(def: LocalUbuntuVendorDefinition): LocalUbuntuVendorRecipe {
        val indexUrl = "https://repo.hex.pm/installs/hex-1.x.csv"
        val index = fetchText(indexUrl)
        require(index.toByteArray(Charsets.UTF_8).size <= 1_000_000)
        val version = requireNotNull(def.pinnedVersion)
        val rows = index.lineSequence().map { it.trim().split(',') }
            .filter { it.size == 3 && it[0] == version && it[2] == "1.14.0" }.toList()
        require(rows.size == 1 && rows.single()[1] == HEX_SHA512) { "Reviewed Hex archive checksum or Elixir compatibility changed" }
        val asset = def.asset(version)
        return LocalUbuntuVendorRecipe(def, version, version, asset, "https://repo.hex.pm/installs/1.14.0/$asset",
            requireNotNull(def.pinnedSha256), 971_966L, indexUrl)
    }
    private suspend fun objectAt(url: String): JsonObject {
        val text = boundedText(url)
        return Json.parseToJsonElement(text) as? JsonObject ?: error("Publisher metadata is not an object")
    }
    private suspend fun boundedText(url: String): String = fetchText(url).also {
        require(it.toByteArray(Charsets.UTF_8).size <= 1_000_000) { "Publisher metadata exceeds its bound" }
    }
    private suspend fun checksum(url: String): String {
        val text = fetchText(url).trim()
        require(text.length <= 1000)
        val sha = text.split(Regex("\\s+")).first()
        require(sha.matches(Regex("[0-9a-f]{64}"))) { "Publisher checksum is invalid" }
        return sha
    }
    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: error("Publisher field '$key' missing")
    private companion object {
        // Original archive's reviewed SHA256 above was computed only after
        // matching this publisher SHA512 and the exact compatible Elixir row.
        const val HEX_SHA512 = "7cc7ef419c87a90b2cc86813501dcf8a46a24e9765940574fbd3d25571c388cb41134537154bffa1f8e4d388fb7231716f1f890478ed6aae9b5c4468afddf010"
    }
}
