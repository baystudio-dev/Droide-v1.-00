package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class AndroidLocalComponentReceiptPolicyTest {
    private fun entry(native: Boolean = false) = AndroidLocalComponentCatalogEntry(
        id = "fixture", familyId = if (native) "build.android-tools" else "sdk.android",
        version = if (native) "36.0.0" else "36",
        kind = if (native) AndroidLocalComponentKind.BUILD_TOOLS_ARM64 else AndroidLocalComponentKind.SDK_PLATFORM,
        downloadUrl = "https://dl.google.com/android/repository/fixture.zip", fileName = "fixture.zip",
        sizeBytes = 100, sha256 = "a".repeat(64), upstreamSha1 = "b".repeat(40),
        archiveRoot = "android-36", guestTarget = if (native) "build-tools/36.0.0" else "platforms/android-36",
        maxFiles = 100, maxUnpackedBytes = 1_000, provenance = "Fixture", provenanceUrl = "https://developer.android.com/studio",
        nativeTools = if (native) AndroidLocalNativeTool.REQUIRED_BUILD_TOOLS_NATIVE_NAMES.sorted().mapIndexed { i, name ->
            AndroidLocalNativeTool(name, "https://github.com/fixture/$name", 100, (i + 1).toString().repeat(64))
        } else emptyList(),
    )

    private fun record(entry: AndroidLocalComponentCatalogEntry) = ManagedPackageRecord(
        familyId = entry.familyId, version = entry.version, scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
        installRoot = "/owned/packages/${entry.familyId}/${entry.version}/arm64-v8a", installedAtEpochMs = 1,
        artifactSha256 = entry.sha256, abi = "arm64-v8a", metadata = buildMap {
            put(LocalManagedPackageMetadata.KIND_KEY, LocalManagedPackageMetadata.KIND_ANDROID_SDK_COMPONENT)
            put(LocalManagedPackageMetadata.ANDROID_COMPONENT_KIND_KEY, entry.kind.name)
            put(LocalManagedPackageMetadata.ANDROID_COMPONENT_GUEST_TARGET_KEY, entry.guestTarget)
            put(LocalManagedPackageMetadata.ANDROID_COMPONENT_UPSTREAM_SHA1_KEY, entry.upstreamSha1)
            if (entry.nativeTools.isNotEmpty()) put(LocalManagedPackageMetadata.ANDROID_COMPONENT_NATIVE_TOOLS_KEY,
                entry.nativeTools.sortedBy { it.name }.joinToString(";") { "${it.name}=${it.sha256}" })
        },
    )

    private fun lock(entry: AndroidLocalComponentCatalogEntry) = LocalAndroidSdkComponentLock(
        familyId = entry.familyId, version = entry.version, componentKind = entry.kind.name,
        artifactUrl = entry.downloadUrl, artifactSha256 = entry.sha256, upstreamSha1 = entry.upstreamSha1,
        artifactSize = entry.sizeBytes, archiveRoot = entry.archiveRoot, guestTarget = entry.guestTarget,
        provenanceUrl = entry.provenanceUrl, nativeToolSha256 = entry.nativeTools.associate { it.name to it.sha256 }, installedAtEpochMs = 1,
    )

    @Test fun sdkAndBuildToolsReceiptsMatchTheirApplicationPins() {
        listOf(entry(), entry(true)).forEach { assertTrue(AndroidLocalComponentReceiptPolicy.matches(record(it), lock(it), it)) }
    }

    @Test fun selfConsistentReplacementDigestCannotAcquireTrust() {
        val pinned = entry()
        val replaced = pinned.copy(sha256 = "c".repeat(64))
        assertFalse(AndroidLocalComponentReceiptPolicy.matches(record(replaced), lock(replaced), pinned))
    }

    @Test fun selfConsistentNativeOverlayReplacementCannotAcquireTrust() {
        val pinned = entry(true)
        val replaced = pinned.copy(nativeTools = pinned.nativeTools.map { it.copy(sha256 = "c".repeat(64)) })
        assertFalse(AndroidLocalComponentReceiptPolicy.matches(record(replaced), lock(replaced), pinned))
    }

    @Test fun lockCannotSubstituteDownloadUrlSizeArchiveOrProvenance() {
        val pinned = entry()
        listOf(lock(pinned).copy(artifactUrl = "https://example.invalid/fixture.zip"),
            lock(pinned).copy(artifactSize = 101), lock(pinned).copy(archiveRoot = "other"),
            lock(pinned).copy(provenanceUrl = "https://example.invalid")).forEach {
            assertFalse(AndroidLocalComponentReceiptPolicy.matches(record(pinned), it, pinned))
        }
    }

    @Test fun guestTargetRevisionAbiAndScopeCannotBeSubstituted() {
        val pinned = entry()
        val good = record(pinned)
        listOf(good.copy(version = "35"), good.copy(abi = "x86_64"), good.copy(scope = ExecutionScope.EDITOR.name),
            good.copy(metadata = good.metadata + (LocalManagedPackageMetadata.ANDROID_COMPONENT_GUEST_TARGET_KEY to "platforms/android-35")))
            .forEach { assertFalse(AndroidLocalComponentReceiptPolicy.matches(it, lock(pinned), pinned)) }
    }

    @Test fun sdkCannotAcquireAnUnreviewedNativeToolManifest() {
        val pinned = entry()
        val extra = record(pinned).copy(metadata = record(pinned).metadata +
            (LocalManagedPackageMetadata.ANDROID_COMPONENT_NATIVE_TOOLS_KEY to "aapt2=${"c".repeat(64)}"))
        assertFalse(AndroidLocalComponentReceiptPolicy.matches(extra, lock(pinned), pinned))
    }
}
