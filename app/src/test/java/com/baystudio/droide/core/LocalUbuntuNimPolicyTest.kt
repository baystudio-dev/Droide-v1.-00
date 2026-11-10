package com.baystudio.droide.core
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Publisher metadata and authored data fixtures only. No original archive/program is loaded or executed. */
class LocalUbuntuNimPolicyTest {
    private fun reject(block:()->Unit)=assertTrue(runCatching(block).isFailure)
    @Test fun exactThreeFamiliesOneServerNoTerminalCommand() {
        val defs=LocalUbuntuVendorCatalog.all.filter { LocalUbuntuNimPolicy.isFamily(it.familyId) }
        assertEquals(3,defs.size);assertEquals(1,defs.count { it.lsp });assertTrue(defs.none { it.supportsPublisherVersionSelection })
        assertEquals(setOf("nimlangserver","droide-nim-helper","droide-nim-lsp-toolchain"),defs.map { it.command }.toSet())
        assertTrue(defs.all { it.auxiliaryCommands.isEmpty() && "nodejs" !in it.packages })
        assertEquals(setOf("nim-2.2.12/bin/nim","nim-2.2.12/bin/nimsuggest","nim-2.2.12/bin/nimble"),LocalUbuntuNimToolchainPolicy.executablePaths)
    }
    @Test fun exactHelperChecksumAndUnknownVersionRefusal() = runBlocking {
        val def=LocalUbuntuVendorCatalog.find(LocalUbuntuNimHelperPolicy.FAMILY)!!
        val sum="${LocalUbuntuNimHelperPolicy.SHA256}  ${LocalUbuntuNimHelperPolicy.ASSET}\n"
        val recipe=LocalUbuntuNimHelperPolicy.releaseRecipe(def,"22.23.3",sum);recipe.validate()
        for(version in listOf(null,"latest","22.23.2","24.0.0")) reject { LocalUbuntuNimHelperPolicy.releaseRecipe(def,version,sum) }
        for(text in listOf(sum+sum,sum.replace("  "," "),sum.replace(LocalUbuntuNimHelperPolicy.SHA256,"0".repeat(64)))) reject { LocalUbuntuNimHelperPolicy.releaseRecipe(def,"22.23.3",text) }
        assertEquals(recipe,LocalUbuntuVendorResolver { sum }.resolve(def,"22.23.3"))
        assertTrue(runCatching { LocalUbuntuVendorResolver { error("Must not fetch fallback") }.resolve(def,"latest") }.isFailure)
        reject { recipe.copy(assetSize=null).validate() };reject { recipe.copy(assetUrl="https://example.com/node.tar.gz").validate() }
    }
    @Test fun strictDataConfigNoEvalDuplicatesOrInjection() {
        val valid="{'variables': {'target_arch':'arm64','node_shared':'false','node_shared_libuv':'false','node_builtin_modules_path':'','node_module_version':127},'target_defaults':{},}"
        val dir=Files.createTempDirectory("nim-config").toFile()
        try { val f=File(dir,"config.gypi");f.writeText(valid);assertEquals(64,LocalUbuntuNimHelperPolicy.requireConfiguration(f).length)
            for(bad in listOf(valid.replace("'arm64'","'x64'"),valid.replace("'node_shared':'false'","'node_shared':'true'"),valid.replace("'node_shared':'false'","'node_shared':'false','node_shared':'false'"),valid+"; import os",valid.replace("127","__import__('os')"),valid.replace("'node_builtin_modules_path':''","'node_builtin_modules_path':'/tmp'"))) {
                f.writeText(bad);reject { LocalUbuntuNimHelperPolicy.requireConfiguration(f) }
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun setIdArchiveModesRejectedBeforeNormalization() {
        fun tar(mode:String):ByteArray { val h=ByteArray(1024);"member".toByteArray().copyInto(h);mode.padStart(7,'0').toByteArray().copyInto(h,100);"00000000000".toByteArray().copyInto(h,124);return h }
        LocalUbuntuVendorArchive.requireNimTarModes(tar("644").inputStream(),1,1)
        for(mode in listOf("4644","2644","6755"))reject { LocalUbuntuVendorArchive.requireNimTarModes(tar(mode).inputStream(),1,1) }
        reject { LocalUbuntuVendorArchive.requireNimTarModes(ByteArray(12).inputStream(),1,1) }
    }
}
