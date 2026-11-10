package com.baystudio.droide.core

import java.security.MessageDigest

internal object LspElpLaunchPolicy {
    const val PROFILE = "elp-2026.10.14-otp27.3-selected-runtime-v1"
    const val POLICY_REVISION = "elp-original-otp27.3-v1"
    const val WORKSPACE_ROOT = "/workspace"
    const val ROOT = "/opt/droide/packages/lsp.elp/2026.10.14-otp27.3/arm64-v8a"
    const val COMMAND = "elp"
    const val NATIVE = "$ROOT/payload/tree/elp"
    const val REPORT = "runtime-report.escript"
    const val OBSERVER = "metadata-observer.py"
    const val SHIM = "rebar-shim.sh"
    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun digest(value: String) = value.matches(Regex("[0-9a-f]{64}"))
    fun path(value: String): Boolean = value.startsWith('/') && value.toByteArray().size <= 4096 &&
        value.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." } && value.none { it.code < 32 || it.code == 127 }
    fun matchesIdentity(identity: ElpManagedLaunchIdentity): Boolean = identity.familyId == "lsp.elp" &&
        identity.version == "2026.10.14-otp27.3" && identity.sourcePolicyRevision == POLICY_REVISION &&
        identity.launchExecutable == NATIVE && identity.originalMemberBytes in 1..ElpResourcePolicy.PRIVATE_BYTES_MAX &&
        listOf(identity.receiptSha256, identity.sealSha256, identity.selectedPackageIdentity,
            identity.originalMemberSha256).all(::digest) && identity.activationIdentity.isNotBlank()
    fun nativeArgv(identity: ElpManagedLaunchIdentity, selection: ElpToolSelection): List<String> {
        require(matchesIdentity(identity))
        return listOf(identity.launchExecutable, "--erl", selection.erl.file.guestPath,
            "--escript", selection.escript.file.guestPath, "server")
    }
    fun environment(privateRoot: String, selection: ElpToolSelection?, health: Boolean,
        ordinary: Map<String, String> = emptyMap(), token: String): Map<String, String> {
        require(path(privateRoot) && token.matches(Regex("[0-9a-f]{32}")))
        val allowed = setOf("HOME", "PATH", "USER", "LOGNAME", "LANG", "LC_ALL", "TZ",
            "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "XDG_DATA_HOME", "XDG_STATE_HOME", "XDG_RUNTIME_DIR",
            "http_proxy", "https_proxy", "no_proxy", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY")
        val result = if (health) linkedMapOf() else ordinary.filterKeys { it in allowed }.toMutableMap()
        result.putIfAbsent("HOME", if (health) "$privateRoot/home" else "/root")
        for ((key, dir) in mapOf("XDG_CONFIG_HOME" to "config", "XDG_CACHE_HOME" to "cache", "XDG_DATA_HOME" to "data", "XDG_STATE_HOME" to "state", "XDG_RUNTIME_DIR" to "run"))
            result.putIfAbsent(key, if (health) "$privateRoot/$dir" else "/root/.${if (dir == "config") "config" else if (dir == "cache") "cache" else "local/$dir"}")
        val basePath = if (health) "/usr/bin:/bin" else result["PATH"] ?: LocalLinuxUserEnvironment.path(emptyList())
        result["PATH"] = listOfNotNull("$privateRoot/shim", selection?.erl?.file?.guestPath?.substringBeforeLast('/'), basePath).joinToString(":")
        result["LANG"] = "C.UTF-8"; result["LC_ALL"] = "C.UTF-8"; result["TMPDIR"] = "$privateRoot/tmp"
        result["DROIDE_ELP_OWNER_TOKEN"] = token
        if (selection != null) {
            result["ELP_ESCRIPT"] = selection.escript.file.guestPath
            result["ESCRIPT_EMULATOR"] = selection.erl.file.guestPath
            result["ERL_ROOTDIR"] = selection.erl.installationRoot
            result["ELP_REBAR3"] = "$privateRoot/shim/rebar3"
            result["DROIDE_ELP_SELECTED_ESCRIPT"] = selection.escript.file.guestPath
            selection.rebar?.let { result["DROIDE_ELP_SELECTED_REBAR"] = it.file.guestPath }
            result["ELP_ERLANG_SERVICE_SCHEDULERS"] = "2"
        }
        // Ambient ELP/OTP/Rebar/loader/shell selectors never enter this positive allowlist.
        return result.toMap()
    }
}
