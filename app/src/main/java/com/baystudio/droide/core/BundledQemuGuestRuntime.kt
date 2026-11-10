package com.baystudio.droide.core

import android.content.Context

// Historical provenance identity, not an asset shipped by the current runtime.
object BundledQemuGuestSpec {
    const val FILE_NAME = "qemu-offline-alpine-v3.24-arm64.tar"
    const val SHA256 = "406fbb16607902b7e39bc8b8c90bca3dda1c36772166b4f0d0777bb9b5d5b0f6"
    const val BYTES = 38_912_000L
    const val VERSION = "11.0.3-r0"
    val commands = listOf("qemu-x86_64", "qemu-system-aarch64", "qemu-system-x86_64", "qemu-img")
}
/** Compatibility tombstone. No old guest, cache, journal or engine bytes are changed. */
@Suppress("UNUSED_PARAMETER")
class BundledQemuGuestRuntime(
    context: Context,
    private val alpine: WorkstationGuestEnvironmentManager = WorkstationGuestEnvironmentManager(context.applicationContext),
) {
    suspend fun ensureInstalled(): String = LegacyAlpineRuntimeRetirement.reject()
}
