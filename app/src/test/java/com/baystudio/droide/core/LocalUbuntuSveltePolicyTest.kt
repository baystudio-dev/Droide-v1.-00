package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation

/** Authored shapes, not npm output, packed publisher bytes, or native semantics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, shadows = [SvelteHostLinkCount::class])
class LocalUbuntuSveltePolicyTest {
    private val allocatedTrees = mutableListOf<File>()
    @After fun releaseTrees() { allocatedTrees.forEach { PathSecurity.deleteTreeNoFollow(it) } }
    private val policy get() = LocalUbuntuSveltePolicy
    private fun recipe() = WorkstationInstallRecipe(policy.FAMILY_ID,policy.VERSION,policy.PACKAGE,policy.VERSION,
        policy.COMMAND,22,policy.PROVENANCE,policy.INTEGRITY,additionalDependencies=policy.dependencies)
    private fun layout(): Map<String,String> = Json.parseToJsonElement(javaClass.getResource("/svelte-authored-layout.json")!!.readText()).jsonObject.mapValues { it.value.jsonPrimitive.content }
    private fun lock(placements:Map<String,String> = layout()): JsonObject = buildJsonObject {
        put("name","droide-managed-svelte");put("version","1.0.0");put("lockfileVersion",3);put("requires",true)
        put("packages",buildJsonObject {
            put("",buildJsonObject {put("name","droide-managed-svelte");put("version","1.0.0");put("dependencies",policy.rootDependencies)})
            for((path,id)in placements)put(path,buildJsonObject {
                val a=policy.artifacts.getValue(id).jsonObject
                if(policy.slot(path).key == "prettier-plugin-svelte4")put("name",a.getValue("name"))
                put("version",a.getValue("version"));put("resolved",a.getValue("tarball"));put("integrity",a.getValue("integrity"))
                for(k in listOf("engines","dependencies","peerDependencies","peerDependenciesMeta","bin"))a[k]?.takeUnless {it==JsonNull}?.let {put(k,it)}
            })
        })
    }
    private fun reject(block:()->Unit) { try{block();fail("Expected fail-closed denial")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){} }
    private fun tree(): File {
        val parent=File(System.getProperty("fixture.output",System.getProperty("java.io.tmpdir")));parent.mkdirs()
        val root=Files.createTempDirectory(parent.toPath(),"svelte-authored-").toFile().also(allocatedTrees::add);val npm=File(root,"payload/npm");npm.mkdirs()
        File(npm,"package.json").writeText(policy.rootManifest.toString());File(npm,"package-lock.json").writeText(lock().toString())
        for((path,id)in layout()){
            val dir=File(npm,path);dir.mkdirs();val a=policy.artifacts.getValue(id).jsonObject
            val manifest=JsonObject(a.filterKeys {it in setOf("name","version","engines","dependencies","peerDependencies","peerDependenciesMeta","bin","main","exports","type","scripts")}.filterValues {it!=JsonNull})
            File(dir,"package.json").writeText(manifest.toString())
            for((name,target)in a["bin"]?.takeUnless{it==JsonNull}?.jsonObject.orEmpty()){
                val file=File(dir,target.jsonPrimitive.content);file.parentFile!!.mkdirs();file.writeText("// Authored inert entry; never upstream code.\n")
                val modules=File(npm,path.substringBeforeLast("node_modules/")+"node_modules")
                val bin=File(modules,".bin");bin.mkdirs();val link=File(bin,name)
                Files.createSymbolicLink(link.toPath(),bin.toPath().relativize(file.toPath()))
            }
        }
        return root
    }
    @Test fun exactRecipeAndFourNumericExtrasOnly(){
        recipe().validate();assertTrue(policy.supports(recipe()));assertEquals(4,recipe().additionalDependencies.size)
        assertEquals(JsonPrimitive("npm:prettier-plugin-svelte@4.1.1"),policy.manifest(recipe()).getValue("dependencies").jsonObject["prettier-plugin-svelte4"])
        val changes=listOf(recipe().copy(familyId="lsp.fake"),recipe().copy(version="latest"),recipe().copy(packageVersion="0.18.6"),recipe().copy(command="node"),recipe().copy(minimumNodeMajor=23),recipe().copy(registryIntegrity="sha512-"+"A".repeat(86)+"=="),recipe().copy(provenanceUrl="https://example.org"),recipe().copy(allowLifecycleScripts=true),recipe().copy(lifecycleScriptReview="anything"),recipe().copy(additionalDependencies=policy.dependencies+("typescript" to "7.0.2")))
        changes.forEach {assertFalse(policy.supports(it))}
        reject { recipe().copy(additionalDependencies=policy.dependencies+("other" to "1.0.0")).validate() }
        reject { recipe().copy(additionalDependencies=policy.dependencies+("prettier-plugin-svelte4" to "npm:prettier-plugin-svelte@4.1.1")).validate() }
    }
    @Test fun completeGraphAndTwoDifferentHoistShapes(){
        assertEquals(66,policy.artifacts.size);assertEquals(64,policy.requiredIdentities.size)
        val original=layout();assertEquals(66,original.size);assertEquals(107,policy.validateGraph(original).getValue("resolutions").jsonArray.size)
        val nested=original.toMutableMap();val id=nested.remove("node_modules/globrex")!!;nested["node_modules/svelte-language-server/node_modules/globrex"]=id
        policy.validateGraph(nested);policy.validateLock(lock(nested).toString(),recipe())
    }
    @Test fun everyMissingIdentityAndArtifactMutationRejects(){
        val original=layout()
        for(id in policy.requiredIdentities){reject {policy.validateGraph(original.filterValues {it!=id})}}
        val value=lock();val packages=value.getValue("packages").jsonObject
        for((path,raw)in packages.filterKeys {it.isNotEmpty()})for(key in listOf("version","resolved","integrity")){
            val changed=JsonObject(raw.jsonObject+(key to JsonPrimitive("wrong")))
            reject {policy.validateLock(JsonObject(value+("packages" to JsonObject(packages+(path to changed)))).toString(),recipe())}
        }
    }
    @Test fun peersAliasesOptionalShadowsAndOrphansReject(){
        val original=layout();fun change(path:String,id:String)=reject {policy.validateGraph(original+(path to id))}
        change("node_modules/svelte","svelte@4.2.20")
        change("node_modules/prettier-plugin-svelte4","prettier-plugin-svelte@3.5.2")
        change("node_modules/svelte-language-server/node_modules/svelte","svelte@5.57.2")
        change("node_modules/picomatch","picomatch@4.0.7")
        change("node_modules/@typescript-eslint/types","@typescript-eslint/types@8.71.2")
        change("node_modules/defu/node_modules/globrex","globrex@0.1.2")
        change("node_modules/svelte-language-server/node_modules/prettier-plugin-svelte4/node_modules/svelte","svelte@5.57.2")
        reject {policy.validateGraph(original+("node_modules/svelte-language-server/node_modules/x/../../escape" to "globrex@0.1.2"))}
    }
    @Test fun strictJsonAndRootMutationReject(){
        listOf("{\"a\":1,\"\\u0061\":2}","{}garbage","[]","{\"a\":NaN}","{\"a\":01}","{\"a\":\"bad\ntext\"}").forEach {reject {policy.strictObject(it)}}
        reject {policy.strictObject("{\"a\":"+"[".repeat(65)+"0"+"]".repeat(65)+"}")}
        reject {policy.validateLock(JsonObject(lock()+("lockfileVersion" to JsonPrimitive(2))).toString(),recipe())}
        reject {policy.validateLock(JsonObject(lock()+("unexpected" to JsonPrimitive(true))).toString(),recipe())}
    }
    @Test fun exactPhysicalTreeAndProof(){val root=tree();val proof=policy.validateInstalledLayout(root);assertEquals(policy.CONTRACT_SHA256,proof.getValue("contractSha256").jsonPrimitive.content);assertEquals(layout().size,proof.getValue("placements").jsonObject.size)}
    @Test fun everyManifestRelevantFieldIsBound(){
        val a=policy.artifacts.getValue("svelte-language-server@0.18.5").jsonObject
        val actual=JsonObject(a.filterKeys {it in setOf("name","version","engines","dependencies","peerDependencies","peerDependenciesMeta","bin","main","exports","type","scripts")}.filterValues {it!=JsonNull})
        policy.validateManifest("svelte-language-server@0.18.5",actual)
        for(k in actual.keys)reject {policy.validateManifest("svelte-language-server@0.18.5",JsonObject(actual+(k to JsonPrimitive("changed"))))}
        for(k in listOf("optionalDependencies","os","cpu","libc","gypfile","bundledDependencies"))reject {policy.validateManifest("svelte-language-server@0.18.5",JsonObject(actual+(k to JsonObject(emptyMap()))))}
    }
    @Test fun undeclaredPackageSymlinkHardlinkNativeAndNestedModulesReject(){
        run {val root=tree();File(root,"payload/npm/node_modules/extra").mkdir();reject {policy.validateInstalledLayout(root)}}
        run {val root=tree();val dir=File(root,"payload/npm/node_modules/globrex");dir.renameTo(File(root,"moved"));Files.createSymbolicLink(dir.toPath(),File(root,"moved").toPath());reject {policy.validateInstalledLayout(root)}}
        run {val root=tree();val dir=File(root,"payload/npm/node_modules/globrex");Files.createLink(File(dir,"hardlink").toPath(),File(dir,"package.json").toPath());reject {policy.validateInstalledLayout(root)}}
        run {val root=tree();File(root,"payload/npm/node_modules/globrex/native.bin").writeBytes(byteArrayOf(127,69,76,70));reject {policy.validateInstalledLayout(root)}}
        run {val root=tree();File(root,"payload/npm/node_modules/globrex/dist/node_modules").mkdirs();reject {policy.validateInstalledLayout(root)}}
    }
    @Test fun hiddenLockAndBinTargetsAreChecked(){
        val root=tree();val npm=File(root,"payload/npm");val l=lock()
        val hidden=JsonObject(l+("packages" to JsonObject(l.getValue("packages").jsonObject.filterKeys {it.isNotEmpty()})))
        File(npm,"node_modules/.package-lock.json").writeText(hidden.toString());policy.validateInstalledLayout(root)
        File(npm,"node_modules/.package-lock.json").writeText(JsonObject(hidden+("lockfileVersion" to JsonPrimitive(2))).toString());reject {policy.validateInstalledLayout(root)}
        File(npm,"node_modules/.package-lock.json").delete()
        val bin=File(npm,"node_modules/.bin/svelteserver");bin.delete();Files.createSymbolicLink(bin.toPath(),java.nio.file.Path.of("../typescript/bin/tsserver"));reject {policy.validateInstalledLayout(root)}
    }
    @Test fun privateHandoffPrecedesNodeAndPinsPublicChild(){
        val handoff=LocalUbuntuSvelteLaunchPolicy.healthHandoff("/owned/node","/owned/package")
        assertTrue(handoff.indexOf("mktemp")<handoff.indexOf("/owned/node"));assertTrue(handoff.contains("exec /usr/bin/env -i"));assertTrue(handoff.contains("private/workspace"))
        assertTrue(LocalUbuntuSvelteLaunchPolicy.script.contains("[c.bin,'--stdio']"));assertFalse(LocalUbuntuSvelteLaunchPolicy.script.contains("startServer()"));assertFalse(LocalUbuntuSvelteLaunchPolicy.script.contains("console.debug="))
        assertTrue(LocalUbuntuSvelteLspHealth.script.contains("[context.bin, '--stdio']"));assertFalse(LocalUbuntuSvelteLspHealth.script.contains("textDocument/didOpen"))
    }
    @Test fun allSixFailureFlagsAndOuterCaptureAreFailClosed(){
        val good=buildJsonObject {put("schema",1);put("revision",LocalUbuntuSvelteLspHealth.REVISION);for(k in listOf("timedOut","outputIncomplete","cleanupIncomplete","cancelled","protocolFailed","identityChanged"))put(k,false)}
        assertTrue(LocalUbuntuSvelteLspHealth.succeeded(ExecResult(0,good.toString(),timedOut=false)))
        for(k in good.keys-setOf("schema","revision"))assertFalse(LocalUbuntuSvelteLspHealth.succeeded(ExecResult(0,JsonObject(good+(k to JsonPrimitive(true))).toString(),timedOut=false)))
        assertFalse(LocalUbuntuSvelteLspHealth.succeeded(ExecResult(0,good.toString(),timedOut=true)))
        assertFalse(LocalUbuntuSvelteLspHealth.succeeded(ExecResult(0,good.toString(),timedOut=false,outputIncomplete=true)))
        assertFalse(LocalUbuntuSvelteLspHealth.succeeded(ExecResult(1,good.toString(),timedOut=false)))
        assertFalse(LocalUbuntuSvelteLspHealth.succeeded(ExecResult(0,"{}",timedOut=false)))
    }
    @Test fun runtimeAndRecipeReceiptRequireExactOwnedNode(){
        LocalUbuntuSvelteLaunchPolicy.requireRuntime("/owned/node","22.20.0","runtime.node@22.20.0")
        reject {LocalUbuntuSvelteLaunchPolicy.requireRuntime("/owned/node","22.20.0",null)}
        reject {LocalUbuntuSvelteLaunchPolicy.requireRuntime("/owned/node","22.20.0","runtime.node@22.19.0")}
        reject {LocalUbuntuSvelteLaunchPolicy.requireRuntime("node","22.20.0","runtime.node@22.20.0")}
        reject {LocalUbuntuSvelteLaunchPolicy.requireRuntime("/owned/node","20.20.0","runtime.node@20.20.0")}
    }
}

/**
 * Robolectric 4.14.1 ShadowLinux hard-codes st_nlink=0 and lstat follows stat.
 * Svelte's host fixture only reads the link count; observe that field on real host
 * files without following links. This is not an Android/PRoot stat implementation.
 */
@Implements(android.system.Os::class)
class SvelteHostLinkCount {
    companion object {
        @JvmStatic @Implementation
        fun lstat(path: String): android.system.StructStat {
            val links = (Files.getAttribute(File(path).toPath(), "unix:nlink", NOFOLLOW_LINKS) as Number).toLong()
            return android.system.StructStat(0, 0, 0, links, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        }
    }
}
