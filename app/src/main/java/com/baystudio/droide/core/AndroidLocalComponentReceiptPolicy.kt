package com.baystudio.droide.core

/** A self-consistent receipt must still describe the component pinned by this application. */
internal object AndroidLocalComponentReceiptPolicy {
    fun matches(record: ManagedPackageRecord, lock: LocalAndroidSdkComponentLock, entry: AndroidLocalComponentCatalogEntry): Boolean {
        val native = entry.nativeTools.associate { it.name to it.sha256 }
        val nativeText = native.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" }
        return record.familyId == entry.familyId && record.version == entry.version &&
            record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name && record.abi == "arm64-v8a" &&
            record.artifactSha256 == entry.sha256 &&
            record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_ANDROID_SDK_COMPONENT &&
            record.metadata[LocalManagedPackageMetadata.ANDROID_COMPONENT_KIND_KEY] == entry.kind.name &&
            record.metadata[LocalManagedPackageMetadata.ANDROID_COMPONENT_GUEST_TARGET_KEY] == entry.guestTarget &&
            record.metadata[LocalManagedPackageMetadata.ANDROID_COMPONENT_UPSTREAM_SHA1_KEY] == entry.upstreamSha1 &&
            (if (native.isEmpty()) !record.metadata.containsKey(LocalManagedPackageMetadata.ANDROID_COMPONENT_NATIVE_TOOLS_KEY)
            else record.metadata[LocalManagedPackageMetadata.ANDROID_COMPONENT_NATIVE_TOOLS_KEY] == nativeText) &&
            lock.schema == 2 && lock.familyId == entry.familyId && lock.version == entry.version &&
            lock.componentKind == entry.kind.name && lock.artifactUrl == entry.downloadUrl &&
            lock.artifactSha256 == entry.sha256 && lock.upstreamSha1 == entry.upstreamSha1 &&
            lock.artifactSize == entry.sizeBytes && lock.archiveRoot == entry.archiveRoot &&
            lock.guestTarget == entry.guestTarget && lock.provenanceUrl == entry.provenanceUrl &&
            lock.nativeToolSha256 == native
    }
}
