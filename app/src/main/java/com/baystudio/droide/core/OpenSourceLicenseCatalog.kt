package com.baystudio.droide.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class OpenSourceLicenseEntry(
    val id: String,
    val name: String,
    val version: String,
    val licenseName: String,
    val spdx: String,
    val copyright: String,
    val licenseTextAsset: String,
    val source: String,
    val notice: String,
    val dependencyKeys: List<String> = emptyList(),
    val noticeTextAssets: List<String> = emptyList(),
)

@Serializable
data class OpenSourceLicenseManifest(
    val schemaVersion: Int,
    val closureStatus: String,
    val closureNote: String,
    val entries: List<OpenSourceLicenseEntry>,
)

// Keep this fail-closed: malformed or missing legal data must never be silently hidden.


object OpenSourceLicenseCatalog {
    private val json = Json { ignoreUnknownKeys = false }

    fun load(context: Context): OpenSourceLicenseManifest {
        val manifest = json.decodeFromString<OpenSourceLicenseManifest>(readAsset(context, MANIFEST_ASSET))
        val supplemental = json.decodeFromString<OpenSourceLicenseManifest>(readAsset(context, SUPPLEMENTAL_ASSET))
        require(manifest.schemaVersion == 1) { "Unsupported open-source license manifest schema" }
        require(supplemental.schemaVersion == 1) { "Unsupported supplemental license manifest schema" }
        val combined = manifest.copy(entries = manifest.entries + supplemental.entries)
        require(manifest.entries.isNotEmpty()) { "Open-source license manifest is empty" }
        require(combined.entries.map { it.id }.distinct().size == combined.entries.size) { "Duplicate open-source license id" }
        combined.entries.forEach { entry ->
            require(entry.id.isNotBlank() && entry.name.isNotBlank() && entry.licenseName.isNotBlank()) { "Incomplete open-source license entry" }
            (listOf(entry.licenseTextAsset) + entry.noticeTextAssets).forEach { path ->
                require(path.startsWith("legal/licenses/") && path.split('/').none { it == ".." || it.isBlank() }) {
                    "License text must be packaged under legal/licenses"
                }
            }
        }
        return combined
    }

    fun readEntry(context: Context, entry: OpenSourceLicenseEntry): String =
        (listOf(entry.licenseTextAsset) + entry.noticeTextAssets).distinct().joinToString("\n\n") { path ->
            readAsset(context, path)
        }

    fun readAsset(context: Context, assetPath: String): String =
        context.assets.open(assetPath).bufferedReader().use { it.readText() }

    const val MANIFEST_ASSET = "legal/open_source_licenses.json"
    const val SUPPLEMENTAL_ASSET = "legal/supplemental_licenses.json"
    const val THIRD_PARTY_NOTICES_ASSET = "legal/THIRD_PARTY_NOTICES.md"
    const val BRAND_ASSET_TERMS_ASSET = "legal/BRAND_ASSET_TERMS.md"
    const val DISTRIBUTION_COMPLIANCE_ASSET = "legal/DISTRIBUTION_COMPLIANCE.md"
    const val DROIDE_LICENSE_ASSET = "legal/DROIDE_LICENSE.md"
}
