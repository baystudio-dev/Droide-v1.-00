package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal object LocalUbuntuTomlLaunchAuthority {
    const val HEALTH_SEED="toml-health-identity.json"
    const val POLICY=LspTomlLaunchPolicy.PROFILE
    val expectedHelperHashes:Map<String,String> = mapOf(
        "payload/toml-adapter.cjs" to "4cf1ae17b28170bc317686ce81991433808595498658270336932ff69fa701bc",
        "payload/toml-health.cjs" to "7b46541b840e06c1aaa6eb55b386279b4b7ec2cabadc6a8c919e88cbf51c2c36",
        "payload/toml-node-health.cjs" to "a9705178d9c342679efeb6c71fd218d4dd5dc4d60765e62d3b02f572b01f7ebb"
    )
    private fun tuple(a:TomlPackageProof,n:TomlPackageProof){require(a.record.familyId=="lsp.taplo-wasm"&&a.record.version=="0.8.0"&&n.record.familyId=="runtime.taplo-node"&&n.record.version=="22.23.3");require(a.dependencyReceipts==mapOf(n.record.familyId to n.receiptSha256)&&n.dependencyReceipts.isEmpty());require(a.helpersSha256==expectedHelperHashes&&n.helpersSha256==expectedHelperHashes);require(a.closureSha256==n.closureSha256)}
    suspend fun inspect(appRoot:String,server:ManagedPackageRecord,records:List<ManagedPackageRecord>,selections:Map<String,String>,discovered:String):TomlManagedLaunchIdentity? {
        val context=currentCoroutineContext();val active={context.ensureActive()}
        return try{require(records.size<=512&&records.map{it.familyId to it.version}.distinct().size==records.size)
            require(selections["lsp.taplo-wasm"] in setOf(null,"0.8.0")&&selections["runtime.taplo-node"] in setOf(null,"22.23.3"))
            require(server==records.single{it.familyId=="lsp.taplo-wasm"&&it.version=="0.8.0"});val node=records.single{it.familyId=="runtime.taplo-node"&&it.version=="22.23.3"}
            val effective=LocalLinuxPackageEnvironment.selectedRecords(records,selections,appRoot);require(effective.singleOrNull{it.familyId==server.familyId}==server&&effective.singleOrNull{it.familyId==node.familyId}==node)
            val a=LocalUbuntuTomlInstaller.inspectPackage(appRoot,server,active);val n=LocalUbuntuTomlInstaller.inspectPackage(appRoot,node,active);tuple(a,n)
            val launch="${LspTomlLaunchPolicy.ROOT}/payload/bin/droide-taplo-lsp";require(discovered in setOf("droide-taplo-lsp",launch,"/opt/droide/bin/droide-taplo-lsp"))
            val projection=if(discovered=="/opt/droide/bin/droide-taplo-lsp")TomlWire.readBytes(File(appRoot,"managed/ubuntu-bin/droide-taplo-lsp"),65536).toString(Charsets.UTF_8).also{require(it=="#!/bin/sh\nexec '$launch' \"\$@\"\n")}else "version-specific"
            val selected=LspTomlLaunchPolicy.sha((selections.filterKeys{it in setOf(server.familyId,node.familyId)}.toSortedMap().toString()+":"+projection).toByteArray())
            TomlManagedLaunchIdentity(server.familyId,server.version,node.familyId,node.version,POLICY,a.receiptSha256,n.receiptSha256,a.sealSha256,n.sealSha256,selected,discovered,launch,LspTomlLaunchPolicy.NODE,a.activationId,n.activationId,a.closureSha256,a.sourceBindingSha256,a.importReviewSha256,a.noticeAdmissionSha256,a.dataAdmissionSha256,a.helpersSha256.toSortedMap()).also{require(LspTomlLaunchPolicy.matchesIdentity(it))}
        }catch(c:CancellationException){throw c}catch(_:Exception){null}
    }
    fun provePackage(appRoot:String,record:ManagedPackageRecord,active:()->Unit={})=LocalUbuntuTomlInstaller.provePackage(appRoot,record,active)
    fun prove(appRoot:String,identity:TomlManagedLaunchIdentity,records:List<ManagedPackageRecord>,active:()->Unit){require(LspTomlLaunchPolicy.matchesIdentity(identity));val deadline=System.nanoTime()+60_000_000_000L;val bounded={active();check(System.nanoTime()<=deadline)}
        val a=provePackage(appRoot,records.single{it.familyId==identity.familyId&&it.version==identity.version},bounded);val n=provePackage(appRoot,records.single{it.familyId==identity.runtimeFamilyId&&it.version==identity.runtimeVersion},bounded);tuple(a,n)
        require(listOf(a.receiptSha256,n.receiptSha256,a.sealSha256,n.sealSha256,a.activationId,n.activationId,a.closureSha256,a.sourceBindingSha256,a.importReviewSha256,a.noticeAdmissionSha256,a.dataAdmissionSha256)==listOf(identity.receiptSha256,identity.runtimeReceiptSha256,identity.sealSha256,identity.runtimeSealSha256,identity.activationIdentity,identity.runtimeActivationIdentity,identity.closureSha256,identity.sourceBindingSha256,identity.importReviewSha256,identity.noticeAdmissionSha256,identity.dataAdmissionSha256));require(identity.helpersSha256==expectedHelperHashes)
    }
    fun guestWrapper(family:String,guestRoot:String):String {require((family=="lsp.taplo-wasm"&&guestRoot==LspTomlLaunchPolicy.ROOT)||(family=="runtime.taplo-node"&&guestRoot==LspTomlLaunchPolicy.NODE_ROOT));return "#!/bin/sh\nset -eu\nprintf '%s\\n' 'TOML requires its exact reviewed private owner; direct launch and generic health are unavailable.' >&2\nexit 64\n"}
    fun healthSeed(packageRoot:String,nodeExecutable:String,proof:TomlPackageProof):JsonObject {require(LspTomlLaunchPolicy.safeManagedPath(packageRoot)&&LspTomlLaunchPolicy.safeManagedPath(nodeExecutable));return buildJsonObject{put("schema",1);put("policy",POLICY);put("packageRoot",packageRoot);put("nodeExecutable",nodeExecutable);put("packageReceiptSha256",proof.receiptSha256);put("runtimeReceiptSha256",proof.dependencyReceipts["runtime.taplo-node"]?:proof.receiptSha256);put("sourceBindingSha256",proof.sourceBindingSha256);put("importReviewSha256",proof.importReviewSha256);put("noticeAdmissionSha256",proof.noticeAdmissionSha256);put("dataAdmissionSha256",proof.dataAdmissionSha256);put("helpersSha256",JsonObject(proof.helpersSha256.mapValues{JsonPrimitive(it.value)}))}}
}
