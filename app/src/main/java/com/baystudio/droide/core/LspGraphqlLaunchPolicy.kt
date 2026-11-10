package com.baystudio.droide.core

import java.security.MessageDigest

internal object LspGraphqlLaunchPolicy {
    const val PROFILE="graphql-language-service-5.7.0-graphql-16.13.2-local-schema-v1"
    const val POLICY_REVISION=PROFILE
    const val ROOT="/opt/droide/packages/lsp.graphql-library/5.7.0/arm64-v8a"
    const val NODE_ROOT="/opt/droide/packages/runtime.graphql-node/22.23.3/arm64-v8a"
    const val NODE="$NODE_ROOT/payload/tree/node-v22.23.3-linux-arm64/bin/node"
    const val ADAPTER="payload/graphql-adapter.cjs"
    const val WIRE="payload/graphql-wire.cjs"
    const val BOOTSTRAP="payload/graphql-bootstrap.cjs"
    const val COMMAND="droide-graphql-local-lsp"
    const val HEALTH="payload/graphql-health.cjs"
    const val NODE_HEALTH="payload/graphql-node-health.cjs"
    fun sha(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    fun safeManagedPath(path:String)=path.matches(Regex("/[A-Za-z0-9_./-]+"))&&path.split('/').none{it=="."||it==".."}
    fun matchesIdentity(i:GraphqlManagedLaunchIdentity):Boolean =
        i.familyId=="lsp.graphql-library"&&i.version=="5.7.0"&&i.runtimeFamilyId=="runtime.graphql-node"&&i.runtimeVersion=="22.23.3"&&i.sourcePolicyRevision==PROFILE&&
        i.launchExecutable=="$ROOT/payload/bin/droide-graphql-local-lsp"&&i.nodeExecutable==NODE&&
        listOf(i.receiptSha256,i.runtimeReceiptSha256,i.sealSha256,i.runtimeSealSha256,i.selectedPackageIdentity,i.closureSha256,i.sourceBindingSha256,i.importReviewSha256,i.noticeAdmissionSha256,i.dataAdmissionSha256).all{it.matches(Regex("[0-9a-f]{64}"))}&&
        listOf(i.activationIdentity,i.runtimeActivationIdentity).all{it.matches(Regex("[0-9a-f-]{36}"))}&&i.helpersSha256.keys==setOf(ADAPTER,BOOTSTRAP,WIRE,HEALTH,NODE_HEALTH)&&i.helpersSha256.values.all{it.matches(Regex("[0-9a-f]{64}"))}
    fun privateEnvironment(root:String,node:String=NODE):Map<String,String> {
        require(safeManagedPath(root)&&safeManagedPath(node))
        return linkedMapOf("HOME" to "$root/home","TMPDIR" to "$root/tmp","XDG_CONFIG_HOME" to "$root/config","XDG_CACHE_HOME" to "$root/cache","XDG_DATA_HOME" to "$root/data","XDG_STATE_HOME" to "$root/state","XDG_RUNTIME_DIR" to "$root/run","PATH" to node.substringBeforeLast('/'),"LANG" to "C.UTF-8","LC_ALL" to "C.UTF-8","TERM" to "dumb")
    }
    internal fun adapterArgv(i:GraphqlManagedLaunchIdentity,owner:LocalGraphqlPreparedOwner):List<String> {
        require(matchesIdentity(i));return GraphqlResourcePolicy.ANALYSIS.shellPrefix()+listOf(i.nodeExecutable)+GraphqlResourcePolicy.ANALYSIS.nodeArguments+listOf("$ROOT/$ADAPTER","${owner.guestRoot}/owner.json")
    }
}
