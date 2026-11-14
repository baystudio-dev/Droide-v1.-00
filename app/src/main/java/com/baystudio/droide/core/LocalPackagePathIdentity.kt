package com.baystudio.droide.core

import java.io.File

/**
 * Android can expose the same app files directory as /data/data or /data/user/0.
 * Only spellings proved to resolve to this Context's own root are admitted. Package
 * subdirectories must retain their exact relative path: a symlink inside packages
 * is not another spelling of an app root.
 */
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
        // Reject traversal through a package-storage symlink, including links which
        // happen to point to another directory inside this app's storage.
        return expected.takeIf { File(path).canonicalPath == expected }
    }

    /**
     * The older Ubuntu layer producer used filesDir.absolutePath, whereas sealed
     * SDK installers already used canonicalPath. Rebase only that producer's
     * address-only receipt; keep every checksum and sealed wrapper byte intact.
     */
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
