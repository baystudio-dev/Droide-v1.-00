package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal data class TomlPackageProof(val record:ManagedPackageRecord,val receiptSha256:String,val sealSha256:String,val activationId:String,
    val dependencyReceipts:Map<String,String>,val helpersSha256:Map<String,String>,val closureSha256:String,val sourceBindingSha256:String,
    val importReviewSha256:String,val noticeAdmissionSha256:String,val dataAdmissionSha256:String)

/** Original-data transaction with separate Node ownership and genuine staged health. No implicit prerequisite acquisition. */
internal class LocalUbuntuTomlInstaller(context:Context,private val authority:LocalManagedPackageAuthority) {
    private val app=context.applicationContext
    private val downloader=UserInitiatedArtifactTransfer(app)
    suspend fun install(recipe:WorkstationInstallRecipe,node:ManagedPackageRecord?,progress:(String)->Unit={}):ManagedPackageRecord {
        require(LocalUbuntuTomlPolicy.supports(recipe))
        val runtime=requireNotNull(node) { LocalUbuntuTomlPolicy.PREREQUISITE_MESSAGE }
        require(runtime.familyId==LocalUbuntuTomlNodePolicy.FAMILY&&runtime.version==LocalUbuntuTomlNodePolicy.VERSION&&authority.owns(runtime)&&authority.verifyTomlPackageWithoutHealth(runtime)) { LocalUbuntuTomlPolicy.PREREQUISITE_MESSAGE }
        return transact(LocalUbuntuTomlPolicy.FAMILY,runtime,progress) { stage,active ->
            val npm=File(stage,"payload/npm");require(npm.mkdirs());val budget=LocalUbuntuTomlArchive.Budget();val archives=linkedMapOf<String,JsonElement>()
            for((placement,identity)in LocalUbuntuTomlPolicy.placements.toSortedMap()) {
                progress("Authenticating original $identity…\n")
                downloader.withArtifact(LocalUbuntuTomlArchive.spec(identity),{progress(it.describe()+"\n")}) { archive->
                    archives[placement]=LocalUbuntuTomlArchive.extract(identity,archive,npm,budget,active)
                }
            }
            val inventory=JsonObject(archives);write(stage,ARCHIVES,inventory.toString())
            val data=TomlWasmDataAdmission.inspect(npm,active);write(stage,DATA,data.toString())
            LocalUbuntuTomlNoticePolicy.prepare(stage) { name->app.assets.open(name).use{it.readBytes()} }
            progress("Checking exact public covered-source availability and retained notices…\n")
            val availability=LocalUbuntuTomlNoticePolicy.refresh(stage)
            write(stage,NOTICE,LocalUbuntuTomlNoticePolicy.derive(stage,inventory,availability,active).toString())
            val nodeProof=provePackage(authority.appRoot,runtime,active)
            write(stage,CLOSURE,read(File(runtime.installRoot,CLOSURE),4_194_304,active))
            require(nodeProof.receiptSha256==receiptSha(runtime))
            sha(File(stage,ARCHIVES),active)
        }
    }
    suspend fun installNode(recipe:LocalUbuntuVendorRecipe,progress:(String)->Unit={}):ManagedPackageRecord {
        LocalUbuntuTomlNodePolicy.requireReviewed(recipe)
        return transact(recipe.familyId,null,progress) { stage,active ->
            val spec=TrustedArtifactSpec("taplo-node-22-23-3",recipe.assetUrl,recipe.assetSha256,recipe.assetName,LocalUbuntuTomlNodePolicy.ARTIFACT_BYTES,LocalUbuntuTomlNodePolicy.ARTIFACT_BYTES)
            downloader.withArtifact(spec,{progress(it.describe()+"\n")}) { archive->
                require(NimRuntimeClosure.measure(archive,LocalUbuntuTomlNodePolicy.ARTIFACT_BYTES,active)==(LocalUbuntuTomlNodePolicy.ARTIFACT_BYTES to LocalUbuntuTomlNodePolicy.SHA256))
                LocalUbuntuVendorArchive.project(recipe,archive,"$root/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch").use { projection->
                    val tree=File(stage,"payload/tree");require(tree.parentFile!!.mkdirs()||tree.parentFile!!.isDirectory)
                    require(projection.root.renameTo(tree)) { "Cannot preserve complete Node tree in Taplo stage" }
                }
            }
            val tree=File(stage,"payload/tree");val inv=LocalUbuntuTomlNodePolicy.requireTree(File(tree,LocalUbuntuTomlNodePolicy.ENTRY),active)
            val closure=NimRuntimeClosure.requireExecutionClosure(listOf(inv),guest(root),emptySet(),active)
            write(stage,ARCHIVES,snapshot(tree,active).toString())
            write(stage,DATA,buildJsonObject{put("schema",1);put("sourceBindingSha256",LocalUbuntuTomlNodePolicy.SOURCE_BINDING_SHA256);put("configSha256",LocalUbuntuTomlNodePolicy.requireConfiguration(File(tree,LocalUbuntuTomlNodePolicy.CONFIG),active));put("originalTreeSha256",sha(File(stage,ARCHIVES),active))}.toString())
            write(stage,NOTICE,buildJsonObject{put("schema",1);put("originalLicenseSha256",NimRuntimeClosure.measure(File(tree,"${LocalUbuntuTomlNodePolicy.TOP}/LICENSE"),4_194_304,active).second);put("originalReadmeSha256",NimRuntimeClosure.measure(File(tree,"${LocalUbuntuTomlNodePolicy.TOP}/README.md"),4_194_304,active).second);put("source","https://nodejs.org/dist/v22.23.3/")}.toString())
            write(stage,CLOSURE,buildJsonObject{put("schema",1);put("files",mapping(closure.files));put("sha256",closure.sha256)}.toString())
            LocalUbuntuTomlNodePolicy.SHA256
        }
    }
    private val root get()=authority.appRoot
    private suspend fun transact(family:String,node:ManagedPackageRecord?,progress:(String)->Unit,stageData:suspend(File,()->Unit)->String):ManagedPackageRecord=withContext(Dispatchers.IO) {
        require(android.os.Build.SUPPORTED_ABIS.any{it=="arm64-v8a"||it=="aarch64"})
        LocalPackageInstallJournal.transactionMutex.withLock {
            val paths=LocalPackageInstallJournal.paths(root,family,LocalUbuntuTomlPolicy.version(family),LocalUbuntuTomlPolicy.kind(family))
            val previous=authority.recoveryReceipt(family,LocalUbuntuTomlPolicy.version(family),LocalUbuntuTomlPolicy.kind(family))
            LocalPackageInstallJournal.recover(paths,previous?.let{LocalPackageInstallJournal.Receipt(it.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY])})
            val context=currentCoroutineContext();val active={context.ensureActive()}
            val dep=node?.let{provePackage(root,it,active)}
            var activation:LocalPackageInstallJournal.Activation?=null
            try {
                require(paths.stage.mkdirs());mode(paths.stage,493);val id=LocalPackageInstallJournal.prepare(paths.stage);mode(File(paths.stage,"DROIDE_ACTIVATION_ID"),420)
                val artifact=stageData(paths.stage,active)
                for((path,hash)in LocalUbuntuTomlLaunchAuthority.expectedHelperHashes) {
                    val bytes=app.assets.open("lsp/toml/${path.substringAfterLast('/')}").use{it.readBytes()}
                    require(bytes.size in 1..1_048_576&&LocalUbuntuTomlPolicy.sha256(bytes)==hash);write(paths.stage,path,bytes.toString(Charsets.UTF_8))
                }
                val command=LocalUbuntuTomlPolicy.command(family);val finalGuest=LocalUbuntuTomlPolicy.root(family)
                write(paths.stage,"payload/bin/$command",LocalUbuntuTomlLaunchAuthority.guestWrapper(family,finalGuest),493)
                write(paths.stage,"payload/host-bin/$command",LocalUbuntuVendorPolicy.hostWrapper("$root/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch","$finalGuest/payload/bin/$command"),493)
                val closure=objectAt(paths.stage,CLOSURE,active)
                val a=buildJsonObject {
                    put("schema",1);put("policy",LocalUbuntuTomlPolicy.POLICY);put("family",family);put("version",LocalUbuntuTomlPolicy.version(family));put("activation",id);put("final",finalGuest)
                    put("artifactSha256",artifact);put("archiveAdmissionSha256",sha(File(paths.stage,ARCHIVES),active));put("dataAdmissionSha256",sha(File(paths.stage,DATA),active));put("importReviewSha256",sha(File(paths.stage,DATA),active));put("noticeAdmissionSha256",sha(File(paths.stage,NOTICE),active));put("closureSha256",text(closure,"sha256"))
                    put("sourceBindingSha256",if(family==LocalUbuntuTomlPolicy.FAMILY)LocalUbuntuTomlPolicy.SOURCE_BINDING_SHA256 else LocalUbuntuTomlNodePolicy.SOURCE_BINDING_SHA256)
                    put("helpers",mapping(LocalUbuntuTomlLaunchAuthority.expectedHelperHashes));put("dependencyReceipts",mapping(dep?.let{mapOf(it.record.familyId to it.receiptSha256)}?:emptyMap()))
                };write(paths.stage,AUTHORITY,a.toString())
                val provisional=record(family,paths.final,id,artifact,"0".repeat(64),text(a,"archiveAdmissionSha256"),node)
                val stagedProof=proof(provisional,a,sha(File(paths.stage,AUTHORITY),active),"0".repeat(64))
                val nodeExe=if(node==null)"${paths.stage.absolutePath}/payload/tree/${LocalUbuntuTomlNodePolicy.ENTRY}" else LocalUbuntuTomlNodePolicy.EXECUTABLE
                write(paths.stage,HEALTH_SEED,LocalUbuntuTomlLaunchAuthority.healthSeed(paths.stage.absolutePath,nodeExe,stagedProof).toString())
                proveStaged(root,paths.stage,active)
                progress("Checking genuine Taplo runtime/API and exact owned cleanup…\n")
                val hold=LocalPackageInstallJournal.beginTomlHealth(paths)
                val health=if(node==null)LocalUbuntuTomlLspHealth.runNodeStaged(root,paths.stage,hold) else LocalUbuntuTomlLspHealth.runStaged(root,paths.stage,node,hold)
                val cleaned=if(node==null)LocalUbuntuTomlLspHealth.nodeCleanupVerified(health)else LocalUbuntuTomlLspHealth.cleanupVerified(health)
                LocalPackageInstallJournal.finishTomlHealth(hold,cleaned)
                check(if(node==null)LocalUbuntuTomlLspHealth.nodeSucceeded(health)else LocalUbuntuTomlLspHealth.succeeded(health)) { "Taplo genuine health or owned cleanup failed; no active publication" }
                proveStaged(root,paths.stage,active);node?.let { require(provePackage(root,it,active).receiptSha256==dep!!.receiptSha256) }
                write(paths.stage,HEALTH,buildJsonObject{put("schema",1);put("authoritySha256",sha(File(paths.stage,AUTHORITY),active));put("cleanupVerified",true);put("exitCode",0);put("profile",if(node==null)"exact-node-identity-resources" else "genuine-local-schema-format-symbols")}.toString())
                val seal=snapshot(paths.stage,active,setOf("SHA256SUMS")).toString();write(paths.stage,"SHA256SUMS",seal)
                val record=provisional.copy(metadata=provisional.metadata+(LocalManagedPackageMetadata.SEAL_SHA256_KEY to LocalUbuntuTomlPolicy.sha256(seal.toByteArray())))
                activation=LocalPackageInstallJournal.activate(paths.stage,paths.final,paths.previous)
                authority.adopt(record);activation=null
                withContext(NonCancellable){LocalPackageInstallJournal.commit(paths.previous)}
                record
            } catch(failure:Throwable) {
                withContext(NonCancellable) {
                    if(runCatching{LocalPackageInstallJournal.requireNoTomlHealthHold(paths)}.isFailure)failure.addSuppressed(IllegalStateException("Taplo exact owner cleanup unknown; stage, private state and journal retained"))
                    else {
                        activation?.let{runCatching{LocalPackageInstallJournal.rollback(paths.final,paths.previous,it)}.exceptionOrNull()?.let(failure::addSuppressed)}
                        if(!PathSecurity.deleteTreeNoFollow(paths.stage))failure.addSuppressed(IllegalStateException("Taplo stage cleanup incomplete"))
                    }
                };throw failure
            }
        }
    }
    private fun record(family:String,final:File,id:String,artifact:String,seal:String,tree:String,node:ManagedPackageRecord?)=ManagedPackageRecord(
        familyId=family,version=LocalUbuntuTomlPolicy.version(family),scope=ExecutionScope.LOCAL_LINUX_ARM64.name,installRoot=final.absolutePath,
        installedAtEpochMs=System.currentTimeMillis(),active=true,pathEntries=listOf("$final/payload/host-bin"),commands=mapOf(LocalUbuntuTomlPolicy.command(family) to "$final/payload/host-bin/${LocalUbuntuTomlPolicy.command(family)}"),environment=emptyMap(),
        dependencies=node?.let{listOf(LocalUbuntuTomlNodePolicy.KEY)}?:emptyList(),healthChecks=emptyList(),artifactSha256=artifact,abi="arm64-v8a",
        metadata=mapOf(LocalManagedPackageMetadata.KIND_KEY to LocalUbuntuTomlPolicy.kind(family),LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,
            LocalManagedPackageMetadata.LIBC_KEY to DeclarativeLibcCompatibility.GLIBC.name,LocalManagedPackageMetadata.ACTIVATION_ID_KEY to id,LocalManagedPackageMetadata.COMMAND_KEY to LocalUbuntuTomlPolicy.command(family),
            LocalManagedPackageMetadata.SEAL_SHA256_KEY to seal,LocalManagedPackageMetadata.TREE_SHA256_KEY to tree,PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name))
    companion object {
        const val AUTHORITY="TOML_AUTHORITY.json"
        const val CLOSURE="TOML_CLOSURE.json"
        const val ARCHIVES="TOML_ARCHIVE_ADMISSION.json"
        const val DATA="TOML_DATA_ADMISSION.json"
        const val NOTICE="TOML_NOTICE_ADMISSION.json"
        const val HEALTH="TOML_HEALTH.json"
        const val HEALTH_SEED="toml-health-identity.json"
        fun receiptSha(record:ManagedPackageRecord)=LocalUbuntuPowerShellLaunchAuthority.receiptSha(record)
        fun write(root:File,path:String,value:String,permissions:Int=420) { LocalUbuntuTomlPolicy.safeRelative(path);val file=File(root,path);require(file.parentFile!!.mkdirs()||file.parentFile!!.isDirectory);NimRuntimeClosure.requireDirectory(file.parentFile!!);require(!Files.isSymbolicLink(file.toPath()));java.io.FileOutputStream(file).use{it.write(value.toByteArray());it.fd.sync()};mode(file,permissions) }
        private fun mode(file:File,value:Int)=android.system.Os.chmod(file.absolutePath,value)
        private fun mapping(value:Map<String,String>)=JsonObject(value.toSortedMap().mapValues{JsonPrimitive(it.value)})
        private fun text(o:JsonObject,k:String)=LocalUbuntuPowerShellRuntimePolicy.string(o,k)
        private fun strings(o:JsonObject,k:String)=o.getValue(k).jsonObject.mapValues{it.value.jsonPrimitive.also{p->require(p.isString)}.content}
        private fun read(file:File,max:Int=4_194_304,active:()->Unit={})=LocalUbuntuTomlPolicy.read(file,max,active)
        private fun sha(file:File,active:()->Unit={})=NimRuntimeClosure.measure(file,67_108_864,active).second
        private fun objectAt(root:File,path:String,active:()->Unit={})=LocalUbuntuTomlPolicy.strictObject(read(File(root,path),4_194_304,active))
        private fun guest(appRoot:String)=File(appRoot,"guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/rootfs")
        private fun proof(record:ManagedPackageRecord,a:JsonObject,receipt:String,seal:String)=TomlPackageProof(record,receipt,seal,text(a,"activation"),strings(a,"dependencyReceipts"),strings(a,"helpers"),text(a,"closureSha256"),text(a,"sourceBindingSha256"),text(a,"importReviewSha256"),text(a,"noticeAdmissionSha256"),text(a,"dataAdmissionSha256"))
        fun inspectPackage(appRoot:String,record:ManagedPackageRecord,active:()->Unit={}):TomlPackageProof {
            active();require(LocalUbuntuTomlPolicy.isFamily(record.familyId)&&record.version==LocalUbuntuTomlPolicy.version(record.familyId))
            val paths=LocalPackageInstallJournal.paths(appRoot,record.familyId,record.version,LocalUbuntuTomlPolicy.kind(record.familyId));LocalPackageInstallJournal.requireNoTomlHealthHold(paths)
            require(record.active&&record.installRoot==paths.final.absolutePath&&record.abi=="arm64-v8a"&&record.scope==ExecutionScope.LOCAL_LINUX_ARM64.name&&record.environment.isEmpty()&&record.healthChecks.isEmpty()&&record.checksumFile=="SHA256SUMS")
            require(record.metadata[LocalManagedPackageMetadata.KIND_KEY]==LocalUbuntuTomlPolicy.kind(record.familyId)&&record.metadata[PackageBackendContract.METADATA_KEY]==PackageBackendId.LOCAL_APP.name&&record.metadata[LocalManagedPackageMetadata.PROFILE_KEY]==DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name&&record.metadata[LocalManagedPackageMetadata.LIBC_KEY]==DeclarativeLibcCompatibility.GLIBC.name)
            val command=LocalUbuntuTomlPolicy.command(record.familyId);require(record.commands==mapOf(command to "${record.installRoot}/payload/host-bin/$command")&&record.pathEntries==listOf("${record.installRoot}/payload/host-bin"))
            require(record.dependencies==if(record.familyId==LocalUbuntuTomlPolicy.FAMILY)listOf(LocalUbuntuTomlNodePolicy.KEY)else emptyList<String>())
            val root=File(record.installRoot);require(root.canonicalFile==root.absoluteFile);val a=objectAt(root,AUTHORITY,active);requireAuthority(root,a,active)
            require(text(a,"family")==record.familyId&&text(a,"activation")==record.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]&&text(a,"artifactSha256")==record.artifactSha256&&text(a,"archiveAdmissionSha256")==record.metadata[LocalManagedPackageMetadata.TREE_SHA256_KEY])
            val seal=sha(File(root,"SHA256SUMS"),active);require(seal==record.metadata[LocalManagedPackageMetadata.SEAL_SHA256_KEY])
            val health=objectAt(root,HEALTH,active);require(health.keys==setOf("schema","authoritySha256","cleanupVerified","exitCode","profile")&&health["schema"]==JsonPrimitive(1)&&health["cleanupVerified"]==JsonPrimitive(true)&&health["exitCode"]==JsonPrimitive(0)&&text(health,"authoritySha256")==sha(File(root,AUTHORITY),active)&&text(health,"profile")==if(record.familyId==LocalUbuntuTomlPolicy.FAMILY)"genuine-local-schema-format-symbols"else"exact-node-identity-resources")
            return proof(record,a,receiptSha(record),seal)
        }
        private fun requireAuthority(root:File,a:JsonObject,active:()->Unit) {
            require(a.keys==setOf("schema","policy","family","version","activation","final","artifactSha256","archiveAdmissionSha256","dataAdmissionSha256","importReviewSha256","noticeAdmissionSha256","closureSha256","sourceBindingSha256","helpers","dependencyReceipts"))
            val family=text(a,"family");require(a["schema"]==JsonPrimitive(1)&&text(a,"policy")==LocalUbuntuTomlPolicy.POLICY&&LocalUbuntuTomlPolicy.isFamily(family)&&text(a,"version")==LocalUbuntuTomlPolicy.version(family)&&text(a,"final")==LocalUbuntuTomlPolicy.root(family)&&LocalPackageInstallJournal.matchesActivation(root,text(a,"activation")))
            require(text(a,"sourceBindingSha256")==if(family==LocalUbuntuTomlPolicy.FAMILY)LocalUbuntuTomlPolicy.SOURCE_BINDING_SHA256 else LocalUbuntuTomlNodePolicy.SOURCE_BINDING_SHA256)
            require(strings(a,"helpers")==LocalUbuntuTomlLaunchAuthority.expectedHelperHashes&&strings(a,"dependencyReceipts").keys==if(family==LocalUbuntuTomlPolicy.FAMILY)setOf(LocalUbuntuTomlNodePolicy.FAMILY)else emptySet<String>())
            for(key in listOf("artifactSha256","archiveAdmissionSha256","dataAdmissionSha256","importReviewSha256","noticeAdmissionSha256","closureSha256"))require(text(a,key).matches(Regex("[0-9a-f]{64}")))
            require(strings(a,"dependencyReceipts").values.all{it.matches(Regex("[0-9a-f]{64}"))})
        }
        fun provePackage(appRoot:String,record:ManagedPackageRecord,active:()->Unit={}):TomlPackageProof {
            val p=inspectPackage(appRoot,record,active);val root=File(record.installRoot)
            require(snapshot(root,active,setOf("SHA256SUMS")).toString()==read(File(root,"SHA256SUMS"),16_777_216,active)) { "Taplo complete package seal changed" }
            proveData(appRoot,root,objectAt(root,AUTHORITY,active),active);return p
        }
        fun proveStaged(appRoot:String,stage:File,active:()->Unit={}) {
            val a=objectAt(stage,AUTHORITY,active);requireAuthority(stage,a,active)
            val expected=LocalPackageInstallJournal.paths(appRoot,text(a,"family"),text(a,"version"),LocalUbuntuTomlPolicy.kind(text(a,"family"))).stage
            require(stage.absoluteFile==expected.absoluteFile);proveData(appRoot,stage,a,active)
        }
        private fun proveData(appRoot:String,root:File,a:JsonObject,active:()->Unit) {
            for((path,key)in mapOf(ARCHIVES to "archiveAdmissionSha256",DATA to "dataAdmissionSha256",NOTICE to "noticeAdmissionSha256"))require(sha(File(root,path),active)==text(a,key))
            require(text(a,"importReviewSha256")==text(a,"dataAdmissionSha256"))
            for((path,hash)in strings(a,"helpers"))require(sha(File(root,path),active)==hash)
            val family=text(a,"family");val command=LocalUbuntuTomlPolicy.command(family)
            require(read(File(root,"payload/bin/$command"),65536,active)==LocalUbuntuTomlLaunchAuthority.guestWrapper(family,LocalUbuntuTomlPolicy.root(family)))
            require(read(File(root,"payload/host-bin/$command"),65536,active)==LocalUbuntuVendorPolicy.hostWrapper("$appRoot/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch","${LocalUbuntuTomlPolicy.root(family)}/payload/bin/$command"))
            val nodeRoot=if(family==LocalUbuntuTomlPolicy.FAMILY)LocalPackageInstallJournal.paths(appRoot,LocalUbuntuTomlNodePolicy.FAMILY,LocalUbuntuTomlNodePolicy.VERSION,LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE).final else root
            val tree=File(nodeRoot,"payload/tree");val inv=LocalUbuntuTomlNodePolicy.requireTree(File(tree,LocalUbuntuTomlNodePolicy.ENTRY),active)
            val closure=NimRuntimeClosure.requireExecutionClosure(listOf(inv),guest(appRoot),emptySet(),active)
            val c=objectAt(root,CLOSURE,active);require(c.keys==setOf("schema","files","sha256")&&c["schema"]==JsonPrimitive(1)&&strings(c,"files")==closure.files&&text(c,"sha256")==closure.sha256&&closure.sha256==text(a,"closureSha256"))
            val originalStage=LocalPackageInstallJournal.paths(appRoot,family,LocalUbuntuTomlPolicy.version(family),LocalUbuntuTomlPolicy.kind(family)).stage.absolutePath
            val seed=objectAt(root,HEALTH_SEED,active)
            require(seed.keys==setOf("schema","policy","packageRoot","nodeExecutable","packageReceiptSha256","runtimeReceiptSha256","sourceBindingSha256","importReviewSha256","noticeAdmissionSha256","dataAdmissionSha256","helpersSha256"))
            require(seed["schema"]==JsonPrimitive(1)&&text(seed,"policy")==LocalUbuntuTomlLaunchAuthority.POLICY&&text(seed,"packageRoot")==originalStage)
            require(text(seed,"nodeExecutable")==if(family==LocalUbuntuTomlPolicy.FAMILY)LocalUbuntuTomlNodePolicy.EXECUTABLE else "$originalStage/payload/tree/${LocalUbuntuTomlNodePolicy.ENTRY}")
            require(text(seed,"packageReceiptSha256")==sha(File(root,AUTHORITY),active)&&text(seed,"runtimeReceiptSha256")==strings(a,"dependencyReceipts")[LocalUbuntuTomlNodePolicy.FAMILY].orEmpty().ifEmpty{sha(File(root,AUTHORITY),active)})
            for(key in listOf("sourceBindingSha256","importReviewSha256","noticeAdmissionSha256","dataAdmissionSha256"))require(seed[key]==a[key])
            require(seed["helpersSha256"]==a["helpers"])
            val data=objectAt(root,DATA,active)
            if(family==LocalUbuntuTomlPolicy.FAMILY) {
                val inventory=objectAt(root,ARCHIVES,active);require(text(a,"artifactSha256")==sha(File(root,ARCHIVES),active));requireNpmInventory(File(root,"payload/npm"),inventory,active)
                require(data==TomlWasmDataAdmission.inspect(File(root,"payload/npm"),active))
                val notice=objectAt(root,NOTICE,active);require(notice==LocalUbuntuTomlNoticePolicy.derive(root,inventory,notice.getValue("availability").jsonObject,active))
                val nodeAuthority=objectAt(nodeRoot,AUTHORITY,active);requireAuthority(nodeRoot,nodeAuthority,active)
                require(snapshot(nodeRoot,active,setOf("SHA256SUMS")).toString()==read(File(nodeRoot,"SHA256SUMS"),16_777_216,active))
            } else {
                require(snapshot(tree,active)==objectAt(root,ARCHIVES,active))
                require(text(a,"artifactSha256")==LocalUbuntuTomlNodePolicy.SHA256&&text(data,"configSha256")==LocalUbuntuTomlNodePolicy.requireConfiguration(File(tree,LocalUbuntuTomlNodePolicy.CONFIG),active)&&text(data,"originalTreeSha256")==sha(File(root,ARCHIVES),active))
                val notice=objectAt(root,NOTICE,active);require(text(notice,"originalLicenseSha256")==sha(File(tree,"${LocalUbuntuTomlNodePolicy.TOP}/LICENSE"),active)&&text(notice,"originalReadmeSha256")==sha(File(tree,"${LocalUbuntuTomlNodePolicy.TOP}/README.md"),active))
            }
        }
        internal fun requireNpmInventory(npm:File,inventory:JsonObject,active:()->Unit={}) {
            require(inventory.keys==LocalUbuntuTomlPolicy.placements.keys)
            val all=mutableSetOf("node_modules","node_modules/@taplo")
            for((placement,identity)in LocalUbuntuTomlPolicy.placements) {
                val a=inventory.getValue(placement).jsonObject;require(text(a,"sha512")==LocalUbuntuTomlPolicy.artifacts.getValue(identity).getValue("sha512").jsonPrimitive.content)
                val files=a.getValue("files").jsonObject;val dirs=a.getValue("directories").jsonArray.map{it.jsonPrimitive.content}.toSet()
                val observed=snapshot(File(npm,placement),active)
                require(observed.keys==files.keys+dirs)
                for((path,record)in files) { val actual=observed.getValue(path).jsonObject;val expected=record.jsonObject;require(text(actual,"kind")=="file"&&actual["size"]==expected["size"]&&actual["sha256"]==expected["sha256"]&&actual["mode"]==expected["mode"]) }
                dirs.forEach{require(text(observed.getValue(it).jsonObject,"kind")=="directory")};all+=placement;all+=observed.keys.map{"$placement/$it"}
                LocalUbuntuTomlPolicy.requirePackage(identity,LocalUbuntuTomlPolicy.strictObject(read(File(npm,"$placement/package.json"),65536,active)))
            }
            require(snapshot(npm,active).keys==all) { "Unexpected original npm member or dependency placement" }
        }
        internal fun snapshot(root:File,active:()->Unit={},exclude:Set<String> = emptySet()):JsonObject {
            NimRuntimeClosure.requireDirectory(root);require(root.canonicalFile==root.absoluteFile);val map=sortedMapOf<String,JsonElement>();var total=0L
            Files.walk(root.toPath()).use{stream->stream.forEach{p->active();if(p==root.toPath())return@forEach
                val relative=root.toPath().relativize(p).toString().replace(File.separatorChar,'/');if(relative in exclude)return@forEach
                LocalUbuntuTomlPolicy.safeRelative(relative);require(map.size<20_000);val mode=android.system.Os.lstat(p.toString()).st_mode and 511
                map[relative]=when {
                    Files.isSymbolicLink(p)->{require(relative.startsWith("payload/tree/")||root.name=="tree");buildJsonObject{put("kind","link");put("mode",mode);put("target",Files.readSymbolicLink(p).toString())}}
                    Files.isDirectory(p,NOFOLLOW_LINKS)->buildJsonObject{put("kind","directory");put("mode",mode)}
                    Files.isRegularFile(p,NOFOLLOW_LINKS)->{val measure=NimRuntimeClosure.measure(p.toFile(),536_870_912,active);total=Math.addExact(total,measure.first);require(total<=553_648_128);buildJsonObject{put("kind","file");put("mode",mode);put("size",measure.first);put("sha256",measure.second)}}
                    else->error("Taplo package special file")
                }
            }};return JsonObject(map)
        }
    }
}
