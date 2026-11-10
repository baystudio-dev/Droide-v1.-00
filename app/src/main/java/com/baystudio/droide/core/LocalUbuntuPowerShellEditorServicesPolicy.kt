package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Original publisher identity and proposed fail-closed layout; no original runtime acceptance. */
internal object LocalUbuntuPowerShellEditorServicesPolicy {
    const val FAMILY = "lsp.powershell-editor-services"
    const val COMMAND = "powershell-editor-services"
    const val ENTRY = "PowerShellEditorServices/Start-EditorServices.ps1"
    const val MAX_FILES = 4096
    const val RUNTIME_FAMILY = "runtime.powershell"
    const val RUNTIME_VERSION = "7.6.7"
    const val RUNTIME_KEY = "$RUNTIME_FAMILY@$RUNTIME_VERSION"
    const val RUNTIME_GUEST = "/opt/droide/packages/$RUNTIME_FAMILY/$RUNTIME_VERSION/arm64-v8a/payload/tree/pwsh"
    const val SOURCE_POLICY_REVISION = "powershell-7.6.7-pses-4.7.0-consolehost-pipe-v1/vendor-v1"
    const val VERSION = "4.7.0"
    const val REPOSITORY = "PowerShell/PowerShellEditorServices"
    const val SOURCE_COMMIT = "e124c135c6bcb95e94ca1ea7bc4ca032249e4572"
    const val ASSET = "PowerShellEditorServices.zip"
    const val SHA256 = "5084f0326cc88539e9d880b08f6c52ce5be4672bb8ecee8921e35f8895a6b9d7"
    const val ARTIFACT_BYTES = 20_484_361L
    const val RELEASE_ID = 345_010_401L
    const val ASSET_ID = 458_036_418L
    const val METADATA_URL = "https://api.github.com/repos/$REPOSITORY/releases/tags/v$VERSION"
    const val RELEASE_API_URL = "https://api.github.com/repos/$REPOSITORY/releases/$RELEASE_ID"
    const val ASSET_URL = "https://github.com/$REPOSITORY/releases/download/v$VERSION/$ASSET"
    const val PROVENANCE_URL = "https://github.com/$REPOSITORY/releases/tag/v$VERSION"
    const val MAX_TREE_BYTES = 256L * 1024 * 1024

    fun requireReviewed(recipe: LocalUbuntuVendorRecipe) {
        require(recipe.definition == LocalUbuntuVendorCatalog.find(FAMILY) && recipe.version == VERSION &&
            recipe.releaseTag == "v$VERSION" && recipe.assetName == ASSET && recipe.assetUrl == ASSET_URL &&
            recipe.assetSha256 == SHA256 && recipe.assetSize == ARTIFACT_BYTES && recipe.provenanceUrl == PROVENANCE_URL) {
            "Only the exact reviewed PSES $VERSION original ARM64 archive is supported; no version is substituted."
        }
    }

    fun publisherRecipe(definition: LocalUbuntuVendorDefinition, text: String): LocalUbuntuVendorRecipe {
        require(definition == LocalUbuntuVendorCatalog.find(FAMILY))
        LocalUbuntuPowerShellRuntimePolicy.requireRelease(text, RELEASE_ID, RELEASE_API_URL, PROVENANCE_URL,
            "v$VERSION", ASSET_ID, ASSET, ARTIFACT_BYTES, ASSET_URL, SHA256)
        return LocalUbuntuVendorRecipe(definition, VERSION, "v$VERSION", ASSET, ASSET_URL, SHA256,
            ARTIFACT_BYTES, PROVENANCE_URL).also { it.validate() }
    }

    /** Data-only bounded module layout inspection, never Import-Module or a native probe. */
    fun requireTree(entry: File, active: () -> Unit = {}) {
        active()
        val root = requireNotNull(entry.parentFile?.parentFile)
        require(entry.relativeTo(root).invariantSeparatorsPath == ENTRY)
        require(root.isDirectory && !PathSecurity.isSymbolicLink(root))
        val expected = setOf("PowerShellEditorServices", "PSScriptAnalyzer", "PSReadLine", "LICENSE", "NOTICE.txt", "README.md", "SECURITY.md")
        val names = linkedSetOf<String>()
        java.nio.file.Files.newDirectoryStream(root.toPath()).use { entries ->
            for (path in entries) { active(); require(names.size < expected.size); names += path.fileName.toString() }
        }
        require(names == expected) { "PSES published root layout changed" }
        var count = 0; var bytes = 0L; var directories = 0
        fun walk(directory: File, depth: Int) {
            active(); require(depth <= 32 && ++directories <= 8192 && !PathSecurity.isSymbolicLink(directory))
            java.nio.file.Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (path in entries) {
                active(); val file = path.toFile()
                require(!PathSecurity.isSymbolicLink(file)) { "PSES module tree contains a link" }
                if (file.isDirectory) walk(file, depth + 1) else {
                    require(file.isFile && ++count <= MAX_FILES)
                    bytes = Math.addExact(bytes, file.length()); require(bytes <= MAX_TREE_BYTES)
                    val magic = ByteArray(4); val n = file.inputStream().use { it.read(magic) }
                    require(n != 4 || !magic.contentEquals(byteArrayOf(127, 69, 76, 70))) { "Unreviewed native ELF in PSES modules" }
                }
            }
            }
        }
        walk(root, 0)
        for (relative in listOf(ENTRY, "PowerShellEditorServices/PowerShellEditorServices.psd1", "LICENSE", "NOTICE.txt")) {
            val file = File(root, relative)
            require(file.isFile && !PathSecurity.isSymbolicLink(file) && file.length() in 1..1_048_576) { "PSES required original file missing: $relative" }
        }
        require(File(root, "PowerShellEditorServices/bin/Common").isDirectory && File(root, "PowerShellEditorServices/bin/Core").isDirectory)
    }
    fun requireRuntime(record: ManagedPackageRecord, appRoot: String) {
        val expected = File(appRoot, "packages/$RUNTIME_FAMILY/$RUNTIME_VERSION/arm64-v8a").absolutePath
        require(record.familyId == RUNTIME_FAMILY && record.version == RUNTIME_VERSION && record.installRoot == expected &&
            record.artifactSha256 == LocalUbuntuPowerShellRuntimePolicy.SHA256 && record.abi == "arm64-v8a" &&
            record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name &&
            record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE) {
            "PSES requires its exact owned PowerShell 7.6.7 runtime, independently of the interactive default"
        }
        LocalUbuntuPowerShellRuntimePolicy.requireReviewed(requireNotNull(LocalUbuntuVendorPolicy.fromReceipt(record)))
    }
}
