package com.baystudio.droide.core

import java.security.MessageDigest

internal object LspCSharpLaunchPolicy {
    const val PROFILE = "csharp-original-0.28.0-sdk-10.0.402-node-22.23.3-private-v1"
    const val POLICY_REVISION = PROFILE
    const val ROOT = "/opt/droide/packages/lsp.csharp-ls/0.28.0/arm64-v8a"
    const val SDK_ROOT = "/opt/droide/packages/runtime.csharp-sdk/10.0.402/arm64-v8a/payload/tree"
    const val HELPER = "/opt/droide/packages/runtime.csharp-helper/22.23.3/arm64-v8a/payload/tree/node-v22.23.3-linux-arm64/bin/node"
    const val SUPERVISOR = "payload/csharp-supervisor.cjs"
    const val HELD_LAUNCHER = "payload/csharp-held-launcher.cjs"
    const val IDENTITY = "payload/csharp-identity.cjs"
    const val HEALTH = "payload/csharp-health.cjs"
    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun safeManagedPath(path: String) = path.matches(Regex("/[A-Za-z0-9_./-]+")) && path.split('/').none { it == "." || it == ".." }
    fun matchesIdentity(i: CSharpManagedLaunchIdentity): Boolean =
        i.familyId == "lsp.csharp-ls" && i.version == "0.28.0" &&
        i.sdkFamilyId == "runtime.csharp-sdk" && i.sdkVersion == "10.0.402" &&
        i.helperFamilyId == "runtime.csharp-helper" && i.helperVersion == "22.23.3" &&
        i.sourcePolicyRevision == PROFILE && i.helperExecutable == HELPER && i.sdkRoot == SDK_ROOT &&
        i.sdkExecutable == "$SDK_ROOT/dotnet" && i.launchExecutable == "$ROOT/payload/bin/csharp-ls" &&
        i.serverExecutable == "$ROOT/payload/tree/tools/net10.0/any/CSharpLanguageServer.dll" &&
        listOf(i.receiptSha256, i.sdkReceiptSha256, i.helperReceiptSha256, i.sealSha256, i.sdkSealSha256,
            i.helperSealSha256, i.selectedPackageIdentity, i.closureSha256).all { it.matches(Regex("[0-9a-f]{64}")) } &&
        listOf(i.activationIdentity, i.sdkActivationIdentity, i.helperActivationIdentity).all { it.matches(Regex("[0-9a-f-]{36}")) }

    fun environment(i: CSharpManagedLaunchIdentity, privateRoot: String): Map<String, String> {
        require(matchesIdentity(i)); return privateEnvironment(privateRoot, i.sdkRoot)
    }
    fun privateEnvironment(root: String, sdkRoot: String = SDK_ROOT): Map<String, String> {
        require(safeManagedPath(root) && safeManagedPath(sdkRoot))
        return linkedMapOf("HOME" to "$root/home", "TMPDIR" to "/tmp", "XDG_CONFIG_HOME" to "$root/config",
            "XDG_CACHE_HOME" to "$root/cache", "XDG_DATA_HOME" to "$root/data", "XDG_STATE_HOME" to "$root/state",
            "XDG_RUNTIME_DIR" to "$root/run", "DOTNET_CLI_HOME" to "$root/home", "NUGET_PACKAGES" to "$root/nuget",
            "NUGET_HTTP_CACHE_PATH" to "$root/nuget-http", "DOTNET_ROOT" to sdkRoot,
            "DOTNET_HOST_PATH" to "$sdkRoot/dotnet", "DOTNET_MULTILEVEL_LOOKUP" to "0",
            "DOTNET_EnableDiagnostics" to "0", "DOTNET_EnableDiagnostics_IPC" to "0",
            "DOTNET_CLI_TELEMETRY_OPTOUT" to "1", "DOTNET_SKIP_FIRST_TIME_EXPERIENCE" to "1",
            "DOTNET_CLI_WORKLOAD_UPDATE_NOTIFY_DISABLE" to "1", "PATH" to sdkRoot,
            "LANG" to "C.UTF-8", "LC_ALL" to "C.UTF-8", "TERM" to "dumb") + CSharpResourcePolicy.ANALYSIS.dotnetEnvironment
    }
    internal fun supervisorArgv(i: CSharpManagedLaunchIdentity, owner: LocalCSharpPreparedOwner): List<String> {
        require(matchesIdentity(i)); return CSharpResourcePolicy.ANALYSIS.shellPrefix() +
            listOf(i.helperExecutable) + CSharpResourcePolicy.ANALYSIS.helperArguments + listOf("$ROOT/$SUPERVISOR", owner.guestRoot)
    }
}
