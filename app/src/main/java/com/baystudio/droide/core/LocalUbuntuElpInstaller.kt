package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal data class ElpPackageProof(val receiptSha256:String,val sealSha256:String,val activationId:String,val memberSha256:String,val memberBytes:Long,val helpersSha256:Map<String,String>)

/** An explicit permit authorizes a single isolated health attempt; it never grants project execution. */
internal class LocalUbuntuElpInstaller(context:Context,private val authority:LocalManagedPackageAuthority) {
    private val app=context.applicationContext
    private val downloader=UserInitiatedArtifactTransfer(app)
    suspend fun install(recipe:LocalUbuntuVendorRecipe,permit:ElpStageHealthPermit?,progress:(String)->Unit={},
        deadline:ElpDeadline=ElpDeadline.after(900_000)):ManagedPackageRecord = withContext(Dispatchers.IO) {
        recipe.validate();LocalUbuntuElpPolicy.requireReviewed(recipe)
        val approved=requireNotNull(permit) { "ELP inactive: explicit selected Erlang health approval is required in Extensions" }
        approved.requireCurrent();approved.claim()
        val operation=deadline.reserve(10_000)
        withTimeout(operation.remainingMillis().coerceAtLeast(1)) {
            val root=authority.appRoot
            val paths=LocalPackageInstallJournal.paths(root,recipe.familyId,recipe.version,LocalUbuntuElpPolicy.KIND)
            val context=currentCoroutineContext();val active={context.ensureActive();operation.check();approved.requireCurrent()}
            val generation=authority.elpPackageGeneration()
            val previous=authority.ownedReceipt(recipe.familyId,recipe.version)
            val previousId=previous?.metadata?.get(LocalManagedPackageMetadata.ACTIVATION_ID_KEY)
            var id:String?=null
            var hold:LocalPackageInstallJournal.ElpHealthHold?=null
            var runtimeEntered=false
            var installerClaim:ElpInstallerClaim?=null
            var cleanup:ElpTeardownReceipt?=null
            var primaryFailure:Throwable?=null
            try {
                // Durable custody survives app restart; inspect it before any staging or health work.
                active();ElpOwnerCustody.requireNoCustody(root)
                installerClaim=ManagedPackageMutationGate.mutex.withLock {
                    active()
                    ElpGenerationAdmission.withAdmission {
                        check(authority.isElpPackageGenerationCurrent(generation))
                        LocalUbuntuElpLaunchAuthority.acquireInstaller(root)
                    }
                }
                // Only bounded journal/generation publication is serialized. No download/probe/UI wait occurs
                // here.
                LocalPackageInstallJournal.transactionMutex.withLock {
                    active();LocalUbuntuElpLaunchAuthority.requireNoActivity(root,requireNotNull(installerClaim))
                    check(authority.isElpPackageGenerationCurrent(generation))
                    LocalPackageInstallJournal.recover(paths,previous?.let{LocalPackageInstallJournal.Receipt(previousId)})
                    require(paths.stage.mkdirs());id=LocalPackageInstallJournal.prepare(paths.stage)
                    LocalPackageInstallJournal.beginElpActivation(paths,requireNotNull(id),previousId)
                    hold=LocalPackageInstallJournal.beginElpHealth(paths,requireNotNull(id))
                }
                progress("Authenticating exact original ELP archive; selected tools remain unverified until permitted probes.\n")
                val spec=TrustedArtifactSpec("elp-original-2026-10-14",recipe.assetUrl,recipe.assetSha256,recipe.assetName,
                    LocalUbuntuElpPolicy.ARTIFACT_BYTES,LocalUbuntuElpPolicy.ARTIFACT_BYTES)
                downloader.withArtifact(spec,{active();progress(it.describe()+"\n")}) { archive ->
                    LocalUbuntuElpArchive.requireOriginal(archive,active)
                    val payload=File(paths.stage,"payload");require(payload.mkdir())
                    val member=LocalUbuntuElpArchive.extract(recipe,archive,File(payload,"tree"),active)
                    copy(archive,File(paths.stage,"ORIGINAL.tar.gz"),active)
                    val origin=asset("ORIGIN.json",LocalUbuntuElpPolicy.ORIGIN_SHA256)
                    val notice=asset("NOTICE-POLICY.json",LocalUbuntuElpPolicy.NOTICE_POLICY_SHA256)
                    write(paths.stage,"DROIDE-NOTICES/origin.json",origin)
                    write(paths.stage,"NOTICE-POLICY.json",notice)
                    val licenseObject=LocalUbuntuPowerShellRuntimePolicy.strictObject(origin.toString(Charsets.UTF_8),131072)["licenses"]!!.jsonObject
                    for((name,hash)in LocalUbuntuElpPolicy.licenseHashes) {
                        val sourceName=name.removePrefix("ELP-").removeSuffix(".txt")
                        val bytes=licenseObject.getValue(sourceName).jsonObject.getValue("text").jsonPrimitive.content.toByteArray()
                        require(LocalUbuntuElpPolicy.sha(bytes)==hash);write(paths.stage,"DROIDE-NOTICES/$name",bytes)
                    }
                    for((relative,hash)in LocalUbuntuElpPolicy.helperHashes) {
                        val bytes=app.assets.open("lsp/elp/${relative.substringAfterLast('/')}").use{it.readBytes()}
                        require(bytes.size<=262144&&LocalUbuntuElpPolicy.sha(bytes)==hash);write(paths.stage,relative,bytes)
                    }
                    write(paths.stage,"payload/bin/elp",LocalUbuntuElpPolicy.wrapper().toByteArray(),true)
                    write(paths.stage,"payload/host-bin/elp",LocalUbuntuElpPolicy.wrapper().toByteArray(),true)
                    write(paths.stage,AUTHORITY,buildJsonObject{
                        put("schema",1);put("policy",LocalUbuntuElpPolicy.POLICY);put("artifactSha256",LocalUbuntuElpPolicy.SHA256)
                        put("artifactBytes",LocalUbuntuElpPolicy.ARTIFACT_BYTES);put("releaseTag",LocalUbuntuElpPolicy.RELEASE_TAG)
                        put("activation",requireNotNull(id));put("memberSha256",member.sha256);put("memberBytes",member.size)
                        put("helpers",JsonObject(LocalUbuntuElpPolicy.helperHashes.mapValues{JsonPrimitive(it.value)}))
                        put("coverage",LocalUbuntuElpPolicy.COVERAGE)
                    }.toString().toByteArray())
                    val initial=snapshot(paths.stage,false,active)
                    write(paths.stage,"SHA256SUMS",initial.toString().toByteArray())
                    val provisional=record(paths.final,requireNotNull(id),LocalUbuntuElpPolicy.sha(initial.toString().toByteArray()))
                    val proof=proof(provisional,paths.stage,false,active)
                    val stageExecutable=File(paths.stage,"payload/tree/elp").absolutePath
                    val identity=LocalUbuntuElpLaunchAuthority.identity(provisional,proof,stageExecutable,stageExecutable,approved.request.selectionIdentity)
                    progress("Running explicitly approved selected OTP probes and empty-project ELP lifecycle health…\n")
                    requireNotNull(hold).enterRuntime();runtimeEntered=true
                    val health=LocalExecutionSubstrate.runElpStagedHealth(paths.stage,identity,approved.request.selection,approved,requireNotNull(hold),operation.limited(90_000))
                    cleanup=health.cleanup
                    check(health.healthPassed&&health.cleanup.complete) { "ELP remains inactive: health or cleanup failed; exact stage is retained" }
                    LocalPackageInstallJournal.requireNoElpHealthHold(paths)
                    // Original archive, extracted bytes, licenses and helper identity are re-proved after native
                    // execution.
                    proveStaged(paths.stage,identity,active)
                    write(paths.stage,HEALTH,buildJsonObject{
                        put("schema",1);put("policy",LocalUbuntuElpPolicy.POLICY);put("selectionIdentity",approved.request.selectionIdentity)
                        put("attempt",approved.attemptId);put("artifactSha256",LocalUbuntuElpPolicy.SHA256);put("activation",requireNotNull(id))
                        put("transportAndOtpPassed",true);put("cleanupVerified",true);put("label",LocalUbuntuElpLspHealth.LABEL)
                        put("evidence",health.evidence.take(16384))
                    }.toString().toByteArray())
                    val sealed=snapshot(paths.stage,true,active);write(paths.stage,"SHA256SUMS",sealed.toString().toByteArray())
                    val result=record(paths.final,requireNotNull(id),LocalUbuntuElpPolicy.sha(sealed.toString().toByteArray()))
                    proof(result,paths.stage,true,active)
                    LocalPackageInstallJournal.transactionMutex.withLock {
                        active();check(authority.isElpPackageGenerationCurrent(generation)) { "ELP package generation changed during health" }
                        LocalUbuntuElpLaunchAuthority.requireNoActivity(root,requireNotNull(installerClaim))
                        LocalPackageInstallJournal.beforeElpPromotion(paths,requireNotNull(id),previousId)
                        LocalPackageInstallJournal.activate(paths.stage,paths.final,paths.previous)
                        LocalPackageInstallJournal.syncElpDirectories(paths)
                        authority.adoptElp(result,requireNotNull(installerClaim))
                        LocalPackageInstallJournal.recoverElp(paths,LocalPackageInstallJournal.Receipt(requireNotNull(id)))
                    }
                    progress(LocalUbuntuElpLspHealth.LABEL+".\n")
                    result
                }
            } catch(failure:Throwable) {
                primaryFailure=failure
                // Positive in-process proof only. Never try this after a runtime entry, even when it throws.
                if(!runtimeEntered) hold?.let { unstarted ->
                    try { LocalPackageInstallJournal.finishElpHealthNeverEntered(unstarted) }
                    catch(retirement:Throwable) { failure.addSuppressed(retirement) }
                }
                // Never infer cleanup from a thrown exception, cancellation, timeout, or missing parent process.
                // Exact stage/activation/owner markers preserve custody and the previous durable generation.
                failure.addSuppressed(IllegalStateException("ELP inactive attempt retained for explicit repair; no unresolved stage is automatically deleted"))
                throw failure
            } finally {
                approved.invalidate()
                installerClaim?.let { claim ->
                    try {
                        // A durable unresolved marker must outlive this in-memory claim, even after restart.
                        if(!runtimeEntered) {
                            LocalPackageInstallJournal.requireNoElpHealthHold(paths)
                            claim.releaseNeverEntered()
                        } else cleanup?.let { receipt ->
                            LocalPackageInstallJournal.requireNoElpHealthHold(paths)
                            claim.release(receipt)
                        }
                    } catch(settlement:Throwable) {
                        if(primaryFailure!=null) primaryFailure!!.addSuppressed(settlement) else throw settlement
                    }
                }
            }
        }
    }
    private fun asset(name:String,hash:String):ByteArray=app.assets.open("lsp/elp/$name").use{it.readBytes()}.also{require(it.size<=131072&&LocalUbuntuElpPolicy.sha(it)==hash)}
    companion object {
        const val AUTHORITY="ELP-AUTHORITY.json"
        const val HEALTH="ELP-HEALTH.json"
        private fun record(final:File,id:String,seal:String)=ManagedPackageRecord(LocalUbuntuElpPolicy.FAMILY,LocalUbuntuElpPolicy.VERSION,
            ExecutionScope.LOCAL_LINUX_ARM64.name,final.absolutePath,0,active=true,
            pathEntries=listOf("${final.absolutePath}/payload/host-bin"),commands=mapOf("elp" to "${final.absolutePath}/payload/host-bin/elp"),
            artifactSha256=LocalUbuntuElpPolicy.SHA256,abi="arm64-v8a",metadata=mapOf(
                PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,
                LocalManagedPackageMetadata.KIND_KEY to LocalUbuntuElpPolicy.KIND,
                LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,
                LocalManagedPackageMetadata.ACTIVATION_ID_KEY to id,LocalManagedPackageMetadata.SEAL_SHA256_KEY to seal,
                "elp.policy" to LocalUbuntuElpPolicy.POLICY,"elp.release" to LocalUbuntuElpPolicy.RELEASE_TAG,"elp.health" to LocalUbuntuElpLspHealth.LABEL))
        fun provePackage(appRoot:String,record:ManagedPackageRecord,active:()->Unit={}):ElpPackageProof {
            require(record.installRoot==File(appRoot,"packages/${LocalUbuntuElpPolicy.FAMILY}/${LocalUbuntuElpPolicy.VERSION}/arm64-v8a").absolutePath)
            return proof(record,File(record.installRoot),true,active)
        }
        fun proveStaged(stage:File,identity:ElpManagedLaunchIdentity,active:()->Unit={}) {
            require(identity.launchExecutable==File(stage,"payload/tree/elp").absolutePath&&identity.discoveredExecutable==identity.launchExecutable)
            val provisional=record(File(stage.parentFile!!.parentFile!!.parentFile!!,"packages/${LocalUbuntuElpPolicy.FAMILY}/${LocalUbuntuElpPolicy.VERSION}/arm64-v8a"),identity.activationIdentity,identity.sealSha256)
            val p=proof(provisional,stage,false,active)
            require(p.receiptSha256==identity.receiptSha256&&p.memberSha256==identity.originalMemberSha256&&p.memberBytes==identity.originalMemberBytes&&p.helpersSha256==identity.helpersSha256)
        }
        private fun proof(record:ManagedPackageRecord,root:File,healthy:Boolean,active:()->Unit):ElpPackageProof {
            active();val id=requireNotNull(record.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY])
            val seal=requireNotNull(record.metadata[LocalManagedPackageMetadata.SEAL_SHA256_KEY])
            require(record==record(File(record.installRoot),id,seal).copy(active=record.active)) { "ELP receipt shape changed" }
            require(LocalPackageInstallJournal.matchesActivation(root,id))
            val bytes=NimRuntimeClosure.readText(File(root,"SHA256SUMS"),65536,active).toByteArray()
            require(LocalUbuntuElpPolicy.sha(bytes)==seal)
            val actual=snapshot(root,healthy,active);require(bytes.toString(Charsets.UTF_8)==actual.toString())
            val originalMember=LocalUbuntuElpArchive.proveOriginalMember(File(root,"ORIGINAL.tar.gz"),active)
            val a=readObject(File(root,AUTHORITY),active)
            require(a["schema"]==JsonPrimitive(1)&&a["policy"]==JsonPrimitive(LocalUbuntuElpPolicy.POLICY)&&a["artifactSha256"]==JsonPrimitive(LocalUbuntuElpPolicy.SHA256)&&
                a["artifactBytes"]==JsonPrimitive(LocalUbuntuElpPolicy.ARTIFACT_BYTES)&&a["releaseTag"]==JsonPrimitive(LocalUbuntuElpPolicy.RELEASE_TAG)&&a["activation"]==JsonPrimitive(id))
            val member=LocalUbuntuElpPolicy.measure(File(root,"payload/tree/elp"),LocalUbuntuElpPolicy.MAX_MEMBER_BYTES,active)
            require(originalMember.size==member.first&&originalMember.sha256==member.second) { "ELP expanded member differs from the authenticated original archive" }
            require(a["memberSha256"]==JsonPrimitive(member.second)&&a["memberBytes"]==JsonPrimitive(member.first))
            LocalUbuntuElpPolicy.requireNative(File(root,"payload/tree/elp"),active)
            require(a["helpers"]==JsonObject(LocalUbuntuElpPolicy.helperHashes.mapValues{JsonPrimitive(it.value)}))
            for((name,hash)in LocalUbuntuElpPolicy.licenseHashes)require(LocalUbuntuElpPolicy.measure(File(root,"DROIDE-NOTICES/$name"),131072,active).second==hash)
            require(LocalUbuntuElpPolicy.measure(File(root,"DROIDE-NOTICES/origin.json"),131072,active).second==LocalUbuntuElpPolicy.ORIGIN_SHA256)
            require(LocalUbuntuElpPolicy.measure(File(root,"NOTICE-POLICY.json"),131072,active).second==LocalUbuntuElpPolicy.NOTICE_POLICY_SHA256)
            for((name,hash)in LocalUbuntuElpPolicy.helperHashes)require(LocalUbuntuElpPolicy.measure(File(root,name),262144,active).second==hash)
            for(name in listOf("payload/bin/elp","payload/host-bin/elp"))require(NimRuntimeClosure.readText(File(root,name),65536,active)==LocalUbuntuElpPolicy.wrapper())
            if(healthy) {
                val health=readObject(File(root,HEALTH),active)
                require(health["schema"]==JsonPrimitive(1)&&health["policy"]==JsonPrimitive(LocalUbuntuElpPolicy.POLICY)&&health["artifactSha256"]==JsonPrimitive(LocalUbuntuElpPolicy.SHA256)&&
                    health["activation"]==JsonPrimitive(id)&&health["transportAndOtpPassed"]==JsonPrimitive(true)&&health["cleanupVerified"]==JsonPrimitive(true)&&health["label"]==JsonPrimitive(LocalUbuntuElpLspHealth.LABEL))
                require(health["selectionIdentity"]!!.jsonPrimitive.content.isNotBlank()&&health["attempt"]!!.jsonPrimitive.content.matches(Regex("[0-9a-f-]{36}")))
            }
            return ElpPackageProof(receiptSha(record.copy(active=true)),seal,id,member.second,member.first,LocalUbuntuElpPolicy.helperHashes)
        }
        internal fun snapshot(root:File,healthy:Boolean,active:()->Unit={}):JsonObject {
            val expected=setOf("DROIDE_ACTIVATION_ID",AUTHORITY,"ORIGINAL.tar.gz","NOTICE-POLICY.json","DROIDE-NOTICES/origin.json",
                "DROIDE-NOTICES/ELP-LICENSE-MIT.txt","DROIDE-NOTICES/ELP-LICENSE-APACHE.txt","payload/tree/elp","payload/bin/elp","payload/host-bin/elp")+
                LocalUbuntuElpPolicy.helperHashes.keys+(if(healthy)setOf(HEALTH)else emptySet())
            val files=linkedMapOf<String,JsonElement>();var entries=0;var total=0L
            require(root.canonicalFile==root.absoluteFile&&Files.isDirectory(root.toPath(),NOFOLLOW_LINKS))
            Files.walk(root.toPath()).use { walk -> walk.forEach { path ->
                active();require(++entries<=64&&!Files.isSymbolicLink(path))
                if(path==root.toPath())return@forEach
                val relative=root.toPath().relativize(path).toString().replace(File.separatorChar,'/')
                if(Files.isDirectory(path,NOFOLLOW_LINKS)) {require(expected.any{it.startsWith(relative+"/")});return@forEach}
                require(Files.isRegularFile(path,NOFOLLOW_LINKS));if(relative=="SHA256SUMS")return@forEach
                require(relative in expected)
                val metadata=Files.readAttributes(path,"unix:mode,nlink",NOFOLLOW_LINKS)
                val mode=(metadata["mode"] as Number).toInt()and 4095
                require((metadata["nlink"] as Number).toLong()==1L&&mode and 0xE12==0&&mode and 0x100!=0&&
                    (if(relative in setOf("payload/tree/elp","payload/bin/elp","payload/host-bin/elp"))mode and 0x40!=0 else mode and 0x49==0)) { "ELP file mode or hard-link custody changed" }
                val measure=LocalUbuntuElpPolicy.measure(path.toFile(),LocalUbuntuElpPolicy.MAX_MEMBER_BYTES,active)
                total=Math.addExact(total,measure.first);require(total<=LocalUbuntuElpPolicy.MAX_MEMBER_BYTES+LocalUbuntuElpPolicy.ARTIFACT_BYTES+1_048_576)
                files[relative]=buildJsonObject{put("sha256",measure.second);put("size",measure.first);put("mode",mode);put("executable",path.toFile().canExecute())}
            }}
            require(files.keys==expected)
            return JsonObject(files.toSortedMap())
        }
        internal fun receiptSha(record:ManagedPackageRecord):String {
            fun mapping(values:Map<String,String>)=JsonObject(values.toSortedMap().mapValues{JsonPrimitive(it.value)})
            fun strings(values:List<String>)=JsonArray(values.map(::JsonPrimitive))
            return LocalUbuntuElpPolicy.sha(buildJsonObject{
                put("family",record.familyId);put("version",record.version);put("scope",record.scope);put("root",record.installRoot)
                put("installedAt",record.installedAtEpochMs);put("active",record.active);put("paths",strings(record.pathEntries))
                put("commands",mapping(record.commands));put("environment",mapping(record.environment));put("dependencies",strings(record.dependencies))
                put("health",JsonArray(record.healthChecks.map{buildJsonObject{put("executable",it.executable);put("args",strings(it.args))}}))
                put("artifact",record.artifactSha256?.let(::JsonPrimitive)?:JsonNull);put("checksum",record.checksumFile)
                put("abi",record.abi?.let(::JsonPrimitive)?:JsonNull);put("metadata",mapping(record.metadata))
            }.toString().toByteArray())
        }
        private fun readObject(file:File,active:()->Unit)=LocalUbuntuPowerShellRuntimePolicy.strictObject(NimRuntimeClosure.readText(file,65536,active),65536)
        private fun write(root:File,path:String,bytes:ByteArray,executable:Boolean=false) {
            val file=File(root,path);require(file.parentFile!!.mkdirs()||file.parentFile!!.isDirectory);require(!Files.isSymbolicLink(file.toPath()))
            java.io.FileOutputStream(file).use{it.write(bytes);it.fd.sync()};require(file.setExecutable(executable,false))
        }
        private fun copy(source:File,target:File,active:()->Unit) {
            source.inputStream().use { input -> java.io.FileOutputStream(target).use { out ->
                val b=ByteArray(65536);while(true){active();val n=input.read(b);if(n<0)break;require(n>0);out.write(b,0,n)};out.fd.sync()
            }}
        }
    }
}
