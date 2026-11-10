package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Authored text trees and receipts only; inspect never upgrades metadata into native byte proof. */
class PowerShellManagedLaunchIdentityTest {
    private fun sha(value: String) = LspPowerShellLaunchPolicy.sha256(value)
    private fun recipe(runtime: Boolean): LocalUbuntuVendorRecipe {
        val family = if(runtime) LocalUbuntuPowerShellRuntimePolicy.FAMILY else LocalUbuntuPowerShellEditorServicesPolicy.FAMILY
        val definition = requireNotNull(LocalUbuntuVendorCatalog.find(family))
        return if(runtime) LocalUbuntuVendorRecipe(definition,"7.6.7","v7.6.7",LocalUbuntuPowerShellRuntimePolicy.ASSET,LocalUbuntuPowerShellRuntimePolicy.ASSET_URL,LocalUbuntuPowerShellRuntimePolicy.SHA256,LocalUbuntuPowerShellRuntimePolicy.ARTIFACT_BYTES,LocalUbuntuPowerShellRuntimePolicy.RELEASE_URL)
        else LocalUbuntuVendorRecipe(definition,"4.7.0","v4.7.0",LocalUbuntuPowerShellEditorServicesPolicy.ASSET,LocalUbuntuPowerShellEditorServicesPolicy.ASSET_URL,LocalUbuntuPowerShellEditorServicesPolicy.SHA256,LocalUbuntuPowerShellEditorServicesPolicy.ARTIFACT_BYTES,LocalUbuntuPowerShellEditorServicesPolicy.PROVENANCE_URL)
    }
    private data class Fixture(val app:File,val runtime:ManagedPackageRecord,val pses:ManagedPackageRecord,val layer:ManagedPackageRecord) {
        val records get()=listOf(runtime,pses,layer)
        val launch get()="/opt/droide/packages/${pses.familyId}/${pses.version}/arm64-v8a/payload/bin/powershell-editor-services"
        suspend fun inspect(records:List<ManagedPackageRecord> = this.records, selections:Map<String,String> = emptyMap(), discovered:String=launch)=
            LocalUbuntuPowerShellLaunchAuthority.inspect(app.absolutePath,records.single { it.familyId==pses.familyId },records,selections,discovered)
    }
    private suspend fun fixture():Fixture {
        val app=Files.createTempDirectory("powershell-identity-").toFile().canonicalFile
        val layer=ManagedPackageRecord("runtime.ubuntu.layer","fixture",ExecutionScope.LOCAL_LINUX_ARM64.name,File(app,"guest/layer").path,1,
            metadata=mapOf(FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND to FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND,
                FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES to recipe(true).requiredPackages.joinToString(",")))
        suspend fun record(r:LocalUbuntuVendorRecipe,runtime:ManagedPackageRecord?):ManagedPackageRecord {
            val root=LocalPackageInstallJournal.paths(app.path,r.familyId,r.version,LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE).final.apply{mkdirs()}
            val guest="/opt/droide/packages/${r.familyId}/${r.version}/arm64-v8a"
            val files=linkedMapOf(r.entryPoint to "authored entry bytes", "data.txt" to "authored inert module data")
            if(runtime==null)files["createdump"]="authored helper bytes"
            fun writeFile(relative:String,value:String,executable:Boolean=false){File(root,relative).apply{parentFile.mkdirs();writeText(value);Files.setAttribute(toPath(),"unix:mode",if(executable)493 else 420)}}
            files.forEach{(relative,value)->writeFile("payload/tree/$relative",value,relative in LocalUbuntuVendorPolicy.executablePaths(r))}
            writeFile("payload/bin/${r.command}",LocalUbuntuVendorPolicy.guestWrapper(r,"$guest/payload/tree/${r.entryPoint}",guest),true)
            writeFile("payload/host-bin/${r.command}",LocalUbuntuVendorPolicy.hostWrapper("${app.path}/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch","$guest/payload/bin/${r.command}"),true)
            writeFile(LocalUbuntuPowerShellLspHealth.FILE_NAME,LocalUbuntuPowerShellLspHealth.script)
            writeFile(LocalUbuntuPowerShellLaunchAuthority.NOTICE_FILE,LocalUbuntuPowerShellLaunchAuthority.noticeText)
            val activation=LocalPackageInstallJournal.prepare(root)
            val treeHash=sha(files.toSortedMap().entries.joinToString(""){(name,value)->"$name\u0000${if(name in LocalUbuntuVendorPolicy.executablePaths(r))493 else 420}\u0000${sha(value)}\n"})
            val treeManifest=files.toSortedMap().entries.joinToString(""){(name,value)->"${sha(value)}  $name\n"}
            writeFile("TREE_SHA256SUMS",treeManifest);writeFile("TREE_LINKS.tsv","");writeFile("TREE_COMMANDS.tsv","${r.command}\t${r.entryPoint}\n")
            writeFile("RECIPE_LOCK.json",buildJsonObject{put("schema",1);put("provider","LOCAL_UBUNTU_VENDOR");put("familyId",r.familyId);put("version",r.version);put("artifactFormat",r.definition.format.name);put("command",r.command);put("treeEntryPoint",r.entryPoint);put("releaseTag",r.releaseTag);put("assetName",r.assetName);put("assetUrl",r.assetUrl);put("assetSha256",r.assetSha256);put("assetSize",r.assetSize!!);put("provenanceUrl",r.provenanceUrl)}.toString())
            writeFile(LocalUbuntuPowerShellLaunchAuthority.FILE_NAME,LocalUbuntuPowerShellLaunchAuthority.create(r,activation,
                LocalPackageInstallJournal.paths(app.path,r.familyId,r.version,LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE).stage.path,guest,treeManifest,runtime))
            val seal=ReviewedWholeTreeSeal.create(root,r.familyId,120_000,12L*1024*1024*1024)
            return ManagedPackageRecord(r.familyId,r.version,ExecutionScope.LOCAL_LINUX_ARM64.name,root.path,123,
                pathEntries=listOf("$root/payload/host-bin"),commands=mapOf(r.command to "$root/payload/host-bin/${r.command}"),
                dependencies=listOfNotNull(runtime?.let{"${it.familyId}@${it.version}"},"${layer.familyId}@${layer.version}"),
                healthChecks=listOf(ManagedPackageHealthCheck("host-bin/${r.command}",LocalUbuntuVendorPolicy.healthArgs(r))),artifactSha256=r.assetSha256,abi="arm64-v8a",
                metadata=mapOf(LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE,
                    LocalUbuntuVendorPolicy.POLICY_KEY to LocalUbuntuVendorPolicy.POLICY_VERSION,LocalManagedPackageMetadata.ACTIVATION_ID_KEY to activation,
                    LocalManagedPackageMetadata.SEAL_SHA256_KEY to seal,LocalManagedPackageMetadata.TREE_SHA256_KEY to treeHash))
        }
        val runtime=record(recipe(true),null);val pses=record(recipe(false),runtime)
        File(app,"managed/ubuntu-bin/powershell-editor-services").apply{parentFile.mkdirs();writeText("#!/bin/sh\nexec '/opt/droide/packages/${pses.familyId}/${pses.version}/arm64-v8a/payload/bin/powershell-editor-services' \"\$@\"\n");setExecutable(true,false)}
        return Fixture(app,runtime,pses,layer)
    }
    private fun fixture(block:suspend(Fixture)->Unit)=runBlocking<Unit>{val f=fixture();try{block(f)}finally{assertTrue(PathSecurity.deleteTreeNoFollow(f.app))}}
    @Test fun metadataIdentityDoesNotRequireRuntimeAsInteractiveDefault()=fixture{f->
        val first=f.inspect();assertNotNull(first);assertTrue(LspPowerShellLaunchPolicy.matchesIdentity(first!!));assertEquals(f.launch,first.launchExecutable)
        val inactive=f.records.map{if(it==f.runtime)it.copy(active=false)else it};val other=f.inspect(records=inactive);assertNotNull(other);assertNotEquals(first,other)
        assertNotNull(f.inspect(selections=mapOf("runtime.powershell" to "unrelated-interactive-version")))
        assertNull(f.inspect(selections=mapOf(f.pses.familyId to "4.8.0")))
        assertNotNull(f.inspect(discovered="/opt/droide/bin/powershell-editor-services"))
        for(path in listOf("powershell-editor-services","/usr/bin/pwsh",first.runtimeExecutable,"/tmp/powershell-editor-services"))assertNull(f.inspect(discovered=path))
    }
    @Test fun metadataAndWholeTreeProofAreDistinct()=fixture{f->
        val first=f.inspect();assertNotNull(first);LocalUbuntuPowerShellLaunchAuthority.proveTree(f.pses){}
        File(f.pses.installRoot,"payload/tree/data.txt").appendText("changed")
        assertEquals(first,f.inspect()) // Cheap metadata query deliberately does not read this payload.
        assertTrue(runCatching{LocalUbuntuPowerShellLaunchAuthority.proveTree(f.pses){}}.isFailure)
    }
    @Test fun exactSealedInventoryModesLinksAndHardlinksAreChecked()=fixture{f->
        for(record in listOf(f.runtime,f.pses))LocalUbuntuPowerShellLaunchAuthority.proveTree(record){}
        val root=File(f.pses.installRoot);val extra=File(root,"extra").apply{writeText("x")};assertTrue(runCatching{LocalUbuntuPowerShellLaunchAuthority.proveTree(f.pses){}}.isFailure);extra.delete()
        val wrapper=File(root,"payload/bin/powershell-editor-services");Files.setAttribute(wrapper.toPath(),"unix:mode",511);assertTrue(runCatching{LocalUbuntuPowerShellLaunchAuthority.proveTree(f.pses){}}.isFailure);Files.setAttribute(wrapper.toPath(),"unix:mode",493)
        val data=File(root,"payload/tree/data.txt");val link=File(f.app,"alias");Files.createLink(link.toPath(),data.toPath());assertTrue(runCatching{LocalUbuntuPowerShellLaunchAuthority.proveTree(f.pses){}}.isFailure);link.delete()
        val bytes=data.readBytes();data.delete();Files.createSymbolicLink(data.toPath(),File(f.app,"foreign").apply{writeBytes(bytes)}.toPath());assertTrue(runCatching{LocalUbuntuPowerShellLaunchAuthority.proveTree(f.pses){}}.isFailure)
    }
    @Test fun changedRuntimeGenerationAndLayerCannotReuseIdentity()=fixture{f->
        val original=f.inspect();assertNotNull(original)
        val changed=f.runtime.copy(installedAtEpochMs=777);assertNotEquals(original,f.inspect(records=listOf(changed,f.pses,f.layer)))
        assertNull(f.inspect(records=listOf(f.runtime,f.pses.copy(dependencies=listOf("wrong")),f.layer)))
        assertNull(f.inspect(records=listOf(f.runtime,f.pses,f.layer.copy(metadata=emptyMap()))))
        File(f.runtime.installRoot,"DROIDE_ACTIVATION_ID").appendText("x");assertNull(f.inspect())
    }
    @Test fun exactIdentityValidatorRejectsEveryMutableClaim()=fixture{f->
        val id=requireNotNull(f.inspect())
        for(other in listOf(id.copy(version="4.8.0"),id.copy(runtimeVersion="7.6.8"),id.copy(sourcePolicyRevision="generic"),id.copy(publisherArchiveSha256="0".repeat(64)),id.copy(runtimeArchiveSha256="0".repeat(64)),id.copy(discoveredExecutable="/usr/bin/pwsh"),id.copy(launchExecutable="/tmp/editor"),id.copy(runtimeExecutable="/usr/bin/pwsh"),id.copy(receiptSha256=""),id.copy(activationIdentity="fake")))assertFalse(LspPowerShellLaunchPolicy.matchesIdentity(other))
    }
    @Test fun cancellationIsPreservedInsideByteProof()=fixture{f->
        var checks=0;assertTrue(runCatching{LocalUbuntuPowerShellLaunchAuthority.proveTree(f.pses){if(++checks==4)throw CancellationException("cancel")}}.exceptionOrNull() is CancellationException)
    }
}
