package com.baystudio.droide.core

import java.io.File

/** Only spellings proved to resolve to this Context's own root are admitted. */

internal class LocalPackagePathIdentity(filesDir: File) {
    val root: String = filesDir.canonicalPath
    private val spellings = buildSet {
        add(root)
        add(filesDir.absolutePath)
        val canonicalAndroidRoot = Regex("^/data/user/0/([^/]+)/files$").matchEntire(root)
        canonicalAndroidRoot?.groupValues?.get(1)?.let { packageName ->
            val alias = File("/data/data/$packageName/files")
            if (alias.canonicalPath == root) add(alias.absolutePath)
        }
    }.filter { candidate ->
        PackageBackendContract.isSafeAbsolute(candidate) && File(candidate).canonicalPath == root
    }.toSet()

    fun canonicalPackagePath(path: String): String? {
        if (!PackageBackendContract.isSafeAbsolute(path)) return null
        val spelling = spellings.firstOrNull { path.startsWith("$it/packages/") } ?: return null
        val suffix = path.removePrefix(spelling)
        val expected = root + suffix

        return expected.takeIf { File(path).canonicalPath == expected }
    }

    /** Rebase only that producer's address-only receipt; keep every checksum and sealed wrapper byte
     * intact. */
    fun normalizeLayerReceipt(record: ManagedPackageRecord): ManagedPackageRecord {
        if (record.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND] !=
            FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND || record.scope != ExecutionScope.LOCAL_LINUX_ARM64.name ||
            record.commands.isNotEmpty() || record.pathEntries.isNotEmpty() || record.environment.isNotEmpty() ||
            record.dependencies.isNotEmpty()) return record
        val declared = record.metadata[PackageBackendContract.METADATA_KEY]
        if (declared != null && declared != PackageBackendId.LOCAL_APP.name) return record
        val canonical = canonicalPackagePath(record.installRoot) ?: return record
        return if (canonical == record.installRoot) record else record.copy(installRoot = canonical)
    }
}
