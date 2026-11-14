package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Complete authored plaintext physical-layout fixtures. These exercise package-file and
 * journal authorities, not authenticity of unopened original archives or native health. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, shadows = [VueHostFileStat::class])
class VuePackageAuthorityFixtureTest {
    private fun fixture(): Pair<File,JsonObject> {
        val root=Files.createTempDirectory("vue-authored-layout-").toFile()
        val contents=LocalUbuntuVuePolicy.artifacts.mapValues { (_,a) -> linkedMapOf("package.json" to a.getValue("metadata").toString()) }.toMutableMap()
        listOf(LocalUbuntuVuePolicy.VUE_ENTRY,LocalUbuntuVuePolicy.TSLSP_ENTRY,LocalUbuntuVuePolicy.TS_ENTRY,LocalUbuntuVuePolicy.TS_LIBRARY,LocalUbuntuVuePolicy.PLUGIN_ENTRY).forEach { path ->
            val slot=LocalUbuntuVuePolicy.placements.keys.filter { path.startsWith("payload/npm/$it/") }.maxBy{it.length}
            contents.getValue(LocalUbuntuVuePolicy.placements.getValue(slot))[path.removePrefix("payload/npm/$slot/")]="// authored inert fixture\n"
        }
        val npm=File(root,LocalUbuntuVuePolicy.NPM_ROOT).apply{check(mkdirs())};File(npm,"package.json").writeText(LocalUbuntuVuePolicy.manifest.toString())
        LocalUbuntuVuePolicy.placements.forEach{(slot,id)->contents.getValue(id).forEach{(path,text)->File(npm,"$slot/$path").apply{parentFile!!.mkdirs();writeText(text)}}}
        val proofs=contents.mapValues{(id,files)->buildJsonObject{
            put("sha512",LocalUbuntuVuePolicy.artifacts.getValue(id).getValue("sha512"));put("directories",JsonArray(emptyList()))
            put("files",buildJsonObject{files.forEach{(path,text)->put(path,buildJsonObject{put("size",text.toByteArray().size);put("sha256",LocalUbuntuVuePolicy.sha256(text.toByteArray()))})}})
        }}
        val proof=buildJsonObject{put("schema",1);put("policy",LocalUbuntuVuePolicy.DATA_SHA256);put("archives",JsonObject(proofs))}
        File(root,LocalUbuntuVuePolicy.PROOF_FILE).writeText(proof.toString())
        return root to proof
    }
    private fun withFixture(block: (File, JsonObject) -> Unit) {
        val (root, proof) = fixture()
        try {
            assertEquals(
                "Pristine layout must be admitted before testing mutations",
                proof,
                LocalUbuntuVuePolicy.validateInstalledLayout(root),
            )
            block(root, proof)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun completeNinetyPlacementFixtureAccepted(){withFixture{root,proof->assertEquals(proof,LocalUbuntuVuePolicy.validateInstalledLayout(root))}}
    @Test fun missingPackageOrOriginalFileRejected(){withFixture{root,proof->File(root,LocalUbuntuVuePolicy.VUE_ENTRY).delete();assertThrows(RuntimeException::class.java){LocalUbuntuVuePolicy.validateInstalledLayout(root,proof)}}}
    @Test fun extraPackageAndUnknownDirectoryRejected(){withFixture{root,proof->File(root,"payload/npm/node_modules/extra/package.json").apply{parentFile!!.mkdirs();writeText("{}")};assertThrows(RuntimeException::class.java){LocalUbuntuVuePolicy.validateInstalledLayout(root,proof)}}}
    @Test fun sameLengthSameTimestampMutationRejected(){withFixture{root,proof->val f=File(root,LocalUbuntuVuePolicy.VUE_ENTRY);val stamp=f.lastModified();f.writeText("X"+f.readText().drop(1));f.setLastModified(stamp);assertThrows(RuntimeException::class.java){LocalUbuntuVuePolicy.validateInstalledLayout(root,proof)}}}
    @Test fun symlinkNeverCountsAsOriginalData(){withFixture{root,proof->val f=File(root,LocalUbuntuVuePolicy.VUE_ENTRY);f.delete();Files.createSymbolicLink(f.toPath(),java.nio.file.Path.of("vue-language-server-other.js"));try{assertThrows(RuntimeException::class.java){LocalUbuntuVuePolicy.validateInstalledLayout(root,proof)}}finally{f.delete()}}}
    @Test fun archiveIdentityProofCannotDrift(){withFixture{root,proof->val archives=proof.getValue("archives").jsonObject.toMutableMap();val key=archives.keys.first();archives[key]=JsonObject(archives.getValue(key).jsonObject+("sha512" to JsonPrimitive("0".repeat(128))));val bad=JsonObject(proof+("archives" to JsonObject(archives)));assertThrows(RuntimeException::class.java){LocalUbuntuVuePolicy.validateInstalledLayout(root,bad)}}}
    @Test fun omittedOriginalEmptyDirectoryRejected(){withFixture{root,proof->
        val archives=proof.getValue("archives").jsonObject.toMutableMap();val id=archives.keys.first()
        archives[id]=JsonObject(archives.getValue(id).jsonObject+("directories" to JsonArray(listOf(JsonPrimitive("empty-original")))))
        val updated=JsonObject(proof+("archives" to JsonObject(archives)))
        assertThrows(RuntimeException::class.java){LocalUbuntuVuePolicy.validateInstalledLayout(root,updated)}
        LocalUbuntuVuePolicy.placements.filterValues{it==id}.keys.forEach{check(File(root,"payload/npm/$it/empty-original").mkdirs())}
        assertEquals(updated,LocalUbuntuVuePolicy.validateInstalledLayout(root,updated))
    }}
    @Test fun reviewedRecipeFieldsAreExact(){
        LocalUbuntuVuePolicy.requireReview(LocalUbuntuVuePolicy.review())
        assertThrows(RuntimeException::class.java){LocalUbuntuVuePolicy.requireReview(JsonObject(LocalUbuntuVuePolicy.review()+("allowLifecycleScripts" to JsonPrimitive(true))))}
    }
    @Test fun unknownHealthPreservesAllTransactionTrees(){
        val root=Files.createTempDirectory("vue-authored-journal-").toFile()
        try{
            val p=LocalPackageInstallJournal.paths(root.absolutePath,LocalUbuntuVuePolicy.FAMILY,LocalUbuntuVuePolicy.VERSION,LocalManagedPackageMetadata.KIND_UBUNTU_NPM)
            listOf(p.stage,p.final,p.previous).forEach{it.mkdirs();File(it,"retain").writeText("owned")}
            val hold=LocalPackageInstallJournal.beginVueHealth(p)
            LocalPackageInstallJournal.finishVueHealth(hold,false)
            assertThrows(IllegalStateException::class.java){LocalPackageInstallJournal.recover(p,null)}
            assertTrue(listOf(p.stage,p.final,p.previous).all{File(it,"retain").readText()=="owned"})
            LocalPackageInstallJournal.finishVueHealth(hold,true)
            LocalPackageInstallJournal.requireNoVueHealthHold(p)
        }finally{root.deleteRecursively()}
    }
    @Test fun replacedHealthTokenCannotClearHold(){
        val root=Files.createTempDirectory("vue-authored-journal-token-").toFile()
        try{
            val p=LocalPackageInstallJournal.paths(root.absolutePath,LocalUbuntuVueHelperPolicy.FAMILY,LocalUbuntuVueHelperPolicy.VERSION,LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE)
            val hold=LocalPackageInstallJournal.beginVueHealth(p);requireNotNull(p.vueHealthHold).writeText("0".repeat(36))
            assertThrows(IllegalStateException::class.java){LocalPackageInstallJournal.finishVueHealth(hold,true)}
            assertTrue(p.vueHealthHold!!.exists())
        }finally{root.deleteRecursively()}
    }
}
