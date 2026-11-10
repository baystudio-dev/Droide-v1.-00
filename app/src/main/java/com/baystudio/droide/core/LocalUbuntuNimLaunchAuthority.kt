package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal object LocalUbuntuNimLaunchAuthority {
    const val FILE_NAME = "NIM_AUTHORITY.json"
    const val CLOSURE_FILE = "NIM_CLOSURE.json"
    const val NOTICE_FILE = "NIM_ORIGINAL_NOTICE.txt"
    const val POLICY = "nimlangserver-1.16.0-nim-2.2.12-helper-22.23.3-fixed-v1"
    val helperPaths = listOf("payload/nim-supervisor.cjs", "payload/nimble-dump-adapter.cjs", "payload/nim-zero-document-health.cjs", "payload/nim-helper-identity-health.cjs")
    // Filled from the authored shipped assets, never from downloaded originals or installed
    // receipts.
    val expectedHelperHashes: Map<String,String> = mapOf(
        "payload/nim-supervisor.cjs" to "c8c48274321eeedb2a75dfe02d2e96ff487e4ef2055b859af263a81e13ef2a53",
        "payload/nimble-dump-adapter.cjs" to "7b832e7fe15af52849a30aaabcaaabb92e76245793b2683d1981fa1478eb51d1",
        "payload/nim-zero-document-health.cjs" to "988e4418d136210e18b2f6cb8856c1c189499e156e0eaa98f2b921fcb7727d18",
        "payload/nim-helper-identity-health.cjs" to "0a509244ae00e8e941b314a364c21c49093fd5a6d5bb60dc29e3b4cf3fd255c1"
    )
    val noticeText = """
        Nim fixed-toolchain analysis: original nimlangserver 1.16.0, Nim/nimsuggest 2.2.12 and bundled Nimble 0.24.1; private original Node 22.23.3.
        Publisher archives are preserved in full. Runtime admission records observed ELF, loader/library, config and notice identities; it does not establish project-code containment, computed/transitive compatibility, native target behavior or a comprehensive binary SBOM.
        Original Nim and nimlangserver MIT notices, Nimble BSD-3-Clause and full Node third-party notice text are retained with the app; all original archive notices stay in their original trees.
        https://github.com/nim-lang/langserver/tree/v1.16.0
        https://github.com/nim-lang/Nim/tree/v2.2.12
        https://github.com/nim-lang/nimble/tree/v0.24.1
        https://nodejs.org/dist/v22.23.3/SHASUMS256.txt
    """.trimIndent() + "\n"
    internal fun receiptSha(record: ManagedPackageRecord) = LocalUbuntuPowerShellLaunchAuthority.receiptSha(record)
    internal fun read(file:File,limit:Int=4*1024*1024,active:()->Unit={}):String = NimRuntimeClosure.readText(file,limit,active)
    internal fun objectAt(file:File,limit:Int=4*1024*1024,active:()->Unit={}):JsonObject =
        LocalUbuntuPowerShellRuntimePolicy.strictObject(read(file,limit,active),limit)
    private fun text(o:JsonObject,k:String)=LocalUbuntuPowerShellRuntimePolicy.string(o,k)
    private fun mapping(m:Map<String,String>)=JsonObject(m.toSortedMap().mapValues { JsonPrimitive(it.value) })
    private fun digest(m:Map<String,String>)=NimRuntimeClosure.sha256(mapping(m).toString())
    fun inventory(recipe:LocalUbuntuVendorRecipe,tree:File,active:()->Unit={}):NimRuntimeClosure.Inventory = when(recipe.familyId) {
        LocalUbuntuNimPolicy.FAMILY -> LocalUbuntuNimPolicy.requireTree(File(tree,recipe.entryPoint),active)
        LocalUbuntuNimToolchainPolicy.FAMILY -> LocalUbuntuNimToolchainPolicy.requireTree(File(tree,recipe.entryPoint),active)
        LocalUbuntuNimHelperPolicy.FAMILY -> LocalUbuntuNimHelperPolicy.requireTree(File(tree,recipe.entryPoint),active)
        else -> error("Not a reviewed Nim family")
    }
    fun requireDependency(record:ManagedPackageRecord,family:String,appRoot:String) {
        require(LocalUbuntuNimPolicy.isFamily(family) && record.familyId==family && record.version==LocalUbuntuNimPolicy.version(family))
        require(record.installRoot==LocalPackageInstallJournal.paths(appRoot,family,record.version,LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE).final.absolutePath)
        val recipe=requireNotNull(LocalUbuntuVendorPolicy.fromReceipt(record)); recipe.validate()
        require(record.artifactSha256==recipe.assetSha256 && record.scope==ExecutionScope.LOCAL_LINUX_ARM64.name && record.abi=="arm64-v8a")
    }
    private fun packageRoot(family:String)="/opt/droide/packages/$family/${LocalUbuntuNimPolicy.version(family)}/arm64-v8a"
    private fun guestOriginal(record:ManagedPackageRecord)=packageRoot(record.familyId)+"/payload/tree/"+requireNotNull(LocalUbuntuVendorPolicy.fromReceipt(record)).entryPoint
    private fun dependencies(recipe:LocalUbuntuVendorRecipe,records:List<ManagedPackageRecord>,appRoot:String):List<ManagedPackageRecord> {
        val wanted=if(recipe.familyId==LocalUbuntuNimPolicy.FAMILY) setOf(LocalUbuntuNimToolchainPolicy.FAMILY,LocalUbuntuNimHelperPolicy.FAMILY) else emptySet()
        require(records.size==wanted.size && records.map { it.familyId }.toSet()==wanted)
        records.forEach { requireDependency(it,it.familyId,appRoot) };return records.sortedBy { it.familyId }
    }

    fun prepare(recipe:LocalUbuntuVendorRecipe,stage:File,activation:String,finalGuest:String,owned:List<ManagedPackageRecord>,
        appRoot:String,helpers:Map<String,String>,active:()->Unit={}):Map<String,String> {
        recipe.validate();require(LocalUbuntuNimPolicy.isFamily(recipe.familyId))
        require(finalGuest==packageRoot(recipe.familyId) && activation.matches(Regex("[0-9a-f-]{36}")))
        require(stage.absolutePath==LocalPackageInstallJournal.paths(appRoot,recipe.familyId,recipe.version,LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE).stage.absolutePath)
        require(helpers.keys==helperPaths.toSet() && helpers.all { (k,v)->expectedHelperHashes[k]==NimRuntimeClosure.sha256(v) })
        val deps=dependencies(recipe,owned,appRoot)
        val own=inventory(recipe,File(stage,"payload/tree"),active)
        val inventories=listOf(own)+deps.map { inventory(requireNotNull(LocalUbuntuVendorPolicy.fromReceipt(it)),File(it.installRoot,"payload/tree"),active) }
        val needsNim=recipe.familyId!=LocalUbuntuNimHelperPolicy.FAMILY
        val loaderEvidence=if(needsNim) NimRuntimeClosure.requireDefaultLoaderEvidence(inventories,active) else emptyMap()
        val closure=NimRuntimeClosure.requireExecutionClosure(inventories,File(appRoot,"guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/rootfs"),
            if(needsNim) setOf("libssl.so.3","libcrypto.so.3","libpcre.so.3") else emptySet(),active)
        val helperRoot=if(recipe.familyId==LocalUbuntuNimHelperPolicy.FAMILY) stage else deps.singleOrNull { it.familyId==LocalUbuntuNimHelperPolicy.FAMILY }?.let { File(it.installRoot) }
        val config=helperRoot?.let { LocalUbuntuNimHelperPolicy.requireConfiguration(File(it,"payload/tree/${LocalUbuntuNimHelperPolicy.CONFIG}"),active) }.orEmpty()
        val tree=read(File(stage,"TREE_SHA256SUMS"),16*1024*1024,active)
        val closureText=buildJsonObject { put("schema",1);put("files",mapping(closure.files));put("sha256",closure.sha256) }.toString()+"\n"
        val authority=buildJsonObject {
            put("schema",1);put("policy",POLICY);put("family",recipe.familyId);put("version",recipe.version);put("archiveSha256",recipe.assetSha256)
            put("activation",activation);put("stage",stage.absolutePath);put("final",finalGuest);put("treeManifestSha256",NimRuntimeClosure.sha256(tree))
            put("helpers",mapping(expectedHelperHashes));put("configSha256",config);put("closureSha256",closure.sha256);put("ordinaryLoaderEvidence",mapping(loaderEvidence))
            put("sourceBindingSha256",LocalUbuntuNimHelperPolicy.SOURCE_BINDING_SHA256);put("sourceCommit",LocalUbuntuNimHelperPolicy.SOURCE_COMMIT)
            put("dependencyReceipts",mapping(deps.associate { it.familyId to receiptSha(it) }))
            put("noticeSha256",NimRuntimeClosure.sha256(noticeText))
        }.toString()+"\n"
        val result=linkedMapOf(FILE_NAME to authority,CLOSURE_FILE to closureText,NOTICE_FILE to noticeText)
        if(recipe.familyId==LocalUbuntuNimPolicy.FAMILY) {
            val helper=deps.single { it.familyId==LocalUbuntuNimHelperPolicy.FAMILY }; val toolchain=deps.single { it.familyId==LocalUbuntuNimToolchainPolicy.FAMILY }
            val proofs=linkedMapOf<String,String>()
            helperPaths.forEach { proofs["${stage.absolutePath}/$it"]=expectedHelperHashes.getValue(it) }
            proofs["${stage.absolutePath}/payload/tree/${recipe.entryPoint}"]=own.hashes.getValue(recipe.entryPoint)
            for(dep in deps) {
                val inv=inventories.single { it.root.absolutePath==File(dep.installRoot,"payload/tree").absolutePath }
                inv.native.keys.forEach { proofs[packageRoot(dep.familyId)+"/payload/tree/$it"]=inv.hashes.getValue(it) }
            }
            result["payload/nim-health-identity.json"]=buildJsonObject {
                put("helperExecutable",guestOriginal(helper));put("helperSourceCommit",LocalUbuntuNimHelperPolicy.SOURCE_COMMIT)
                put("helperArchiveSha256",LocalUbuntuNimHelperPolicy.SHA256);put("helperSourceBindingSha256",LocalUbuntuNimHelperPolicy.SOURCE_BINDING_SHA256)
                put("helperConfigSha256",config);put("closureSha256",closure.sha256);put("closurePath","${stage.absolutePath}/$CLOSURE_FILE")
                put("serverExecutable","${stage.absolutePath}/payload/tree/${recipe.entryPoint}")
                put("compilerExecutable",guestOriginal(toolchain));put("backendExecutable",LocalUbuntuNimToolchainPolicy.BACKEND);put("nimbleExecutable",LocalUbuntuNimToolchainPolicy.NIMBLE)
                put("proofs",JsonArray(proofs.map { (path,sha)->buildJsonObject { put("path",path);put("sha256",sha) } }))
            }.toString()+"\n"
        }
        return result
    }
    /** Relocate only known staged health fields after health, before the final checksum seal. */
    fun finalHealthSeed(seed:String,stage:String,finalGuest:String):String {
        val value=LocalUbuntuPowerShellRuntimePolicy.strictObject(seed,65536)
        require(finalGuest==LocalUbuntuNimPolicy.ROOT)
        fun relocate(path:String):String = if(path.startsWith("$stage/")) finalGuest+path.removePrefix(stage) else path
        val proofs=value["proofs"] as? JsonArray ?: error("Nim health proof list missing")
        require(proofs.size in 4..24)
        return JsonObject(value + mapOf("serverExecutable" to JsonPrimitive(relocate(text(value,"serverExecutable"))),
            "closurePath" to JsonPrimitive(relocate(text(value,"closurePath"))),
            "proofs" to JsonArray(proofs.map { element ->
                val proof=element as? JsonObject ?: error("Invalid Nim proof");require(proof.keys==setOf("path","sha256"))
                JsonObject(proof + ("path" to JsonPrimitive(relocate(text(proof,"path")))))
            }))).toString()+"\n"
    }

    private fun generation(record:ManagedPackageRecord,appRoot:String,active:()->Unit):Pair<JsonObject,String> {
        requireDependency(record,record.familyId,appRoot)
        val root=File(record.installRoot);NimRuntimeClosure.requireDirectory(root)
        require(root.absolutePath==root.canonicalPath)
        val recipe=requireNotNull(LocalUbuntuVendorPolicy.fromReceipt(record))
        require(LocalUbuntuVendorPolicy.verifyIntegration(record,recipe,appRoot))
        val activation=record.metadata.getValue(LocalManagedPackageMetadata.ACTIVATION_ID_KEY)
        require(read(File(root,"DROIDE_ACTIVATION_ID"),128,active)==activation)
        val seal=read(File(root,"SHA256SUMS"),16*1024*1024,active)
        require(NimRuntimeClosure.sha256(seal)==record.metadata[LocalManagedPackageMetadata.SEAL_SHA256_KEY])
        val a=objectAt(File(root,FILE_NAME),65536,active)
        require(a["schema"]==JsonPrimitive(1) && text(a,"policy")==POLICY && text(a,"family")==record.familyId && text(a,"version")==record.version &&
            text(a,"archiveSha256")==recipe.assetSha256 && text(a,"activation")==activation && text(a,"final")==packageRoot(record.familyId) &&
            text(a,"stage")==LocalPackageInstallJournal.paths(appRoot,record.familyId,record.version,LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE).stage.absolutePath &&
            a["helpers"]==mapping(expectedHelperHashes) && text(a,"sourceBindingSha256")==LocalUbuntuNimHelperPolicy.SOURCE_BINDING_SHA256 &&
            text(a,"sourceCommit")==LocalUbuntuNimHelperPolicy.SOURCE_COMMIT && text(a,"noticeSha256")==NimRuntimeClosure.sha256(noticeText))
        if(record.familyId==LocalUbuntuNimPolicy.FAMILY) {
            val seed=objectAt(File(root,"payload/nim-health-identity.json"),65536,active)
            require(text(seed,"serverExecutable")==LocalUbuntuNimPolicy.EXECUTABLE && text(seed,"helperExecutable")==LocalUbuntuNimHelperPolicy.EXECUTABLE &&
                text(seed,"compilerExecutable")==LocalUbuntuNimToolchainPolicy.EXECUTABLE && text(seed,"backendExecutable")==LocalUbuntuNimToolchainPolicy.BACKEND &&
                text(seed,"nimbleExecutable")==LocalUbuntuNimToolchainPolicy.NIMBLE && text(seed,"helperConfigSha256")==text(a,"configSha256") &&
                text(seed,"closureSha256")==text(a,"closureSha256") && text(seed,"closurePath")=="${LocalUbuntuNimPolicy.ROOT}/$CLOSURE_FILE")
        }
        closureFiles(root,a,active)
        return a to NimRuntimeClosure.sha256(seal)
    }
    /** Bounded metadata only: no inventory scan, compiler probe, generic verify, health or install. */
    suspend fun inspect(appRoot:String,server:ManagedPackageRecord,records:List<ManagedPackageRecord>,selections:Map<String,String>,discovered:String):NimManagedLaunchIdentity? {
        val context=currentCoroutineContext();val active={context.ensureActive()}
        return try {
            require(records.size<=512 && records.map { it.familyId to it.version }.distinct().size==records.size)
            require(server.familyId==LocalUbuntuNimPolicy.FAMILY && server.version==LocalUbuntuNimPolicy.VERSION)
            require(LocalLinuxPackageEnvironment.selectedRecords(records,selections,appRoot).singleOrNull { it.familyId==server.familyId }==server)
            val tool=records.single { it.familyId==LocalUbuntuNimToolchainPolicy.FAMILY && it.version==LocalUbuntuNimToolchainPolicy.VERSION }
            val helper=records.single { it.familyId==LocalUbuntuNimHelperPolicy.FAMILY && it.version==LocalUbuntuNimHelperPolicy.VERSION }
            val (a,seal)=generation(server,appRoot,active);val (ta,tseal)=generation(tool,appRoot,active);val (ha,hseal)=generation(helper,appRoot,active)
            require(a["dependencyReceipts"]==mapping(mapOf(tool.familyId to receiptSha(tool),helper.familyId to receiptSha(helper))))
            require(ta["dependencyReceipts"]==JsonObject(emptyMap()) && ha["dependencyReceipts"]==JsonObject(emptyMap()))
            val layerKey=server.dependencies.single { it !in setOf(LocalUbuntuNimToolchainPolicy.KEY,LocalUbuntuNimHelperPolicy.KEY) }
            require(server.dependencies.toSet()==setOf(layerKey,LocalUbuntuNimToolchainPolicy.KEY,LocalUbuntuNimHelperPolicy.KEY) && server.dependencies.size==3)
            require(tool.dependencies==listOf(layerKey) && helper.dependencies==listOf(layerKey))
            val layer=records.single { "${it.familyId}@${it.version}"==layerKey }
            val recipe=requireNotNull(LocalUbuntuVendorPolicy.fromReceipt(server))
            require(layer.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND]==FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND &&
                layer.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES]?.split(',')?.sorted()==recipe.requiredPackages.sorted())
            val launch="${LocalUbuntuNimPolicy.ROOT}/payload/bin/${LocalUbuntuNimPolicy.COMMAND}"
            require(discovered==launch || discovered=="/opt/droide/bin/nimlangserver")
            val projection=if(discovered==launch)"version-specific" else read(File(appRoot,"managed/ubuntu-bin/nimlangserver"),65536,active).also {
                require(it=="#!/bin/sh\nexec '$launch' \"${'$'}@\"\n")
            }
            NimManagedLaunchIdentity(server.familyId,server.version,tool.familyId,tool.version,helper.familyId,helper.version,POLICY,
                recipe.assetSha256,tool.artifactSha256!!,helper.artifactSha256!!,receiptSha(server),receiptSha(tool),receiptSha(helper),seal,tseal,hseal,
                NimRuntimeClosure.sha256(mapping(selections).toString()+":"+projection+":"+receiptSha(layer)),discovered,launch,
                LocalUbuntuNimPolicy.EXECUTABLE,LocalUbuntuNimToolchainPolicy.EXECUTABLE,LocalUbuntuNimToolchainPolicy.BACKEND,LocalUbuntuNimToolchainPolicy.NIMBLE,
                LocalUbuntuNimHelperPolicy.EXECUTABLE,text(a,"activation"),text(ta,"activation"),text(ha,"activation"),text(a,"configSha256"),
                LocalUbuntuNimHelperPolicy.SOURCE_BINDING_SHA256,text(a,"closureSha256"))
        } catch(c:CancellationException){throw c} catch(_:Exception){null}
    }
    /** Metadata-only closure currentness; no execution and no guest file hashing. */
    private fun closureFiles(root:File,authority:JsonObject,active:()->Unit):Map<String,String> {
        val closure=objectAt(File(root,CLOSURE_FILE),65536,active)
        require(closure.keys==setOf("schema","files","sha256") && closure["schema"]==JsonPrimitive(1))
        require(text(closure,"sha256")==text(authority,"closureSha256"))
        val values=closure["files"] as? JsonObject ?: error("Nim closure missing")
        require(values.size in 1..67)
        val result=values.mapValues { (path,value)->
            NimRuntimeClosure.requireRelative(path)
            require(path.startsWith("usr/lib/") || path.startsWith("lib/") || path in setOf("etc/ld.so.cache","etc/ld.so.preload","bin/sh","usr/bin/sh","bin/dash","usr/bin/dash"))
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.also { require(it.matches(Regex("[0-9a-f]{64}"))) }
                ?: error("Invalid Nim closure hash")
        }
        require(NimRuntimeClosure.sha256(result.toSortedMap().entries.joinToString("\n") { "${it.key}\u0000${it.value}" })==text(authority,"closureSha256"))
        return result
    }

    suspend fun provePackage(appRoot:String,record:ManagedPackageRecord,active:()->Unit={}) {
        val (a,_)=generation(record,appRoot,active);val root=File(record.installRoot);val recipe=requireNotNull(LocalUbuntuVendorPolicy.fromReceipt(record))
        val verified=requireNotNull(LocalPackageIntegrity.verifiedChecksums(root,File(root,"SHA256SUMS"),exactInventory=true))
        require(verified.size<=recipe.definition.maxFiles+32)
        var entries=0
        Files.walk(root.toPath()).use { paths -> paths.forEach { path ->
            active();require(++entries<=recipe.definition.maxFiles*2+64)
            val relative=root.toPath().relativize(path).toString().replace(File.separatorChar,'/')
            if(relative.isNotEmpty() && !relative.startsWith("payload/tree/")) {
                require(!Files.isSymbolicLink(path)) { "Nim managed metadata cannot be a link" }
                require(Files.isDirectory(path,NOFOLLOW_LINKS) || Files.isRegularFile(path,NOFOLLOW_LINKS))
            }
        } }
        (verified.keys + "SHA256SUMS").filter { !it.startsWith("payload/tree/") }.forEach { relative ->
            active();val stat=android.system.Os.lstat(File(root,relative).absolutePath)
            val mode=if(relative in setOf("payload/bin/${recipe.command}","payload/host-bin/${recipe.command}"))493 else 420
            require(stat.st_nlink==1L && stat.st_mode and 4095==mode) { "Nim managed helper/metadata mode or hardlink changed" }
        }
        require(NimRuntimeClosure.sha256(read(File(root,"TREE_SHA256SUMS"),16*1024*1024,active))==text(a,"treeManifestSha256"))
        val inv=inventory(recipe,File(root,"payload/tree"),active)
        val links=read(File(root,"TREE_LINKS.tsv"),512*1024,active).lineSequence().filter { it.isNotEmpty() }.associate { row->
            val p=row.split('\t');require(p.size==3);p[0] to p[1]
        };require(links==inv.links)
        val tree=StringBuilder()
        inv.files.sorted().forEach { relative->
            active();val file=File(inv.root,relative);val stat=android.system.Os.lstat(file.absolutePath)
            require(stat.st_nlink==1L && stat.st_mode and 3072==0)
            val mode=stat.st_mode and 511;require(mode==420||mode==493)
            if(recipe.familyId!=LocalUbuntuNimHelperPolicy.FAMILY)require(mode==if(relative in LocalUbuntuVendorPolicy.executablePaths(recipe))493 else 420)
            require(verified["payload/tree/$relative"]==inv.hashes.getValue(relative))
            tree.append("$relative\u0000$mode\u0000${inv.hashes.getValue(relative)}\n")
        }
        inv.links.toSortedMap().forEach { (path,target)->
            val resolved=File(inv.root,path).canonicalFile.relativeTo(inv.root).invariantSeparatorsPath
            tree.append("LINK\u0000$path\u0000$target\u0000$resolved\n")
        }
        require(NimRuntimeClosure.sha256(tree.toString())==record.metadata[LocalManagedPackageMetadata.TREE_SHA256_KEY])
        val values=closureFiles(root,a,active)
        val guestRoot=File(appRoot,"guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/rootfs")
        values.forEach { (path,expected)->
            val file=requireNotNull(NimRuntimeClosure.resolveGuestFile(guestRoot,"/$path",active))
            require(NimRuntimeClosure.measure(file,512L*1024*1024,active).second==expected)
        }

        val nim=record.familyId!=LocalUbuntuNimHelperPolicy.FAMILY
        val fresh=NimRuntimeClosure.requireExecutionClosure(listOf(inv),guestRoot,
            if(nim)setOf("libssl.so.3","libcrypto.so.3","libpcre.so.3") else emptySet(),active)
        require(fresh.files.all { (path,sha)->values[path]==sha })
        if(record.familyId!=LocalUbuntuNimPolicy.FAMILY) require(fresh.files==values)
        if(record.familyId==LocalUbuntuNimToolchainPolicy.FAMILY)
            require(a["ordinaryLoaderEvidence"]==mapping(NimRuntimeClosure.requireDefaultLoaderEvidence(listOf(inv),active)))
        if(record.familyId==LocalUbuntuNimHelperPolicy.FAMILY)
            require(LocalUbuntuNimHelperPolicy.requireConfiguration(File(root,"payload/tree/${LocalUbuntuNimHelperPolicy.CONFIG}"),active)==text(a,"configSha256"))

    }

    suspend fun proveOwnedPackages(appRoot:String,server:ManagedPackageRecord,owned:List<ManagedPackageRecord>,active:()->Unit={}) {
        val started=System.nanoTime();val check={active();check(System.nanoTime()-started<=60_000_000_000L){"Nim full proof deadline exceeded"}}
        val recipe=requireNotNull(LocalUbuntuVendorPolicy.fromReceipt(server));require(recipe.familyId==LocalUbuntuNimPolicy.FAMILY)
        val deps=dependencies(recipe,owned,appRoot)
        val authority=generation(server,appRoot,check).first
        require(authority["dependencyReceipts"]==mapping(deps.associate { it.familyId to receiptSha(it) }))
        (deps+server).forEach { provePackage(appRoot,it,check) }
        val inv=(listOf(server)+deps).map { inventory(requireNotNull(LocalUbuntuVendorPolicy.fromReceipt(it)),File(it.installRoot,"payload/tree"),check) }
        require(authority["ordinaryLoaderEvidence"]==mapping(NimRuntimeClosure.requireDefaultLoaderEvidence(inv,check)))
        val closure=NimRuntimeClosure.requireExecutionClosure(inv,File(appRoot,"guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/rootfs"),setOf("libssl.so.3","libcrypto.so.3","libpcre.so.3"),check)
        val helper=deps.single { it.familyId==LocalUbuntuNimHelperPolicy.FAMILY }
        require(closure.sha256==text(authority,"closureSha256") &&
            LocalUbuntuNimHelperPolicy.requireConfiguration(File(helper.installRoot,"payload/tree/${LocalUbuntuNimHelperPolicy.CONFIG}"),check)==text(authority,"configSha256"))
        check()
    }
    suspend fun prove(appRoot:String,identity:NimManagedLaunchIdentity,records:List<ManagedPackageRecord>,active:()->Unit) {
        require(identity.sourcePolicyRevision==POLICY && identity.familyId==LocalUbuntuNimPolicy.FAMILY && identity.version==LocalUbuntuNimPolicy.VERSION &&
            identity.toolchainFamilyId==LocalUbuntuNimToolchainPolicy.FAMILY && identity.toolchainVersion==LocalUbuntuNimToolchainPolicy.VERSION &&
            identity.helperFamilyId==LocalUbuntuNimHelperPolicy.FAMILY && identity.helperVersion==LocalUbuntuNimHelperPolicy.VERSION &&
            identity.publisherArchiveSha256==LocalUbuntuNimPolicy.SHA256 && identity.toolchainArchiveSha256==LocalUbuntuNimToolchainPolicy.SHA256 &&
            identity.helperArchiveSha256==LocalUbuntuNimHelperPolicy.SHA256 && identity.helperSourceBindingSha256==LocalUbuntuNimHelperPolicy.SOURCE_BINDING_SHA256 &&
            identity.launchExecutable=="${LocalUbuntuNimPolicy.ROOT}/payload/bin/nimlangserver" && identity.serverExecutable==LocalUbuntuNimPolicy.EXECUTABLE &&
            identity.compilerExecutable==LocalUbuntuNimToolchainPolicy.EXECUTABLE && identity.backendExecutable==LocalUbuntuNimToolchainPolicy.BACKEND &&
            identity.nimbleExecutable==LocalUbuntuNimToolchainPolicy.NIMBLE && identity.helperExecutable==LocalUbuntuNimHelperPolicy.EXECUTABLE)
        val server=records.single { it.familyId==identity.familyId&&it.version==identity.version }
        val tool=records.single { it.familyId==identity.toolchainFamilyId&&it.version==identity.toolchainVersion }
        val helper=records.single { it.familyId==identity.helperFamilyId&&it.version==identity.helperVersion }
        val all=listOf(server,tool,helper)
        require(all.map(::receiptSha)==listOf(identity.receiptSha256,identity.toolchainReceiptSha256,identity.helperReceiptSha256))
        val generations=all.map { generation(it,appRoot,active) }
        require(generations.map { it.second }==listOf(identity.sealSha256,identity.toolchainSealSha256,identity.helperSealSha256))
        require(generations.map { text(it.first,"activation") }==listOf(identity.activationIdentity,identity.toolchainActivationIdentity,identity.helperActivationIdentity))
        require(text(generations.first().first,"configSha256")==identity.helperConfigSha256 && text(generations.first().first,"closureSha256")==identity.closureSha256)
        proveOwnedPackages(appRoot,server,listOf(tool,helper),active)
    }
}
