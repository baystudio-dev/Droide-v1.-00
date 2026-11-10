package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest

/** Private fixed-mode launch only. It never resolves terminal Node/Nim or widens ordinary PATH. */
internal object LspNimLaunchPolicy {
    const val POLICY_REVISION = "nimlangserver-1.16.0-nim-2.2.12-helper-22.23.3-fixed-v1"
    const val PROFILE = "nimlangserver-1.16.0-nim-2.2.12-fixed-analysis-v1"
    const val REVISION = POLICY_REVISION
    const val SUPERVISOR = "payload/nim-supervisor.cjs"
    const val ADAPTER = "payload/nimble-dump-adapter.cjs"
    const val HEALTH = "payload/nim-zero-document-health.cjs"
    const val HELPER_HEALTH = "payload/nim-helper-identity-health.cjs"
    const val METADATA_MS = 30_000L
    const val RETIRE_MS = 10_000L
    const val HELPER = "/opt/droide/packages/runtime.nim-helper/22.23.3/arm64-v8a/payload/tree/node-v22.23.3-linux-arm64/bin/node"
    const val SOURCE_COMMIT = "80dc632040e6bada37aac1220dde9c79581c9c22"
    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun matchesIdentity(i: NimManagedLaunchIdentity): Boolean =
        i.familyId == "lsp.nimlangserver" && i.version == "1.16.0" && i.toolchainFamilyId == "runtime.nim-lsp" &&
            i.toolchainVersion == "2.2.12" && i.helperFamilyId == "runtime.nim-helper" && i.helperVersion == "22.23.3" &&
            i.sourcePolicyRevision == POLICY_REVISION && i.helperExecutable == HELPER &&
            i.publisherArchiveSha256 == "024a8b3e6d8d1f7c35694048704754d3d660b520f9a0331822f5a579a964925e" &&
            i.toolchainArchiveSha256 == "3dc04191af72a8310cf1c8ffb6f772cc3716fc5f264bc7c453ab4aecb4085221" &&
            i.helperArchiveSha256 == "5ced2d48d1d7198739b7f86804de0171aefb6823b684b12341d3321afc3cb0b2" &&
            listOf(i.receiptSha256,i.toolchainReceiptSha256,i.helperReceiptSha256,i.sealSha256,i.toolchainSealSha256,
                i.helperSealSha256,i.selectedPackageIdentity,i.helperConfigSha256,i.helperSourceBindingSha256,i.closureSha256).all { it.matches(Regex("[0-9a-f]{64}")) } &&
            listOf(i.serverExecutable,i.compilerExecutable,i.backendExecutable,i.nimbleExecutable).all(::safeManagedPath) &&
            File(i.compilerExecutable).parent == File(i.backendExecutable).parent && File(i.compilerExecutable).parent == File(i.nimbleExecutable).parent
    fun safeManagedPath(path: String): Boolean = path.matches(Regex("/[A-Za-z0-9_./-]+")) &&
        path.split('/').none { it == "." || it == ".." } && !path.contains("nimbinaries")
    fun packageRoot(i: NimManagedLaunchIdentity): String = "/opt/droide/packages/${i.familyId}/${i.version}/arm64-v8a"
    fun environment(i: NimManagedLaunchIdentity, privateRoot: String): Map<String,String> {
        require(matchesIdentity(i) && safeManagedPath(privateRoot))
        return mapOf("HOME" to "$privateRoot/home", "TMPDIR" to "$privateRoot/tmp", "XDG_CONFIG_HOME" to "$privateRoot/config",
            "XDG_CACHE_HOME" to "$privateRoot/cache", "XDG_DATA_HOME" to "$privateRoot/data", "XDG_STATE_HOME" to "$privateRoot/state",
            "XDG_RUNTIME_DIR" to "$privateRoot/run", "PATH" to "$privateRoot/commands", "LANG" to "C.UTF-8", "LC_ALL" to "C.UTF-8",
            "TERM" to "dumb", "DROIDE_NIM_OWNER" to privateRoot)
    }
    internal fun supervisorArgv(i: NimManagedLaunchIdentity, owner: LocalNimPreparedOwner): List<String> {
        require(matchesIdentity(i));return listOf(i.helperExecutable, "${packageRoot(i)}/$SUPERVISOR", owner.guestRoot)
    }
    fun refusalEntry(guestRoot: String): String {
        require(safeManagedPath(guestRoot))

        val helperScript="/opt/droide/packages/lsp.nimlangserver/1.16.0/arm64-v8a/$HELPER_HEALTH"
        return "#!/bin/sh\nexec '$HELPER' '$helperScript' --refuse '$guestRoot'\n"
    }
    fun adapterEntry(i: NimManagedLaunchIdentity): String {
        require(matchesIdentity(i));return "#!/bin/sh\nexec '${i.helperExecutable}' '${packageRoot(i)}/$ADAPTER' \"\$@\"\n"
    }
}
