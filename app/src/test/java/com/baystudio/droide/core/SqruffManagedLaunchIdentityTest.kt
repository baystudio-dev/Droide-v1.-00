package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Inert ELF and authored receipts. No shell, installer health, native sqruff or project execution. */
class SqruffManagedLaunchIdentityTest {
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    private data class Fixture(val app:File,val root:File,val record:ManagedPackageRecord,val guest:String) {
        val wrapper get()="$guest/payload/bin/sqruff"
        suspend fun inspect(record:ManagedPackageRecord=this.record,records:List<ManagedPackageRecord> = listOf(record),
            selections:Map<String,String> = emptyMap(),discovered:String=wrapper)=LocalUbuntuSqruffLaunchAuthority.inspect(app.absolutePath,record,records,selections,discovered)
    }
    private suspend fun fixture(): Fixture {
        val app=Files.createTempDirectory("sqruff-proof-").toFile()
        val p=LocalUbuntuSqruffPolicy
        val r=LocalUbuntuVendorRecipe(LocalUbuntuVendorCatalog.find(p.FAMILY)!!,p.VERSION,"v${p.VERSION}",p.ASSET,p.ASSET_URL,p.SHA256,p.ARTIFACT_BYTES,p.PROVENANCE_URL)
        val root=File(app,"packages/${p.FAMILY}/${p.VERSION}/arm64-v8a").apply{mkdirs()}
        val guest="/opt/droide/packages/${p.FAMILY}/${p.VERSION}/arm64-v8a"
        val bytes=ByteArray(512);val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        byteArrayOf(127,69,76,70,2,1,1).copyInto(bytes);b.putShort(16,2);b.putShort(18,183);b.putInt(20,1)
        b.putLong(24,0x1080);b.putLong(32,64);b.putShort(52,64);b.putShort(54,56);b.putShort(56,1)
        b.putInt(64,1);b.putInt(68,5);b.putLong(80,0x1000);b.putLong(96,512);b.putLong(104,512)
        File(root,"payload/tree/sqruff").apply{parentFile.mkdirs();writeBytes(bytes);Files.setAttribute(toPath(),"unix:mode",493)}
        File(root,"payload/bin/sqruff").apply{parentFile.mkdirs();writeText(LocalUbuntuVendorPolicy.guestWrapper(r,"$guest/payload/tree/sqruff",guest));Files.setAttribute(toPath(),"unix:mode",493)}
        val launcher="${app.absolutePath}/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch"
        File(root,"payload/host-bin/sqruff").apply{parentFile.mkdirs();writeText(LocalUbuntuVendorPolicy.hostWrapper(launcher,"$guest/payload/bin/sqruff"));Files.setAttribute(toPath(),"unix:mode",493)}
        File(root,LocalUbuntuSqruffLspHealth.FILE_NAME).writeText(LocalUbuntuSqruffLspHealth.script)
        val activation=LocalPackageInstallJournal.prepare(root)
        val treeSha=sha("sqruff\u0000493\u0000${sha(bytes)}\n".toByteArray())
        File(root,"TREE_LINKS.tsv").writeText("");File(root,"TREE_COMMANDS.tsv").writeText("sqruff\tsqruff\n")
        File(root,"RECIPE_LOCK.json").writeText(buildJsonObject {
            put("schema",1);put("provider","LOCAL_UBUNTU_VENDOR");put("familyId",r.familyId);put("version",r.version)
            put("artifactFormat","TAR_GZ");put("command","sqruff");put("treeEntryPoint","sqruff");put("releaseTag",r.releaseTag)
            put("assetName",r.assetName);put("assetUrl",r.assetUrl);put("assetSha256",r.assetSha256);put("assetSize",r.assetSize!!);put("provenanceUrl",r.provenanceUrl)
            put("treeFiles",1);put("treeSymlinks",0);put("treeBytes",512);put("treeSha256",treeSha);put("treeLinksSha256",sha(byteArrayOf()))
            put("commands",buildJsonObject{put("sqruff","sqruff")});put("runtimeDependencies",JsonArray(emptyList()))
        }.toString())
        val seal=LocalPackageIntegrity.seal(root,16,70L*1024*1024)
        val record=ManagedPackageRecord(r.familyId,r.version,ExecutionScope.LOCAL_LINUX_ARM64.name,root.absolutePath,123,
            pathEntries=listOf("$root/payload/host-bin"),commands=mapOf("sqruff" to "$root/payload/host-bin/sqruff"),
            healthChecks=listOf(ManagedPackageHealthCheck("host-bin/sqruff",listOf("--droide-health"))),artifactSha256=r.assetSha256,abi="arm64-v8a",
            metadata=mapOf(LocalManagedPackageMetadata.ACTIVATION_ID_KEY to activation,
                LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE,
                LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,
                LocalManagedPackageMetadata.LIBC_KEY to DeclarativeLibcCompatibility.GLIBC.name,
                LocalManagedPackageMetadata.COMMAND_KEY to "sqruff",LocalManagedPackageMetadata.TREE_SHA256_KEY to treeSha,
                LocalManagedPackageMetadata.TREE_LINKS_SHA256_KEY to sha(byteArrayOf()),LocalManagedPackageMetadata.TREE_COMMANDS_SHA256_KEY to sha("sqruff\tsqruff\n".toByteArray()),
                LocalManagedPackageMetadata.SEAL_SHA256_KEY to seal,LocalUbuntuVendorPolicy.POLICY_KEY to LocalUbuntuVendorPolicy.POLICY_VERSION))
        File(app,"managed/ubuntu-bin/sqruff").apply{parentFile.mkdirs();writeText("#!/bin/sh\nexec '$guest/payload/bin/sqruff' \"\$@\"\n");Files.setAttribute(toPath(),"unix:mode",493)}
        return Fixture(app,root,record,guest)
    }
    private fun runFixture(block:suspend (Fixture)->Unit)=runBlocking<Unit>{val f=fixture();try{block(f)}finally{assertTrue(PathSecurity.deleteTreeNoFollow(f.app))}}
    @Test fun completeProofBindsActualDiscoveryAndVersionSpecificLaunch()=runFixture { f ->
        val proof=f.inspect();assertNotNull(proof);assertEquals(f.wrapper,proof!!.launchExecutable);assertEquals(f.wrapper,proof.discoveredExecutable)
        assertEquals(LocalUbuntuSqruffPolicy.SHA256,proof.publisherArchiveSha256);assertNotEquals(proof.publisherArchiveSha256,proof.installedExecutableSha256)
        val global=f.inspect(discovered="/opt/droide/bin/sqruff");assertNotNull(global);assertEquals(f.wrapper,global!!.launchExecutable);assertNotEquals(proof,global)
        assertEquals(proof,f.inspect());assertNotEquals(proof,f.inspect(selections=mapOf("lsp.sqruff" to "0.41.0")))
        for(path in listOf("sqruff","/usr/bin/sqruff","/tmp/sqruff",f.wrapper.replace("0.41.0","0.42.0"),"${f.guest}/payload/tree/sqruff"))assertNull(f.inspect(discovered=path))
    }
    @Test fun replacementAtEverySealedBoundaryFails()=runFixture { f ->
        val names=listOf("DROIDE_ACTIVATION_ID","RECIPE_LOCK.json","TREE_LINKS.tsv","TREE_COMMANDS.tsv",LocalUbuntuSqruffLspHealth.FILE_NAME,
            "payload/bin/sqruff","payload/host-bin/sqruff","payload/tree/sqruff","SHA256SUMS")
        for(name in names){val file=File(f.root,name);val original=file.readBytes();file.appendText("x");assertNull("changed $name",f.inspect());file.writeBytes(original)}
        val extra=File(f.root,"unsealed").apply{writeText("x")};assertNull(f.inspect());extra.delete()
        val directory=File(f.root,"payload/tree/extra").apply{mkdir()};assertNull(f.inspect());directory.delete()
        val file=File(f.root,"payload/tree/sqruff");val bytes=file.readBytes();file.delete();Files.createSymbolicLink(file.toPath(),File(f.app,"outside").apply{writeBytes(bytes)}.toPath());assertNull(f.inspect())
    }
    @Test fun receiptsPolicyDuplicatesAndSelectionNeverAttestByName()=runFixture { f ->
        assertNull(f.inspect(records=listOf(f.record,f.record)));assertNull(f.inspect(selections=mapOf("lsp.sqruff" to "0.42.0")))
        assertNull(f.inspect(record=f.record.copy(active=false)));assertNull(f.inspect(record=f.record.copy(artifactSha256="a".repeat(64))))
        for(key in listOf(LocalUbuntuVendorPolicy.POLICY_KEY,LocalManagedPackageMetadata.PROFILE_KEY,LocalManagedPackageMetadata.KIND_KEY,
            LocalManagedPackageMetadata.SEAL_SHA256_KEY,LocalManagedPackageMetadata.TREE_SHA256_KEY,LocalManagedPackageMetadata.TREE_LINKS_SHA256_KEY,
            LocalManagedPackageMetadata.TREE_COMMANDS_SHA256_KEY,LocalManagedPackageMetadata.ACTIVATION_ID_KEY))
            assertNull(key,f.inspect(record=f.record.copy(metadata=f.record.metadata+(key to "changed"))))
        val projection=File(f.app,"managed/ubuntu-bin/sqruff");projection.appendText("# different\n");assertNull(f.inspect(discovered="/opt/droide/bin/sqruff"))
        assertNotNull(f.inspect()) // Unselected global projection does not replace a verified version-specific discovery.
    }
    @Test fun hardLinkedInstalledMemberAndManifestAreUnavailable()=runFixture { f ->
        for(relative in listOf("payload/tree/sqruff","SHA256SUMS")) {
            val link=File(f.app,"other-name");Files.createLink(link.toPath(),File(f.root,relative).toPath())
            assertNull("hardlinked $relative",f.inspect());link.delete();assertNotNull(f.inspect())
        }
    }
    @Test fun changedExecutableModesCannotKeepTheProjectionDigest()=runFixture { f ->
        for(relative in listOf("payload/tree/sqruff","payload/bin/sqruff","payload/host-bin/sqruff")) {
            val file=File(f.root,relative)
            for(mode in listOf(420,511,2541)) { // 0644, 0777 and 04755 are all outside the projected 0755.
                Files.setAttribute(file.toPath(),"unix:mode",mode)
                assertNull("mode $mode at $relative",f.inspect())
            }
            Files.setAttribute(file.toPath(),"unix:mode",493);assertNotNull(f.inspect())
        }
    }
    @Test fun cancellationIsNotConvertedToAbsentIdentity()=runFixture { f ->
        coroutineScope { val job=launch(start=CoroutineStart.LAZY){f.inspect();error("canceled inspection returned")};job.cancel();job.start();job.join();assertTrue(job.isCancelled) }
        var checkpoints=0;val file=File(f.root,"payload/tree/sqruff")
        assertTrue(runCatching{LocalUbuntuSqruffLaunchAuthority.measure(file,1024){if(++checkpoints==2)throw CancellationException("fixture")}}.exceptionOrNull() is CancellationException)
    }
}
