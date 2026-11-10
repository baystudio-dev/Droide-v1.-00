package com.baystudio.droide.core

object LocalUbuntuCMakeLaunchPolicy {
    const val SUPERVISOR_SHA256 = "454f4f7c56ee86a280414d776fa69d1d83412238cb961d77daf14f95b4b0cee8"
    const val EDITOR_POLICY = "cmake-editor-bounded-owner-v1"
    const val ENVIRONMENT_POLICY = "cmake-editor-clean-env-v1"
    const val OWNER_WALL_MS = 1_800_000L
    const val STARTUP_MS = 30_000L
    const val GROUP_TEARDOWN_MS = 8_000L
    const val PRIVATE_CLEANUP_MS = 8_000L
    const val MAXIMUM_RECORD_BYTES = 16_384
    const val MAXIMUM_OWNERS = 32

    fun environment(identity: CMakeManagedLaunchIdentity, state: CMakePrivateState, health: Boolean = false): Map<String, String> {
        require(identity.pythonExecutableGuestAbsolute == "/usr/bin/python3.12")
        require(identity.cmakeExecutableGuestAbsolute == "/usr/bin/cmake")
        val bins = identity.approvedToolBinGuestPaths.toList()
        require(bins.size <= 32 && bins.distinct().size == bins.size)
        bins.forEach { require(it.startsWith('/') && ':' !in it && it.none(Char::isISOControl) && !it.contains("/../")) }
        return mapOf(
            "HOME" to state.guest("home"), "TMPDIR" to state.guest("tmp"),
            "XDG_CONFIG_HOME" to state.guest("xdg-config"), "XDG_CACHE_HOME" to state.guest("xdg-cache"),
            "XDG_DATA_HOME" to state.guest("xdg-data"), "XDG_STATE_HOME" to state.guest("xdg-state"),
            "XDG_RUNTIME_DIR" to state.guest("xdg-run"), "LANG" to "C.UTF-8", "LC_ALL" to "C.UTF-8",
            "PATH" to if (health) "" else (listOf("/usr/bin", "/bin") + bins).joinToString(":"),
            "DROIDE_CMAKE_POLICY" to if (health) "health" else "editor",
        )
    }

    internal fun supervisorArgv(identity: CMakeManagedLaunchIdentity, owner: LocalCMakePreparedOwner): List<String> =
        listOf(identity.pythonExecutableGuestAbsolute, "-I", "-S", "-B", identity.supervisorGuestAbsolute,
            "--control-root", owner.privateState.guestRoot, "--nonce", owner.nonce, "--policy", "editor", "--",
            identity.pythonExecutableGuestAbsolute, "-I", "-S", "-B", identity.bootstrapGuestAbsolute)
}
