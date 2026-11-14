package com.baystudio.droide.core

import java.io.File

/** User bin locations in each guest's own /root; no bind or binary sharing between guests. */
internal object LocalLinuxUserEnvironment {
    // npm derives its global prefix from the selected Node installation otherwise. User CLI
    // installs must not alter a sealed managed Node payload. Explicit --prefix still wins.
    val environment: Map<String, String> = mapOf("NPM_CONFIG_PREFIX" to "/root/.local")
    private val userBins = listOf(
        "/root/.local/bin", "/root/.local/share/mise/shims", "/root/.dotnet", "/root/.opencode/bin", "/root/.npm-global/bin",
        "/root/.local/share/pnpm", "/root/.bun/bin", "/root/.cargo/bin", "/root/go/bin",
    )
    private val systemBins = listOf(
        "/usr/local/sbin", "/usr/local/bin", "/usr/sbin", "/usr/bin", "/sbin", "/bin",
    )

    /** Only the known guest-owned bin roots; managed bind paths have separate authority. */
    fun executableRoots(): List<String> = userBins + userSdkBins() + systemBins

    /** Standard user SDK roots are considered before an implicit managed SDK default. */
    val userAndroidSdkRoots = listOf("/root/Android/Sdk", "/root/.local/share/android-sdk", "/root/android-sdk")

    private fun userSdkBins(): List<String> = userAndroidSdkRoots.flatMap { root ->
        listOf("$root/platform-tools", "$root/cmdline-tools/latest/bin", "$root/tools/bin")
    }

    fun installedUserAndroidSdkRoot(rootfs: File): String? = userAndroidSdkRoots.firstOrNull { guestPath ->
        val sdk = File(rootfs, guestPath.removePrefix("/"))
        File(sdk, "platforms").listFiles()?.any { it.isDirectory && it.name.startsWith("android-") } == true &&
            File(sdk, "build-tools").listFiles()?.any { it.isDirectory } == true
    }

    /** A default receipt must not pin Java/SDK over a tool the user updated in their home. */
    fun guestEnvironment(
        managedEnvironment: Map<String, String>,
        workspaceSelections: Map<String, String>,
        userAndroidSdkRoot: String? = null,
    ): Map<String, String> = (environment + managedEnvironment).toMutableMap().apply {
        val explicitPack = LocalAndroidWorkstationPolicy.FAMILY in workspaceSelections
        if (!explicitPack && LocalUbuntuJdkPolicy.FAMILY !in workspaceSelections) remove("JAVA_HOME")
        val explicitSdk = explicitPack || workspaceSelections.keys.any {
            it in setOf("sdk.android", "build.android-tools", "toolchain.android-ndk", "build.cmake")
        }
        if (!explicitSdk && userAndroidSdkRoot != null) {
            require(userAndroidSdkRoot in userAndroidSdkRoots) { "Unknown user Android SDK root" }
            put("ANDROID_HOME", userAndroidSdkRoot)
            put("ANDROID_SDK_ROOT", userAndroidSdkRoot)
        }
    }.toMap()

    fun path(managedBins: List<String>, preferredManagedBins: List<String> = emptyList()): String =
        // Keep the directory in PATH before installation, so an already-open shell discovers
        // the official OpenCode installer output without sourcing or replacing user rc files.
        (preferredManagedBins + userBins + userSdkBins() + managedBins.sortedBy { if (it == "/opt/droide/bin") 0 else 1 } + systemBins)
            .distinct().joinToString(":")
}
