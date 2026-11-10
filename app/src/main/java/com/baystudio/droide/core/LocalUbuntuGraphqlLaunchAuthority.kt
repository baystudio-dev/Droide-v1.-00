package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal object LocalUbuntuGraphqlLaunchAuthority {
    const val HEALTH_SEED="graphql-health-identity.json"
    const val POLICY=LspGraphqlLaunchPolicy.PROFILE

    val expectedHelperHashes:Map<String,String> = mapOf(
        "payload/graphql-adapter.cjs" to "830f722da27d12aa654c4b1587b4a7a406c2f0907398e1cb2b97c2fecc4ce6b7",
        "payload/graphql-bootstrap.cjs" to "4d86782e994071032d03341b0d5fa0f16516eeb7dcb3c566eff4f02073b83f4e",
        "payload/graphql-health.cjs" to "8fce968b544177c2bd15640df82af0e5ac985eb6476c5a6e72b54a70457721b1",
        "payload/graphql-node-health.cjs" to "8c85815056c3fdef31323b524a3256d4fe9d1cafcfc080ec367a76c78c305e61",
        "payload/graphql-wire.cjs" to "921200f129522fd60f383065c2139e467645b5197719a753b4557405356def9f"
    )
    private fun tuple(a:GraphqlPackageProof,n:GraphqlPackageProof){require(a.record.familyId=="lsp.graphql-library"&&a.record.version=="5.7.0"&&n.record.familyId=="runtime.graphql-node"&&n.record.version=="22.23.3");require(a.dependencyReceipts==mapOf(n.record.familyId to n.receiptSha256)&&n.dependencyReceipts.isEmpty());require(a.helpersSha256==expectedHelperHashes&&n.helpersSha256==expectedHelperHashes);require(a.closureSha256==n.closureSha256)}
    suspend fun inspect(appRoot:String,server:ManagedPackageRecord,records:List<ManagedPackageRecord>,selections:Map<String,String>,discovered:String,deadline:GraphqlDeadline):GraphqlManagedLaunchIdentity? {
        val context=currentCoroutineContext();val active={context.ensureActive();deadline.check()}
        return try{active();require(records.size<=512&&records.map{it.familyId to it.version}.distinct().size==records.size)
            require(selections["lsp.graphql-library"] in setOf(null,"5.7.0")&&selections["runtime.graphql-node"] in setOf(null,"22.23.3"))
            require(server==records.single{it.familyId=="lsp.graphql-library"&&it.version=="5.7.0"});val node=records.single{it.familyId=="runtime.graphql-node"&&it.version=="22.23.3"}
            val effective=LocalLinuxPackageEnvironment.selectedRecords(records,selections,appRoot);require(effective.singleOrNull{it.familyId==server.familyId}==server&&effective.singleOrNull{it.familyId==node.familyId}==node)
            val a=LocalUbuntuGraphqlInstaller.inspectPackage(appRoot,server,active);val n=LocalUbuntuGraphqlInstaller.inspectPackage(appRoot,node,active);tuple(a,n)
            val launch="${LspGraphqlLaunchPolicy.ROOT}/payload/bin/droide-graphql-local-lsp";require(discovered in setOf("droide-graphql-local-lsp",launch,"/opt/droide/bin/droide-graphql-local-lsp"))
            val projection=if(discovered=="/opt/droide/bin/droide-graphql-local-lsp")GraphqlWire.readBytes(File(appRoot,"managed/ubuntu-bin/droide-graphql-local-lsp"),65536).toString(Charsets.UTF_8).also{require(it=="#!/bin/sh\nexec '$launch' \"\$@\"\n")}else "version-specific"
            val selected=LspGraphqlLaunchPolicy.sha((selections.filterKeys{it in setOf(server.familyId,node.familyId)}.toSortedMap().toString()+":"+projection).toByteArray())
            GraphqlManagedLaunchIdentity(server.familyId,server.version,node.familyId,node.version,POLICY,a.receiptSha256,n.receiptSha256,a.sealSha256,n.sealSha256,selected,discovered,launch,LspGraphqlLaunchPolicy.NODE,a.activationId,n.activationId,a.closureSha256,a.sourceBindingSha256,a.importReviewSha256,a.noticeAdmissionSha256,a.dataAdmissionSha256,a.helpersSha256.toSortedMap()).also{require(LspGraphqlLaunchPolicy.matchesIdentity(it))}
        }catch(c:CancellationException){throw c}catch(_:Exception){null}
    }
    fun provePackage(appRoot:String,record:ManagedPackageRecord,active:()->Unit={})=LocalUbuntuGraphqlInstaller.provePackage(appRoot,record,active)
    fun prove(appRoot:String,identity:GraphqlManagedLaunchIdentity,records:List<ManagedPackageRecord>,deadline:GraphqlDeadline,active:()->Unit){require(LspGraphqlLaunchPolicy.matchesIdentity(identity));val bounded={active();deadline.check()}
        val a=provePackage(appRoot,records.single{it.familyId==identity.familyId&&it.version==identity.version},bounded);val n=provePackage(appRoot,records.single{it.familyId==identity.runtimeFamilyId&&it.version==identity.runtimeVersion},bounded);tuple(a,n)
        require(listOf(a.receiptSha256,n.receiptSha256,a.sealSha256,n.sealSha256,a.activationId,n.activationId,a.closureSha256,a.sourceBindingSha256,a.importReviewSha256,a.noticeAdmissionSha256,a.dataAdmissionSha256)==listOf(identity.receiptSha256,identity.runtimeReceiptSha256,identity.sealSha256,identity.runtimeSealSha256,identity.activationIdentity,identity.runtimeActivationIdentity,identity.closureSha256,identity.sourceBindingSha256,identity.importReviewSha256,identity.noticeAdmissionSha256,identity.dataAdmissionSha256));require(identity.helpersSha256==expectedHelperHashes)
    }
    /** Exact Node + bash + env dependency/symbol closure. Does not claim atomic later exec resolution. */
    fun requireExecutionClosure(node:NimRuntimeClosure.Inventory,guestRoot:File,active:()->Unit):NimRuntimeClosure.Closure {
        val files=linkedMapOf<String,String>();val native=linkedMapOf<String,NimRuntimeClosure.NativeImage>()
        for(path in listOf("/bin/bash","/usr/bin/env")) {
            active();val file=requireNotNull(NimRuntimeClosure.resolveGuestFile(guestRoot,path,active))
            val relative=file.relativeTo(guestRoot).invariantSeparatorsPath
            require(relative in if(path=="/bin/bash")setOf("bin/bash","usr/bin/bash")else setOf("usr/bin/env","bin/env"))
            val image=NimRuntimeClosure.inspectElf(file,relative,NimRuntimeClosure.NativeRole.EXECUTABLE,active)
            require(image.soname==null&&!image.originSearch)
            native[relative]=image;files[relative]=NimRuntimeClosure.measure(file,16_777_216,active).second
        }
        val launchers=NimRuntimeClosure.Inventory(guestRoot,files.keys,native,files,emptyMap())
        val closure=NimRuntimeClosure.requireExecutionClosure(listOf(node,launchers),guestRoot,emptySet(),active)
        val all=(closure.files+files).toSortedMap();return NimRuntimeClosure.Closure(all,NimRuntimeClosure.sha256(all.entries.joinToString("\n"){it.key+"\u0000"+it.value}))
    }
    fun guestWrapper(family:String,guestRoot:String):String {require((family=="lsp.graphql-library"&&guestRoot==LspGraphqlLaunchPolicy.ROOT)||(family=="runtime.graphql-node"&&guestRoot==LspGraphqlLaunchPolicy.NODE_ROOT));return "#!/bin/sh\nset -eu\nprintf '%s\\n' 'GraphQL requires its exact reviewed private owner; direct launch and generic health are unavailable.' >&2\nexit 64\n"}
    fun healthSeed(packageRoot:String,nodeExecutable:String,proof:GraphqlPackageProof):JsonObject {require(LspGraphqlLaunchPolicy.safeManagedPath(packageRoot)&&LspGraphqlLaunchPolicy.safeManagedPath(nodeExecutable));return buildJsonObject{put("schema",1);put("policy",POLICY);put("packageRoot",packageRoot);put("nodeExecutable",nodeExecutable);put("packageReceiptSha256",proof.receiptSha256);put("runtimeReceiptSha256",proof.dependencyReceipts["runtime.graphql-node"]?:proof.receiptSha256);put("sourceBindingSha256",proof.sourceBindingSha256);put("importReviewSha256",proof.importReviewSha256);put("noticeAdmissionSha256",proof.noticeAdmissionSha256);put("dataAdmissionSha256",proof.dataAdmissionSha256);put("helpersSha256",JsonObject(proof.helpersSha256.mapValues{JsonPrimitive(it.value)}))}}
}
