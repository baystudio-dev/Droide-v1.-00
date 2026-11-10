package com.baystudio.droide.core

import android.content.Context
import java.io.File

/** Historical image identity only; this provider cannot download or import retired Alpine images. */
@Suppress("UNUSED_PARAMETER")
class AlpineQemuVmImageProvider(context: Context) {
    data class ResolvedImage(val spec: QemuVmImageSpec, val verifiedDownload: File)

    suspend fun resolveAndDownload(
        onProgress: (TrustedArtifactDownloadProgress) -> Unit = {},
    ): ResolvedImage = withResolvedImage(onProgress) { it }

    suspend fun <T> withResolvedImage(
        onProgress: (TrustedArtifactDownloadProgress) -> Unit = {},
        block: suspend (ResolvedImage) -> T,
    ): T = LegacyAlpineRuntimeRetirement.reject()

    companion object {
        const val VERSION = "3.24.2"
        const val EXPECTED_BYTES = 110_886_912L
        private const val OFFICIAL_HOST = "dl-cdn.alpinelinux.org"
        private const val BASE_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/cloud"
        private const val IMAGE_FILE = "alpine-3.24.2-x86_64-tiny-r0.qcow2"
        private const val METADATA_FILE = "alpine-3.24.2-x86_64-tiny-r0.yaml"
        const val IMAGE_URL = "$BASE_URL/$IMAGE_FILE"
        const val IMAGE_SHA512_URL = "$IMAGE_URL.sha512"
        const val METADATA_URL = "$BASE_URL/$METADATA_FILE"
        const val METADATA_SHA512_URL = "$METADATA_URL.sha512"

    }
}
