package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LocalUbuntuTomlPolicyTest {
    private fun denied(block:()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
    @Test fun onlyTwoExactFamiliesAndExactRecipe(){assertTrue(LocalUbuntuTomlPolicy.supports(LocalUbuntuTomlPolicy.recipe()));assertFalse(LocalUbuntuTomlPolicy.supports(LocalUbuntuTomlPolicy.recipe().copy(allowLifecycleScripts=true)));assertFalse(LocalUbuntuTomlPolicy.isFamily("runtime.node"));assertFalse(LocalUbuntuTomlPolicy.isFamily("runtime.vue-helper"));assertFalse(LocalUbuntuTomlPolicy.isFamily("lsp.taplo"));assertEquals("22.23.3",LocalUbuntuTomlPolicy.version("runtime.taplo-node"));denied{LocalUbuntuTomlPolicy.version("runtime.node")}}
    @Test fun exactMetadataAllowsPassiveFieldsButRejectsIdentityChanges(){val expected=LocalUbuntuTomlPolicy.artifacts.getValue("@taplo/lsp@0.8.0");LocalUbuntuTomlPolicy.requirePackage("@taplo/lsp@0.8.0",JsonObject(expected+mapOf("description" to JsonPrimitive("authored passive metadata"))));denied{LocalUbuntuTomlPolicy.requirePackage("@taplo/lsp@0.8.0",JsonObject(expected+("bin" to JsonPrimitive("cli.js"))))};denied{LocalUbuntuTomlPolicy.requirePackage("@taplo/lsp@0.8.0",JsonObject(expected+("version" to JsonPrimitive("0.9.0"))))}}
    @Test fun exactNodeChecksumAndNoMovingVersion(){val def=LocalUbuntuVendorCatalog.find(LocalUbuntuTomlNodePolicy.FAMILY)!!;val checksum="${LocalUbuntuTomlNodePolicy.SHA256}  ${LocalUbuntuTomlNodePolicy.ASSET}";val recipe=LocalUbuntuTomlNodePolicy.releaseRecipe(def,"22.23.3",checksum);assertEquals("runtime.taplo-node",recipe.familyId);denied{LocalUbuntuTomlNodePolicy.releaseRecipe(def,"latest",checksum)};denied{LocalUbuntuTomlNodePolicy.releaseRecipe(def,"22.23.3",checksum+"\n"+checksum)}}
    @Test fun journalSuffixDefaultsAndExactHoldAreSeparate(){val old=LocalPackageInstallJournal.paths("/tmp/authored-toml-policy","runtime.node","22.23.3",LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE);val own=LocalPackageInstallJournal.paths("/tmp/authored-toml-policy","runtime.taplo-node","22.23.3",LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE);assertNull(old.tomlHealthHold);assertNotNull(own.tomlHealthHold);assertTrue(old.stage.name.contains("local-ubuntu-vendor"));assertTrue(own.stage.name.contains("local-ubuntu-vendor"));assertNotEquals(old.stage,own.stage)}

    @Test fun unknownHealthRetainsExactDurablyBoundOwnerAndBlocksRecovery(){
        val root=java.nio.file.Files.createTempDirectory("authored-toml-hold-").toFile()
        val paths=LocalPackageInstallJournal.paths(root.absolutePath,LocalUbuntuTomlPolicy.FAMILY,"0.8.0",LocalManagedPackageMetadata.KIND_UBUNTU_NPM)
        val hold=LocalPackageInstallJournal.beginTomlHealth(paths)
        val token="a".repeat(32);val owner=java.io.File(root,"runtime-tmp/droide-toml-health-$token");check(owner.mkdirs());android.system.Os.chmod(owner.absolutePath,448)
        val attrs=java.nio.file.Files.readAttributes(owner.toPath(),"unix:dev,ino");val identity="${attrs["dev"]}:${attrs["ino"]}"
        LocalPackageInstallJournal.bindTomlHealthOwner(hold,owner.absolutePath,token,identity)
        val nonce=hold.file.readText();val bound=hold.ownerFile.readText();assertTrue(bound.contains(owner.absolutePath));assertTrue(bound.contains(identity))
        LocalPackageInstallJournal.finishTomlHealth(hold,false)
        assertEquals(nonce,hold.file.readText());assertEquals(bound,hold.ownerFile.readText());assertTrue(owner.isDirectory)
        denied{LocalPackageInstallJournal.requireNoTomlHealthHold(paths)}
        denied{LocalPackageInstallJournal.recover(paths,null)}
        denied{LocalPackageInstallJournal.finishTomlHealth(hold,true)}
        assertTrue(hold.ownerFile.isFile);assertTrue(hold.file.isFile);assertTrue(owner.isDirectory)
    }
    @Test fun healthOwnerCannotBindToReplacedNonceOrWrongDirectory(){
        val root=java.nio.file.Files.createTempDirectory("authored-toml-replaced-hold-").toFile()
        val paths=LocalPackageInstallJournal.paths(root.absolutePath,LocalUbuntuTomlPolicy.FAMILY,"0.8.0",LocalManagedPackageMetadata.KIND_UBUNTU_NPM)
        val hold=LocalPackageInstallJournal.beginTomlHealth(paths)
        val token="b".repeat(32);val owner=java.io.File(root,"runtime-tmp/droide-toml-health-$token");check(owner.mkdirs());android.system.Os.chmod(owner.absolutePath,448)
        val attrs=java.nio.file.Files.readAttributes(owner.toPath(),"unix:dev,ino");val identity="${attrs["dev"]}:${attrs["ino"]}"
        denied{LocalPackageInstallJournal.bindTomlHealthOwner(hold,owner.absolutePath,token,"0:0")}
        hold.file.writeText("x".repeat(36))
        denied{LocalPackageInstallJournal.bindTomlHealthOwner(hold,owner.absolutePath,token,identity)}
        assertFalse(hold.ownerFile.exists());assertTrue(hold.file.isFile);assertTrue(owner.isDirectory)
    }
}
