package com.baystudio.droide.core

import java.security.MessageDigest

internal object LspPowerShellLaunchPolicy {
    const val REVISION = "powershell-7.6.7-pses-4.7.0-consolehost-pipe-v1"
    const val HEALTH_SELECTOR = "--droide-powershell-health-v1"
    const val NODE = "/usr/bin/node"
    const val ADAPTER_HOST_VERSION = "1.0.0"
    val editorIdentity: String by lazy { "$REVISION:sha256=${sha256(LocalUbuntuPowerShellLspHealth.script)}:profiles-consented:closed-child-stdin:private-pipe" }
    internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun matchesIdentity(identity: PowerShellManagedLaunchIdentity): Boolean = with(identity) {
        familyId == LocalUbuntuPowerShellEditorServicesPolicy.FAMILY && version == LocalUbuntuPowerShellEditorServicesPolicy.VERSION &&
            runtimeFamilyId == LocalUbuntuPowerShellRuntimePolicy.FAMILY && runtimeVersion == LocalUbuntuPowerShellRuntimePolicy.VERSION &&
            sourcePolicyRevision == REVISION && publisherArchiveSha256 == LocalUbuntuPowerShellEditorServicesPolicy.SHA256 &&
            runtimeArchiveSha256 == LocalUbuntuPowerShellRuntimePolicy.SHA256 && runtimeExecutable == LocalUbuntuPowerShellEditorServicesPolicy.RUNTIME_GUEST &&
            launchExecutable == "/opt/droide/packages/$familyId/$version/arm64-v8a/payload/bin/powershell-editor-services" &&
            discoveredExecutable in setOf(launchExecutable, "/opt/droide/bin/powershell-editor-services") &&
            listOf(receiptSha256, runtimeReceiptSha256, sealSha256, runtimeSealSha256, selectedPackageIdentity).all { it.matches(Regex("[0-9a-f]{64}")) } &&
            listOf(activationIdentity, runtimeActivationIdentity).all { it.matches(Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) }
    }

    internal fun handoff(health: Boolean): String = buildString {
        append("set -eu; umask 077; lease=\"${'$'}{DROIDE_PROCESS_LEASE-}\"; if [ -n \"${'$'}lease\" ]; then case \"${'$'}lease\" in *[!0-9a-f]*) exit 64;; esac; [ \"${'$'}{#lease}\" -eq 20 ] || exit 64; set -- \"DROIDE_PROCESS_LEASE=${'$'}lease\" \"${'$'}@\"; fi; ")
        if (!health) for (key in listOf("PSModulePath", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME", "XDG_STATE_HOME", "XDG_RUNTIME_DIR"))
            append("if [ \"${'$'}{$key+x}\" = x ]; then set -- \"$key=${'$'}$key\" \"${'$'}@\"; fi; ")
        append(if (health) "exec /usr/bin/env -i HOME=/nonexistent PATH= " else "exec /usr/bin/env -i \"HOME=${'$'}{HOME-}\" \"PATH=${'$'}{PATH-}\" ")
        append("LANG=C.UTF-8 LC_ALL=C.UTF-8 TERM=dumb POWERSHELL_TELEMETRY_OPTOUT=1 POWERSHELL_UPDATECHECK=Off DOTNET_EnableDiagnostics=0 \"${'$'}@\"")
    }
    fun supervisorCommand(root: String, runtime: Boolean = false, health: Boolean = true): List<String> {
        require(root.startsWith('/') && root.length <= 4095 && root.none { it.code < 32 } && root.split('/').none { it == ".." || it == "." })
        return listOf("/bin/sh", "-c", handoff(health), "droide-powershell-env", NODE,
            "$root/${LocalUbuntuPowerShellLspHealth.FILE_NAME}", if (runtime) "runtime-health" else if (health) "health" else "editor", root)
    }
    fun guestWrapper(recipe: LocalUbuntuVendorRecipe, target: String, root: String): String {
        recipe.validate()
        val runtime = recipe.familyId == LocalUbuntuPowerShellRuntimePolicy.FAMILY
        require(runtime || recipe.familyId == LocalUbuntuPowerShellEditorServicesPolicy.FAMILY)
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        return buildString {
            append("#!/bin/sh\nset -eu\n")
            append("if [ \"${'$'}#\" -eq 1 ] && [ \"${'$'}1\" = ").append(q(HEALTH_SELECTOR)).append(" ]; then exec ")
                .append(supervisorCommand(root, runtime).joinToString(" ", transform = ::q)).append("; fi\n")
            if (runtime) append("exec ").append(q(target)).append(" \"${'$'}@\"\n")
            else {
                append("[ \"${'$'}#\" -eq 0 ] || { echo 'PSES accepts no editor arguments or the sole managed health selector' >&2; exit 64; }\n")
                append("exec ").append(supervisorCommand(root, health = false).joinToString(" ", transform = ::q)).append("\n")
            }
        }
    }
}
