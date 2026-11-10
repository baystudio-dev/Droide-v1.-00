package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal data class CSharpPackageProof(val record:ManagedPackageRecord,val receiptSha256:String,val activationId:String,
    val sealSha256:String,val treeSha256:String,val configSha256:String,val closureSha256:String,val closureFiles:Map<String,String>,
    val helpersSha256:Map<String,String>,val dependencyReceipts:Map<String,String>)

/** Independent exact-artifact journal; no original command executes except the dedicated owned staged health lane. */
internal class LocalUbuntuCSharpInstaller(context:Context,private val authority:LocalManagedPackageAuthority) {
    private val app=context.applicationContext
    private val downloader=UserInitiatedArtifactTransfer(app)
    suspend fun install(recipe:CSharpInstallRecipe,progress:(String)->Unit={}):ManagedPackageRecord=withContext(Dispatchers.IO) {
        recipe.validate()
        if(recipe.familyId==LocalUbuntuCSharpPolicy.FAMILY) {
            for(family in listOf(LocalUbuntuCSharpHelperPolicy.FAMILY,LocalUbuntuCSharpSdkPolicy.FAMILY)) {
                val required=LocalUbuntuCSharpPolicy.recipe(family)
                val owned=authority.ownedReceipt(family,required.version)
                if(owned==null||!authority.verify(owned))install(required,progress)
            }
        }
        LocalPackageInstallJournal.transactionMutex.withLock {
            val root=authority.appRoot
            val paths=LocalPackageInstallJournal.paths(root,recipe.familyId,recipe.version,LocalUbuntuCSharpPolicy.KIND)
            val previous=authority.recoveryReceipt(recipe.familyId,recipe.version,LocalUbuntuCSharpPolicy.KIND)
            LocalPackageInstallJournal.recover(paths,previous?.let{LocalPackageInstallJournal.Receipt(it.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY])})
            val dependencies=if(recipe.familyId==LocalUbuntuCSharpPolicy.FAMILY) LocalUbuntuCSharpPolicy.dependencyKeys.map { key ->
                requireNotNull(authority.ownedReceipt(key.substringBefore('@'),key.substringAfter('@'))) { "Exact C# prerequisite receipt missing" }
            } else emptyList()
            val context=currentCoroutineContext();val active={context.ensureActive()}
            dependencies.forEach{provePackage(root,it,active)}
            var activation:LocalPackageInstallJournal.Activation?=null
            try {
                suspend fun unpack(archive:File):ManagedPackageRecord {
                    progress("Authenticating complete original ${recipe.familyId} ${recipe.version}…\n")
                    LocalUbuntuCSharpArchive.project(recipe,archive,active).use { projection ->
                        check(paths.stage.mkdirs());LocalUbuntuCSharpArchive.setMode(paths.stage,493)
                        val tree=File(paths.stage,"payload/tree");check(tree.parentFile!!.mkdirs());LocalUbuntuCSharpArchive.setMode(tree.parentFile!!,493)
                        check(projection.root.renameTo(tree)) { "Could not move C# authenticated quarantine into stage" }
                        val id=LocalPackageInstallJournal.prepare(paths.stage)
                        val inv=CSharpRuntimeClosure.inspectTree(recipe,tree,projection.members,active)
                        write(paths.stage,TREE_MANIFEST,projection.manifest)
                        val all=listOf(inv)+dependencies.map{inventory(it,active)}
                        val guest=File(root,"guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/rootfs")
                        val closure=CSharpRuntimeClosure.guestClosure(guest,all,active)
                        val notices=if(recipe.familyId==LocalUbuntuCSharpPolicy.FAMILY)CSharpRuntimeClosure.requireManagedClosure(all,active) else inv.notices
                        val helpers=if(recipe.familyId==LocalUbuntuCSharpPolicy.FAMILY)LocalUbuntuCSharpLaunchAuthority.expectedHelperHashes else emptyMap()
                        for((path,expected)in helpers) {
                            val bytes=app.assets.open("lsp/csharp/${path.substringAfterLast('/')}").use{it.readBytes()}
                            require(bytes.size in 1..1_048_576&&NimRuntimeClosure.sha256(bytes.toString(Charsets.UTF_8))==expected)
                            write(paths.stage,path,bytes.toString(Charsets.UTF_8))
                        }
                        val guestRoot=LocalUbuntuCSharpPolicy.root(recipe.familyId)
                        write(paths.stage,"payload/bin/${recipe.command}",LocalUbuntuCSharpLaunchAuthority.guestWrapper(recipe.familyId,guestRoot),493)
                        write(paths.stage,"payload/host-bin/${recipe.command}",LocalUbuntuVendorPolicy.hostWrapper("$root/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch","$guestRoot/payload/bin/${recipe.command}"),493)
                        val depHashes=dependencies.associate{it.familyId to receiptSha(it)}
                        val authorityText=buildJsonObject {
                            put("schema",1);put("policy",LocalUbuntuCSharpPolicy.POLICY);put("launchPolicy",LocalUbuntuCSharpLaunchAuthority.POLICY)
                            put("family",recipe.familyId);put("version",recipe.version);put("role",recipe.role.name);put("final",guestRoot);put("activation",id)
                            put("publisherAlgorithm",recipe.publisherDigest.algorithm.jca);put("publisherDigest",recipe.publisherDigest.hex)
                            put("publisherBase64",recipe.publisherDigest.publisherBase64?.let(::JsonPrimitive)?:JsonNull)
                            put("localArchiveSha256",projection.localArchiveSha256);put("archiveBytes",archive.length());put("url",recipe.url);put("provenance",recipe.provenanceUrl)
                            put("treeSha256",projection.manifestSha256);put("configSha256",inv.configSha256);put("closureSha256",closure.sha256)
                            put("helpers",mapping(helpers));put("dependencyReceipts",mapping(depHashes));put("notices",mapping(notices))
                            put("signaturePolicy",if(recipe.role==CSharpArtifactRole.MANAGED_NUPKG)LocalUbuntuCSharpPolicy.SIGNATURE_POLICY else "Publisher artifact digest verified.")
                        }.toString()+"\n"
                        write(paths.stage,AUTHORITY,authorityText)
                        write(paths.stage,CLOSURE,buildJsonObject{put("schema",1);put("files",mapping(closure.files));put("sha256",closure.sha256)}.toString()+"\n")
                        if(recipe.familyId==LocalUbuntuCSharpPolicy.FAMILY) {
                            val sdk=dependencies.single{it.familyId==LocalUbuntuCSharpSdkPolicy.FAMILY};val helper=dependencies.single{it.familyId==LocalUbuntuCSharpHelperPolicy.FAMILY}
                            val seed=healthSeed(paths.stage,id,sdk,helper,inv.configSha256,closure,root,active)
                            write(paths.stage,HEALTH_SEED,seed)
                            proveStaged(root,paths.stage,active)
                            val hold=LocalPackageInstallJournal.beginCSharpHealth(paths)
                            val result=LocalUbuntuCSharpLspHealth.runStaged(root,paths.stage,sdk,helper)
                            LocalPackageInstallJournal.finishCSharpHealth(hold,LocalUbuntuCSharpLspHealth.cleanupVerified(result))
                            check(LocalUbuntuCSharpLspHealth.succeeded(result)) { "C# isolated SDK/stdio health or owned retirement failed" }
                            proveStaged(root,paths.stage,active)
                            write(paths.stage,HEALTH_RECEIPT,buildJsonObject{put("schema",1);put("profile","zero-project-isolated-stdio");put("authoritySha256",NimRuntimeClosure.sha256(authorityText));put("closureSha256",closure.sha256);put("cleanupVerified",true);put("exitCode",0)}.toString()+"\n")
                            write(paths.stage,HEALTH_SEED,relocateSeed(seed,paths.stage.absolutePath,guestRoot))
                        }
                        val seal=snapshot(paths.stage,recipe,active);write(paths.stage,"SHA256SUMS",seal)
                        activation=LocalPackageInstallJournal.activate(paths.stage,paths.final,paths.previous)
                        val record=ManagedPackageRecord(recipe.familyId,recipe.version,ExecutionScope.LOCAL_LINUX_ARM64.name,paths.final.absolutePath,System.currentTimeMillis(),
                            pathEntries=listOf("${paths.final}/payload/host-bin"),commands=mapOf(recipe.command to "${paths.final}/payload/host-bin/${recipe.command}"),
                            dependencies=dependencies.map{"${it.familyId}@${it.version}"}.sorted(),healthChecks=emptyList(),artifactSha256=projection.localArchiveSha256,abi="arm64-v8a",
                            metadata=mapOf(LocalManagedPackageMetadata.KIND_KEY to LocalUbuntuCSharpPolicy.KIND,
                                LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,LocalManagedPackageMetadata.LIBC_KEY to DeclarativeLibcCompatibility.GLIBC.name,
                                LocalManagedPackageMetadata.COMMAND_KEY to recipe.command,LocalManagedPackageMetadata.ACTIVATION_ID_KEY to id,
                                LocalManagedPackageMetadata.SEAL_SHA256_KEY to NimRuntimeClosure.sha256(seal),LocalManagedPackageMetadata.TREE_SHA256_KEY to projection.manifestSha256,
                                PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name))
                        authority.adopt(record);activation=null
                        withContext(NonCancellable){LocalPackageInstallJournal.commit(paths.previous)}
                        return record
                    }
                }
                when(val spec=recipe.descriptor()) {
                    is TrustedArtifactSpec -> downloader.withArtifact(spec,{progress(it.describe()+"\n")}){unpack(it)}
                    is TrustedSha512ArtifactSpec -> downloader.withArtifact(spec,{progress(it.describe()+"\n")}){unpack(it)}
                    else -> error("Unsupported C# digest descriptor")
                }
            } catch(failure:Throwable) {
                withContext(NonCancellable) {
                    if(runCatching{LocalPackageInstallJournal.requireNoCSharpHealthHold(paths)}.isFailure)failure.addSuppressed(IllegalStateException("C# owner cleanup unknown; all trees and journal retained"))
                    else {
                        activation?.let{runCatching{LocalPackageInstallJournal.rollback(paths.final,paths.previous,it)}.exceptionOrNull()?.let(failure::addSuppressed)}
                        if(!PathSecurity.deleteTreeNoFollow(paths.stage))failure.addSuppressed(IllegalStateException("C# failed stage cleanup incomplete"))
                    }
                };throw failure
            }
        }
    }
    companion object {
        const val AUTHORITY="CSHARP_AUTHORITY.json"
        const val CLOSURE="CSHARP_CLOSURE.json"
        const val TREE_MANIFEST="CSHARP_ORIGINAL_TREE.tsv"
        const val HEALTH_SEED="csharp-health-identity.json"
        const val HEALTH_RECEIPT="CSHARP_HEALTH.json"
        fun receiptSha(record:ManagedPackageRecord)=LocalUbuntuPowerShellLaunchAuthority.receiptSha(record)
        private fun mapping(m:Map<String,String>)=JsonObject(m.toSortedMap().mapValues{JsonPrimitive(it.value)})
        private fun text(o:JsonObject,key:String)=LocalUbuntuPowerShellRuntimePolicy.string(o,key)
        private fun objectAt(root:File,path:String,active:()->Unit={})=LocalUbuntuPowerShellRuntimePolicy.strictObject(NimRuntimeClosure.readText(File(root,path),4_194_304,active),4_194_304)
        private fun strings(o:JsonObject,key:String):Map<String,String> = (o[key] as? JsonObject?:error("Missing C# $key")).mapValues{(_,v)->require(v is JsonPrimitive&&v.isString);v.content}
        private fun sha(file:File,active:()->Unit={})=NimRuntimeClosure.measure(file,16_777_216,active).second
        private fun write(root:File,path:String,text:String,mode:Int=420) {
            LocalUbuntuCSharpArchive.requirePath(path);val file=File(root,path)
            val parents=generateSequence(file.parentFile){if(it==root)null else it.parentFile}.toList().asReversed()
            for(parent in parents){require(parent.absolutePath==root.absolutePath||parent.absolutePath.startsWith(root.absolutePath+"/"));if(!parent.exists())check(parent.mkdir());NimRuntimeClosure.requireDirectory(parent);LocalUbuntuCSharpArchive.setMode(parent,493)}
            Files.newOutputStream(file.toPath(),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,java.nio.file.StandardOpenOption.WRITE,NOFOLLOW_LINKS).use{it.write(text.toByteArray(Charsets.UTF_8))}
            LocalUbuntuCSharpArchive.setMode(file,mode)
        }
        private fun inventory(record:ManagedPackageRecord,active:()->Unit={}):CSharpRuntimeClosure.Inventory {
            val recipe=LocalUbuntuCSharpPolicy.requested(record.familyId,record.version);val root=File(record.installRoot)
            return inventoryAt(recipe,root,active)
        }
        private fun inventoryAt(recipe:CSharpInstallRecipe,root:File,active:()->Unit)=CSharpRuntimeClosure.inspectTree(recipe,File(root,"payload/tree"),LocalUbuntuCSharpArchive.decode(NimRuntimeClosure.readText(File(root,TREE_MANIFEST),16_777_216,active)),active)

        private fun completeDataProof(appRoot:String,root:File,recipe:CSharpInstallRecipe,a:JsonObject,active:()->Unit):NimRuntimeClosure.Closure {
            val own=inventoryAt(recipe,root,active)
            require(own.treeSha256==text(a,"treeSha256")&&own.configSha256==text(a,"configSha256"))
            val inventories=mutableListOf(own)
            if(recipe.familyId==LocalUbuntuCSharpPolicy.FAMILY)for(family in listOf(LocalUbuntuCSharpSdkPolicy.FAMILY,LocalUbuntuCSharpHelperPolicy.FAMILY)) {
                val dep=LocalUbuntuCSharpPolicy.recipe(family)
                val paths=LocalPackageInstallJournal.paths(appRoot,family,dep.version,LocalUbuntuCSharpPolicy.KIND)
                LocalPackageInstallJournal.requireNoCSharpHealthHold(paths)
                val depRoot=paths.final;val header=objectAt(depRoot,AUTHORITY,active);requireAuthority(header,dep)
                require(LocalPackageInstallJournal.matchesActivation(depRoot,text(header,"activation")))
                require(LocalUbuntuCSharpArchive.mode(File(depRoot,"SHA256SUMS"))==420)
                require(snapshot(depRoot,dep,active)==NimRuntimeClosure.readText(File(depRoot,"SHA256SUMS"),16_777_216,active))
                val inv=inventoryAt(dep,depRoot,active)
                require(inv.treeSha256==text(header,"treeSha256")&&inv.configSha256==text(header,"configSha256"))
                inventories+=inv
            }
            val notices=if(recipe.familyId==LocalUbuntuCSharpPolicy.FAMILY)CSharpRuntimeClosure.requireManagedClosure(inventories,active)else own.notices
            require(strings(a,"notices")==notices) { "C# bounded complete component attribution changed" }
            val closure=CSharpRuntimeClosure.guestClosure(File(appRoot,"guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/rootfs"),inventories,active)
            val c=objectAt(root,CLOSURE,active)
            require(c.keys==setOf("schema","files","sha256")&&c["schema"]==JsonPrimitive(1))
            require(closure.files==strings(c,"files")&&closure.sha256==text(c,"sha256")&&closure.sha256==text(a,"closureSha256")) { "C# complete three-artifact native closure changed" }
            return closure
        }
        fun inspectPackage(appRoot:String,record:ManagedPackageRecord,active:()->Unit={}):CSharpPackageProof {
            active();val recipe=LocalUbuntuCSharpPolicy.requested(record.familyId,record.version)
            val paths=LocalPackageInstallJournal.paths(appRoot,record.familyId,record.version,LocalUbuntuCSharpPolicy.KIND)
            LocalPackageInstallJournal.requireNoCSharpHealthHold(paths)
            require(record.installRoot==paths.final.absolutePath&&record.active&&record.abi=="arm64-v8a"&&record.scope==ExecutionScope.LOCAL_LINUX_ARM64.name)
            require(record.metadata[LocalManagedPackageMetadata.KIND_KEY]==LocalUbuntuCSharpPolicy.KIND&&record.metadata[LocalManagedPackageMetadata.PROFILE_KEY]==DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name&&record.metadata[LocalManagedPackageMetadata.LIBC_KEY]==DeclarativeLibcCompatibility.GLIBC.name)
            require(record.metadata[PackageBackendContract.METADATA_KEY]==PackageBackendId.LOCAL_APP.name&&record.environment.isEmpty()&&record.checksumFile=="SHA256SUMS"&&record.healthChecks.isEmpty())
            require(record.commands==mapOf(recipe.command to "${record.installRoot}/payload/host-bin/${recipe.command}")&&record.pathEntries==listOf("${record.installRoot}/payload/host-bin"))
            require(record.dependencies==if(record.familyId==LocalUbuntuCSharpPolicy.FAMILY)LocalUbuntuCSharpPolicy.dependencyKeys else emptyList<String>())
            val root=File(record.installRoot);require(root.canonicalFile==root.absoluteFile);val a=objectAt(root,AUTHORITY,active);requireAuthority(a,recipe)
            val id=text(a,"activation");require(LocalPackageInstallJournal.matchesActivation(root,id)&&id==record.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY])
            val seal=sha(File(root,"SHA256SUMS"),active);require(seal==record.metadata[LocalManagedPackageMetadata.SEAL_SHA256_KEY]&&text(a,"localArchiveSha256")==record.artifactSha256)
            require(text(a,"treeSha256")==record.metadata[LocalManagedPackageMetadata.TREE_SHA256_KEY]&&sha(File(root,TREE_MANIFEST),active)==text(a,"treeSha256"))
            val c=objectAt(root,CLOSURE,active);require(c.keys==setOf("schema","files","sha256")&&c["schema"]==JsonPrimitive(1));val files=strings(c,"files")
            require(files.size in 1..4096&&files.all{(path,hash)->LocalUbuntuCSharpArchive.requirePath(path);hash.matches(Regex("[0-9a-f]{64}"))})
            require(text(c,"sha256")==NimRuntimeClosure.sha256(files.toSortedMap().entries.joinToString("\n"){"${it.key}\u0000${it.value}"})&&text(c,"sha256")==text(a,"closureSha256"))
            if(record.familyId==LocalUbuntuCSharpPolicy.FAMILY) {
                val health=objectAt(root,HEALTH_RECEIPT,active)
                require(health.keys==setOf("schema","profile","authoritySha256","closureSha256","cleanupVerified","exitCode")&&health["schema"]==JsonPrimitive(1)&&health["cleanupVerified"]==JsonPrimitive(true)&&health["exitCode"]==JsonPrimitive(0))
                require(text(health,"profile")=="zero-project-isolated-stdio"&&text(health,"authoritySha256")==sha(File(root,AUTHORITY),active)&&text(health,"closureSha256")==text(a,"closureSha256"))
            }
            return CSharpPackageProof(record,receiptSha(record),id,seal,text(a,"treeSha256"),text(a,"configSha256"),text(a,"closureSha256"),files,strings(a,"helpers"),strings(a,"dependencyReceipts"))
        }
        private fun requireAuthority(a:JsonObject,recipe:CSharpInstallRecipe) {
            require(a.keys==setOf("schema","policy","launchPolicy","family","version","role","final","activation","publisherAlgorithm","publisherDigest","publisherBase64","localArchiveSha256","archiveBytes","url","provenance","treeSha256","configSha256","closureSha256","helpers","dependencyReceipts","notices","signaturePolicy"))
            require(a["schema"]==JsonPrimitive(1)&&text(a,"policy")==LocalUbuntuCSharpPolicy.POLICY&&text(a,"launchPolicy")==LocalUbuntuCSharpLaunchAuthority.POLICY)
            require(text(a,"family")==recipe.familyId&&text(a,"version")==recipe.version&&text(a,"role")==recipe.role.name&&text(a,"final")==LocalUbuntuCSharpPolicy.root(recipe.familyId))
            require(text(a,"publisherAlgorithm")==recipe.publisherDigest.algorithm.jca&&text(a,"publisherDigest")==recipe.publisherDigest.hex&&a["publisherBase64"]==(recipe.publisherDigest.publisherBase64?.let(::JsonPrimitive)?:JsonNull))
            require(text(a,"url")==recipe.url&&text(a,"provenance")==recipe.provenanceUrl)
            val size=a["archiveBytes"] as? JsonPrimitive?:error("Missing archive size");require(!size.isString&&size.long in 1..recipe.maxArchiveBytes&&(recipe.expectedBytes==null||size.long==recipe.expectedBytes))
            for(key in listOf("localArchiveSha256","treeSha256","configSha256","closureSha256"))require(text(a,key).matches(Regex("[0-9a-f]{64}")))
            require(strings(a,"helpers")==if(recipe.familyId==LocalUbuntuCSharpPolicy.FAMILY)LocalUbuntuCSharpLaunchAuthority.expectedHelperHashes else emptyMap<String,String>())
            require(strings(a,"dependencyReceipts").keys==if(recipe.familyId==LocalUbuntuCSharpPolicy.FAMILY)setOf(LocalUbuntuCSharpSdkPolicy.FAMILY,LocalUbuntuCSharpHelperPolicy.FAMILY) else emptySet<String>())
            require(strings(a,"dependencyReceipts").values.all{it.matches(Regex("[0-9a-f]{64}"))})
            require(text(a,"signaturePolicy")==if(recipe.role==CSharpArtifactRole.MANAGED_NUPKG)LocalUbuntuCSharpPolicy.SIGNATURE_POLICY else "Publisher artifact digest verified.")
        }
        fun provePackage(appRoot:String,record:ManagedPackageRecord,active:()->Unit={}):CSharpPackageProof {
            val proof=inspectPackage(appRoot,record,active);val root=File(record.installRoot);val recipe=LocalUbuntuCSharpPolicy.recipe(record.familyId)
            require(LocalUbuntuCSharpArchive.mode(File(root,"SHA256SUMS"))==420)
            require(snapshot(root,recipe,active)==NimRuntimeClosure.readText(File(root,"SHA256SUMS"),16_777_216,active)) { "C# package full manifest changed" }
            val inv=inventory(record,active);require(inv.treeSha256==proof.treeSha256&&inv.configSha256==proof.configSha256)
            for((path,hash)in proof.helpersSha256)require(sha(File(root,path),active)==hash)
            require(NimRuntimeClosure.readText(File(root,"payload/bin/${recipe.command}"),65536,active)==LocalUbuntuCSharpLaunchAuthority.guestWrapper(recipe.familyId,LocalUbuntuCSharpPolicy.root(recipe.familyId)))
            require(NimRuntimeClosure.readText(File(root,"payload/host-bin/${recipe.command}"),65536,active)==LocalUbuntuVendorPolicy.hostWrapper("$appRoot/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch","${LocalUbuntuCSharpPolicy.root(recipe.familyId)}/payload/bin/${recipe.command}"))
            completeDataProof(appRoot,root,recipe,objectAt(root,AUTHORITY,active),active)
            if(recipe.familyId==LocalUbuntuCSharpPolicy.FAMILY)requireHealthSeed(appRoot,root,LocalUbuntuCSharpPolicy.root(recipe.familyId),active)
            return proof
        }
        fun proveStaged(appRoot:String,stage:File,active:()->Unit={}) {
            require(stage.absolutePath==LocalPackageInstallJournal.paths(appRoot,LocalUbuntuCSharpPolicy.FAMILY,LocalUbuntuCSharpPolicy.VERSION,LocalUbuntuCSharpPolicy.KIND).stage.absolutePath)
            val a=objectAt(stage,AUTHORITY,active);val recipe=LocalUbuntuCSharpPolicy.recipe(LocalUbuntuCSharpPolicy.FAMILY);requireAuthority(a,recipe)
            require(LocalPackageInstallJournal.matchesActivation(stage,text(a,"activation")))
            val members=LocalUbuntuCSharpArchive.decode(NimRuntimeClosure.readText(File(stage,TREE_MANIFEST),16_777_216,active))
            require(NimRuntimeClosure.sha256(LocalUbuntuCSharpArchive.encode(members))==text(a,"treeSha256"))
            val inv=CSharpRuntimeClosure.inspectTree(recipe,File(stage,"payload/tree"),members,active);require(inv.configSha256==text(a,"configSha256"))
            for((path,hash)in strings(a,"helpers"))require(sha(File(stage,path),active)==hash)
            completeDataProof(appRoot,stage,recipe,a,active)
            requireHealthSeed(appRoot,stage,stage.absolutePath,active)
        }
        private fun requireHealthSeed(appRoot:String,root:File,projectedRoot:String,active:()->Unit) {
            val a=objectAt(root,AUTHORITY,active);val seed=objectAt(root,HEALTH_SEED,active)
            require(seed.keys==setOf("schema","policy","packageRoot","sdkRoot","serverExecutable","sdkExecutable","helperExecutable","activation","sdkActivation","helperActivation","receiptSha256","sdkReceiptSha256","helperReceiptSha256","closurePath","closureSha256","helpers","helperConfigSha256","helperSourceCommit","helperArchiveSha256","helperSourceBindingSha256","sdkVersion","runtimeVersion","proofs"))
            require(seed["schema"]==JsonPrimitive(1)&&text(seed,"policy")==LocalUbuntuCSharpLaunchAuthority.POLICY)
            require(text(seed,"packageRoot")==projectedRoot&&text(seed,"serverExecutable")=="$projectedRoot/payload/tree/${LocalUbuntuCSharpPolicy.ENTRY}")
            require(text(seed,"sdkRoot")==LocalUbuntuCSharpSdkPolicy.SDK_ROOT&&text(seed,"sdkExecutable")==LocalUbuntuCSharpSdkPolicy.EXECUTABLE&&text(seed,"helperExecutable")==LocalUbuntuCSharpHelperPolicy.EXECUTABLE)
            require(text(seed,"activation")==text(a,"activation")&&text(seed,"receiptSha256")==sha(File(root,AUTHORITY),active))
            require(text(seed,"closurePath")=="$projectedRoot/$CLOSURE"&&text(seed,"closureSha256")==text(a,"closureSha256"))
            require(strings(seed,"helpers")==LocalUbuntuCSharpLaunchAuthority.expectedHelperHashes)
            require(text(seed,"sdkVersion")==LocalUbuntuCSharpSdkPolicy.VERSION&&text(seed,"runtimeVersion")==LocalUbuntuCSharpSdkPolicy.RUNTIME_VERSION)
            require(text(seed,"helperSourceCommit")==LocalUbuntuCSharpHelperPolicy.SOURCE_COMMIT&&text(seed,"helperArchiveSha256")==LocalUbuntuCSharpHelperPolicy.SHA256&&text(seed,"helperSourceBindingSha256")==LocalUbuntuCSharpHelperPolicy.SOURCE_BINDING_SHA256)
            val expected=LocalUbuntuCSharpLaunchAuthority.expectedHelperHashes.mapKeys{"$projectedRoot/${it.key}"}.toMutableMap()
            for(path in listOf(AUTHORITY,CLOSURE,"payload/tree/${LocalUbuntuCSharpPolicy.ENTRY}"))expected["$projectedRoot/$path"]=NimRuntimeClosure.measure(File(root,path),536_870_912,active).second
            for((family,prefix)in listOf(LocalUbuntuCSharpSdkPolicy.FAMILY to "sdk",LocalUbuntuCSharpHelperPolicy.FAMILY to "helper")) {
                val recipe=LocalUbuntuCSharpPolicy.recipe(family);val depRoot=LocalPackageInstallJournal.paths(appRoot,family,recipe.version,LocalUbuntuCSharpPolicy.KIND).final
                val header=objectAt(depRoot,AUTHORITY,active)
                require(text(seed,prefix+"Activation")==text(header,"activation")&&text(seed,prefix+"ReceiptSha256")==strings(a,"dependencyReceipts").getValue(family))
                val executable=if(prefix=="sdk")LocalUbuntuCSharpSdkPolicy.EXECUTABLE else LocalUbuntuCSharpHelperPolicy.EXECUTABLE
                expected[executable]=NimRuntimeClosure.measure(File(depRoot,"payload/tree/${recipe.entryPoint}"),536_870_912,active).second
                if(prefix=="helper")require(text(seed,"helperConfigSha256")==NimRuntimeClosure.measure(File(depRoot,"payload/tree/${LocalUbuntuCSharpHelperPolicy.CONFIG}"),2_097_152,active).second)
            }
            val proofs=seed["proofs"] as? JsonArray?:error("Missing C# health file proof")
            val actual=proofs.map { item->val o=item as? JsonObject?:error("Invalid C# health proof");require(o.keys==setOf("path","sha256"));text(o,"path") to text(o,"sha256") }
            require(actual.size==expected.size&&actual.toMap().size==actual.size&&actual.toMap()==expected) { "C# health seed file binding changed" }
        }

        private fun snapshot(root:File,recipe:CSharpInstallRecipe,active:()->Unit):String {
            NimRuntimeClosure.requireDirectory(root);require(root.canonicalFile==root.absoluteFile&&LocalUbuntuCSharpArchive.mode(root)==493)
            val members=mutableListOf<LocalUbuntuCSharpArchive.Member>();var bytes=0L
            Files.walk(root.toPath()).use{stream->stream.forEach{p->active();if(p==root.toPath())return@forEach
                val relative=root.toPath().relativize(p).toString().replace(File.separatorChar,'/');if(relative=="SHA256SUMS")return@forEach
                require(members.size<recipe.maxFiles*2+64);LocalUbuntuCSharpArchive.requirePath(relative)
                val member=when {
                    Files.isSymbolicLink(p)->{require(relative.startsWith("payload/tree/"));LocalUbuntuCSharpArchive.Member(relative,"link",511,target=Files.readSymbolicLink(p).toString())}
                    Files.isDirectory(p,NOFOLLOW_LINKS)->LocalUbuntuCSharpArchive.Member(relative,"directory",LocalUbuntuCSharpArchive.mode(p.toFile()))
                    Files.isRegularFile(p,NOFOLLOW_LINKS)->{val measured=NimRuntimeClosure.measure(p.toFile(),536_870_912,active);bytes=Math.addExact(bytes,measured.first);require(bytes<=recipe.maxTreeBytes+33_554_432)
                        LocalUbuntuCSharpArchive.Member(relative,"file",LocalUbuntuCSharpArchive.mode(p.toFile()),measured.first,measured.second)}
                    else->error("C# package special file")
                };members+=member
            }}
            return LocalUbuntuCSharpArchive.encode(members)
        }
        private fun healthSeed(stage:File,id:String,sdk:ManagedPackageRecord,helper:ManagedPackageRecord,config:String,closure:NimRuntimeClosure.Closure,appRoot:String,active:()->Unit):String {
            val h=inspectPackage(appRoot,helper,active);val s=inspectPackage(appRoot,sdk,active)
            val proofs=LocalUbuntuCSharpLaunchAuthority.expectedHelperHashes.mapKeys{stage.absolutePath+"/"+it.key}.toMutableMap()
            for(path in listOf(AUTHORITY,CLOSURE,"payload/tree/${LocalUbuntuCSharpPolicy.ENTRY}"))proofs["${stage.absolutePath}/$path"]=NimRuntimeClosure.measure(File(stage,path),536_870_912,active).second
            proofs[LocalUbuntuCSharpSdkPolicy.EXECUTABLE]=NimRuntimeClosure.measure(File(sdk.installRoot,"payload/tree/dotnet"),536_870_912,active).second
            proofs[LocalUbuntuCSharpHelperPolicy.EXECUTABLE]=NimRuntimeClosure.measure(File(helper.installRoot,"payload/tree/${LocalUbuntuCSharpHelperPolicy.ENTRY}"),536_870_912,active).second
            return buildJsonObject {
                put("schema",1);put("policy",LocalUbuntuCSharpLaunchAuthority.POLICY);put("packageRoot",stage.absolutePath);put("sdkRoot",LocalUbuntuCSharpSdkPolicy.SDK_ROOT)
                put("serverExecutable","${stage.absolutePath}/payload/tree/${LocalUbuntuCSharpPolicy.ENTRY}");put("sdkExecutable",LocalUbuntuCSharpSdkPolicy.EXECUTABLE);put("helperExecutable",LocalUbuntuCSharpHelperPolicy.EXECUTABLE)
                put("activation",id);put("sdkActivation",s.activationId);put("helperActivation",h.activationId)
                put("receiptSha256",sha(File(stage,AUTHORITY),active));put("sdkReceiptSha256",s.receiptSha256);put("helperReceiptSha256",h.receiptSha256)
                put("closurePath","${stage.absolutePath}/$CLOSURE");put("closureSha256",closure.sha256)
                put("helpers",mapping(LocalUbuntuCSharpLaunchAuthority.expectedHelperHashes));put("helperConfigSha256",NimRuntimeClosure.measure(File(helper.installRoot,"payload/tree/${LocalUbuntuCSharpHelperPolicy.CONFIG}"),2_097_152,active).second)
                put("helperSourceCommit",LocalUbuntuCSharpHelperPolicy.SOURCE_COMMIT);put("helperArchiveSha256",LocalUbuntuCSharpHelperPolicy.SHA256);put("helperSourceBindingSha256",LocalUbuntuCSharpHelperPolicy.SOURCE_BINDING_SHA256)
                put("sdkVersion",LocalUbuntuCSharpSdkPolicy.VERSION);put("runtimeVersion",LocalUbuntuCSharpSdkPolicy.RUNTIME_VERSION)
                put("proofs",JsonArray(proofs.toSortedMap().map{(path,hash)->buildJsonObject{put("path",path);put("sha256",hash)}}))
            }.toString()+"\n"
        }
        private fun relocateSeed(raw:String,stage:String,guest:String):String {
            fun replace(value:JsonElement):JsonElement=when(value){is JsonObject->JsonObject(value.mapValues{replace(it.value)});is JsonArray->JsonArray(value.map(::replace));is JsonPrimitive->if(value.isString&&(value.content==stage||value.content.startsWith("$stage/")))JsonPrimitive(guest+value.content.removePrefix(stage))else value;else->value}
            return replace(LocalUbuntuPowerShellRuntimePolicy.strictObject(raw,4_194_304)).toString()+"\n"
        }
    }
}
