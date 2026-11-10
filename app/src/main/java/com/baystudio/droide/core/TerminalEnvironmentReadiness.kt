package com.baystudio.droide.core

/** Identity captured together with the selected terminal, never inferred from its PATH. */
internal interface TerminalEnvironmentIdentitySession {
    val environmentProfile: TerminalManager.Profile?
}

internal data class TerminalInstallHint(
    val commands: List<String>,
    val detail: String,
    val managedFamily: String? = null,
)

/** Read-only suggestions. The normal install/shell permission and package authority still apply. */
internal object TerminalEnvironmentReadiness {
    private val ubuntuFamilies = mapOf(
        "git" to "cli.git", "curl" to "cli.curl", "wget" to "cli.wget",
        "node" to "runtime.node", "npm" to "runtime.node", "npx" to "runtime.node",
        "python" to "runtime.python", "python3" to "runtime.python", "pip" to "package.pip", "pip3" to "package.pip",
        "pnpm" to "package.pnpm", "yarn" to "package.yarn",
    )
    private val alpinePackages = mapOf(
        "git" to listOf("git"), "curl" to listOf("curl"), "wget" to listOf("wget"),
        "node" to listOf("nodejs", "npm"), "npm" to listOf("nodejs", "npm"), "npx" to listOf("nodejs", "npm"),
        "python" to listOf("python3"), "python3" to listOf("python3"), "pip" to listOf("python3", "py3-pip"), "pip3" to listOf("python3", "py3-pip"),
    )

    fun label(profile: TerminalManager.Profile?): String = when (profile) {
        TerminalManager.Profile.LOCAL -> "android-local"
        TerminalManager.Profile.LOCAL_LINUX_ARM64 -> "ubuntu-arm64"
        TerminalManager.Profile.LOCAL_QEMU -> "alpine"
        TerminalManager.Profile.DEVICE_ADB -> "connected-android-device"
        null -> "unknown"
    }

    fun missing(command: String, profile: TerminalManager.Profile?): TerminalInstallHint? = when (profile) {
        TerminalManager.Profile.LOCAL_LINUX_ARM64 -> ubuntuFamilies[command]?.let { family ->
            TerminalInstallHint(listOf("droide pkg install $family"),
                "Install or inspect this Ubuntu tool through the same authority as Extensions; healthy packages and the shared guest are reused. Re-probe afterward. Installation is not performed by this check.", family)
        }
        TerminalManager.Profile.LOCAL_QEMU -> alpinePackages[command]?.let { packages ->
            TerminalInstallHint(listOf("apk add " + packages.joinToString(" ")),
                "Install in this Alpine guest only; Ubuntu installation is not required. Packages and native binaries remain guest-specific. Re-probe afterward." +
                    if (command == "python") " Use python3; installing Python does not guarantee a python alias." else "")
        }
        TerminalManager.Profile.LOCAL -> if (command in ubuntuFamilies || command in alpinePackages)
            TerminalInstallHint(emptyList(), "Open the Ubuntu terminal and install its tools. Android Local does not inherit Linux executables or apt/apk; this check does not create another terminal.") else null
        TerminalManager.Profile.DEVICE_ADB -> if (command in ubuntuFamilies || command in alpinePackages)
            TerminalInstallHint(emptyList(), "Open the Ubuntu terminal for project commands. ADB is used for Android deployment and debugging.") else null
        null -> null
    }
}
