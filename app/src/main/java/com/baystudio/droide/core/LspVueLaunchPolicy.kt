package com.baystudio.droide.core

import java.security.MessageDigest

internal object LspVueLaunchPolicy {
    const val PROFILE="vue-3.3.13-tslsp-6.0.2-ts-6.0.3-node-22.23.3-private-v1"
    const val POLICY_REVISION=PROFILE
    const val ROOT="/opt/droide/packages/lsp.vue/3.3.13/arm64-v8a"
    const val HELPER="/opt/droide/packages/runtime.vue-helper/22.23.3/arm64-v8a/payload/tree/node-v22.23.3-linux-arm64/bin/node"
    const val SUPERVISOR="payload/vue-supervisor.cjs"
    const val MULTIPLEXER="payload/vue-multiplexer.cjs"
    const val HEALTH="payload/vue-native-health.cjs"
    const val HELPER_HEALTH="payload/vue-helper-identity-health.cjs"
    const val STARTUP_MS=45_000L
    const val RETIRE_MS=10_000L
    fun sha(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    fun safeManagedPath(path:String):Boolean=path.matches(Regex("/[A-Za-z0-9_./-]+"))&&path.split('/').none{it=="."||it==".."}
    fun matchesIdentity(i:VueManagedLaunchIdentity):Boolean=i.familyId=="lsp.vue"&&i.version=="3.3.13"&&i.helperFamilyId=="runtime.vue-helper"&&i.helperVersion=="22.23.3"&&i.sourcePolicyRevision==PROFILE&&i.helperExecutable==HELPER&&i.npmRoot=="$ROOT/payload/npm"&&
        i.launchExecutable=="$ROOT/payload/bin/vue-language-server"&&i.serverExecutable=="${i.npmRoot}/node_modules/@vue/language-server/bin/vue-language-server.js"&&
        i.typescriptLspExecutable=="${i.npmRoot}/node_modules/typescript-language-server/lib/cli.mjs"&&i.typescriptServerExecutable=="${i.npmRoot}/node_modules/typescript/lib/tsserver.js"&&
        i.typescriptLibrary=="${i.npmRoot}/node_modules/typescript/lib/typescript.js"&&i.pluginExecutable=="${i.npmRoot}/node_modules/@vue/typescript-plugin/index.js"&&
        listOf(i.receiptSha256,i.helperReceiptSha256,i.sealSha256,i.helperSealSha256,i.selectedPackageIdentity,i.helperConfigSha256,i.helperSourceBindingSha256,i.closureSha256).all{it.matches(Regex("[0-9a-f]{64}"))}
    fun environment(i:VueManagedLaunchIdentity,privateRoot:String):Map<String,String>{require(matchesIdentity(i)&&safeManagedPath(privateRoot));return privateEnvironment(privateRoot)}
    fun privateEnvironment(root:String):Map<String,String>{require(safeManagedPath(root));return mapOf("HOME" to "$root/home","TMPDIR" to "$root/tmp","XDG_CONFIG_HOME" to "$root/config","XDG_CACHE_HOME" to "$root/cache","XDG_DATA_HOME" to "$root/data","XDG_STATE_HOME" to "$root/state","XDG_RUNTIME_DIR" to "$root/run","PATH" to "$root/commands","LANG" to "C.UTF-8","LC_ALL" to "C.UTF-8","TERM" to "dumb")}
    internal fun supervisorArgv(i:VueManagedLaunchIdentity,owner:LocalVuePreparedOwner):List<String>{require(matchesIdentity(i));return listOf(i.helperExecutable,"$ROOT/$SUPERVISOR",owner.guestRoot)}
}
