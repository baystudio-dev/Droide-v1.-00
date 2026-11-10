package com.baystudio.droide.core

import com.baystudio.droide.core.AndroidDevelopmentManager.BuildBackend

internal object AndroidBuildRouting {
    fun requireUbuntuBackend(requested: BuildBackend?): BuildBackend {
        check(requested != BuildBackend.DEVICE_WORKSTATION) {
            "Droide builds use Ubuntu. Device Workstation builds are unavailable; ADB remains available for deploy and debugging."
        }
        return BuildBackend.LOCAL_UBUNTU
    }

    suspend fun <L : Any, R> executeUbuntu(
        requiredBackend: BuildBackend?,
        resolveLocal: suspend () -> L?,
        runLocal: suspend (L) -> R,
        unavailableReason: () -> String = { "Ubuntu Android toolchain unavailable. Install compatible JDK, SDK and Build Tools in Extensions." },
    ): R {
        requireUbuntuBackend(requiredBackend)
        val local = resolveLocal() ?: error(unavailableReason())
        return runLocal(local)
    }

    suspend fun <L : Any, R> execute(
        requiredBackend: BuildBackend?,
        resolveLocal: suspend () -> L?,
        runLocal: suspend (L) -> R,
        runDevice: suspend () -> R,
    ): R {
        if (requiredBackend == BuildBackend.DEVICE_WORKSTATION) return runDevice()
        val local = resolveLocal()
        if (local != null) return runLocal(local)
        check(requiredBackend != BuildBackend.LOCAL_UBUNTU) { "The required Local Ubuntu build backend is unavailable" }
        return runDevice()
    }
}
